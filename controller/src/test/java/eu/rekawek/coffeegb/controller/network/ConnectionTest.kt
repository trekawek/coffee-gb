package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.LinkedController
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.joypad.Button
import org.junit.After
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
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

  private val senderBus = EventBusImpl()

  private val receiverBus = EventBusImpl()

  private var sender: Connection? = null

  private var receiver: Connection? = null

  private val threads = mutableListOf<Thread>()

  private fun connect() {
    val toReceiver = PipedOutputStream()
    val fromSender = PipedInputStream(toReceiver, 1 shl 20)
    val toSender = PipedOutputStream()
    val fromReceiver = PipedInputStream(toSender, 1 shl 20)

    val sender = Connection(fromReceiver, toReceiver, senderBus, true)
    val receiver = Connection(TrickleInputStream(fromSender), toSender, receiverBus, false)
    this.sender = sender
    this.receiver = receiver
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

    senderBus.post(
        LinkedController.LocalRomLoadedEvent(
            rom, battery, snapshot, GameboyType.CGB, Gameboy.BootstrapMode.FAST_FORWARD, 7))

    val event = received.poll(10, TimeUnit.SECONDS)
    assertNotNull(event)
    assertContentEquals(rom, event.rom)
    assertContentEquals(battery, event.battery)
    assertContentEquals(snapshot, event.snapshot)
    assertEquals(GameboyType.CGB, event.gameboyType)
    assertEquals(Gameboy.BootstrapMode.FAST_FORWARD, event.bootstrapMode)
    assertEquals(7, event.frame)
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
    val toReceiver = PipedOutputStream()
    val fromSender = PipedInputStream(toReceiver, 1 shl 16)
    val toSender = PipedOutputStream()
    PipedInputStream(toSender, 1 shl 16)

    // the client-side handshake happens during construction
    toReceiver.write("CoffeeGB WRONG!!".toByteArray() + byteArrayOf(0x02))
    toReceiver.flush()
    assertFailsWith<IOException> { Connection(fromSender, toSender, receiverBus, false) }
  }

  @Test
  fun handshakeRejectsWrongVersion() {
    val toReceiver = PipedOutputStream()
    val fromSender = PipedInputStream(toReceiver, 1 shl 16)
    val toSender = PipedOutputStream()
    PipedInputStream(toSender, 1 shl 16)

    toReceiver.write("CoffeeGB NETPLAY".toByteArray() + byteArrayOf(0x7f))
    toReceiver.flush()
    assertFailsWith<IOException> { Connection(fromSender, toSender, receiverBus, false) }
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
