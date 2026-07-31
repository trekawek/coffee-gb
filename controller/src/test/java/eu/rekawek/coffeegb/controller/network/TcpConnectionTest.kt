package eu.rekawek.coffeegb.controller.network

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.StateLimits
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.LinkMode
import eu.rekawek.coffeegb.controller.link.LinkedController
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.state.DetachedStateAdapter
import eu.rekawek.coffeegb.controller.state.MachineState
import eu.rekawek.coffeegb.controller.state.MachineStateRoot
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.controller.state.StateCompression
import eu.rekawek.coffeegb.controller.state.StateIdentity
import eu.rekawek.coffeegb.controller.state.StateIdentityEntry
import eu.rekawek.coffeegb.controller.state.StateRootKind
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.hardware.HardwareProfile
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import eu.rekawek.coffeegb.core.serial.FourPlayerAdapter
import org.junit.After
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.file.Paths
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

  private val temporaryRoms = mutableListOf<java.io.File>()

  private val synchronizationFailures = LinkedBlockingQueue<String>()

  init {
    serverBus.register<ConnectionController.ServerProtocolErrorEvent> {
      synchronizationFailures.add("host player ${it.player + 1}: ${it.message}")
    }
    clientBus.register<ConnectionController.ClientProtocolErrorEvent> {
      synchronizationFailures.add("client: ${it.message}")
    }
  }

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
    temporaryRoms.forEach(java.io.File::delete)
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
  fun stoppingServerTerminatesItsConnectedClient() {
    val port = ServerSocket(0).use { it.localPort }
    val serverStarted = LinkedBlockingQueue<ConnectionController.ServerStartedEvent>()
    val serverReady = LinkedBlockingQueue<ConnectionController.ServerGotConnectionEvent>()
    val clientReady = LinkedBlockingQueue<ConnectionController.ClientConnectedToServerEvent>()
    val clientDisconnected =
        LinkedBlockingQueue<ConnectionController.ClientDisconnectedFromServerEvent>()
    serverBus.register<ConnectionController.ServerStartedEvent>(serverStarted::add)
    serverBus.register<ConnectionController.ServerGotConnectionEvent>(serverReady::add)
    clientBus.register<ConnectionController.ClientConnectedToServerEvent>(clientReady::add)
    clientBus.register<ConnectionController.ClientDisconnectedFromServerEvent>(clientDisconnected::add)

    val server = TcpServer(serverBus, port)
    this.server = server
    threads += Thread(server).also { it.start() }
    assertNotNull(serverStarted.poll(5, TimeUnit.SECONDS), "server did not start")

    val client = TcpClient("localhost:$port", clientBus)
    this.client = client
    threads += Thread(client).also { it.start() }
    assertNotNull(serverReady.poll(5, TimeUnit.SECONDS), "server did not activate the session")
    assertNotNull(clientReady.poll(5, TimeUnit.SECONDS), "client did not activate the session")

    server.stop()

    assertNotNull(
        clientDisconnected.poll(5, TimeUnit.SECONDS),
        "stopping the server must terminate the connected client",
    )
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
          ByteArray("CoffeeGB NETPLAY".length + 7))

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
  fun rawV7PeersAreRejectedInBothRolesBeforeAnyCommandIsDelivered() {
    val port = ServerSocket(0).use { it.localPort }
    val started = LinkedBlockingQueue<ConnectionController.ServerStartedEvent>()
    val serverErrors = LinkedBlockingQueue<ConnectionController.ServerProtocolErrorEvent>()
    val delivered = LinkedBlockingQueue<LinkedController.RemoteButtonStateEvent>()
    serverBus.register<ConnectionController.ServerStartedEvent> { started.add(it) }
    serverBus.register<ConnectionController.ServerProtocolErrorEvent> { serverErrors.add(it) }
    serverBus.register<LinkedController.RemoteButtonStateEvent> { delivered.add(it) }
    val server = TcpServer(serverBus, port)
    this.server = server
    threads += Thread(server).also { it.start() }
    assertNotNull(started.poll(5, TimeUnit.SECONDS))

    Socket("localhost", port).use { socket ->
      val input = DataInputStream(socket.getInputStream())
      val output = DataOutputStream(socket.getOutputStream())
      input.readFully(ByteArray("CoffeeGB NETPLAY".length + 7))
      // A v7 peer's one-byte temporary-codec marker is followed by a valid-looking v7 INPUT.
      output.write(byteArrayOf(0x01, 0x03, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0))
      output.flush()
      val error = assertNotNull(serverErrors.poll(5, TimeUnit.SECONDS))
      assertTrue(error.message.contains("legacy protocol-v7 capability marker"))
      assertEquals(null, delivered.poll(200, TimeUnit.MILLISECONDS))
    }

    server.stop()
    val fakeServer = ServerSocket(0)
    val fakeThread =
        Thread {
          fakeServer.use { listener ->
            listener.accept().use { socket ->
              socket.getOutputStream().also { output ->
                output.write(
                    "CoffeeGB NETPLAY".toByteArray() +
                        byteArrayOf(0x07, LinkMode.NORMAL.ordinal.toByte(), 0x01))
                output.flush()
              }
            }
          }
        }.also {
          threads += it
          it.start()
        }
    val clientErrors = LinkedBlockingQueue<ConnectionController.ClientProtocolErrorEvent>()
    clientBus.register<ConnectionController.ClientProtocolErrorEvent> { clientErrors.add(it) }
    val oldServerClient = TcpClient("localhost:${fakeServer.localPort}", clientBus)
    this.client = oldServerClient
    threads += Thread(oldServerClient).also { it.start() }
    val error = assertNotNull(clientErrors.poll(5, TimeUnit.SECONDS))
    assertTrue(error.message.contains("expected version 8, received 7"))
    fakeThread.join(5_000)
  }

  @Test
  fun pendingHandshakesUseABoundedPoolAndBackpressure() {
    val port = ServerSocket(0).use { it.localPort }
    val started = LinkedBlockingQueue<ConnectionController.ServerStartedEvent>()
    serverBus.register<ConnectionController.ServerStartedEvent> { started.add(it) }
    val server = TcpServer(serverBus, port)
    this.server = server
    threads += Thread(server).also { it.start() }
    assertNotNull(started.poll(5, TimeUnit.SECONDS))

    val silent = mutableListOf<Socket>()
    try {
      repeat(StateLimits.NETPLAY_PENDING_HANDSHAKES) {
        val socket = Socket("localhost", port).also(silent::add)
        socket.soTimeout = 5_000
        DataInputStream(socket.getInputStream()).readFully(
            ByteArray("CoffeeGB NETPLAY".length + 7))
      }
      awaitCondition { server.pendingHandshakeCount() == StateLimits.NETPLAY_PENDING_HANDSHAKES }
      assertTrue(server.handshakeWorkerCount() <= StateLimits.NETPLAY_HANDSHAKE_WORKERS)

      Socket("localhost", port).use { rejected ->
        rejected.soTimeout = 5_000
        val greeting = ByteArray("CoffeeGB NETPLAY".length + 3)
        DataInputStream(rejected.getInputStream()).readFully(greeting)
        assertEquals(0xff, greeting["CoffeeGB NETPLAY".length + 1].toInt() and 0xff)
        assertEquals(
            Connection.RejectionReason.SERVER_BUSY.wireCode,
            greeting["CoffeeGB NETPLAY".length + 2].toInt() and 0xff,
        )
      }
    } finally {
      silent.forEach(Socket::close)
    }
  }

  @Test
  fun fourPlayerPendingHandshakesReserveDistinctSlotsAndReleaseThem() {
    val port = ServerSocket(0).use { it.localPort }
    val started = LinkedBlockingQueue<ConnectionController.ServerStartedEvent>()
    serverBus.register<ConnectionController.ServerStartedEvent> { started.add(it) }
    val server = TcpServer(serverBus, port, LinkMode.FOUR_PLAYER_ADAPTER)
    this.server = server
    threads += Thread(server).also { it.start() }
    assertNotNull(started.poll(5, TimeUnit.SECONDS))

    val pending = mutableListOf<Socket>()
    try {
      val assigned =
          (1..3).map {
            val socket = Socket("localhost", port).also(pending::add)
            socket.soTimeout = 5_000
            val greeting = ByteArray("CoffeeGB NETPLAY".length + 7)
            DataInputStream(socket.getInputStream()).readFully(greeting)
            greeting["CoffeeGB NETPLAY".length + 2].toInt()
          }
      assertEquals(listOf(1, 2, 3), assigned.sorted())

      Socket("localhost", port).use { rejected ->
        rejected.soTimeout = 5_000
        val greeting = ByteArray("CoffeeGB NETPLAY".length + 3)
        DataInputStream(rejected.getInputStream()).readFully(greeting)
        assertEquals(
            Connection.RejectionReason.SERVER_FULL.wireCode,
            greeting["CoffeeGB NETPLAY".length + 2].toInt() and 0xff,
        )
      }

      pending.removeAt(0).close()
      awaitCondition { server.pendingHandshakeCount() == 2 }
      val replacement = Socket("localhost", port).also(pending::add)
      replacement.soTimeout = 5_000
      val greeting = ByteArray("CoffeeGB NETPLAY".length + 7)
      DataInputStream(replacement.getInputStream()).readFully(greeting)
      assertEquals(1, greeting["CoffeeGB NETPLAY".length + 2].toInt())
    } finally {
      pending.forEach(Socket::close)
    }
  }

  @Test
  fun realTcpRuntimeHeldDuringCapabilityHandshakeFollowsStart() {
    val port = ServerSocket(0).use { it.localPort }
    val started = LinkedBlockingQueue<ConnectionController.ServerStartedEvent>()
    serverBus.register<ConnectionController.ServerStartedEvent> { started.add(it) }
    val server = TcpServer(serverBus, port)
    this.server = server
    threads += Thread(server).also { it.start() }
    assertNotNull(started.poll(5, TimeUnit.SECONDS))

    Socket("localhost", port).use { socket ->
      socket.soTimeout = 5_000
      val input = DataInputStream(socket.getInputStream())
      val output = DataOutputStream(socket.getOutputStream())
      input.readFully(ByteArray("CoffeeGB NETPLAY".length + 7))
      awaitCondition { server.pendingConnectionCount() == 1 }

      serverBus.post(
          LinkedController.LocalButtonStateEvent(
              42,
              Input(listOf(Button.A), emptyList()),
              player = 0,
          ))
      output.write(byteArrayOf(0x08, 0x01, 0x01, 0x07))
      output.flush()

      assertEquals(0x08, readWireCommand(input), "runtime traffic preceded START")
      assertEquals(0x03, readWireCommand(input), "queued input was not released after START")
    }
  }

  @Test
  fun realTcpCheckpointRecordsRemainAtomicDuringConcurrentRuntimeWrites() {
    val port = ServerSocket(0).use { it.localPort }
    val started = LinkedBlockingQueue<ConnectionController.ServerStartedEvent>()
    serverBus.register<ConnectionController.ServerStartedEvent> { started.add(it) }
    val server = TcpServer(serverBus, port, LinkMode.FOUR_PLAYER_ADAPTER)
    this.server = server
    threads += Thread(server).also { it.start() }
    assertNotNull(started.poll(5, TimeUnit.SECONDS))

    Socket("localhost", port).use { socket ->
      socket.soTimeout = 10_000
      val input = DataInputStream(socket.getInputStream())
      val output = DataOutputStream(socket.getOutputStream())
      input.readFully(ByteArray("CoffeeGB NETPLAY".length + 7))
      output.write(byteArrayOf(0x08, 0x01, 0x01, 0x07))
      output.flush()
      assertEquals(0x08, readWireCommand(input))

      val noisyRom = ByteArray(8 * 1024 * 1024) { ((it * 31 + it / 17) and 0xff).toByte() }
      ROM.readBytes().copyInto(noisyRom)
      val states =
          listOf(0, 1).map { player ->
            LinkedController.LocalRomLoadedEvent(
                noisyRom,
                null,
                portableSession(noisyRom, player),
                GameboyType.DMG,
                Gameboy.BootstrapMode.SKIP,
                73,
                player = player,
            )
          }
      val startedWrite = CountDownLatch(1)
      val checkpoint =
          Thread {
            startedWrite.countDown()
            serverBus.post(LinkedController.SessionStateReadyEvent(73, states))
          }.also { it.start() }
      startedWrite.await(5, TimeUnit.SECONDS)
      Thread.sleep(10)
      repeat(32) { frame ->
        serverBus.post(
            LinkedController.LocalButtonStateEvent(
                frame.toLong(),
                Input(listOf(Button.A), emptyList()),
                player = 0,
            ))
      }
      checkpoint.join(10_000)

      var command: Int
      do {
        command = readWireCommand(input)
      } while (command != 0x01)
      assertEquals(0x01, readWireCommand(input), "runtime split checkpoint state records")
      assertEquals(0x09, readWireCommand(input), "runtime split checkpoint synchronization")
    }
  }

  @Test
  fun nonReadingPeerIsIsolatedAndCannotBlockHostOrShutdown() {
    val port = ServerSocket(0).use { it.localPort }
    val started = LinkedBlockingQueue<ConnectionController.ServerStartedEvent>()
    val disconnected = LinkedBlockingQueue<ConnectionController.ServerPlayerDisconnectedEvent>()
    serverBus.register<ConnectionController.ServerStartedEvent> { started.add(it) }
    serverBus.register<ConnectionController.ServerPlayerDisconnectedEvent> { disconnected.add(it) }
    val server = TcpServer(serverBus, port, LinkMode.FOUR_PLAYER_ADAPTER)
    this.server = server
    val serverThread = Thread(server, "slow-reader-server").also { it.start() }
    threads += serverThread
    assertNotNull(started.poll(5, TimeUnit.SECONDS))

    val noisyRom = ByteArray(16 * 1024 * 1024)
    ROM.readBytes().copyInto(noisyRom)
    ByteArray(noisyRom.size - 0x8000)
        .also { java.util.Random(314).nextBytes(it) }
        .copyInto(noisyRom, destinationOffset = 0x8000)
    val state =
        LinkedController.LocalRomLoadedEvent(
            noisyRom,
            null,
            portableSession(noisyRom, 0),
            GameboyType.DMG,
            Gameboy.BootstrapMode.SKIP,
            73,
            player = 0,
        )
    val slow = handshakenRawClient(port)
    try {
      repeat(20) { attempt ->
        if (disconnected.isEmpty()) {
          serverBus.post(LinkedController.SessionStateReadyEvent(attempt.toLong(), listOf(state)))
        }
      }
      val dropped =
          assertNotNull(disconnected.poll(10, TimeUnit.SECONDS), "slow peer was not isolated")
      assertEquals(1, dropped.player)

      val heartbeatStart = System.nanoTime()
      serverBus.post(
          LinkedController.LocalButtonStateEvent(
              100,
              Input(listOf(Button.A), emptyList()),
              player = 0,
          ))
      assertTrue(
          System.nanoTime() - heartbeatStart < TimeUnit.SECONDS.toNanos(1),
          "a disconnected slow reader stalled host event delivery",
      )
    } finally {
      slow.close()
    }

    // The old physical slot is reusable, proving that only the offending connection was removed.
    val replacement = handshakenRawClient(port)
    serverBus.post(LinkedController.SessionStateReadyEvent(200, listOf(state)))
    Thread.sleep(100)
    val stopper = Thread(server::stop, "slow-reader-stop").also { it.start() }
    stopper.join(2_000)
    val stoppedWithinBound = !stopper.isAlive
    replacement.close()
    stopper.join(3_000)
    serverThread.join(2_000)

    assertTrue(stoppedWithinBound, "a blocked socket writer made server.stop() exceed two seconds")
    assertFalse(serverThread.isAlive, "server run loop did not stop after closing a slow peer")
  }

  @Test
  fun controllerRejectionCancelsABlockedWriterAndReleasesOnlyThatSlot() {
    val port = ServerSocket(0).use { it.localPort }
    val started = LinkedBlockingQueue<ConnectionController.ServerStartedEvent>()
    val disconnected = LinkedBlockingQueue<ConnectionController.ServerPlayerDisconnectedEvent>()
    serverBus.register<ConnectionController.ServerStartedEvent> { started.add(it) }
    serverBus.register<ConnectionController.ServerPlayerDisconnectedEvent> { disconnected.add(it) }
    val host = linkedController(serverBus, LinkMode.FOUR_PLAYER_ADAPTER, 0)
    serverBus.post(LoadRomEvent(ROM))
    host.runFrame()

    val server = TcpServer(serverBus, port, LinkMode.FOUR_PLAYER_ADAPTER)
    this.server = server
    threads += Thread(server, "controller-rejection-server").also { it.start() }
    assertNotNull(started.poll(5, TimeUnit.SECONDS))

    val slow = handshakenRawClient(port)
    val healthyBus = EventBusImpl().also(extraBuses::add)
    val healthyHandshake =
        LinkedBlockingQueue<ConnectionController.ClientHandshakeCompletedEvent>()
    val healthyInputs = LinkedBlockingQueue<LinkedController.RemoteButtonStateEvent>()
    healthyBus.register<ConnectionController.ClientHandshakeCompletedEvent> {
      healthyHandshake.add(it)
    }
    healthyBus.register<LinkedController.RemoteButtonStateEvent> { healthyInputs.add(it) }
    val healthy = TcpClient("localhost:$port", healthyBus)
    extraClients += healthy
    threads += Thread(healthy).also { it.start() }
    assertEquals(2, assertNotNull(healthyHandshake.poll(5, TimeUnit.SECONDS)).player)

    try {
      val noisyRom = ByteArray(16 * 1024 * 1024)
      ROM.readBytes().copyInto(noisyRom)
      val randomTail = ByteArray(noisyRom.size - 0x8000)
          .also { java.util.Random(314).nextBytes(it) }
      randomTail.copyInto(noisyRom, destinationOffset = 0x8000)
      serverBus.post(
          LinkedController.SessionStateReadyEvent(
              host.currentFrame(),
              listOf(
                  LinkedController.LocalRomLoadedEvent(
                      noisyRom,
                      null,
                      portableSession(noisyRom, 0),
                      GameboyType.DMG,
                      Gameboy.BootstrapMode.SKIP,
                      host.currentFrame(),
                      player = 0,
                  )),
          ))

      val received = LinkedBlockingQueue<LinkedController.RemoteButtonStateEvent>()
      serverBus.register<LinkedController.RemoteButtonStateEvent> { received.add(it) }
      DataOutputStream(slow.getOutputStream()).also { output ->
        output.writeByte(0x03)
        output.writeByte(1)
        output.writeLong(Int.MAX_VALUE.toLong())
        output.writeByte(0)
        output.writeByte(0)
        output.flush()
      }
      assertNotNull(received.poll(5, TimeUnit.SECONDS), "host did not receive hostile input")
      var dropped: ConnectionController.ServerPlayerDisconnectedEvent? = null
      val rejectionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      while (dropped == null && System.nanoTime() < rejectionDeadline) {
        // The root-bus probe above can run before the same synchronous post reaches the linked
        // controller's child queue. Keep advancing the controller within the existing bound so
        // the test observes that delivery ordering instead of racing it once.
        host.runFrame()
        dropped = disconnected.poll(10, TimeUnit.MILLISECONDS)
      }
      val disconnectedPlayer =
          assertNotNull(dropped, "controller rejection did not release the blocked socket")
      assertEquals(1, disconnectedPlayer.player)

      serverBus.post(
          LinkedController.LocalButtonStateEvent(
              host.currentFrame(),
              Input(listOf(Button.A), emptyList()),
              player = 0,
          ))
      assertNotNull(healthyInputs.poll(5, TimeUnit.SECONDS), "healthy peer stopped responding")
      host.runFrame()

      val replacementBus = EventBusImpl().also(extraBuses::add)
      val replacementHandshake =
          LinkedBlockingQueue<ConnectionController.ClientHandshakeCompletedEvent>()
      replacementBus.register<ConnectionController.ClientHandshakeCompletedEvent> {
        replacementHandshake.add(it)
      }
      val replacement = TcpClient("localhost:$port", replacementBus)
      extraClients += replacement
      threads += Thread(replacement).also { it.start() }
      assertEquals(1, assertNotNull(replacementHandshake.poll(5, TimeUnit.SECONDS)).player)
    } finally {
      slow.close()
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
  fun localLegacySnapshotIsRejectedBeforeNetworkWrite() {
    val port = ServerSocket(0).use { it.localPort }
    val serverStarted = LinkedBlockingQueue<ConnectionController.ServerStartedEvent>()
    val serverGotConnection = LinkedBlockingQueue<ConnectionController.ServerGotConnectionEvent>()
    serverBus.register<ConnectionController.ServerStartedEvent> { serverStarted.add(it) }
    serverBus.register<ConnectionController.ServerGotConnectionEvent> { serverGotConnection.add(it) }

    val server = TcpServer(serverBus, port)
    this.server = server
    threads += Thread(server).also { it.start() }
    assertNotNull(serverStarted.poll(5, TimeUnit.SECONDS))

    val disconnected = LinkedBlockingQueue<ConnectionController.ClientDisconnectedFromServerEvent>()
    clientBus.register<ConnectionController.ClientDisconnectedFromServerEvent> {
      disconnected.add(it)
    }
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

    assertNotNull(disconnected.poll(5, TimeUnit.SECONDS))
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
    val rom = syntheticCgbRom(41)
    val hostProperties = borderEnabledProperties(HardwareProfileRegistry.CGB)
    val clientProperties = borderEnabledProperties(HardwareProfileRegistry.CGB)
    val hostController = linkedController(serverBus, LinkMode.NORMAL, 0, hostProperties)
    val clientController = linkedController(clientBus, LinkMode.NORMAL, 1, clientProperties)

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
    serverBus.post(LoadRomEvent(rom, runningMemento(1_000, hostProperties, rom)))
    clientBus.post(LoadRomEvent(rom, runningMemento(2_000, clientProperties, rom)))
    driveControllers(hostController, clientController) {
      hostController.activeSessionCount() == 2 && clientController.activeSessionCount() == 2
    }

    assertEquals(2, hostController.activeSessionCount())
    assertEquals(2, clientController.activeSessionCount())
  }

  @Test
  fun linkedControllersExchangeTransferred512KiBCgbRomAndMachineCheckpoint() {
    val port = ServerSocket(0).use { it.localPort }
    val serverStarted = LinkedBlockingQueue<ConnectionController.ServerStartedEvent>()
    val serverReady = LinkedBlockingQueue<ConnectionController.ServerGotConnectionEvent>()
    val clientReady = LinkedBlockingQueue<ConnectionController.ClientConnectedToServerEvent>()
    serverBus.register<ConnectionController.ServerStartedEvent> { serverStarted.add(it) }
    serverBus.register<ConnectionController.ServerGotConnectionEvent> { serverReady.add(it) }
    clientBus.register<ConnectionController.ClientConnectedToServerEvent> { clientReady.add(it) }
    val rom = synthetic512KiBCgbRom(47)
    val properties = borderEnabledProperties(HardwareProfileRegistry.CGB)
    assertMachineCheckpointMatchesTransferredRom(rom, properties)
    val hostController = linkedController(serverBus, LinkMode.NORMAL, 0, properties)
    val clientController =
        linkedController(
            clientBus,
            LinkMode.NORMAL,
            1,
            borderEnabledProperties(HardwareProfileRegistry.CGB),
        )

    val server = TcpServer(serverBus, port)
    this.server = server
    threads += Thread(server).also { it.start() }
    assertNotNull(serverStarted.poll(5, TimeUnit.SECONDS))
    val client = TcpClient("localhost:$port", clientBus)
    this.client = client
    threads += Thread(client).also { it.start() }
    assertNotNull(serverReady.poll(5, TimeUnit.SECONDS))
    assertNotNull(clientReady.poll(5, TimeUnit.SECONDS))

    serverBus.post(LoadRomEvent(rom, runningMemento(1_000, properties, rom)))
    clientBus.post(
        LoadRomEvent(
            rom,
            runningMemento(
                2_000,
                borderEnabledProperties(HardwareProfileRegistry.CGB),
                rom,
            ),
        ))
    driveControllers(hostController, clientController) {
      hostController.activeSessionCount() == 2 && clientController.activeSessionCount() == 2
    }

    assertEquals(2, hostController.activeSessionCount())
    assertEquals(2, clientController.activeSessionCount())
    assertTrue(synchronizationFailures.isEmpty(), synchronizationFailures.peek())
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
    val rom = syntheticCgbRom(53)
    val hostProperties = borderEnabledProperties(HardwareProfileRegistry.CGB0)
    val clientProperties = borderEnabledProperties(HardwareProfileRegistry.CGB0)
    val hostController =
        linkedController(serverBus, LinkMode.FOUR_PLAYER_ADAPTER, 0, hostProperties)
    val clientController =
        linkedController(clientBus, LinkMode.FOUR_PLAYER_ADAPTER, 1, clientProperties)

    val server = TcpServer(serverBus, port, LinkMode.FOUR_PLAYER_ADAPTER)
    this.server = server
    threads += Thread(server).also { it.start() }
    assertNotNull(serverStarted.poll(5, TimeUnit.SECONDS))
    serverBus.post(LoadRomEvent(rom))
    repeat(3) { hostController.runFrame() }
    assertEquals(1, hostController.activeSessionCount())

    val client = TcpClient("localhost:$port", clientBus)
    this.client = client
    threads += Thread(client).also { it.start() }
    assertNotNull(clientReady.poll(5, TimeUnit.SECONDS))
    clientBus.post(LoadRomEvent(rom))
    driveControllers(hostController, clientController) {
      hostController.activeSessionCount() == 2 && clientController.activeSessionCount() == 2
    }

    val checkpoint = assertNotNull(checkpoints.poll(5, TimeUnit.SECONDS))
    assertEquals(listOf(0, 1), checkpoint.states.map { it.player })
    assertTrue(
        checkpoint.states.all { state ->
          state.portableState?.root?.kind == StateRootKind.SESSION
        })
    assertEquals(2, hostController.activeSessionCount())
    assertEquals(2, clientController.activeSessionCount())
  }

  @Test
  fun invalidOutgoingProfileIsReportedBeforeSynchronizationTimeout() {
    val port = ServerSocket(0).use { it.localPort }
    val serverStarted = LinkedBlockingQueue<ConnectionController.ServerStartedEvent>()
    val serverReady = LinkedBlockingQueue<ConnectionController.ServerGotConnectionEvent>()
    val failures = LinkedBlockingQueue<ConnectionController.ServerProtocolErrorEvent>()
    val disconnected =
        LinkedBlockingQueue<ConnectionController.ClientDisconnectedFromServerEvent>()
    serverBus.register<ConnectionController.ServerStartedEvent> { serverStarted.add(it) }
    serverBus.register<ConnectionController.ServerGotConnectionEvent> { serverReady.add(it) }
    serverBus.register<ConnectionController.ServerProtocolErrorEvent> { failures.add(it) }
    clientBus.register<ConnectionController.ClientDisconnectedFromServerEvent> {
      disconnected.add(it)
    }

    val server = TcpServer(serverBus, port)
    this.server = server
    threads += Thread(server).also { it.start() }
    assertNotNull(serverStarted.poll(5, TimeUnit.SECONDS))
    val client = TcpClient("localhost:$port", clientBus)
    this.client = client
    threads += Thread(client).also { it.start() }
    assertNotNull(serverReady.poll(5, TimeUnit.SECONDS))

    val rom = StateCodecTestSupport.rom(seed = 67, cgb = true)
    serverBus.post(
        LinkedController.LocalRomLoadedEvent(
            romFile = rom,
            batteryFile = null,
            portableState = null,
            gameboyType = GameboyType.CGB,
            bootstrapMode = Gameboy.BootstrapMode.SKIP,
            frame = 0,
            hardwareProfileId = HardwareProfileRegistry.CGB.id(),
            displaySgbBorder = true,
        ))

    val failure = assertNotNull(failures.poll(2, TimeUnit.SECONDS))
    assertEquals(1, failure.player)
    assertEquals(
        "Outgoing portable checkpoint was rejected: " +
            "SGB-border profile flag is invalid for CGB",
        failure.message,
    )
    assertNotNull(disconnected.poll(2, TimeUnit.SECONDS))
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
      val handshake = ByteArray("CoffeeGB NETPLAY".length + 7)
      input.readFully(handshake)
      assertEquals(0x08, handshake["CoffeeGB NETPLAY".length].toInt())
      assertContentEquals(
          byteArrayOf(0x08, 0x01, 0x01, 0x07),
          handshake.copyOfRange(handshake.size - 4, handshake.size),
      )
      output.write(byteArrayOf(0x08, 0x01, 0x01, 0x07))
      output.flush()
      assertEquals(0x08, input.readUnsignedByte())

      val rom = Connection.deflate(byteArrayOf(1))
      val legacy = byteArrayOf(0xac.toByte(), 0xed.toByte(), 0x00, 0x05)
      val header = ByteBuffer.allocate(Connection.ROM_HEADER_SIZE)
      header.put(1)
      header.putLong(0)
      header.put(GameboyType.DMG.ordinal.toByte())
      header.put(Gameboy.BootstrapMode.SKIP.ordinal.toByte())
      header.putInt(0)
      header.put(0)
      intArrayOf(1, rom.size, 0, 0, 0, 0, legacy.size).forEach(header::putInt)
      output.writeByte(0x01)
      output.write(header.array())
      output.write(legacy)
      output.write(rom)
      output.flush()

      val error = assertNotNull(protocolErrors.poll(5, TimeUnit.SECONDS))
      assertEquals(1, error.player)
      assertTrue(
          error.message.startsWith(
              Connection.ProtocolErrorReason.UNSUPPORTED_STATE_FORMAT.userMessage))
      assertTrue(error.message.contains("does not begin with CGBS"))
      assertEquals(null, delivered.poll(200, TimeUnit.MILLISECONDS))
    }
  }

  private fun linkedController(
      bus: EventBusImpl,
      mode: LinkMode,
      player: Int,
      properties: EmulatorProperties = EmulatorProperties(),
  ): LinkedController =
      LinkedController(bus, properties, null, mode, player).also {
        it.timingTicker.disabled = true
        controllers += it
      }

  private fun driveControllers(
      host: LinkedController,
      client: LinkedController,
      complete: () -> Boolean,
  ) {
    val started = System.nanoTime()
    val timeout = TimeUnit.SECONDS.toNanos(5)
    var pumps = 0
    while (System.nanoTime() - started < timeout) {
      host.runFrame()
      client.runFrame()
      pumps++
      synchronizationFailures.poll()?.let { failure ->
        throw AssertionError("linked synchronization rejected immediately: $failure")
      }
      if (complete()) return
      Thread.sleep(10)
    }
    val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    assertTrue(
        complete(),
        "linked controllers did not synchronize after ${elapsedMillis}ms/$pumps pumps; " +
            "host sessions=${host.activeSessionCount()}, frame=${host.currentFrame()}; " +
            "client sessions=${client.activeSessionCount()}, frame=${client.currentFrame()}",
    )
  }

  private fun awaitCondition(condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!condition() && System.nanoTime() < deadline) {
      Thread.sleep(10)
    }
    assertTrue(condition(), "condition was not reached before timeout")
  }

  private fun handshakenRawClient(port: Int): Socket {
    val socket = Socket()
    socket.receiveBufferSize = 1_024
    socket.soTimeout = 10_000
    socket.connect(java.net.InetSocketAddress("localhost", port))
    val input = DataInputStream(socket.getInputStream())
    val output = DataOutputStream(socket.getOutputStream())
    input.readFully(ByteArray("CoffeeGB NETPLAY".length + 7))
    output.write(byteArrayOf(0x08, 0x01, 0x01, 0x07))
    output.flush()
    assertEquals(0x08, readWireCommand(input))
    socket.soTimeout = 0
    return socket
  }

  /** Reads and consumes one protocol-v8 command, returning its command byte. */
  private fun readWireCommand(input: DataInputStream): Int {
    val command = input.readUnsignedByte()
    when (command) {
      0x01 -> {
        val header = ByteArray(Connection.ROM_HEADER_SIZE).also(input::readFully)
        val heldButtons = header[15].toInt() and 0xff
        val payloadBytes =
            ByteBuffer.wrap(header).let { buffer ->
              buffer.position(20)
              val rom = buffer.int.toLong()
              buffer.position(28)
              val slot = buffer.int.toLong()
              buffer.position(36)
              val battery = buffer.int.toLong()
              val state = buffer.int.toLong()
              rom + slot + battery + state
            }
        require(payloadBytes <= Int.MAX_VALUE)
        input.readFully(ByteArray(heldButtons + payloadBytes.toInt()))
      }
      0x03 -> {
        val header = ByteArray(11).also(input::readFully)
        val buttons = (header[9].toInt() and 0xff) + (header[10].toInt() and 0xff)
        input.readFully(ByteArray(buttons))
      }
      0x06, 0x07 -> input.readFully(ByteArray(9))
      0x09 -> input.readLong()
      0x0a -> input.readUnsignedByte()
    }
    return command
  }

  private fun runningMemento(
      ticks: Int,
      properties: EmulatorProperties = EmulatorProperties(),
      romFile: java.io.File = ROM,
  ): MachineState {
    val bus = EventBusImpl()
    val gameboy = Controller.createGameboyConfig(properties, Rom(romFile)).build()
    gameboy.init(bus, SerialEndpoint.NULL_ENDPOINT, InfraredEndpoint.NULL_ENDPOINT, null)
    return try {
      repeat(ticks) { gameboy.tick() }
      DetachedStateAdapter.capture(gameboy)
    } finally {
      gameboy.stop()
      gameboy.close()
      bus.close()
    }
  }

  private fun assertMachineCheckpointMatchesTransferredRom(
      romFile: java.io.File,
      properties: EmulatorProperties,
  ) {
    val wireRom = romFile.readBytes()
    assertEquals(512 * 1024, wireRom.size)
    assertContentEquals(
        wireRom,
        Rom(wireRom).rom.map(Int::toByte).toByteArray(),
        "the valid synthetic header must not require loader correction",
    )
    val sourceBus = EventBusImpl()
    val sourceConfig = Controller.createGameboyConfig(properties, Rom(romFile))
    val source = sourceConfig.build()
    source.init(sourceBus, SerialEndpoint.NULL_ENDPOINT, InfraredEndpoint.NULL_ENDPOINT, null)
    val file =
        try {
          repeat(1_000) { source.tick() }
          StateCodec.capture(sourceConfig, source)
        } finally {
          source.stop()
          source.close()
          sourceBus.close()
        }
    val sourceIdentity = StateIdentity.from(sourceConfig)
    assertEquals(sourceIdentity, file.identities.single().identity)
    assertEquals(SYNTHETIC_CGB_SHA256, sourceIdentity.primaryRom.hex())
    assertEquals("cgb", sourceIdentity.profile.canonicalProfileId)
    assertFalse(sourceIdentity.profile.displaySgbBorder)
    assertEquals(
        2,
        (file.root as MachineStateRoot)
            .machine
            .recordCount(FILE_BATTERY_STATE),
        "the local checkpoint captures both file-backed battery ownership records",
    )
    val target =
        Connection.peerConfiguration(
            wireRom,
            null,
            null,
            GameboyType.CGB,
            Gameboy.BootstrapMode.SKIP,
            false,
            false,
            false,
            false,
            true,
        )
    assertEquals(sourceIdentity, StateIdentity.from(target))
    StateCodec.validateForTarget(
        file,
        StateRootKind.MACHINE,
        listOf(StateIdentityEntry(0, StateIdentity.from(target))),
    )
    val targetBus = EventBusImpl()
    val probe = target.forRestore().build()
    probe.init(targetBus, SerialEndpoint.NULL_ENDPOINT, InfraredEndpoint.NULL_ENDPOINT, null)
    try {
      assertEquals(
          2,
          DetachedStateAdapter.capture(probe).recordCount(MEMORY_BATTERY_STATE),
          "the peer checkpoint target must be service-free and memory-backed",
      )
      DetachedStateAdapter.validateTarget(probe, (file.root as MachineStateRoot).machine)
    } finally {
      probe.stop()
      probe.close()
      targetBus.close()
    }
  }

  private fun borderEnabledProperties(profile: HardwareProfile): EmulatorProperties =
      EmulatorProperties(profile).also {
        it.properties[EmulatorProperties.Key.ShowSgbBorder.propertyName] = "true"
      }

  private fun syntheticCgbRom(seed: Int): java.io.File =
      java.io.File.createTempFile("coffee-gb-netplay-cgb-$seed-", ".gbc")
          .also {
            it.writeBytes(StateCodecTestSupport.rom(seed = seed, cgb = true))
            temporaryRoms += it
          }

  private fun synthetic512KiBCgbRom(seed: Int): java.io.File =
      java.io.File.createTempFile("coffee-gb-netplay-cgb-512k-$seed-", ".gbc")
          .also { file ->
            val bytes = ByteArray(512 * 1024) { index -> ((index * 17 + seed) and 0xff).toByte() }
            bytes[0x100] = 0x18
            bytes[0x101] = 0xfe.toByte()
            ByteArray(16).copyInto(bytes, 0x134)
            "CGBSYNTH512".forEachIndexed { index, character ->
              bytes[0x134 + index] = character.code.toByte()
            }
            bytes[0x143] = 0x80.toByte()
            bytes[0x144] = 0
            bytes[0x145] = 0
            bytes[0x146] = 0
            bytes[0x147] = 0x1b
            bytes[0x148] = 0x04
            bytes[0x149] = 0x03
            bytes[0x14a] = 0
            bytes[0x14b] = 0
            bytes[0x14c] = 0
            var headerChecksum = 0
            for (index in 0x134..0x14c) {
              headerChecksum = (headerChecksum - (bytes[index].toInt() and 0xff) - 1) and 0xff
            }
            bytes[0x14d] = headerChecksum.toByte()
            bytes[0x14e] = 0
            bytes[0x14f] = 0
            val globalChecksum =
                bytes.indices
                    .filter { it != 0x14e && it != 0x14f }
                    .fold(0) { sum, index -> (sum + (bytes[index].toInt() and 0xff)) and 0xffff }
            bytes[0x14e] = (globalChecksum ushr 8).toByte()
            bytes[0x14f] = globalChecksum.toByte()
            file.writeBytes(bytes)
            temporaryRoms += file
          }

  private fun portableSession(rom: ByteArray, player: Int): ByteArray {
    val bus = EventBusImpl()
    val configuration =
        Gameboy.GameboyConfiguration(Rom(rom))
            .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
            .setGameboyType(GameboyType.DMG)
    val session = Session(configuration, bus, null, FourPlayerAdapter().endpoint(player))
    return try {
      StateCodec.encode(StateCodec.capture(session), StateCompression.DEFLATE)
    } finally {
      session.close()
      bus.close()
    }
  }

  private companion object {
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()
    const val FILE_BATTERY_STATE =
        "eu.rekawek.coffeegb.core.memory.cart.battery.FileBattery\$FileBatteryState"
    const val MEMORY_BATTERY_STATE =
        "eu.rekawek.coffeegb.core.memory.cart.battery.MemoryBattery\$MemoryBatteryState"
    const val SYNTHETIC_CGB_SHA256 =
        "4eebdc6a6e2d47d711054a73419b06f4c8675ef1838a9cc364d32f27552e1b5c"
  }
}
