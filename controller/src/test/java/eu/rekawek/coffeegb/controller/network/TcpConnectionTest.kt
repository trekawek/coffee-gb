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
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** End-to-end exchange over real TCP sockets on the loopback interface. */
class TcpConnectionTest {

  private val serverBus = EventBusImpl()

  private val clientBus = EventBusImpl()

  private var server: TcpServer? = null

  private var client: TcpClient? = null

  private val threads = mutableListOf<Thread>()

  @After
  fun tearDown() {
    client?.stop()
    server?.stop()
    threads.forEach { it.join(3000) }
    serverBus.close()
    clientBus.close()
  }

  @Test
  fun messagesTravelBothWaysOverTcp() {
    val port = ServerSocket(0).use { it.localPort }

    val serverStarted = LinkedBlockingQueue<ConnectionController.ServerStartedEvent>()
    val serverGotConnection = LinkedBlockingQueue<ConnectionController.ServerGotConnectionEvent>()
    serverBus.register<ConnectionController.ServerStartedEvent> { serverStarted.add(it) }
    serverBus.register<ConnectionController.ServerGotConnectionEvent> { serverGotConnection.add(it) }

    val serverReceivedButtons = LinkedBlockingQueue<LinkedController.RemoteButtonStateEvent>()
    serverBus.register<LinkedController.RemoteButtonStateEvent> { serverReceivedButtons.add(it) }
    val clientReceivedGame = LinkedBlockingQueue<Connection.PeerLoadedGameEvent>()
    clientBus.register<Connection.PeerLoadedGameEvent> { clientReceivedGame.add(it) }

    val server = TcpServer(serverBus, port)
    this.server = server
    threads += Thread(server).also { it.start() }
    assertNotNull(serverStarted.poll(5, TimeUnit.SECONDS), "server did not start")

    val client = TcpClient("localhost:$port", clientBus)
    this.client = client
    threads += Thread(client).also { it.start() }
    assertNotNull(serverGotConnection.poll(5, TimeUnit.SECONDS), "client did not connect")

    // server -> client: game load with compressed payloads; the Connection registers
    // its handlers shortly after the accept, so retry until the message goes through
    val rom = ByteArray(128 * 1024)
    Random(2).nextBytes(rom, 0, 16 * 1024)
    var game: Connection.PeerLoadedGameEvent? = null
    for (attempt in 0 until 20) {
      serverBus.post(
          LinkedController.LocalRomLoadedEvent(
              rom, null, null, GameboyType.DMG, Gameboy.BootstrapMode.FAST_FORWARD, 11))
      game = clientReceivedGame.poll(500, TimeUnit.MILLISECONDS)
      if (game != null) {
        break
      }
    }
    assertNotNull(game, "game not received")
    assertContentEquals(rom, game.rom)
    assertEquals(11, game.frame)

    // client -> server: a burst of input messages
    repeat(20) { i ->
      clientBus.post(
          LinkedController.LocalButtonStateEvent(
              i.toLong(), Input(listOf(Button.entries[i % Button.entries.size]), emptyList())))
    }
    repeat(20) { i ->
      val e = assertNotNull(serverReceivedButtons.poll(5, TimeUnit.SECONDS), "input ${i + 1} lost")
      assertEquals(i.toLong(), e.frame)
      assertEquals(listOf(Button.entries[i % Button.entries.size]), e.input.pressedButtons)
    }
  }
}
