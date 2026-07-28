package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.state.DetachedStateAdapter
import eu.rekawek.coffeegb.controller.state.MachineIdentity
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateCompression
import eu.rekawek.coffeegb.controller.state.StateDecodeException
import eu.rekawek.coffeegb.controller.state.StateDecodeReason
import eu.rekawek.coffeegb.controller.state.StateIdentity
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory

/** The two unambiguous local snapshot prefixes admitted by [SnapshotManager]. */
enum class SnapshotFileFormat {
  PORTABLE,
  LEGACY_JAVA,
}

/**
 * Local-only policy for the bounded legacy importer.
 *
 * Production preserves an accepted historical file by default. The opt-in policy rewrites it
 * only after its state has been validated and committed successfully. The replacement itself is
 * committed through the same crash-recoverable writer as ordinary portable saves.
 */
enum class LegacySnapshotMigrationPolicy {
  PRESERVE,
  REWRITE_AFTER_SUCCESS,
}

/**
 * Actionable local snapshot failure. Portable failures preserve their stable codec reason.
 *
 * [sourceIdentity] is present only when bounded portable inspection safely decoded it; historical
 * Java snapshots did not carry a ROM hash or hardware profile.
 */
class SnapshotLoadException(
    val format: SnapshotFileFormat?,
    val stateDecodeReason: StateDecodeReason?,
    val sourceIdentity: MachineIdentity?,
    val targetIdentity: MachineIdentity,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal data class SnapshotReadLimits(
    val portableBytes: Int,
    val legacyBytes: Int,
) {
  init {
    require(portableBytes >= SNAPSHOT_PREFIX_BYTES)
    require(legacyBytes >= SNAPSHOT_PREFIX_BYTES)
  }

  companion object {
    val DEFAULT =
        SnapshotReadLimits(
            StateLimits.PORTABLE_MAX_FILE_BYTES,
            StateLimits.GAME_SNAPSHOT.decodedBytes,
        )
  }
}

internal data class SnapshotFileBytes(
    val format: SnapshotFileFormat,
    val bytes: ByteArray,
)

internal class SnapshotReadException(
    val format: SnapshotFileFormat?,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Prefix-first bounded reader. Its streaming count, not a racy File.length observation, decides
 * whether the file is admitted. Capacity growth is explicitly capped before every allocation.
 */
internal object SnapshotFileReader {
  private val PORTABLE_MAGIC =
      byteArrayOf(
          'C'.code.toByte(),
          'G'.code.toByte(),
          'B'.code.toByte(),
          'S'.code.toByte(),
      )
  private val LEGACY_MAGIC = byteArrayOf(0xac.toByte(), 0xed.toByte(), 0x00, 0x05)

  fun read(
      file: File,
      limits: SnapshotReadLimits = SnapshotReadLimits.DEFAULT,
  ): SnapshotFileBytes =
      try {
        Files.newInputStream(file.toPath()).use { read(it, limits) }
      } catch (failure: SnapshotReadException) {
        throw failure
      } catch (failure: IOException) {
        throw SnapshotReadException(null, "Snapshot file could not be read", failure)
      }

  fun read(input: InputStream, limits: SnapshotReadLimits): SnapshotFileBytes {
    val prefix = readPrefix(input)
    val format =
        when {
          prefix.contentEquals(PORTABLE_MAGIC) -> SnapshotFileFormat.PORTABLE
          prefix.contentEquals(LEGACY_MAGIC) -> SnapshotFileFormat.LEGACY_JAVA
          else ->
              throw SnapshotReadException(
                  null,
                  "Unknown snapshot format prefix ${prefix.hex()}; expected CGBS or AC ED 00 05",
              )
        }
    val limit =
        when (format) {
          SnapshotFileFormat.PORTABLE -> limits.portableBytes
          SnapshotFileFormat.LEGACY_JAVA -> limits.legacyBytes
        }
    return try {
      SnapshotFileBytes(format, readBounded(input, prefix, limit, format))
    } catch (failure: SnapshotReadException) {
      throw failure
    } catch (failure: IOException) {
      throw SnapshotReadException(format, "Snapshot file could not be read", failure)
    }
  }

  private fun readPrefix(input: InputStream): ByteArray {
    val prefix = ByteArray(SNAPSHOT_PREFIX_BYTES)
    var count = 0
    try {
      while (count < prefix.size) {
        val read = input.read(prefix, count, prefix.size - count)
        if (read == -1) break
        if (read == 0) continue
        count += read
      }
    } catch (failure: IOException) {
      throw SnapshotReadException(null, "Snapshot prefix could not be read", failure)
    }
    if (count != prefix.size) {
      throw SnapshotReadException(
          null,
          "Unknown snapshot format: expected a four-byte CGBS or AC ED 00 05 prefix, found $count bytes",
      )
    }
    return prefix
  }

  private fun readBounded(
      input: InputStream,
      prefix: ByteArray,
      limit: Int,
      format: SnapshotFileFormat,
  ): ByteArray {
    val output = BoundedBytes(limit)
    output.append(prefix, 0, prefix.size)
    val scratch = ByteArray(DEFAULT_READ_BUFFER_BYTES.coerceAtMost(limit))
    while (true) {
      val remaining = limit - output.size
      val requested = scratch.size.coerceAtMost(remaining + 1)
      val count = input.read(scratch, 0, requested)
      if (count == -1) break
      if (count == 0) continue
      if (count > remaining) {
        throw SnapshotReadException(
            format,
            "${format.description()} snapshot exceeds the $limit-byte file limit",
        )
      }
      output.append(scratch, 0, count)
    }
    return output.toByteArray()
  }

  private class BoundedBytes(private val limit: Int) {
    private var buffer = ByteArray(DEFAULT_READ_BUFFER_BYTES.coerceAtMost(limit))
    var size: Int = 0
      private set

    fun append(source: ByteArray, offset: Int, length: Int) {
      if (length < 0 || length > limit - size) {
        throw IllegalArgumentException("Snapshot byte accumulation exceeds its limit")
      }
      val required = size + length
      ensureCapacity(required)
      source.copyInto(buffer, size, offset, offset + length)
      size = required
    }

    fun toByteArray(): ByteArray = if (size == buffer.size) buffer else buffer.copyOf(size)

    private fun ensureCapacity(required: Int) {
      if (required <= buffer.size) return
      val doubled = (buffer.size.toLong() * 2).coerceAtMost(limit.toLong())
      val capacity = maxOf(required.toLong(), doubled).toInt()
      check(capacity <= limit)
      buffer = buffer.copyOf(capacity)
    }
  }

  private fun ByteArray.hex(): String =
      joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
}

/**
 * Local slot persistence used only from BasicController's emulation-thread frame boundary.
 *
 * New saves are portable machine-root StateFiles. Legacy Java input is admitted only by the exact
 * historical header and strict local allowlisted reader.
 */
class SnapshotManager private constructor(
    private val configuration: Gameboy.GameboyConfiguration,
    private val legacyMigrationPolicy: LegacySnapshotMigrationPolicy,
    private val readLimits: SnapshotReadLimits,
    private val legacyApplyProbe: (() -> Unit)?,
    private val persistence: AtomicFileWriter,
) {

  constructor(
      configuration: Gameboy.GameboyConfiguration,
      legacyMigrationPolicy: LegacySnapshotMigrationPolicy =
          LegacySnapshotMigrationPolicy.PRESERVE,
  ) : this(
      configuration,
      legacyMigrationPolicy,
      SnapshotReadLimits.DEFAULT,
      null,
      AtomicFileWriter.system(),
  )

  private val origin = configuration.rom.origin

  fun snapshotAvailable(slot: Int): Boolean =
      try {
        getSnapshotPaths(slot).any(persistence::exists)
      } catch (failure: IOException) {
        LOG.warn("Unable to recover snapshot slot {} before checking availability", slot, failure)
        false
      }

  fun saveSnapshot(slot: Int, gameboy: Gameboy) {
    val snapshotFile = getSnapshotFile(slot)
    val bytes =
        StateCodec.encode(
            StateCodec.capture(configuration, gameboy),
            StateCompression.DEFLATE,
        )
    persistence.write(snapshotFile.toPath(), bytes)
  }

  fun loadSnapshot(slot: Int, gameboy: Gameboy): Boolean {
    val target = StateIdentity.from(configuration)
    var snapshotFile: File? = null
    val snapshot =
        try {
          getSnapshotPaths(slot).firstNotNullOfOrNull { path ->
            persistence.read(path) { recovered ->
              if (!Files.exists(recovered)) {
                null
              } else {
                SnapshotFileReader.read(recovered.toFile(), readLimits).also {
                  snapshotFile = path.toFile()
                }
              }
            }
          }
        } catch (failure: SnapshotReadException) {
          throw loadFailure(
              failure.format,
              null,
              target,
              null,
              failure.message ?: "Snapshot file could not be read",
              failure,
          )
        } catch (failure: IOException) {
          throw loadFailure(
              null,
              null,
              target,
              null,
              "Snapshot transaction recovery or read failed",
              failure,
          )
        }
        ?: return false

    when (snapshot.format) {
      SnapshotFileFormat.PORTABLE -> loadPortable(snapshot.bytes, target, gameboy)
      SnapshotFileFormat.LEGACY_JAVA ->
          loadLegacy(requireNotNull(snapshotFile), snapshot.bytes, target, gameboy)
    }
    return true
  }

  private fun loadPortable(
      bytes: ByteArray,
      target: MachineIdentity,
      gameboy: Gameboy,
  ) {
    val source = inspectMachineIdentity(bytes)
    try {
      StateCodec.decodeAndApply(bytes, configuration, gameboy)
    } catch (failure: StateDecodeException) {
      throw loadFailure(
          SnapshotFileFormat.PORTABLE,
          failure.reason,
          target,
          source,
          failure.message ?: "Portable snapshot is invalid or incompatible",
          failure,
      )
    }
  }

  private fun loadLegacy(
      snapshotFile: File,
      bytes: ByteArray,
      target: MachineIdentity,
      gameboy: Gameboy,
  ) {
    val legacyState =
        try {
          LegacySnapshotImporter.importGameboyState(bytes)
        } catch (failure: Exception) {
          throw loadFailure(
              SnapshotFileFormat.LEGACY_JAVA,
              null,
              target,
              null,
              failure.message ?: "Legacy snapshot is invalid or unsupported",
              failure,
          )
        }
    try {
      DetachedStateAdapter.applyLegacyState(gameboy, legacyState, legacyApplyProbe)
    } catch (failure: Exception) {
      throw loadFailure(
          SnapshotFileFormat.LEGACY_JAVA,
          null,
          target,
          null,
          failure.message ?: "Legacy snapshot is incompatible with the target",
          failure,
      )
    }

    if (legacyMigrationPolicy == LegacySnapshotMigrationPolicy.REWRITE_AFTER_SUCCESS) {
      val portable =
          StateCodec.encode(
              StateCodec.capture(configuration, gameboy),
              StateCompression.DEFLATE,
          )
      try {
        persistence.write(snapshotFile.toPath(), portable)
      } catch (failure: IOException) {
        // The state is already restored successfully. The transaction remains recoverable and
        // migration is deliberately best effort.
        LOG.warn("Legacy snapshot restored, but its optional portable rewrite failed", failure)
      }
    }
  }

  private fun inspectMachineIdentity(bytes: ByteArray): MachineIdentity? =
      try {
        StateCodec.inspect(bytes)
            .identities
            .singleOrNull { it.player == 0 }
            ?.identity
      } catch (_: StateDecodeException) {
        null
      }

  private fun loadFailure(
      format: SnapshotFileFormat?,
      reason: StateDecodeReason?,
      target: MachineIdentity,
      source: MachineIdentity?,
      detail: String,
      cause: Throwable,
  ): SnapshotLoadException {
    val sourceDescription =
        source?.description() ?: "unavailable (format carries none or inspection failed safely)"
    val reasonDescription = reason?.let { " [$it]" } ?: ""
    return SnapshotLoadException(
        format,
        reason,
        source,
        target,
        "${format?.description() ?: "Unknown-format"} snapshot rejected$reasonDescription: " +
            "source {$sourceDescription}; target {${target.description()}}; $detail",
        cause,
    )
  }

  private fun getSnapshotFile(slot: Int): File {
    return origin.persistencePath(".sn${slot}").orElseThrow {
      IllegalArgumentException("Local snapshots require a persistent ROM origin")
    }.toFile()
  }

  private fun getSnapshotPaths(slot: Int): List<Path> {
    val primary = getSnapshotFile(slot).toPath()
    val legacy = origin.legacyArchivePersistencePath(".sn${slot}").orElse(null)
    return listOfNotNull(primary, legacy).distinct()
  }

  private fun MachineIdentity.description(): String =
      "ROM SHA-256=${primaryRom.hex()}, " +
          "slot ROM SHA-256=${slotRom?.hex() ?: "absent"}, profile=$profile"

  internal companion object {
    private val LOG = LoggerFactory.getLogger(SnapshotManager::class.java)

    fun testing(
        configuration: Gameboy.GameboyConfiguration,
        legacyMigrationPolicy: LegacySnapshotMigrationPolicy,
        readLimits: SnapshotReadLimits = SnapshotReadLimits.DEFAULT,
        persistence: AtomicFileWriter = AtomicFileWriter.system(),
        legacyApplyProbe: (() -> Unit)? = null,
    ): SnapshotManager =
        SnapshotManager(
            configuration,
            legacyMigrationPolicy,
            readLimits,
            legacyApplyProbe,
            persistence,
        )
  }
}

private fun SnapshotFileFormat.description(): String =
    when (this) {
      SnapshotFileFormat.PORTABLE -> "Portable CGBS"
      SnapshotFileFormat.LEGACY_JAVA -> "Legacy AC ED 00 05"
    }

private const val SNAPSHOT_PREFIX_BYTES = 4
private const val DEFAULT_READ_BUFFER_BYTES = 8192
