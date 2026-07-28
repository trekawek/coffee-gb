package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.FileAlreadyExistsException
import java.nio.file.StandardOpenOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bounded machine-StateFile repository. The state file is authoritative; optional UI metadata is
 * committed separately and ignored whenever its recorded byte count or SHA-256 is stale.
 *
 * Repository instances in this JVM share path-striped ref and named-namespace locks, so a state and
 * its sidecar cannot interleave and the named-state capacity check is atomic across instances that
 * use the same normalized path. The configured game root remains the caller-selected trust
 * boundary; existing descendants are refused when they are symlinks, but hostile concurrent
 * filesystem replacement requires stronger directory-handle isolation than portable NIO provides.
 * A case-insensitive filesystem alias is accepted only when its on-disk UUID spelling is canonical.
 *
 * This service is synchronous by design. Phase-4 controller integration owns worker scheduling.
 */
class StateRepository(
    val layout: StateStorageLayout,
    private val persistence: AtomicFileWriter = AtomicFileWriter.system(),
) {
  /**
   * Validates and atomically commits one portable machine state. Metadata failure never rolls back
   * or invalidates the authoritative state file.
   */
  fun save(
      ref: StateRef,
      encodedState: ByteArray,
      requestedMetadata: StateSaveMetadata,
  ): StateSaveResult =
      saveWithThumbnail(ref, encodedState, requestedMetadata, null).state

  /**
   * Saves a state and its optional deterministic thumbnail without making the image authoritative.
   * A thumbnail failure still commits a loadable state and metadata without a thumbnail reference.
   */
  fun saveWithThumbnail(
      ref: StateRef,
      encodedState: ByteArray,
      requestedMetadata: StateSaveMetadata,
      thumbnailPng: ByteArray?,
  ): StateAssetSaveResult =
      if (ref is StateRef.Named) {
        namedNamespaceLock().withLock {
          saveLocked(ref, encodedState, requestedMetadata, thumbnailPng)
        }
      } else {
        saveLocked(ref, encodedState, requestedMetadata, thumbnailPng)
      }

  private fun saveLocked(
      ref: StateRef,
      encodedState: ByteArray,
      requestedMetadata: StateSaveMetadata,
      thumbnailPng: ByteArray?,
  ): StateAssetSaveResult =
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
        val ownedThumbnail = thumbnailPng?.clone()
        val suppliedThumbnailSha256 =
            ownedThumbnail?.let(StateMetadataCodec::sha256)
        if (ownedThumbnail != null) {
          if (ownedThumbnail.size > StatePngCodec.MAX_PNG_BYTES) {
            throw IOException(
                "Thumbnail exceeds the ${StatePngCodec.MAX_PNG_BYTES}-byte PNG limit")
          }
          val decodedThumbnail = StatePngCodec.decode(ownedThumbnail)
          require(
              decodedThumbnail.width == StateImage.THUMBNAIL_WIDTH &&
                  decodedThumbnail.height == StateImage.THUMBNAIL_HEIGHT) {
            "State thumbnail must be ${StateImage.THUMBNAIL_WIDTH} x " +
                StateImage.THUMBNAIL_HEIGHT
          }
          if (requestedMetadata.thumbnailSha256 != null &&
              requestedMetadata.thumbnailSha256 != suppliedThumbnailSha256) {
            throw IllegalArgumentException(
                "Requested thumbnail hash does not match the supplied PNG")
          }
        } else if (requestedMetadata.thumbnailSha256 != null) {
          throw IllegalArgumentException(
              "Thumbnail hash was supplied without thumbnail PNG bytes")
        }

        val statePath = layout.stateFile(ref)
        if (ref is StateRef.Named) {
          enforceNamedCapacity(ref, statePath)
        }
        ensureSafeParent(statePath)
        val stateRecovery = persistence.recoverWithReport(statePath)
        persistence.write(statePath, ownedBytes)

        var thumbnailCommitted = false
        var thumbnailFailure: String? = null
        var thumbnailRecovery = AtomicFileWriter.RecoveryReport.NONE
        var thumbnailSha256: String? = null
        if (ownedThumbnail != null) {
          val computedThumbnailSha = checkNotNull(suppliedThumbnailSha256)
          val thumbnailPath = layout.thumbnailFile(ref, hash)
          try {
            ensureSafeParent(thumbnailPath)
            thumbnailRecovery = persistence.recoverWithReport(thumbnailPath)
            persistence.write(thumbnailPath, ownedThumbnail)
            thumbnailCommitted = true
            thumbnailSha256 = computedThumbnailSha
          } catch (failure: IOException) {
            thumbnailFailure = failure.message ?: failure.javaClass.simpleName
          }
        }

        val metadata =
            StateMetadata(
                ref = ref,
                label = requestedMetadata.label,
                savedAt = requestedMetadata.savedAt,
                playDurationNanos = requestedMetadata.playDurationNanos,
                stateBytes = ownedBytes.size,
                stateSha256 = hash,
                thumbnailSha256 = thumbnailSha256,
            )
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
        if (metadataFailure == null) {
          cleanupObsoleteThumbnails(ref, hash.takeIf { thumbnailCommitted })
        }
        StateAssetSaveResult(
            StateSaveResult(
                ref,
                hash,
                metadata,
                metadataFailure == null,
                metadataFailure,
                StateRecovery(stateRecovery, metadataRecovery),
            ),
            thumbnailCommitted,
            thumbnailFailure,
            thumbnailRecovery,
        )
      }

  /** Fully decodes a detached state and its trusted matching metadata without mutating a machine. */
  fun read(ref: StateRef): StateReadResult =
      lock(ref).withLock {
        val raw = readRaw(ref) ?: throw NoSuchFileException(layout.stateFile(ref).toString())
        decodeRaw(raw)
      }

  /**
   * Reads the exact hash-bound thumbnail referenced by trusted matching metadata. A missing image
   * is represented by null; a changed or oversized image is rejected instead of displayed.
   */
  fun readThumbnail(
      ref: StateRef,
      stateSha256: String,
      thumbnailSha256: String,
  ): StateThumbnailReadResult =
      lock(ref).withLock {
        require(StateStorageLayout.isSha256(stateSha256)) { "Invalid state thumbnail key" }
        require(StateStorageLayout.isSha256(thumbnailSha256)) {
          "Invalid expected thumbnail hash"
        }
        val read =
            readOptionalBytes(
                layout.thumbnailFile(ref, stateSha256),
                StatePngCodec.MAX_PNG_BYTES,
            )
        val bytes = read.bytes
        if (bytes != null && StateMetadataCodec.sha256(bytes) != thumbnailSha256) {
          throw IOException("State thumbnail does not match its metadata hash")
        }
        StateThumbnailReadResult(bytes, read.recovery)
      }

  /**
   * Removes one user-selected state and its bounded sidecars. Recovery runs first so a stale
   * backup cannot resurrect a state after deletion.
   */
  fun delete(ref: StateRef): StateDeleteResult =
      if (ref is StateRef.Named) {
        namedNamespaceLock().withLock { deleteLocked(ref) }
      } else {
        deleteLocked(ref)
      }

  private fun deleteLocked(ref: StateRef): StateDeleteResult =
      lock(ref).withLock {
        val directory = layout.directory(ref)
        ensureSafeParent(layout.stateFile(ref))
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
          return@withLock StateDeleteResult(
              ref,
              0,
              StateRecovery(
                  AtomicFileWriter.RecoveryReport.NONE,
                  AtomicFileWriter.RecoveryReport.NONE,
              ),
          )
        }
        if (Files.isSymbolicLink(directory) ||
            !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
          throw IOException("State storage entry is not a safe directory")
        }

        val statePath = layout.stateFile(ref)
        val metadataPath = layout.metadataFile(ref)
        val stateRecovery = persistence.recoverWithReport(statePath)
        val metadataRecovery = persistence.recoverWithReport(metadataPath)
        var deleted = deleteRegularArtifact(statePath)
        deleted += deleteRegularArtifact(metadataPath)

        var entries = 0
        Files.newDirectoryStream(directory).use { stream ->
          stream.forEach { child ->
            entries++
            if (entries > MAX_STATE_DIRECTORY_ENTRIES) {
              throw IOException(
                  "State entry contains more than $MAX_STATE_DIRECTORY_ENTRIES artifacts")
            }
            if (THUMBNAIL_FILE.matches(child.fileName.toString())) {
              deleted += deleteRegularArtifact(child)
            }
          }
        }
        runCatching { Files.deleteIfExists(directory) }
        StateDeleteResult(
            ref,
            deleted,
            StateRecovery(stateRecovery, metadataRecovery),
        )
      }

  /** Exports the authoritative encoded StateFile to a new destination without replacement. */
  fun export(ref: StateRef, destination: Path): StateExportResult {
    val raw =
        lock(ref).withLock {
          readRaw(ref) ?: throw NoSuchFileException(layout.stateFile(ref).toString())
        }
    val written =
        try {
          ExclusiveFileWriter.write(destination, raw.bytes)
        } catch (failure: FileAlreadyExistsException) {
          throw IOException("Export destination already exists: ${destination.fileName}", failure)
        }
    return StateExportResult(
        ref,
        written.path,
        StateMetadataCodec.sha256(raw.bytes),
        written.recovery,
    )
  }

  /**
   * Builds a bounded deterministic catalog. Supplying [targetIdentity] additionally classifies
   * wrong-ROM and wrong-profile entries without live-state access.
   */
  fun catalog(targetIdentity: MachineIdentity? = null): StateCatalog {
    val slots =
        (StateRef.MIN_SLOT..StateRef.MAX_SLOT).mapNotNull { index ->
          catalogEntry(StateRef.Slot(index), targetIdentity)
        }
    val named =
        try {
          val refs = namedNamespaceLock().withLock(::discoverNamedRefs)
          catalogNamedEntries(refs.refs, targetIdentity)
        } catch (failure: IOException) {
          NamedCatalog(
              emptyList(),
              truncated = true,
              error = failure.message ?: failure.javaClass.simpleName,
          )
        }
    val autosave = catalogEntry(StateRef.Autosave, targetIdentity)
    return StateCatalog(
        buildList {
          addAll(slots)
          addAll(named.entries)
          autosave?.let(::add)
        },
        named.truncated,
        named.error,
    )
  }

  private fun catalogNamedEntries(
      refs: List<StateRef.Named>,
      targetIdentity: MachineIdentity?,
  ): NamedCatalog {
    val entries = ArrayList<StateCatalogEntry>()
    for (ref in refs) {
      val entry = catalogEntry(ref, targetIdentity) ?: continue
      if (entries.size == MAX_NAMED_STATES) {
        return NamedCatalog(entries, truncated = true, error = null)
      }
      entries += entry
    }
    return NamedCatalog(entries, truncated = false, error = null)
  }

  private fun catalogEntry(
      ref: StateRef,
      targetIdentity: MachineIdentity?,
  ): StateCatalogEntry? =
      lock(ref).withLock {
        val raw =
            try {
              readRaw(ref)
            } catch (failure: IOException) {
              return@withLock StateCatalogEntry(
                  ref,
                  StateCatalogStatus.IO_ERROR,
                  diagnostic(failure.message ?: failure.javaClass.simpleName),
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
          undecodableCatalogEntry(raw, failure)
        } catch (failure: IOException) {
          StateCatalogEntry(
              ref,
              StateCatalogStatus.IO_ERROR,
              diagnostic(failure.message ?: failure.javaClass.simpleName),
              null,
              StateMetadataCodec.sha256(raw.bytes),
              null,
              null,
              null,
              StateRecovery(raw.recovery, AtomicFileWriter.RecoveryReport.NONE),
          )
        }
      }

  private fun undecodableCatalogEntry(
      raw: RawState,
      failure: StateDecodeException,
  ): StateCatalogEntry {
    val hash = StateMetadataCodec.sha256(raw.bytes)
    val metadataRead = readMetadata(raw.ref, raw.bytes.size, hash)
    val status = catalogStatus(failure.reason)
    val detail = diagnostic("${failure.reason}: ${failure.message ?: "StateFile is invalid"}")
    val compatibility =
        if (status == StateCatalogStatus.INCOMPATIBLE) {
          StateCompatibilityResult(
              StateCompatibilityStatus.INCOMPATIBLE,
              failure.reason,
              detail,
          )
        } else {
          null
        }
    return StateCatalogEntry(
        raw.ref,
        status,
        detail,
        null,
        hash,
        metadataRead.metadata,
        metadataRead.warning,
        compatibility,
        StateRecovery(raw.recovery, metadataRead.recovery),
    )
  }

  private fun catalogStatus(reason: StateDecodeReason): StateCatalogStatus =
      when (reason) {
        StateDecodeReason.UNSUPPORTED_FORMAT_VERSION,
        StateDecodeReason.UNSUPPORTED_SECTION_VERSION,
        StateDecodeReason.UNSUPPORTED_FLAGS,
        StateDecodeReason.UNKNOWN_REQUIRED_SECTION -> StateCatalogStatus.INCOMPATIBLE
        else -> StateCatalogStatus.CORRUPT
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
                  diagnostic(failure.message ?: failure.javaClass.simpleName),
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
                  diagnostic(failure.message ?: "State metadata is malformed"),
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

  private fun enforceNamedCapacity(ref: StateRef.Named, statePath: Path) {
    val parentExists = Files.exists(statePath.parent, LinkOption.NOFOLLOW_LINKS)
    val named = discoverNamedRefs()
    if (parentExists && ref !in named.refs) {
      throw IOException(
          "Named-state storage does not use the canonical UUID spelling for ${ref.id}")
    }
    if (stateArtifactExists(statePath)) return
    if (!parentExists && named.rawEntries >= MAX_CATALOG_DIRECTORY_ENTRIES) {
      throw StateRepositoryCapacityException(
          "Named-state directory may contain at most " +
              "$MAX_CATALOG_DIRECTORY_ENTRIES raw entries")
    }
    var occupied = 0
    for (existing in named.refs) {
      if (existing == ref) continue
      if (stateArtifactExists(layout.stateFile(existing))) {
        occupied++
        if (occupied >= MAX_NAMED_STATES) {
          throw StateRepositoryCapacityException(
              "At most $MAX_NAMED_STATES named states may be stored")
        }
      }
    }
  }

  /**
   * Presence and recovery failures both occupy capacity: either represents a catalog-manageable
   * artifact and must not let a damaged namespace bypass the named-state bound.
   */
  private fun stateArtifactExists(path: Path): Boolean {
    ensureSafeParent(path)
    if (!Files.exists(path.parent, LinkOption.NOFOLLOW_LINKS)) return false
    return try {
      persistence.existsWithRecovery(path).value()
    } catch (_: IOException) {
      true
    }
  }

  private fun cleanupObsoleteThumbnails(ref: StateRef, retainedStateSha256: String?) {
    val directory = layout.directory(ref)
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) ||
        Files.isSymbolicLink(directory)) {
      return
    }
    var entries = 0
    try {
      Files.newDirectoryStream(directory).use { stream ->
        stream.forEach { child ->
          entries++
          if (entries > MAX_STATE_DIRECTORY_ENTRIES) {
            throw IOException(
                "State entry contains more than $MAX_STATE_DIRECTORY_ENTRIES artifacts")
          }
          val match = THUMBNAIL_FILE.matchEntire(child.fileName.toString()) ?: return@forEach
          if (match.groupValues[1] != retainedStateSha256) {
            deleteRegularArtifact(child)
          }
        }
      }
    } catch (_: IOException) {
      // Cleanup is best effort after both authoritative state and metadata are committed.
    }
  }

  private fun deleteRegularArtifact(path: Path): Int {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return 0
    if (Files.isSymbolicLink(path) ||
        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw IOException("State artifact is not a regular file: ${path.fileName}")
    }
    return if (Files.deleteIfExists(path)) 1 else 0
  }

  private fun discoverNamedRefs(): NamedRefs {
    val directory = layout.namedDirectory
    ensureSafeParent(directory.resolve(StateStorageLayout.STATE_FILE))
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      return NamedRefs(emptyList(), 0)
    }
    if (Files.isSymbolicLink(directory) ||
        !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw IOException("Named-state storage is not a directory")
    }
    val refs = ArrayList<StateRef.Named>()
    var entries = 0
    try {
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
    } catch (failure: DirectoryIteratorException) {
      throw failure.cause ?: IOException("Unable to enumerate named-state storage", failure)
    }
    refs.sortBy { it.id.toString() }
    return NamedRefs(refs, entries)
  }

  /** Refuses traversal through symlinked or non-directory components, including the game root. */
  private fun ensureSafeParent(target: Path) {
    val normalized = target.toAbsolutePath().normalize()
    require(normalized.startsWith(layout.gameDirectory)) {
      "State repository target escapes the configured game directory"
    }
    val parent = requireNotNull(normalized.parent)
    var cursor = parent.root
    parent.forEach { component ->
      cursor = if (cursor == null) component else cursor.resolve(component)
      if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) &&
          (Files.isSymbolicLink(cursor) ||
              !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS))) {
        throw IOException(
            "State repository path component is not a safe directory: ${cursor.fileName}")
      }
    }
  }

  private fun diagnostic(value: String): String =
      StateDiagnosticRedactor.redact(value, listOf(layout.gameDirectory), MAX_DIAGNOSTIC_CHARS)

  private fun lock(ref: StateRef): ReentrantLock =
      sharedLock(layout.stateFile(ref), REF_LOCKS)

  private fun namedNamespaceLock(): ReentrantLock =
      sharedLock(layout.namedDirectory, NAMED_NAMESPACE_LOCKS)

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

  private data class NamedCatalog(
      val entries: List<StateCatalogEntry>,
      val truncated: Boolean,
      val error: String?,
  )

  private data class NamedRefs(
      val refs: List<StateRef.Named>,
      val rawEntries: Int,
  )

  companion object {
    const val MAX_NAMED_STATES = 128
    const val MAX_CATALOG_DIRECTORY_ENTRIES = 512
    const val MAX_STATE_DIRECTORY_ENTRIES = 32
    private const val MAX_DIAGNOSTIC_CHARS = 512
    private const val LOCK_STRIPES = 64
    private const val DEFAULT_BUFFER_BYTES = 8192
    private val REF_LOCKS = Array(LOCK_STRIPES) { ReentrantLock() }
    private val NAMED_NAMESPACE_LOCKS = Array(LOCK_STRIPES) { ReentrantLock() }
    private val THUMBNAIL_FILE = Regex("thumbnail-([0-9a-f]{64})\\.png")

    private fun sharedLock(path: Path, locks: Array<ReentrantLock>): ReentrantLock {
      val normalized = path.toAbsolutePath().normalize()
      return locks[(normalized.hashCode() and Int.MAX_VALUE) % locks.size]
    }
  }
}
