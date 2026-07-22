package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.LinkedController
import eu.rekawek.coffeegb.controller.link.LinkMode
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

  private val extraClients = mutableListOf<TcpClient>()

  private val extraBuses = mutableListOf<EventBusImpl>()

  private val threads = mutableListOf<Thread>()

  @After
  fun tearDown() {
    client?.stop()
    extraClients.forEach { it.stop() }
    server?.stop()
    threads.forEach { it.join(3000) }
    serverBus.close()
    clientBus.close()
    extraBuses.forEach { it.close() }
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
              i.toLong(),
              Input(listOf(Button.entries[i % Button.entries.size]), emptyList()),
              player = 1,
          ))
    }
    repeat(20) { i ->
      val e = assertNotNull(serverReceivedButtons.poll(5, TimeUnit.SECONDS), "input ${i + 1} lost")
      assertEquals(i.toLong(), e.frame)
      assertEquals(listOf(Button.entries[i % Button.entries.size]), e.input.pressedButtons)
    }
  }

  @Test
  fun fourPlayerServerAssignsSlotsWaitsForAllPlayersAndRelaysInput() {
    val port = ServerSocket(0).use { it.localPort }
    val serverStarted = LinkedBlockingQueue<ConnectionController.ServerStartedEvent>()
    val sessionStarted = LinkedBlockingQueue<ConnectionController.ServerGotConnectionEvent>()
    serverBus.register<ConnectionController.ServerStartedEvent> { serverStarted.add(it) }
    serverBus.register<ConnectionController.ServerGotConnectionEvent> { sessionStarted.add(it) }

    val server = TcpServer(serverBus, port, LinkMode.FOUR_PLAYER_ADAPTER)
    this.server = server
    threads += Thread(server).also { it.start() }
    assertNotNull(serverStarted.poll(5, TimeUnit.SECONDS), "server did not start")

    val buses = List(3) { EventBusImpl().also(extraBuses::add) }
    val handshakes = buses.map { LinkedBlockingQueue<ConnectionController.ClientHandshakeCompletedEvent>() }
    val ready = buses.map { LinkedBlockingQueue<ConnectionController.ClientConnectedToServerEvent>() }
    buses.forEachIndexed { index, bus ->
      bus.register<ConnectionController.ClientHandshakeCompletedEvent> { handshakes[index].add(it) }
      bus.register<ConnectionController.ClientConnectedToServerEvent> { ready[index].add(it) }
      val client = TcpClient("localhost:$port", bus)
      extraClients += client
      threads += Thread(client).also { it.start() }
      val handshake = assertNotNull(handshakes[index].poll(5, TimeUnit.SECONDS))
      assertEquals(LinkMode.FOUR_PLAYER_ADAPTER, handshake.mode)
      assertEquals(index + 1, handshake.player)
      if (index < 2) {
        assertEquals(null, ready[index].poll(200, TimeUnit.MILLISECONDS))
      }
    }

    assertNotNull(sessionStarted.poll(5, TimeUnit.SECONDS), "server did not release session")
    ready.forEachIndexed { index, queue ->
      val event = assertNotNull(queue.poll(5, TimeUnit.SECONDS), "player ${index + 2} not released")
      assertEquals(LinkMode.FOUR_PLAYER_ADAPTER, event.mode)
      assertEquals(index + 1, event.player)
    }

    val serverInputs = LinkedBlockingQueue<LinkedController.RemoteButtonStateEvent>()
    serverBus.register<LinkedController.RemoteButtonStateEvent> { serverInputs.add(it) }
    val clientInputs =
        buses.map { bus ->
          LinkedBlockingQueue<LinkedController.RemoteButtonStateEvent>().also { queue ->
            bus.register<LinkedController.RemoteButtonStateEvent> { queue.add(it) }
          }
        }

    buses[1].post(
        LinkedController.LocalButtonStateEvent(
            55,
            Input(listOf(Button.START), emptyList()),
            player = 2,
        ))
    val atServer = assertNotNull(serverInputs.poll(5, TimeUnit.SECONDS))
    assertEquals(2, atServer.player)
    assertEquals(55, atServer.frame)
    for (clientIndex in listOf(0, 2)) {
      val relayed = assertNotNull(clientInputs[clientIndex].poll(5, TimeUnit.SECONDS))
      assertEquals(2, relayed.player)
      assertEquals(listOf(Button.START), relayed.input.pressedButtons)
    }
    assertEquals(null, clientInputs[1].poll(200, TimeUnit.MILLISECONDS))

    serverBus.post(
        LinkedController.LocalButtonStateEvent(
            56,
            Input(listOf(Button.A), emptyList()),
            player = 0,
        ))
    clientInputs.forEachIndexed { index, queue ->
      val relayed = assertNotNull(queue.poll(5, TimeUnit.SECONDS), "host input missing at $index")
      assertEquals(0, relayed.player)
      assertEquals(56, relayed.frame)
    }
  }
}
