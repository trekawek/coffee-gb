package eu.rekawek.coffeegb.controller.network.discovery

import eu.rekawek.coffeegb.controller.network.BoundedSnapshotPublisher
import eu.rekawek.coffeegb.controller.network.NetplaySnapshotListener
import eu.rekawek.coffeegb.controller.network.NetplaySnapshotSource
import eu.rekawek.coffeegb.controller.network.NetplayNumericAddress
import eu.rekawek.coffeegb.controller.network.v9.V9DeadlineScheduler
import eu.rekawek.coffeegb.controller.network.v9.V9FoundationServerStatus
import eu.rekawek.coffeegb.controller.network.v9.V9LinkMode
import eu.rekawek.coffeegb.controller.network.v9.V9MonotonicClock
import eu.rekawek.coffeegb.controller.network.v9.V9SystemDeadlineScheduler
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Public, random identifier for one discovery advertisement. It is not an invitation secret. */
class NetplayPublicSessionId(bytes: ByteArray) {
  private val value: ByteArray

  init {
    require(bytes.size == BYTES)
    value = bytes.copyOf()
  }

  fun bytes(): ByteArray = value.copyOf()

  override fun equals(other: Any?): Boolean =
      other is NetplayPublicSessionId && value.contentEquals(other.value)

  override fun hashCode(): Int = value.contentHashCode()

  override fun toString(): String = "NetplayPublicSessionId(redacted-public-id)"

  companion object { const val BYTES = 16 }
}

data class NetplayDiscoveryAdvertisement(
    val protocolMajor: Int,
    val sessionId: NetplayPublicSessionId,
    val mode: V9LinkMode,
    val openSlots: Int,
    val pairingRequired: Boolean,
    val port: Int,
) {
  init {
    require(protocolMajor == PROTOCOL_MAJOR)
    require(openSlots in 1..if (mode == V9LinkMode.NORMAL) 1 else 3)
    require(pairingRequired)
    require(port in 1..65_535)
  }

  companion object { const val PROTOCOL_MAJOR = 9 }
}

class NetplayDiscoveredHost(
    val advertisement: NetplayDiscoveryAdvertisement,
    numericAddresses: Collection<String>,
    val lastSeenMillis: Long,
) {
  init {
    require(numericAddresses.size in 1..TrustedLanNetplayDiscovery.MAX_ADDRESSES_PER_SERVICE)
    require(numericAddresses.all(NetplayNumericAddress::isValid))
  }

  val numericAddresses: List<String> =
      Collections.unmodifiableList(numericAddresses.distinct().sorted())
}

class NetplayDiscoverySnapshot internal constructor(
    val enabled: Boolean,
    val backendHealthy: Boolean,
    hosts: Collection<NetplayDiscoveredHost>,
) {
  init { require(hosts.size <= TrustedLanNetplayDiscovery.MAX_SERVICES) }

  val hosts: List<NetplayDiscoveredHost> = Collections.unmodifiableList(hosts.toList())
}

/** Selection only prefills an endpoint. There is deliberately no connect/tokenless API here. */
class NetplayDiscoverySelection(
    val endpoint: InetSocketAddress,
    val requiresAuthenticatedInvitation: Boolean = true,
) {
  override fun toString(): String = "NetplayDiscoverySelection(redacted-endpoint)"
}

interface NetplayDiscovery : NetplaySnapshotSource<NetplayDiscoverySnapshot>, Closeable {
  fun enable()
  fun disable()
  fun updateHost(status: V9FoundationServerStatus)
  fun confirm(host: NetplayDiscoveredHost, addressIndex: Int, confirmed: Boolean):
      NetplayDiscoverySelection
}

internal data class NetplayDiscoveryDatagram(val bytes: ByteArray, val source: InetAddress)

/** Bounded backend boundary. Implementations may not invoke receiver with more than 64 bytes. */
internal interface NetplayDiscoveryBackend : Closeable {
  fun start(receiver: (NetplayDiscoveryDatagram) -> Unit)
  fun advertise(bytes: ByteArray)
  fun withdraw()
  fun stop()
}

internal object NetplayDiscoveryCodec {
  const val BYTES = 28
  private val MAGIC = byteArrayOf(0x43, 0x47, 0x42, 0x44) // CGBD; not the CGB9 stream preface.

  fun encode(value: NetplayDiscoveryAdvertisement): ByteArray {
    val mode = if (value.mode == V9LinkMode.NORMAL) 0 else 1
    return ByteBuffer.allocate(BYTES).order(ByteOrder.BIG_ENDIAN)
        .put(MAGIC)
        .put(1)
        .put(value.protocolMajor.toByte())
        .put(mode.toByte())
        .put(value.openSlots.toByte())
        .put(if (value.pairingRequired) 1 else 0)
        .put(0)
        .putShort(value.port.toShort())
        .put(value.sessionId.bytes())
        .array()
  }

  fun decode(bytes: ByteArray): NetplayDiscoveryAdvertisement? {
    if (bytes.size != BYTES || !bytes.copyOfRange(0, 4).contentEquals(MAGIC)) return null
    if (u8(bytes, 4) != 1 || u8(bytes, 5) != 9 || u8(bytes, 9) != 0) return null
    val mode = when (u8(bytes, 6)) {
      0 -> V9LinkMode.NORMAL
      1 -> V9LinkMode.FOUR_PLAYER
      else -> return null
    }
    if (u8(bytes, 8) != 1) return null
    val openSlots = u8(bytes, 7)
    val port = (u8(bytes, 10) shl 8) or u8(bytes, 11)
    return try {
      NetplayDiscoveryAdvertisement(
          9,
          NetplayPublicSessionId(bytes.copyOfRange(12, 28)),
          mode,
          openSlots,
          true,
          port,
      )
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  private fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xff
}

/**
 * Trusted-LAN discovery is off by default and strictly address-only. Cache and listener storage
 * are constant bounded; malformed metadata is ignored without affecting direct hosting.
 */
class TrustedLanNetplayDiscovery internal constructor(
    private val backend: NetplayDiscoveryBackend,
    private val clock: V9MonotonicClock = V9MonotonicClock.SYSTEM,
    scheduler: V9DeadlineScheduler? = null,
    sessionId: NetplayPublicSessionId =
        NetplayPublicSessionId(ByteArray(16).also(SecureRandom()::nextBytes)),
) : NetplayDiscovery {
  constructor() : this(V9MulticastDiscoveryBackend())

  private val lock = Any()
  private val scheduler = scheduler ?: V9SystemDeadlineScheduler(clock)
  private val ownedScheduler = if (scheduler == null) this.scheduler as Closeable else null
  private val publicSessionId = sessionId
  private val cache = LinkedHashMap<NetplayPublicSessionId, CachedHost>()
  private var enabled = false
  private var closed = false
  private var backendRunning = false
  private var backendHealthy = true
  private var lifecycleGeneration = 0L
  private var lifecycleSettled = CountDownLatch(0)
  private var hostStatus: V9FoundationServerStatus? = null
  private var refreshTask: Closeable? = null
  private val lifecycleSignals = ArrayBlockingQueue<Unit>(1)
  private val lifecycleWorkerTerminated = CountDownLatch(1)
  private val lifecycleWorker =
      thread(isDaemon = true, name = "netplay-v9-discovery-lifecycle") {
        try {
          lifecycleLoop()
        } finally {
          lifecycleWorkerTerminated.countDown()
        }
      }
  private val publisher =
      BoundedSnapshotPublisher(
          NetplayDiscoverySnapshot(false, true, emptyList()),
          "netplay-v9-discovery-snapshots",
      )

  override fun snapshot(): NetplayDiscoverySnapshot = synchronized(lock) { snapshotLocked() }

  override fun addListener(listener: NetplaySnapshotListener<NetplayDiscoverySnapshot>): Closeable =
      publisher.addListener(listener)

  override fun enable() {
    synchronized(lock) {
      check(!closed) { "netplay discovery is closed" }
      if (enabled) return
      enabled = true
      advanceLifecycleLocked()
    }
    signalLifecycle()
    publisher.update(synchronized(lock) { snapshotLocked() })
  }

  override fun disable() {
    val value = synchronized(lock) {
      if (!enabled) return
      enabled = false
      cache.clear()
      refreshTask?.close()
      refreshTask = null
      advanceLifecycleLocked()
      snapshotLocked()
    }
    signalLifecycle()
    publisher.update(value)
  }

  override fun updateHost(status: V9FoundationServerStatus) {
    synchronized(lock) { if (!closed) hostStatus = status }
    refresh()
  }

  override fun confirm(
      host: NetplayDiscoveredHost,
      addressIndex: Int,
      confirmed: Boolean,
  ): NetplayDiscoverySelection {
    require(confirmed) { "discovered endpoint requires explicit local confirmation" }
    require(addressIndex in host.numericAddresses.indices)
    val address = synchronized(lock) {
      check(enabled && !closed) { "netplay discovery is not active" }
      val cached = cache[host.advertisement.sessionId]
          ?: throw IllegalArgumentException("discovered service is no longer available")
      require(cached.advertisement == host.advertisement) {
        "discovered service metadata changed before confirmation"
      }
      cached.addresses[host.numericAddresses[addressIndex]]
          ?: throw IllegalArgumentException("discovered address is no longer available")
    }
    return NetplayDiscoverySelection(InetSocketAddress(address, host.advertisement.port))
  }

  internal fun expireNow() = refresh()

  internal fun cacheSize(): Int = synchronized(lock) { cache.size }

  internal fun awaitBackendLifecycle(timeout: Long, unit: TimeUnit): Boolean {
    val settled = synchronized(lock) { lifecycleSettled }
    return settled.await(timeout, unit)
  }

  internal fun backendRunningForTest(): Boolean = synchronized(lock) { backendRunning }

  internal fun lifecycleWorkerAliveForTest(): Boolean = lifecycleWorker.isAlive

  internal fun awaitLifecycleWorkerStoppedForTest(timeout: Long, unit: TimeUnit): Boolean =
      lifecycleWorkerTerminated.await(timeout, unit)

  private fun receive(value: NetplayDiscoveryDatagram) {
    if (value.bytes.size > MAX_PACKET_BYTES) return
    val decoded = NetplayDiscoveryCodec.decode(value.bytes) ?: return
    if (decoded.sessionId == publicSessionId) return
    val numeric = value.source.hostAddress ?: return
    if (!safeNumericAddress(numeric)) return
    val now = clock.nowMillis()
    val snapshot = synchronized(lock) {
      if (!enabled || closed) return
      val current = cache[decoded.sessionId]
      if (current == null && cache.size >= MAX_SERVICES) {
        val oldest = cache.entries.minWithOrNull(
            compareBy<Map.Entry<NetplayPublicSessionId, CachedHost>> { it.value.lastSeen }
                .thenBy { stableId(it.key) },
        )
        if (oldest != null) cache.remove(oldest.key)
      }
      val target = cache.getOrPut(decoded.sessionId) { CachedHost(decoded, now) }
      target.advertisement = decoded
      target.lastSeen = now
      if (target.addresses.size < MAX_ADDRESSES_PER_SERVICE || numeric in target.addresses) {
        target.addresses[numeric] = value.source
      }
      snapshotLocked()
    }
    publisher.update(snapshot)
  }

  private fun refresh() {
    val packet: ByteArray?
    val mayUseBackend: Boolean
    synchronized(lock) {
      if (closed) return
      val now = clock.nowMillis()
      cache.entries.removeIf { elapsedMillis(now, it.value.lastSeen) >= CACHE_EXPIRY_MILLIS }
      val status = hostStatus
      mayUseBackend = backendRunning
      packet =
          if (mayUseBackend && enabled && backendHealthy &&
              status?.listening == true && status.openSlots > 0) {
            NetplayDiscoveryCodec.encode(
                NetplayDiscoveryAdvertisement(
                    9,
                    publicSessionId,
                    status.mode,
                    status.openSlots,
                    true,
                    status.port,
                ),
            )
          } else null
    }
    if (!mayUseBackend) {
      publisher.update(synchronized(lock) { snapshotLocked() })
      return
    }
    if (packet != null) {
      try {
        backend.advertise(packet)
      } catch (_: RuntimeException) {
        synchronized(lock) { backendHealthy = false }
      } catch (_: IOException) {
        synchronized(lock) { backendHealthy = false }
      } finally {
        packet.fill(0)
      }
    } else {
      try {
        backend.withdraw()
      } catch (_: RuntimeException) {
        synchronized(lock) { backendHealthy = false }
      } catch (_: IOException) {
        synchronized(lock) { backendHealthy = false }
      }
    }
    publisher.update(synchronized(lock) { snapshotLocked() })
  }

  private fun scheduleRefresh() {
    val failureSnapshot = synchronized(lock) {
      if (!enabled || closed || refreshTask != null) return
      val deadline = addSaturated(clock.nowMillis(), REFRESH_MILLIS)
      try {
        refreshTask = scheduler.schedule(deadline) {
          synchronized(lock) { refreshTask = null }
          refresh()
          scheduleRefresh()
        }
        null
      } catch (_: RuntimeException) {
        backendHealthy = false
        refreshTask = null
        snapshotLocked()
      }
    }
    failureSnapshot?.let(publisher::update)
  }

  private fun snapshotLocked(): NetplayDiscoverySnapshot =
      NetplayDiscoverySnapshot(
          enabled,
          backendHealthy,
          Collections.unmodifiableList(
              cache.values.mapNotNull { value ->
                if (value.addresses.isEmpty()) null
                else NetplayDiscoveredHost(
                    value.advertisement,
                    value.addresses.keys,
                    value.lastSeen,
                )
              }.sortedBy { stableId(it.advertisement.sessionId) },
          ),
      )

  override fun close() {
    val finalSnapshot = synchronized(lock) {
      if (closed) return
      closed = true
      enabled = false
      cache.clear()
      refreshTask?.close()
      refreshTask = null
      advanceLifecycleLocked()
      snapshotLocked()
    }
    signalLifecycle()
    ownedScheduler?.close()
    publisher.update(finalSnapshot)
    publisher.close()
  }

  private fun lifecycleLoop() {
    while (true) {
      try {
        lifecycleSignals.take()
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        return
      }
      while (true) {
        val target = synchronized(lock) {
          LifecycleTarget(lifecycleGeneration, enabled, closed, backendRunning)
        }
        if (target.closed) {
          closeBackend()
          synchronized(lock) {
            backendRunning = false
            settleLifecycleLocked(target.generation)
          }
          return
        }
        if (target.enabled && !target.running) {
          val startedSuccessfully = startBackend()
          val accepted = synchronized(lock) {
            if (startedSuccessfully) backendRunning = true
            startedSuccessfully && !closed && enabled &&
                lifecycleGeneration == target.generation
          }
          if (!accepted && startedSuccessfully) {
            stopBackend()
            synchronized(lock) { backendRunning = false }
          }
          if (accepted) {
            refresh()
            scheduleRefresh()
          }
        } else if (!target.enabled && target.running) {
          stopBackend()
          synchronized(lock) { backendRunning = false }
        }
        val reconciled = synchronized(lock) {
          if (lifecycleGeneration == target.generation) {
            settleLifecycleLocked(target.generation)
            true
          } else {
            false
          }
        }
        if (reconciled) break
      }
    }
  }

  private fun startBackend(): Boolean =
      try {
        backend.start(::receive)
        synchronized(lock) { backendHealthy = true }
        true
      } catch (_: RuntimeException) {
        synchronized(lock) { backendHealthy = false }
        false
      } catch (_: IOException) {
        synchronized(lock) { backendHealthy = false }
        false
      }

  private fun stopBackend() {
    try {
      backend.stop()
    } catch (_: RuntimeException) {
      synchronized(lock) { backendHealthy = false }
    } catch (_: IOException) {
      synchronized(lock) { backendHealthy = false }
    }
  }

  private fun closeBackend() {
    try {
      backend.close()
    } catch (_: RuntimeException) {
      // Discovery is ancillary and cannot affect direct hosting cleanup.
    } catch (_: IOException) {
      // Discovery is ancillary and cannot affect direct hosting cleanup.
    }
  }

  private fun advanceLifecycleLocked() {
    lifecycleSettled.countDown()
    lifecycleGeneration = addSaturated(lifecycleGeneration, 1)
    lifecycleSettled = CountDownLatch(1)
  }

  private fun settleLifecycleLocked(generation: Long) {
    if (generation == lifecycleGeneration) lifecycleSettled.countDown()
  }

  private fun signalLifecycle() {
    lifecycleSignals.offer(Unit)
  }

  private data class LifecycleTarget(
      val generation: Long,
      val enabled: Boolean,
      val closed: Boolean,
      val running: Boolean,
  )

  private class CachedHost(
      var advertisement: NetplayDiscoveryAdvertisement,
      var lastSeen: Long,
      val addresses: MutableMap<String, InetAddress> = linkedMapOf(),
  )

  companion object {
    const val MAX_SERVICES = 64
    const val MAX_ADDRESSES_PER_SERVICE = 8
    const val MAX_PACKET_BYTES = 64
    const val CACHE_EXPIRY_MILLIS = 5_000L
    const val REFRESH_MILLIS = 1_000L

    private fun safeNumericAddress(value: String): Boolean = NetplayNumericAddress.isValid(value)

    private fun stableId(value: NetplayPublicSessionId): String {
      val digits = "0123456789abcdef"
      val bytes = value.bytes()
      val result = StringBuilder(bytes.size * 2)
      bytes.forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        result.append(digits[unsigned ushr 4]).append(digits[unsigned and 0x0f])
      }
      return result.toString()
    }

    private fun addSaturated(left: Long, right: Long): Long =
        try {
          Math.addExact(left, right)
        } catch (_: ArithmeticException) {
          Long.MAX_VALUE
        }

    private fun elapsedMillis(now: Long, since: Long): Long =
        try {
          Math.subtractExact(now, since).coerceAtLeast(0)
        } catch (_: ArithmeticException) {
          if (now >= since) Long.MAX_VALUE else 0
        }
  }
}

/** Keeps advertisement gating tied to the real listener without coupling discovery to sockets. */
class NetplayDiscoveryHostBinding(
    source: NetplaySnapshotSource<V9FoundationServerStatus>,
    private val discovery: NetplayDiscovery,
) : Closeable {
  private val mode = source.snapshot().mode
  private val subscription = source.addListener(discovery::updateHost)
  override fun close() {
    subscription.close()
    discovery.updateHost(V9FoundationServerStatus(false, 0, mode, 0))
  }
}

/**
 * Java-16-only multicast backend. It carries the fixed 28-byte metadata datagram, never an
 * invitation or emulator content. All socket work is owned by two daemon threads.
 */
internal class V9MulticastDiscoveryBackend : NetplayDiscoveryBackend {
  private val lifecycleLock = Any()
  private val closed = AtomicBoolean(false)
  private val running = AtomicBoolean(false)
  private val outgoing = ArrayBlockingQueue<ByteArray>(1)
  private var socket: MulticastSocket? = null
  private var interfaces: List<NetworkInterface> = emptyList()
  private var receiverTask: Thread? = null
  private var senderTask: Thread? = null
  private var receiverTerminated = CountDownLatch(0)
  private var senderTerminated = CountDownLatch(0)

  override fun start(receiver: (NetplayDiscoveryDatagram) -> Unit) {
    synchronized(lifecycleLock) {
      check(!closed.get()) { "discovery backend is closed" }
      check(receiverTask?.isAlive != true && senderTask?.isAlive != true) {
        "discovery backend workers have not terminated"
      }
      check(running.compareAndSet(false, true)) { "discovery backend already started" }
      val value = MulticastSocket(null)
      try {
        value.reuseAddress = true
        value.bind(InetSocketAddress(PORT))
        value.timeToLive = 1
        val interfaces =
            (NetworkInterface.getNetworkInterfaces()?.let(Collections::list) ?: emptyList())
                .filter { it.isUp }
                .sortedBy { it.name }
                .take(MAX_INTERFACES)
        this.interfaces = interfaces
        for (network in interfaces) {
          try {
            value.joinGroup(InetSocketAddress(IPV4_GROUP, PORT), network)
          } catch (_: IOException) {
            // One interface/family failure does not disable direct hosting or other interfaces.
          }
          try {
            value.joinGroup(InetSocketAddress(IPV6_GROUP, PORT), network)
          } catch (_: IOException) {
            // IPv6 is best effort on hosts without a multicast route.
          }
        }
        value.soTimeout = 250
        socket = value
        receiverTerminated = CountDownLatch(1)
        senderTerminated = CountDownLatch(1)
        val receiverDone = receiverTerminated
        val senderDone = senderTerminated
        receiverTask = thread(isDaemon = true, name = "netplay-v9-discovery-receive") {
          try {
            receiveLoop(value, receiver)
          } finally {
            receiverDone.countDown()
          }
        }
        senderTask = thread(isDaemon = true, name = "netplay-v9-discovery-send") {
          try {
            sendLoop(value)
          } finally {
            senderDone.countDown()
          }
        }
      } catch (failure: RuntimeException) {
        value.close()
        running.set(false)
        receiverTask?.interrupt()
        senderTask?.interrupt()
        awaitTermination(receiverTask, receiverTerminated)
        awaitTermination(senderTask, senderTerminated)
        receiverTask = null
        senderTask = null
        throw failure
      } catch (failure: IOException) {
        value.close()
        running.set(false)
        receiverTask?.interrupt()
        senderTask?.interrupt()
        awaitTermination(receiverTask, receiverTerminated)
        awaitTermination(senderTask, senderTerminated)
        receiverTask = null
        senderTask = null
        throw failure
      }
    }
  }

  override fun advertise(bytes: ByteArray) {
    require(bytes.size == NetplayDiscoveryCodec.BYTES)
    synchronized(lifecycleLock) {
      if (closed.get() || !running.get()) return
      val owned = bytes.copyOf()
      outgoing.poll()?.fill(0)
      if (!outgoing.offer(owned)) owned.fill(0)
    }
  }

  override fun withdraw() {
    synchronized(lifecycleLock) { outgoing.poll()?.fill(0) }
  }

  private fun receiveLoop(
      socket: MulticastSocket,
      receiver: (NetplayDiscoveryDatagram) -> Unit,
  ) {
    val buffer = ByteArray(TrustedLanNetplayDiscovery.MAX_PACKET_BYTES)
    while (running.get() && !closed.get()) {
      val packet = DatagramPacket(buffer, buffer.size)
      try {
        socket.receive(packet)
        if (packet.length <= TrustedLanNetplayDiscovery.MAX_PACKET_BYTES) {
          receiver(
              NetplayDiscoveryDatagram(
                  packet.data.copyOfRange(packet.offset, packet.offset + packet.length),
                  packet.address,
              ),
          )
        }
      } catch (_: java.net.SocketTimeoutException) {
        // Poll close.
      } catch (_: IOException) {
        if (running.get() && !closed.get()) return
      } catch (_: RuntimeException) {
        if (running.get() && !closed.get()) continue
      }
    }
  }

  private fun sendLoop(socket: MulticastSocket) {
    try {
      while (running.get() && !closed.get()) {
        val bytes = outgoing.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
        try {
          val targets: List<NetworkInterface?> =
              if (interfaces.isEmpty()) listOf(null) else interfaces
          for (network in targets) {
            if (network != null) socket.networkInterface = network
            try {
              socket.send(DatagramPacket(bytes, bytes.size, IPV4_GROUP, PORT))
            } catch (_: IOException) {
              // Continue with other interfaces/families.
            }
            try {
              socket.send(DatagramPacket(bytes, bytes.size, IPV6_GROUP, PORT))
            } catch (_: IOException) {
              // IPv4 discovery remains available when IPv6 multicast is unavailable.
            }
          }
        } finally {
          bytes.fill(0)
        }
      }
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
    } catch (_: IOException) {
      // Discovery failure is isolated from direct hosting.
    }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    stop()
  }

  override fun stop() {
    val stopped = synchronized(lifecycleLock) {
      if (!running.compareAndSet(true, false)) {
        wipeOutgoing()
        return
      }
      val value = StopState(
          socket,
          receiverTask,
          senderTask,
          receiverTerminated,
          senderTerminated,
      )
      socket = null
      interfaces = emptyList()
      value.socket?.close()
      value.receiver?.takeUnless { it === Thread.currentThread() }?.interrupt()
      value.sender?.takeUnless { it === Thread.currentThread() }?.interrupt()
      wipeOutgoing()
      value
    }
    val receiverStopped = awaitTermination(stopped.receiver, stopped.receiverDone)
    val senderStopped = awaitTermination(stopped.sender, stopped.senderDone)
    synchronized(lifecycleLock) {
      if (receiverStopped && receiverTask === stopped.receiver) receiverTask = null
      if (senderStopped && senderTask === stopped.sender) senderTask = null
    }
  }

  internal fun liveWorkerCount(): Int = synchronized(lifecycleLock) {
    listOfNotNull(receiverTask, senderTask).count(Thread::isAlive)
  }

  internal fun queuedAdvertisementCount(): Int = outgoing.size

  internal fun awaitWorkersStopped(timeout: Long, unit: TimeUnit): Boolean {
    val deadlines = synchronized(lifecycleLock) { receiverTerminated to senderTerminated }
    val started = System.nanoTime()
    if (!deadlines.first.await(timeout, unit)) return false
    val budgetNanos = unit.toNanos(timeout)
    val remaining = (budgetNanos - (System.nanoTime() - started)).coerceAtLeast(0)
    return deadlines.second.await(remaining, TimeUnit.NANOSECONDS)
  }

  private fun awaitTermination(task: Thread?, terminated: CountDownLatch): Boolean {
    if (task == null) return true
    if (task === Thread.currentThread()) return false
    return try {
      terminated.await(STOP_WAIT_MILLIS, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      false
    }
  }

  private fun wipeOutgoing() {
    outgoing.forEach { it.fill(0) }
    outgoing.clear()
  }

  private data class StopState(
      val socket: MulticastSocket?,
      val receiver: Thread?,
      val sender: Thread?,
      val receiverDone: CountDownLatch,
      val senderDone: CountDownLatch,
  )

  companion object {
    private const val PORT = 37_619
    private const val MAX_INTERFACES = 8
    private const val STOP_WAIT_MILLIS = 1_000L
    private val IPV4_GROUP = InetAddress.getByName("239.255.67.66")
    private val IPV6_GROUP = InetAddress.getByName("ff02::4347:4244")
  }
}
