package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bounded machine-StateFile repository. The state file is authoritative; optional UI metadata is
 * committed separately and ignored whenever its recorded byte count or SHA-256 is stale.
 *
 * This service is synchronous by design. Phase-4 controller integration owns worker scheduling.
 */
class StateRepository(
    val layout: StateStorageLayout,
    private val persistence: AtomicFileWriter = AtomicFileWriter.system(),
) {
  private val locks = Array(LOCK_STRIPES) { ReentrantLock() }

  /**
   * Validates and atomically commits one portable machine state. Metadata failure never rolls back
   * or invalidates the authoritative state file.
   */
  fun save(
      ref: StateRef,
      encodedState: ByteArray,
      requestedMetadata: StateSaveMetadata,
  ): StateSaveResult =
      lock(ref).withLock {
        if (encodedState.size > StateLimits.PORTABLE_MAX_FILE_BYTES) {
          throw StateDecodeException(
              StateDecodeReason.LIMIT_EXCEEDED,
              "Portable state ${encodedState.size} exceeds " +
                  "${StateLimits.PORTABLE_MAX_FILE_BYTES} bytes",
          )
        }
        val ownedBytes = encodedState.clone()
        val decoded = StateCodec.decode(ownedBytes)
        if (decoded.root.kind != StateRootKind.MACHINE) {
          throw StateDecodeException(
              StateDecodeReason.TARGET_STATE_MISMATCH,
              "Local state repository accepts machine-root StateFiles only",
          )
        }
        val hash = StateMetadataCodec.sha256(ownedBytes)
        val metadata =
            StateMetadata(
                ref = ref,
                label = requestedMetadata.label,
                savedAt = requestedMetadata.savedAt,
                playDurationNanos = requestedMetadata.playDurationNanos,
                stateBytes = ownedBytes.size,
                stateSha256 = hash,
                thumbnailSha256 = requestedMetadata.thumbnailSha256,
            )

        val statePath = layout.stateFile(ref)
        ensureSafeParent(statePath)
        val stateRecovery = persistence.recoverWithReport(statePath)
        persistence.write(statePath, ownedBytes)

        val metadataPath = layout.metadataFile(ref)
        var metadataRecovery = AtomicFileWriter.RecoveryReport.NONE
        var metadataFailure: String? = null
        try {
          ensureSafeParent(metadataPath)
          metadataRecovery = persistence.recoverWithReport(metadataPath)
          persistence.write(metadataPath, StateMetadataCodec.encode(metadata))
        } catch (failure: IOException) {
          metadataFailure = failure.message ?: failure.javaClass.simpleName
        }
        StateSaveResult(
            ref,
            hash,
            metadata,
            metadataFailure == null,
            metadataFailure,
            StateRecovery(stateRecovery, metadataRecovery),
        )
      }

  /** Fully decodes a detached state and its trusted matching metadata without mutating a machine. */
  fun read(ref: StateRef): StateReadResult =
      lock(ref).withLock {
        val raw = readRaw(ref) ?: throw NoSuchFileException(layout.stateFile(ref).toString())
        decodeRaw(raw)
      }

  /**
   * Builds a bounded deterministic catalog. Supplying [targetIdentity] additionally classifies
   * wrong-ROM and wrong-profile entries without live-state access.
   */
  fun catalog(targetIdentity: MachineIdentity? = null): StateCatalog {
    val named = discoverNamedRefs()
    val refs =
        buildList {
          (StateRef.MIN_SLOT..StateRef.MAX_SLOT).forEach { add(StateRef.Slot(it)) }
          addAll(named.refs)
          add(StateRef.Autosave)
        }
    val entries =
        refs.mapNotNull { ref ->
          lock(ref).withLock {
            val raw =
                try {
                  readRaw(ref)
                } catch (failure: IOException) {
                  return@withLock StateCatalogEntry(
                      ref,
                      StateCatalogStatus.IO_ERROR,
                      failure.message ?: failure.javaClass.simpleName,
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                  )
                } ?: return@withLock null
            try {
              val read = decodeRaw(raw)
              val compatibility =
                  if (read.state.root.kind != StateRootKind.MACHINE) {
                    StateCompatibilityResult(
                        StateCompatibilityStatus.ROOT_MISMATCH,
                        StateDecodeReason.TARGET_STATE_MISMATCH,
                        "StateFile root ${read.state.root.kind} is not a machine",
                    )
                  } else {
                    targetIdentity?.let {
                      StateCodec.classifyCompatibility(
                          read.state,
                          StateRootKind.MACHINE,
                          listOf(StateIdentityEntry(0, it)),
                      )
                    }
                  }
              StateCatalogEntry(
                  ref,
                  if (compatibility == null || compatibility.isCompatible) {
                    StateCatalogStatus.AVAILABLE
                  } else {
                    StateCatalogStatus.INCOMPATIBLE
                  },
                  compatibility?.detail,
                  read.inspection,
                  read.stateSha256,
                  read.metadata,
                  read.metadataWarning,
                  compatibility,
                  read.recovery,
              )
            } catch (failure: StateDecodeException) {
              StateCatalogEntry(
                  ref,
                  StateCatalogStatus.CORRUPT,
                  "${failure.reason}: ${failure.message ?: "StateFile is invalid"}",
                  null,
                  StateMetadataCodec.sha256(raw.bytes),
                  null,
                  null,
                  null,
                  StateRecovery(raw.recovery, AtomicFileWriter.RecoveryReport.NONE),
              )
            } catch (failure: IOException) {
              StateCatalogEntry(
                  ref,
                  StateCatalogStatus.IO_ERROR,
                  failure.message ?: failure.javaClass.simpleName,
                  null,
                  StateMetadataCodec.sha256(raw.bytes),
                  null,
                  null,
                  null,
                  StateRecovery(raw.recovery, AtomicFileWriter.RecoveryReport.NONE),
              )
            }
          }
        }
    return StateCatalog(entries, named.truncated)
  }

  private fun decodeRaw(raw: RawState): StateReadResult {
    val state = StateCodec.decode(raw.bytes)
    val inspection = StateCodec.inspect(raw.bytes)
    val hash = StateMetadataCodec.sha256(raw.bytes)
    val metadataRead = readMetadata(raw.ref, raw.bytes.size, hash)
    return StateReadResult(
        raw.ref,
        state,
        inspection,
        hash,
        metadataRead.metadata,
        metadataRead.warning,
        StateRecovery(raw.recovery, metadataRead.recovery),
    )
  }

  private fun readMetadata(
      ref: StateRef,
      stateBytes: Int,
      stateSha256: String,
  ): MetadataRead {
    val read =
        try {
          readOptionalBytes(layout.metadataFile(ref), StateMetadataCodec.MAX_METADATA_BYTES)
        } catch (failure: IOException) {
          return MetadataRead(
              null,
              StateMetadataWarning(
                  StateMetadataWarningReason.UNREADABLE,
                  failure.message ?: failure.javaClass.simpleName,
              ),
              AtomicFileWriter.RecoveryReport.NONE,
          )
        }
    val bytes = read.bytes ?: return MetadataRead(null, null, read.recovery)
    val metadata =
        try {
          StateMetadataCodec.decode(bytes)
        } catch (failure: StateMetadataException) {
          return MetadataRead(
              null,
              StateMetadataWarning(
                  StateMetadataWarningReason.CORRUPT,
                  failure.message ?: "State metadata is malformed",
              ),
              read.recovery,
          )
        }
    if (metadata.ref != ref) {
      return MetadataRead(
          null,
          StateMetadataWarning(
              StateMetadataWarningReason.REFERENCE_MISMATCH,
              "State metadata belongs to ${metadata.ref.storageKey()}, not ${ref.storageKey()}",
          ),
          read.recovery,
      )
    }
    if (metadata.stateBytes != stateBytes || metadata.stateSha256 != stateSha256) {
      return MetadataRead(
          null,
          StateMetadataWarning(
              StateMetadataWarningReason.STATE_HASH_MISMATCH,
              "State metadata does not match the authoritative StateFile bytes",
          ),
          read.recovery,
      )
    }
    return MetadataRead(metadata, null, read.recovery)
  }

  private fun readRaw(ref: StateRef): RawState? {
    val read = readOptionalBytes(layout.stateFile(ref), StateLimits.PORTABLE_MAX_FILE_BYTES)
    return read.bytes?.let { RawState(ref, it, read.recovery) }
  }

  private fun readOptionalBytes(path: Path, maximumBytes: Int): OptionalBytes {
    ensureSafeParent(path)
    if (!Files.exists(path.parent, LinkOption.NOFOLLOW_LINKS)) {
      return OptionalBytes(null, AtomicFileWriter.RecoveryReport.NONE)
    }
    val result =
        persistence.readWithRecovery(path) { recovered ->
          if (!Files.exists(recovered, LinkOption.NOFOLLOW_LINKS)) {
            null
          } else {
            readBoundedRegularFile(recovered, maximumBytes)
          }
        }
    return OptionalBytes(result.value(), result.recovery())
  }

  private fun readBoundedRegularFile(path: Path, maximumBytes: Int): ByteArray {
    if (Files.isSymbolicLink(path) ||
        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw IOException("State repository artifact is not a regular file: ${path.fileName}")
    }
    val output = ByteArrayOutputStream(minOf(DEFAULT_BUFFER_BYTES, maximumBytes))
    Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
      val scratch = ByteArray(DEFAULT_BUFFER_BYTES)
      var total = 0
      while (true) {
        val requested = minOf(scratch.size, maximumBytes - total + 1)
        val count = input.read(scratch, 0, requested)
        if (count < 0) break
        if (count == 0) continue
        if (count > maximumBytes - total) {
          throw IOException(
              "Repository artifact ${path.fileName} exceeds the $maximumBytes-byte limit")
        }
        output.write(scratch, 0, count)
        total += count
      }
    }
    return output.toByteArray()
  }

  private fun discoverNamedRefs(): NamedRefs {
    val directory = layout.namedDirectory
    ensureSafeParent(directory.resolve(StateStorageLayout.STATE_FILE))
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      return NamedRefs(emptyList(), false)
    }
    if (Files.isSymbolicLink(directory) ||
        !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw IOException("Named-state storage is not a directory")
    }
    val refs = ArrayList<StateRef.Named>()
    var entries = 0
    Files.newDirectoryStream(directory).use { stream: DirectoryStream<Path> ->
      stream.forEach { child ->
        entries++
        if (entries > MAX_CATALOG_DIRECTORY_ENTRIES) {
          throw IOException(
              "Named-state directory contains more than $MAX_CATALOG_DIRECTORY_ENTRIES entries")
        }
        if (!Files.isSymbolicLink(child) &&
            Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
          layout.parseNamedDirectoryName(child.fileName.toString())?.let(refs::add)
        }
      }
    }
    refs.sortBy { it.id.toString() }
    return NamedRefs(refs.take(MAX_NAMED_STATES), refs.size > MAX_NAMED_STATES)
  }

  /**
   * Refuses symlinked or non-directory components created below the configured game root. The game
   * root itself is user-selected and therefore acts as the trust boundary.
   */
  private fun ensureSafeParent(target: Path) {
    val normalized = target.toAbsolutePath().normalize()
    require(normalized.startsWith(layout.gameDirectory)) {
      "State repository target escapes the configured game directory"
    }
    val parent = requireNotNull(normalized.parent)
    var cursor = layout.gameDirectory
    layout.gameDirectory.relativize(parent).forEach { component ->
      cursor = cursor.resolve(component)
      if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) &&
          (Files.isSymbolicLink(cursor) ||
              !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))) {
        throw IOException(
            "State repository path component is not a safe directory: ${cursor.fileName}")
      }
    }
  }

  private fun lock(ref: StateRef): ReentrantLock =
      locks[(ref.storageKey().hashCode() and Int.MAX_VALUE) % locks.size]

  private data class OptionalBytes(
      val bytes: ByteArray?,
      val recovery: AtomicFileWriter.RecoveryReport,
  )

  private data class RawState(
      val ref: StateRef,
      val bytes: ByteArray,
      val recovery: AtomicFileWriter.RecoveryReport,
  )

  private data class MetadataRead(
      val metadata: StateMetadata?,
      val warning: StateMetadataWarning?,
      val recovery: AtomicFileWriter.RecoveryReport,
  )

  private data class NamedRefs(
      val refs: List<StateRef.Named>,
      val truncated: Boolean,
  )

  companion object {
    const val MAX_NAMED_STATES = 128
    const val MAX_CATALOG_DIRECTORY_ENTRIES = 512
    private const val LOCK_STRIPES = 64
    private const val DEFAULT_BUFFER_BYTES = 8192
  }
}
