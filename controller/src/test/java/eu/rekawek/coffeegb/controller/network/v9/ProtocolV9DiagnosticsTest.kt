package eu.rekawek.coffeegb.controller.network.v9

import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ProtocolV9DiagnosticsTest {

  @Test
  fun diagnosticsOptionIsTheOnlyWayFoundationAdvertisesPing() {
    val accepted = ArrayBlockingQueue<V9FoundationConnection>(1)
    val server =
        V9FoundationServer(optionalCapabilities = setOf(V9Capability.PING_V1)) {
          accepted.offer(it)
        }
    var client: V9FoundationConnection? = null
    try {
      server.start()
      client =
          V9FoundationClient.connect(
              InetSocketAddress("127.0.0.1", server.localPort),
              optionalCapabilities = setOf(V9Capability.PING_V1),
          )
      client.awaitPairingBoundary(3, TimeUnit.SECONDS)
      val serverConnection = assertNotNull(accepted.poll(3, TimeUnit.SECONDS))
      assertFalse(V9Capability.PING_V1 in client.negotiatedCapabilities())
      assertFalse(V9Capability.PING_V1 in serverConnection.negotiatedCapabilities())
      assertNull(client.transportMetricsSource())
      assertNull(serverConnection.transportMetricsSource())
    } finally {
      client?.close()
      server.close()
    }
  }

  @Test
  fun pingCodecAndIncrementalFrameHonorFrozenWireContract() {
    val value = V9PingPayload(0x0102030405060708L, Long.MIN_VALUE)
    val payload = V9PingCodec.encode(value)
    assertEquals(16, payload.size)
    assertEquals(value, V9PingCodec.decode(payload))
    for (length in 0 until V9PingCodec.PAYLOAD_BYTES) {
      val failure = runCatching { V9PingCodec.decode(payload.copyOf(length)) }.exceptionOrNull()
      assertTrue(failure is V9ProtocolException)
      assertEquals(V9ErrorCode.LIMIT_EXCEEDED, failure.reason)
    }
    val oversized = runCatching { V9PingCodec.decode(payload + 0) }.exceptionOrNull()
    assertTrue(oversized is V9ProtocolException)
    assertEquals(V9ErrorCode.LIMIT_EXCEEDED, oversized.reason)

    val policy =
        V9DecoderPolicy(
            allowedMessages = setOf(V9MessageType.PING, V9MessageType.PONG),
            negotiatedCapabilities = setOf(V9Capability.PING_V1),
        )
    val frame =
        V9FrameEncoder.encode(
            V9OutboundFrame(V9MessageType.PING, 0, 0, 0, 0, payload),
            policy,
        )
    val decoder = V9IncrementalDecoder(policy = policy)
    for (index in frame.indices) {
      val result = decoder.feedOne(frame, index, 1)
      if (index < frame.lastIndex) {
        assertTrue(result.frames.isEmpty())
        assertNull(result.failure)
      } else {
        assertEquals(V9MessageType.PING, result.frames.single().header.type)
        result.frames.single().use { assertEquals(value, V9PingCodec.decode(it.payloadView())) }
      }
    }

    val malformedPong = frame.copyOf()
    malformedPong[8] = 0
    malformedPong[9] = V9MessageType.PONG.wireId.toByte()
    // PONG without RESPONSE is rejected at the decisive flags field before payload allocation.
    val rejected = V9IncrementalDecoder(policy = policy).feed(malformedPong)
    assertEquals(V9ErrorCode.UNKNOWN_REQUIRED_FLAG, rejected.failure?.reason)
  }

  @Test
  fun rttMathPendingBoundsExpiryAndPeerStampIsolationAreExact() {
    val clock = MutableClock()
    val metrics = V9TransportMetrics(clock, V9Role.CLIENT, V9LinkMode.NORMAL, null)
    val first = V9PingPayload(11, Long.MAX_VALUE)
    assertTrue(metrics.registerPing(1, first))
    clock.now = 10
    assertNull(metrics.acceptPong(1, first))
    assertEquals(10_000, metrics.snapshot().currentRttMicros)
    assertEquals(V9ErrorCode.CORRELATION_ERROR, metrics.acceptPong(1, first))

    val second = V9PingPayload(12, Long.MIN_VALUE)
    assertTrue(metrics.registerPing(2, second))
    clock.now = 30
    assertNull(metrics.acceptPong(2, second))
    val snapshot = metrics.snapshot()
    assertEquals(20_000, snapshot.currentRttMicros)
    assertEquals(11_250, snapshot.ewmaRttMicros)
    assertEquals(10_000, snapshot.recentMinimumRttMicros)
    assertEquals(20_000, snapshot.recentMaximumRttMicros)
    assertEquals(625, snapshot.jitterMicros)
    assertEquals(2, metrics.retainedRttSampleCount())

    assertTrue(metrics.registerPing(3, V9PingPayload(3, 3)))
    assertEquals(50, metrics.nextExpiryDeadline(20))
    assertEquals(V9ErrorCode.CORRELATION_ERROR, metrics.acceptPong(99, V9PingPayload(3, 3)))
    assertEquals(V9ErrorCode.CORRELATION_ERROR, metrics.acceptPong(3, V9PingPayload(4, 3)))
    assertEquals(1, metrics.pendingCount())
    assertFalse(metrics.registerPing(4, V9PingPayload(4, 0)))
    clock.now = 49
    assertTrue(metrics.expirePings(20).isEmpty())
    clock.now = 50
    assertEquals(listOf(3L), metrics.expirePings(20))
    assertEquals(V9ErrorCode.CORRELATION_ERROR,
        metrics.acceptPong(3, V9PingPayload(3, 3)))
    assertEquals(0, metrics.pendingCount())
    assertEquals(1, metrics.snapshot().timedOutPings)
    metrics.close()
    assertFalse(metrics.registerPing(9, V9PingPayload(9, 0)))
    assertEquals(V9ErrorCode.CORRELATION_ERROR,
        metrics.acceptPong(9, V9PingPayload(9, 0)))
  }

  @Test
  fun transportCountersFramesListenersAndOverflowRemainBounded() {
    val clock = MutableClock(100)
    val metrics =
        V9TransportMetrics(
            clock,
            V9Role.SERVER,
            V9LinkMode.FOUR_PLAYER,
            V9DiagnosticEndpoint("2001:db8::1", 8765),
        )
    val callbacks = AtomicInteger()
    val subscription = metrics.addListener { callbacks.incrementAndGet() }
    assertEquals(1, metrics.listenerCountForTest())
    metrics.recordRead(17)
    metrics.recordWrite(19)
    metrics.recordLocalFrame(40)
    metrics.recordLocalFrame(39)
    metrics.recordRemoteInput(37)
    clock.now = 106
    metrics.recordRemoteFrame(38)
    var value = metrics.snapshot()
    assertEquals(17, value.bytesReceived)
    assertEquals(19, value.bytesSent)
    assertEquals(40, value.localFrame)
    assertEquals(38, value.remoteFrame)
    assertEquals(6, value.newestRemoteInputAgeMillis)
    assertEquals(6, value.connectionDurationMillis)

    metrics.seedCountersForTest(Long.MAX_VALUE - 1, Long.MAX_VALUE - 2, Long.MAX_VALUE - 1)
    metrics.recordWrite(Int.MAX_VALUE)
    metrics.recordRead(Int.MAX_VALUE)
    clock.now = 20_106
    assertTrue(metrics.registerPing(1, V9PingPayload(1, 0)))
    clock.now = 20_107
    metrics.expirePings(1)
    value = metrics.snapshot()
    assertEquals(Long.MAX_VALUE, value.bytesSent)
    assertEquals(Long.MAX_VALUE, value.bytesReceived)
    assertEquals(Long.MAX_VALUE, value.timedOutPings)
    subscription.close()
    assertEquals(0, metrics.listenerCountForTest())
    metrics.close()
    assertEquals(0, metrics.pendingCount())

    val wrappingClock = MutableClock(Long.MIN_VALUE)
    val wrapping = V9TransportMetrics(
        wrappingClock, V9Role.CLIENT, V9LinkMode.NORMAL, null,
    )
    wrapping.recordRemoteInput(1)
    wrappingClock.now = Long.MAX_VALUE
    assertEquals(Long.MAX_VALUE, wrapping.snapshot().connectionDurationMillis)
    assertEquals(Long.MAX_VALUE, wrapping.snapshot().newestRemoteInputAgeMillis)
    wrapping.close()
  }

  @Test
  fun slowObserverCannotBlockMetricProducer() {
    val metrics = V9TransportMetrics(MutableClock(), V9Role.CLIENT, V9LinkMode.NORMAL, null)
    val observerEntered = CountDownLatch(1)
    val releaseObserver = CountDownLatch(1)
    val subscription = metrics.addListener {
      observerEntered.countDown()
      releaseObserver.await()
    }
    assertTrue(observerEntered.await(1, TimeUnit.SECONDS))
    val producerReturned = CountDownLatch(1)
    val producer = Thread {
      metrics.recordWrite(1)
      producerReturned.countDown()
    }
    producer.start()
    try {
      assertTrue(producerReturned.await(1, TimeUnit.SECONDS))
    } finally {
      releaseObserver.countDown()
      producer.join(1_000)
      subscription.close()
      metrics.close()
    }
    assertFalse(producer.isAlive)
  }

  private class MutableClock(var now: Long = 0) : V9MonotonicClock {
    override fun nowMillis(): Long = now
  }
}
