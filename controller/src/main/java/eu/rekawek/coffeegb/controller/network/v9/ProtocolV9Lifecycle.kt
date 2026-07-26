package eu.rekawek.coffeegb.controller.network.v9

import java.io.Closeable
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

fun interface V9MonotonicClock {
  fun nowMillis(): Long

  companion object {
    val SYSTEM = V9MonotonicClock { System.nanoTime() / 1_000_000L }
  }
}

enum class V9LifecycleState {
  SEND_SERVER_HELLO,
  WAIT_SERVER_HELLO,
  SEND_CLIENT_HELLO,
  WAIT_CLIENT_HELLO,
  SEND_AUTH,
  WAIT_AUTH,
  SEND_AUTH_RESULT,
  WAIT_AUTH_RESULT,
  SEND_SERVER_MANIFEST,
  WAIT_SERVER_MANIFEST,
  SEND_CLIENT_MANIFEST,
  WAIT_CLIENT_MANIFEST,
  EXCHANGE_CONSENT,
  SYNCHRONIZING,
  SEND_READY,
  WAIT_READY,
  ACTIVE,
  BULK_PROGRESS,
  TERMINAL_CLEANUP,
  CLOSED;

  fun timeout(): V9Timeout? = when (this) {
    SEND_SERVER_HELLO -> V9Timeout.SEND_SERVER_HELLO
    WAIT_SERVER_HELLO -> V9Timeout.WAIT_SERVER_HELLO
    SEND_CLIENT_HELLO -> V9Timeout.SEND_CLIENT_HELLO
    WAIT_CLIENT_HELLO -> V9Timeout.WAIT_CLIENT_HELLO
    SEND_AUTH -> V9Timeout.SEND_AUTH
    WAIT_AUTH -> V9Timeout.WAIT_AUTH
    SEND_AUTH_RESULT -> V9Timeout.SEND_AUTH_RESULT
    WAIT_AUTH_RESULT -> V9Timeout.WAIT_AUTH_RESULT
    SEND_SERVER_MANIFEST -> V9Timeout.SEND_SERVER_MANIFEST
    WAIT_SERVER_MANIFEST -> V9Timeout.WAIT_SERVER_MANIFEST
    SEND_CLIENT_MANIFEST -> V9Timeout.SEND_CLIENT_MANIFEST
    WAIT_CLIENT_MANIFEST -> V9Timeout.WAIT_CLIENT_MANIFEST
    EXCHANGE_CONSENT -> V9Timeout.EXCHANGE_CONSENT
    SYNCHRONIZING -> V9Timeout.SYNCHRONIZING
    SEND_READY -> V9Timeout.SEND_READY
    WAIT_READY -> V9Timeout.WAIT_READY
    ACTIVE -> V9Timeout.ACTIVE_IDLE
    BULK_PROGRESS -> V9Timeout.BULK_PROGRESS
    TERMINAL_CLEANUP -> V9Timeout.TERMINAL_CLEANUP
    CLOSED -> null
  }
}

enum class V9LifecyclePhase {
  NEGOTIATING,
  AWAITING_PAIRING,
  PAIRING,
  SYNCHRONIZING,
  ACTIVE,
  TERMINAL,
  CLOSED,
}

data class V9LifecycleSnapshot(
    val role: V9Role,
    val state: V9LifecycleState,
    val phase: V9LifecyclePhase,
    val deadlineMillis: Long?,
    val negotiatedCapabilities: Set<V9Capability>,
    val failure: V9Failure?,
)

fun interface V9LifecycleListener {
  fun onStateChanged(snapshot: V9LifecycleSnapshot)
}

interface V9LifecycleSource {
  fun snapshot(): V9LifecycleSnapshot

  fun addListener(listener: V9LifecycleListener): Closeable
}

/**
 * Controller-owned immutable lifecycle stream.
 *
 * Phase #347 advances only HELLO states. All later frozen states are explicit values, rather than
 * being inferred from unrelated emulator events, but their transitions remain unavailable until
 * their owning phases implement them.
 */
class V9Lifecycle(
    private val role: V9Role,
    private val clock: V9MonotonicClock = V9MonotonicClock.SYSTEM,
) : V9LifecycleSource {
  private val listeners = CopyOnWriteArrayList<V9LifecycleListener>()
  private var state =
      if (role == V9Role.SERVER) V9LifecycleState.SEND_SERVER_HELLO
      else V9LifecycleState.WAIT_SERVER_HELLO
  private var deadline = deadlineFor(state)
  private var capabilities: Set<V9Capability> = emptySet()
  private var failure: V9Failure? = null

  @Synchronized
  override fun snapshot(): V9LifecycleSnapshot =
      V9LifecycleSnapshot(
          role,
          state,
          phase(state),
          deadline,
          Collections.unmodifiableSet(capabilities.toSet()),
          failure,
      )

  override fun addListener(listener: V9LifecycleListener): Closeable {
    listeners += listener
    listener.onStateChanged(snapshot())
    return Closeable { listeners.remove(listener) }
  }

  @Synchronized
  fun serverHelloSent() {
    requireRoleState(V9Role.SERVER, V9LifecycleState.SEND_SERVER_HELLO)
    transition(V9LifecycleState.WAIT_CLIENT_HELLO)
  }

  @Synchronized
  fun serverHelloReceived() {
    requireRoleState(V9Role.CLIENT, V9LifecycleState.WAIT_SERVER_HELLO)
    transition(V9LifecycleState.SEND_CLIENT_HELLO)
  }

  @Synchronized
  fun clientHelloSent(negotiated: V9NegotiatedCapabilities) {
    requireRoleState(V9Role.CLIENT, V9LifecycleState.SEND_CLIENT_HELLO)
    capabilities = negotiated.capabilities.toSet()
    transition(V9LifecycleState.SEND_AUTH)
  }

  @Synchronized
  fun clientHelloReceived(negotiated: V9NegotiatedCapabilities) {
    requireRoleState(V9Role.SERVER, V9LifecycleState.WAIT_CLIENT_HELLO)
    capabilities = negotiated.capabilities.toSet()
    transition(V9LifecycleState.WAIT_AUTH)
  }

  @Synchronized
  fun clientAuthSent() {
    requireRoleState(V9Role.CLIENT, V9LifecycleState.SEND_AUTH)
    transition(V9LifecycleState.WAIT_AUTH_RESULT)
  }

  @Synchronized
  fun clientAuthReceived() {
    requireRoleState(V9Role.SERVER, V9LifecycleState.WAIT_AUTH)
    transition(V9LifecycleState.SEND_AUTH_RESULT)
  }

  @Synchronized
  fun serverAuthResultSent() {
    requireRoleState(V9Role.SERVER, V9LifecycleState.SEND_AUTH_RESULT)
    transition(V9LifecycleState.SEND_SERVER_MANIFEST)
  }

  @Synchronized
  fun serverAuthResultReceived() {
    requireRoleState(V9Role.CLIENT, V9LifecycleState.WAIT_AUTH_RESULT)
    transition(V9LifecycleState.WAIT_SERVER_MANIFEST)
  }

  @Synchronized
  fun checkDeadline(): V9Failure? {
    val currentDeadline = deadline ?: return failure
    if (clock.nowMillis() >= currentDeadline && state != V9LifecycleState.CLOSED) {
      val error = state.timeout()?.expiryError
      if (error == null) {
        closeNormally()
      } else {
        fail(error, V9Diagnostic.TIMEOUT)
      }
    }
    return failure
  }

  @Synchronized
  fun cancel(): V9Failure? {
    if (state != V9LifecycleState.CLOSED) {
      fail(V9ErrorCode.CANCELLED, V9Diagnostic.CANCELLED)
    }
    return failure
  }

  /**
   * Records the original local rejection and begins the frozen best-effort terminal drain.
   *
   * The failure stage is the state that rejected the peer, not `TERMINAL_CLEANUP`. Subsequent
   * cancellation, delivery failure, peer EOF, or cleanup expiry cannot replace that outcome.
   */
  @Synchronized
  fun beginTerminalCleanup(reason: V9ErrorCode, diagnostic: V9Diagnostic): V9Failure? {
    if (state == V9LifecycleState.CLOSED || state == V9LifecycleState.TERMINAL_CLEANUP) {
      return failure
    }
    val result = V9Failure(reason, state, diagnostic)
    failure = result
    transition(V9LifecycleState.TERMINAL_CLEANUP)
    return result
  }

  /** Completes terminal cleanup after peer EOF or a failed best-effort terminal write. */
  @Synchronized
  fun completeTerminalCleanup() {
    if (state == V9LifecycleState.TERMINAL_CLEANUP) closeNormally()
  }

  @Synchronized
  fun fail(reason: V9ErrorCode, diagnostic: V9Diagnostic): V9Failure? {
    if (state == V9LifecycleState.CLOSED) return failure
    if (state == V9LifecycleState.TERMINAL_CLEANUP && failure != null) {
      closeNormally()
      return failure
    }
    val result = V9Failure(reason, state, diagnostic)
    failure = result
    state = V9LifecycleState.CLOSED
    deadline = null
    publish()
    return result
  }

  @Synchronized
  fun closeNormally() {
    if (state != V9LifecycleState.CLOSED) {
      state = V9LifecycleState.CLOSED
      deadline = null
      publish()
    }
  }

  private fun transition(next: V9LifecycleState) {
    state = next
    deadline = deadlineFor(next)
    publish()
  }

  private fun publish() {
    val value = snapshot()
    listeners.forEach { it.onStateChanged(value) }
  }

  private fun deadlineFor(value: V9LifecycleState): Long? {
    val timeout = value.timeout() ?: return null
    return try {
      Math.addExact(clock.nowMillis(), timeout.milliseconds)
    } catch (_: ArithmeticException) {
      Long.MAX_VALUE
    }
  }

  private fun requireRoleState(expectedRole: V9Role, expectedState: V9LifecycleState) {
    check(role == expectedRole && state == expectedState) {
      "Illegal v9 lifecycle transition"
    }
  }

  private fun phase(value: V9LifecycleState): V9LifecyclePhase = when (value) {
    V9LifecycleState.SEND_SERVER_HELLO,
    V9LifecycleState.WAIT_SERVER_HELLO,
    V9LifecycleState.SEND_CLIENT_HELLO,
    V9LifecycleState.WAIT_CLIENT_HELLO -> V9LifecyclePhase.NEGOTIATING
    V9LifecycleState.SEND_AUTH,
    V9LifecycleState.WAIT_AUTH -> V9LifecyclePhase.AWAITING_PAIRING
    V9LifecycleState.SEND_AUTH_RESULT,
    V9LifecycleState.WAIT_AUTH_RESULT,
    V9LifecycleState.SEND_SERVER_MANIFEST,
    V9LifecycleState.WAIT_SERVER_MANIFEST,
    V9LifecycleState.SEND_CLIENT_MANIFEST,
    V9LifecycleState.WAIT_CLIENT_MANIFEST,
    V9LifecycleState.EXCHANGE_CONSENT -> V9LifecyclePhase.PAIRING
    V9LifecycleState.SYNCHRONIZING,
    V9LifecycleState.SEND_READY,
    V9LifecycleState.WAIT_READY,
    V9LifecycleState.BULK_PROGRESS -> V9LifecyclePhase.SYNCHRONIZING
    V9LifecycleState.ACTIVE -> V9LifecyclePhase.ACTIVE
    V9LifecycleState.TERMINAL_CLEANUP -> V9LifecyclePhase.TERMINAL
    V9LifecycleState.CLOSED -> V9LifecyclePhase.CLOSED
  }
}

/**
 * Exact per-direction response ledger. It is independent of the lifecycle so later phases can
 * reuse it without assigning wire meaning to enum ordering.
 */
class V9ResponseLedger(initialIncomingSequence: Long = 0) {
  private var expected = validateSequence(initialIncomingSequence, allowExhausted = true)
  private val outstanding = mutableMapOf<Long, V9MessageType>()
  private var lastRecordedRequestSequence = 0L

  val nextIncomingSequence: Long?
    @Synchronized get() = expected.takeUnless { it == ProtocolV9.EXHAUSTED_SEQUENCE }

  val outstandingRequests: Int
    @Synchronized get() = outstanding.size

  @Synchronized
  fun recordPeerRequest(sequence: Long, type: V9MessageType) {
    require(sequence in 1..ProtocolV9.LAST_SEQUENCE)
    require(type in setOf(V9MessageType.AUTH, V9MessageType.START, V9MessageType.PING))
    require(sequence > lastRecordedRequestSequence)
    require(outstanding.size < V9Limit.QUEUED_FRAMES.value)
    outstanding[sequence] = type
    lastRecordedRequestSequence = sequence
  }

  @Synchronized
  fun accept(
      incomingSequence: Long,
      type: V9MessageType,
      flags: Int,
      correlation: Long,
  ): V9ErrorCode? {
    if (expected == ProtocolV9.EXHAUSTED_SEQUENCE || incomingSequence != expected) {
      return V9ErrorCode.SEQUENCE_ERROR
    }
    val response = flags and V9Flag.RESPONSE.wireMask != 0
    if (!response) {
      if (correlation != 0L) return V9ErrorCode.CORRELATION_ERROR
    } else {
      if (correlation == 0L) return V9ErrorCode.CORRELATION_ERROR
      val request = outstanding[correlation] ?: return V9ErrorCode.CORRELATION_ERROR
      val allowed = when (type) {
        V9MessageType.AUTH_RESULT -> request == V9MessageType.AUTH
        V9MessageType.READY -> request == V9MessageType.START
        V9MessageType.PONG -> request == V9MessageType.PING
        V9MessageType.ERROR -> true
        else -> false
      }
      if (!allowed) return V9ErrorCode.CORRELATION_ERROR
      outstanding.remove(correlation)
    }
    expected =
        if (expected == ProtocolV9.LAST_SEQUENCE) ProtocolV9.EXHAUSTED_SEQUENCE
        else Math.addExact(expected, 1)
    return null
  }

  private fun validateSequence(value: Long, allowExhausted: Boolean): Long {
    val maximum =
        if (allowExhausted) ProtocolV9.EXHAUSTED_SEQUENCE else ProtocolV9.LAST_SEQUENCE
    require(value in 0..maximum)
    return value
  }
}

enum class V9PrefaceKind {
  V9,
  LEGACY_V8_OR_EARLIER,
  UNSUPPORTED,
  NEED_MORE,
  TRUNCATED,
}

object V9Preface {
  fun detect(bytes: ByteArray, eof: Boolean = false): V9PrefaceKind {
    val count = minOf(bytes.size, 4)
    if (count < 4) return if (eof) V9PrefaceKind.TRUNCATED else V9PrefaceKind.NEED_MORE
    val prefix = bytes.copyOfRange(0, 4)
    if (prefix.contentEquals(ProtocolV9.MAGIC)) return V9PrefaceKind.V9
    if (prefix.contentEquals(byteArrayOf(0x43, 0x6f, 0x66, 0x66))) {
      return V9PrefaceKind.LEGACY_V8_OR_EARLIER
    }
    return V9PrefaceKind.UNSUPPORTED
  }
}
