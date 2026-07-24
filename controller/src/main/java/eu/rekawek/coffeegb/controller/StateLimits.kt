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
  const val NETPLAY_ROLLBACK_FRAMES = 60L * 5
  const val NETPLAY_REPLAY_WORK_FRAMES = NETPLAY_ROLLBACK_FRAMES
  const val NETPLAY_FUTURE_FRAMES = 60L * 2
  const val NETPLAY_MAX_FRAME = Int.MAX_VALUE.toLong()
  const val NETPLAY_MAX_REBASE_FRAME = NETPLAY_MAX_FRAME - NETPLAY_FUTURE_FRAMES
  const val NETPLAY_PENDING_EVENTS = 4
  const val NETPLAY_HANDSHAKE_PENDING_MESSAGES = 8
  const val NETPLAY_HANDSHAKE_PENDING_BYTES = NETPLAY_ENCODED_MESSAGE_BYTES.toLong()
  const val NETPLAY_PENDING_HANDSHAKES = 8
  const val NETPLAY_HANDSHAKE_WORKERS = 4
  const val NETPLAY_EVENT_QUEUE_EVENTS = 512
  const val NETPLAY_EVENT_QUEUE_BYTES = NETPLAY_DECODED_MESSAGE_BYTES.toLong() * 3
  const val NETPLAY_EVENT_QUEUE_SOURCE_EVENTS = NETPLAY_EVENT_QUEUE_EVENTS / 2
  const val NETPLAY_EVENT_QUEUE_SOURCE_BYTES = NETPLAY_DECODED_MESSAGE_BYTES.toLong() * 2

  // JEP 290 graph limits for the local-only legacy migration reader.
  const val LEGACY_MAX_DEPTH = 96L
  const val LEGACY_MAX_REFERENCES = 100_000L
  const val LEGACY_MAX_ARRAY_LENGTH = 16L * MIB
  const val LEGACY_MAX_ARRAY_BYTES = 32L * MIB
  const val LEGACY_MAX_COLLECTION_ENTRIES = 16_384
  const val LEGACY_MAX_MAP_TABLE_ENTRIES = 32_768
  const val LEGACY_MAX_STRING_CHARS = 65_536
  const val LEGACY_MAX_STRING_BYTES = LEGACY_MAX_STRING_CHARS * 3L
}
