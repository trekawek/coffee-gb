package eu.rekawek.coffeegb.controller.network.v9

import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

interface V9TransportChannel : Closeable {
  @Throws(IOException::class)
  fun read(bytes: ByteArray, offset: Int, length: Int): Int

  @Throws(IOException::class)
  fun write(bytes: ByteArray, offset: Int, length: Int): Int

  @Throws(IOException::class)
  fun shutdownOutput()
}

interface V9ConnectableChannel : V9TransportChannel {
  @Throws(IOException::class)
  fun connect(address: InetSocketAddress, timeoutMillis: Int)
}

class V9SocketChannel(private val socket: Socket) : V9ConnectableChannel {
  override fun connect(address: InetSocketAddress, timeoutMillis: Int) {
    socket.connect(address, timeoutMillis)
    socket.tcpNoDelay = true
    socket.keepAlive = true
    socket.soTimeout = V9Timeout.WAIT_SERVER_HELLO.milliseconds.toInt()
  }

  override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
      socket.getInputStream().read(bytes, offset, length)

  override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
    socket.getOutputStream().write(bytes, offset, length)
    return length
  }

  override fun shutdownOutput() {
    if (!socket.isOutputShutdown) socket.shutdownOutput()
  }

  override fun close() {
    socket.close()
  }
}

fun interface V9DeadlineScheduler {
  fun schedule(deadlineMillis: Long, action: Runnable): Closeable
}

class V9SystemDeadlineScheduler(
    private val clock: V9MonotonicClock = V9MonotonicClock.SYSTEM,
) : V9DeadlineScheduler, Closeable {
  private val executor =
      ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "netplay-v9-deadline").also { it.isDaemon = true }
      }.also { it.removeOnCancelPolicy = true }

  override fun schedule(deadlineMillis: Long, action: Runnable): Closeable {
    val now = clock.nowMillis()
    val delay =
        if (deadlineMillis <= now) {
          0
        } else {
          try {
            Math.subtractExact(deadlineMillis, now)
          } catch (_: ArithmeticException) {
            Long.MAX_VALUE
          }
        }
    val future = executor.schedule(action, delay, TimeUnit.MILLISECONDS)
    return Closeable { future.cancel(false) }
  }

  override fun close() {
    executor.shutdownNow()
  }
}

internal class V9WriterQueue {
  private val queue =
      ArrayBlockingQueue<QueuedWrite>(V9Limit.QUEUED_FRAMES.value.toInt())
  private var wireBytes = 0L
  private var closed = false

  @Synchronized
  fun offer(value: ByteArray, onWritten: () -> Unit): Boolean {
    if (closed) return false
    if (queue.remainingCapacity() == 0) return false
    val next = try {
      Math.addExact(wireBytes, value.size.toLong())
    } catch (_: ArithmeticException) {
      return false
    }
    if (next > V9Limit.QUEUED_WIRE_BYTES.value) return false
    val owned = value.copyOf()
    if (!queue.offer(QueuedWrite(owned, onWritten))) return false
    wireBytes = next
    return true
  }

  fun poll(timeoutMillis: Long): QueuedWrite? =
      queue.poll(timeoutMillis, TimeUnit.MILLISECONDS)

  @Synchronized
  fun completed(value: QueuedWrite) {
    if (closed) return
    wireBytes = Math.subtractExact(wireBytes, value.bytes.size.toLong())
  }

  @Synchronized
  fun close() {
    closed = true
    queue.clear()
    wireBytes = 0
  }

  @Synchronized
  fun snapshot(): V9QueueSnapshot = V9QueueSnapshot(queue.size.toLong(), wireBytes, 0)

  data class QueuedWrite(val bytes: ByteArray, val onWritten: () -> Unit)
}

/**
 * Opt-in protocol-v9 transport foundation.
 *
 * This owner performs only the server-first HELLO exchange. It stops at the role-specific
 * `WAIT_AUTH`/`SEND_AUTH` state (public phase `AWAITING_PAIRING`); no invitation, manifest,
 * consent, private payload, checkpoint, or gameplay message can be queued or delivered.
 */
class V9FoundationConnection(
    private val channel: V9TransportChannel,
    val role: V9Role,
    private val mode: V9LinkMode = V9LinkMode.NORMAL,
    nonce: ByteArray = randomNonce(),
    optionalCapabilities: Set<V9Capability> = emptySet(),
    private val clock: V9MonotonicClock = V9MonotonicClock.SYSTEM,
    scheduler: V9DeadlineScheduler? = null,
) : Closeable, V9LifecycleSource {
  private val scheduler = scheduler ?: V9SystemDeadlineScheduler(clock)
  private val ownedScheduler = if (scheduler == null) this.scheduler as Closeable else null
  private val lifecycle = V9Lifecycle(role, clock)
  private val localHello = V9HelloCodec.create(role, nonce, optionalCapabilities)
  private val decoder =
      V9IncrementalDecoder(
          policy =
              V9DecoderPolicy(
                  allowedMessages = setOf(V9MessageType.HELLO, V9MessageType.ERROR),
                  negotiatedCapabilities = V9Capability.entries.toSet(),
                  linkMode = mode,
              ),
      )
  private val writer = V9WriterQueue()
  private val responseLedger = V9ResponseLedger()
  private val closed = AtomicBoolean(false)
  private val started = AtomicBoolean(false)
  private val boundary = CountDownLatch(1)
  private val tasks = mutableListOf<Thread>()
  private val taskLock = Any()
  private val ownershipLock = Any()
  private val closeListenerLock = Any()
  private val closeListeners = mutableListOf<() -> Unit>()
  private val wireStateLock = Any()
  private var timeoutTask: Closeable? = null
  private var writerTask: Thread? = null
  private var negotiated: V9NegotiatedCapabilities? = null
  private var nextOutgoingSequence = 0L

  init {
    lifecycle.addListener { state ->
      scheduleDeadline(state)
      if (state.phase == V9LifecyclePhase.AWAITING_PAIRING ||
          state.phase == V9LifecyclePhase.CLOSED) {
        boundary.countDown()
      }
    }
  }

  override fun snapshot(): V9LifecycleSnapshot = lifecycle.snapshot()

  override fun addListener(listener: V9LifecycleListener): Closeable =
      lifecycle.addListener(listener)

  fun start() {
    synchronized(ownershipLock) {
      check(!closed.get()) { "v9 foundation is closed" }
      check(started.compareAndSet(false, true)) { "v9 foundation already started" }
      writerTask = startTask("netplay-v9-writer", ::writeLoop)
      if (role == V9Role.SERVER) {
        // The server-first contract does not admit a peer byte until the complete HELLO is written.
        enqueueHello {
          lifecycle.serverHelloSent()
          startTask("netplay-v9-reader", ::readLoop)
        }
      } else {
        startTask("netplay-v9-reader", ::readLoop)
      }
    }
  }

  fun awaitPairingBoundary(timeout: Long, unit: TimeUnit): V9LifecycleSnapshot {
    boundary.await(timeout, unit)
    return snapshot()
  }

  fun negotiatedCapabilities(): Set<V9Capability> =
      negotiated?.capabilities?.toSet() ?: emptySet()

  fun writerQueueSnapshot(): V9QueueSnapshot = writer.snapshot()

  internal fun isClosed(): Boolean = closed.get()

  internal fun activeTaskCount(): Int = synchronized(taskLock) { tasks.count(Thread::isAlive) }

  internal fun addCloseListener(listener: () -> Unit): Closeable {
    var invokeNow = false
    synchronized(closeListenerLock) {
      if (closed.get()) invokeNow = true else closeListeners += listener
    }
    if (invokeNow) listener()
    return Closeable {
      synchronized(closeListenerLock) { closeListeners.remove(listener) }
    }
  }

  /** Phase #347 deliberately refuses all post-HELLO traffic. */
  fun sendUnavailable(type: V9MessageType): Nothing {
    require(type != V9MessageType.HELLO && type != V9MessageType.ERROR)
    throw V9ProtocolException(V9ErrorCode.UNEXPECTED_MESSAGE, 0)
  }

  fun cancel() {
    synchronized(ownershipLock) {
      if (!closed.get()) lifecycle.cancel()
      closeResourcesLocked()
    }
  }

  override fun close() {
    synchronized(ownershipLock) {
      if (!closed.get()) lifecycle.closeNormally()
      closeResourcesLocked()
    }
  }

  private fun enqueueHello(onWritten: () -> Unit) {
    val payload = V9HelloCodec.encode(localHello)
    val sequence = synchronized(wireStateLock) { nextOutgoingSequence }
    val encoded =
        try {
          V9FrameEncoder.encode(
              V9OutboundFrame(
                  V9MessageType.HELLO,
                  0,
                  sequence,
                  0,
                  ProtocolV9.CONTROL_CHANNEL,
                  payload,
              ),
              V9DecoderPolicy(
                  allowedMessages = setOf(V9MessageType.HELLO),
                  negotiatedCapabilities = V9Capability.entries.toSet(),
                  linkMode = mode,
              ),
          )
        } catch (e: V9ProtocolException) {
          fail(e.reason, V9Diagnostic.HELLO_REJECTED)
          return
        }
    if (!writer.offer(encoded) {
          advanceOutgoingSequence()
          onWritten()
        }) {
      fail(V9ErrorCode.QUEUE_OVERFLOW, V9Diagnostic.QUEUE_FULL)
    }
  }

  private fun readLoop() {
    val bytes = ByteArray(8_192)
    try {
      while (!closed.get()) {
        val count = channel.read(bytes, 0, bytes.size)
        if (count < 0) {
          if (snapshot().state == V9LifecycleState.TERMINAL_CLEANUP) {
            lifecycle.completeTerminalCleanup()
            closeResources()
            return
          }
          val result = decoder.finish()
          val reason = result.failure?.reason ?: V9ErrorCode.UNEXPECTED_EOF
          fail(reason, V9Diagnostic.IO_FAILURE)
          return
        }
        if (count == 0) continue
        if (snapshot().state == V9LifecycleState.TERMINAL_CLEANUP) {
          // Output is half-closed. Peer bytes are drained only to observe EOF; no frame is admitted.
          continue
        }
        val result = decoder.feed(bytes, 0, count)
        result.frames.forEach { frame ->
          frame.use {
            if (!closed.get() &&
                snapshot().state != V9LifecycleState.TERMINAL_CLEANUP) {
              handleFrame(frame)
            }
          }
        }
        result.failure?.let {
          reject(it.reason, diagnosticFor(it.reason))
        }
      }
    } catch (e: V9ProtocolException) {
      reject(e.reason, diagnosticFor(e.reason))
    } catch (_: SocketTimeoutException) {
      fail(V9ErrorCode.TIMEOUT, V9Diagnostic.TIMEOUT)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      if (!closed.get() &&
          snapshot().state != V9LifecycleState.TERMINAL_CLEANUP) {
        fail(V9ErrorCode.CANCELLED, V9Diagnostic.CANCELLED)
      }
    } catch (_: IOException) {
      if (!closed.get()) fail(V9ErrorCode.INTERNAL_ERROR, V9Diagnostic.IO_FAILURE)
    } catch (_: RuntimeException) {
      if (!closed.get()) fail(V9ErrorCode.INTERNAL_ERROR, V9Diagnostic.IO_FAILURE)
    }
  }

  private fun writeLoop() {
    try {
      while (!closed.get()) {
        val value = writer.poll(50) ?: continue
        synchronized(wireStateLock) {
          writeFully(value.bytes)
          writer.completed(value)
          if (!closed.get()) value.onWritten()
        }
      }
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      if (!closed.get() &&
          snapshot().state != V9LifecycleState.TERMINAL_CLEANUP) {
        fail(V9ErrorCode.CANCELLED, V9Diagnostic.CANCELLED)
      }
    } catch (_: IOException) {
      if (!closed.get()) fail(V9ErrorCode.INTERNAL_ERROR, V9Diagnostic.IO_FAILURE)
    } catch (_: RuntimeException) {
      if (!closed.get()) fail(V9ErrorCode.INTERNAL_ERROR, V9Diagnostic.IO_FAILURE)
    }
  }

  private fun handleFrame(frame: V9Frame) {
    synchronized(wireStateLock) {
      val type = frame.header.type
          ?: return reject(V9ErrorCode.UNKNOWN_REQUIRED_TYPE, V9Diagnostic.HELLO_REJECTED)
      responseLedger.accept(
              frame.header.sequence,
              type,
              frame.header.flags,
              frame.header.correlation,
          )
          ?.let {
            reject(it, V9Diagnostic.HELLO_REJECTED)
            return
          }
      when (type) {
        V9MessageType.HELLO -> handleHello(V9HelloCodec.decode(frame.payloadView()))
        V9MessageType.ERROR -> {
          val remote = V9ErrorPayloadCodec.decode(frame.payloadView())
          if (role == V9Role.CLIENT &&
              snapshot().state == V9LifecycleState.WAIT_SERVER_HELLO &&
              (frame.header.flags != V9Flag.TERMINAL.wireMask ||
                  frame.header.sequence != 0L ||
                  remote.error !in setOf(V9ErrorCode.SERVER_BUSY, V9ErrorCode.SERVER_FULL))) {
            reject(V9ErrorCode.UNEXPECTED_MESSAGE, V9Diagnostic.HELLO_REJECTED)
            return
          }
          fail(remote.error, diagnosticFor(remote.error))
        }
        else -> reject(V9ErrorCode.UNEXPECTED_MESSAGE, V9Diagnostic.HELLO_REJECTED)
      }
    }
  }

  private fun handleHello(remote: V9Hello) {
    try {
      when (role) {
        V9Role.CLIENT -> {
          if (snapshot().state != V9LifecycleState.WAIT_SERVER_HELLO) {
            return fail(V9ErrorCode.UNEXPECTED_MESSAGE, V9Diagnostic.HELLO_REJECTED)
          }
          val result = V9HelloCodec.negotiate(localHello, remote, V9Role.SERVER, mode)
          negotiated = result
          lifecycle.serverHelloReceived()
          enqueueHello { lifecycle.clientHelloSent(result) }
        }
        V9Role.SERVER -> {
          if (snapshot().state != V9LifecycleState.WAIT_CLIENT_HELLO) {
            return fail(V9ErrorCode.UNEXPECTED_MESSAGE, V9Diagnostic.HELLO_REJECTED)
          }
          val result = V9HelloCodec.negotiate(localHello, remote, V9Role.CLIENT, mode)
          negotiated = result
          lifecycle.clientHelloReceived(result)
        }
      }
    } catch (e: V9ProtocolException) {
      reject(e.reason, V9Diagnostic.CAPABILITY_MISMATCH)
    }
  }

  private fun reject(reason: V9ErrorCode, diagnostic: V9Diagnostic) {
    if (closed.get() || snapshot().state == V9LifecycleState.TERMINAL_CLEANUP) return
    writer.close()
    writerTask?.takeUnless { it === Thread.currentThread() }?.interrupt()
    lifecycle.beginTerminalCleanup(reason, diagnostic)
    if (reason.peerVisible &&
        reason != V9ErrorCode.UNSUPPORTED_PROTOCOL &&
        !closed.get()) {
      try {
        sendProtocolError(reason)
      } catch (_: IOException) {
        // Local state remains a typed rejection; raw I/O details are never exposed.
        lifecycle.completeTerminalCleanup()
        closeResources()
      } catch (_: V9ProtocolException) {
        // An exhausted direction cannot emit another frame and closes below.
        lifecycle.completeTerminalCleanup()
        closeResources()
      }
    } else {
      lifecycle.completeTerminalCleanup()
      closeResources()
    }
  }

  @Throws(IOException::class, V9ProtocolException::class)
  private fun sendProtocolError(reason: V9ErrorCode) {
    synchronized(wireStateLock) {
      if (closed.get() || nextOutgoingSequence > ProtocolV9.LAST_SEQUENCE) return
      val bytes =
          V9FrameEncoder.encode(
              V9OutboundFrame(
                  V9MessageType.ERROR,
                  V9Flag.TERMINAL.wireMask,
                  nextOutgoingSequence,
                  0,
                  ProtocolV9.CONTROL_CHANNEL,
                  V9ErrorPayloadCodec.encode(reason),
              ),
          )
      writeFully(bytes)
      advanceOutgoingSequence()
      channel.shutdownOutput()
    }
  }

  @Throws(IOException::class)
  private fun writeFully(bytes: ByteArray) {
    var offset = 0
    while (offset < bytes.size && !closed.get()) {
      val count = channel.write(bytes, offset, bytes.size - offset)
      if (count <= 0) throw IOException("v9 writer made no progress")
      offset = Math.addExact(offset, count)
    }
    if (offset != bytes.size) throw IOException("v9 writer closed")
  }

  private fun advanceOutgoingSequence() {
    nextOutgoingSequence =
        if (nextOutgoingSequence == ProtocolV9.LAST_SEQUENCE) {
          ProtocolV9.EXHAUSTED_SEQUENCE
        } else {
          Math.addExact(nextOutgoingSequence, 1)
        }
  }

  private fun startTask(name: String, block: () -> Unit): Thread {
    check(!closed.get()) { "v9 foundation is closed" }
    val task = thread(start = false, isDaemon = true, name = name, block = block)
    synchronized(taskLock) { tasks += task }
    task.start()
    return task
  }

  private fun scheduleDeadline(state: V9LifecycleSnapshot) {
    synchronized(taskLock) {
      timeoutTask?.close()
      timeoutTask = null
      val deadline = state.deadlineMillis ?: return
      timeoutTask =
          scheduler.schedule(deadline) {
            val current = lifecycle.snapshot()
            if (!closed.get() &&
                current.state == state.state &&
                current.deadlineMillis == deadline &&
                clock.nowMillis() >= deadline) {
              lifecycle.checkDeadline()
              closeResources()
            }
          }
    }
  }

  private fun fail(reason: V9ErrorCode, diagnostic: V9Diagnostic) {
    if (!closed.get()) lifecycle.fail(reason, diagnostic)
    closeResources()
  }

  private fun closeResources() {
    synchronized(ownershipLock) { closeResourcesLocked() }
  }

  private fun closeResourcesLocked() {
    if (!closed.compareAndSet(false, true)) return
    synchronized(taskLock) {
      timeoutTask?.close()
      timeoutTask = null
    }
    writer.close()
    try {
      channel.close()
    } catch (_: IOException) {
      // The typed lifecycle state already reports the local failure without leaking I/O text.
    }
    synchronized(taskLock) {
      tasks.filter { it !== Thread.currentThread() }.forEach(Thread::interrupt)
    }
    ownedScheduler?.close()
    boundary.countDown()
    val listeners =
        synchronized(closeListenerLock) {
          closeListeners.toList().also { closeListeners.clear() }
        }
    listeners.forEach {
      try {
        it()
      } catch (_: RuntimeException) {
        // Resource release is final even when an observer is faulty.
      }
    }
  }

  private fun diagnosticFor(reason: V9ErrorCode): V9Diagnostic = when (reason) {
    V9ErrorCode.UNSUPPORTED_PROTOCOL -> V9Diagnostic.PROTOCOL_MISMATCH
    V9ErrorCode.CAPABILITY_MISMATCH,
    V9ErrorCode.UNKNOWN_REQUIRED_CAPABILITY -> V9Diagnostic.CAPABILITY_MISMATCH
    V9ErrorCode.TIMEOUT -> V9Diagnostic.TIMEOUT
    V9ErrorCode.CANCELLED -> V9Diagnostic.CANCELLED
    V9ErrorCode.QUEUE_OVERFLOW -> V9Diagnostic.QUEUE_FULL
    else -> V9Diagnostic.HELLO_REJECTED
  }

  companion object {
    private fun randomNonce(): ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)
  }
}

/**
 * Opt-in listener used only by foundation diagnostics/tests until #348 supplies pairing.
 * It is intentionally not reachable from [eu.rekawek.coffeegb.controller.network.ConnectionController].
 */
class V9FoundationServer(
    private val port: Int = 0,
    private val mode: V9LinkMode = V9LinkMode.NORMAL,
    private val optionalCapabilities: Set<V9Capability> = emptySet(),
    private val onAwaitingPairing: (V9FoundationConnection) -> Unit,
) : Closeable {
  private val stopped = AtomicBoolean(false)
  private val startStopLock = Any()
  private val callbackLock = Any()
  private val candidateLock = Any()
  private val connections = ConcurrentHashMap.newKeySet<V9FoundationConnection>()
  private val pending = ConcurrentHashMap.newKeySet<V9PendingCandidate>()
  private val workers =
      ThreadPoolExecutor(
          V9Limit.HANDSHAKE_WORKERS.value.toInt(),
          V9Limit.HANDSHAKE_WORKERS.value.toInt(),
          0,
          TimeUnit.MILLISECONDS,
          ArrayBlockingQueue(V9Limit.PENDING_HANDSHAKES.value.toInt()),
          { task -> Thread(task, "netplay-v9-handshake").also { it.isDaemon = true } },
      )
  private var listener: ServerSocket? = null
  private var acceptThread: Thread? = null

  internal var connectionFactory:
      (V9TransportChannel, V9Role, V9LinkMode, Set<V9Capability>) ->
          V9FoundationConnection =
      { channel, role, linkMode, capabilities ->
        V9FoundationConnection(
            channel,
            role,
            linkMode,
            optionalCapabilities = capabilities,
        )
      }

  internal var candidateHooks = V9ServerCandidateHooks()

  val localPort: Int get() = listener?.localPort ?: 0

  internal fun activeConnectionCount(): Int = connections.size

  internal fun pendingCandidateCount(): Int = pending.size

  internal fun acceptThreadAlive(): Boolean = acceptThread?.isAlive == true

  internal fun workerPoolTerminated(): Boolean = workers.isTerminated

  fun start() {
    synchronized(startStopLock) {
      check(!stopped.get()) { "v9 foundation listener is closed" }
      check(listener == null) { "v9 foundation listener already started" }
      listener = ServerSocket(port).also { it.soTimeout = 100 }
      acceptThread =
          thread(isDaemon = true, name = "netplay-v9-accept") {
            acceptLoop(requireNotNull(listener))
          }
    }
  }

  override fun close() {
    synchronized(callbackLock) {
      synchronized(startStopLock) {
        synchronized(candidateLock) {
          if (!stopped.compareAndSet(false, true)) return
        }
      }
    }
    try {
      listener?.close()
    } catch (_: IOException) {
      // Best-effort listener shutdown.
    }
    val neverStarted = workers.shutdownNow()
    neverStarted.filterIsInstance<V9PendingCandidate>().forEach(V9PendingCandidate::close)
    pending.toList().forEach(V9PendingCandidate::close)
    connections.toList().forEach(V9FoundationConnection::close)
    acceptThread?.interrupt()
  }

  private fun acceptLoop(server: ServerSocket) {
    while (!stopped.get()) {
      try {
        val socket = server.accept()
        val candidate = V9PendingCandidate(socket)
        try {
          candidateHooks.afterAcceptBeforeAdmission(socket)
          admit(candidate)
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          candidate.close()
        } catch (_: RuntimeException) {
          candidate.close()
        }
      } catch (_: SocketTimeoutException) {
        // Poll stop.
      } catch (_: SocketException) {
        if (!stopped.get()) continue
      } catch (_: IOException) {
        if (!stopped.get()) continue
      }
    }
  }

  private fun admit(candidate: V9PendingCandidate) {
    synchronized(candidateLock) {
      if (stopped.get() ||
          pending.size >= V9Limit.PENDING_HANDSHAKES.value) {
        candidate.close()
        return
      }
      pending += candidate
      try {
        workers.execute(candidate)
      } catch (_: RejectedExecutionException) {
        candidate.close()
      }
    }
  }

  private fun negotiate(socket: Socket) {
    var connection: V9FoundationConnection? = null
    try {
      socket.tcpNoDelay = true
      socket.keepAlive = true
      socket.soTimeout = V9Timeout.WAIT_CLIENT_HELLO.milliseconds.toInt()
      connection =
          connectionFactory(
              V9SocketChannel(socket),
              V9Role.SERVER,
              mode,
              optionalCapabilities,
          )
      connections += connection
      connection.addCloseListener { connections.remove(connection) }
      connection.start()
      val state =
          connection.awaitPairingBoundary(
              V9Timeout.WAIT_CLIENT_HELLO.milliseconds + 1_000,
              TimeUnit.MILLISECONDS,
          )
      if (state.phase == V9LifecyclePhase.AWAITING_PAIRING) {
        synchronized(callbackLock) {
          if (stopped.get()) {
            connection.close()
          } else {
            onAwaitingPairing(connection)
          }
        }
      } else {
        connection.close()
        connections.remove(connection)
      }
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      connection?.close()
      connection?.let(connections::remove)
      try {
        socket.close()
      } catch (_: IOException) {
        // Server shutdown owns this candidate and remains best effort.
      }
    } catch (_: IOException) {
      connection?.close()
      connection?.let(connections::remove)
      try {
        socket.close()
      } catch (_: IOException) {
        // Candidate isolation is more important than reporting remote details.
      }
    } catch (_: RuntimeException) {
      connection?.close()
      connection?.let(connections::remove)
      try {
        socket.close()
      } catch (_: IOException) {
        // Candidate construction/start/callback failures remain isolated to this socket.
      }
    }
  }

  private inner class V9PendingCandidate(
      private val socket: Socket,
  ) : Runnable, Closeable {
    private val candidateClosed = AtomicBoolean(false)

    override fun run() {
      try {
        candidateHooks.beforeWorkerNegotiation(socket)
        if (stopped.get()) {
          close()
          return
        }
        negotiate(socket)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        close()
      } catch (_: RuntimeException) {
        close()
      } finally {
        pending.remove(this)
      }
    }

    override fun close() {
      pending.remove(this)
      if (!candidateClosed.compareAndSet(false, true)) return
      try {
        socket.close()
      } catch (_: IOException) {
        // Candidate cleanup remains best effort and never affects another accept.
      }
    }
  }
}

internal class V9ServerCandidateHooks(
    var afterAcceptBeforeAdmission: (Socket) -> Unit = {},
    var beforeWorkerNegotiation: (Socket) -> Unit = {},
)

object V9FoundationClient {
  fun connect(
      address: InetSocketAddress,
      mode: V9LinkMode = V9LinkMode.NORMAL,
      optionalCapabilities: Set<V9Capability> = emptySet(),
      connectTimeoutMillis: Int = V9Timeout.WAIT_SERVER_HELLO.milliseconds.toInt(),
  ): V9FoundationConnection {
    val channel = V9SocketChannel(Socket())
    try {
      channel.connect(address, connectTimeoutMillis)
      return V9FoundationConnection(
          channel,
          V9Role.CLIENT,
          mode,
          optionalCapabilities = optionalCapabilities,
      ).also(V9FoundationConnection::start)
    } catch (e: IOException) {
      try {
        channel.close()
      } catch (_: IOException) {
        // Keep the original connect failure.
      }
      throw e
    }
  }
}

/**
 * Cancellable connect owner for callers that must never block the emulator thread or EDT.
 * The attempt owns the pending channel until it hands a started connection to the callback.
 */
class V9FoundationConnectAttempt(
    private val clock: V9MonotonicClock = V9MonotonicClock.SYSTEM,
    scheduler: V9DeadlineScheduler? = null,
    private val channelFactory: () -> V9ConnectableChannel =
        { V9SocketChannel(Socket()) },
) : Closeable {
  private val lock = Any()
  private val scheduler = scheduler ?: V9SystemDeadlineScheduler(clock)
  private val ownedScheduler = if (scheduler == null) this.scheduler as Closeable else null
  private var state = V9ConnectAttemptState.NEW
  private var pendingChannel: V9ConnectableChannel? = null
  private var pendingConnection: V9FoundationConnection? = null
  private var task: Thread? = null
  private var timeoutTask: Closeable? = null
  private var callback:
      ((V9FoundationConnection?, V9ErrorCode?) -> Unit)? = null
  private var callbackDelivered = false

  internal var hooks = V9ConnectAttemptHooks()

  fun start(
      address: InetSocketAddress,
      mode: V9LinkMode = V9LinkMode.NORMAL,
      optionalCapabilities: Set<V9Capability> = emptySet(),
      onComplete: (V9FoundationConnection?, V9ErrorCode?) -> Unit,
  ) {
    var cancelledBeforeStart = false
    synchronized(lock) {
      check(callback == null) { "v9 connect attempt already started" }
      callback = onComplete
      if (state == V9ConnectAttemptState.CANCELLED) {
        callbackDelivered = true
        cancelledBeforeStart = true
      } else {
        check(state == V9ConnectAttemptState.NEW) { "v9 connect attempt is closed" }
        state = V9ConnectAttemptState.CONNECTING
      }
    }
    if (cancelledBeforeStart) {
      onComplete(null, V9ErrorCode.CANCELLED)
      return
    }

    val deadline =
        try {
          Math.addExact(clock.nowMillis(), V9Timeout.WAIT_SERVER_HELLO.milliseconds)
        } catch (_: ArithmeticException) {
          Long.MAX_VALUE
        }
    val scheduled =
        scheduler.schedule(deadline) {
          if (clock.nowMillis() >= deadline) completeFailure(V9ErrorCode.TIMEOUT)
        }
    synchronized(lock) {
      if (state == V9ConnectAttemptState.CONNECTING) timeoutTask = scheduled
      else scheduled.close()
    }

    val worker =
        thread(start = false, isDaemon = true, name = "netplay-v9-connect") {
          runAttempt(address, mode, optionalCapabilities)
        }
    val startWorker =
        synchronized(lock) {
          if (state == V9ConnectAttemptState.CONNECTING) {
            task = worker
            true
          } else {
            false
          }
        }
    if (startWorker) worker.start()
  }

  fun isComplete(): Boolean =
      synchronized(lock) {
        state in
            setOf(
                V9ConnectAttemptState.HANDED_OFF,
                V9ConnectAttemptState.CANCELLED,
                V9ConnectAttemptState.TIMED_OUT,
                V9ConnectAttemptState.FAILED,
            )
      }

  fun cancel() {
    completeFailure(V9ErrorCode.CANCELLED)
  }

  override fun close() {
    cancel()
  }

  private fun runAttempt(
      address: InetSocketAddress,
      mode: V9LinkMode,
      optionalCapabilities: Set<V9Capability>,
  ) {
    var createdChannel: V9ConnectableChannel? = null
    try {
      createdChannel = channelFactory()
      if (!adoptChannel(createdChannel)) {
        closeQuietly(createdChannel)
        return
      }
      hooks.beforeConnect()
      if (!isState(V9ConnectAttemptState.CONNECTING)) return
      createdChannel.connect(
          address,
          V9Timeout.WAIT_SERVER_HELLO.milliseconds.toInt(),
      )
      if (!advance(V9ConnectAttemptState.CONNECTING, V9ConnectAttemptState.CONNECTED)) return
      hooks.afterConnect()
      if (!isState(V9ConnectAttemptState.CONNECTED)) return

      val value =
          V9FoundationConnection(
              createdChannel,
              V9Role.CLIENT,
              mode,
              optionalCapabilities = optionalCapabilities,
          )
      if (!adoptConnection(value)) {
        value.close()
        return
      }
      createdChannel = null
      hooks.afterConnectionCreated()
      if (!isState(V9ConnectAttemptState.CONSTRUCTED)) return
      value.start()
      if (!advance(V9ConnectAttemptState.CONSTRUCTED, V9ConnectAttemptState.STARTED)) return
      hooks.afterConnectionStarted()
      handOff(value)
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      completeFailure(V9ErrorCode.CANCELLED)
    } catch (_: IOException) {
      completeFailure(V9ErrorCode.INTERNAL_ERROR)
    } catch (_: RuntimeException) {
      completeFailure(V9ErrorCode.INTERNAL_ERROR)
    } finally {
      createdChannel?.let(::closeQuietly)
    }
  }

  private fun adoptChannel(channel: V9ConnectableChannel): Boolean =
      synchronized(lock) {
        if (state != V9ConnectAttemptState.CONNECTING) {
          false
        } else {
          pendingChannel = channel
          true
        }
      }

  private fun adoptConnection(connection: V9FoundationConnection): Boolean =
      synchronized(lock) {
        if (state != V9ConnectAttemptState.CONNECTED) {
          false
        } else {
          pendingConnection = connection
          pendingChannel = null
          state = V9ConnectAttemptState.CONSTRUCTED
          true
        }
      }

  private fun advance(
      expected: V9ConnectAttemptState,
      next: V9ConnectAttemptState,
  ): Boolean =
      synchronized(lock) {
        if (state != expected) false
        else {
          state = next
          true
        }
      }

  private fun isState(expected: V9ConnectAttemptState): Boolean =
      synchronized(lock) { state == expected }

  private fun handOff(connection: V9FoundationConnection) {
    val completion: ((V9FoundationConnection?, V9ErrorCode?) -> Unit)?
    val deadline: Closeable?
    synchronized(lock) {
      if (state != V9ConnectAttemptState.STARTED) return
      state = V9ConnectAttemptState.HANDED_OFF
      pendingConnection = null
      pendingChannel = null
      deadline = timeoutTask
      timeoutTask = null
      completion = callback.takeUnless { callbackDelivered }
      callbackDelivered = true
    }
    deadline?.close()
    ownedScheduler?.close()
    try {
      completion?.invoke(connection, null)
    } catch (_: RuntimeException) {
      // A callback that refuses ownership cannot leave the started connection orphaned.
      connection.close()
    }
  }

  private fun completeFailure(error: V9ErrorCode) {
    val channel: V9ConnectableChannel?
    val connection: V9FoundationConnection?
    val worker: Thread?
    val deadline: Closeable?
    val completion: ((V9FoundationConnection?, V9ErrorCode?) -> Unit)?
    synchronized(lock) {
      if (state == V9ConnectAttemptState.HANDED_OFF ||
          state == V9ConnectAttemptState.CANCELLED ||
          state == V9ConnectAttemptState.TIMED_OUT ||
          state == V9ConnectAttemptState.FAILED) {
        return
      }
      state =
          when (error) {
            V9ErrorCode.CANCELLED -> V9ConnectAttemptState.CANCELLED
            V9ErrorCode.TIMEOUT -> V9ConnectAttemptState.TIMED_OUT
            else -> V9ConnectAttemptState.FAILED
          }
      channel = pendingChannel
      connection = pendingConnection
      pendingChannel = null
      pendingConnection = null
      worker = task
      deadline = timeoutTask
      timeoutTask = null
      completion = callback.takeUnless { callbackDelivered }
      if (completion != null) callbackDelivered = true
    }
    deadline?.close()
    closeQuietly(channel)
    connection?.cancel()
    if (worker !== Thread.currentThread()) worker?.interrupt()
    ownedScheduler?.close()
    try {
      completion?.invoke(null, error)
    } catch (_: RuntimeException) {
      // A faulty observer cannot reopen or change an already-completed attempt.
    }
  }

  private fun closeQuietly(channel: V9TransportChannel?) {
    try {
      channel?.close()
    } catch (_: IOException) {
      // Completion remains typed and sanitized.
    }
  }
}

internal enum class V9ConnectAttemptState {
  NEW,
  CONNECTING,
  CONNECTED,
  CONSTRUCTED,
  STARTED,
  HANDED_OFF,
  CANCELLED,
  TIMED_OUT,
  FAILED,
}

internal class V9ConnectAttemptHooks(
    var beforeConnect: () -> Unit = {},
    var afterConnect: () -> Unit = {},
    var afterConnectionCreated: () -> Unit = {},
    var afterConnectionStarted: () -> Unit = {},
)
