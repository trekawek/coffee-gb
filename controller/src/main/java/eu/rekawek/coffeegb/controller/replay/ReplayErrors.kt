package eu.rekawek.coffeegb.controller.replay

import java.io.IOException

/** Stable classifications for failures while reading untrusted CGBR bytes. */
enum class ReplayDecodeReason {
  INVALID_MAGIC,
  UNSUPPORTED_FORMAT_VERSION,
  UNSUPPORTED_SECTION_VERSION,
  UNSUPPORTED_FLAGS,
  UNSUPPORTED_REPLAY_SEMANTICS,
  UNSUPPORTED_STATE_FILE_VERSION,
  CORRUPT_CHECKSUM,
  TRUNCATED,
  LIMIT_EXCEEDED,
  MALFORMED_STRUCTURE,
  MALFORMED_ENUM,
  MALFORMED_UTF8,
  MISSING_REQUIRED_SECTION,
  DUPLICATE_SECTION,
  UNKNOWN_REQUIRED_SECTION,
  TRAILING_DATA,
  COMPRESSION_ERROR,
  INVALID_EMBEDDED_STATE,
}

class ReplayDecodeException(
    val reason: ReplayDecodeReason,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

class ReplayEncodeException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Compatibility is checked before a replay player is allowed to mutate its target session. */
enum class ReplayCompatibilityReason {
  UNSUPPORTED_STATE_ROOT,
  UNSUPPORTED_SERIAL_PERIPHERAL,
  UNSUPPORTED_INFRARED_ENDPOINT,
  UNSUPPORTED_SENSOR_CARTRIDGE,
  UNSUPPORTED_WALL_CLOCK_CARTRIDGE,
  UNSUPPORTED_PAUSED_RTC,
  UNSUPPORTED_INITIAL_PHYSICAL_INPUT,
  INVALID_EMBEDDED_STATE,
  PRIMARY_ROM_MISMATCH,
  SLOT_ROM_MISMATCH,
  HARDWARE_PROFILE_MISMATCH,
  CLOCK_MISMATCH,
  BOOTSTRAP_MISMATCH,
  BEHAVIOR_MISMATCH,
  REPLAY_SEMANTICS_MISMATCH,
  STATE_FILE_VERSION_MISMATCH,
}

class ReplayCompatibilityException(
    val reason: ReplayCompatibilityReason,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
