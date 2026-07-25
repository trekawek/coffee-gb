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
  // Protocol v8 carries one complete StateFile directly, without the legacy outer compression.
  // Both the wire file and its declared decoded section stream are capped at 32 MiB before the
  // payload is retained or inflated. Current machine and four-player session records remain well
  // below this boundary; a larger future state requires a deliberate protocol review.
  const val NETPLAY_STATE_FILE_BYTES = 32 * MIB
  const val NETPLAY_STATE_FILE_DECODED_BYTES = 32 * MIB
  const val NETPLAY_ROLLBACK_FRAMES = 60L * 5
  const val NETPLAY_REPLAY_WORK_FRAMES = NETPLAY_ROLLBACK_FRAMES
  const val NETPLAY_STATE_CHANGE_FIXED_WORK = 60L
  // A checkpoint is one bounded transaction: protocol sequencing limits it to four distinct
  // states and queue admission limits its aggregate payload to one decoded message. Allow a short
  // burst of nine topology transactions, then refill at the ordinary per-frame rate.
  const val NETPLAY_CHECKPOINT_WORK_FRAMES =
      NETPLAY_STATE_CHANGE_FIXED_WORK * 9L
  const val NETPLAY_STATE_CHANGE_REFILL_PER_FRAME = 1L
  const val NETPLAY_FUTURE_FRAMES = 60L * 2
  const val NETPLAY_MAX_FRAME = Int.MAX_VALUE.toLong()
  const val NETPLAY_MAX_REBASE_FRAME = NETPLAY_MAX_FRAME - NETPLAY_FUTURE_FRAMES
  const val NETPLAY_PENDING_EVENTS = 4
  const val NETPLAY_HANDSHAKE_PENDING_MESSAGES = 8
  const val NETPLAY_HANDSHAKE_PENDING_BYTES = NETPLAY_ENCODED_MESSAGE_BYTES.toLong()
  const val NETPLAY_PENDING_HANDSHAKES = 8
  const val NETPLAY_HANDSHAKE_WORKERS = 4
  // Enough count headroom for an ordinary input burst; the byte limit remains the primary bound
  // for large state records sent to a non-reader.
  const val NETPLAY_OUTBOUND_MESSAGES = 512
  const val NETPLAY_OUTBOUND_BYTES = NETPLAY_ENCODED_MESSAGE_BYTES.toLong() * 2
  const val NETPLAY_WRITER_CLOSE_MILLIS = 250L
  const val NETPLAY_PROTOCOL_ERROR_GRACE_MILLIS = 50L
  const val NETPLAY_EVENT_QUEUE_EVENTS = 512
  // Four-player hosting has one local producer and three remote connections. Reserve one equal
  // protocol-sized message for each so saturated peers cannot make the next honest producer trip
  // the global cap. Queue admission charges the retained ROM/battery arrays plus the StateFile's
  // encoded and decoded sizes; detached graphs retain their own portable-codec limits below.
  const val NETPLAY_EVENT_QUEUE_SOURCES = 4
  const val NETPLAY_EVENT_QUEUE_SOURCE_BYTES = NETPLAY_DECODED_MESSAGE_BYTES.toLong()
  const val NETPLAY_EVENT_QUEUE_BYTES =
      NETPLAY_EVENT_QUEUE_SOURCE_BYTES * NETPLAY_EVENT_QUEUE_SOURCES
  const val NETPLAY_EVENT_QUEUE_SOURCE_EVENTS =
      NETPLAY_EVENT_QUEUE_EVENTS / NETPLAY_EVENT_QUEUE_SOURCES
  const val NETPLAY_EVENT_DISPATCH_EVENTS = 64

  // JEP 290 graph limits for the local-only legacy migration reader.
  const val LEGACY_MAX_DEPTH = 96L
  const val LEGACY_MAX_REFERENCES = 100_000L
  const val LEGACY_MAX_ARRAY_LENGTH = 16L * MIB
  const val LEGACY_MAX_ARRAY_BYTES = 32L * MIB
  const val LEGACY_MAX_COLLECTION_ENTRIES = 16_384
  const val LEGACY_MAX_MAP_TABLE_ENTRIES = 32_768
  const val LEGACY_MAX_STRING_CHARS = 65_536
  const val LEGACY_MAX_STRING_BYTES = LEGACY_MAX_STRING_CHARS * 3L

  // StateFile v1 limits. These names are deliberately independent from the local-only legacy
  // importer: changing a portable format limit is a versioned compatibility decision.
  const val PORTABLE_MAX_DECODED_PAYLOAD_BYTES = 128 * MIB
  const val PORTABLE_MAX_ENCODED_PAYLOAD_BYTES =
      PORTABLE_MAX_DECODED_PAYLOAD_BYTES + DEFLATE_OVERHEAD_ALLOWANCE
  const val PORTABLE_MAX_FILE_BYTES =
      PORTABLE_MAX_ENCODED_PAYLOAD_BYTES + DEFLATE_OVERHEAD_ALLOWANCE
  const val PORTABLE_MAX_SECTION_BYTES = PORTABLE_MAX_DECODED_PAYLOAD_BYTES
  const val PORTABLE_MAX_SECTIONS = 64
  const val PORTABLE_MAX_GRAPH_DEPTH = 96
  // Counts every encoded StateValue position, including NullState collection/map/record slots.
  const val PORTABLE_MAX_VALUE_OCCURRENCES = 100_000
  const val PORTABLE_MAX_COLLECTION_ENTRIES = 16_384
  const val PORTABLE_MAX_ARRAY_ELEMENTS = 16 * MIB
  const val PORTABLE_MAX_ARRAY_BYTES = 32 * MIB
  const val PORTABLE_MAX_STRING_CHARS = 65_536
  const val PORTABLE_MAX_STRING_BYTES = PORTABLE_MAX_STRING_CHARS * 3
  const val PORTABLE_MAX_LINKED_PLAYERS = 4
}
