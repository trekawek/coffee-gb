package eu.rekawek.coffeegb.controller.network.discovery

import eu.rekawek.coffeegb.controller.network.NetplaySnapshotListener
import eu.rekawek.coffeegb.controller.network.NetplaySnapshotSource
import eu.rekawek.coffeegb.controller.network.v9.V9DeadlineScheduler
import eu.rekawek.coffeegb.controller.network.v9.V9FoundationServerStatus
import eu.rekawek.coffeegb.controller.network.v9.V9FoundationServer
import eu.rekawek.coffeegb.controller.network.v9.V9InvitationHost
import eu.rekawek.coffeegb.controller.network.v9.V9LinkMode
import eu.rekawek.coffeegb.controller.network.v9.V9MonotonicClock
import java.io.Closeable
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals
import org.junit.Test
import org.junit.Assume.assumeTrue

class NetplayDiscoveryTest {

  @Test
  fun productionFoundationStatusTracksListenerAndPairableCapacity() {
    val host = V9InvitationHost(V9LinkMode.NORMAL)
    val server = V9FoundationServer(invitationHost = host) { it.close() }
    try {
      assertFalse(server.statusSource().snapshot().listening)
      server.start()
      val listening = server.statusSource().snapshot()
      assertTrue(listening.listening)
      assertEquals(server.localPort, listening.port)
      assertEquals(V9LinkMode.NORMAL, listening.mode)
      assertEquals(1, listening.openSlots)
    } finally {
      server.close()
      host.close()
    }
    val stopped = server.statusSource().snapshot()
    assertFalse(stopped.listening)
    assertEquals(0, stopped.openSlots)
    assertEquals(0, stopped.port)
  }

  @Test
  fun codecAndHostGatingExposeOnlyFrozenPublicFields() {
    val clock = MutableClock()
    val scheduler = ManualScheduler(clock)
    val backend = FakeBackend()
    val localId = id(1)
    val discovery = TrustedLanNetplayDiscovery(backend, clock, scheduler, localId)
    discovery.enable()
    settle(discovery)
    assertTrue(backend.advertisements.isEmpty())

    discovery.updateHost(V9FoundationServerStatus(true, 8765, V9LinkMode.NORMAL, 0))
    assertTrue(backend.advertisements.isEmpty())
    discovery.updateHost(V9FoundationServerStatus(true, 8765, V9LinkMode.NORMAL, 1))
    val packet = backend.advertisements.single()
    assertEquals(NetplayDiscoveryCodec.BYTES, packet.size)
    assertContentEquals(
        byteArrayOf(
            0x43, 0x47, 0x42, 0x44, 0x01, 0x09, 0x00, 0x01,
            0x01, 0x00, 0x22, 0x3d,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1,
        ),
        packet,
    )
    val decoded = requireNotNull(NetplayDiscoveryCodec.decode(packet))
    assertEquals(9, decoded.protocolMajor)
    assertEquals(localId, decoded.sessionId)
    assertEquals(V9LinkMode.NORMAL, decoded.mode)
    assertEquals(1, decoded.openSlots)
    assertTrue(decoded.pairingRequired)
    assertEquals(8765, decoded.port)
    val firstRefreshCount = backend.advertisements.size
    clock.now = TrustedLanNetplayDiscovery.REFRESH_MILLIS - 1
    scheduler.runDue()
    assertEquals(firstRefreshCount, backend.advertisements.size)
    clock.now++
    scheduler.runDue()
    assertEquals(firstRefreshCount + 1, backend.advertisements.size)
    discovery.updateHost(V9FoundationServerStatus(true, 8765, V9LinkMode.NORMAL, 0))
    assertTrue(backend.withdrawCount > 0)

    assertEquals(null, NetplayDiscoveryCodec.decode(packet.copyOf(27)))
    assertEquals(null, NetplayDiscoveryCodec.decode(packet + 0))
    val badReserved = packet.copyOf().also { it[9] = 1 }
    assertEquals(null, NetplayDiscoveryCodec.decode(badReserved))
    discovery.disable()
    settle(discovery)
    assertEquals(1, backend.stopCount)
    assertFalse(discovery.snapshot().enabled)
    discovery.enable()
    settle(discovery)
    assertEquals(2, backend.startCount)
    discovery.disable()
    settle(discovery)
    discovery.close()
    settle(discovery)
  }

  @Test
  fun ipv4Ipv6DedupCacheEvictionRefreshAndExactExpiryAreBounded() {
    val clock = MutableClock()
    val scheduler = ManualScheduler(clock)
    val backend = FakeBackend()
    val discovery = TrustedLanNetplayDiscovery(backend, clock, scheduler, id(0))
    discovery.enable()
    settle(discovery)
    val advertisement =
        NetplayDiscoveryAdvertisement(9, id(2), V9LinkMode.FOUR_PLAYER, 3, true, 9000)
    val bytes = NetplayDiscoveryCodec.encode(advertisement)
    backend.emit(bytes, "192.0.2.10")
    backend.emit(bytes, "192.0.2.10")
    backend.emit(bytes, "2001:db8::10")
    var snapshot = discovery.snapshot()
    assertEquals(1, snapshot.hosts.size)
    assertEquals(2, snapshot.hosts.single().numericAddresses.size)
    @Suppress("UNCHECKED_CAST")
    assertFailsWith<UnsupportedOperationException> {
      (snapshot.hosts as MutableList<NetplayDiscoveredHost>).clear()
    }
    @Suppress("UNCHECKED_CAST")
    assertFailsWith<UnsupportedOperationException> {
      (snapshot.hosts.single().numericAddresses as MutableList<String>).add("192.0.2.99")
    }
    assertContentEquals(
        listOf("192.0.2.10", InetAddress.getByName("2001:db8::10").hostAddress).sorted(),
        snapshot.hosts.single().numericAddresses,
    )
    val addressBound =
        NetplayDiscoveryAdvertisement(9, id(68), V9LinkMode.NORMAL, 1, true, 9002)
    repeat(TrustedLanNetplayDiscovery.MAX_ADDRESSES_PER_SERVICE + 1) { index ->
      backend.emit(NetplayDiscoveryCodec.encode(addressBound), "203.0.113.${index + 1}")
    }
    assertEquals(
        TrustedLanNetplayDiscovery.MAX_ADDRESSES_PER_SERVICE,
        discovery.snapshot().hosts.single {
          it.advertisement.sessionId == addressBound.sessionId
        }.numericAddresses.size,
    )

    for (index in 3..67) {
      clock.now++
      val value =
          NetplayDiscoveryAdvertisement(9, id(index), V9LinkMode.NORMAL, 1, true, 9001)
      backend.emit(NetplayDiscoveryCodec.encode(value), "198.51.100.${index % 200 + 1}")
    }
    assertEquals(TrustedLanNetplayDiscovery.MAX_SERVICES, discovery.cacheSize())
    assertEquals(TrustedLanNetplayDiscovery.MAX_SERVICES, discovery.snapshot().hosts.size)

    val mostRecent = discovery.snapshot().hosts.last()
    val beforeExpiry = mostRecent.lastSeenMillis
    clock.now = beforeExpiry + TrustedLanNetplayDiscovery.CACHE_EXPIRY_MILLIS - 1
    discovery.expireNow()
    assertTrue(discovery.snapshot().hosts.any {
      it.advertisement.sessionId == mostRecent.advertisement.sessionId
    })
    clock.now++
    discovery.expireNow()
    assertFalse(discovery.snapshot().hosts.any {
      it.advertisement.sessionId == mostRecent.advertisement.sessionId
    })
    discovery.close()
    settle(discovery)
    assertFalse(discovery.snapshot().enabled)
    assertTrue(discovery.snapshot().hosts.isEmpty())
  }

  @Test
  fun hostileMetadataFailureIsolationConfirmationAndCleanupStayExplicit() {
    val clock = MutableClock()
    val scheduler = ManualScheduler(clock)
    val failing = FakeBackend(failStart = true)
    val isolated = TrustedLanNetplayDiscovery(failing, clock, scheduler, id(0))
    isolated.enable()
    settle(isolated)
    assertFalse(isolated.snapshot().backendHealthy)
    // Discovery failure never changes or throws from the direct-listener status update.
    isolated.updateHost(V9FoundationServerStatus(true, 7777, V9LinkMode.NORMAL, 1))
    isolated.close()
    settle(isolated)

    val advertiseFailure = FakeBackend(failAdvertise = true)
    val advertiseIsolated =
        TrustedLanNetplayDiscovery(advertiseFailure, clock, scheduler, id(8))
    advertiseIsolated.enable()
    settle(advertiseIsolated)
    advertiseIsolated.updateHost(V9FoundationServerStatus(true, 7777, V9LinkMode.NORMAL, 1))
    assertFalse(advertiseIsolated.snapshot().backendHealthy)
    advertiseIsolated.close()
    settle(advertiseIsolated)

    val schedulingBackend = FakeBackend()
    val schedulingIsolated =
        TrustedLanNetplayDiscovery(
            schedulingBackend,
            clock,
            FailingScheduler,
            id(10),
        )
    schedulingIsolated.enable()
    settle(schedulingIsolated)
    assertFalse(schedulingIsolated.snapshot().backendHealthy)
    schedulingIsolated.close()
    settle(schedulingIsolated)

    val backend = FakeBackend()
    val discovery = TrustedLanNetplayDiscovery(backend, clock, scheduler, id(0))
    discovery.enable()
    settle(discovery)
    val bytes =
        NetplayDiscoveryCodec.encode(
            NetplayDiscoveryAdvertisement(9, id(7), V9LinkMode.NORMAL, 1, true, 7777),
        )
    backend.emit(bytes, "203.0.113.9")
    val host = discovery.snapshot().hosts.single()
    assertFailsWith<IllegalArgumentException> { discovery.confirm(host, 0, false) }
    val selected = discovery.confirm(host, 0, true)
    assertTrue(selected.requiresAuthenticatedInvitation)
    assertEquals("203.0.113.9", selected.endpoint.address.hostAddress)
    assertEquals(7777, selected.endpoint.port)
    assertFalse(selected.toString().contains("203.0.113.9"))
    assertFailsWith<IllegalArgumentException> {
      NetplayDiscoveredHost(host.advertisement, listOf("dead.beef"), host.lastSeenMillis)
    }
    val forged =
        NetplayDiscoveredHost(host.advertisement, listOf("203.0.113.10"), host.lastSeenMillis)
    assertFailsWith<IllegalArgumentException> { discovery.confirm(forged, 0, true) }

    val source = MutableStatusSource(V9FoundationServerStatus(false, 0, V9LinkMode.NORMAL, 0))
    val binding = NetplayDiscoveryHostBinding(source, discovery)
    source.publish(V9FoundationServerStatus(true, 7777, V9LinkMode.NORMAL, 1))
    assertTrue(backend.advertisements.isNotEmpty())
    binding.close()
    val count = backend.advertisements.size
    source.publish(V9FoundationServerStatus(true, 8888, V9LinkMode.NORMAL, 1))
    clock.now += TrustedLanNetplayDiscovery.REFRESH_MILLIS
    scheduler.runDue()
    assertEquals(count, backend.advertisements.size)
    discovery.close()
    settle(discovery)
    assertEquals(1, backend.closeCount)
  }

  @Test
  fun disableAndCloseFenceAStartThatCompletesAfterTheLifecycleChanges() {
    val clock = MutableClock()
    val disabledBackend = FakeBackend(blockStart = true)
    val disabled =
        TrustedLanNetplayDiscovery(disabledBackend, clock, ManualScheduler(clock), id(40))
    disabled.enable()
    assertTrue(disabledBackend.startEntered.await(1, TimeUnit.SECONDS))
    disabled.updateHost(V9FoundationServerStatus(true, 8765, V9LinkMode.NORMAL, 1))
    disabled.disable()
    assertFalse(disabled.snapshot().enabled)
    assertTrue(disabled.snapshot().hosts.isEmpty())
    assertTrue(disabledBackend.advertisements.isEmpty())
    disabledBackend.releaseStart.countDown()
    settle(disabled)
    assertFalse(disabled.backendRunningForTest())
    assertFalse(disabledBackend.running)
    assertEquals(1, disabledBackend.stopCount)
    assertTrue(disabledBackend.advertisements.isEmpty())
    disabled.close()
    settle(disabled)
    assertTrue(disabled.awaitLifecycleWorkerStoppedForTest(1, TimeUnit.SECONDS))
    assertFalse(disabled.lifecycleWorkerAliveForTest())

    val closedBackend = FakeBackend(blockStart = true)
    val closed = TrustedLanNetplayDiscovery(closedBackend, clock, ManualScheduler(clock), id(41))
    closed.enable()
    assertTrue(closedBackend.startEntered.await(1, TimeUnit.SECONDS))
    closed.updateHost(V9FoundationServerStatus(true, 8765, V9LinkMode.NORMAL, 1))
    closed.close()
    closedBackend.releaseStart.countDown()
    settle(closed)
    assertFalse(closed.backendRunningForTest())
    assertFalse(closedBackend.running)
    assertEquals(1, closedBackend.closeCount)
    assertTrue(closedBackend.advertisements.isEmpty())
    assertTrue(closed.awaitLifecycleWorkerStoppedForTest(1, TimeUnit.SECONDS))
    assertFalse(closed.lifecycleWorkerAliveForTest())
  }

  @Test
  fun malformedMetadataFailsClosedAtEveryDecisiveField() {
    val valid =
        NetplayDiscoveryCodec.encode(
            NetplayDiscoveryAdvertisement(9, id(9), V9LinkMode.NORMAL, 1, true, 8765),
        )
    val hostile =
        listOf(
            valid.copyOf().also { it[0] = 0 },
            valid.copyOf().also { it[4] = 2 },
            valid.copyOf().also { it[5] = 8 },
            valid.copyOf().also { it[6] = 2 },
            valid.copyOf().also { it[7] = 0 },
            valid.copyOf().also { it[7] = 4 },
            valid.copyOf().also { it[8] = 0 },
            valid.copyOf().also { it[9] = 1 },
            valid.copyOf().also { it[10] = 0; it[11] = 0 },
            valid.copyOf(TrustedLanNetplayDiscovery.MAX_PACKET_BYTES),
        )
    hostile.forEach { assertEquals(null, NetplayDiscoveryCodec.decode(it)) }
    assertEquals(null, NetplayDiscoveryCodec.decode(ByteArray(1_000)))
  }

  @Test
  fun realBackendLoopbackSmokeIsBoundedWhereMulticastIsAvailable() {
    val receiver = V9MulticastDiscoveryBackend()
    val sender = V9MulticastDiscoveryBackend()
    val observed = CountDownLatch(1)
    val expected =
        NetplayDiscoveryCodec.encode(
            NetplayDiscoveryAdvertisement(9, id(31), V9LinkMode.NORMAL, 1, true, 8765),
        )
    try {
      try {
        receiver.start { if (it.bytes.contentEquals(expected)) observed.countDown() }
        sender.start {}
        sender.advertise(expected)
      } catch (_: Exception) {
        assumeTrue("multicast is unavailable on this host", false)
      }
      assumeTrue("multicast loopback is unavailable on this host", observed.await(2, TimeUnit.SECONDS))
      sender.advertise(expected)
      receiver.stop()
      sender.stop()
      assertTrue(receiver.awaitWorkersStopped(2, TimeUnit.SECONDS))
      assertTrue(sender.awaitWorkersStopped(2, TimeUnit.SECONDS))
      assertEquals(0, receiver.liveWorkerCount())
      assertEquals(0, sender.liveWorkerCount())
      assertEquals(0, receiver.queuedAdvertisementCount())
      assertEquals(0, sender.queuedAdvertisementCount())

      receiver.start {}
      sender.start {}
      receiver.stop()
      sender.stop()
      assertTrue(receiver.awaitWorkersStopped(2, TimeUnit.SECONDS))
      assertTrue(sender.awaitWorkersStopped(2, TimeUnit.SECONDS))
      assertEquals(0, receiver.liveWorkerCount())
      assertEquals(0, sender.liveWorkerCount())
    } finally {
      receiver.close()
      sender.close()
      expected.fill(0)
    }
  }

  private fun id(number: Int): NetplayPublicSessionId =
      NetplayPublicSessionId(ByteArray(16).also { it[12] = (number ushr 24).toByte();
        it[13] = (number ushr 16).toByte(); it[14] = (number ushr 8).toByte();
        it[15] = number.toByte() })

  private fun settle(discovery: TrustedLanNetplayDiscovery) {
    assertTrue(discovery.awaitBackendLifecycle(2, TimeUnit.SECONDS))
  }

  private class MutableClock(var now: Long = 0) : V9MonotonicClock {
    override fun nowMillis(): Long = now
  }

  private class ManualScheduler(private val clock: MutableClock) : V9DeadlineScheduler {
    private val tasks = mutableListOf<Task>()
    override fun schedule(deadlineMillis: Long, action: Runnable): Closeable {
      val task = Task(deadlineMillis, action)
      tasks += task
      return Closeable { task.cancelled = true }
    }
    fun runDue() {
      val due = tasks.filter { !it.cancelled && it.deadline <= clock.now }.toList()
      tasks.removeAll(due)
      due.forEach { it.action.run() }
    }
    private data class Task(val deadline: Long, val action: Runnable, var cancelled: Boolean = false)
  }

  private object FailingScheduler : V9DeadlineScheduler {
    override fun schedule(deadlineMillis: Long, action: Runnable): Closeable =
        throw IllegalStateException("synthetic scheduler failure")
  }

  private class FakeBackend(
      private val failStart: Boolean = false,
      private val failAdvertise: Boolean = false,
      private val blockStart: Boolean = false,
  ) : NetplayDiscoveryBackend {
    @Volatile var receiver: ((NetplayDiscoveryDatagram) -> Unit)? = null
    val advertisements = Collections.synchronizedList(mutableListOf<ByteArray>())
    val startEntered = CountDownLatch(1)
    val releaseStart = CountDownLatch(if (blockStart) 1 else 0)
    @Volatile var running = false
    var stopCount = 0
    var closeCount = 0
    var startCount = 0
    var withdrawCount = 0
    override fun start(receiver: (NetplayDiscoveryDatagram) -> Unit) {
      startCount++
      startEntered.countDown()
      releaseStart.await()
      if (failStart) throw IllegalStateException("synthetic backend failure")
      this.receiver = receiver
      running = true
    }
    override fun advertise(bytes: ByteArray) {
      if (failAdvertise) throw IllegalStateException("synthetic advertise failure")
      advertisements += bytes.copyOf()
    }
    override fun withdraw() { withdrawCount++ }
    override fun stop() { stopCount++; running = false; receiver = null }
    override fun close() { closeCount++; running = false; receiver = null }
    fun emit(bytes: ByteArray, address: String) {
      receiver?.invoke(NetplayDiscoveryDatagram(bytes.copyOf(), InetAddress.getByName(address)))
    }
  }

  private class MutableStatusSource(initial: V9FoundationServerStatus) :
      NetplaySnapshotSource<V9FoundationServerStatus> {
    private var value = initial
    private val listeners = mutableListOf<NetplaySnapshotListener<V9FoundationServerStatus>>()
    override fun snapshot(): V9FoundationServerStatus = value
    override fun addListener(listener: NetplaySnapshotListener<V9FoundationServerStatus>): Closeable {
      listeners += listener
      listener.onSnapshot(value)
      return Closeable { listeners.remove(listener) }
    }
    fun publish(next: V9FoundationServerStatus) {
      value = next
      listeners.toList().forEach { it.onSnapshot(next) }
    }
  }
}
