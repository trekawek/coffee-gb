package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.LinkMode
import eu.rekawek.coffeegb.controller.link.LinkedController
import eu.rekawek.coffeegb.controller.link.createNetplayLoadEvent
import eu.rekawek.coffeegb.controller.network.Connection.PeerLoadedGameEvent
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.ApplicationSettingsOverrides
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.state.StateRepository
import eu.rekawek.coffeegb.controller.state.StateWorkspace
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.persistence.HandoffAutosaveWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.system.exitProcess
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NetplayAutosaveHandoffTest {
  @get:Rule val directory = TemporaryFolder()

  @Test(timeout = 30_000)
  fun sharedSavesDoNotStrandEitherPeerDuringTheControllerHandoff() {
    val root = directory.root.toPath()
    val rom = Path.of("src/test/resources/roms/cpu_instrs.gb").toAbsolutePath()
    Peer(root, rom, true).use { host ->
      host.expect("LOADED")
      // Load the client before holding the host's write, as on the desktop.
      Peer(root, rom, false).use { client ->
        client.expect("LOADED")
        host.proceed()
        host.expect("CLOSING")
        host.expect("AUTOSAVE_READY")
        client.proceed()
        client.expect("CLOSING")
        val clientFinishedEarly = client.process.waitFor(300, TimeUnit.MILLISECONDS)
        host.proceed()
        host.expect("LINKED_FRAMES_ADVANCED")
        client.expect("LINKED_FRAMES_ADVANCED")
        host.assertSuccess()
        client.assertSuccess()
        assertFalse(clientFinishedEarly)
      }
    }
    Files.walk(root.resolve("saves")).use { paths ->
      assertTrue(paths.anyMatch { it.fileName.toString() == "state.cgbstate" })
    }
  }

  private class Peer(root: Path, rom: Path, hold: Boolean) : AutoCloseable {
    val process = ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("java.class.path"),
        NetplayAutosavePeer::class.java.name, root.toString(), rom.toString(), hold.toString(),
    ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    private val output = process.inputStream.bufferedReader()

    fun expect(expected: String) = assertEquals(
        expected, CompletableFuture.supplyAsync { output.readLine() }.get(10, TimeUnit.SECONDS))

    fun proceed() {
      process.outputStream.write(10)
      process.outputStream.flush()
    }

    fun assertSuccess() {
      assertTrue(process.waitFor(5, TimeUnit.SECONDS))
      assertEquals(0, process.exitValue())
    }

    override fun close() {
      process.destroyForcibly()
      assertTrue(process.waitFor(5, TimeUnit.SECONDS))
      output.close()
    }
  }
}

/** Also accepts an authorized local ROM path for a headless desktop-handoff reproduction. */
object NetplayAutosavePeer {
  @JvmStatic
  fun main(args: Array<String>) {
    try {
      run(Path.of(args[0]), Path.of(args[1]), args[2].toBoolean())
      exitProcess(0)
    } catch (failure: Throwable) {
      failure.printStackTrace()
      exitProcess(1)
    }
  }

  private fun run(root: Path, rom: Path, hold: Boolean) {
    val properties = EmulatorProperties(
        root.resolve("peer-$hold/settings.properties"),
        overrides = ApplicationSettingsOverrides(
            hardwareProfile = HardwareProfileRegistry.DMG, bootstrapMode = BootstrapMode.SKIP),
        debounceMillis = 0,
    )
    properties.updateApplicationSettings {
      it.copy(saves = ApplicationSettings.Saves(
          directory = root.resolve("saves"),
          resumePolicy = ApplicationSettings.ResumePolicy.NEVER,
      ))
    }
    val bus = EventBusImpl()
    val started = LinkedBlockingQueue<Controller.EmulationStartedEvent>()
    bus.register<Controller.EmulationStartedEvent>(started::add)
    bus.register<Controller.LoadRomFailedEvent> {
      System.err.println("ROM load failed (${it.kind}): ${it.technicalDetails}")
    }
    val basic = BasicController(
        bus, properties, null, RomSessionPreparer(), SnapshotManagerFactory.DEFAULT,
        RewindManager(enabled = false),
        StateWorkspaceFactory { paths ->
          StateWorkspace(paths.copy(fallbackLayouts = emptyList())) { layout ->
            StateRepository(layout, HandoffAutosaveWriter(layout.gameDirectory, hold))
          }
        },
        StateOperationWorkerFactory.DEFAULT,
    )
    basic.startController()
    bus.post(Controller.LoadRomEvent(rom.toFile()))
    assertNotNull(started.poll(10, TimeUnit.SECONDS))
    println("LOADED")
    check(System.`in`.read() != -1)
    println("CLOSING")
    val state = assertNotNull(basic.closeWithState())
    // This is the same state transfer as SwingEmulator.startLinkedController().
    val localPlayer = if (hold) 0 else 1
    val linked = LinkedController(bus, properties, null, LinkMode.NORMAL, localPlayer = localPlayer)
    try {
      linked.timingTicker.disabled = true
      bus.post(createNetplayLoadEvent(state, LinkMode.NORMAL))
      awaitSessions(linked, 1)
      val configuration = Controller.createGameboyConfig(properties, state.rom)
      bus.post(PeerLoadedGameEvent(
          rom.toFile().readBytes(), null, null, configuration.gameboyType,
          configuration.bootstrapMode, linked.currentFrame(), player = 1 - localPlayer,
      ))
      awaitSessions(linked, 2)
      val firstFrame = linked.currentFrame()
      repeat(3) { linked.runFrame() }
      assertTrue(linked.currentFrame() > firstFrame)
      println("LINKED_FRAMES_ADVANCED")
    } finally {
      linked.close()
      properties.close()
      bus.close()
    }
  }

  private fun awaitSessions(linked: LinkedController, expected: Int) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (linked.activeSessionCount() != expected && System.nanoTime() < deadline) {
      linked.runFrame()
      Thread.sleep(1)
    }
    assertEquals(expected, linked.activeSessionCount())
  }
}
