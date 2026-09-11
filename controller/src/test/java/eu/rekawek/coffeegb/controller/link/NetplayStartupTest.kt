package eu.rekawek.coffeegb.controller.link

import eu.rekawek.coffeegb.controller.BasicController
import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.network.Connection
import eu.rekawek.coffeegb.controller.network.ConnectionController
import eu.rekawek.coffeegb.controller.network.TcpClient
import eu.rekawek.coffeegb.controller.network.TcpServer
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.ApplicationSettingsOverrides
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.state.DetachedStateAdapter
import eu.rekawek.coffeegb.controller.state.MachineState
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.gpu.Display
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties.Feature
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NetplayStartupTest {
  @get:Rule val directory = TemporaryFolder()

  @Test
  fun startupPolicyIsScopedToTheCartridgeAndDoesNotChangeSaveIdentityFeatures() {
    val properties = properties(0)
    try {
      val rom = Rom(testRom())
      assertTrue(rom.cartridgeProperties.isLinkRequiredAtBoot)
      assertTrue(Feature.entries.none(rom.cartridgeProperties::has))
      val state = Controller.ControllerState(machineState(properties, rom), rom)
      val restart = createNetplayLoadEvent(state, LinkMode.NORMAL)
      assertSame(rom.image, restart.image)
      assertNull(restart.state)
      assertFalse(restart.allowAutosaveResume)

      val adapter = createNetplayLoadEvent(state, LinkMode.FOUR_PLAYER_ADAPTER)
      assertSame(state.state, adapter.state)
      assertTrue(adapter.allowAutosaveResume)

      for (bytes in listOf(
          testRom().also { it[0x134] = 'X'.code.toByte() },
          testRom().also { it[0x143] = 0x80.toByte() },
          testRom().also { it[0x147] = 0x1b },
          testRom().also { it[0x14a] = 0 },
          testRom().copyOf(0x80000),
      )) {
        val other = Rom(bytes)
        assertFalse(other.cartridgeProperties.isLinkRequiredAtBoot)
        val resume = createNetplayLoadEvent(state.copy(rom = other), LinkMode.NORMAL)
        assertSame(state.state, resume.state)
        assertTrue(resume.allowAutosaveResume)
      }
    } finally {
      properties.close()
    }
  }

  @Test(timeout = 30_000)
  fun hostStartRebootsBothRunningGamesOverTcpAndSkipsExistingAutosaves() {
    val romFile = directory.newFile("renamed-game.gbc").also { it.writeBytes(testRom()) }
    val rom = Rom(romFile)
    val properties = List(2, ::properties)
    val freshMarker = marker(properties[0], rom, machineState(properties[0], rom))
    assertTrue(freshMarker != 0x5a, "fixture must distinguish power-on RAM from the running game")
    val buses = List(2) { EventBusImpl() }
    val frames = List(2) { CountDownLatch(1) }
    val starts = List(2) { LinkedBlockingQueue<LinkedController>() }
    val requests = List(2) { LinkedBlockingQueue<Controller.LoadRomEvent>() }
    val wireStates = List(2) { LinkedBlockingQueue<Connection.PeerLoadedGameEvent>() }
    val failures = LinkedBlockingQueue<String>()
    val basics = buses.mapIndexed { player, bus ->
      bus.register<Display.GbcFrameReadyEvent> { frames[player].countDown() }
      bus.register<Connection.PeerLoadedGameEvent> { wireStates[player].add(it) }
      bus.register<Controller.LoadRomFailedEvent> { failures.add(it.technicalDetails) }
      bus.register<ConnectionController.ServerProtocolErrorEvent> { failures.add(it.message) }
      bus.register<ConnectionController.ClientProtocolErrorEvent> { failures.add(it.message) }
      BasicController(bus, properties[player], null).also {
        it.startController()
        bus.post(Controller.LoadRomEvent(romFile))
      }
    }
    val linked = mutableListOf<LinkedController>()
    val threads = mutableListOf<Thread>()
    var server: TcpServer? = null
    var client: TcpClient? = null
    try {
      frames.forEach { assertTrue(it.await(10, TimeUnit.SECONDS), "standalone game did not run") }
      fun start(player: Int) {
        // Same ownership handoff as SwingEmulator, invoked by the actual host START lifecycle.
        val state = assertNotNull(basics[player].closeWithState())
        assertEquals(0x5a, marker(properties[player], rom, state.state))
        val controller = LinkedController(buses[player], properties[player], null,
            LinkMode.NORMAL, player).also { it.timingTicker.disabled = true }
        val request = createNetplayLoadEvent(state, LinkMode.NORMAL)
        requests[player].add(request)
        if (player == 0) buses[player].post(request)
        // Delay the client's ROM load to exercise the connected-but-not-ready barrier.
        starts[player].add(controller)
      }
      buses[0].register<ConnectionController.ServerGotConnectionEvent> { start(0) }
      buses[1].register<ConnectionController.ClientConnectedToServerEvent> { start(1) }
      val listening = CountDownLatch(1)
      buses[0].register<ConnectionController.ServerStartedEvent> { listening.countDown() }
      val port = ServerSocket(0).use { it.localPort }
      server = TcpServer(buses[0], port).also { threads += Thread(it).also(Thread::start) }
      assertTrue(listening.await(5, TimeUnit.SECONDS))
      client = TcpClient("localhost:$port", buses[1]).also {
        threads += Thread(it).also(Thread::start)
      }
      starts.forEach { linked += assertNotNull(it.poll(10, TimeUnit.SECONDS)) }
      val loads = requests.map { assertNotNull(it.poll()) }
      loads.forEach {
        assertNull(it.state)
        assertFalse(it.allowAutosaveResume)
      }
      properties.forEach {
        assertEquals(ApplicationSettings.ResumePolicy.ALWAYS, it.applicationSettings.saves.resumePolicy)
        Files.walk(it.applicationSettings.saves.directory!!).use { paths ->
          assertTrue(paths.anyMatch { path -> path.fileName.toString() == "state.cgbstate" },
              "the handoff must have an autosave available to accidentally resume")
        }
      }

      val host = linked[0]
      val guest = linked[1]
      host.processPendingWorkAtSafePoint()
      assertEquals(1, host.activeSessionCount())
      val waitingState = host.captureDetachedState()
      repeat(5) { host.runFrame() }
      assertEquals(waitingState, host.captureDetachedState(), "host ran before the client was ready")
      assertEquals(freshMarker, marker(properties[0], rom, waitingState.players[0].session!!.machine))

      buses[1].post(loads[1])
      guest.processPendingWorkAtSafePoint()
      wireStates.forEach { queue ->
        val transferred = assertNotNull(queue.poll(5, TimeUnit.SECONDS), failures.toString())
        assertEquals(0L, transferred.frame)
        assertNull(transferred.portableState, "fresh startup must not transfer a running state")
      }
      linked.forEach { it.processPendingWorkAtSafePoint() }
      assertEquals(2, host.activeSessionCount())
      assertEquals(2, guest.activeSessionCount())
      assertEquals(host.captureDetachedState().players, guest.captureDetachedState().players)
      host.captureDetachedState().players.take(2).forEach {
        assertEquals(freshMarker, marker(properties[0], rom, it.session!!.machine))
      }
      repeat(12) { linked.forEach(LinkedController::runFrame) }
      assertTrue(linked.all { it.currentFrame() >= 12 })
      linked.forEachIndexed { player, controller ->
        assertEquals(0x5a, marker(properties[player], rom,
            controller.captureDetachedState().players[player].session!!.machine))
      }
      assertTrue(failures.isEmpty(), failures.toString())
    } finally {
      client?.stop()
      server?.stop()
      threads.forEach { it.join(3_000) }
      linked.forEach { it.close() }
      basics.forEach { it.close() }
      buses.forEach { it.close() }
      properties.forEach { it.close() }
    }
  }

  private fun properties(player: Int) = EmulatorProperties(
      directory.root.toPath().resolve("peer-$player/settings.properties"),
      overrides = ApplicationSettingsOverrides(bootstrapMode = BootstrapMode.SKIP,
          runtimeWarmupEnabled = false, batterySavesEnabled = false, rewindEnabled = false),
      debounceMillis = 0,
  ).also { properties ->
    properties.updateApplicationSettings {
      it.copy(saves = ApplicationSettings.Saves(
          directory = directory.root.toPath().resolve("peer-$player/saves"),
          resumePolicy = ApplicationSettings.ResumePolicy.ALWAYS,
      ))
    }
  }

  private fun machineState(properties: EmulatorProperties, rom: Rom): MachineState {
    val gameboy = Controller.createGameboyConfig(properties, rom).build()
    return try { DetachedStateAdapter.capture(gameboy) } finally { gameboy.discardUnstarted() }
  }

  private fun marker(properties: EmulatorProperties, rom: Rom, state: MachineState): Int {
    val gameboy = Controller.createGameboyConfig(properties, rom).forRestore().build()
    val bus = EventBusImpl()
    try {
      gameboy.init(bus, SerialEndpoint.NULL_ENDPOINT, InfraredEndpoint.NULL_ENDPOINT, null)
      DetachedStateAdapter.apply(gameboy, state)
      return gameboy.addressSpace.getByte(0xc000)
    } finally {
      gameboy.discardUnstarted()
      bus.close()
    }
  }

  /** Synthetic startup marker program; no commercial game code or save data. */
  private fun testRom(): ByteArray = ByteArray(0x100000).also { bytes ->
    "RAZOR FREESBRZE".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0x134)
    bytes[0x143] = 0xc0.toByte()
    bytes[0x147] = 0x19
    bytes[0x148] = 5
    bytes[0x14a] = 1
    byteArrayOf(0xc3.toByte(), 0x50, 0x01).copyInto(bytes, 0x100)
    byteArrayOf(0x3e, 0x5a, 0xea.toByte(), 0x00, 0xc0.toByte(), 0x18, 0xfe.toByte())
        .copyInto(bytes, 0x150)
  }
}
