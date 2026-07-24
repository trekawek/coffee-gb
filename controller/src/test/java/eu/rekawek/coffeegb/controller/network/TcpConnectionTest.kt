package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.LinkedController
import eu.rekawek.coffeegb.controller.link.LinkMode
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import org.junit.After
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.file.Paths
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** End-to-end exchange over real TCP sockets on the loopback interface. */
class TcpConnectionTest {

  private val serverBus = EventBusImpl()

  private val clientBus = EventBusImpl()

  private var server: TcpServer? = null

  private var client: TcpClient? = null

  private val extraClients = mutableListOf<TcpClient>()

  private val extraBuses = mutableListOf<EventBusImpl>()

  private val threads = mutableListOf<Thread>()

  private val controllers = mutableListOf<LinkedController>()

  @After
  fun tearDown() {
    client?.stop()
    extraClients.forEach { it.stop() }
    server?.stop()
    threads.forEach { it.join(3000) }
    controllers.forEach { it.closeWithState() }
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
    val rom = ROM.readBytes()
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

    val initialStates = LinkedBlockingQueue<Connection.PeerLoadedGameEvent>()
    serverBus.register<Connection.PeerLoadedGameEvent> { initialStates.add(it) }
    buses.forEachIndexed { index, bus ->
      bus.post(
          LinkedController.LocalRomLoadedEvent(
              ROM.readBytes(),
              null,
              null,
              GameboyType.DMG,
              Gameboy.BootstrapMode.SKIP,
              0,
              player = index + 1,
          ))
    }
    repeat(3) { assertNotNull(initialStates.poll(5, TimeUnit.SECONDS)) }

    buses[1].post(
        LinkedController.LocalButtonStateEvent(
            55,
            Input(listOf(Button.START), emptyList()),
            player = 2,
        ))
    val atServer = assertNotNull(serverInputs.poll(5, TimeUnit.SECONDS))
    assertEquals(2, atServer.player)
    assertEquals(55, atServer.frame)
    // LinkedController emits this only after checking the peer frame against its own clock.
    serverBus.post(Connection.ValidatedPeerButtonStateEvent(atServer))
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
  fun silentCapabilityPeerDoesNotBlockAnotherClientOrServerShutdown() {
    val port = ServerSocket(0).use { it.localPort }
    val serverStarted = LinkedBlockingQueue<ConnectionController.ServerStartedEvent>()
    val clientReady = LinkedBlockingQueue<ConnectionController.ClientConnectedToServerEvent>()
    val serverStopped = LinkedBlockingQueue<ConnectionController.ServerStoppedEvent>()
    serverBus.register<ConnectionController.ServerStartedEvent> { serverStarted.add(it) }
    serverBus.register<ConnectionController.ServerStoppedEvent> { serverStopped.add(it) }
    clientBus.register<ConnectionController.ClientConnectedToServerEvent> { clientReady.add(it) }

    val server = TcpServer(serverBus, port)
    this.server = server
    val serverThread = Thread(server).also { it.start() }
    threads += serverThread
    assertNotNull(serverStarted.poll(5, TimeUnit.SECONDS))

    Socket("localhost", port).use { silent ->
      // Read the server greeting but deliberately withhold the capability byte.
      DataInputStream(silent.getInputStream()).readFully(
          ByteArray("CoffeeGB NETPLAY".length + 4))

      val client = TcpClient("localhost:$port", clientBus)
      this.client = client
      threads += Thread(client).also { it.start() }
      assertNotNull(
          clientReady.poll(1, TimeUnit.SECONDS),
          "silent peer blocked the accept loop",
      )

      server.stop()
      serverThread.join(1_000)
      assertNotNull(serverStopped.poll(1, TimeUnit.SECONDS), "pending handshake blocked shutdown")
    }
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

  @Test
  fun linkedControllersExchangePortableRunningStateOverTcp() {
    val port = ServerSocket(0).use { it.localPort }
    val serverStarted = LinkedBlockingQueue<ConnectionController.ServerStartedEvent>()
    val serverReady = LinkedBlockingQueue<ConnectionController.ServerGotConnectionEvent>()
    val clientReady = LinkedBlockingQueue<ConnectionController.ClientConnectedToServerEvent>()
    serverBus.register<ConnectionController.ServerStartedEvent> { serverStarted.add(it) }
    serverBus.register<ConnectionController.ServerGotConnectionEvent> { serverReady.add(it) }
    clientBus.register<ConnectionController.ClientConnectedToServerEvent> { clientReady.add(it) }
    val hostController = linkedController(serverBus, LinkMode.NORMAL, 0)
    val clientController = linkedController(clientBus, LinkMode.NORMAL, 1)

    val server = TcpServer(serverBus, port)
    this.server = server
    threads += Thread(server).also { it.start() }
    assertNotNull(serverStarted.poll(5, TimeUnit.SECONDS))
    val client = TcpClient("localhost:$port", clientBus)
    this.client = client
    threads += Thread(client).also { it.start() }
    assertNotNull(serverReady.poll(5, TimeUnit.SECONDS))
    assertNotNull(clientReady.poll(5, TimeUnit.SECONDS))

    // Both sides are already running when the controller transition supplies their detached
    // mementos. LinkedController must encode those states portably before Connection sees them.
    serverBus.post(LoadRomEvent(ROM, runningMemento(1_000)))
    clientBus.post(LoadRomEvent(ROM, runningMemento(2_000)))
    driveControllers(hostController, clientController) {
      hostController.activeSessionCount() == 2 && clientController.activeSessionCount() == 2
    }

    assertEquals(2, hostController.activeSessionCount())
    assertEquals(2, clientController.activeSessionCount())
  }

  @Test
  fun linkedFourPlayerClientStartsFromPortableCheckpointOverTcp() {
    val port = ServerSocket(0).use { it.localPort }
    val serverStarted = LinkedBlockingQueue<ConnectionController.ServerStartedEvent>()
    val clientReady = LinkedBlockingQueue<ConnectionController.ClientConnectedToServerEvent>()
    val checkpoints = LinkedBlockingQueue<Connection.SessionCheckpointEvent>()
    serverBus.register<ConnectionController.ServerStartedEvent> { serverStarted.add(it) }
    clientBus.register<ConnectionController.ClientConnectedToServerEvent> { clientReady.add(it) }
    clientBus.register<Connection.SessionCheckpointEvent> { checkpoints.add(it) }
    val hostController = linkedController(serverBus, LinkMode.FOUR_PLAYER_ADAPTER, 0)
    val clientController = linkedController(clientBus, LinkMode.FOUR_PLAYER_ADAPTER, 1)

    val server = TcpServer(serverBus, port, LinkMode.FOUR_PLAYER_ADAPTER)
    this.server = server
    threads += Thread(server).also { it.start() }
    assertNotNull(serverStarted.poll(5, TimeUnit.SECONDS))
    serverBus.post(LoadRomEvent(ROM))
    repeat(3) { hostController.runFrame() }
    assertEquals(1, hostController.activeSessionCount())

    val client = TcpClient("localhost:$port", clientBus)
    this.client = client
    threads += Thread(client).also { it.start() }
    assertNotNull(clientReady.poll(5, TimeUnit.SECONDS))
    clientBus.post(LoadRomEvent(ROM))
    driveControllers(hostController, clientController) {
      hostController.activeSessionCount() == 2 && clientController.activeSessionCount() == 2
    }

    val checkpoint = assertNotNull(checkpoints.poll(5, TimeUnit.SECONDS))
    assertEquals(listOf(0, 1), checkpoint.states.map { it.player })
    assertTrue(
        checkpoint.states.all { state ->
          state.sessionSnapshot?.copyOfRange(0, 4)?.contentEquals("CGBN".toByteArray()) == true
        })
    assertEquals(2, hostController.activeSessionCount())
    assertEquals(2, clientController.activeSessionCount())
  }

  @Test
  fun clientToServerLegacySnapshotRaisesServerProtocolEventBeforeDelivery() {
    val port = ServerSocket(0).use { it.localPort }
    val serverStarted = LinkedBlockingQueue<ConnectionController.ServerStartedEvent>()
    val protocolErrors = LinkedBlockingQueue<ConnectionController.ServerProtocolErrorEvent>()
    val delivered = LinkedBlockingQueue<Connection.PeerLoadedGameEvent>()
    serverBus.register<ConnectionController.ServerStartedEvent> { serverStarted.add(it) }
    serverBus.register<ConnectionController.ServerProtocolErrorEvent> { protocolErrors.add(it) }
    serverBus.register<Connection.PeerLoadedGameEvent> { delivered.add(it) }
    val server = TcpServer(serverBus, port)
    this.server = server
    threads += Thread(server).also { it.start() }
    assertNotNull(serverStarted.poll(5, TimeUnit.SECONDS))

    Socket("localhost", port).use { socket ->
      TcpClient.configure(socket)
      val input = DataInputStream(socket.getInputStream())
      val output = DataOutputStream(socket.getOutputStream())
      val handshake = ByteArray("CoffeeGB NETPLAY".length + 4)
      input.readFully(handshake)
      assertEquals(0x07, handshake["CoffeeGB NETPLAY".length].toInt())
      assertEquals(0x01, handshake.last().toInt())
      output.writeByte(0x01)
      output.flush()
      assertEquals(0x08, input.readUnsignedByte())

      val rom = Connection.deflate(byteArrayOf(1))
      val legacy = byteArrayOf(0xac.toByte(), 0xed.toByte(), 0x00, 0x05)
      val snapshot = Connection.deflate(legacy)
      val header = ByteBuffer.allocate(45)
      header.put(1)
      header.putLong(0)
      header.put(GameboyType.DMG.ordinal.toByte())
      header.put(Gameboy.BootstrapMode.SKIP.ordinal.toByte())
      header.put(0)
      header.put(0)
      intArrayOf(1, 0, legacy.size, 0).forEach(header::putInt)
      intArrayOf(rom.size, 0, snapshot.size, 0).forEach(header::putInt)
      output.writeByte(0x01)
      output.write(header.array())
      output.write(rom)
      output.write(snapshot)
      output.flush()

      val error = assertNotNull(protocolErrors.poll(5, TimeUnit.SECONDS))
      assertEquals(1, error.player)
      assertEquals(
          "The peer sent an unsafe legacy Java save state. " +
              "Network state transfer requires the portable state format.",
          error.message,
      )
      assertEquals(null, delivered.poll(200, TimeUnit.MILLISECONDS))
    }
  }

  private fun linkedController(
      bus: EventBusImpl,
      mode: LinkMode,
      player: Int,
  ): LinkedController =
      LinkedController(bus, EmulatorProperties(), null, mode, player).also {
        it.timingTicker.disabled = true
        controllers += it
      }

  private fun driveControllers(
      host: LinkedController,
      client: LinkedController,
      complete: () -> Boolean,
  ) {
    repeat(100) {
      host.runFrame()
      client.runFrame()
      if (complete()) return
      Thread.sleep(10)
    }
    assertTrue(complete(), "linked controllers did not synchronize within the test timeout")
  }

  private fun runningMemento(ticks: Int): eu.rekawek.coffeegb.core.memento.Memento<Gameboy> {
    val bus = EventBusImpl()
    val gameboy =
        Gameboy.GameboyConfiguration(Rom(ROM))
            .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
            .build()
    gameboy.init(bus, SerialEndpoint.NULL_ENDPOINT, InfraredEndpoint.NULL_ENDPOINT, null)
    return try {
      repeat(ticks) { gameboy.tick() }
      gameboy.saveToMemento()
    } finally {
      gameboy.stop()
      gameboy.close()
      bus.close()
    }
  }

  private companion object {
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()
  }
}
