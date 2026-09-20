package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.controller.state.StateUxSessionEvent
import eu.rekawek.coffeegb.controller.state.bess.BessLoadRequestEvent
import eu.rekawek.coffeegb.controller.state.bess.BessOperationCompletedEvent
import eu.rekawek.coffeegb.controller.state.bess.BessOperationFailedEvent
import eu.rekawek.coffeegb.controller.state.bess.BessSaveRequestEvent
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.debug.DebugAddressSpace
import eu.rekawek.coffeegb.core.debug.DebugMemoryRequest
import eu.rekawek.coffeegb.core.debug.DebugMemoryWrite
import eu.rekawek.coffeegb.core.debug.DebugPort
import eu.rekawek.coffeegb.core.debug.DebugStepKind
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.state.bess.BessCodec
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BasicControllerBessTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun `DMG BESS save and load restore memory and retain desktop pause`() = roundTrip(cgb = false)

  @Test
  fun `CGB BESS save and load restore memory and retain desktop pause`() = roundTrip(cgb = true)

  @Test
  fun `default monochrome hardware supports BESS save and load`() =
      roundTrip(cgb = false, automaticHardware = true)

  @Test
  fun `BESS operations complete while the debugger is paused between frames`() {
    Fixture(temporary.newFolder().toPath()).use { fixture ->
      fixture.open()
      val stepped = await(fixture.port.step(DebugStepKind.INSTRUCTION)).value()
      assertTrue(stepped.snapshot().framePosition() > 0)
      val path = fixture.directory.resolve("debugger.bess")
      fixture.save(path)
      assertTrue(await(fixture.port.snapshot()).value().paused())

      await(fixture.port.step(DebugStepKind.INSTRUCTION))
      fixture.eventBus.post(BessLoadRequestEvent(path, fixture.sessionId))
      assertEquals(BessOperationCompletedEvent(path, true), fixture.awaitCompleted())
      assertTrue(await(fixture.port.snapshot()).value().paused())
    }
  }

  private fun roundTrip(cgb: Boolean, automaticHardware: Boolean = false) {
    Fixture(temporary.newFolder().toPath(), automaticHardware).use { fixture ->
      fixture.open(cgb = cgb)
      fixture.writeRam(0x5a)
      val beforeSave = await(fixture.port.snapshot()).value()
      val path = fixture.directory.resolve("portable.bess")
      fixture.save(path)
      assertEquals(beforeSave.masterTick(), await(fixture.port.snapshot()).value().masterTick())

      val bess = BessCodec.read(Files.readAllBytes(path))
      assertEquals(0x5a, bess.core().ram()[0].toInt() and 0xff)
      assertEquals(if (cgb) 'C' else if (automaticHardware) 'S' else 'G', bess.core().model()[0])
      fixture.writeRam(0x17)
      assertEquals(0x17, fixture.readRam())

      fixture.eventBus.post(BessLoadRequestEvent(path, fixture.sessionId))
      assertEquals(BessOperationCompletedEvent(path, true), fixture.awaitCompleted())
      assertEquals(0x5a, fixture.readRam())
      assertTrue(await(fixture.port.snapshot()).value().paused())
      assertTrue(fixture.failures.isEmpty())
    }
  }

  @Test
  fun `malformed BESS load leaves the active paused machine usable`() {
    Fixture(temporary.newFolder().toPath()).use { fixture ->
      fixture.open()
      fixture.writeRam(0x39)
      val path = fixture.directory.resolve("malformed.bess")
      Files.write(path, byteArrayOf(1, 2, 3, 4))

      fixture.eventBus.post(BessLoadRequestEvent(path, fixture.sessionId))
      assertTrue(fixture.awaitFailure().message.contains("loaded"))
      assertEquals(0x39, fixture.readRam())
      assertTrue(await(fixture.port.snapshot()).value().paused())
      assertTrue(fixture.completed.isEmpty())

      fixture.save(fixture.directory.resolve("after-failure.bess"))
    }
  }

  @Test
  fun `requests from a replaced session neither load nor save the new game`() {
    Fixture(temporary.newFolder().toPath()).use { fixture ->
      fixture.open()
      val staleSessionId = fixture.sessionId
      fixture.open(seed = 1)
      assertTrue(fixture.sessionId != staleSessionId)
      fixture.writeRam(0x42)
      val path = fixture.directory.resolve("stale.bess")

      fixture.eventBus.post(BessSaveRequestEvent(path, staleSessionId))
      assertTrue(fixture.awaitFailure().message.contains("same active local game"))
      fixture.eventBus.post(BessLoadRequestEvent(path, staleSessionId))
      assertTrue(fixture.awaitFailure().message.contains("same active local game"))

      assertFalse(Files.exists(path))
      assertEquals(0x42, fixture.readRam())
      assertTrue(fixture.completed.isEmpty())
      fixture.save(path)
    }
  }

  @Test
  fun `BESS from another ROM is rejected before changing live memory`() {
    Fixture(temporary.newFolder().toPath()).use { fixture ->
      fixture.open()
      fixture.writeRam(0x21)
      val path = fixture.directory.resolve("first-game.bess")
      fixture.save(path)
      fixture.open(seed = 1)
      fixture.writeRam(0x67)

      fixture.eventBus.post(BessLoadRequestEvent(path, fixture.sessionId))
      fixture.awaitFailure()

      assertEquals(0x67, fixture.readRam())
      assertTrue(await(fixture.port.snapshot()).value().paused())
      assertTrue(fixture.completed.isEmpty())
    }
  }

  private class Fixture(val directory: Path, automaticHardware: Boolean = false) : AutoCloseable {
    val eventBus = EventBusImpl()
    val completed = LinkedBlockingQueue<BessOperationCompletedEvent>()
    val failures = LinkedBlockingQueue<BessOperationFailedEvent>()
    private val sessions = LinkedBlockingQueue<StateUxSessionEvent>()
    private val ports = LinkedBlockingQueue<Controller.SessionDebugPortEvent>()
    private val properties =
        EmulatorProperties(directory.resolve("settings.properties"), debounceMillis = 0).also {
          it.updateApplicationSettings { settings ->
            settings.copy(
                advanced = settings.advanced.copy(
                    bootstrapMode = Gameboy.BootstrapMode.SKIP,
                    dmgGamesProfile =
                        if (automaticHardware) ApplicationSettings.ProfileSelection.Auto
                        else ApplicationSettings.ProfileSelection.Explicit(HardwareProfileRegistry.DMG),
                    cgbGamesProfile = ApplicationSettings.ProfileSelection.Explicit(HardwareProfileRegistry.CGB),
                ),
                saves = settings.saves.copy(
                    directory = directory.resolve("saves"),
                    resumePolicy = ApplicationSettings.ResumePolicy.NEVER,
                ),
            )
          }
        }
    private val controller = BasicController(eventBus, properties, null)
    lateinit var port: DebugPort
      private set
    var sessionId = 0L
      private set

    init {
      eventBus.register<BessOperationCompletedEvent>(completed::add)
      eventBus.register<BessOperationFailedEvent>(failures::add)
      eventBus.register<StateUxSessionEvent> { if (it.available) sessions.add(it) }
      eventBus.register<Controller.SessionDebugPortEvent> { if (it.debugPort != null) ports.add(it) }
      controller.startController()
    }

    fun open(seed: Int = 0, cgb: Boolean = false) {
      val rom = directory.resolve("game-$seed-${if (cgb) "cgb" else "dmg"}.gb")
      Files.write(rom, StateCodecTestSupport.rom(seed, cgb))
      eventBus.post(Controller.LoadRomEvent(rom.toFile()))
      sessionId = assertNotNull(sessions.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).sessionId
      port = assertNotNull(assertNotNull(ports.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)).debugPort)
      assertTrue(await(port.pause()).isSuccess)
      assertTrue(await(port.step(DebugStepKind.FRAME)).isSuccess)
    }

    fun writeRam(value: Int) {
      assertTrue(await(port.writeMemory(DebugMemoryWrite(DebugAddressSpace.WORK_RAM, 0xc000, value))).isSuccess)
    }

    fun readRam(): Int =
        await(port.readMemory(DebugMemoryRequest(DebugAddressSpace.WORK_RAM, 0xc000, 1)))
            .value().unsignedByteAt(0)

    fun save(path: Path) {
      eventBus.post(BessSaveRequestEvent(path, sessionId))
      assertEquals(BessOperationCompletedEvent(path, false), awaitCompleted())
    }

    fun awaitCompleted(): BessOperationCompletedEvent =
        assertNotNull(completed.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS), "BESS operation failed: ${failures.peek()}")

    fun awaitFailure(): BessOperationFailedEvent =
        assertNotNull(failures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS))

    override fun close() {
      try {
        controller.close()
      } finally {
        eventBus.close()
        properties.close()
      }
    }
  }

  companion object {
    private const val TIMEOUT_SECONDS = 10L

    private fun <T> await(future: CompletionStage<T>): T =
        future.toCompletableFuture().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
  }
}
