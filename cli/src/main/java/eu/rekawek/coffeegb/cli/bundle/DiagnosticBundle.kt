package eu.rekawek.coffeegb.cli.bundle

import java.util.Collections
import java.util.EnumSet
import java.util.SortedMap
import java.util.TreeMap

/** Sensitive payload classes that are representable in a diagnostic bundle. */
enum class DiagnosticSensitiveCategory(val manifestName: String) {
  MEDIA("media"),
  MEMORY("memory"),
  REPLAY("replay"),
  RAW_REPLAY("raw-replay"),
}

/**
 * The complete artifact allowlist. There is deliberately no kind for ROM, boot ROM, save data,
 * filesystem paths, credentials, or arbitrary caller-selected ZIP names.
 */
enum class DiagnosticBundleEntryKind(
    val path: String,
    val mediaType: String,
    val maximumBytes: Int,
    val sensitiveCategory: DiagnosticSensitiveCategory? = null,
) {
  REPORT_JSON("report.json", "application/json", 128 * 1024),
  LOGS_NDJSON("logs.ndjson", "application/x-ndjson", 256 * 1024),
  SAFE_REPLAY(
      "replay.cgbreplay",
      "application/vnd.coffee-gb.replay",
      64 * 1024 * 1024,
      DiagnosticSensitiveCategory.REPLAY,
  ),
  SCREENSHOT_PNG(
      "screenshot.png",
      "image/png",
      256 * 1024,
      DiagnosticSensitiveCategory.MEDIA,
  ),
  AUDIO_WAV(
      "audio.wav",
      "audio/wav",
      64 * 1024 * 1024 + 44,
      DiagnosticSensitiveCategory.MEDIA,
  ),
  MEMORY_JSON(
      "sensitive/memory.json",
      "application/json",
      128 * 1024,
      DiagnosticSensitiveCategory.MEMORY,
  ),
  RAW_REPLAY(
      "sensitive/replay.cgbreplay",
      "application/vnd.coffee-gb.replay",
      64 * 1024 * 1024,
      DiagnosticSensitiveCategory.RAW_REPLAY,
  ),
}

class DiagnosticBundleEntry(
    val kind: DiagnosticBundleEntryKind,
    bytes: ByteArray,
) {
  private val ownedBytes = bytes.copyOf()

  val bytes: ByteArray
    get() = ownedBytes.copyOf()

  internal fun payload(): ByteArray = ownedBytes.copyOf()
}

/**
 * Both sets must match the sensitive categories actually present in the archive. This models the
 * separate user request and explicit confirmation gates without collapsing them into one flag.
 */
class DiagnosticBundleConsent(
    requestedSensitive: Set<DiagnosticSensitiveCategory> = emptySet(),
    confirmedSensitive: Set<DiagnosticSensitiveCategory> = emptySet(),
) {
  val requestedSensitive: Set<DiagnosticSensitiveCategory> = immutableEnumSet(requestedSensitive)
  val confirmedSensitive: Set<DiagnosticSensitiveCategory> = immutableEnumSet(confirmedSensitive)

  companion object {
    val NONE = DiagnosticBundleConsent()

    private fun immutableEnumSet(
        source: Set<DiagnosticSensitiveCategory>,
    ): Set<DiagnosticSensitiveCategory> {
      val copy = if (source.isEmpty()) {
        EnumSet.noneOf(DiagnosticSensitiveCategory::class.java)
      } else {
        EnumSet.copyOf(source)
      }
      return Collections.unmodifiableSet(copy)
    }
  }
}

class DiagnosticRomMetadata(
    val sha256: String,
    val byteLength: Long,
    header: Map<String, String> = emptyMap(),
) {
  val header: SortedMap<String, String> = immutableSortedMap(header)

  init {
    require(SHA256.matches(sha256)) { "ROM SHA-256 must be lowercase hexadecimal" }
    require(byteLength in 1..MAX_ROM_BYTES) { "ROM byte length is outside the supported range" }
    require(this.header.keys.all { it in ALLOWED_HEADER_KEYS }) {
      "ROM header metadata contains a non-allowlisted key"
    }
  }

  companion object {
    private const val MAX_ROM_BYTES = 64L * 1024L * 1024L
    private val SHA256 = Regex("[0-9a-f]{64}")
    internal val ALLOWED_HEADER_KEYS = setOf(
        "cartridgeType",
        "cgbFlag",
        "headerChecksumValid",
        "nintendoLogoValid",
        "ramSizeCode",
        "romSizeCode",
        "sgbFlag",
        "title",
    )
  }
}

class DiagnosticBundleMetadata(
    val applicationVersion: String,
    val javaVersion: String,
    val javaVendor: String,
    val javaVmName: String,
    val osName: String,
    val osVersion: String,
    val osArchitecture: String,
    configuration: Map<String, String> = emptyMap(),
    val rom: DiagnosticRomMetadata? = null,
    execution: Map<String, String> = emptyMap(),
) {
  val configuration: SortedMap<String, String> = immutableSortedMap(configuration)
  val execution: SortedMap<String, String> = immutableSortedMap(execution)

  init {
    require(this.configuration.keys.all { it in ALLOWED_CONFIGURATION_KEYS }) {
      "Configuration metadata contains a non-allowlisted key"
    }
    require(this.execution.keys.all { it in ALLOWED_EXECUTION_KEYS }) {
      "Execution metadata contains a non-allowlisted key"
    }
  }

  companion object {
    internal val ALLOWED_CONFIGURATION_KEYS = setOf(
        "batteryPersistence",
        "bootstrap",
        "codeBreakerRumble",
        "infrared",
        "inputRecords",
        "inputScript",
        "mealybugDmgBlob",
        "profile",
        "rtcEpochMillis",
        "serial",
        "sgbBorder",
    )
    internal val ALLOWED_EXECUTION_KEYS = setOf(
        "breakpoint",
        "command",
        "executedFrames",
        "executedTicks",
        "exitCode",
        "frame",
        "framePosition",
        "frames",
        "fullStateHash",
        "inputRecords",
        "maximumFrames",
        "maximumTicks",
        "mode",
        "replayStatus",
        "requestedFrames",
        "requestedTicks",
        "status",
        "ticks",
    )
  }
}

private fun immutableSortedMap(source: Map<String, String>): SortedMap<String, String> =
    Collections.unmodifiableSortedMap(TreeMap(source))
