package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.LinkMode
import eu.rekawek.coffeegb.controller.link.LinkedController
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.joypad.Button
import org.junit.After
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
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
    // run() performs the handshake and then reads; both sides need it running
    threads += Thread { sender.run() }.also { it.start() }
    threads += Thread { receiver.run() }.also { it.start() }
    if (startSession) sender.startSession()
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

    // realistic shape: mostly sparse data compresses, random data does not inflate badly
    val rom = ByteArray(256 * 1024)
    Random(0).nextBytes(rom, 0, 32 * 1024)
    val battery = ByteArray(8 * 1024) { (it % 7).toByte() }
    val snapshot = ByteArray(64 * 1024)
    val sessionSnapshot = ByteArray(32 * 1024) { (it % 11).toByte() }

    senderBus.post(
        LinkedController.LocalRomLoadedEvent(
            rom,
            battery,
            snapshot,
            GameboyType.CGB,
            Gameboy.BootstrapMode.FAST_FORWARD,
            7,
            cgb0Revision = true,
            sessionSnapshot = sessionSnapshot,
            heldButtons = setOf(Button.A, Button.LEFT),
        )
    )

    val event = received.poll(10, TimeUnit.SECONDS)
    assertNotNull(event)
    assertContentEquals(rom, event.rom)
    assertContentEquals(battery, event.battery)
    assertContentEquals(snapshot, event.snapshot)
    assertContentEquals(sessionSnapshot, event.sessionSnapshot)
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
            romFile = byteArrayOf(1, 2, 3),
            batteryFile = null,
            snapshot = null,
            gameboyType = GameboyType.DMG,
            bootstrapMode = Gameboy.BootstrapMode.SKIP,
            frame = 73,
            player = 0,
            sessionSnapshot = byteArrayOf(4, 5, 6),
            heldButtons = setOf(Button.START),
        )
    senderBus.post(LinkedController.SessionStateReadyEvent(73, listOf(state)))

    assertEquals(null, received.poll(200, TimeUnit.MILLISECONDS))
    val checkpoint = assertNotNull(synchronized.poll(5, TimeUnit.SECONDS))
    assertEquals(1, checkpoint.states.size)
    val game = checkpoint.states.single()
    assertContentEquals(byteArrayOf(4, 5, 6), game.sessionSnapshot)
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
            byteArrayOf(1, 2, 3),
            null,
            null,
            GameboyType.DMG,
            Gameboy.BootstrapMode.SKIP,
            0,
        ))
    assertEquals(null, received.poll(200, TimeUnit.MILLISECONDS))

    sender!!.startSession()
    assertContentEquals(
        byteArrayOf(1, 2, 3),
        assertNotNull(received.poll(5, TimeUnit.SECONDS)).rom,
    )
  }

  @Test
  fun resetAndStopRoundTrip() {
    val resets = LinkedBlockingQueue<Connection.ReceivedRemoteResetEvent>()
    val stops = LinkedBlockingQueue<Connection.ReceivedRemoteStopEvent>()
    receiverBus.register<Connection.ReceivedRemoteResetEvent> { resets.add(it) }
    receiverBus.register<Connection.ReceivedRemoteStopEvent> { stops.add(it) }
    connect()

    senderBus.post(Connection.RequestResetEvent(123456789L))
    senderBus.post(Connection.RequestStopEvent(987654321L))

    assertEquals(123456789L, assertNotNull(resets.poll(5, TimeUnit.SECONDS)).frame)
    assertEquals(987654321L, assertNotNull(stops.poll(5, TimeUnit.SECONDS)).frame)
  }

  @Test
  fun concurrentSendersDoNotInterleaveMessages() {
    val received = LinkedBlockingQueue<LinkedController.RemoteButtonStateEvent>()
    receiverBus.register<LinkedController.RemoteButtonStateEvent> { received.add(it) }
    connect()

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
  fun inflateRejectsTruncatedPayload() {
    val data = ByteArray(4096) { (it % 13).toByte() }
    val compressed = Connection.deflate(data)
    val truncated = compressed.copyOf(compressed.size / 2)
    assertFailsWith<IOException> { Connection.inflate(truncated, data.size) }
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
}
