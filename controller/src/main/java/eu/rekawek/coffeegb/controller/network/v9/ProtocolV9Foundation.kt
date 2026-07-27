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
  init {
    // Lifecycle deadlines own blocking-read cancellation. A fixed SO_TIMEOUT would preempt later
    // states whose frozen deadline is longer than the HELLO/AUTH deadline.
    socket.soTimeout = 0
  }

  override fun connect(address: InetSocketAddress, timeoutMillis: Int) {
    socket.connect(address, timeoutMillis)
    socket.tcpNoDelay = true
    socket.keepAlive = true
    socket.soTimeout = 0
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

private fun newV9SocketChannel(): V9SocketChannel {
  val socket = Socket()
  return try {
    V9SocketChannel(socket)
  } catch (e: IOException) {
    try {
      socket.close()
    } catch (_: IOException) {
      // Preserve the setup failure while closing the not-yet-owned socket best effort.
    }
    throw e
  } catch (e: RuntimeException) {
    try {
      socket.close()
    } catch (_: IOException) {
      // Preserve the setup failure while closing the not-yet-owned socket best effort.
    }
    throw e
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
    if (!queue.offer(QueuedWrite(owned, onWritten))) {
      owned.fill(0)
      return false
    }
    wireBytes = next
    return true
  }

  fun poll(timeoutMillis: Long): QueuedWrite? =
      queue.poll(timeoutMillis, TimeUnit.MILLISECONDS)

  @Synchronized
  fun completed(value: QueuedWrite) {
    if (!closed) {
      wireBytes = Math.subtractExact(wireBytes, value.bytes.size.toLong())
    }
    value.bytes.fill(0)
  }

  @Synchronized
  fun close() {
    closed = true
    queue.forEach { it.bytes.fill(0) }
    queue.clear()
    wireBytes = 0
  }

  @Synchronized
  fun snapshot(): V9QueueSnapshot = V9QueueSnapshot(queue.size.toLong(), wireBytes, 0)

  data class QueuedWrite(val bytes: ByteArray, val onWritten: () -> Unit)
}

/** Immutable Part-1 boundary. MANIFEST remains opt-in and all private traffic is unavailable. */
data class V9PostAuthBoundary(
    val role: V9Role,
    val mode: V9LinkMode,
    val slot: Int,
    val state: V9LifecycleState,
)

/**
 * Opt-in protocol-v9 transport foundation.
 *
 * Without invitation ownership this performs only the server-first HELLO exchange and stops at
 * `WAIT_AUTH`/`SEND_AUTH`. With explicit Part-1 invitation owners it performs AUTH and stops at
 * `SEND_SERVER_MANIFEST`/`WAIT_SERVER_MANIFEST`. With an explicit caller-prepared [manifestPlan],
 * it exchanges and validates MANIFEST and stops at the immutable pre-consent boundary. With an
 * explicit [part3Plan], item-scoped CONSENT and bounded ROM/battery preparation are enabled and
 * stop at an immutable pre-START boundary. An explicit [playPlan] additionally enables direct
 * StateFile-v2 CHECKPOINT, START/READY, and bounded ACTIVE traffic. Diagnostics and discovery
 * remain unavailable.
 */
class V9FoundationConnection(
    private val channel: V9TransportChannel,
    val role: V9Role,
    private val mode: V9LinkMode = V9LinkMode.NORMAL,
    nonce: ByteArray = randomNonce(),
    optionalCapabilities: Set<V9Capability> = emptySet(),
    private val clock: V9MonotonicClock = V9MonotonicClock.SYSTEM,
    scheduler: V9DeadlineScheduler? = null,
    private val invitationHost: V9InvitationHost? = null,
    private val clientInvitation: V9ClientInvitation? = null,
    private val manifestPlan: V9ManifestPlan? = null,
    private val part3Plan: V9Part3Plan? = null,
    private val playPlan: V9PlayPlan? = null,
) : Closeable, V9LifecycleSource {
  private val scheduler = scheduler ?: V9SystemDeadlineScheduler(clock)
  private val ownedScheduler = if (scheduler == null) this.scheduler as Closeable else null
  private val lifecycle = V9Lifecycle(role, clock)
  private val localHello = V9HelloCodec.create(role, nonce, optionalCapabilities)
  private val decoderPolicy =
      V9DecoderPolicy(
          allowedMessages =
              buildSet {
                add(V9MessageType.HELLO)
                add(V9MessageType.AUTH)
                add(V9MessageType.AUTH_RESULT)
                add(V9MessageType.CANCEL)
                add(V9MessageType.GOODBYE)
                add(V9MessageType.ERROR)
                if (manifestPlan != null) add(V9MessageType.MANIFEST)
                if (part3Plan != null) addAll(PART3_MESSAGES)
                if (playPlan != null) addAll(PLAY_MESSAGES)
              },
          negotiatedCapabilities = V9Capability.entries.toSet(),
          linkMode = mode,
          headerAdmission =
              if (part3Plan == null && playPlan == null) null
              else V9HeaderAdmission(::admitDynamicHeader),
      )
  private val decoder = V9IncrementalDecoder(policy = decoderPolicy)
  private val writer = V9WriterQueue()
  private val responseLedger = V9ResponseLedger()
  private val closed = AtomicBoolean(false)
  private val started = AtomicBoolean(false)
  private val boundary = CountDownLatch(1)
  private val postAuth = CountDownLatch(1)
  private val manifestComplete = CountDownLatch(1)
  private val preparationComplete = CountDownLatch(1)
  private val activeComplete = CountDownLatch(1)
  @Volatile private var pairingBoundary: V9LifecycleSnapshot? = null
  @Volatile private var completedManifestBoundary: V9ManifestPairingBoundary? = null
  @Volatile private var completedPreparationBoundary: V9PreparationBoundary? = null
  @Volatile private var completedActiveBoundary: V9ActiveBoundary? = null
  private val tasks = mutableListOf<Thread>()
  private val taskLock = Any()
  private val ownershipLock = Any()
  private val closeListenerLock = Any()
  private val closeListeners = mutableListOf<() -> Unit>()
  private val part3ListenerLock = Any()
  private val part3Registrations = mutableSetOf<Part3ListenerRegistration>()
  private val wireStateLock = Any()
  private var timeoutTask: Closeable? = null
  private var writerTask: Thread? = null
  private var negotiated: V9NegotiatedCapabilities? = null
  private var remoteHello: V9Hello? = null
  private var slotReservation: V9InvitationHost.Reservation? = null
  private var authenticatedBoundary: V9PostAuthBoundary? = null
  private var localManifestPayload: ByteArray? = null
  private var localManifest: V9Manifest? = null
  @Volatile private var part3Session: V9Part3Session? = null
  @Volatile private var playSession: V9PlaySession? = null
  private var nextOutgoingSequence = 0L

  init {
    require(invitationHost == null || role == V9Role.SERVER && invitationHost.mode == mode)
    require(clientInvitation == null || role == V9Role.CLIENT && clientInvitation.mode == mode)
    require(manifestPlan == null || manifestPlan.role == role && manifestPlan.mode == mode)
    require(manifestPlan == null || invitationHost != null || clientInvitation != null) {
      "v9 manifest exchange requires invitation authentication"
    }
    require(part3Plan == null || manifestPlan != null) {
      "v9 Part-3 requires an explicit manifest plan"
    }
    require(part3Plan == null || part3Plan.role == role && part3Plan.mode == mode)
    require(playPlan == null || part3Plan != null) {
      "v9 playable transport requires explicit Part-3 consent ownership"
    }
    require(playPlan == null || playPlan.role == role && playPlan.mode == mode)
    lifecycle.addListener { state ->
      scheduleDeadline(state)
      if (state.phase == V9LifecyclePhase.AWAITING_PAIRING) {
        pairingBoundary = state
        boundary.countDown()
      } else if (state.phase == V9LifecyclePhase.CLOSED) {
        boundary.countDown()
        manifestComplete.countDown()
        preparationComplete.countDown()
        activeComplete.countDown()
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
    return pairingBoundary ?: snapshot()
  }

  fun awaitPostAuthBoundary(timeout: Long, unit: TimeUnit): V9LifecycleSnapshot {
    postAuth.await(timeout, unit)
    return snapshot()
  }

  fun awaitManifestBoundary(timeout: Long, unit: TimeUnit): V9ManifestPairingBoundary? {
    manifestComplete.await(timeout, unit)
    return completedManifestBoundary
  }

  fun manifestBoundary(): V9ManifestPairingBoundary? = completedManifestBoundary

  fun awaitPreparationBoundary(
      timeout: Long,
      unit: TimeUnit,
  ): V9PreparationBoundary? {
    preparationComplete.await(timeout, unit)
    return completedPreparationBoundary
  }

  fun preparationBoundary(): V9PreparationBoundary? = completedPreparationBoundary

  fun awaitActiveBoundary(timeout: Long, unit: TimeUnit): V9ActiveBoundary? {
    activeComplete.await(timeout, unit)
    return completedActiveBoundary
  }

  fun activeBoundary(): V9ActiveBoundary? = completedActiveBoundary

  fun consentItems(): List<V9ConsentItem> = part3Session?.items().orEmpty()

  fun submitConsent(proposalId: Long, decision: V9ConsentDecision) {
    val session =
        part3Session ?: throw IllegalStateException("v9 Part-3 boundary is not ready")
    session.submitConsent(proposalId, decision)
  }

  fun part3Progress(): V9Part3Progress? = part3Session?.progress()

  fun addPart3ProgressListener(listener: V9Part3ProgressListener): Closeable {
    val registration = Part3ListenerRegistration(listener)
    val session =
        synchronized(part3ListenerLock) {
          if (closed.get()) {
            null
          } else {
            part3Registrations += registration
            part3Session
          }
        }
    if (closed.get()) {
      registration.close()
    } else if (session != null) {
      registration.promote(session)
    }
    return registration
  }

  fun negotiatedCapabilities(): Set<V9Capability> =
      negotiated?.capabilities?.toSet() ?: emptySet()

  fun writerQueueSnapshot(): V9QueueSnapshot = writer.snapshot()

  @Synchronized
  fun postAuthBoundary(): V9PostAuthBoundary? = authenticatedBoundary

  @Synchronized
  fun authenticatedSlot(): Int? = authenticatedBoundary?.slot

  internal fun isClosed(): Boolean = closed.get()

  internal fun activeTaskCount(): Int = synchronized(taskLock) { tasks.count(Thread::isAlive) }

  internal fun part3HeaderAdmissionForTest(
      type: V9MessageType,
      flags: Int,
      channel: Long,
      encodedLength: Long,
      decodedLength: Long,
  ): V9ErrorCode? {
    return admitDynamicHeader(type, flags, channel, encodedLength, decodedLength)
  }

  internal fun configuredDecoderMessagesForTest(): Set<V9MessageType> =
      decoderPolicy.allowedMessages

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

  fun sendCheckpoint(kind: V9CheckpointKind, owner: Int, frame: Long) {
    val session = playSession ?: throw V9ProtocolException(V9ErrorCode.UNEXPECTED_MESSAGE, 0)
    session.sendCheckpoint(kind, owner, frame)
  }

  fun sendInput(value: V9InputState) {
    val session = playSession ?: throw V9ProtocolException(V9ErrorCode.UNEXPECTED_MESSAGE, 0)
    session.sendInput(value)
  }

  fun sendControl(value: V9RuntimeControl) {
    val session = playSession ?: throw V9ProtocolException(V9ErrorCode.UNEXPECTED_MESSAGE, 0)
    session.sendControl(value)
  }

  /** The foundation refuses traffic that its explicitly supplied phase plans do not own. */
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
      readLoop@ while (!closed.get()) {
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
        var offset = 0
        while (offset < count && !closed.get()) {
          val before = decoder.snapshot().consumedBytes
          val result = decoder.feedOne(bytes, offset, count - offset)
          val consumed = Math.subtractExact(result.consumedBytes, before)
          if (consumed <= 0 || consumed > count - offset) {
            reject(V9ErrorCode.INTERNAL_ERROR, V9Diagnostic.IO_FAILURE)
            continue@readLoop
          }
          offset = Math.addExact(offset, consumed.toInt())
          result.frames.forEach { frame ->
            val waitForDelivery =
                frame.header.type == V9MessageType.ROM_END ||
                    frame.header.type == V9MessageType.BATTERY_END
            frame.use {
              if (!closed.get() &&
                  snapshot().state != V9LifecycleState.TERMINAL_CLEANUP) {
                handleFrame(frame)
              }
            }
            if (waitForDelivery) part3Session?.awaitPendingDelivery()
          }
          val failure = result.failure
          if (failure != null) {
            reject(failure.reason, diagnosticFor(failure.reason))
            continue@readLoop
          }
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
          try {
            writeFully(value.bytes)
          } finally {
            writer.completed(value)
          }
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
        V9MessageType.AUTH -> handleAuth(frame)
        V9MessageType.AUTH_RESULT -> handleAuthResult(frame)
        V9MessageType.MANIFEST -> handleManifest(frame)
        V9MessageType.CONSENT ->
          part3Session?.handleConsent(frame.payloadView())
              ?: reject(V9ErrorCode.UNEXPECTED_MESSAGE, V9Diagnostic.CONSENT_REJECTED)
        V9MessageType.ROM_BEGIN,
        V9MessageType.ROM_CHUNK,
        V9MessageType.ROM_END,
        V9MessageType.BATTERY_BEGIN,
        V9MessageType.BATTERY_CHUNK,
        V9MessageType.BATTERY_END ->
          part3Session?.handleBulk(
              type,
              frame.header.flags,
              frame.header.channel,
              frame.payloadView(),
          ) ?: reject(V9ErrorCode.UNEXPECTED_MESSAGE, V9Diagnostic.TRANSFER_REJECTED)
        V9MessageType.CHECKPOINT,
        V9MessageType.START,
        V9MessageType.READY,
        V9MessageType.INPUT,
        V9MessageType.RESET,
        V9MessageType.STOP ->
          playSession?.handle(frame)
              ?: reject(V9ErrorCode.UNEXPECTED_MESSAGE, V9Diagnostic.TRANSFER_REJECTED)
        V9MessageType.CANCEL -> fail(V9ErrorCode.CANCELLED, V9Diagnostic.CANCELLED)
        V9MessageType.GOODBYE -> {
          lifecycle.closeNormally()
          closeResources()
        }
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
          remoteHello = remote
          lifecycle.serverHelloReceived()
          enqueueHello {
            lifecycle.clientHelloSent(result)
            if (clientInvitation != null) enqueueClientAuth()
          }
        }
        V9Role.SERVER -> {
          if (snapshot().state != V9LifecycleState.WAIT_CLIENT_HELLO) {
            return fail(V9ErrorCode.UNEXPECTED_MESSAGE, V9Diagnostic.HELLO_REJECTED)
          }
          val result = V9HelloCodec.negotiate(localHello, remote, V9Role.CLIENT, mode)
          negotiated = result
          remoteHello = remote
          lifecycle.clientHelloReceived(result)
        }
      }
    } catch (e: V9ProtocolException) {
      reject(e.reason, V9Diagnostic.CAPABILITY_MISMATCH)
    }
  }

  private fun enqueueClientAuth() {
    val invitation = clientInvitation ?: return
    val server = remoteHello?.nonce()
        ?: return fail(V9ErrorCode.INTERNAL_ERROR, V9Diagnostic.IO_FAILURE)
    val client = localHello.nonce()
    val auth =
        try {
          invitation.createAuth(server, client)
        } catch (_: RuntimeException) {
          fail(V9ErrorCode.AUTH_FAILED, V9Diagnostic.AUTH_REJECTED)
          return
        }
    val sequence = nextSequenceOrFail() ?: return
    val encoded =
        try {
          V9FrameEncoder.encode(
              V9OutboundFrame(
                  V9MessageType.AUTH,
                  0,
                  sequence,
                  0,
                  ProtocolV9.CONTROL_CHANNEL,
                  V9AuthCodec.encode(auth),
              ),
              authPolicy(),
          )
        } catch (e: V9ProtocolException) {
          fail(e.reason, V9Diagnostic.AUTH_REJECTED)
          return
        }
    responseLedger.recordPeerRequest(sequence, V9MessageType.AUTH)
    if (!writer.offer(encoded) {
          advanceOutgoingSequence()
          invitation.close()
          lifecycle.clientAuthSent()
        }) {
      invitation.close()
      fail(V9ErrorCode.QUEUE_OVERFLOW, V9Diagnostic.QUEUE_FULL)
    }
  }

  private fun handleAuth(frame: V9Frame) {
    if (role != V9Role.SERVER ||
        snapshot().state != V9LifecycleState.WAIT_AUTH ||
        invitationHost == null) {
      reject(V9ErrorCode.UNEXPECTED_MESSAGE, V9Diagnostic.AUTH_REJECTED)
      return
    }
    lifecycle.clientAuthReceived()
    val remote = remoteHello?.nonce()
    val local = localHello.nonce()
    val auth =
        try {
          V9AuthCodec.decode(frame.payloadView(), mode)
        } catch (_: V9ProtocolException) {
          null
        }
    val outcome =
        if (auth == null || remote == null) {
          invitationHost.rejectMalformedAdmission()
        } else {
          invitationHost.authenticate(auth, local, remote)
        }
    when (outcome) {
      is V9Authentication.Accepted -> {
        slotReservation = outcome.reservation
        enqueueAcceptedAuthResult(frame.header.sequence, outcome.reservation.slot)
      }
      V9Authentication.Failed ->
        sendRejectedAuthResult(frame.header.sequence)
      V9Authentication.SlotFull ->
        sendCorrelatedSlotFull(frame.header.sequence)
    }
  }

  private fun enqueueAcceptedAuthResult(correlation: Long, slot: Int) {
    val sequence = nextSequenceOrFail() ?: return
    val encoded =
        try {
          V9FrameEncoder.encode(
              V9OutboundFrame(
                  V9MessageType.AUTH_RESULT,
                  V9Flag.RESPONSE.wireMask,
                  sequence,
                  correlation,
                  ProtocolV9.CONTROL_CHANNEL,
                  V9AuthCodec.encode(V9AuthResult(V9AuthStatus.ACCEPTED)),
              ),
              authPolicy(),
          )
        } catch (e: V9ProtocolException) {
          fail(e.reason, V9Diagnostic.AUTH_REJECTED)
          return
        }
    if (!writer.offer(encoded) {
          advanceOutgoingSequence()
          lifecycle.serverAuthResultSent()
          authenticatedBoundary =
              V9PostAuthBoundary(role, mode, slot, V9LifecycleState.SEND_SERVER_MANIFEST)
          postAuth.countDown()
          if (manifestPlan != null) enqueueServerManifest(slot)
        }) {
      fail(V9ErrorCode.QUEUE_OVERFLOW, V9Diagnostic.QUEUE_FULL)
    }
  }

  private fun handleAuthResult(frame: V9Frame) {
    if (role != V9Role.CLIENT ||
        snapshot().state != V9LifecycleState.WAIT_AUTH_RESULT ||
        clientInvitation == null) {
      reject(V9ErrorCode.UNEXPECTED_MESSAGE, V9Diagnostic.AUTH_REJECTED)
      return
    }
    val result =
        try {
          V9AuthCodec.decodeResult(frame.payloadView(), frame.header.flags)
        } catch (_: V9ProtocolException) {
          reject(V9ErrorCode.AUTH_FAILED, V9Diagnostic.AUTH_REJECTED)
          return
        }
    if (result.status == V9AuthStatus.REJECTED) {
      fail(V9ErrorCode.AUTH_FAILED, V9Diagnostic.AUTH_REJECTED)
      return
    }
    lifecycle.serverAuthResultReceived()
    authenticatedBoundary =
        V9PostAuthBoundary(
            role,
            mode,
            clientInvitation.slot,
            V9LifecycleState.WAIT_SERVER_MANIFEST,
        )
    postAuth.countDown()
  }

  private fun enqueueServerManifest(slot: Int) {
    if (role != V9Role.SERVER ||
        snapshot().state != V9LifecycleState.SEND_SERVER_MANIFEST) {
      reject(V9ErrorCode.UNEXPECTED_MESSAGE, V9Diagnostic.MANIFEST_REJECTED)
      return
    }
    val manifest = manifestPlan?.manifestFor(role, slot)
        ?: return reject(V9ErrorCode.MANIFEST_MISMATCH, V9Diagnostic.MANIFEST_REJECTED)
    val context = manifestContext(slot, 0, slot, manifest)
    val payload =
        try {
          V9ManifestCodec.encode(manifest, context)
        } catch (e: V9ProtocolException) {
          reject(e.reason, V9Diagnostic.MANIFEST_REJECTED)
          return
        }
    localManifest = manifest
    localManifestPayload = payload.copyOf()
    enqueueManifest(payload) {
      lifecycle.serverManifestSent()
    }
  }

  private fun handleManifest(frame: V9Frame) {
    val boundary = authenticatedBoundary
    if (manifestPlan == null || boundary == null) {
      reject(V9ErrorCode.UNEXPECTED_MESSAGE, V9Diagnostic.MANIFEST_REJECTED)
      return
    }
    val state = snapshot().state
    val expected =
        role == V9Role.CLIENT && state == V9LifecycleState.WAIT_SERVER_MANIFEST ||
            role == V9Role.SERVER && state == V9LifecycleState.WAIT_CLIENT_MANIFEST
    if (!expected) {
      reject(V9ErrorCode.UNEXPECTED_MESSAGE, V9Diagnostic.MANIFEST_REJECTED)
      return
    }
    val wireSource = if (role == V9Role.CLIENT) 0 else boundary.slot
    val wireTarget = if (role == V9Role.CLIENT) boundary.slot else 0
    val prepared =
        manifestPlan.manifestFor(role, boundary.slot)
            ?: return reject(
                V9ErrorCode.MANIFEST_MISMATCH,
                V9Diagnostic.MANIFEST_REJECTED,
            )
    val context = manifestContext(boundary.slot, wireSource, wireTarget, prepared)
    val decoded =
        try {
          V9ManifestCodec.decode(frame.payloadView(), context)
        } catch (e: V9ProtocolException) {
          reject(e.reason, V9Diagnostic.MANIFEST_REJECTED)
          return
        }
    if (role == V9Role.CLIENT) {
      enqueueClientManifest(boundary.slot, decoded, frame.payloadView())
    } else {
      completeServerManifestPair(boundary.slot, decoded, frame.payloadView())
    }
  }

  private fun enqueueClientManifest(
      slot: Int,
      serverManifest: V9Manifest,
      serverPayload: ByteArray,
  ) {
    val manifest = manifestPlan?.manifestFor(role, slot)
        ?: return reject(V9ErrorCode.MANIFEST_MISMATCH, V9Diagnostic.MANIFEST_REJECTED)
    val context = manifestContext(slot, slot, 0, manifest)
    val payload =
        try {
          V9ManifestCodec.encode(manifest, context)
        } catch (e: V9ProtocolException) {
          reject(e.reason, V9Diagnostic.MANIFEST_REJECTED)
          return
        }
    val compatible =
        compatibleBoundary(slot, serverManifest, serverPayload, manifest, payload)
            ?: return
    lifecycle.serverManifestReceived()
    enqueueManifest(payload) {
      lifecycle.clientManifestSent(compatible.proposals.isNotEmpty())
      publishManifestBoundary(compatible)
    }
  }

  private fun completeServerManifestPair(
      slot: Int,
      clientManifest: V9Manifest,
      clientPayload: ByteArray,
  ) {
    val serverManifest = localManifest
        ?: return reject(V9ErrorCode.MANIFEST_MISMATCH, V9Diagnostic.MANIFEST_REJECTED)
    val serverPayload = localManifestPayload
        ?: return reject(V9ErrorCode.MANIFEST_MISMATCH, V9Diagnostic.MANIFEST_REJECTED)
    val compatible =
        compatibleBoundary(slot, serverManifest, serverPayload, clientManifest, clientPayload)
            ?: return
    lifecycle.clientManifestReceived(compatible.proposals.isNotEmpty())
    publishManifestBoundary(compatible)
    localManifestPayload?.fill(0)
    localManifestPayload = null
    localManifest = null
  }

  private fun compatibleBoundary(
      slot: Int,
      serverManifest: V9Manifest,
      serverPayload: ByteArray,
      clientManifest: V9Manifest,
      clientPayload: ByteArray,
  ): V9ManifestPairingBoundary? {
    val comparison =
        V9ManifestCompatibility.compare(
            serverManifest,
            clientManifest,
            slot,
            protocolCapabilityCompatible =
                negotiated?.capabilities?.containsAll(V9Capability.requiredCapabilities) == true,
        )
    if (comparison is V9ManifestComparisonResult.Rejected) {
      reject(comparison.reason, V9Diagnostic.MANIFEST_REJECTED)
      return null
    }
    comparison as V9ManifestComparisonResult.Compatible
    val nextState =
        if (comparison.proposals.isEmpty()) {
          V9LifecycleState.SYNCHRONIZING
        } else {
          V9LifecycleState.EXCHANGE_CONSENT
        }
    return V9ManifestPairingBoundary(
        role,
        mode,
        slot,
        nextState,
        serverManifest,
        clientManifest,
        V9ManifestDigest.sha256(serverPayload),
        V9ManifestDigest.sha256(clientPayload),
        comparison.differences,
        comparison.proposals,
    )
  }

  private fun publishManifestBoundary(value: V9ManifestPairingBoundary) {
    if (part3Plan != null) {
      val guestPlan =
          part3Plan.forGuest(role, value.authenticatedGuest)
              ?: return reject(
                  V9ErrorCode.CONSENT_REJECTED,
                  V9Diagnostic.CONSENT_REJECTED,
              )
      lateinit var session: V9Part3Session
      session =
          V9Part3Session(
              role,
              mode,
              value.authenticatedGuest,
              guestPlan,
              clock,
              scheduler::schedule,
              ::startTask,
              ::enqueuePart3,
              lifecycle::consentComplete,
              ::reject,
              onPreparationComplete = { prepared ->
                completedPreparationBoundary = prepared
                preparationComplete.countDown()
                if (playPlan != null) activatePlaySession(value, session)
              },
              checkpointTransportEnabled = playPlan != null,
          )
      val registrations =
          synchronized(part3ListenerLock) {
            if (closed.get()) {
              null
            } else {
              part3Session = session
              part3Registrations.toList()
            }
          }
      if (registrations == null) {
        session.close()
        return
      }
      session.start(value)
      registrations.forEach { it.promote(session) }
    }
    completedManifestBoundary = value
    manifestComplete.countDown()
  }

  private fun activatePlaySession(
      boundary: V9ManifestPairingBoundary,
      consentSession: V9Part3Session,
  ) {
    val plan = playPlan?.forGuest(role, boundary.authenticatedGuest)
        ?: return reject(V9ErrorCode.CONSENT_REJECTED, V9Diagnostic.CONSENT_REJECTED)
    val authorization = consentSession.checkpointAuthorization()
        ?: return reject(V9ErrorCode.CONSENT_REJECTED, V9Diagnostic.CONSENT_REJECTED)
    val session =
        V9PlaySession(
            role,
            mode,
            boundary.authenticatedGuest,
            authorization,
            plan,
            ::startTask,
            V9PlaySend(::enqueuePlay),
            lifecycle,
            ::reject,
        ) { active ->
          completedActiveBoundary = active
          activeComplete.countDown()
        }
    synchronized(wireStateLock) {
      if (closed.get() || playSession != null) {
        session.close()
        return
      }
      playSession = session
    }
    session.start()
  }

  private fun manifestContext(
      slot: Int,
      source: Int,
      target: Int,
      prepared: V9Manifest,
  ): V9ManifestValidationContext =
      V9ManifestValidationContext(
          mode,
          slot,
          source,
          target,
          prepared.rosterGeneration,
          prepared.rosterCommitment(),
          negotiated?.capabilities ?: emptySet(),
      )

  private fun enqueueManifest(payload: ByteArray, onWritten: () -> Unit) {
    val sequence = nextSequenceOrFail() ?: return
    val encoded =
        try {
          V9FrameEncoder.encode(
              V9OutboundFrame(
                  V9MessageType.MANIFEST,
                  0,
                  sequence,
                  0,
                  ProtocolV9.CONTROL_CHANNEL,
                  payload,
              ),
              manifestPolicy(),
          )
        } catch (e: V9ProtocolException) {
          reject(e.reason, V9Diagnostic.MANIFEST_REJECTED)
          return
        }
    if (!writer.offer(encoded) {
          advanceOutgoingSequence()
          onWritten()
        }) {
      reject(V9ErrorCode.QUEUE_OVERFLOW, V9Diagnostic.QUEUE_FULL)
    }
  }

  private fun enqueuePart3(
      type: V9MessageType,
      flags: Int,
      channel: Long,
      payload: ByteArray,
      onWritten: () -> Unit,
  ): Boolean =
      synchronized(wireStateLock) {
        if (closed.get() || snapshot().state == V9LifecycleState.TERMINAL_CLEANUP) {
          return@synchronized false
        }
        val sequence = nextSequenceOrFail() ?: return@synchronized false
        val encoded =
            try {
              V9FrameEncoder.encode(
                  V9OutboundFrame(type, flags, sequence, 0, channel, payload),
                  part3Policy(),
              )
            } catch (e: V9ProtocolException) {
              reject(e.reason, diagnosticFor(e.reason))
              return@synchronized false
            }
        advanceOutgoingSequence()
        try {
          writer.offer(encoded, onWritten)
        } finally {
          encoded.fill(0)
        }
      }

  private fun enqueuePlay(
      type: V9MessageType,
      flags: Int,
      correlation: Long,
      channel: Long,
      payload: ByteArray,
      onAdmitted: (Long) -> Closeable,
      onWritten: () -> Unit,
  ): Long? =
      synchronized(wireStateLock) {
        if (closed.get() || snapshot().state == V9LifecycleState.TERMINAL_CLEANUP) {
          return@synchronized null
        }
        val sequence = nextSequenceOrFail() ?: return@synchronized null
        val encoded =
            try {
              V9FrameEncoder.encode(
                  V9OutboundFrame(type, flags, sequence, correlation, channel, payload),
                  playPolicy(),
              )
            } catch (e: V9ProtocolException) {
              reject(e.reason, diagnosticFor(e.reason))
              return@synchronized null
            }
        var requestRecorded = false
        var admissionRollback: Closeable? = null
        if (type == V9MessageType.START) {
          try {
            responseLedger.recordPeerRequest(sequence, V9MessageType.START)
            requestRecorded = true
          } catch (_: RuntimeException) {
            encoded.fill(0)
            reject(V9ErrorCode.CORRELATION_ERROR, V9Diagnostic.TRANSFER_REJECTED)
            return@synchronized null
          }
        }
        val offered = try {
          admissionRollback = onAdmitted(sequence)
          advanceOutgoingSequence()
          writer.offer(encoded, onWritten)
        } catch (_: RuntimeException) {
          false
        } finally {
          encoded.fill(0)
        }
        if (offered) sequence else {
          admissionRollback?.close()
          if (requestRecorded) {
            responseLedger.cancelPeerRequest(sequence, V9MessageType.START)
          }
          reject(V9ErrorCode.QUEUE_OVERFLOW, V9Diagnostic.QUEUE_FULL)
          null
        }
      }

  private fun admitDynamicHeader(
      type: V9MessageType,
      flags: Int,
      channel: Long,
      encodedLength: Long,
      decodedLength: Long,
  ): V9ErrorCode? {
    if (type !in PART3_MESSAGES && type !in PLAY_MESSAGES) return null
    return synchronized(wireStateLock) {
      if (type in PART3_MESSAGES) {
        val session = part3Session ?: return@synchronized V9ErrorCode.UNEXPECTED_MESSAGE
        session.headerAdmission(type, flags, channel, encodedLength, decodedLength)
      } else {
        val session = playSession ?: return@synchronized V9ErrorCode.UNEXPECTED_MESSAGE
        session.headerAdmission(type, flags, channel, encodedLength, decodedLength)
      }
    }
  }

  private fun reject(reason: V9ErrorCode, diagnostic: V9Diagnostic) {
    if (closed.get() || snapshot().state == V9LifecycleState.TERMINAL_CLEANUP) return
    lifecycle.beginTerminalCleanup(reason, diagnostic)
    writer.close()
    part3Session?.close()
    playSession?.close()
    localManifestPayload?.fill(0)
    localManifestPayload = null
    localManifest = null
    writerTask?.takeUnless { it === Thread.currentThread() }?.interrupt()
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

  private fun sendRejectedAuthResult(correlation: Long) {
    sendTerminalResponse(
        V9ErrorCode.AUTH_FAILED,
        V9Diagnostic.AUTH_REJECTED,
        V9MessageType.AUTH_RESULT,
        V9Flag.RESPONSE.wireMask or V9Flag.TERMINAL.wireMask,
        correlation,
        V9AuthCodec.encode(V9AuthResult(V9AuthStatus.REJECTED)),
    )
  }

  private fun sendCorrelatedSlotFull(correlation: Long) {
    sendTerminalResponse(
        V9ErrorCode.SERVER_FULL,
        V9Diagnostic.AUTH_REJECTED,
        V9MessageType.ERROR,
        V9Flag.RESPONSE.wireMask or V9Flag.TERMINAL.wireMask,
        correlation,
        V9ErrorPayloadCodec.encode(
            V9ErrorCode.SERVER_FULL,
            V9MessageType.AUTH.wireId,
            correlation,
        ),
    )
  }

  private fun sendTerminalResponse(
      reason: V9ErrorCode,
      diagnostic: V9Diagnostic,
      type: V9MessageType,
      flags: Int,
      correlation: Long,
      payload: ByteArray,
  ) {
    if (closed.get()) return
    lifecycle.beginTerminalCleanup(reason, diagnostic)
    writer.close()
    writerTask?.takeUnless { it === Thread.currentThread() }?.interrupt()
    try {
      synchronized(wireStateLock) {
        val sequence = nextSequenceOrFail() ?: return
        val bytes =
            V9FrameEncoder.encode(
                V9OutboundFrame(
                    type,
                    flags,
                    sequence,
                    correlation,
                    ProtocolV9.CONTROL_CHANNEL,
                    payload,
                ),
                authPolicy(),
            )
        writeFully(bytes)
        advanceOutgoingSequence()
        channel.shutdownOutput()
      }
    } catch (_: IOException) {
      lifecycle.completeTerminalCleanup()
      closeResources()
    } catch (_: V9ProtocolException) {
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

  private fun nextSequenceOrFail(): Long? {
    if (nextOutgoingSequence > ProtocolV9.LAST_SEQUENCE) {
      fail(V9ErrorCode.SEQUENCE_ERROR, V9Diagnostic.AUTH_REJECTED)
      return null
    }
    return nextOutgoingSequence
  }

  private fun authPolicy(): V9DecoderPolicy =
      V9DecoderPolicy(
          allowedMessages =
              setOf(
                  V9MessageType.HELLO,
                  V9MessageType.AUTH,
                  V9MessageType.AUTH_RESULT,
                  V9MessageType.ERROR,
              ),
          negotiatedCapabilities = negotiated?.capabilities ?: V9Capability.entries.toSet(),
          linkMode = mode,
      )

  private fun manifestPolicy(): V9DecoderPolicy =
      V9DecoderPolicy(
          allowedMessages = setOf(V9MessageType.MANIFEST, V9MessageType.ERROR),
          negotiatedCapabilities = negotiated?.capabilities ?: emptySet(),
          linkMode = mode,
      )

  private fun part3Policy(): V9DecoderPolicy =
      V9DecoderPolicy(
          allowedMessages = PART3_MESSAGES + V9MessageType.ERROR,
          negotiatedCapabilities = negotiated?.capabilities ?: emptySet(),
          linkMode = mode,
      )

  private fun playPolicy(): V9DecoderPolicy =
      V9DecoderPolicy(
          allowedMessages = PLAY_MESSAGES + V9MessageType.ERROR,
          negotiatedCapabilities = negotiated?.capabilities ?: emptySet(),
          linkMode = mode,
      )

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
    slotReservation?.close()
    slotReservation = null
    localManifestPayload?.fill(0)
    localManifestPayload = null
    localManifest = null
    val (session, registrations) =
        synchronized(part3ListenerLock) {
          val currentSession = part3Session
          part3Session = null
          val currentRegistrations = part3Registrations.toList()
          part3Registrations.clear()
          currentSession to currentRegistrations
        }
    registrations.forEach(Closeable::close)
    session?.close()
    playSession?.close()
    playSession = null
    clientInvitation?.close()
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
    postAuth.countDown()
    manifestComplete.countDown()
    preparationComplete.countDown()
    activeComplete.countDown()
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
    V9ErrorCode.AUTH_FAILED,
    V9ErrorCode.SERVER_FULL -> V9Diagnostic.AUTH_REJECTED
    V9ErrorCode.MANIFEST_MISMATCH -> V9Diagnostic.MANIFEST_REJECTED
    V9ErrorCode.CONSENT_REJECTED -> V9Diagnostic.CONSENT_REJECTED
    V9ErrorCode.ROM_MISMATCH -> V9Diagnostic.TRANSFER_REJECTED
    else -> V9Diagnostic.HELLO_REJECTED
  }

  private inner class Part3ListenerRegistration(
      listener: V9Part3ProgressListener,
  ) : Closeable {
    private val active = AtomicBoolean(true)
    private val safeListener =
        V9Part3ProgressListener { progress ->
          if (active.get()) {
            try {
              listener.onProgress(progress)
            } catch (_: RuntimeException) {
              // Presentation observers never affect protocol or private-content ownership.
            }
          }
        }
    private var subscription: Closeable? = null

    fun promote(session: V9Part3Session) {
      if (!active.get()) return
      val installed = session.addProgressListener(safeListener)
      val discard =
          synchronized(part3ListenerLock) {
            if (!active.get() || closed.get() || part3Session !== session || subscription != null) {
              true
            } else {
              subscription = installed
              false
            }
          }
      if (discard) installed.close()
    }

    override fun close() {
      if (!active.compareAndSet(true, false)) return
      val installed =
          synchronized(part3ListenerLock) {
            part3Registrations.remove(this)
            subscription.also { subscription = null }
          }
      installed?.close()
    }
  }

  companion object {
    private val PART3_MESSAGES =
        setOf(
            V9MessageType.CONSENT,
            V9MessageType.ROM_BEGIN,
            V9MessageType.ROM_CHUNK,
            V9MessageType.ROM_END,
            V9MessageType.BATTERY_BEGIN,
            V9MessageType.BATTERY_CHUNK,
            V9MessageType.BATTERY_END,
        )

    private val PLAY_MESSAGES =
        setOf(
            V9MessageType.CHECKPOINT,
            V9MessageType.START,
            V9MessageType.READY,
            V9MessageType.INPUT,
            V9MessageType.RESET,
            V9MessageType.STOP,
        )

    private fun randomNonce(): ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)
  }
}

/**
 * Opt-in listener used only by foundation/Part-1/Part-2 diagnostics and tests until later pairing
 * phases.
 * It is intentionally not reachable from [eu.rekawek.coffeegb.controller.network.ConnectionController].
 */
class V9FoundationServer(
    private val port: Int = 0,
    private val mode: V9LinkMode = V9LinkMode.NORMAL,
    private val optionalCapabilities: Set<V9Capability> = emptySet(),
    private val invitationHost: V9InvitationHost? = null,
    private val manifestPlan: V9ManifestPlan? = null,
    private val part3Plan: V9Part3Plan? = null,
    private val playPlan: V9PlayPlan? = null,
    private val onAwaitingPairing: (V9FoundationConnection) -> Unit,
) : Closeable {
  init {
    require(manifestPlan == null ||
        manifestPlan.role == V9Role.SERVER && manifestPlan.mode == mode)
    require(manifestPlan == null || invitationHost != null) {
      "v9 manifest exchange requires invitation authentication"
    }
    require(part3Plan == null ||
        manifestPlan != null &&
            part3Plan.role == V9Role.SERVER &&
            part3Plan.mode == mode) {
      "v9 Part-3 requires matching server manifest ownership"
    }
    require(playPlan == null ||
        part3Plan != null && playPlan.role == V9Role.SERVER && playPlan.mode == mode) {
      "v9 playable transport requires matching server Part-3 ownership"
    }
  }

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
            invitationHost = invitationHost,
            manifestPlan = manifestPlan,
            part3Plan = part3Plan,
            playPlan = playPlan,
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
    pending.toTypedArray().forEach(V9PendingCandidate::close)
    connections.toTypedArray().forEach(V9FoundationConnection::close)
    invitationHost?.close()
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
      socket.soTimeout = 0
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
      invitation: V9ClientInvitation? = null,
      manifestPlan: V9ManifestPlan? = null,
      part3Plan: V9Part3Plan? = null,
      playPlan: V9PlayPlan? = null,
  ): V9FoundationConnection {
    require(invitation == null || invitation.mode == mode)
    require(manifestPlan == null || manifestPlan.role == V9Role.CLIENT && manifestPlan.mode == mode)
    require(part3Plan == null ||
        manifestPlan != null &&
            part3Plan.role == V9Role.CLIENT &&
            part3Plan.mode == mode)
    require(playPlan == null ||
        part3Plan != null && playPlan.role == V9Role.CLIENT && playPlan.mode == mode)
    return connectAccepted(
        address,
        mode,
        optionalCapabilities,
        connectTimeoutMillis,
        invitation,
        manifestPlan,
        part3Plan,
        playPlan,
        ::newV9SocketChannel,
    )
  }

  internal fun connect(
      address: InetSocketAddress,
      mode: V9LinkMode,
      optionalCapabilities: Set<V9Capability>,
      connectTimeoutMillis: Int,
      invitation: V9ClientInvitation?,
      manifestPlan: V9ManifestPlan? = null,
      part3Plan: V9Part3Plan? = null,
      playPlan: V9PlayPlan? = null,
      channelFactory: () -> V9ConnectableChannel,
  ): V9FoundationConnection {
    require(invitation == null || invitation.mode == mode)
    require(manifestPlan == null || manifestPlan.role == V9Role.CLIENT && manifestPlan.mode == mode)
    require(part3Plan == null ||
        manifestPlan != null &&
            part3Plan.role == V9Role.CLIENT &&
            part3Plan.mode == mode)
    require(playPlan == null ||
        part3Plan != null && playPlan.role == V9Role.CLIENT && playPlan.mode == mode)
    return connectAccepted(
        address,
        mode,
        optionalCapabilities,
        connectTimeoutMillis,
        invitation,
        manifestPlan,
        part3Plan,
        playPlan,
        channelFactory,
    )
  }

  private fun connectAccepted(
      address: InetSocketAddress,
      mode: V9LinkMode,
      optionalCapabilities: Set<V9Capability>,
      connectTimeoutMillis: Int,
      invitation: V9ClientInvitation?,
      manifestPlan: V9ManifestPlan?,
      part3Plan: V9Part3Plan?,
      playPlan: V9PlayPlan?,
      channelFactory: () -> V9ConnectableChannel,
  ): V9FoundationConnection {
    var channel: V9ConnectableChannel? = null
    var connection: V9FoundationConnection? = null
    try {
      val acceptedChannel = channelFactory()
      channel = acceptedChannel
      acceptedChannel.connect(address, connectTimeoutMillis)
      val acceptedConnection = V9FoundationConnection(
          acceptedChannel,
          V9Role.CLIENT,
          mode,
          optionalCapabilities = optionalCapabilities,
          clientInvitation = invitation,
          manifestPlan = manifestPlan,
          part3Plan = part3Plan,
          playPlan = playPlan,
      )
      connection = acceptedConnection
      acceptedConnection.start()
      return acceptedConnection
    } catch (e: IOException) {
      invitation?.close()
      if (connection != null) connection.close() else closeChannel(channel)
      throw e
    } catch (e: RuntimeException) {
      invitation?.close()
      if (connection != null) connection.close() else closeChannel(channel)
      throw e
    }
  }

  private fun closeChannel(channel: V9TransportChannel?) {
    try {
      channel?.close()
    } catch (_: IOException) {
      // Keep the original setup/connect/construction failure.
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
        { newV9SocketChannel() },
) : Closeable {
  private val lock = Any()
  private val scheduler = scheduler ?: V9SystemDeadlineScheduler(clock)
  private val ownedScheduler = if (scheduler == null) this.scheduler as Closeable else null
  private var state = V9ConnectAttemptState.NEW
  private var pendingChannel: V9ConnectableChannel? = null
  private var pendingConnection: V9FoundationConnection? = null
  private var pendingInvitation: V9ClientInvitation? = null
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
      invitation: V9ClientInvitation? = null,
      manifestPlan: V9ManifestPlan? = null,
      part3Plan: V9Part3Plan? = null,
      playPlan: V9PlayPlan? = null,
      onComplete: (V9FoundationConnection?, V9ErrorCode?) -> Unit,
  ) {
    require(invitation == null || invitation.mode == mode)
    require(manifestPlan == null || manifestPlan.role == V9Role.CLIENT && manifestPlan.mode == mode)
    require(part3Plan == null ||
        manifestPlan != null &&
            part3Plan.role == V9Role.CLIENT &&
            part3Plan.mode == mode)
    require(playPlan == null ||
        part3Plan != null && playPlan.role == V9Role.CLIENT && playPlan.mode == mode)
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
        pendingInvitation = invitation
      }
    }
    if (cancelledBeforeStart) {
      invitation?.close()
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
        try {
          scheduler.schedule(deadline) {
            if (clock.nowMillis() >= deadline) completeFailure(V9ErrorCode.TIMEOUT)
          }
        } catch (_: RuntimeException) {
          completeFailure(V9ErrorCode.INTERNAL_ERROR)
          return
        }
    synchronized(lock) {
      if (state == V9ConnectAttemptState.CONNECTING) timeoutTask = scheduled
      else scheduled.close()
    }

    val worker =
        thread(start = false, isDaemon = true, name = "netplay-v9-connect") {
          runAttempt(
              address,
              mode,
              optionalCapabilities,
              invitation,
              manifestPlan,
              part3Plan,
              playPlan,
          )
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
      invitation: V9ClientInvitation?,
      manifestPlan: V9ManifestPlan?,
      part3Plan: V9Part3Plan? = null,
      playPlan: V9PlayPlan? = null,
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
              clientInvitation = invitation,
              manifestPlan = manifestPlan,
              part3Plan = part3Plan,
              playPlan = playPlan,
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
          pendingInvitation = null
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
    val invitation: V9ClientInvitation?
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
      invitation = pendingInvitation
      pendingInvitation = null
      worker = task
      deadline = timeoutTask
      timeoutTask = null
      completion = callback.takeUnless { callbackDelivered }
      if (completion != null) callbackDelivered = true
    }
    deadline?.close()
    closeQuietly(channel)
    invitation?.close()
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
