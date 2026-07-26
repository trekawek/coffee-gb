package eu.rekawek.coffeegb.controller.network.v9

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class V9ConsentDecision(val wireId: Int) {
  APPROVE(1),
  REJECT(2);

  companion object {
    fun fromWireId(value: Int): V9ConsentDecision? = entries.firstOrNull { it.wireId == value }
  }
}

/**
 * Exact consent-v1 value. All byte arrays are detached and diagnostics deliberately omit them.
 */
class V9ConsentVote(
    val decisionId: Long,
    val actorPlayer: Int,
    val decision: V9ConsentDecision,
    val transferClass: V9TransferClass,
    val asset: V9TransferAsset,
    val sourcePlayer: Int,
    val targetPlayer: Int,
    val ownerPlayer: Int,
    val proposalId: Long,
    val expectedSize: Long,
    expectedSha256: V9ManifestDigest,
    serverManifestSha256: V9ManifestDigest,
    clientManifestSha256: V9ManifestDigest,
) {
  private val expectedDigest = V9ManifestDigest(expectedSha256.bytes())
  private val serverDigest = V9ManifestDigest(serverManifestSha256.bytes())
  private val clientDigest = V9ManifestDigest(clientManifestSha256.bytes())

  fun expectedSha256(): V9ManifestDigest = V9ManifestDigest(expectedDigest.bytes())

  fun serverManifestSha256(): V9ManifestDigest = V9ManifestDigest(serverDigest.bytes())

  fun clientManifestSha256(): V9ManifestDigest = V9ManifestDigest(clientDigest.bytes())

  internal fun expectedDigestView(): ByteArray = expectedDigest.view()

  internal fun serverDigestView(): ByteArray = serverDigest.view()

  internal fun clientDigestView(): ByteArray = clientDigest.view()

  override fun toString(): String =
      "V9ConsentVote(proposal=$proposalId, actor=$actorPlayer, decision=$decision, " +
          "class=$transferClass, asset=$asset, content=[redacted])"
}

class V9ConsentValidationContext(
    val authenticatedGuest: Int,
    val wireSource: Int,
    proposals: List<V9TransferProposal>,
    serverManifestSha256: V9ManifestDigest,
    clientManifestSha256: V9ManifestDigest,
) {
  private val serverDigest = V9ManifestDigest(serverManifestSha256.bytes())
  private val clientDigest = V9ManifestDigest(clientManifestSha256.bytes())
  private val proposalMap =
      Collections.unmodifiableMap(proposals.associateBy { it.proposalId })

  init {
    require(authenticatedGuest in 1..3)
    require(wireSource == 0 || wireSource == authenticatedGuest)
    require(proposalMap.size == proposals.size)
  }

  internal fun proposal(id: Long): V9TransferProposal? = proposalMap[id]

  internal fun serverDigestView(): ByteArray = serverDigest.view()

  internal fun clientDigestView(): ByteArray = clientDigest.view()
}

object V9ConsentCodec {
  const val PAYLOAD_BYTES = 116

  fun encode(
      vote: V9ConsentVote,
      context: V9ConsentValidationContext,
  ): ByteArray {
    validate(vote, context)
    return ByteBuffer.allocate(PAYLOAD_BYTES).order(ByteOrder.BIG_ENDIAN)
        .putInt(vote.decisionId.toInt())
        .put(vote.actorPlayer.toByte())
        .put(vote.decision.wireId.toByte())
        .put(vote.transferClass.wireId.toByte())
        .put(vote.asset.wireId.toByte())
        .put(vote.sourcePlayer.toByte())
        .put(vote.targetPlayer.toByte())
        .put(vote.ownerPlayer.toByte())
        .put(0)
        .putInt(vote.proposalId.toInt())
        .putInt(vote.expectedSize.toInt())
        .put(vote.expectedDigestView())
        .put(vote.serverDigestView())
        .put(vote.clientDigestView())
        .array()
  }

  fun decode(
      payload: ByteArray,
      context: V9ConsentValidationContext,
  ): V9ConsentVote {
    if (payload.size != PAYLOAD_BYTES || (payload[11].toInt() and 0xff) != 0) mismatch(payload.size)
    val decision =
        V9ConsentDecision.fromWireId(payload[5].toInt() and 0xff)
            ?: mismatch(payload.size)
    val transferClass =
        V9TransferClass.fromWireId(payload[6].toInt() and 0xff)
            ?: mismatch(payload.size)
    val asset =
        V9TransferAsset.fromWireId(payload[7].toInt() and 0xff)
            ?: mismatch(payload.size)
    val vote =
        V9ConsentVote(
            decisionId = u32(payload, 0),
            actorPlayer = payload[4].toInt() and 0xff,
            decision = decision,
            transferClass = transferClass,
            asset = asset,
            sourcePlayer = payload[8].toInt() and 0xff,
            targetPlayer = payload[9].toInt() and 0xff,
            ownerPlayer = payload[10].toInt() and 0xff,
            proposalId = u32(payload, 12),
            expectedSize = u32(payload, 16),
            expectedSha256 = V9ManifestDigest(payload.copyOfRange(20, 52)),
            serverManifestSha256 = V9ManifestDigest(payload.copyOfRange(52, 84)),
            clientManifestSha256 = V9ManifestDigest(payload.copyOfRange(84, 116)),
        )
    validate(vote, context)
    return vote
  }

  fun forProposal(
      proposal: V9TransferProposal,
      actorPlayer: Int,
      decision: V9ConsentDecision,
      serverManifestSha256: V9ManifestDigest,
      clientManifestSha256: V9ManifestDigest,
  ): V9ConsentVote =
      V9ConsentVote(
          proposal.proposalId,
          actorPlayer,
          decision,
          proposal.transferClass,
          proposal.asset,
          proposal.sourcePlayer,
          proposal.targetPlayer,
          proposal.ownerPlayer,
          proposal.proposalId,
          proposal.expectedSize,
          proposal.expectedSha256(),
          serverManifestSha256,
          clientManifestSha256,
      )

  fun validate(
      vote: V9ConsentVote,
      context: V9ConsentValidationContext,
  ) {
    val proposal = context.proposal(vote.proposalId) ?: mismatch()
    if (vote.decisionId !in 1..ProtocolV9.U32_MAX ||
        vote.decisionId != vote.proposalId ||
        vote.actorPlayer != context.wireSource ||
        vote.actorPlayer !in setOf(proposal.sourcePlayer, proposal.targetPlayer) ||
        vote.transferClass != proposal.transferClass ||
        vote.asset != proposal.asset ||
        vote.asset.transferClass != vote.transferClass ||
        vote.sourcePlayer != proposal.sourcePlayer ||
        vote.targetPlayer != proposal.targetPlayer ||
        vote.ownerPlayer != proposal.ownerPlayer ||
        vote.expectedSize != proposal.expectedSize ||
        !MessageDigest.isEqual(vote.expectedDigestView(), proposal.expectedDigestView()) ||
        !MessageDigest.isEqual(vote.serverDigestView(), context.serverDigestView()) ||
        !MessageDigest.isEqual(vote.clientDigestView(), context.clientDigestView())) {
      mismatch()
    }
  }

  private fun mismatch(decisive: Int = PAYLOAD_BYTES): Nothing =
      throw V9ProtocolException(V9ErrorCode.CONSENT_REJECTED, decisive)
}

/** Sanitized immutable proposal metadata suitable for controller and Swing presentation. */
data class V9ConsentItem(
    val proposalId: Long,
    val transferClass: V9TransferClass,
    val asset: V9TransferAsset,
    val ownerPlayer: Int,
    val sourcePlayer: Int,
    val targetPlayer: Int,
    val expectedSize: Long,
) {
  companion object {
    internal fun from(proposal: V9TransferProposal): V9ConsentItem =
        V9ConsentItem(
            proposal.proposalId,
            proposal.transferClass,
            proposal.asset,
            proposal.ownerPlayer,
            proposal.sourcePlayer,
            proposal.targetPlayer,
            proposal.expectedSize,
        )
  }
}

enum class V9ConsentItemState {
  WAITING_FOR_LOCAL_DECISION,
  WAITING_FOR_PEER_DECISION,
  APPROVED,
  TRANSFERRING,
  PREPARED,
  REJECTED,
}

data class V9ConsentItemProgress(
    val item: V9ConsentItem,
    val state: V9ConsentItemState,
    val transferredBytes: Long,
)

class V9Part3Progress(
    val lifecycle: V9LifecycleState,
    sourceItems: Collection<V9ConsentItemProgress>,
    val preparationComplete: Boolean,
    val failure: V9ErrorCode?,
) {
  val items: List<V9ConsentItemProgress> =
      Collections.unmodifiableList(sourceItems.toList())

  override fun toString(): String =
      "V9Part3Progress(lifecycle=$lifecycle, items=${items.size}, " +
          "complete=$preparationComplete, failure=$failure, content=[redacted])"
}

fun interface V9Part3ProgressListener {
  fun onProgress(progress: V9Part3Progress)
}

interface V9Part3ProgressSource {
  fun progress(): V9Part3Progress?

  fun addProgressListener(listener: V9Part3ProgressListener): Closeable
}

/** Lazy, caller-owned source. It is opened only after both exact consent votes are accepted. */
interface V9BulkSource : Closeable {
  val length: Long

  fun read(bytes: ByteArray, offset: Int, length: Int): Int
}

fun interface V9BulkSourceProvider {
  fun open(item: V9ConsentItem): V9BulkSource
}

/**
 * One complete verified private payload. The receiving callback owns it after a normal return and
 * must close it; a failed/discarded callback is wiped by the foundation.
 */
class V9CompletedBulkCandidate internal constructor(
    val item: V9ConsentItem,
    bytes: ByteArray,
) : Closeable {
  private val closed = AtomicBoolean(false)
  private val owned = bytes

  val size: Int get() = owned.size

  fun bytes(): ByteArray {
    check(!closed.get()) { "v9 private candidate is closed" }
    return owned.copyOf()
  }

  override fun close() {
    if (closed.compareAndSet(false, true)) owned.fill(0)
  }

  override fun toString(): String =
      "V9CompletedBulkCandidate(item=${item.proposalId}, class=${item.transferClass}, " +
          "size=$size, content=[redacted])"
}

fun interface V9BulkCandidateSink {
  /**
   * Accepts ownership of [candidate] after exact length and SHA-256 verification. Throwing rejects
   * the transfer and leaves cleanup to the foundation.
   */
  fun accept(candidate: V9CompletedBulkCandidate)
}

fun interface V9TransactionIdSource {
  fun nextId(): Long

  companion object {
    val SECURE =
        V9TransactionIdSource {
          val random = SecureRandom()
          var id: Long
          do {
            val bytes = ByteArray(4)
            random.nextBytes(bytes)
            id = u32(bytes, 0)
            bytes.fill(0)
          } while (id == 0L)
          id
        }
  }
}

class V9GuestPart3Plan(
    sourceProviders: Map<Long, V9BulkSourceProvider>,
    val sink: V9BulkCandidateSink,
    val compressChunks: Boolean = false,
    val transactionIds: V9TransactionIdSource = V9TransactionIdSource.SECURE,
) {
  private val providers = Collections.unmodifiableMap(sourceProviders.toMap())

  internal fun sourceProvider(proposalId: Long): V9BulkSourceProvider? = providers[proposalId]

  internal fun configuredSourceIds(): Set<Long> = providers.keys
}

/** Explicit opt-in plan. Merely supplying MANIFEST metadata never enables private traffic. */
class V9Part3Plan private constructor(
    val role: V9Role,
    val mode: V9LinkMode,
    plansByGuest: Map<Int, V9GuestPart3Plan>,
) {
  private val plans = Collections.unmodifiableMap(plansByGuest.toMap())

  init {
    require(plans.isNotEmpty())
    require(plans.keys.all { if (mode == V9LinkMode.NORMAL) it == 1 else it in 1..3 })
    if (role == V9Role.CLIENT) require(plans.size == 1)
  }

  internal fun forGuest(role: V9Role, guest: Int): V9GuestPart3Plan? =
      if (role == this.role) plans[guest] else null

  companion object {
    fun server(
        mode: V9LinkMode,
        plansByGuest: Map<Int, V9GuestPart3Plan>,
    ): V9Part3Plan = V9Part3Plan(V9Role.SERVER, mode, plansByGuest)

    fun client(
        mode: V9LinkMode,
        guest: Int,
        plan: V9GuestPart3Plan,
    ): V9Part3Plan = V9Part3Plan(V9Role.CLIENT, mode, mapOf(guest to plan))
  }
}

class V9PreparationBoundary(
    val role: V9Role,
    val mode: V9LinkMode,
    val authenticatedGuest: Int,
    val state: V9LifecycleState,
    preparedItems: List<V9ConsentItem>,
) {
  val preparedItems: List<V9ConsentItem> =
      Collections.unmodifiableList(preparedItems.toList())

  override fun toString(): String =
      "V9PreparationBoundary(role=$role, mode=$mode, guest=$authenticatedGuest, " +
          "state=$state, prepared=${preparedItems.size}, content=[redacted])"
}

data class V9BulkBegin(
    val transactionId: Long,
    val proposalId: Long,
    val sourcePlayer: Int,
    val targetPlayer: Int,
    val ownerPlayer: Int,
    val asset: V9TransferAsset,
    val totalDecodedLength: Long,
    val completeSha256: V9ManifestDigest,
    val chunkSize: Long,
)

class V9BulkChunk private constructor(
    val transactionId: Long,
    val absoluteOffset: Long,
    data: ByteArray,
    takeOwnership: Boolean,
) : Closeable {
  private val ownedData = if (takeOwnership) data else data.copyOf()

  constructor(
      transactionId: Long,
      absoluteOffset: Long,
      data: ByteArray,
  ) : this(transactionId, absoluteOffset, data, false)

  private val closed = AtomicBoolean(false)

  fun data(): ByteArray {
    check(!closed.get()) { "v9 bulk chunk is closed" }
    return ownedData.copyOf()
  }

  internal fun dataView(): ByteArray {
    check(!closed.get()) { "v9 bulk chunk is closed" }
    return ownedData
  }

  override fun close() {
    if (closed.compareAndSet(false, true)) ownedData.fill(0)
  }

  override fun toString(): String =
      "V9BulkChunk(transaction=$transactionId, offset=$absoluteOffset, " +
          "bytes=${ownedData.size}, content=[redacted])"

  companion object {
    internal fun takeOwnership(
        transactionId: Long,
        absoluteOffset: Long,
        data: ByteArray,
    ): V9BulkChunk = V9BulkChunk(transactionId, absoluteOffset, data, true)
  }
}

data class V9BulkEnd(
    val transactionId: Long,
    val completeSha256: V9ManifestDigest,
)

object V9BulkCodec {
  const val BEGIN_BYTES = 52
  const val CHUNK_HEADER_BYTES = 8
  const val END_BYTES = 36

  fun encodeBegin(value: V9BulkBegin): ByteArray {
    validateBeginShape(value)
    return ByteBuffer.allocate(BEGIN_BYTES).order(ByteOrder.BIG_ENDIAN)
        .putInt(value.transactionId.toInt())
        .putInt(value.proposalId.toInt())
        .put(value.sourcePlayer.toByte())
        .put(value.targetPlayer.toByte())
        .put(value.ownerPlayer.toByte())
        .put(value.asset.wireId.toByte())
        .putInt(value.totalDecodedLength.toInt())
        .put(value.completeSha256.view())
        .putInt(value.chunkSize.toInt())
        .array()
  }

  fun decodeBegin(payload: ByteArray): V9BulkBegin {
    if (payload.size != BEGIN_BYTES) malformed(payload.size)
    val asset =
        V9TransferAsset.fromWireId(payload[11].toInt() and 0xff)
            ?: malformed(payload.size)
    val result =
        V9BulkBegin(
            u32(payload, 0),
            u32(payload, 4),
            payload[8].toInt() and 0xff,
            payload[9].toInt() and 0xff,
            payload[10].toInt() and 0xff,
            asset,
            u32(payload, 12),
            V9ManifestDigest(payload.copyOfRange(16, 48)),
            u32(payload, 48),
        )
    validateBeginShape(result)
    return result
  }

  fun encodeChunk(value: V9BulkChunk): ByteArray {
    if (value.transactionId !in 1..ProtocolV9.U32_MAX ||
        value.absoluteOffset !in 0..ProtocolV9.U32_MAX ||
        value.dataView().size !in 1..V9Limit.BULK_CHUNK_DECODED_BYTES.value.toInt()) {
      malformed()
    }
    return ByteBuffer.allocate(CHUNK_HEADER_BYTES + value.dataView().size)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(value.transactionId.toInt())
        .putInt(value.absoluteOffset.toInt())
        .put(value.dataView())
        .array()
  }

  fun decodeChunk(payload: ByteArray): V9BulkChunk {
    if (payload.size !in
        CHUNK_HEADER_BYTES + 1..
            CHUNK_HEADER_BYTES + V9Limit.BULK_CHUNK_DECODED_BYTES.value.toInt()) {
      malformed(payload.size)
    }
    val transaction = u32(payload, 0)
    if (transaction == 0L) malformed(payload.size)
    return V9BulkChunk.takeOwnership(
        transaction,
        u32(payload, 4),
        payload.copyOfRange(CHUNK_HEADER_BYTES, payload.size),
    )
  }

  fun encodeEnd(value: V9BulkEnd): ByteArray {
    if (value.transactionId !in 1..ProtocolV9.U32_MAX ||
        value.completeSha256.isZero()) {
      malformed()
    }
    return ByteBuffer.allocate(END_BYTES).order(ByteOrder.BIG_ENDIAN)
        .putInt(value.transactionId.toInt())
        .put(value.completeSha256.view())
        .array()
  }

  fun decodeEnd(payload: ByteArray): V9BulkEnd {
    if (payload.size != END_BYTES) malformed(payload.size)
    val result =
        V9BulkEnd(
            u32(payload, 0),
            V9ManifestDigest(payload.copyOfRange(4, 36)),
        )
    if (result.transactionId == 0L || result.completeSha256.isZero()) {
      malformed(payload.size)
    }
    return result
  }

  fun messageClass(type: V9MessageType): V9TransferClass? = when (type) {
    V9MessageType.ROM_BEGIN,
    V9MessageType.ROM_CHUNK,
    V9MessageType.ROM_END -> V9TransferClass.ROM
    V9MessageType.BATTERY_BEGIN,
    V9MessageType.BATTERY_CHUNK,
    V9MessageType.BATTERY_END -> V9TransferClass.BATTERY
    else -> null
  }

  private fun validateBeginShape(value: V9BulkBegin) {
    val limit =
        when (value.asset.transferClass) {
          V9TransferClass.ROM -> V9Limit.ROM_BYTES.value
          V9TransferClass.BATTERY -> V9Limit.BATTERY_BYTES.value
          V9TransferClass.CHECKPOINT -> malformed()
        }
    if (value.transactionId !in 1..ProtocolV9.U32_MAX ||
        value.proposalId !in 1..ProtocolV9.U32_MAX ||
        value.sourcePlayer !in 0..3 ||
        value.targetPlayer !in 0..3 ||
        value.sourcePlayer == value.targetPlayer ||
        value.ownerPlayer !in 0..3 ||
        value.totalDecodedLength !in 1..limit ||
        value.completeSha256.isZero() ||
        value.chunkSize !in 1..V9Limit.BULK_CHUNK_DECODED_BYTES.value) {
      malformed()
    }
  }

  private fun malformed(decisive: Int = 0): Nothing =
      throw V9ProtocolException(V9ErrorCode.MALFORMED_HEADER, decisive)
}

internal class V9Part3Session(
    private val role: V9Role,
    private val mode: V9LinkMode,
    private val authenticatedGuest: Int,
    private val plan: V9GuestPart3Plan,
    private val clock: V9MonotonicClock,
    private val schedule: (Long, Runnable) -> Closeable,
    private val startTask: (String, () -> Unit) -> Thread,
    private val send:
        (
            V9MessageType,
            Int,
            Long,
            ByteArray,
            () -> Unit,
        ) -> Boolean,
    private val transitionToSynchronizing: () -> Unit,
    private val fail: (V9ErrorCode, V9Diagnostic) -> Unit,
    private val onPreparationComplete: (V9PreparationBoundary) -> Unit,
) : Closeable, V9Part3ProgressSource {
  private val lock = Any()
  private val listeners = CopyOnWriteArrayList<V9Part3ProgressListener>()
  private var boundary: V9ManifestPairingBoundary? = null
  private var proposals = emptyList<V9TransferProposal>()
  private var proposalById = emptyMap<Long, V9TransferProposal>()
  private val votes = mutableMapOf<Long, MutableMap<Int, V9ConsentDecision>>()
  private val localQueued = mutableSetOf<Long>()
  private val claimed = mutableSetOf<Long>()
  private val prepared = mutableSetOf<Long>()
  private val rejected = mutableSetOf<Long>()
  private val transferred = mutableMapOf<Long, Long>()
  private val usedTransactionIds = mutableSetOf<Long>()
  private val writeWaiters = mutableSetOf<CountDownLatch>()
  private var inbound: InboundTransaction? = null
  private var pendingDelivery: PendingDelivery? = null
  private var activeSource: V9BulkSource? = null
  private var bulkDeadline: Closeable? = null
  private var closed = false
  private var preparationPublished = false
  private var terminalFailure: V9ErrorCode? = null

  fun start(value: V9ManifestPairingBoundary) {
    var complete: V9PreparationBoundary? = null
    synchronized(lock) {
      if (closed) return
      check(boundary == null) { "v9 Part-3 session already started" }
      check(value.role == role && value.mode == mode &&
          value.authenticatedGuest == authenticatedGuest)
      boundary = value
      proposals = value.proposals.sortedBy { it.proposalId }
      proposalById = proposals.associateBy { it.proposalId }
      check(proposalById.size == proposals.size)
      if (proposals.size > V9Limit.MANIFEST_PROPOSALS.value ||
          proposals.any {
            it.transferClass !in setOf(V9TransferClass.ROM, V9TransferClass.BATTERY)
          }) {
        protocolFailure(V9ErrorCode.CONSENT_REJECTED)
        return
      }
      val local = localActor()
      if (plan.configuredSourceIds().any { id ->
            val proposal = proposalById[id]
            proposal == null ||
                proposal.sourcePlayer != local ||
                proposal.transferClass !in setOf(V9TransferClass.ROM, V9TransferClass.BATTERY)
          }) {
        protocolFailure(V9ErrorCode.CONSENT_REJECTED)
        return
      }
      proposals.forEach { transferred[it.proposalId] = 0 }
      if (proposals.isEmpty()) complete = preparationBoundaryLocked()
    }
    publish()
    complete?.let(onPreparationComplete)
  }

  fun items(): List<V9ConsentItem> =
      synchronized(lock) { proposals.map(V9ConsentItem::from) }

  fun submitConsent(proposalId: Long, decision: V9ConsentDecision) {
    val payload: ByteArray
    synchronized(lock) {
      ensureOpen()
      val value = requireNotNull(boundary) { "v9 manifest boundary is not ready" }
      check(value.state == V9LifecycleState.EXCHANGE_CONSENT) {
        "v9 session has no pending consent"
      }
      val proposal =
          proposalById[proposalId]
              ?: throw V9ProtocolException(V9ErrorCode.CONSENT_REJECTED, 0)
      val actor = localActor()
      if (actor !in setOf(proposal.sourcePlayer, proposal.targetPlayer) ||
          votes[proposalId]?.containsKey(actor) == true ||
          !localQueued.add(proposalId)) {
        throw V9ProtocolException(V9ErrorCode.CONSENT_REJECTED, 0)
      }
      payload =
          V9ConsentCodec.encode(
              V9ConsentCodec.forProposal(
                  proposal,
                  actor,
                  decision,
                  value.serverPayloadSha256(),
                  value.clientPayloadSha256(),
              ),
              consentContext(actor),
          )
    }
    val accepted =
        send(
            V9MessageType.CONSENT,
            0,
            ProtocolV9.CONTROL_CHANNEL,
            payload,
        ) {
          onLocalVoteWritten(proposalId, decision)
        }
    payload.fill(0)
    if (!accepted) {
      synchronized(lock) { localQueued.remove(proposalId) }
      failSession(V9ErrorCode.QUEUE_OVERFLOW, V9Diagnostic.QUEUE_FULL)
    }
  }

  fun handleConsent(payload: ByteArray) {
    val vote =
        try {
          V9ConsentCodec.decode(payload, consentContext(peerActor()))
        } catch (e: V9ProtocolException) {
          failSession(e.reason, V9Diagnostic.CONSENT_REJECTED)
          return
        }
    var consentComplete = false
    synchronized(lock) {
      if (closed || !recordVoteLocked(vote.proposalId, vote.actorPlayer, vote.decision)) {
        protocolFailure(V9ErrorCode.CONSENT_REJECTED)
        return
      }
      if (vote.decision == V9ConsentDecision.REJECT) {
        rejected += vote.proposalId
        protocolFailure(V9ErrorCode.CONSENT_REJECTED)
        return
      }
      consentComplete = allApprovedLocked()
    }
    publish()
    if (consentComplete) beginSynchronization()
  }

  fun headerAdmission(
      type: V9MessageType,
      flags: Int,
      channel: Long,
      encodedLength: Long,
      decodedLength: Long,
  ): V9ErrorCode? {
    synchronized(lock) {
      if (closed) return V9ErrorCode.UNEXPECTED_MESSAGE
      if (type == V9MessageType.CONSENT) {
        val admissible =
            proposals.isNotEmpty() &&
                !allApprovedLocked() &&
                flags == 0 &&
                channel == ProtocolV9.CONTROL_CHANNEL &&
                encodedLength == V9ConsentCodec.PAYLOAD_BYTES.toLong() &&
                decodedLength == V9ConsentCodec.PAYLOAD_BYTES.toLong()
        return if (admissible) {
          null
        } else {
          V9ErrorCode.UNEXPECTED_MESSAGE
        }
      }
      return when (type) {
          V9MessageType.ROM_BEGIN,
          V9MessageType.BATTERY_BEGIN -> {
            val transferClass = requireNotNull(V9BulkCodec.messageClass(type))
            if (!allApprovedLocked() ||
                inbound != null ||
                pendingDelivery != null ||
                flags != 0 ||
                channel !in 1L..4L ||
                encodedLength != V9BulkCodec.BEGIN_BYTES.toLong() ||
                decodedLength != V9BulkCodec.BEGIN_BYTES.toLong() ||
                !hasInboundGrantLocked(transferClass)) {
              V9ErrorCode.CONSENT_REJECTED
            } else {
              null
            }
          }
          V9MessageType.ROM_CHUNK,
          V9MessageType.BATTERY_CHUNK -> {
            val current = inbound
            if (current == null ||
                current.transferClass != V9BulkCodec.messageClass(type) ||
                channel != current.channel ||
                decodedLength !in
                    (V9BulkCodec.CHUNK_HEADER_BYTES + 1).toLong()..
                        (V9BulkCodec.CHUNK_HEADER_BYTES +
                            V9Limit.BULK_CHUNK_DECODED_BYTES.value)) {
              V9ErrorCode.CONSENT_REJECTED
            } else {
              null
            }
          }
          V9MessageType.ROM_END,
          V9MessageType.BATTERY_END -> {
            val current = inbound
            if (current == null ||
                current.transferClass != V9BulkCodec.messageClass(type) ||
                flags != 0 ||
                channel != current.channel ||
                encodedLength != V9BulkCodec.END_BYTES.toLong() ||
                decodedLength != V9BulkCodec.END_BYTES.toLong()) {
              V9ErrorCode.CONSENT_REJECTED
            } else {
              null
            }
          }
          else -> null
      }
    }
  }

  fun handleBulk(type: V9MessageType, flags: Int, channel: Long, payload: ByteArray) {
    try {
      when (type) {
        V9MessageType.ROM_BEGIN,
        V9MessageType.BATTERY_BEGIN ->
          acceptBegin(type, channel, V9BulkCodec.decodeBegin(payload))
        V9MessageType.ROM_CHUNK,
        V9MessageType.BATTERY_CHUNK ->
          acceptChunk(type, flags, channel, payload)
        V9MessageType.ROM_END,
        V9MessageType.BATTERY_END ->
          acceptEnd(type, channel, V9BulkCodec.decodeEnd(payload))
        else -> protocolFailure(V9ErrorCode.UNEXPECTED_MESSAGE)
      }
    } catch (e: V9ProtocolException) {
      failSession(e.reason, V9Diagnostic.TRANSFER_REJECTED)
    } catch (_: RuntimeException) {
      failSession(V9ErrorCode.INTERNAL_ERROR, V9Diagnostic.TRANSFER_REJECTED)
    }
  }

  override fun progress(): V9Part3Progress? =
      synchronized(lock) { boundary?.let { progressLocked() } }

  override fun addProgressListener(listener: V9Part3ProgressListener): Closeable {
    val active = AtomicBoolean(true)
    val initial =
        synchronized(lock) {
          if (closed) {
            active.set(false)
            null
          } else {
            listeners += listener
            boundary?.let { progressLocked() }
          }
        }
    if (initial != null) {
      try {
        listener.onProgress(initial)
      } catch (_: RuntimeException) {
        // An initial observer failure cannot affect protocol or transfer ownership.
      }
    }
    return Closeable {
      if (active.compareAndSet(true, false)) listeners.remove(listener)
    }
  }

  override fun close() {
    val source: V9BulkSource?
    val retained: ByteArray?
    val delivery: PendingDelivery?
    synchronized(lock) {
      if (closed) return
      closed = true
      bulkDeadline?.close()
      bulkDeadline = null
      source = activeSource
      activeSource = null
      retained = inbound?.bytes
      inbound = null
      delivery = pendingDelivery
      pendingDelivery = null
      writeWaiters.forEach(CountDownLatch::countDown)
      writeWaiters.clear()
    }
    retained?.fill(0)
    delivery?.candidate?.close()
    delivery?.completed?.countDown()
    try {
      source?.close()
    } catch (_: IOException) {
      // The connection is already closing; caller source details remain private.
    } catch (_: RuntimeException) {
      // The connection is already closing; caller source details remain private.
    }
    publish()
    listeners.clear()
  }

  @Throws(InterruptedException::class)
  fun awaitPendingDelivery() {
    while (true) {
      val current = synchronized(lock) { if (closed) null else pendingDelivery } ?: return
      if (current.completed.await(100, TimeUnit.MILLISECONDS)) return
    }
  }

  private fun onLocalVoteWritten(proposalId: Long, decision: V9ConsentDecision) {
    var consentComplete = false
    synchronized(lock) {
      if (closed) return
      localQueued.remove(proposalId)
      if (!recordVoteLocked(proposalId, localActor(), decision)) {
        protocolFailure(V9ErrorCode.CONSENT_REJECTED)
        return
      }
      if (decision == V9ConsentDecision.REJECT) {
        rejected += proposalId
        protocolFailure(V9ErrorCode.CONSENT_REJECTED)
        return
      }
      consentComplete = allApprovedLocked()
    }
    publish()
    if (consentComplete) beginSynchronization()
  }

  private fun recordVoteLocked(
      proposalId: Long,
      actor: Int,
      decision: V9ConsentDecision,
  ): Boolean {
    val proposal = proposalById[proposalId] ?: return false
    if (actor !in setOf(proposal.sourcePlayer, proposal.targetPlayer)) return false
    val proposalVotes = votes.getOrPut(proposalId) { mutableMapOf() }
    if (proposalVotes.putIfAbsent(actor, decision) != null) return false
    return true
  }

  private fun allApprovedLocked(): Boolean =
      proposals.all { proposal ->
        votes[proposal.proposalId]?.let { proposalVotes ->
          proposalVotes.size == 2 &&
              proposalVotes.keys == setOf(proposal.sourcePlayer, proposal.targetPlayer) &&
              proposalVotes.values.all { it == V9ConsentDecision.APPROVE }
        } == true
      }

  private fun beginSynchronization() {
    synchronized(lock) {
      if (closed || !allApprovedLocked()) return
    }
    transitionToSynchronizing()
    publish()
    advanceTransfer()
  }

  private fun advanceTransfer() {
    var sourceProposal: V9TransferProposal? = null
    var complete: V9PreparationBoundary? = null
    synchronized(lock) {
      if (closed) return
      val next =
          proposals.firstOrNull {
            it.proposalId !in prepared &&
                it.transferClass in setOf(V9TransferClass.ROM, V9TransferClass.BATTERY)
          }
      if (next == null) {
        if (proposals.all { it.proposalId in prepared }) {
          complete = preparationBoundaryLocked()
        }
      } else if (next.sourcePlayer == localActor() &&
          next.proposalId !in claimed &&
          activeSource == null) {
        if (!claimed.add(next.proposalId)) {
          protocolFailure(V9ErrorCode.CONSENT_REJECTED)
          return
        }
        sourceProposal = next
      }
    }
    publish()
    complete?.let(onPreparationComplete)
    sourceProposal?.let { proposal ->
      try {
        startTask("netplay-v9-private-source") { transmit(proposal) }
      } catch (_: RuntimeException) {
        failSession(V9ErrorCode.INTERNAL_ERROR, V9Diagnostic.TRANSFER_REJECTED)
      }
    }
  }

  private fun transmit(proposal: V9TransferProposal) {
    val item = V9ConsentItem.from(proposal)
    val provider = plan.sourceProvider(proposal.proposalId)
    if (provider == null) {
      failSession(V9ErrorCode.INTERNAL_ERROR, V9Diagnostic.TRANSFER_REJECTED)
      return
    }
    var source: V9BulkSource? = null
    var sourcePublished = false
    val chunkBuffer = ByteArray(V9Limit.BULK_CHUNK_DECODED_BYTES.value.toInt())
    try {
      source = provider.open(item)
      synchronized(lock) {
        if (closed) return
        activeSource = source
        sourcePublished = true
      }
      if (source.length != proposal.expectedSize) {
        throw V9ProtocolException(V9ErrorCode.CHECKSUM_MISMATCH, 0)
      }
      val transactionId = plan.transactionIds.nextId()
      synchronized(lock) {
        if (transactionId !in 1..ProtocolV9.U32_MAX ||
            !usedTransactionIds.add(transactionId)) {
          throw V9ProtocolException(V9ErrorCode.CONSENT_REJECTED, 0)
        }
      }
      val begin =
          V9BulkBegin(
              transactionId,
              proposal.proposalId,
              proposal.sourcePlayer,
              proposal.targetPlayer,
              proposal.ownerPlayer,
              proposal.asset,
              proposal.expectedSize,
              proposal.expectedSha256(),
              V9Limit.BULK_CHUNK_DECODED_BYTES.value,
          )
      if (!sendAndWait(
              beginType(proposal.transferClass),
              0,
              playerChannel(proposal.ownerPlayer),
              V9BulkCodec.encodeBegin(begin),
          )) return
      validProgress(proposal.proposalId, 0)

      val digest = MessageDigest.getInstance("SHA-256")
      var offset = 0L
      while (offset < proposal.expectedSize) {
        val requested =
            minOf(chunkBuffer.size.toLong(), proposal.expectedSize - offset).toInt()
        val count = source.read(chunkBuffer, 0, requested)
        if (count <= 0 || count > requested) {
          throw V9ProtocolException(V9ErrorCode.CHECKSUM_MISMATCH, 0)
        }
        digest.update(chunkBuffer, 0, count)
        val data = chunkBuffer.copyOf(count)
        val chunk = V9BulkChunk(transactionId, offset, data)
        val payload =
            try {
              V9BulkCodec.encodeChunk(chunk)
            } finally {
              chunk.close()
              data.fill(0)
            }
        val flags =
            if (plan.compressChunks) V9Flag.DEFLATE.wireMask else 0
        if (!sendAndWait(
                chunkType(proposal.transferClass),
                flags,
                playerChannel(proposal.ownerPlayer),
                payload,
            )) return
        offset = Math.addExact(offset, count.toLong())
        validProgress(proposal.proposalId, offset)
      }
      if (source.read(chunkBuffer, 0, 1) >= 0 ||
          !MessageDigest.isEqual(digest.digest(), proposal.expectedDigestView())) {
        throw V9ProtocolException(V9ErrorCode.CHECKSUM_MISMATCH, 0)
      }
      if (!sendAndWait(
              endType(proposal.transferClass),
              0,
              playerChannel(proposal.ownerPlayer),
              V9BulkCodec.encodeEnd(
                  V9BulkEnd(transactionId, proposal.expectedSha256()),
              ),
          )) return
      markPrepared(proposal.proposalId)
    } catch (e: V9ProtocolException) {
      failSession(e.reason, V9Diagnostic.TRANSFER_REJECTED)
    } catch (_: InterruptedException) {
      if (!isClosed()) failSession(V9ErrorCode.CANCELLED, V9Diagnostic.CANCELLED)
      Thread.currentThread().interrupt()
    } catch (_: IOException) {
      failSession(V9ErrorCode.INTERNAL_ERROR, V9Diagnostic.TRANSFER_REJECTED)
    } catch (_: RuntimeException) {
      failSession(V9ErrorCode.INTERNAL_ERROR, V9Diagnostic.TRANSFER_REJECTED)
    } finally {
      chunkBuffer.fill(0)
      val closeHere =
          synchronized(lock) {
            if (activeSource === source) {
              activeSource = null
              true
            } else {
              !sourcePublished
            }
          }
      if (closeHere) {
        try {
          source?.close()
        } catch (_: IOException) {
          if (!isClosed()) {
            failSession(V9ErrorCode.INTERNAL_ERROR, V9Diagnostic.TRANSFER_REJECTED)
          }
        } catch (_: RuntimeException) {
          if (!isClosed()) {
            failSession(V9ErrorCode.INTERNAL_ERROR, V9Diagnostic.TRANSFER_REJECTED)
          }
        }
      }
      val continueWithNext =
          synchronized(lock) { !closed && proposal.proposalId in prepared }
      if (continueWithNext) advanceTransfer()
    }
  }

  private fun acceptBegin(type: V9MessageType, channel: Long, value: V9BulkBegin) {
    synchronized(lock) {
      ensureOpen()
      val proposal =
          proposalById[value.proposalId]
              ?: throw V9ProtocolException(V9ErrorCode.CONSENT_REJECTED, 0)
      val transferClass = requireNotNull(V9BulkCodec.messageClass(type))
      if (!allApprovedLocked() ||
          proposal.proposalId in claimed ||
          proposal.proposalId in prepared ||
          pendingDelivery != null ||
          value.transactionId in usedTransactionIds ||
          proposal.transferClass != transferClass ||
          value.sourcePlayer != peerActor() ||
          value.targetPlayer != localActor() ||
          value.sourcePlayer != proposal.sourcePlayer ||
          value.targetPlayer != proposal.targetPlayer ||
          value.ownerPlayer != proposal.ownerPlayer ||
          value.asset != proposal.asset ||
          value.totalDecodedLength != proposal.expectedSize ||
          !MessageDigest.isEqual(
              value.completeSha256.view(),
              proposal.expectedDigestView(),
          ) ||
          channel != playerChannel(proposal.ownerPlayer) ||
          inbound != null) {
        throw V9ProtocolException(V9ErrorCode.CONSENT_REJECTED, 0)
      }
      claimed += proposal.proposalId
      usedTransactionIds += value.transactionId
      inbound =
          InboundTransaction(
              value.transactionId,
              proposal,
              transferClass,
              channel,
              value.chunkSize.toInt(),
              ByteArray(value.totalDecodedLength.toInt()),
              MessageDigest.getInstance("SHA-256"),
          )
    }
    validProgress(value.proposalId, 0)
  }

  private fun acceptChunk(
      type: V9MessageType,
      flags: Int,
      channel: Long,
      payload: ByteArray,
  ) {
    val chunk = V9BulkCodec.decodeChunk(payload)
    try {
      synchronized(lock) {
        ensureOpen()
        val current =
            inbound ?: throw V9ProtocolException(V9ErrorCode.CONSENT_REJECTED, 0)
        val data = chunk.dataView()
        val next = try {
          Math.addExact(current.offset, data.size.toLong())
        } catch (_: ArithmeticException) {
          throw V9ProtocolException(V9ErrorCode.LIMIT_EXCEEDED, 0)
        }
        if (current.transferClass != V9BulkCodec.messageClass(type) ||
            current.channel != channel ||
            current.transactionId != chunk.transactionId ||
            current.offset != chunk.absoluteOffset ||
            data.size > current.chunkSize ||
            next > current.bytes.size.toLong() ||
            flags and V9Flag.KNOWN_MASK.inv() != 0) {
          throw V9ProtocolException(V9ErrorCode.CONSENT_REJECTED, 0)
        }
        System.arraycopy(data, 0, current.bytes, current.offset.toInt(), data.size)
        current.digest.update(data)
        current.offset = next
        transferred[current.proposal.proposalId] = next
      }
      validProgress(requireNotNull(inboundProposalId()), transferredFor(inboundProposalId()))
    } finally {
      chunk.close()
    }
  }

  private fun acceptEnd(type: V9MessageType, channel: Long, value: V9BulkEnd) {
    val transaction: InboundTransaction
    val delivery: PendingDelivery
    synchronized(lock) {
      ensureOpen()
      transaction =
          inbound ?: throw V9ProtocolException(V9ErrorCode.CONSENT_REJECTED, 0)
      if (transaction.transferClass != V9BulkCodec.messageClass(type) ||
          transaction.channel != channel ||
          transaction.transactionId != value.transactionId ||
          pendingDelivery != null ||
          transaction.offset != transaction.bytes.size.toLong() ||
          !MessageDigest.isEqual(value.completeSha256.view(), transaction.proposal.expectedDigestView()) ||
          !MessageDigest.isEqual(transaction.digest.digest(), transaction.proposal.expectedDigestView())) {
        throw V9ProtocolException(V9ErrorCode.CHECKSUM_MISMATCH, 0)
      }
      inbound = null
      delivery =
          PendingDelivery(
              transaction.proposal,
              V9CompletedBulkCandidate(
                  V9ConsentItem.from(transaction.proposal),
                  transaction.bytes,
              ),
          )
      pendingDelivery = delivery
    }
    validProgress(transaction.proposal.proposalId, transaction.offset)
    try {
      startTask("netplay-v9-private-sink") { deliver(delivery) }
    } catch (_: RuntimeException) {
      discardDelivery(delivery)
      failSession(V9ErrorCode.INTERNAL_ERROR, V9Diagnostic.TRANSFER_REJECTED)
    }
  }

  private fun deliver(delivery: PendingDelivery) {
    var handedOff = false
    try {
      if (isClosed()) return
      plan.sink.accept(delivery.candidate)
      handedOff =
          synchronized(lock) {
            if (!closed && pendingDelivery === delivery) {
              pendingDelivery = null
              true
            } else {
              false
            }
          }
      if (handedOff) markPrepared(delivery.proposal.proposalId)
    } catch (_: InterruptedException) {
      if (!isClosed()) failSession(V9ErrorCode.CANCELLED, V9Diagnostic.CANCELLED)
      Thread.currentThread().interrupt()
    } catch (_: Exception) {
      if (!isClosed()) {
        failSession(V9ErrorCode.INTERNAL_ERROR, V9Diagnostic.TRANSFER_REJECTED)
      }
    } finally {
      if (!handedOff) discardDelivery(delivery)
      delivery.completed.countDown()
    }
  }

  private fun discardDelivery(delivery: PendingDelivery) {
    synchronized(lock) {
      if (pendingDelivery === delivery) pendingDelivery = null
    }
    delivery.candidate.close()
    delivery.completed.countDown()
  }

  private fun markPrepared(proposalId: Long) {
    synchronized(lock) {
      if (closed || !prepared.add(proposalId)) {
        if (!closed) protocolFailure(V9ErrorCode.CONSENT_REJECTED)
        return
      }
      transferred[proposalId] = proposalById.getValue(proposalId).expectedSize
      bulkDeadline?.close()
      bulkDeadline = null
    }
    publish()
    advanceTransfer()
  }

  private fun sendAndWait(
      type: V9MessageType,
      flags: Int,
      channel: Long,
      payload: ByteArray,
  ): Boolean {
    val written = CountDownLatch(1)
    synchronized(lock) {
      if (closed) {
        payload.fill(0)
        return false
      }
      if (writeWaiters.size >= V9Limit.BULK_WINDOW_CHUNKS.value) {
        payload.fill(0)
        throw V9ProtocolException(V9ErrorCode.QUEUE_OVERFLOW, 0)
      }
      writeWaiters += written
    }
    val accepted =
        try {
          send(type, flags, channel, payload) { written.countDown() }
        } finally {
          payload.fill(0)
        }
    if (!accepted) {
      synchronized(lock) { writeWaiters.remove(written) }
      throw V9ProtocolException(V9ErrorCode.QUEUE_OVERFLOW, 0)
    }
    while (!written.await(100, TimeUnit.MILLISECONDS)) {
      if (isClosed()) return false
    }
    synchronized(lock) { writeWaiters.remove(written) }
    return !isClosed()
  }

  private fun validProgress(proposalId: Long, bytes: Long) {
    val deadline =
        try {
          Math.addExact(clock.nowMillis(), V9Timeout.BULK_PROGRESS.milliseconds)
        } catch (_: ArithmeticException) {
          Long.MAX_VALUE
        }
    synchronized(lock) {
      if (closed) return
      transferred[proposalId] = bytes
      bulkDeadline?.close()
      bulkDeadline =
          schedule(deadline) {
            if (clock.nowMillis() >= deadline && !isClosed()) {
              failSession(V9ErrorCode.TIMEOUT, V9Diagnostic.TIMEOUT)
            }
          }
    }
    publish()
  }

  private fun hasInboundGrantLocked(transferClass: V9TransferClass): Boolean =
      proposals.any {
        it.transferClass == transferClass &&
            it.sourcePlayer == peerActor() &&
            it.targetPlayer == localActor() &&
            it.proposalId !in claimed &&
            it.proposalId !in prepared
      }

  private fun consentContext(wireSource: Int): V9ConsentValidationContext {
    val value = requireNotNull(boundary)
    return V9ConsentValidationContext(
        authenticatedGuest,
        wireSource,
        proposals,
        value.serverPayloadSha256(),
        value.clientPayloadSha256(),
    )
  }

  private fun progressLocked(): V9Part3Progress {
    val lifecycle =
        when {
          closed -> V9LifecycleState.CLOSED
          !allApprovedLocked() && proposals.isNotEmpty() -> V9LifecycleState.EXCHANGE_CONSENT
          else -> V9LifecycleState.SYNCHRONIZING
        }
    val local = localActor()
    val progress =
        proposals.map { proposal ->
          val proposalVotes = votes[proposal.proposalId].orEmpty()
          val state =
              when {
                proposal.proposalId in rejected -> V9ConsentItemState.REJECTED
                proposal.proposalId in prepared -> V9ConsentItemState.PREPARED
                proposal.proposalId in claimed -> V9ConsentItemState.TRANSFERRING
                proposalVotes.size == 2 -> V9ConsentItemState.APPROVED
                local !in proposalVotes && proposal.proposalId !in localQueued ->
                  V9ConsentItemState.WAITING_FOR_LOCAL_DECISION
                else -> V9ConsentItemState.WAITING_FOR_PEER_DECISION
              }
          V9ConsentItemProgress(
              V9ConsentItem.from(proposal),
              state,
              transferred[proposal.proposalId] ?: 0,
          )
        }
    return V9Part3Progress(
        lifecycle,
        progress,
        preparationPublished,
        terminalFailure
            ?: if (rejected.isEmpty()) null else V9ErrorCode.CONSENT_REJECTED,
    )
  }

  private fun publish() {
    val value = progress() ?: return
    listeners.forEach {
      try {
        it.onProgress(value)
      } catch (_: RuntimeException) {
        // A presentation observer cannot affect transfer ownership.
      }
    }
  }

  private fun preparationBoundaryLocked(): V9PreparationBoundary? {
    if (preparationPublished) return null
    preparationPublished = true
    return V9PreparationBoundary(
        role,
        mode,
        authenticatedGuest,
        V9LifecycleState.SYNCHRONIZING,
        proposals.map(V9ConsentItem::from),
    )
  }

  private fun inboundProposalId(): Long? = synchronized(lock) { inbound?.proposal?.proposalId }

  private fun transferredFor(id: Long?): Long =
      synchronized(lock) { if (id == null) 0 else transferred[id] ?: 0 }

  private fun localActor(): Int = if (role == V9Role.SERVER) 0 else authenticatedGuest

  private fun peerActor(): Int = if (role == V9Role.SERVER) authenticatedGuest else 0

  private fun isClosed(): Boolean = synchronized(lock) { closed }

  private fun ensureOpen() {
    if (closed || boundary == null) {
      throw V9ProtocolException(V9ErrorCode.UNEXPECTED_MESSAGE, 0)
    }
  }

  private fun protocolFailure(reason: V9ErrorCode) {
    failSession(reason, V9Diagnostic.TRANSFER_REJECTED)
  }

  private fun failSession(reason: V9ErrorCode, diagnostic: V9Diagnostic) {
    synchronized(lock) {
      if (terminalFailure == null) terminalFailure = reason
    }
    fail(reason, diagnostic)
  }

  private fun playerChannel(owner: Int): Long {
    if (owner !in 0..3) throw V9ProtocolException(V9ErrorCode.CONSENT_REJECTED, 0)
    return owner.toLong() + 1
  }

  private fun beginType(value: V9TransferClass): V9MessageType =
      if (value == V9TransferClass.ROM) V9MessageType.ROM_BEGIN
      else if (value == V9TransferClass.BATTERY) V9MessageType.BATTERY_BEGIN
      else throw V9ProtocolException(V9ErrorCode.UNEXPECTED_MESSAGE, 0)

  private fun chunkType(value: V9TransferClass): V9MessageType =
      if (value == V9TransferClass.ROM) V9MessageType.ROM_CHUNK
      else if (value == V9TransferClass.BATTERY) V9MessageType.BATTERY_CHUNK
      else throw V9ProtocolException(V9ErrorCode.UNEXPECTED_MESSAGE, 0)

  private fun endType(value: V9TransferClass): V9MessageType =
      if (value == V9TransferClass.ROM) V9MessageType.ROM_END
      else if (value == V9TransferClass.BATTERY) V9MessageType.BATTERY_END
      else throw V9ProtocolException(V9ErrorCode.UNEXPECTED_MESSAGE, 0)

  private data class InboundTransaction(
      val transactionId: Long,
      val proposal: V9TransferProposal,
      val transferClass: V9TransferClass,
      val channel: Long,
      val chunkSize: Int,
      val bytes: ByteArray,
      val digest: MessageDigest,
      var offset: Long = 0,
  )

  private data class PendingDelivery(
      val proposal: V9TransferProposal,
      val candidate: V9CompletedBulkCandidate,
      val completed: CountDownLatch = CountDownLatch(1),
  )
}
