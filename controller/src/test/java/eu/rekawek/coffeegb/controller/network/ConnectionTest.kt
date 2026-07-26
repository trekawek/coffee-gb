package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.controller.events.EventQueue
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.LinkMode
import eu.rekawek.coffeegb.controller.link.LinkedController
import eu.rekawek.coffeegb.controller.state.MachineState
import eu.rekawek.coffeegb.controller.state.MachineStateRoot
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateCompression
import eu.rekawek.coffeegb.controller.state.StateFile
import eu.rekawek.coffeegb.controller.state.StateGraph
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.state.ComponentState
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
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConnectionTest {

  /** Fails the test if the connection attempts to read beyond the supplied declaration bytes. */
  private class PayloadTripwireInputStream(private val prefix: ByteArray) : InputStream() {
    private var offset = 0

    override fun read(): Int {
      if (offset == prefix.size) {
        throw AssertionError("payload read occurred after a rejected declaration")
      }
      return prefix[offset++].toInt() and 0xff
    }

    override fun read(bytes: ByteArray, off: Int, len: Int): Int {
      if (len == 0) return 0
      if (offset == prefix.size) {
        throw AssertionError("payload read occurred after a rejected declaration")
      }
      val count = minOf(len, prefix.size - offset)
      prefix.copyInto(bytes, off, offset, offset + count)
      offset += count
      return count
    }
  }

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
        portableStates(
                battery,
                GameboyType.CGB,
                cgb0Revision = true,
                bootstrapMode = Gameboy.BootstrapMode.FAST_FORWARD,
            )
            .first
    assertContentEquals("CGBS".toByteArray(), snapshot.copyOfRange(0, 4))
    assertEquals(
        eu.rekawek.coffeegb.controller.state.StateRootKind.MACHINE,
        StateCodec.inspect(snapshot).rootKind,
    )

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
    assertEquals(StateCodec.decode(snapshot), event.portableState)
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
            portableState = portableStates(heldButtons = setOf(Button.START)).second,
            gameboyType = GameboyType.DMG,
            bootstrapMode = Gameboy.BootstrapMode.SKIP,
            frame = 73,
            player = 0,
            heldButtons = setOf(Button.START),
        )
    assertContentEquals(
        "CGBS".toByteArray(),
        checkNotNull(state.portableState).copyOfRange(0, 4),
    )
    assertEquals(
        eu.rekawek.coffeegb.controller.state.StateRootKind.SESSION,
        StateCodec.inspect(state.portableState).rootKind,
    )
    senderBus.post(LinkedController.SessionStateReadyEvent(73, listOf(state)))

    assertEquals(null, received.poll(200, TimeUnit.MILLISECONDS))
    val checkpoint = assertNotNull(synchronized.poll(5, TimeUnit.SECONDS))
    assertEquals(1, checkpoint.states.size)
    val game = checkpoint.states.single()
    assertEquals(StateCodec.decode(checkNotNull(state.portableState)), game.portableState)
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
            ByteArrayInputStream(capabilities()),
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
        capabilities() +
            byteArrayOf(0x01) +
            romHeader(
                romDecoded = romBytes.size,
                romEncoded = rom.size,
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
            ByteArrayInputStream(capabilities() + inputMessage + inputMessage + inputMessage),
            ByteArrayOutputStream(),
            receiverBus,
            true,
            LinkMode.FOUR_PLAYER_ADAPTER,
            assignedPlayer = 1,
        )
    val honest =
        Connection(
            ByteArrayInputStream(capabilities() + inputMessage),
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
    val checkpoint =
        byteArrayOf(0x01) +
            romHeader(
                romDecoded = romBytes.size,
                romEncoded = rom.size,
                stateBytes = sessionBytes.size,
            ) +
            sessionBytes +
            rom +
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
  fun handshakeRequiresProtocolV8StateFileV1CapabilityInBothDirections() {
    val serverHandshake =
        "CoffeeGB NETPLAY".toByteArray() +
            byteArrayOf(0x08, LinkMode.NORMAL.ordinal.toByte(), 0x01) +
            capabilities(stateFile = 2)
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
            ByteArrayInputStream(capabilities(stateFile = 2)),
            ByteArrayOutputStream(),
            receiverBus,
            true,
        )
    assertFailsWith<Connection.CompatibilityException> { server.run() }
  }

  @Test
  fun handshakeRejectsV7AndTruncatedCapabilitiesBeforeCommandsInBothDirections() {
    val clientPreambles =
        listOf(
            "CoffeeGB NETPLAY".toByteArray() +
                byteArrayOf(0x07, LinkMode.NORMAL.ordinal.toByte(), 0x01),
            "CoffeeGB NETPLAY".toByteArray() +
                byteArrayOf(0x08, LinkMode.NORMAL.ordinal.toByte(), 0x01, 0x08, 0x01),
        )
    clientPreambles.forEach { preamble ->
      assertFailsWith<Connection.CompatibilityException> {
        Connection(
            ByteArrayInputStream(preamble),
            ByteArrayOutputStream(),
            receiverBus,
            false,
        )
      }
    }

    listOf(byteArrayOf(0x01, 0x03), byteArrayOf(0x08, 0x01)).forEach { response ->
      val server =
          Connection(
              ByteArrayInputStream(response),
              ByteArrayOutputStream(),
              receiverBus,
              true,
          )
      assertFailsWith<Connection.CompatibilityException> { server.run() }
    }
  }

  @Test
  fun directStateDeclarationBoundaryAndPayloadTripwire() {
    assertEquals(
        StateLimits.NETPLAY_STATE_FILE_BYTES,
        Connection.validateStateFileDeclaration(StateLimits.NETPLAY_STATE_FILE_BYTES)!!.wireBytes,
    )
    for (invalid in listOf(-1, StateLimits.NETPLAY_STATE_FILE_BYTES + 1, Int.MAX_VALUE)) {
      assertFailsWith<IOException> { Connection.validateStateFileDeclaration(invalid) }
    }
    val maxEncoded = StateLimits.NETPLAY_STATE_FILE_BYTES - StateCodec.HEADER_SIZE
    assertEquals(
        StateLimits.NETPLAY_STATE_FILE_DECODED_BYTES,
        Connection.validateNetworkStateLengths(
            maxEncoded.toLong(),
            StateLimits.NETPLAY_STATE_FILE_DECODED_BYTES.toLong(),
            StateLimits.NETPLAY_STATE_FILE_BYTES,
        ),
    )
    listOf(
            Triple(maxEncoded.toLong() + 1, 1L, StateLimits.NETPLAY_STATE_FILE_BYTES),
            Triple(1L, StateLimits.NETPLAY_STATE_FILE_DECODED_BYTES.toLong() + 1, 69),
            Triple(Long.MAX_VALUE, 1L, StateLimits.NETPLAY_STATE_FILE_BYTES),
            Triple(1L, 1L, StateCodec.HEADER_SIZE),
        )
        .forEach { (encoded, decoded, wire) ->
          assertFailsWith<IOException> {
            Connection.validateNetworkStateLengths(encoded, decoded, wire)
          }
        }

    val prefix =
        "CoffeeGB NETPLAY".toByteArray() +
            byteArrayOf(0x08, LinkMode.NORMAL.ordinal.toByte(), 0x01) +
            capabilities() +
            byteArrayOf(0x01) +
            romHeader(stateBytes = StateLimits.NETPLAY_STATE_FILE_BYTES + 1)
    val connection =
        Connection(
            PayloadTripwireInputStream(prefix),
            ByteArrayOutputStream(),
            receiverBus,
            false,
        )
    val error = assertFailsWith<Connection.ProtocolException> { connection.run() }
    assertEquals(Connection.ProtocolErrorReason.MALFORMED_MESSAGE, error.reason)
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
            romEncoded = StateLimits.ROM.encodedBytes,
            slotDecoded = 1,
            slotEncoded = StateLimits.ROM.encodedBytes,
            batteryDecoded = 1,
            batteryEncoded = StateLimits.BATTERY.encodedBytes,
            stateBytes = StateLimits.NETPLAY_STATE_FILE_BYTES,
        )
    val connection = tripwireClient(byteArrayOf(0x01) + header)

    val error = assertFailsWith<Connection.ProtocolException> { connection.run() }
    assertTrue(error.cause!!.message!!.contains("message exceeds"))
  }

  @Test
  fun aggregateDecodedRomMessageLimitIsCheckedBeforePayloadReads() {
    val header =
        romHeader(
            romDecoded = StateLimits.ROM.decodedBytes,
            slotDecoded = StateLimits.ROM.decodedBytes,
            slotEncoded = 1,
            batteryDecoded = StateLimits.BATTERY.decodedBytes,
            batteryEncoded = 1,
            stateBytes = 1,
        )
    val connection = tripwireClient(byteArrayOf(0x01) + header)

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

    assertEquals(
        StateLimits.NETPLAY_DECODED_MESSAGE_BYTES.toLong(),
        Connection.checkedPendingStateBytes(
            StateLimits.NETPLAY_DECODED_MESSAGE_BYTES.toLong() - 1,
            1,
        ),
    )
    listOf(
            -1L to 0L,
            0L to -1L,
            StateLimits.NETPLAY_DECODED_MESSAGE_BYTES.toLong() to 1L,
            Long.MAX_VALUE to 1L,
        )
        .forEach { (retained, incoming) ->
          assertFailsWith<IOException> {
            Connection.checkedPendingStateBytes(retained, incoming)
          }
        }
  }

  @Test
  fun invalidEnumAndFlagDeclarationsAreProtocolErrors() {
    val invalidEnum = romHeader(gameboyType = 0xff)
    assertFailsWith<IOException> { clientConnection(byteArrayOf(0x01) + invalidEnum).run() }

    val invalidFlag = romHeader(profileFlags = 1 shl 4)
    assertFailsWith<IOException> { clientConnection(byteArrayOf(0x01) + invalidFlag).run() }
  }

  @Test
  fun legacyJavaSnapshotFromPeerIsRejectedBeforeEventDelivery() {
    val received = LinkedBlockingQueue<Connection.PeerLoadedGameEvent>()
    receiverBus.register<Connection.PeerLoadedGameEvent> { received.add(it) }
    val rom = Connection.deflate(byteArrayOf(1), StateLimits.ROM)
    val legacyState = byteArrayOf(0xac.toByte(), 0xed.toByte(), 0x00, 0x05)
    val header =
        romHeader(
            romEncoded = rom.size,
            stateBytes = legacyState.size,
        )
    val connection = clientConnection(byteArrayOf(0x01) + header + legacyState + rom)

    val error = assertFailsWith<Connection.ProtocolException> { connection.run() }
    assertEquals(Connection.ProtocolErrorReason.UNSUPPORTED_STATE_FORMAT, error.reason)
    assertEquals(null, received.poll())
  }

  @Test
  fun headerlessPeerSnapshotsAreRejectedBeforeEventDelivery() {
    for (unsupported in listOf(byteArrayOf(1, 2, 3, 4), "CGBN".toByteArray())) {
      val received = LinkedBlockingQueue<Connection.PeerLoadedGameEvent>()
      receiverBus.register<Connection.PeerLoadedGameEvent> { received.add(it) }
      val rom = Connection.deflate(byteArrayOf(1), StateLimits.ROM)
      val connection =
          clientConnection(
              byteArrayOf(0x01) +
                  romHeader(romEncoded = rom.size, stateBytes = unsupported.size) +
                  unsupported +
                  rom)

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
    val header =
        romHeader(
            romDecoded = romBytes.size,
            romEncoded = rom.size,
            stateBytes = invalidState.size,
        )
    val connection = clientConnection(byteArrayOf(0x01) + header + invalidState + rom)

    val error = assertFailsWith<Connection.ProtocolException> { connection.run() }
    assertEquals(Connection.ProtocolErrorReason.INVALID_PORTABLE_STATE, error.reason)
    assertEquals(null, received.poll())
  }

  @Test
  fun corruptTruncatedFutureAndWrongRootStateFilesAreRejectedBeforeDelivery() {
    val received = LinkedBlockingQueue<Connection.PeerLoadedGameEvent>()
    receiverBus.register<Connection.PeerLoadedGameEvent> { received.add(it) }
    val (machine, session) = portableStates()
    val cases =
        listOf(
            "truncated" to "CGBS".toByteArray(),
            "future version" to machine.clone().also { it[5] = 2 },
            "corrupt checksum" to machine.clone().also {
              it[it.lastIndex] = (it.last().toInt() xor 0x55).toByte()
            },
            "wrong root" to session,
        )

    cases.forEach { (description, state) ->
      val connection = clientConnection(networkRomMessage(state))
      val error = assertFailsWith<Connection.ProtocolException>(description) { connection.run() }
      assertEquals(Connection.ProtocolErrorReason.INVALID_PORTABLE_STATE, error.reason, description)
      if (description == "future version") {
        assertTrue(error.message!!.contains("version 2; supported 1"))
      }
      assertEquals(null, received.poll(), description)
    }
  }

  @Test
  fun protocolV8RejectsExactSgbFamilyBeforeWritingAnyStateBearingMessage() {
    for (profile in listOf(HardwareProfileRegistry.SGB, HardwareProfileRegistry.SGB2)) {
      val localBus = EventBusImpl()
      val handshake =
          "CoffeeGB NETPLAY".toByteArray() +
              byteArrayOf(0x08, LinkMode.NORMAL.ordinal.toByte(), 0x01) +
              capabilities()
      val output = ByteArrayOutputStream()
      val connection =
          Connection(ByteArrayInputStream(handshake), output, localBus, false)
      val before = output.toByteArray()
      val configuration =
          Gameboy.GameboyConfiguration(Rom(ROM))
              .setHardwareProfile(profile)
              .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
              .setSupportBatterySave(false)
      val state = portableMachine(configuration)
      assertEquals(2, StateCodec.inspect(state).formatVersion)

      localBus.post(
          LinkedController.LocalRomLoadedEvent(
              ROM.readBytes(),
              null,
              state,
              GameboyType.SGB,
              Gameboy.BootstrapMode.SKIP,
              0,
              hardwareProfileId = profile.id(),
              player = 1,
          ))

      assertContentEquals(before, output.toByteArray(), profile.id())
      connection.stop()
      localBus.close()
    }
  }

  @Test
  fun protocolV8RejectsCoarseSgbHeaderBeforeReadingAnyPayload() {
    val received = LinkedBlockingQueue<Connection.PeerLoadedGameEvent>()
    receiverBus.register<Connection.PeerLoadedGameEvent> { received.add(it) }
    val header =
        romHeader(
            gameboyType = GameboyType.SGB.ordinal,
            romDecoded = 1,
            romEncoded = 1,
            stateBytes = 1,
        )

    val error =
        assertFailsWith<Connection.ProtocolException> {
          tripwireClient(byteArrayOf(0x01) + header).run()
        }

    assertEquals(Connection.ProtocolErrorReason.UNSUPPORTED_STATE_FORMAT, error.reason)
    assertTrue(error.message!!.contains("StateFile v2"))
    assertEquals(null, received.poll())
  }

  @Test
  fun romAndHardwareProfileIdentityMismatchAreRejectedBeforeDelivery() {
    val received = LinkedBlockingQueue<Connection.PeerLoadedGameEvent>()
    receiverBus.register<Connection.PeerLoadedGameEvent> { received.add(it) }
    val machine = portableStates().first
    val wrongRom = ROM.readBytes().clone().also { it[it.lastIndex] = (it.last() + 1).toByte() }
    val wrongRomError =
        assertFailsWith<Connection.ProtocolException> {
          clientConnection(networkRomMessage(machine, wrongRom)).run()
        }
    assertEquals(Connection.ProtocolErrorReason.INVALID_PORTABLE_STATE, wrongRomError.reason)

    val cgb0Machine = portableStates(gameboyType = GameboyType.CGB, cgb0Revision = true).first
    val wrongProfileError =
        assertFailsWith<Connection.ProtocolException> {
          clientConnection(
                  networkRomMessage(
                      cgb0Machine,
                      gameboyType = GameboyType.CGB,
                      profileFlags = 0,
                  ))
              .run()
        }
    assertEquals(Connection.ProtocolErrorReason.INVALID_PORTABLE_STATE, wrongProfileError.reason)
    assertEquals(null, received.poll())
  }

  @Test
  fun datelSlotPresenceAndHashMismatchAreRejectedBeforeDelivery() {
    val datel = datelRom()
    val slot = slotRom(seed = 3)
    val configuration =
        Gameboy.GameboyConfiguration(Rom(datel))
            .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
            .setGameboyType(GameboyType.CGB)
            .setSlotRom(Rom(slot))
            .setSupportBatterySave(false)
    val state = portableMachine(configuration)

    for (wrongSlot in listOf(null, slotRom(seed = 4))) {
      val error =
          assertFailsWith<Connection.ProtocolException> {
            clientConnection(
                    networkRomMessage(
                        state,
                        datel,
                        gameboyType = GameboyType.CGB,
                        slotRomBytes = wrongSlot,
                    ))
                .run()
          }
      assertEquals(Connection.ProtocolErrorReason.INVALID_PORTABLE_STATE, error.reason)
    }
  }

  @Test
  fun peerProtocolErrorHasAUserFacingReason() {
    val error =
        assertFailsWith<Connection.ProtocolException> {
          clientConnection(byteArrayOf(0x0a, 0x02)).run()
        }

    assertEquals(Connection.ProtocolErrorReason.UNSUPPORTED_STATE_FORMAT, error.reason)
  }

  @Test
  fun productionPeerStatePathHasNoLegacyOrNativeDecoderReachability() {
    val networkSources =
        listOf(
            Paths.get(
                "src/main/java/eu/rekawek/coffeegb/controller/network/Connection.kt"),
            Paths.get(
                "src/main/java/eu/rekawek/coffeegb/controller/link/LinkedController.kt"),
        )
    val forbidden =
        listOf(
            "LegacySnapshotImporter",
            "NetplayMementoCodec",
            "ObjectInputStream",
            "ObjectOutputStream",
            "CGBN",
        )
    networkSources.forEach { path ->
      val source = path.toFile().readText()
      forbidden.forEach { token ->
        assertFalse(source.contains(token), "$path reaches forbidden peer decoder token $token")
      }
    }
    assertFalse(
        Paths.get(
                "src/main/java/eu/rekawek/coffeegb/controller/NetplayMementoCodec.kt")
            .toFile()
            .exists())
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
            byteArrayOf(0x08, mode.ordinal.toByte(), 0x01) +
            capabilities()
    return Connection(
        ByteArrayInputStream(handshake + messages),
        ByteArrayOutputStream(),
        receiverBus,
        false,
    )
  }

  private fun tripwireClient(
      messages: ByteArray,
      mode: LinkMode = LinkMode.NORMAL,
  ): Connection {
    val handshake =
        "CoffeeGB NETPLAY".toByteArray() +
            byteArrayOf(0x08, mode.ordinal.toByte(), 0x01) +
            capabilities()
    return Connection(
        PayloadTripwireInputStream(handshake + messages),
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

  private fun networkRomMessage(
      state: ByteArray,
      romBytes: ByteArray = ROM.readBytes(),
      gameboyType: GameboyType = GameboyType.DMG,
      profileFlags: Int = 0,
      slotRomBytes: ByteArray? = null,
  ): ByteArray {
    val rom = Connection.deflate(romBytes, StateLimits.ROM)
    val slot = slotRomBytes?.let { Connection.deflate(it, StateLimits.ROM) }
    return byteArrayOf(0x01) +
        romHeader(
            gameboyType = gameboyType.ordinal,
            profileFlags = profileFlags,
            romDecoded = romBytes.size,
            romEncoded = rom.size,
            slotDecoded = slotRomBytes?.size ?: 0,
            slotEncoded = slot?.size ?: 0,
            stateBytes = state.size,
        ) +
        state +
        rom +
        (slot ?: byteArrayOf())
  }

  private fun capabilities(
      protocol: Int = 8,
      negotiation: Int = 1,
      stateFile: Int = 1,
      roots: Int = 7,
  ) =
      byteArrayOf(
          protocol.toByte(),
          negotiation.toByte(),
          stateFile.toByte(),
          roots.toByte(),
      )

  private fun portableStates(
      battery: ByteArray? = null,
      gameboyType: GameboyType = GameboyType.DMG,
      cgb0Revision: Boolean = false,
      heldButtons: Set<Button> = emptySet(),
      bootstrapMode: Gameboy.BootstrapMode = Gameboy.BootstrapMode.SKIP,
  ): Pair<ByteArray, ByteArray> {
    val config =
        Gameboy.GameboyConfiguration(Rom(ROM))
            .setBootstrapMode(bootstrapMode)
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
          StateCodec.encode(
              StateCodec.capture(config, gameboy),
              StateCompression.DEFLATE,
          )
        } finally {
          gameboy.stop()
          gameboy.close()
          gameBus.close()
        }
    val sessionBus = EventBusImpl()
    val session = Session(config, sessionBus, null, FourPlayerAdapter().endpoint(0))
    session.heldButtons = heldButtons
    val sessionState =
        try {
          StateCodec.encode(
              StateCodec.capture(session),
              StateCompression.DEFLATE,
          )
        } finally {
          session.close()
          sessionBus.close()
        }
    return game to sessionState
  }

  private fun portableMachine(configuration: Gameboy.GameboyConfiguration): ByteArray {
    val bus = EventBusImpl()
    val gameboy = configuration.build()
    gameboy.init(bus, SerialEndpoint.NULL_ENDPOINT, InfraredEndpoint.NULL_ENDPOINT, null)
    return try {
      StateCodec.encode(
          StateCodec.capture(configuration, gameboy),
          StateCompression.DEFLATE,
      )
    } finally {
      gameboy.stop()
      gameboy.close()
      bus.close()
    }
  }

  private fun datelRom(): ByteArray =
      ByteArray(0x20000).also {
        it[0x100] = 0x00
        it[0x101] = 0xc3.toByte()
        it[0x104] = 0x44
        it[0x147] = 0x00
        it[0x148] = 0x02
      }

  private fun slotRom(seed: Int): ByteArray =
      ByteArray(0x8000).also {
        intArrayOf(0xce, 0xed, 0x66, 0x66, 0xcc, 0x0d).forEachIndexed { index, value ->
          it[0x104 + index] = value.toByte()
        }
        it[0x100] = 0x18
        it[0x101] = 0xfe.toByte()
        it[0x134] = seed.toByte()
        it[0x147] = 0x08
        it[0x148] = 0x00
        it[0x149] = 0x02
      }

  private fun invalidPortableGameSnapshot(): ByteArray {
    val bus = EventBusImpl()
    val config =
        Gameboy.GameboyConfiguration(Rom(ROM))
            .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
    val gameboy = config.build()
    gameboy.init(bus, SerialEndpoint.NULL_ENDPOINT, InfraredEndpoint.NULL_ENDPOINT, null)
    return try {
      val validFile = StateCodec.capture(config, gameboy)
      val validMachine = (validFile.root as MachineStateRoot).machine
      val memento = gameboy.captureState()
      val mmu = recordComponent(memento, "mmuMemento")!!
      val invalidMmu = replaceRecordComponent(mmu, "ramC000Memento", Ram.RamState(IntArray(0)))
      @Suppress("UNCHECKED_CAST")
      val invalid =
          replaceRecordComponent(memento, "mmuMemento", invalidMmu) as ComponentState<Gameboy>
      val invalidRoot =
          StateGraph.captureRoot(
              invalid,
              "eu.rekawek.coffeegb.core.Gameboy\$GameboyState",
          )
      StateCodec.encode(
          StateFile(
              validFile.identities,
              MachineStateRoot(
                  MachineState(
                      invalidRoot,
                      validMachine.rtcRuntime,
                      validMachine.hardware,
                      validMachine.dmgFifoRuntime,
                  )),
          ),
          StateCompression.DEFLATE,
      )
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
      profileFlags: Int = 0,
      romDecoded: Int = 1,
      romEncoded: Int = 1,
      slotDecoded: Int = 0,
      slotEncoded: Int = 0,
      batteryDecoded: Int = 0,
      batteryEncoded: Int = 0,
      stateBytes: Int = 0,
  ): ByteArray {
    val header = ByteBuffer.allocate(Connection.ROM_HEADER_SIZE)
    header.put(0)
    header.putLong(0)
    header.put(gameboyType.toByte())
    header.put(Gameboy.BootstrapMode.SKIP.ordinal.toByte())
    header.putInt(profileFlags)
    header.put(0)
    header.putInt(romDecoded)
    header.putInt(romEncoded)
    header.putInt(slotDecoded)
    header.putInt(slotEncoded)
    header.putInt(batteryDecoded)
    header.putInt(batteryEncoded)
    header.putInt(stateBytes)
    return header.array()
  }

  private companion object {
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()
  }
}
