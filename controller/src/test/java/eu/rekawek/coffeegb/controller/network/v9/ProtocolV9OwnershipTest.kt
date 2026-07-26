package eu.rekawek.coffeegb.controller.network.v9

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ProtocolV9OwnershipTest {

  @Test
  fun failedInFlightWriteWipesTheExactOwnedBufferAndDoesNotRunCompletion() {
    val channel = ScriptedChannel(failWrites = true)
    val connection = V9FoundationConnection(channel, V9Role.SERVER)

    connection.start()
    awaitCondition { connection.isClosed() }
    awaitCondition { connection.activeTaskCount() == 0 }

    val inFlight = assertNotNull(channel.lastWriteBuffer.get())
    assertTrue(inFlight.all { it == 0.toByte() }, "polled writer bytes must be wiped")
    assertEquals(0, channel.readCalls.get(), "HELLO completion must not start the reader")
    assertEquals(V9LifecycleState.CLOSED, connection.snapshot().state)
    assertEquals(V9ErrorCode.INTERNAL_ERROR, connection.snapshot().failure?.reason)
  }

  @Test
  fun localTerminalErrorDrainsUntilPeerEofOrExactCleanupDeadline() {
    val clock = FakeClock()
    val scheduler = ManualScheduler(clock)
    val channel = ScriptedChannel()
    val connection =
        V9FoundationConnection(
            channel,
            V9Role.CLIENT,
            clock = clock,
            scheduler = scheduler,
        )
    connection.start()
    channel.enqueue(malformedServerHello())
    assertTrue(channel.outputShutdown.await(2, TimeUnit.SECONDS))
    assertEquals(V9LifecycleState.TERMINAL_CLEANUP, connection.snapshot().state)
    assertEquals(V9ErrorCode.CAPABILITY_MISMATCH, connection.snapshot().failure?.reason)
    assertEquals(V9QueueSnapshot(0, 0, 0), connection.writerQueueSnapshot())

    // Reader admission is stopped: even a complete valid HELLO cannot replace the rejection.
    channel.enqueue(serverHello())
    clock.now = 1_999
    scheduler.runDue()
    assertFalse(channel.closed.get())
    assertEquals(V9LifecycleState.TERMINAL_CLEANUP, connection.snapshot().state)
    clock.now = 2_000
    scheduler.runDue()
    assertTrue(channel.closed.get())
    awaitCondition { connection.activeTaskCount() == 0 }
    assertEquals(V9LifecycleState.CLOSED, connection.snapshot().state)
    assertEquals(V9ErrorCode.CAPABILITY_MISMATCH, connection.snapshot().failure?.reason)
    connection.cancel()
    connection.close()
    assertEquals(1, channel.closeCount.get())

    val eofClock = FakeClock()
    val eofScheduler = ManualScheduler(eofClock)
    val eofChannel = ScriptedChannel()
    val peerEof =
        V9FoundationConnection(
            eofChannel,
            V9Role.CLIENT,
            clock = eofClock,
            scheduler = eofScheduler,
        )
    peerEof.start()
    eofChannel.enqueue(malformedServerHello())
    assertTrue(eofChannel.outputShutdown.await(2, TimeUnit.SECONDS))
    eofChannel.enqueueEof()
    awaitCondition { peerEof.isClosed() }
    awaitCondition { peerEof.activeTaskCount() == 0 }
    assertEquals(V9ErrorCode.CAPABILITY_MISMATCH, peerEof.snapshot().failure?.reason)
    assertEquals(0, eofScheduler.activeTasks())
  }

  @Test
  fun blockedTerminalWriteStillClosesAtDeadlineWithoutReplacingOriginalFailure() {
    val clock = FakeClock()
    val scheduler = ManualScheduler(clock)
    val channel = ScriptedChannel(blockWrites = true)
    val connection =
        V9FoundationConnection(
            channel,
            V9Role.CLIENT,
            clock = clock,
            scheduler = scheduler,
        )
    connection.start()
    channel.enqueue(malformedServerHello())
    assertTrue(channel.writeEntered.await(2, TimeUnit.SECONDS))
    assertEquals(V9LifecycleState.TERMINAL_CLEANUP, connection.snapshot().state)
    clock.now = 1_999
    scheduler.runDue()
    assertFalse(connection.isClosed())
    clock.now = 2_000
    scheduler.runDue()
    awaitCondition { connection.isClosed() }
    awaitCondition { connection.activeTaskCount() == 0 }
    assertEquals(V9ErrorCode.CAPABILITY_MISMATCH, connection.snapshot().failure?.reason)
    assertEquals(1, channel.closeCount.get())

    val failedWrite = ScriptedChannel(failWrites = true)
    val isolated = V9FoundationConnection(failedWrite, V9Role.CLIENT)
    isolated.start()
    failedWrite.enqueue(malformedServerHello())
    awaitCondition { isolated.isClosed() }
    assertEquals(V9ErrorCode.CAPABILITY_MISMATCH, isolated.snapshot().failure?.reason)
    awaitCondition { failedWrite.closeCount.get() == 1 }
    assertEquals(1, failedWrite.closeCount.get())
  }

  @Test
  fun everyHelloStageUsesItsExactInjectedDeadlineAndStartAfterCloseStartsNoTask() {
    assertStageTimeout(
        V9LifecycleState.SEND_SERVER_HELLO,
        V9Role.SERVER,
        ScriptedChannel(blockWrites = true),
    )
    assertStageTimeout(
        V9LifecycleState.WAIT_SERVER_HELLO,
        V9Role.CLIENT,
        ScriptedChannel(),
    )
    assertStageTimeout(
        V9LifecycleState.SEND_CLIENT_HELLO,
        V9Role.CLIENT,
        ScriptedChannel(blockWrites = true).also { it.enqueue(serverHello()) },
    )
    assertStageTimeout(
        V9LifecycleState.WAIT_CLIENT_HELLO,
        V9Role.SERVER,
        ScriptedChannel(),
    )

    val closed = V9FoundationConnection(ScriptedChannel(), V9Role.CLIENT)
    closed.close()
    assertFailsWith<IllegalStateException> { closed.start() }
    assertEquals(0, closed.activeTaskCount())
    closed.cancel()
    assertEquals(V9LifecycleState.CLOSED, closed.snapshot().state)
    assertNull(closed.snapshot().failure)
  }

  @Test
  fun liveFoundationAppliesResponseLedgerAndOnlyAllowsFrozenPreHelloRejections() {
    val responseChannel = ScriptedChannel()
    val response =
        V9FoundationConnection(responseChannel, V9Role.CLIENT)
    response.start()
    responseChannel.enqueue(
        rawFrame(
            V9MessageType.ERROR,
            V9Flag.RESPONSE.wireMask or V9Flag.TERMINAL.wireMask,
            0,
            7,
            V9ErrorPayloadCodec.encode(V9ErrorCode.SERVER_BUSY),
        ),
    )
    assertTrue(responseChannel.outputShutdown.await(2, TimeUnit.SECONDS))
    assertEquals(V9ErrorCode.CORRELATION_ERROR, response.snapshot().failure?.reason)
    responseChannel.enqueueEof()
    awaitCondition { response.isClosed() }

    val rejectedChannel = ScriptedChannel()
    val rejected = V9FoundationConnection(rejectedChannel, V9Role.CLIENT)
    rejected.start()
    rejectedChannel.enqueue(
        rawFrame(
            V9MessageType.ERROR,
            V9Flag.TERMINAL.wireMask,
            0,
            0,
            V9ErrorPayloadCodec.encode(V9ErrorCode.SERVER_FULL),
        ),
    )
    awaitCondition { rejected.isClosed() }
    assertEquals(V9ErrorCode.SERVER_FULL, rejected.snapshot().failure?.reason)
    assertEquals(0, rejectedChannel.outputBytes().size)

    val illegalChannel = ScriptedChannel()
    val illegal = V9FoundationConnection(illegalChannel, V9Role.CLIENT)
    illegal.start()
    illegalChannel.enqueue(
        rawFrame(
            V9MessageType.ERROR,
            V9Flag.TERMINAL.wireMask,
            0,
            0,
            V9ErrorPayloadCodec.encode(V9ErrorCode.CAPABILITY_MISMATCH),
        ),
    )
    assertTrue(illegalChannel.outputShutdown.await(2, TimeUnit.SECONDS))
    assertEquals(V9ErrorCode.UNEXPECTED_MESSAGE, illegal.snapshot().failure?.reason)
    illegalChannel.enqueueEof()
    awaitCondition { illegal.isClosed() }
  }

  @Test
  fun injectedSystemSchedulerRemainsCallerOwned() {
    val scheduler = V9SystemDeadlineScheduler()
    try {
      val connection =
          V9FoundationConnection(
              ScriptedChannel(),
              V9Role.CLIENT,
              scheduler = scheduler,
          )
      connection.close()
      val invoked = CountDownLatch(1)
      scheduler.schedule(V9MonotonicClock.SYSTEM.nowMillis()) { invoked.countDown() }
      assertTrue(invoked.await(2, TimeUnit.SECONDS))

      val attempt =
          V9FoundationConnectAttempt(
              scheduler = scheduler,
              channelFactory = { ImmediateConnectableChannel() },
          )
      attempt.cancel()
      attempt.start(InetSocketAddress("127.0.0.1", 1)) { _, error ->
        assertEquals(V9ErrorCode.CANCELLED, error)
      }
      val invokedAfterAttempt = CountDownLatch(1)
      scheduler.schedule(V9MonotonicClock.SYSTEM.nowMillis()) {
        invokedAfterAttempt.countDown()
      }
      assertTrue(invokedAfterAttempt.await(2, TimeUnit.SECONDS))
    } finally {
      scheduler.close()
    }
  }

  @Test
  fun connectAttemptCancellationWinsEveryPreHandoffBoundaryExactlyOnce() {
    V9ConnectHook.entries.forEach { selected ->
      val channel = ImmediateConnectableChannel()
      val entered = CountDownLatch(1)
      val release = CountDownLatch(1)
      val callback = CountDownLatch(1)
      val callbackCount = AtomicInteger()
      var result: V9ErrorCode? = null
      val attempt = V9FoundationConnectAttempt(channelFactory = { channel })
      val blocker = {
        entered.countDown()
        release.await()
        Unit
      }
      attempt.hooks =
          V9ConnectAttemptHooks(
              beforeConnect = if (selected == V9ConnectHook.BEFORE_CONNECT) blocker else ({}),
              afterConnect = if (selected == V9ConnectHook.AFTER_CONNECT) blocker else ({}),
              afterConnectionCreated =
                  if (selected == V9ConnectHook.AFTER_CONSTRUCTION) blocker else ({}),
              afterConnectionStarted =
                  if (selected == V9ConnectHook.AFTER_START) blocker else ({}),
          )
      attempt.start(InetSocketAddress("127.0.0.1", 1)) { connection, error ->
        assertNull(connection)
        result = error
        callbackCount.incrementAndGet()
        callback.countDown()
      }
      assertTrue(entered.await(2, TimeUnit.SECONDS), selected.name)
      attempt.cancel()
      attempt.cancel()
      attempt.close()
      release.countDown()
      assertTrue(callback.await(2, TimeUnit.SECONDS), selected.name)
      assertEquals(V9ErrorCode.CANCELLED, result, selected.name)
      assertEquals(1, callbackCount.get(), selected.name)
      assertEquals(1, channel.closeCount.get(), selected.name)
    }

    val cancelled = V9FoundationConnectAttempt { ImmediateConnectableChannel() }
    cancelled.cancel()
    val callback = AtomicInteger()
    cancelled.start(InetSocketAddress("127.0.0.1", 1)) { connection, error ->
      assertNull(connection)
      assertEquals(V9ErrorCode.CANCELLED, error)
      callback.incrementAndGet()
    }
    cancelled.close()
    assertEquals(1, callback.get())
  }

  @Test
  fun connectAttemptTimesOutExactlyAndRelinquishesAHandedOffConnection() {
    val clock = FakeClock()
    val scheduler = ManualScheduler(clock)
    val blocked = BlockingConnectableChannel()
    val timeoutResult = CountDownLatch(1)
    var timeoutError: V9ErrorCode? = null
    val timeout =
        V9FoundationConnectAttempt(
            clock = clock,
            scheduler = scheduler,
            channelFactory = { blocked },
        )
    timeout.start(InetSocketAddress("127.0.0.1", 1)) { connection, error ->
      assertNull(connection)
      timeoutError = error
      timeoutResult.countDown()
    }
    assertTrue(blocked.connectEntered.await(2, TimeUnit.SECONDS))
    clock.now = 4_999
    scheduler.runDue()
    assertFalse(timeout.isComplete())
    assertEquals(0, blocked.closeCount.get())
    clock.now = 5_000
    scheduler.runDue()
    assertTrue(timeoutResult.await(2, TimeUnit.SECONDS))
    assertEquals(V9ErrorCode.TIMEOUT, timeoutError)
    assertEquals(1, blocked.closeCount.get())

    val handedChannel = ImmediateConnectableChannel()
    val handedResult = CountDownLatch(1)
    var handed: V9FoundationConnection? = null
    val attempt = V9FoundationConnectAttempt { handedChannel }
    attempt.start(InetSocketAddress("127.0.0.1", 1)) { connection, error ->
      assertNull(error)
      handed = assertNotNull(connection)
      handedResult.countDown()
    }
    assertTrue(handedResult.await(2, TimeUnit.SECONDS))
    attempt.close()
    assertEquals(0, handedChannel.closeCount.get())
    assertFalse(assertNotNull(handed).isClosed())
    assertNotNull(handed).close()
    assertEquals(1, handedChannel.closeCount.get())
  }

  @Test
  fun connectAttemptSchedulingFailureCompletesOnceAndDestroysAcceptedCredential() {
    val scheduler = ThrowingScheduler()
    val factoryCalls = AtomicInteger()
    val callbacks = AtomicInteger()
    val result = AtomicReference<V9ErrorCode>()
    val invitation = clientInvitation()
    val attempt =
        V9FoundationConnectAttempt(
            scheduler = scheduler,
            channelFactory = {
              factoryCalls.incrementAndGet()
              ImmediateConnectableChannel()
            },
        )

    attempt.start(
        InetSocketAddress("127.0.0.1", 1),
        invitation = invitation,
    ) { connection, error ->
      assertNull(connection)
      result.set(error)
      callbacks.incrementAndGet()
    }

    assertEquals(V9ErrorCode.INTERNAL_ERROR, result.get())
    assertEquals(1, callbacks.get())
    assertEquals(0, factoryCalls.get())
    assertFalse(invitation.isSecretAvailable())
    assertEquals(0, scheduler.closeCount.get())
    attempt.cancel()
    attempt.close()
    assertEquals(1, callbacks.get())
    assertEquals(0, scheduler.closeCount.get())
  }

  @Test
  fun synchronousClientAcceptsCredentialOnlyAfterValidationAndClosesItOnSetupFailure() {
    val address = InetSocketAddress("127.0.0.1", 1)
    val validationFactoryCalls = AtomicInteger()
    val callerOwned = clientInvitation()
    assertFailsWith<IllegalArgumentException> {
      V9FoundationClient.connect(
          address,
          V9LinkMode.FOUR_PLAYER,
          emptySet(),
          V9Timeout.WAIT_SERVER_HELLO.milliseconds.toInt(),
          callerOwned,
      ) {
        validationFactoryCalls.incrementAndGet()
        ImmediateConnectableChannel()
      }
    }
    assertEquals(0, validationFactoryCalls.get())
    assertTrue(callerOwned.isSecretAvailable())
    callerOwned.close()

    val accepted = clientInvitation()
    val setupFactoryCalls = AtomicInteger()
    assertFailsWith<IOException> {
      V9FoundationClient.connect(
          address,
          V9LinkMode.NORMAL,
          emptySet(),
          V9Timeout.WAIT_SERVER_HELLO.milliseconds.toInt(),
          accepted,
      ) {
        setupFactoryCalls.incrementAndGet()
        throw IOException("synthetic socket-channel setup failure")
      }
    }
    assertEquals(1, setupFactoryCalls.get())
    assertFalse(accepted.isSecretAvailable())
  }

  @Test
  fun serverRemovesClosedCandidatesAndIsolatesConstructionStartAndCallbackFailures() {
    val callbacks = AtomicInteger()
    val accepted = CountDownLatch(3)
    V9FoundationServer(onAwaitingPairing = {
      callbacks.incrementAndGet()
      it.close()
      accepted.countDown()
    }).use { server ->
      server.start()
      val candidates = List(3) { completeClientHello(server.localPort) }
      assertTrue(accepted.await(5, TimeUnit.SECONDS))
      candidates.forEach(Socket::close)
      awaitCondition {
        server.activeConnectionCount() == 0 && server.pendingCandidateCount() == 0
      }
      assertEquals(3, callbacks.get())
    }

    val valid = CountDownLatch(1)
    val factoryCalls = AtomicInteger()
    V9FoundationServer(onAwaitingPairing = {
      it.close()
      valid.countDown()
    }).use { server ->
      server.connectionFactory = { channel, role, mode, capabilities ->
        when (factoryCalls.incrementAndGet()) {
          1 -> throw IllegalStateException("synthetic constructor failure")
          2 ->
            V9FoundationConnection(
                    channel,
                    role,
                    mode,
                    optionalCapabilities = capabilities,
                )
                .also(V9FoundationConnection::close)
          else ->
            V9FoundationConnection(
                channel,
                role,
                mode,
                optionalCapabilities = capabilities,
            )
        }
      }
      server.start()
      Socket("127.0.0.1", server.localPort).use { it.getInputStream().read() }
      Socket("127.0.0.1", server.localPort).use { it.getInputStream().read() }
      val candidate = completeClientHello(server.localPort)
      assertTrue(valid.await(5, TimeUnit.SECONDS))
      candidate.close()
      awaitCondition {
        server.activeConnectionCount() == 0 && server.pendingCandidateCount() == 0
      }
      assertEquals(3, factoryCalls.get())
    }

    val callbackCalls = AtomicInteger()
    val laterValid = CountDownLatch(1)
    V9FoundationServer(onAwaitingPairing = {
      if (callbackCalls.incrementAndGet() == 1) {
        throw IllegalStateException("synthetic callback failure")
      }
      it.close()
      laterValid.countDown()
    }).use { server ->
      server.start()
      val first = completeClientHello(server.localPort)
      val second = completeClientHello(server.localPort)
      assertTrue(laterValid.await(5, TimeUnit.SECONDS))
      first.close()
      second.close()
      awaitCondition {
        server.activeConnectionCount() == 0 && server.pendingCandidateCount() == 0
      }
      assertEquals(2, callbackCalls.get())
    }

    val callbackEntered = CountDownLatch(1)
    val releaseCallback = CountDownLatch(1)
    val closeReturned = CountDownLatch(1)
    val callbackAfterClose = AtomicInteger()
    val racing =
        V9FoundationServer(onAwaitingPairing = {
          callbackEntered.countDown()
          releaseCallback.await()
          if (closeReturned.count == 0L) callbackAfterClose.incrementAndGet()
          it.close()
        })
    racing.start()
    val candidate = completeClientHello(racing.localPort)
    assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
    val closer =
        Thread {
          racing.close()
          closeReturned.countDown()
        }.also(Thread::start)
    assertFalse(closeReturned.await(50, TimeUnit.MILLISECONDS))
    releaseCallback.countDown()
    assertTrue(closeReturned.await(5, TimeUnit.SECONDS))
    closer.join()
    candidate.close()
    assertEquals(0, callbackAfterClose.get())
    assertEquals(0, racing.activeConnectionCount())
    assertEquals(0, racing.pendingCandidateCount())

    val neverStarted = V9FoundationServer(onAwaitingPairing = {})
    neverStarted.close()
    assertFailsWith<IllegalStateException> { neverStarted.start() }
    assertEquals(0, neverStarted.activeConnectionCount())
    assertEquals(0, neverStarted.pendingCandidateCount())
  }

  @Test
  fun serverShutdownClosesSocketAcceptedBeforeCandidateAdmission() {
    val accepted = CountDownLatch(1)
    val releaseAdmission = CountDownLatch(1)
    val serverSocket = AtomicReference<Socket>()
    val callbacks = AtomicInteger()
    val server =
        V9FoundationServer(onAwaitingPairing = {
          callbacks.incrementAndGet()
          it.close()
        })
    server.candidateHooks =
        V9ServerCandidateHooks(
            afterAcceptBeforeAdmission = {
              serverSocket.set(it)
              accepted.countDown()
              releaseAdmission.await()
            },
        )
    server.start()
    val client = Socket("127.0.0.1", server.localPort)
    try {
      assertTrue(accepted.await(2, TimeUnit.SECONDS))
      assertEquals(0, server.pendingCandidateCount())

      server.close()
      releaseAdmission.countDown()

      awaitCondition { assertNotNull(serverSocket.get()).isClosed }
      awaitServerShutdown(server)
      assertEquals(0, callbacks.get())
    } finally {
      releaseAdmission.countDown()
      client.close()
      server.close()
    }
  }

  @Test
  fun serverShutdownClosesCandidateQueuedBeforeAnyWorkerCanRunIt() {
    val workerCount = V9Limit.HANDSHAKE_WORKERS.value.toInt()
    val candidateCount = workerCount + 1
    val accepted = CountDownLatch(candidateCount)
    val workersEntered = CountDownLatch(workerCount)
    val workerStarts = AtomicInteger()
    val releaseWorkers = CountDownLatch(1)
    val serverSockets = ConcurrentLinkedQueue<Socket>()
    val callbacks = AtomicInteger()
    val server =
        V9FoundationServer(onAwaitingPairing = {
          callbacks.incrementAndGet()
          it.close()
        })
    server.candidateHooks =
        V9ServerCandidateHooks(
            afterAcceptBeforeAdmission = {
              serverSockets += it
              accepted.countDown()
            },
            beforeWorkerNegotiation = {
              workerStarts.incrementAndGet()
              workersEntered.countDown()
              releaseWorkers.await()
            },
        )
    server.start()
    val clients = List(candidateCount) { Socket("127.0.0.1", server.localPort) }
    try {
      assertTrue(accepted.await(2, TimeUnit.SECONDS))
      assertTrue(workersEntered.await(2, TimeUnit.SECONDS))
      awaitCondition { server.pendingCandidateCount() == candidateCount }
      // All workers are held above; the extra accepted candidate is still queued.
      assertEquals(workerCount, workerStarts.get())

      server.close()
      releaseWorkers.countDown()

      awaitCondition {
        serverSockets.size == candidateCount && serverSockets.all(Socket::isClosed)
      }
      awaitServerShutdown(server)
      assertEquals(0, callbacks.get())
    } finally {
      releaseWorkers.countDown()
      clients.forEach(Socket::close)
      server.close()
    }
  }

  private fun awaitServerShutdown(server: V9FoundationServer) {
    awaitCondition {
      server.pendingCandidateCount() == 0 &&
          server.activeConnectionCount() == 0 &&
          !server.acceptThreadAlive() &&
          server.workerPoolTerminated()
    }
  }

  private fun assertStageTimeout(
      expected: V9LifecycleState,
      role: V9Role,
      channel: ScriptedChannel,
  ) {
    val clock = FakeClock()
    val scheduler = ManualScheduler(clock)
    val connection =
        V9FoundationConnection(
            channel,
            role,
            clock = clock,
            scheduler = scheduler,
        )
    connection.start()
    awaitCondition { connection.snapshot().state == expected }
    clock.now = 4_999
    scheduler.runDue()
    assertEquals(expected, connection.snapshot().state)
    clock.now = 5_000
    scheduler.runDue()
    awaitCondition { connection.isClosed() }
    assertEquals(V9ErrorCode.TIMEOUT, connection.snapshot().failure?.reason)
    assertEquals(1, channel.closeCount.get())
  }

  private fun completeClientHello(port: Int): Socket {
    val socket = Socket("127.0.0.1", port)
    socket.soTimeout = 3_000
    readExactly(socket, ProtocolV9.HEADER_BYTES + 94)
    socket.getOutputStream().write(clientHello())
    socket.getOutputStream().flush()
    return socket
  }

  private fun readExactly(socket: Socket, size: Int) {
    val bytes = ByteArray(size)
    var offset = 0
    while (offset < bytes.size) {
      val count = socket.getInputStream().read(bytes, offset, bytes.size - offset)
      if (count < 0) throw IOException("unexpected test EOF")
      offset += count
    }
  }

  private fun serverHello(): ByteArray =
      helloFrame(V9Role.SERVER, ByteArray(32) { 1 })

  private fun clientHello(): ByteArray =
      helloFrame(V9Role.CLIENT, ByteArray(32) { 2 })

  private fun helloFrame(role: V9Role, nonce: ByteArray): ByteArray =
      V9FrameEncoder.encode(
          V9OutboundFrame(
              V9MessageType.HELLO,
              0,
              0,
              0,
              ProtocolV9.CONTROL_CHANNEL,
              V9HelloCodec.encode(V9HelloCodec.create(role, nonce)),
          ),
      )

  private fun malformedServerHello(): ByteArray {
    val valid = V9HelloCodec.encode(V9HelloCodec.create(V9Role.SERVER, ByteArray(32) { 3 }))
    val malformed =
        valid.copyOf(valid.size - 8).also {
          ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putShort(36, 6)
        }
    return rawFrame(V9MessageType.HELLO, 0, 0, 0, malformed)
  }

  private fun rawFrame(
      type: V9MessageType,
      flags: Int,
      sequence: Long,
      correlation: Long,
      payload: ByteArray,
  ): ByteArray =
      ByteBuffer.allocate(ProtocolV9.HEADER_BYTES + payload.size).order(ByteOrder.BIG_ENDIAN)
          .put(ProtocolV9.MAGIC)
          .put(ProtocolV9.MAJOR.toByte())
          .put(ProtocolV9.MINOR.toByte())
          .putShort(ProtocolV9.HEADER_BYTES.toShort())
          .putShort(type.wireId.toShort())
          .putShort(flags.toShort())
          .putInt(sequence.toInt())
          .putInt(correlation.toInt())
          .putInt(payload.size)
          .putInt(payload.size)
          .putInt(ProtocolV9.CONTROL_CHANNEL.toInt())
          .put(MessageDigest.getInstance("SHA-256").digest(payload))
          .put(payload)
          .array()

  private fun awaitCondition(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition()) {
      check(System.nanoTime() < deadline) { "condition did not become true" }
      Thread.yield()
    }
  }

  private fun clientInvitation(): V9ClientInvitation =
      V9Invitation.parse(
              "coffeegb://play.example:6688/join?v=9&mode=normal&slot=1" +
                  "&exp=2000000000&token=AAECAwQFBgcICQoLDA0ODw",
          )
          .forClientAuthentication()

  private enum class V9ConnectHook {
    BEFORE_CONNECT,
    AFTER_CONNECT,
    AFTER_CONSTRUCTION,
    AFTER_START,
  }

  private class FakeClock(var now: Long = 0) : V9MonotonicClock {
    override fun nowMillis(): Long = now
  }

  private class ManualScheduler(private val clock: FakeClock) : V9DeadlineScheduler {
    private val tasks = mutableListOf<Task>()

    @Synchronized
    override fun schedule(deadlineMillis: Long, action: Runnable): Closeable {
      val task = Task(deadlineMillis, action)
      tasks += task
      return Closeable { task.active.set(false) }
    }

    @Synchronized
    fun runDue() {
      tasks.filter { it.active.get() && it.deadline <= clock.now }.forEach {
        if (it.active.compareAndSet(true, false)) it.action.run()
      }
    }

    @Synchronized
    fun activeTasks(): Int = tasks.count { it.active.get() }

    private data class Task(
        val deadline: Long,
        val action: Runnable,
        val active: AtomicBoolean = AtomicBoolean(true),
    )
  }

  private class ThrowingScheduler : V9DeadlineScheduler, Closeable {
    val closeCount = AtomicInteger()

    override fun schedule(deadlineMillis: Long, action: Runnable): Closeable {
      throw IllegalStateException("synthetic scheduling failure")
    }

    override fun close() {
      closeCount.incrementAndGet()
    }
  }

  private class ScriptedChannel(
      private val blockWrites: Boolean = false,
      private val failWrites: Boolean = false,
  ) : V9TransportChannel {
    private val incoming = LinkedBlockingQueue<Int>()
    private val output = ByteArrayOutputStream()
    private val writeRelease = CountDownLatch(if (blockWrites) 1 else 0)
    val writeEntered = CountDownLatch(1)
    val outputShutdown = CountDownLatch(1)
    val closed = AtomicBoolean()
    val closeCount = AtomicInteger()
    val readCalls = AtomicInteger()
    val lastWriteBuffer = AtomicReference<ByteArray?>()

    fun enqueue(bytes: ByteArray) {
      bytes.forEach { incoming.put(it.toInt() and 0xff) }
    }

    fun enqueueEof() {
      incoming.put(-1)
    }

    @Synchronized
    fun outputBytes(): ByteArray = output.toByteArray()

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
      readCalls.incrementAndGet()
      val first = incoming.take()
      if (first < 0) return -1
      bytes[offset] = first.toByte()
      var count = 1
      while (count < length) {
        val next = incoming.poll() ?: break
        if (next < 0) {
          incoming.offer(next)
          break
        }
        bytes[offset + count++] = next.toByte()
      }
      return count
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
      lastWriteBuffer.set(bytes)
      writeEntered.countDown()
      writeRelease.await()
      if (closed.get()) throw IOException("closed")
      if (failWrites) throw IOException("synthetic write failure")
      synchronized(this) { output.write(bytes, offset, length) }
      return length
    }

    override fun shutdownOutput() {
      outputShutdown.countDown()
    }

    override fun close() {
      if (closed.compareAndSet(false, true)) {
        closeCount.incrementAndGet()
        writeRelease.countDown()
        incoming.offer(-1)
      }
    }
  }

  private open class ImmediateConnectableChannel : V9ConnectableChannel {
    private val closeLatch = CountDownLatch(1)
    val closeCount = AtomicInteger()
    private val closed = AtomicBoolean()

    override fun connect(address: InetSocketAddress, timeoutMillis: Int) = Unit

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
      closeLatch.await()
      return -1
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
      if (closed.get()) throw IOException("closed")
      return length
    }

    override fun shutdownOutput() = Unit

    override fun close() {
      if (closed.compareAndSet(false, true)) {
        closeCount.incrementAndGet()
        closeLatch.countDown()
      }
    }
  }

  private class BlockingConnectableChannel : ImmediateConnectableChannel() {
    val connectEntered = CountDownLatch(1)
    private val connectRelease = CountDownLatch(1)

    override fun connect(address: InetSocketAddress, timeoutMillis: Int) {
      connectEntered.countDown()
      connectRelease.await()
    }

    override fun close() {
      connectRelease.countDown()
      super.close()
    }
  }
}
