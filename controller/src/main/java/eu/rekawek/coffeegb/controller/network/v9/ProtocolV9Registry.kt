package eu.rekawek.coffeegb.controller.network.v9

/**
 * Stable protocol-v9 wire constants.
 *
 * The numeric values in this package are the production mirror of the frozen registries under
 * `controller/src/test/resources/netplay-v9`. Enum declaration order is never used on the wire.
 */
object ProtocolV9 {
  val MAGIC: ByteArray = byteArrayOf(0x43, 0x47, 0x42, 0x39)
    get() = field.copyOf()

  const val MAJOR = 9
  const val MINOR = 0
  const val HEADER_BYTES = 64
  const val CONTROL_CHANNEL = 0L
  const val GROUP_CHANNEL = 0xffff_ffffL
  const val LAST_SEQUENCE = 0xffff_fffeL
  const val EXHAUSTED_SEQUENCE = 0xffff_ffffL
  const val U32_MAX = 0xffff_ffffL
}

enum class V9Flag(val wireMask: Int) {
  OPTIONAL(0x0001),
  DEFLATE(0x0002),
  RESPONSE(0x0004),
  TERMINAL(0x0008);

  companion object {
    const val KNOWN_MASK = 0x000f
  }
}

enum class V9ChannelKind {
  CONTROL,
  PLAYER,
  GROUP_OR_PLAYER,
}

enum class V9Compression {
  NONE,
  RAW_DEFLATE,
}

data class V9MessageSpec(
    val wireId: Int,
    val wireName: String,
    val minimumDecodedBytes: Long,
    val maximumDecodedBytes: Long,
    val maximumEncodedBytes: Long,
    val allowedFlags: Int,
    val requiredFlags: Int,
    val channelKind: V9ChannelKind,
    val compression: V9Compression,
    val payloadSchema: String,
)

enum class V9MessageType(val spec: V9MessageSpec) {
  HELLO(spec(0x0001, "HELLO", 38, 294, 294, 0, 0, V9ChannelKind.CONTROL, V9Compression.NONE, "hello-v1")),
  AUTH(spec(0x0002, "AUTH", 36, 36, 36, 0, 0, V9ChannelKind.CONTROL, V9Compression.NONE, "auth-v1")),
  AUTH_RESULT(spec(0x0003, "AUTH_RESULT", 4, 4, 4, 0x000c, 0x0004, V9ChannelKind.CONTROL, V9Compression.NONE, "auth-result-v1")),
  MANIFEST(spec(0x0004, "MANIFEST", 342, 1_396, 1_396, 0, 0, V9ChannelKind.CONTROL, V9Compression.NONE, "manifest-v1")),
  CONSENT(spec(0x0005, "CONSENT", 116, 116, 116, 0, 0, V9ChannelKind.CONTROL, V9Compression.NONE, "consent-v1")),
  START(spec(0x0006, "START", 16, 16, 16, 0, 0, V9ChannelKind.CONTROL, V9Compression.NONE, "start-v1")),
  READY(spec(0x0007, "READY", 8, 8, 8, 0x0004, 0x0004, V9ChannelKind.CONTROL, V9Compression.NONE, "ready-v1")),
  INPUT(spec(0x0008, "INPUT", 16, 16, 16, 0, 0, V9ChannelKind.PLAYER, V9Compression.NONE, "input-v1")),
  CHECKPOINT(spec(0x0009, "CHECKPOINT", 88, 33_554_452, 33_554_452, 0, 0, V9ChannelKind.GROUP_OR_PLAYER, V9Compression.NONE, "statefile-v2-group-v1")),
  ROM_BEGIN(spec(0x000a, "ROM_BEGIN", 52, 52, 52, 0, 0, V9ChannelKind.PLAYER, V9Compression.NONE, "bulk-begin-v1")),
  ROM_CHUNK(spec(0x000b, "ROM_CHUNK", 9, 65_544, 65_600, 0x0002, 0, V9ChannelKind.PLAYER, V9Compression.RAW_DEFLATE, "bulk-chunk-v1")),
  ROM_END(spec(0x000c, "ROM_END", 36, 36, 36, 0, 0, V9ChannelKind.PLAYER, V9Compression.NONE, "bulk-end-v1")),
  BATTERY_BEGIN(spec(0x000d, "BATTERY_BEGIN", 52, 52, 52, 0, 0, V9ChannelKind.PLAYER, V9Compression.NONE, "bulk-begin-v1")),
  BATTERY_CHUNK(spec(0x000e, "BATTERY_CHUNK", 9, 65_544, 65_600, 0x0002, 0, V9ChannelKind.PLAYER, V9Compression.RAW_DEFLATE, "bulk-chunk-v1")),
  BATTERY_END(spec(0x000f, "BATTERY_END", 36, 36, 36, 0, 0, V9ChannelKind.PLAYER, V9Compression.NONE, "bulk-end-v1")),
  RESET(spec(0x0010, "RESET", 16, 16, 16, 0, 0, V9ChannelKind.PLAYER, V9Compression.NONE, "reset-v1")),
  STOP(spec(0x0011, "STOP", 16, 16, 16, 0, 0, V9ChannelKind.PLAYER, V9Compression.NONE, "stop-v1")),
  PING(spec(0x0012, "PING", 16, 16, 16, 0, 0, V9ChannelKind.CONTROL, V9Compression.NONE, "ping-v1")),
  PONG(spec(0x0013, "PONG", 16, 16, 16, 0x0004, 0x0004, V9ChannelKind.CONTROL, V9Compression.NONE, "pong-v1")),
  CANCEL(spec(0x0014, "CANCEL", 4, 260, 260, 0x0008, 0x0008, V9ChannelKind.CONTROL, V9Compression.NONE, "terminal-text-v1")),
  GOODBYE(spec(0x0015, "GOODBYE", 4, 260, 260, 0x0008, 0x0008, V9ChannelKind.CONTROL, V9Compression.NONE, "terminal-text-v1")),
  ERROR(spec(0x0016, "ERROR", 12, 524, 524, 0x000c, 0x0008, V9ChannelKind.CONTROL, V9Compression.NONE, "error-v1"));

  val wireId: Int get() = spec.wireId
  val wireName: String get() = spec.wireName

  companion object {
    private val BY_ID = entries.associateBy { it.wireId }
    private val BY_NAME = entries.associateBy { it.wireName }

    fun fromWireId(wireId: Int): V9MessageType? = BY_ID[wireId]

    fun fromWireName(wireName: String): V9MessageType? = BY_NAME[wireName]
  }
}

private fun spec(
    id: Int,
    name: String,
    minimumDecoded: Long,
    maximumDecoded: Long,
    maximumEncoded: Long,
    allowedFlags: Int,
    requiredFlags: Int,
    channelKind: V9ChannelKind,
    compression: V9Compression,
    payloadSchema: String,
) = V9MessageSpec(
    id,
    name,
    minimumDecoded,
    maximumDecoded,
    maximumEncoded,
    allowedFlags,
    requiredFlags,
    channelKind,
    compression,
    payloadSchema,
)

enum class V9Capability(
    val wireId: Int,
    val wireName: String,
    val schemaVersion: Int,
    val required: Boolean,
    val phaseOwner: Int,
    val meaning: String,
) {
  FRAME_V1(0x0001, "FRAME_V1", 1, true, 347, "64-byte-CGB9-header-and-SHA256"),
  INVITATION_PROOF_V1(0x0002, "INVITATION_PROOF_V1", 1, true, 347, "HMAC-SHA256-invitation-possession-proof"),
  MANIFEST_V1(0x0003, "MANIFEST_V1", 1, true, 348, "bounded-ROM-slot-profile-manifest"),
  CONSENT_V1(0x0004, "CONSENT_V1", 1, true, 348, "two-sided-item-scoped-directional-consent"),
  STATEFILE_V2(0x0005, "STATEFILE_V2", 1, true, 349, "exact-profile-portable-checkpoints"),
  PROFILE_ID_ASCII_V1(0x0006, "PROFILE_ID_ASCII_V1", 1, true, 349, "canonical-lowercase-profile-identities"),
  ATOMIC_GROUP_CHECKPOINT_V1(0x0007, "ATOMIC_GROUP_CHECKPOINT_V1", 1, true, 349, "one-logical-linked-session-transaction"),
  ROM_TRANSFER_V1(0x0008, "ROM_TRANSFER_V1", 1, false, 348, "consent-gated-ROM-transfer"),
  BATTERY_TRANSFER_V1(0x0009, "BATTERY_TRANSFER_V1", 1, false, 348, "consent-gated-battery-transfer"),
  RAW_DEFLATE_V1(0x000a, "RAW_DEFLATE_V1", 1, false, 348, "bounded-RFC1951-bulk-chunks-only"),
  FOUR_PLAYER_V1(0x000b, "FOUR_PLAYER_V1", 1, false, 349, "four-player-mode-and-topology"),
  PING_V1(0x000c, "PING_V1", 1, false, 350, "active-idle-liveness-probe");

  companion object {
    private val BY_ID = entries.associateBy { it.wireId }

    val requiredCapabilities: Set<V9Capability> =
        entries.filter { it.required }.toSet()

    fun fromWireId(wireId: Int): V9Capability? = BY_ID[wireId]
  }
}

enum class V9ErrorCode(
    val wireId: Int,
    val wireName: String,
    val peerVisible: Boolean = true,
    val retrySameConnection: Boolean = false,
    val mutationAllowed: Boolean = false,
) {
  MALFORMED_HEADER(0x0001, "MALFORMED_HEADER"),
  UNSUPPORTED_PROTOCOL(0x0002, "UNSUPPORTED_PROTOCOL"),
  UNKNOWN_REQUIRED_TYPE(0x0003, "UNKNOWN_REQUIRED_TYPE"),
  UNKNOWN_REQUIRED_CAPABILITY(0x0004, "UNKNOWN_REQUIRED_CAPABILITY"),
  UNKNOWN_REQUIRED_FLAG(0x0005, "UNKNOWN_REQUIRED_FLAG"),
  LIMIT_EXCEEDED(0x0006, "LIMIT_EXCEEDED"),
  TRUNCATED(0x0007, "TRUNCATED"),
  CHECKSUM_MISMATCH(0x0008, "CHECKSUM_MISMATCH"),
  DECOMPRESSION_FAILED(0x0009, "DECOMPRESSION_FAILED"),
  UNEXPECTED_MESSAGE(0x000a, "UNEXPECTED_MESSAGE"),
  SEQUENCE_ERROR(0x000b, "SEQUENCE_ERROR"),
  CORRELATION_ERROR(0x000c, "CORRELATION_ERROR"),
  TIMEOUT(0x000d, "TIMEOUT"),
  CANCELLED(0x000e, "CANCELLED"),
  AUTH_FAILED(0x000f, "AUTH_FAILED"),
  SERVER_FULL(0x0010, "SERVER_FULL"),
  SERVER_BUSY(0x0011, "SERVER_BUSY"),
  CAPABILITY_MISMATCH(0x0012, "CAPABILITY_MISMATCH"),
  MANIFEST_MISMATCH(0x0013, "MANIFEST_MISMATCH"),
  CONSENT_REJECTED(0x0014, "CONSENT_REJECTED"),
  ROM_MISMATCH(0x0015, "ROM_MISMATCH"),
  PROFILE_MISMATCH(0x0016, "PROFILE_MISMATCH"),
  STATEFILE_VERSION(0x0017, "STATEFILE_VERSION"),
  ROOT_KIND_MISMATCH(0x0018, "ROOT_KIND_MISMATCH"),
  TOPOLOGY_MISMATCH(0x0019, "TOPOLOGY_MISMATCH"),
  QUEUE_OVERFLOW(0x001a, "QUEUE_OVERFLOW"),
  UNEXPECTED_EOF(0x001b, "UNEXPECTED_EOF", false),
  TRAILING_DATA(0x001c, "TRAILING_DATA", false),
  STRICT_UTF8(0x001d, "STRICT_UTF8"),
  INTERNAL_ERROR(0x001e, "INTERNAL_ERROR"),
  STATEFILE_MALFORMED(0x001f, "STATEFILE_MALFORMED");

  companion object {
    private val BY_ID = entries.associateBy { it.wireId }

    fun fromWireId(wireId: Int): V9ErrorCode? = BY_ID[wireId]
  }
}

enum class V9Limit(
    val value: Long,
    val unit: String,
    val validationBoundary: String,
    val rationale: String,
) {
  HEADER_BYTES(64, "bytes", "before-payload", "fixed-framing"),
  UNKNOWN_OPTIONAL_BYTES(4_096, "bytes", "before-payload-read", "bounded-forward-skip"),
  CAPABILITY_COUNT(32, "entries", "before-capability-loop", "finite-negotiation"),
  PROFILE_ID_BYTES(32, "bytes", "before-string-decode", "StateFile-v2-compatible"),
  MANIFEST_ENTRIES(4, "entries", "before-entry-allocation", "one-per-logical-slot"),
  MANIFEST_PROPOSALS(8, "entries", "before-proposal-allocation", "item-scoped-private-transfer-proposals"),
  MANIFEST_DIFFS(16, "entries", "before-diff-allocation", "bounded-compatibility-report"),
  INVITATION_URI_BYTES(512, "bytes", "before-URI-parse", "clipboard-and-log-bound"),
  INVITATION_TOKEN_BYTES(16, "bytes", "before-auth", "128-bit-minimum"),
  INVITATION_EXPIRY_MIN_SECONDS(60, "seconds", "before-invitation-create", "bounded-lifetime-lower-bound"),
  INVITATION_EXPIRY_DEFAULT_SECONDS(300, "seconds", "before-invitation-create", "canonical-default"),
  INVITATION_EXPIRY_MAX_SECONDS(600, "seconds", "before-invitation-create", "bounded-lifetime-upper-bound"),
  AUTH_FAILURES_PER_WINDOW(8, "attempts", "before-proof-admission", "bounded-listener-work"),
  AUTH_FAILURE_WINDOW_MILLIS(60_000, "milliseconds", "monotonic-before-proof-admission", "generic-rate-limit-window"),
  STATEFILE_ENCODED_BYTES(33_554_432, "bytes", "before-StateFile-read", "tighter-network-bound"),
  STATEFILE_DECODED_BYTES(33_554_432, "bytes", "before-StateFile-inflate", "tighter-network-bound"),
  ROM_BYTES(67_108_864, "bytes", "before-ROM-transaction", "current-ROM-policy"),
  BATTERY_BYTES(2_097_152, "bytes", "before-battery-transaction", "current-battery-policy"),
  BULK_CHUNK_DECODED_BYTES(65_536, "bytes", "before-chunk-read", "bounded-stream-window"),
  BULK_CHUNK_ENCODED_BYTES(65_600, "bytes", "before-chunk-read", "bounded-deflate-overhead"),
  BULK_WINDOW_CHUNKS(4, "chunks", "before-queue-admission", "bounded-backpressure"),
  SESSION_DECODED_AGGREGATE_BYTES(134_217_728, "bytes", "checked-before-admission", "portable-aggregate-ceiling"),
  QUEUED_FRAMES(256, "frames", "before-queue-admission", "bounded-session-work"),
  QUEUED_WIRE_BYTES(33_817_172, "bytes", "checked-before-copy", "one-complete-checkpoint-frame-plus-four-complete-maximum-chunk-frames"),
  PENDING_HANDSHAKES(8, "sessions", "before-accept-admission", "listener-isolation"),
  HANDSHAKE_WORKERS(4, "workers", "before-task-start", "listener-isolation"),
  OPEN_BULK_TRANSACTIONS(1, "transaction", "before-BEGIN", "bounded-ownership"),
  CHECKPOINTS_PER_DIRECTIONAL_GRANT(32, "transactions", "before-checkpoint-admission", "bounded-session-scoped-checkpoint-resynchronization"),
}

enum class V9Timeout(
    val milliseconds: Long,
    val expiryError: V9ErrorCode?,
) {
  SEND_SERVER_HELLO(5_000, V9ErrorCode.TIMEOUT),
  WAIT_SERVER_HELLO(5_000, V9ErrorCode.TIMEOUT),
  SEND_CLIENT_HELLO(5_000, V9ErrorCode.TIMEOUT),
  WAIT_CLIENT_HELLO(5_000, V9ErrorCode.TIMEOUT),
  SEND_AUTH(5_000, V9ErrorCode.TIMEOUT),
  WAIT_AUTH(5_000, V9ErrorCode.TIMEOUT),
  SEND_AUTH_RESULT(5_000, V9ErrorCode.TIMEOUT),
  WAIT_AUTH_RESULT(5_000, V9ErrorCode.TIMEOUT),
  SEND_SERVER_MANIFEST(10_000, V9ErrorCode.TIMEOUT),
  WAIT_SERVER_MANIFEST(10_000, V9ErrorCode.TIMEOUT),
  SEND_CLIENT_MANIFEST(10_000, V9ErrorCode.TIMEOUT),
  WAIT_CLIENT_MANIFEST(10_000, V9ErrorCode.TIMEOUT),
  EXCHANGE_CONSENT(120_000, V9ErrorCode.CONSENT_REJECTED),
  SEND_READY(15_000, V9ErrorCode.TIMEOUT),
  WAIT_READY(15_000, V9ErrorCode.TIMEOUT),
  SYNCHRONIZING(120_000, V9ErrorCode.TIMEOUT),
  ACTIVE_IDLE(30_000, V9ErrorCode.TIMEOUT),
  BULK_PROGRESS(15_000, V9ErrorCode.TIMEOUT),
  TERMINAL_CLEANUP(2_000, null),
}

enum class V9Role(val wireId: Int) {
  SERVER(1),
  CLIENT(2);

  companion object {
    fun fromWireId(wireId: Int): V9Role? = entries.firstOrNull { it.wireId == wireId }
  }
}

enum class V9LinkMode {
  NORMAL,
  FOUR_PLAYER,
}
