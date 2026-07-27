package eu.rekawek.coffeegb.controller.network.v9

import eu.rekawek.coffeegb.controller.network.BoundedSnapshotPublisher
import eu.rekawek.coffeegb.controller.network.NetplayRollbackMetrics
import eu.rekawek.coffeegb.controller.network.NetplayNumericAddress
import eu.rekawek.coffeegb.controller.network.NetplaySnapshotListener
import eu.rekawek.coffeegb.controller.network.NetplaySnapshotSource
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/** Explicit opt-in for #350 diagnostics. Older v9 callers do not negotiate or emit PING. */
data class V9DiagnosticsOptions(
    val enabled: Boolean = false,
    val pingCadenceMillis: Long = DEFAULT_PING_CADENCE_MILLIS,
    val pingTimeoutMillis: Long = DEFAULT_PING_TIMEOUT_MILLIS,
) {
  init {
    require(pingCadenceMillis in 1_000..30_000)
    require(pingTimeoutMillis in pingCadenceMillis..30_000)
  }

  companion object {
    val DISABLED = V9DiagnosticsOptions()
    val ENABLED = V9DiagnosticsOptions(enabled = true)
    const val DEFAULT_PING_CADENCE_MILLIS = 10_000L
    const val DEFAULT_PING_TIMEOUT_MILLIS = 20_000L
  }
}

data class V9PingPayload(val nonce: Long, val peerDiagnosticMicros: Long)

/** Exact frozen 16-byte PING/PONG payload. Both fields are unsigned bit patterns on the wire. */
object V9PingCodec {
  const val PAYLOAD_BYTES = 16

  fun encode(value: V9PingPayload): ByteArray =
      ByteBuffer.allocate(PAYLOAD_BYTES).order(ByteOrder.BIG_ENDIAN)
          .putLong(value.nonce)
          .putLong(value.peerDiagnosticMicros)
          .array()

  fun decode(bytes: ByteArray): V9PingPayload {
    if (bytes.size != PAYLOAD_BYTES) {
      throw V9ProtocolException(V9ErrorCode.LIMIT_EXCEEDED, 0)
    }
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    return V9PingPayload(buffer.long, buffer.long)
  }
}

/** Address is retained locally for the explicit export opt-in; it is never included by default. */
class V9DiagnosticEndpoint(val numericAddress: String, val port: Int) {
  init {
    require(NetplayNumericAddress.isValid(numericAddress))
    require(port in 1..65_535)
  }

  override fun equals(other: Any?): Boolean =
      other is V9DiagnosticEndpoint &&
          numericAddress == other.numericAddress && port == other.port

  override fun hashCode(): Int = 31 * numericAddress.hashCode() + port

  override fun toString(): String = "V9DiagnosticEndpoint(redacted)"
}

data class V9TransportMetricsSnapshot(
    val currentRttMicros: Long?,
    val ewmaRttMicros: Long?,
    val recentMinimumRttMicros: Long?,
    val recentMaximumRttMicros: Long?,
    val jitterMicros: Long?,
    val unansweredPings: Int,
    val timedOutPings: Long,
    val bytesSent: Long,
    val bytesReceived: Long,
    val connectionDurationMillis: Long,
    val localFrame: Long?,
    val remoteFrame: Long?,
    val newestRemoteInputAgeMillis: Long?,
    val lifecycle: V9LifecycleState,
    val mode: V9LinkMode,
    val role: V9Role,
    val authenticatedSlot: Int?,
    val remoteEndpoint: V9DiagnosticEndpoint?,
)

/**
 * Bounded local metrics. It retains one pending ping and the newest 32 RTT samples. Peer stamps
 * are echoed for diagnostics only; all RTT/liveness math uses [clock].
 */
internal class V9TransportMetrics(
    private val clock: V9MonotonicClock,
    private val role: V9Role,
    private val mode: V9LinkMode,
    private val endpoint: V9DiagnosticEndpoint?,
) : NetplaySnapshotSource<V9TransportMetricsSnapshot>, Closeable {
  private val lock = Any()
  private val startedAt = clock.nowMillis()
  private val pending = LinkedHashMap<Long, PendingPing>()
  private val rttSamples = LongArray(RTT_SAMPLES)
  private var rttIndex = 0
  private var rttCount = 0
  private var currentRtt: Long? = null
  private var ewmaRtt: Long? = null
  private var jitter: Long? = null
  private var timedOut = 0L
  private var bytesSent = 0L
  private var bytesReceived = 0L
  private var localFrame: Long? = null
  private var remoteFrame: Long? = null
  private var lastRemoteInputAt: Long? = null
  private var lifecycle =
      if (role == V9Role.SERVER) V9LifecycleState.SEND_SERVER_HELLO
      else V9LifecycleState.WAIT_SERVER_HELLO
  private var slot: Int? = null
  private var closed = false
  private val publisher =
      BoundedSnapshotPublisher(snapshotLocked(startedAt), "netplay-v9-transport-diagnostics")

  override fun snapshot(): V9TransportMetricsSnapshot = synchronized(lock) {
    snapshotLocked(clock.nowMillis())
  }

  override fun addListener(
      listener: NetplaySnapshotListener<V9TransportMetricsSnapshot>,
  ): Closeable = publisher.addListener(listener)

  fun recordLifecycle(value: V9LifecycleSnapshot, authenticatedSlot: Int?) {
    publish {
      lifecycle = value.state
      slot = authenticatedSlot
    }
  }

  fun recordRead(count: Int) {
    require(count >= 0)
    publish { bytesReceived = saturatingAdd(bytesReceived, count.toLong()) }
  }

  fun recordWrite(count: Int) {
    require(count >= 0)
    publish { bytesSent = saturatingAdd(bytesSent, count.toLong()) }
  }

  fun recordLocalFrame(frame: Long) {
    if (frame < 0) return
    publish { localFrame = maxOf(localFrame ?: frame, frame) }
  }

  fun recordRemoteInput(frame: Long) {
    if (frame < 0) return
    publish {
      remoteFrame = maxOf(remoteFrame ?: frame, frame)
      lastRemoteInputAt = clock.nowMillis()
    }
  }

  fun recordRemoteFrame(frame: Long) {
    if (frame < 0) return
    publish { remoteFrame = maxOf(remoteFrame ?: frame, frame) }
  }

  fun registerPing(sequence: Long, value: V9PingPayload): Boolean = synchronized(lock) {
    if (closed || pending.size >= MAX_PENDING_PINGS || pending.containsKey(sequence)) return false
    pending[sequence] = PendingPing(value, clock.nowMillis())
    publisher.update(snapshotLocked(clock.nowMillis()))
    true
  }

  fun cancelPing(sequence: Long) {
    publish { pending.remove(sequence) }
  }

  fun acceptPong(correlation: Long, value: V9PingPayload): V9ErrorCode? {
    val now = clock.nowMillis()
    val outcome = synchronized(lock) {
      if (closed) return V9ErrorCode.CORRELATION_ERROR
      val original = pending[correlation] ?: return V9ErrorCode.CORRELATION_ERROR
      if (original.payload != value) return V9ErrorCode.CORRELATION_ERROR
      pending.remove(correlation)
      val elapsedMillis = elapsedMillis(now, original.sentAtMillis)
      val sample = saturatingMultiply(elapsedMillis, 1_000)
      val previous = currentRtt
      currentRtt = sample
      ewmaRtt = ewmaRtt?.let { it + (sample - it) / 8 } ?: sample
      if (previous != null) {
        val difference = unsignedAbsDifference(sample, previous)
        jitter = jitter?.let { it + (difference - it) / 16 } ?: difference
      } else {
        jitter = 0
      }
      rttSamples[rttIndex] = sample
      rttIndex = (rttIndex + 1) % rttSamples.size
      if (rttCount < rttSamples.size) rttCount++
      snapshotLocked(now)
    }
    publisher.update(outcome)
    return null
  }

  fun expirePings(timeoutMillis: Long): List<Long> {
    require(timeoutMillis > 0)
    val now = clock.nowMillis()
    val expired = ArrayList<Long>(MAX_PENDING_PINGS)
    val snapshot = synchronized(lock) {
      if (closed) return emptyList()
      val iterator = pending.entries.iterator()
      while (iterator.hasNext()) {
        val entry = iterator.next()
        if (elapsedMillis(now, entry.value.sentAtMillis) >= timeoutMillis) {
          expired += entry.key
          iterator.remove()
        }
      }
      timedOut = saturatingAdd(timedOut, expired.size.toLong())
      snapshotLocked(now)
    }
    if (expired.isNotEmpty()) publisher.update(snapshot)
    return expired.toList()
  }

  internal fun pendingCount(): Int = synchronized(lock) { pending.size }
  internal fun nextExpiryDeadline(timeoutMillis: Long): Long? = synchronized(lock) {
    require(timeoutMillis > 0)
    pending.values.minOfOrNull { addSaturated(it.sentAtMillis, timeoutMillis) }
  }
  internal fun retainedRttSampleCount(): Int = synchronized(lock) { rttCount }
  internal fun listenerCountForTest(): Int = publisher.activeListenerCount()
  internal fun seedCountersForTest(sent: Long, received: Long, timeoutCount: Long) {
    require(sent >= 0 && received >= 0 && timeoutCount >= 0)
    publish {
      bytesSent = sent
      bytesReceived = received
      timedOut = timeoutCount
    }
  }

  override fun close() {
    synchronized(lock) {
      if (closed) return
      closed = true
      pending.clear()
    }
    publisher.close()
  }

  private fun publish(update: () -> Unit) {
    val value = synchronized(lock) {
      if (closed) return
      update()
      snapshotLocked(clock.nowMillis())
    }
    publisher.update(value)
  }

  private fun snapshotLocked(now: Long): V9TransportMetricsSnapshot {
    var minimum: Long? = null
    var maximum: Long? = null
    for (index in 0 until rttCount) {
      val value = rttSamples[index]
      minimum = minimum?.let { minOf(it, value) } ?: value
      maximum = maximum?.let { maxOf(it, value) } ?: value
    }
    return V9TransportMetricsSnapshot(
        currentRtt,
        ewmaRtt,
        minimum,
        maximum,
        jitter,
        pending.size,
        timedOut,
        bytesSent,
        bytesReceived,
        elapsedMillis(now, startedAt),
        localFrame,
        remoteFrame,
        lastRemoteInputAt?.let { elapsedMillis(now, it) },
        lifecycle,
        mode,
        role,
        slot,
        endpoint,
    )
  }

  private data class PendingPing(val payload: V9PingPayload, val sentAtMillis: Long)

  companion object {
    const val MAX_PENDING_PINGS = 1
    const val RTT_SAMPLES = 32

    private fun saturatingAdd(left: Long, right: Long): Long =
        NetplayRollbackMetrics.saturatingAdd(left, right)

    private fun saturatingMultiply(value: Long, multiplier: Long): Long =
        if (value > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else value * multiplier

    private fun addSaturated(left: Long, right: Long): Long =
        try {
          Math.addExact(left, right)
        } catch (_: ArithmeticException) {
          Long.MAX_VALUE
        }

    private fun unsignedAbsDifference(left: Long, right: Long): Long =
        if (left >= right) left - right else right - left

    private fun elapsedMillis(now: Long, since: Long): Long =
        try {
          Math.subtractExact(now, since).coerceAtLeast(0)
        } catch (_: ArithmeticException) {
          if (now >= since) Long.MAX_VALUE else 0
        }
  }
}
