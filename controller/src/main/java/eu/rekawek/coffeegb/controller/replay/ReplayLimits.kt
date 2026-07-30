package eu.rekawek.coffeegb.controller.replay

/**
 * Allocation and count limits at the untrusted replay-file boundary.
 *
 * These values are part of the CGBR v1 contract. Increasing one is a format/security decision,
 * not an implementation detail.
 */
object ReplayLimits {
  private const val MIB = 1024 * 1024

  const val MAX_FILE_BYTES = 64 * MIB
  const val MAX_TOTAL_DECODED_BYTES = 64 * MIB
  const val MAX_SECTION_ENCODED_BYTES = 32 * MIB
  const val MAX_SECTION_DECODED_BYTES = 32 * MIB
  const val MAX_EMBEDDED_STATE_BYTES = 32 * MIB

  const val MAX_SECTIONS = 16
  const val MAX_INPUT_RECORDS = 1_000_000
  const val MAX_INPUT_RECORDS_PER_TICK = 64
  const val MAX_CHECKPOINTS = 65_536

  /** Leaves one representable successor tick for player position accounting. */
  const val MAX_TIMELINE_TICK = Long.MAX_VALUE - 1L

  const val SHA256_BYTES = 32
  const val MAX_PROFILE_ID_BYTES = 32
  const val MAX_PRODUCER_VERSION_BYTES = 1_024
  const val MAX_NOTE_CHARS = 4_096
  const val MAX_METADATA_BYTES = 16 * 1024
}
