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
  fun fourPlayerServerStartsImmediatelyAssignsSlotsAndRelaysInput() {
    val port = ServerSocket(0).use { it.localPort }
    val serverStarted = LinkedBlockingQueue<ConnectionController.ServerStartedEvent>()
    val sessionStarted = LinkedBlockingQueue<ConnectionController.ServerGotConnectionEvent>()
    serverBus.register<ConnectionController.ServerStartedEvent> { serverStarted.add(it) }
    serverBus.register<ConnectionController.ServerGotConnectionEvent> { sessionStarted.add(it) }

    val server = TcpServer(serverBus, port, LinkMode.FOUR_PLAYER_ADAPTER)
    this.server = server
    threads += Thread(server).also { it.start() }
    assertNotNull(serverStarted.poll(5, TimeUnit.SECONDS), "server did not start")
    val active = assertNotNull(sessionStarted.poll(5, TimeUnit.SECONDS), "adapter did not start")
    assertEquals(LinkMode.FOUR_PLAYER_ADAPTER, active.mode)
    assertEquals(0, active.player)

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
      val event = assertNotNull(ready[index].poll(5, TimeUnit.SECONDS))
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

  @Test
  fun disconnectedFourPlayerSlotCanBeReusedWithoutStoppingServer() {
    val port = ServerSocket(0).use { it.localPort }
    val serverStarted = LinkedBlockingQueue<ConnectionController.ServerStartedEvent>()
    val disconnected = LinkedBlockingQueue<ConnectionController.ServerPlayerDisconnectedEvent>()
    serverBus.register<ConnectionController.ServerStartedEvent> { serverStarted.add(it) }
    serverBus.register<ConnectionController.ServerPlayerDisconnectedEvent> { disconnected.add(it) }

    val server = TcpServer(serverBus, port, LinkMode.FOUR_PLAYER_ADAPTER)
    this.server = server
    threads += Thread(server).also { it.start() }
    assertNotNull(serverStarted.poll(5, TimeUnit.SECONDS))

    val firstBus = EventBusImpl().also(extraBuses::add)
    val firstHandshake = LinkedBlockingQueue<ConnectionController.ClientHandshakeCompletedEvent>()
    val firstReady = LinkedBlockingQueue<ConnectionController.ClientConnectedToServerEvent>()
    firstBus.register<ConnectionController.ClientHandshakeCompletedEvent> { firstHandshake.add(it) }
    firstBus.register<ConnectionController.ClientConnectedToServerEvent> { firstReady.add(it) }
    val first = TcpClient("localhost:$port", firstBus)
    extraClients += first
    threads += Thread(first).also { it.start() }
    assertEquals(1, assertNotNull(firstHandshake.poll(5, TimeUnit.SECONDS)).player)
    assertNotNull(firstReady.poll(5, TimeUnit.SECONDS))

    first.stop()
    assertEquals(1, assertNotNull(disconnected.poll(5, TimeUnit.SECONDS)).player)

    val replacementBus = EventBusImpl().also(extraBuses::add)
    val replacementHandshake =
        LinkedBlockingQueue<ConnectionController.ClientHandshakeCompletedEvent>()
    val replacementReady = LinkedBlockingQueue<ConnectionController.ClientConnectedToServerEvent>()
    replacementBus.register<ConnectionController.ClientHandshakeCompletedEvent> {
      replacementHandshake.add(it)
    }
    replacementBus.register<ConnectionController.ClientConnectedToServerEvent> {
      replacementReady.add(it)
    }
    val replacement = TcpClient("localhost:$port", replacementBus)
    extraClients += replacement
    threads += Thread(replacement).also { it.start() }

    assertEquals(1, assertNotNull(replacementHandshake.poll(5, TimeUnit.SECONDS)).player)
    assertNotNull(replacementReady.poll(5, TimeUnit.SECONDS))
  }

  @Test
  fun fourPlayerServerRejectsAnExtraClientWithAClearReason() {
    val port = ServerSocket(0).use { it.localPort }
    val serverStarted = LinkedBlockingQueue<ConnectionController.ServerStartedEvent>()
    serverBus.register<ConnectionController.ServerStartedEvent> { serverStarted.add(it) }

    val server = TcpServer(serverBus, port, LinkMode.FOUR_PLAYER_ADAPTER)
    this.server = server
    threads += Thread(server).also { it.start() }
    assertNotNull(serverStarted.poll(5, TimeUnit.SECONDS), "server did not start")

    repeat(3) {
      val bus = EventBusImpl().also(extraBuses::add)
      val ready = LinkedBlockingQueue<ConnectionController.ClientConnectedToServerEvent>()
      bus.register<ConnectionController.ClientConnectedToServerEvent> { ready.add(it) }
      val client = TcpClient("localhost:$port", bus)
      extraClients += client
      threads += Thread(client).also { it.start() }
      assertNotNull(ready.poll(5, TimeUnit.SECONDS), "client ${it + 2} did not start")
    }

    val rejectedBus = EventBusImpl().also(extraBuses::add)
    val rejected = LinkedBlockingQueue<ConnectionController.ClientConnectionRejectedEvent>()
    val handshake = LinkedBlockingQueue<ConnectionController.ClientHandshakeCompletedEvent>()
    rejectedBus.register<ConnectionController.ClientConnectionRejectedEvent> { rejected.add(it) }
    rejectedBus.register<ConnectionController.ClientHandshakeCompletedEvent> { handshake.add(it) }
    val extraClient = TcpClient("localhost:$port", rejectedBus)
    extraClients += extraClient
    threads += Thread(extraClient).also { it.start() }

    val event = assertNotNull(rejected.poll(5, TimeUnit.SECONDS), "rejection reason not received")
    assertEquals("The netplay server is already full.", event.message)
    assertEquals(null, handshake.poll(200, TimeUnit.MILLISECONDS))
  }

  @Test
  fun legacyPeerSnapshotClosesConnectionWithAUserFacingProtocolError() {
    val port = ServerSocket(0).use { it.localPort }
    val serverStarted = LinkedBlockingQueue<ConnectionController.ServerStartedEvent>()
    val serverGotConnection = LinkedBlockingQueue<ConnectionController.ServerGotConnectionEvent>()
    serverBus.register<ConnectionController.ServerStartedEvent> { serverStarted.add(it) }
    serverBus.register<ConnectionController.ServerGotConnectionEvent> { serverGotConnection.add(it) }

    val server = TcpServer(serverBus, port)
    this.server = server
    threads += Thread(server).also { it.start() }
    assertNotNull(serverStarted.poll(5, TimeUnit.SECONDS))

    val protocolErrors = LinkedBlockingQueue<ConnectionController.ClientProtocolErrorEvent>()
    clientBus.register<ConnectionController.ClientProtocolErrorEvent> { protocolErrors.add(it) }
    val client = TcpClient("localhost:$port", clientBus)
    this.client = client
    threads += Thread(client).also { it.start() }
    assertNotNull(serverGotConnection.poll(5, TimeUnit.SECONDS))

    serverBus.post(
        LinkedController.LocalRomLoadedEvent(
            byteArrayOf(1, 2, 3),
            null,
            byteArrayOf(0xac.toByte(), 0xed.toByte(), 0x00, 0x05),
            GameboyType.DMG,
            Gameboy.BootstrapMode.SKIP,
            0,
        ))

    val error = assertNotNull(protocolErrors.poll(5, TimeUnit.SECONDS))
    assertEquals(
        "The peer sent an unsafe legacy Java save state. " +
            "Network state transfer requires the portable state format.",
        error.message,
    )
  }
}
