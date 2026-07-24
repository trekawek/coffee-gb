package eu.rekawek.coffeegb.controller

/**
 * Allocation limits at the state-file and netplay trust boundaries.
 *
 * The values intentionally leave headroom above currently observed payloads while keeping one
 * declaration from reserving an unbounded heap region. They are format limits, not estimates: a
 * future state format that needs more space must change the relevant named limit and its boundary
 * tests deliberately.
 */
internal object StateLimits {

  private const val MIB = 1024 * 1024
  private const val DEFLATE_OVERHEAD_ALLOWANCE = 64 * 1024

  data class Payload(
      val description: String,
      val decodedBytes: Int,
      val encodedBytes: Int = decodedBytes + DEFLATE_OVERHEAD_ALLOWANCE,
  )

  // Large homebrew and multicart images exceed the official 8 MiB cartridge maximum.
  val ROM = Payload("ROM", 64 * MIB)

  // This covers supported cartridge RAM/RTC payloads with ample mapper-specific headroom.
  val BATTERY = Payload("battery", 2 * MIB)

  // Current Java mementos are allocation-heavy, so the migration reader needs temporary headroom.
  val GAME_SNAPSHOT = Payload("game snapshot", 32 * MIB)
  val SESSION_SNAPSHOT = Payload("session snapshot", 32 * MIB)

  // A ROM message contains all four payload types. Cap both wire retention and inflated heap use.
  const val NETPLAY_ENCODED_MESSAGE_BYTES = 128 * MIB
  const val NETPLAY_DECODED_MESSAGE_BYTES = 128 * MIB

  // JEP 290 graph limits for the local-only legacy migration reader.
  const val LEGACY_MAX_DEPTH = 96L
  const val LEGACY_MAX_REFERENCES = 100_000L
  const val LEGACY_MAX_ARRAY_LENGTH = 16L * MIB
  const val LEGACY_MAX_COLLECTION_ENTRIES = 16_384
  const val LEGACY_MAX_STRING_CHARS = 65_536
}
