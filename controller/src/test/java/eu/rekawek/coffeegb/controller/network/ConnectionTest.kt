package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.NetplayMementoCodec
import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.controller.events.EventQueue
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.LinkMode
import eu.rekawek.coffeegb.controller.link.LinkedController
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memento.Memento
import eu.rekawek.coffeegb.core.memory.Ram
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.FourPlayerAdapter
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import org.junit.After
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.file.Paths
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConnectionTest {

  /** Delivers at most one byte per read, like a heavily fragmented TCP stream. */
  private class TrickleInputStream(private val delegate: InputStream) : InputStream() {
    override fun read(): Int = delegate.read()

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        delegate.read(b, off, if (len > 0) 1 else 0)
  }

  /**
   * Thread-agnostic in-memory pipe. PipedInputStream/PipedOutputStream track the last
   * writer/reader thread and throw "write end dead" once it terminates - but Connection
   * writes from short-lived event-bus dispatch threads, which killed the pipe mid-test
   * on slow machines.
   */
  private class Pipe {
    private val lock = Object()
    private val data = ArrayDeque<Byte>()
    private var closed = false

    val sink: OutputStream = object : OutputStream() {
      override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

      override fun write(b: ByteArray, off: Int, len: Int) {
        synchronized(lock) {
          if (closed) throw IOException("Pipe closed")
          for (i in off until off + len) data.addLast(b[i])
          lock.notifyAll()
        }
      }

      override fun close() {
        synchronized(lock) {
          closed = true
          lock.notifyAll()
        }
      }
    }

    val source: InputStream = object : InputStream() {
      override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) == -1) -1 else b[0].toInt() and 0xff
      }

      override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        synchronized(lock) {
          while (data.isEmpty() && !closed) {
            try {
              lock.wait()
            } catch (e: InterruptedException) {
              throw InterruptedIOException()
            }
          }
          if (data.isEmpty()) return -1
          var n = 0
          while (n < len && data.isNotEmpty()) {
            b[off + n] = data.removeFirst()
            n++
          }
          return n
        }
      }

      override fun close() = sink.close()
    }
  }

  private class ToggleFailingOutputStream : ByteArrayOutputStream() {
    var failWrites = false

    override fun write(b: Int) {
      if (failWrites) throw IOException("destination closed")
      super.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
      if (failWrites) throw IOException("destination closed")
      super.write(b, off, len)
    }
  }

  private val senderBus = EventBusImpl()

  private val receiverBus = EventBusImpl()

  private var sender: Connection? = null

  private var receiver: Connection? = null

  private val threads = mutableListOf<Thread>()

  private fun connect(
      startSession: Boolean = true,
      mode: LinkMode = LinkMode.NORMAL,
  ) {
    val senderToReceiver = Pipe()
    val receiverToSender = Pipe()

    val sender =
        Connection(receiverToSender.source, senderToReceiver.sink, senderBus, true, mode)
    val receiver =
        Connection(TrickleInputStream(senderToReceiver.source), receiverToSender.sink, receiverBus, false)
    this.sender = sender
    this.receiver = receiver
    // Exercise the deterministic pre-capability queue: START must not be silently discarded when
    // the server run loop has not consumed the client's capability byte yet.
    if (startSession) sender.startSession()
    // run() performs the handshake and then reads; both sides need it running
    threads += Thread { sender.run() }.also { it.start() }
    threads += Thread { receiver.run() }.also { it.start() }
  }

  @After
  fun tearDown() {
    sender?.stop()
    receiver?.stop()
    threads.forEach { it.interrupt() }
    senderBus.close()
    receiverBus.close()
  }

  @Test
  fun buttonStateSurvivesFragmentedDelivery() {
    val received = LinkedBlockingQueue<LinkedController.RemoteButtonStateEvent>()
    receiverBus.register<LinkedController.RemoteButtonStateEvent> { received.add(it) }
    connect()
    establishSharedFrame(0)

    senderBus.post(
        LinkedController.LocalButtonStateEvent(
            42, Input(listOf(Button.A, Button.START), listOf(Button.LEFT))))
    senderBus.post(
        LinkedController.LocalButtonStateEvent(43, Input(emptyList(), listOf(Button.A))))

    val first = received.poll(5, TimeUnit.SECONDS)
    assertNotNull(first)
    assertEquals(42, first.frame)
    assertEquals(listOf(Button.A, Button.START), first.input.pressedButtons)
    assertEquals(listOf(Button.LEFT), first.input.releasedButtons)

    val second = received.poll(5, TimeUnit.SECONDS)
    assertNotNull(second)
    assertEquals(43, second.frame)
    assertEquals(listOf(Button.A), second.input.releasedButtons)
  }

  @Test
  fun romTransferIsCompressedAndRoundTrips() {
    val received = LinkedBlockingQueue<Connection.PeerLoadedGameEvent>()
    receiverBus.register<Connection.PeerLoadedGameEvent> { received.add(it) }
    connect()

    val rom = ROM.readBytes()
    val battery = ByteArray(8 * 1024) { (it % 7).toByte() }
    val snapshot =
        portableStates(battery, GameboyType.CGB, cgb0Revision = true).first

    senderBus.post(
        LinkedController.LocalRomLoadedEvent(
            rom,
            battery,
            snapshot,
            GameboyType.CGB,
            Gameboy.BootstrapMode.FAST_FORWARD,
            7,
            cgb0Revision = true,
            heldButtons = setOf(Button.A, Button.LEFT),
        )
    )

    val event = received.poll(10, TimeUnit.SECONDS)
    assertNotNull(event)
    assertContentEquals(rom, event.rom)
    assertContentEquals(battery, event.battery)
    assertContentEquals(snapshot, event.snapshot)
    assertEquals(null, event.sessionSnapshot)
    assertEquals(GameboyType.CGB, event.gameboyType)
    assertEquals(Gameboy.BootstrapMode.FAST_FORWARD, event.bootstrapMode)
    assertTrue(event.cgb0Revision)
    assertEquals(setOf(Button.A, Button.LEFT), event.heldButtons)
    assertEquals(7, event.frame)
  }

  @Test
  fun fourPlayerCheckpointEndsWithSynchronizationFrame() {
    val received = LinkedBlockingQueue<Connection.PeerLoadedGameEvent>()
    val synchronized = LinkedBlockingQueue<Connection.SessionCheckpointEvent>()
    receiverBus.register<Connection.PeerLoadedGameEvent> { received.add(it) }
    receiverBus.register<Connection.SessionCheckpointEvent> { synchronized.add(it) }
    connect(mode = LinkMode.FOUR_PLAYER_ADAPTER)

    val state =
        LinkedController.LocalRomLoadedEvent(
            romFile = ROM.readBytes(),
            batteryFile = null,
            snapshot = null,
            gameboyType = GameboyType.DMG,
            bootstrapMode = Gameboy.BootstrapMode.SKIP,
            frame = 73,
            player = 0,
            sessionSnapshot = portableStates().second,
            heldButtons = setOf(Button.START),
        )
    senderBus.post(LinkedController.SessionStateReadyEvent(73, listOf(state)))

    assertEquals(null, received.poll(200, TimeUnit.MILLISECONDS))
    val checkpoint = assertNotNull(synchronized.poll(5, TimeUnit.SECONDS))
    assertEquals(1, checkpoint.states.size)
    val game = checkpoint.states.single()
    assertContentEquals(state.sessionSnapshot, game.sessionSnapshot)
    assertEquals(setOf(Button.START), game.heldButtons)
    assertEquals(73, checkpoint.frame)
  }

  @Test
  fun messagesReceivedBeforeStartAreDeliveredAfterControllerTransition() {
    val received = LinkedBlockingQueue<Connection.PeerLoadedGameEvent>()
    receiverBus.register<Connection.PeerLoadedGameEvent> { received.add(it) }
    connect(startSession = false)

    senderBus.post(
        LinkedController.LocalRomLoadedEvent(
            ROM.readBytes(),
            null,
            null,
            GameboyType.DMG,
            Gameboy.BootstrapMode.SKIP,
            0,
        ))
    assertEquals(null, received.poll(200, TimeUnit.MILLISECONDS))

    sender!!.startSession()
    assertContentEquals(
        ROM.readBytes(),
        assertNotNull(received.poll(5, TimeUnit.SECONDS)).rom,
    )
  }

  @Test
  fun runtimeMessagesWaitBehindStartWhileBootstrapStateMayLeadIt() {
    val games = LinkedBlockingQueue<Connection.PeerLoadedGameEvent>()
    val inputs = LinkedBlockingQueue<LinkedController.RemoteButtonStateEvent>()
    receiverBus.register<Connection.PeerLoadedGameEvent> { games.add(it) }
    receiverBus.register<LinkedController.RemoteButtonStateEvent> { inputs.add(it) }
    connect(startSession = false)

    senderBus.post(
        LinkedController.LocalRomLoadedEvent(
            ROM.readBytes(),
            null,
            null,
            GameboyType.DMG,
            Gameboy.BootstrapMode.SKIP,
            12,
        ))
    senderBus.post(
        LinkedController.LocalButtonStateEvent(
            12,
            Input(listOf(Button.A), emptyList()),
        ))

    assertEquals(null, games.poll(200, TimeUnit.MILLISECONDS))
    assertEquals(null, inputs.poll(200, TimeUnit.MILLISECONDS))
    sender!!.startSession()

    assertEquals(12, assertNotNull(games.poll(5, TimeUnit.SECONDS)).frame)
    assertEquals(12, assertNotNull(inputs.poll(5, TimeUnit.SECONDS)).frame)
  }

  @Test
  fun resetAndStopRoundTrip() {
    val resets = LinkedBlockingQueue<Connection.ReceivedRemoteResetEvent>()
    val stops = LinkedBlockingQueue<Connection.ReceivedRemoteStopEvent>()
    receiverBus.register<Connection.ReceivedRemoteResetEvent> { resets.add(it) }
    receiverBus.register<Connection.ReceivedRemoteStopEvent> { stops.add(it) }
    connect()
    establishSharedFrame(0)

    senderBus.post(Connection.RequestResetEvent(42L))
    senderBus.post(Connection.RequestStopEvent(43L))

    assertEquals(42L, assertNotNull(resets.poll(5, TimeUnit.SECONDS)).frame)
    assertEquals(43L, assertNotNull(stops.poll(5, TimeUnit.SECONDS)).frame)
  }

  @Test
  fun concurrentSendersDoNotInterleaveMessages() {
    val received = LinkedBlockingQueue<LinkedController.RemoteButtonStateEvent>()
    receiverBus.register<LinkedController.RemoteButtonStateEvent> { received.add(it) }
    connect()
    establishSharedFrame(100)

    // messages are written by different event bus threads in production; hammer the
    // sender from many threads and verify that every message arrives intact
    val perThread = 50
    val senders = (0 until 4).map { t ->
      Thread {
        for (i in 0 until perThread) {
          val frame = (t * perThread + i).toLong()
          val button = Button.entries[(frame % Button.entries.size).toInt()]
          senderBus.post(
              LinkedController.LocalButtonStateEvent(frame, Input(listOf(button), emptyList())))
        }
      }
    }
    senders.forEach { it.start() }
    senders.forEach { it.join() }

    val events = mutableListOf<LinkedController.RemoteButtonStateEvent>()
    repeat(4 * perThread) {
      events += assertNotNull(received.poll(5, TimeUnit.SECONDS), "message ${it + 1} lost")
    }
    for (e in events) {
      val expectedButton = Button.entries[(e.frame % Button.entries.size).toInt()]
      assertEquals(listOf(expectedButton), e.input.pressedButtons, "corrupted frame ${e.frame}")
    }
  }

  @Test
  fun validatedRelayDestinationWriteFailureDoesNotEscapeControllerDispatch() {
    val delivered = LinkedBlockingQueue<LinkedController.RemoteButtonStateEvent>()
    receiverBus.register<LinkedController.RemoteButtonStateEvent> { delivered.add(it) }
    val destinationOutput = ToggleFailingOutputStream()
    val destination =
        Connection(
            ByteArrayInputStream(byteArrayOf(0x01)),
            destinationOutput,
            receiverBus,
            true,
            LinkMode.FOUR_PLAYER_ADAPTER,
            assignedPlayer = 2,
        )
    destination.completeServerHandshake()
    destinationOutput.failWrites = true
    val romBytes = ROM.readBytes()
    val rom = Connection.deflate(romBytes, StateLimits.ROM)
    val inputMessage =
        byteArrayOf(0x01, 0x01) +
            romHeader(
                decodedSizes = intArrayOf(romBytes.size, 0, 0, 0),
                encodedSizes = intArrayOf(rom.size, 0, 0, 0),
            ) +
            rom +
            byteArrayOf(
                0x03,
                0x01,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                1,
                0,
                Button.A.ordinal.toByte(),
            )
    val origin =
        Connection(
            ByteArrayInputStream(inputMessage),
            ByteArrayOutputStream(),
            receiverBus,
            true,
            LinkMode.FOUR_PLAYER_ADAPTER,
            assignedPlayer = 1,
        )
    try {
      origin.run()

      val event = assertNotNull(delivered.poll(1, TimeUnit.SECONDS))
      assertEquals(1, event.player)
      assertEquals(listOf(Button.A), event.input.pressedButtons)
      receiverBus.post(Connection.ValidatedPeerButtonStateEvent(event))
    } finally {
      origin.close()
      destination.close()
    }
  }

  @Test
  fun runtimeOverflowPurgesOnlyTheOffendingConnectionsQueuedEvents() {
    val delivered = mutableListOf<LinkedController.RemoteButtonStateEvent>()
    val queue =
        EventQueue(
            receiverBus,
            maxEvents = 2,
            maxBytes = 1_024,
            eventWeight = { 1 },
            eventSource = {
              (it as? LinkedController.RemoteButtonStateEvent)?.source
            },
        )
    queue.register<LinkedController.RemoteButtonStateEvent> { delivered += it }
    val inputMessage =
        byteArrayOf(0x03, 0x00) + ByteBuffer.allocate(8).putLong(0).array() + byteArrayOf(0, 0)
    val offender =
        Connection(
            ByteArrayInputStream(byteArrayOf(0x01) + inputMessage + inputMessage + inputMessage),
            ByteArrayOutputStream(),
            receiverBus,
            true,
            LinkMode.FOUR_PLAYER_ADAPTER,
            assignedPlayer = 1,
        )
    val honest =
        Connection(
            ByteArrayInputStream(byteArrayOf(0x01) + inputMessage),
            ByteArrayOutputStream(),
            receiverBus,
            true,
            LinkMode.FOUR_PLAYER_ADAPTER,
            assignedPlayer = 2,
        )
    try {
      assertFailsWith<Connection.ProtocolException> { offender.run() }
      honest.run()
      queue.dispatch()

      assertEquals(listOf(2), delivered.map { it.player })
    } finally {
      offender.close()
      honest.close()
    }
  }

  @Test
  fun pendingCheckpointOverflowDisconnectsBeforeControllerDelivery() {
    val delivered = LinkedBlockingQueue<Connection.SessionCheckpointEvent>()
    receiverBus.register<Connection.SessionCheckpointEvent> { delivered += it }
    val romBytes = ROM.readBytes()
    val rom = Connection.deflate(romBytes, StateLimits.ROM)
    val sessionBytes = portableStates().second
    val session = Connection.deflate(sessionBytes, StateLimits.SESSION_SNAPSHOT)
    val checkpoint =
        byteArrayOf(0x01) +
            romHeader(
                decodedSizes = intArrayOf(romBytes.size, 0, 0, sessionBytes.size),
                encodedSizes = intArrayOf(rom.size, 0, 0, session.size),
            ) +
            rom +
            session +
            byteArrayOf(0x09) +
            ByteBuffer.allocate(8).putLong(0).array()
    val connection =
        clientConnection(
            ByteArrayOutputStream().also { output ->
              repeat(StateLimits.NETPLAY_PENDING_EVENTS + 1) { output.write(checkpoint) }
            }.toByteArray(),
            LinkMode.FOUR_PLAYER_ADAPTER,
        )

    assertFailsWith<Connection.ProtocolException> { connection.run() }
    assertEquals(null, delivered.poll())
  }

  @Test
  fun handshakeRejectsWrongProtocol() {
    val toReceiver = Pipe()
    val toSender = Pipe()

    // the client-side handshake happens during construction
    toReceiver.sink.write(
        "CoffeeGB WRONG!!".toByteArray() +
            byteArrayOf(0x05, LinkMode.NORMAL.ordinal.toByte(), 0x01))
    assertFailsWith<IOException> {
      Connection(toReceiver.source, toSender.sink, receiverBus, false)
    }
  }

  @Test
  fun handshakeRejectsWrongVersion() {
    val toReceiver = Pipe()
    val toSender = Pipe()

    toReceiver.sink.write(
        "CoffeeGB NETPLAY".toByteArray() +
            byteArrayOf(0x7f, LinkMode.NORMAL.ordinal.toByte(), 0x01))
    assertFailsWith<IOException> {
      Connection(toReceiver.source, toSender.sink, receiverBus, false)
    }
  }

  @Test
  fun handshakeRequiresPortableStateCapabilityInBothDirections() {
    val serverHandshake =
        "CoffeeGB NETPLAY".toByteArray() +
            byteArrayOf(0x07, LinkMode.NORMAL.ordinal.toByte(), 0x01, 0x00)
    assertFailsWith<Connection.CompatibilityException> {
      Connection(
          ByteArrayInputStream(serverHandshake),
          ByteArrayOutputStream(),
          receiverBus,
          false,
      )
    }

    val server =
        Connection(
            ByteArrayInputStream(byteArrayOf(0x00)),
            ByteArrayOutputStream(),
            receiverBus,
            true,
        )
    assertFailsWith<Connection.CompatibilityException> { server.run() }
  }

  @Test
  fun inflateRejectsTruncatedPayload() {
    val data = ByteArray(4096) { (it % 13).toByte() }
    val compressed = Connection.deflate(data)
    val truncated = compressed.copyOf(compressed.size / 2)
    assertFailsWith<IOException> { Connection.inflate(truncated, data.size) }
  }

  @Test
  fun everyPayloadLimitRejectsBoundaryPlusOneAndOverflowDeclarations() {
    val limits =
        listOf(
            StateLimits.ROM,
            StateLimits.BATTERY,
            StateLimits.GAME_SNAPSHOT,
            StateLimits.SESSION_SNAPSHOT,
        )

    for (limit in limits) {
      assertEquals(
          limit.decodedBytes,
          Connection.validateDeclaration(limit.decodedBytes, 1, limit, required = true).decodedBytes,
      )
      assertFailsWith<IOException>(limit.description) {
        Connection.validateDeclaration(limit.decodedBytes + 1, 1, limit, required = true)
      }
      assertEquals(
          limit.encodedBytes,
          Connection.validateDeclaration(1, limit.encodedBytes, limit, required = true).encodedBytes,
      )
      assertFailsWith<IOException>(limit.description) {
        Connection.validateDeclaration(1, limit.encodedBytes + 1, limit, required = true)
      }
      assertFailsWith<IOException>(limit.description) {
        Connection.validateDeclaration(Int.MAX_VALUE, Int.MAX_VALUE, limit, required = true)
      }
    }
  }

  @Test
  fun optionalPayloadRequiresConsistentZeroLengths() {
    assertEquals(
        Connection.PayloadDeclaration(0, 0),
        Connection.validateDeclaration(0, 0, StateLimits.BATTERY),
    )
    assertFailsWith<IOException> {
      Connection.validateDeclaration(1, 0, StateLimits.BATTERY)
    }
    assertFailsWith<IOException> {
      Connection.validateDeclaration(0, 1, StateLimits.BATTERY)
    }
  }

  @Test
  fun inflateRejectsCorruptionTrailingDataAndOutputPastDeclaration() {
    val limit = StateLimits.Payload("test payload", 4096, 4096)
    val original = ByteArray(1024) { (it % 31).toByte() }
    val compressed = Connection.deflate(original, limit)

    val corrupt =
        compressed.clone().also {
          it[it.lastIndex / 2] = (it[it.lastIndex / 2].toInt() xor 0x55).toByte()
        }
    assertFailsWith<IOException> {
      Connection.inflate(
          corrupt,
          Connection.PayloadDeclaration(original.size, corrupt.size),
          limit,
      )
    }
    val trailing = compressed + byteArrayOf(1, 2, 3)
    assertFailsWith<IOException> {
      Connection.inflate(
          trailing,
          Connection.PayloadDeclaration(original.size, trailing.size),
          limit,
      )
    }
    assertFailsWith<IOException> {
      Connection.inflate(
          compressed,
          Connection.PayloadDeclaration(original.size - 1, compressed.size),
          limit,
      )
    }
  }

  @Test
  fun compressionBombDeclarationIsRejectedBeforeInflation() {
    val compressed = Connection.deflate(ByteArray(1024))

    assertFailsWith<IOException> {
      Connection.validateDeclaration(
          StateLimits.BATTERY.decodedBytes + 1,
          compressed.size,
          StateLimits.BATTERY,
          required = true,
      )
    }
  }

  @Test
  fun aggregateRomMessageLimitIsCheckedBeforePayloadReads() {
    val header =
        romHeader(
            decodedSizes = intArrayOf(1, 1, 1, 1),
            encodedSizes =
                intArrayOf(
                    StateLimits.ROM.encodedBytes,
                    StateLimits.BATTERY.encodedBytes,
                    StateLimits.GAME_SNAPSHOT.encodedBytes,
                    StateLimits.SESSION_SNAPSHOT.encodedBytes,
                ),
        )
    val connection = clientConnection(byteArrayOf(0x01) + header)

    val error = assertFailsWith<Connection.ProtocolException> { connection.run() }
    assertTrue(error.cause!!.message!!.contains("message exceeds"))
  }

  @Test
  fun aggregateDecodedRomMessageLimitIsCheckedBeforePayloadReads() {
    val header =
        romHeader(
            decodedSizes =
                intArrayOf(
                    StateLimits.ROM.decodedBytes,
                    StateLimits.BATTERY.decodedBytes,
                    StateLimits.GAME_SNAPSHOT.decodedBytes,
                    StateLimits.SESSION_SNAPSHOT.decodedBytes,
                ),
            encodedSizes = intArrayOf(1, 1, 1, 1),
        )
    val connection = clientConnection(byteArrayOf(0x01) + header)

    val error = assertFailsWith<Connection.ProtocolException> { connection.run() }
    assertTrue(error.cause!!.message!!.contains("Decoded netplay ROM message exceeds"))
  }

  @Test
  fun aggregateLimitsAcceptBoundaryAndRejectPlusOneAndOverflow() {
    assertEquals(
        StateLimits.NETPLAY_ENCODED_MESSAGE_BYTES,
        Connection.checkedMessageSize(
            0,
            StateLimits.NETPLAY_ENCODED_MESSAGE_BYTES,
        ),
    )
    assertFailsWith<IOException> {
      Connection.checkedMessageSize(0, StateLimits.NETPLAY_ENCODED_MESSAGE_BYTES + 1)
    }
    assertFailsWith<IOException> { Connection.checkedMessageSize(Long.MAX_VALUE, 1) }

    Connection.checkedDecodedMessageSize(StateLimits.NETPLAY_DECODED_MESSAGE_BYTES)
    assertFailsWith<IOException> {
      Connection.checkedDecodedMessageSize(StateLimits.NETPLAY_DECODED_MESSAGE_BYTES, 1)
    }
  }

  @Test
  fun invalidEnumAndFlagDeclarationsAreProtocolErrors() {
    val invalidEnum = romHeader(gameboyType = 0xff)
    assertFailsWith<IOException> { clientConnection(byteArrayOf(0x01) + invalidEnum).run() }

    val invalidFlag = romHeader(cgb0Revision = 2)
    assertFailsWith<IOException> { clientConnection(byteArrayOf(0x01) + invalidFlag).run() }
  }

  @Test
  fun legacyJavaSnapshotFromPeerIsRejectedBeforeEventDelivery() {
    val received = LinkedBlockingQueue<Connection.PeerLoadedGameEvent>()
    receiverBus.register<Connection.PeerLoadedGameEvent> { received.add(it) }
    val rom = Connection.deflate(byteArrayOf(1), StateLimits.ROM)
    val legacyState = byteArrayOf(0xac.toByte(), 0xed.toByte(), 0x00, 0x05)
    val snapshot = Connection.deflate(legacyState, StateLimits.GAME_SNAPSHOT)
    val header =
        romHeader(
            decodedSizes = intArrayOf(1, 0, legacyState.size, 0),
            encodedSizes = intArrayOf(rom.size, 0, snapshot.size, 0),
        )
    val connection = clientConnection(byteArrayOf(0x01) + header + rom + snapshot)

    val error = assertFailsWith<Connection.ProtocolException> { connection.run() }
    assertTrue(error.message!!.contains("unsafe legacy Java save state"))
    assertEquals(null, received.poll())
  }

  @Test
  fun headerlessPeerSnapshotsAreRejectedBeforeEventDelivery() {
    for (sessionState in listOf(false, true)) {
      val received = LinkedBlockingQueue<Connection.PeerLoadedGameEvent>()
      receiverBus.register<Connection.PeerLoadedGameEvent> { received.add(it) }
      val rom = Connection.deflate(byteArrayOf(1), StateLimits.ROM)
      val unsupported = byteArrayOf(1, 2, 3, 4)
      val stateLimit = if (sessionState) StateLimits.SESSION_SNAPSHOT else StateLimits.GAME_SNAPSHOT
      val state = Connection.deflate(unsupported, stateLimit)
      val decodedSizes = intArrayOf(1, 0, 0, 0)
      val encodedSizes = intArrayOf(rom.size, 0, 0, 0)
      val index = if (sessionState) 3 else 2
      decodedSizes[index] = unsupported.size
      encodedSizes[index] = state.size
      val connection =
          clientConnection(
              byteArrayOf(0x01) +
                  romHeader(decodedSizes = decodedSizes, encodedSizes = encodedSizes) +
                  rom +
                  state)

      val error = assertFailsWith<Connection.ProtocolException> { connection.run() }
      assertEquals(Connection.ProtocolErrorReason.UNSUPPORTED_STATE_FORMAT, error.reason)
      assertEquals(null, received.poll())
    }
  }

  @Test
  fun semanticallyInvalidPortableSnapshotIsRejectedBeforeEventDelivery() {
    val received = LinkedBlockingQueue<Connection.PeerLoadedGameEvent>()
    receiverBus.register<Connection.PeerLoadedGameEvent> { received.add(it) }
    val romBytes = ROM.readBytes()
    val rom = Connection.deflate(romBytes, StateLimits.ROM)
    val invalidState = invalidPortableGameSnapshot()
    val snapshot = Connection.deflate(invalidState, StateLimits.GAME_SNAPSHOT)
    val header =
        romHeader(
            decodedSizes = intArrayOf(romBytes.size, 0, invalidState.size, 0),
            encodedSizes = intArrayOf(rom.size, 0, snapshot.size, 0),
        )
    val connection = clientConnection(byteArrayOf(0x01) + header + rom + snapshot)

    val error = assertFailsWith<Connection.ProtocolException> { connection.run() }
    assertEquals(Connection.ProtocolErrorReason.INVALID_PORTABLE_STATE, error.reason)
    assertEquals(null, received.poll())
  }

  @Test
  fun peerProtocolErrorHasAUserFacingReason() {
    val error =
        assertFailsWith<Connection.ProtocolException> {
          clientConnection(byteArrayOf(0x0a, 0x01)).run()
        }

    assertTrue(error.message!!.contains("portable state format"))
  }

  @Test
  fun deflateShrinksSparseData() {
    val sparse = ByteArray(1 shl 20)
    Random(1).nextBytes(sparse, 0, 64 * 1024)
    val compressed = Connection.deflate(sparse)
    assertTrue(
        compressed.size < sparse.size / 4,
        "expected sparse memento-like data to compress well, got ${compressed.size}")
    assertContentEquals(sparse, Connection.inflate(compressed, sparse.size))
  }

  private fun clientConnection(
      messages: ByteArray,
      mode: LinkMode = LinkMode.NORMAL,
  ): Connection {
    val handshake =
        "CoffeeGB NETPLAY".toByteArray() +
            byteArrayOf(0x07, mode.ordinal.toByte(), 0x01, 0x01)
    return Connection(
        ByteArrayInputStream(handshake + messages),
        ByteArrayOutputStream(),
        receiverBus,
        false,
    )
  }

  private fun establishSharedFrame(frame: Long) {
    senderBus.post(
        LinkedController.LocalRomLoadedEvent(
            ROM.readBytes(),
            null,
            null,
            GameboyType.DMG,
            Gameboy.BootstrapMode.SKIP,
            frame,
        ))
  }

  private fun portableStates(
      battery: ByteArray? = null,
      gameboyType: GameboyType = GameboyType.DMG,
      cgb0Revision: Boolean = false,
  ): Pair<ByteArray, ByteArray> {
    val config =
        Gameboy.GameboyConfiguration(Rom(ROM))
            .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
            .setBatteryData(battery)
            .setGameboyType(gameboyType)
            .setCgb0Revision(cgb0Revision)
    val gameBus = EventBusImpl()
    val gameboy = config.build()
    gameboy.init(
        gameBus,
        SerialEndpoint.NULL_ENDPOINT,
        InfraredEndpoint.NULL_ENDPOINT,
        null,
    )
    val game =
        try {
          NetplayMementoCodec.encodeGameboy(gameboy.saveToMemento())
        } finally {
          gameboy.stop()
          gameboy.close()
          gameBus.close()
        }
    val sessionBus = EventBusImpl()
    val session = Session(config, sessionBus, null, FourPlayerAdapter().endpoint(0))
    val sessionState =
        try {
          NetplayMementoCodec.encodeSession(session.saveToMemento())
        } finally {
          session.close()
          sessionBus.close()
        }
    return game to sessionState
  }

  private fun invalidPortableGameSnapshot(): ByteArray {
    val bus = EventBusImpl()
    val gameboy =
        Gameboy.GameboyConfiguration(Rom(ROM))
            .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
            .build()
    gameboy.init(bus, SerialEndpoint.NULL_ENDPOINT, InfraredEndpoint.NULL_ENDPOINT, null)
    return try {
      val memento = gameboy.saveToMemento()
      val mmu = recordComponent(memento, "mmuMemento")!!
      val invalidMmu = replaceRecordComponent(mmu, "ramC000Memento", Ram.RamMemento(IntArray(0)))
      @Suppress("UNCHECKED_CAST")
      NetplayMementoCodec.encodeGameboy(
          replaceRecordComponent(memento, "mmuMemento", invalidMmu) as Memento<Gameboy>)
    } finally {
      gameboy.stop()
      gameboy.close()
      bus.close()
    }
  }

  private fun recordComponent(record: Any, name: String): Any? =
      record.javaClass.recordComponents.single { it.name == name }.accessor.let { accessor ->
        accessor.isAccessible = true
        accessor.invoke(record)
      }

  private fun replaceRecordComponent(record: Any, name: String, replacement: Any?): Any {
    val components = record.javaClass.recordComponents
    val constructor =
        record.javaClass.getDeclaredConstructor(*components.map { it.type }.toTypedArray()).also {
          it.isAccessible = true
        }
    val arguments =
        components.map { component ->
          if (component.name == name) {
            replacement
          } else {
            component.accessor.let { accessor ->
              accessor.isAccessible = true
              accessor.invoke(record)
            }
          }
        }.toTypedArray()
    return constructor.newInstance(*arguments)
  }

  private fun romHeader(
      gameboyType: Int = GameboyType.DMG.ordinal,
      cgb0Revision: Int = 0,
      decodedSizes: IntArray = intArrayOf(1, 0, 0, 0),
      encodedSizes: IntArray = intArrayOf(1, 0, 0, 0),
  ): ByteArray {
    val header = ByteBuffer.allocate(45)
    header.put(0)
    header.putLong(0)
    header.put(gameboyType.toByte())
    header.put(Gameboy.BootstrapMode.SKIP.ordinal.toByte())
    header.put(cgb0Revision.toByte())
    header.put(0)
    decodedSizes.forEach(header::putInt)
    encodedSizes.forEach(header::putInt)
    return header.array()
  }

  private companion object {
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()
  }
}
