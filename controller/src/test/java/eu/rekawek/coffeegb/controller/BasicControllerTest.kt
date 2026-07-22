package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.Controller.EmulationStartedEvent
import eu.rekawek.coffeegb.controller.Controller.EmulationStoppedEvent
import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.Controller.LoadRomFailedEvent
import eu.rekawek.coffeegb.controller.Controller.RomLoadingCancelledEvent
import eu.rekawek.coffeegb.controller.Controller.RomLoadingEvent
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.gpu.Display.GbcFrameReadyEvent
import eu.rekawek.coffeegb.core.memory.cart.Rom
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class BasicControllerTest {

  @Test
  fun failedLoadDoesNotPreventLoadingAnotherRom() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val failures = LinkedBlockingQueue<LoadRomFailedEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<LoadRomFailedEvent> { failures.add(it) }
    val controller = BasicController(eventBus, EmulatorProperties(), null)
    val invalidRom = Files.createTempFile("coffee-gb-invalid-rom", ".gbc").toFile()
    invalidRom.writeText("not a Game Boy ROM")

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(ROM))
      assertEquals("CPU_INSTRS", started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.romName)

      eventBus.post(LoadRomEvent(invalidRom))
      val failure = failures.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
      assertNotNull(failure, "the failed load should be reported")
      assertEquals(invalidRom, failure.rom)

      eventBus.post(LoadRomEvent(ROM))
      assertEquals(
          "CPU_INSTRS",
          started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.romName,
          "the controller thread should keep processing load requests",
      )
    } finally {
      controller.close()
      eventBus.close()
      invalidRom.delete()
    }
  }

  @Test
  fun keepsCurrentSessionRunningWhileNextRomIsPrepared() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val stopped = LinkedBlockingQueue<EmulationStoppedEvent>()
    val loading = LinkedBlockingQueue<RomLoadingEvent>()
    val frames = LinkedBlockingQueue<GbcFrameReadyEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<EmulationStoppedEvent> { stopped.add(it) }
    eventBus.register<RomLoadingEvent> { loading.add(it) }
    eventBus.register<GbcFrameReadyEvent> { frames.add(it) }

    val nextRom = namedRom("NEXT_GAME")
    val preparing = CountDownLatch(1)
    val release = CountDownLatch(1)
    val preparer =
        SessionPreparer { properties, event ->
          val config =
              Controller.createGameboyConfig(properties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
          if (event.rom == nextRom) {
            preparing.countDown()
            try {
              release.await()
            } catch (_: InterruptedException) {
              throw CancellationException("superseded")
            }
          }
          PreparedSession.Ready(config, config.build())
        }
    val controller = BasicController(eventBus, EmulatorProperties(), null, preparer)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(ROM))
      assertEquals("CPU_INSTRS", started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.romName)
      loading.clear()
      frames.clear()

      eventBus.post(LoadRomEvent(nextRom))
      assertTrue(preparing.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      assertEquals(nextRom, loading.poll(1, TimeUnit.SECONDS)?.rom)
      assertNull(stopped.poll(250, TimeUnit.MILLISECONDS), "the old session must stay active")
      assertNotNull(frames.poll(1, TimeUnit.SECONDS), "the old session should keep producing frames")

      release.countDown()
      assertEquals("NEXT_GAME", started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.romName)
    } finally {
      release.countDown()
      controller.close()
      eventBus.close()
      nextRom.delete()
    }
  }

  @Test
  fun rapidLoadBurstStartsOnlyTheLatestRom() {
    val eventBus = EventBusImpl()
    val started = LinkedBlockingQueue<EmulationStartedEvent>()
    val cancelled = LinkedBlockingQueue<RomLoadingCancelledEvent>()
    eventBus.register<EmulationStartedEvent> { started.add(it) }
    eventBus.register<RomLoadingCancelledEvent> { cancelled.add(it) }

    val firstRom = namedRom("FIRST_GAME")
    val middleRom = namedRom("MIDDLE_GAME")
    val lastRom = namedRom("LAST_GAME")
    val firstPreparationStarted = CountDownLatch(1)
    val neverReleaseFirst = CountDownLatch(1)
    val preparer =
        SessionPreparer { properties, event ->
          if (event.rom == firstRom) {
            firstPreparationStarted.countDown()
            try {
              neverReleaseFirst.await()
            } catch (_: InterruptedException) {
              throw CancellationException("superseded")
            }
          }
          val config =
              Controller.createGameboyConfig(properties, Rom(event.rom))
                  .setBootstrapMode(BootstrapMode.SKIP)
          PreparedSession.Ready(config, config.build())
        }
    val controller = BasicController(eventBus, EmulatorProperties(), null, preparer)

    controller.startController()
    try {
      eventBus.post(LoadRomEvent(firstRom))
      assertTrue(firstPreparationStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
      eventBus.post(LoadRomEvent(middleRom))
      eventBus.post(LoadRomEvent(lastRom))

      assertEquals("LAST_GAME", started.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)?.romName)
      assertNull(started.poll(500, TimeUnit.MILLISECONDS), "superseded ROMs must never start")
      assertEquals(firstRom, cancelled.poll(1, TimeUnit.SECONDS)?.rom)
      assertEquals(middleRom, cancelled.poll(1, TimeUnit.SECONDS)?.rom)
    } finally {
      controller.close()
      eventBus.close()
      firstRom.delete()
      middleRom.delete()
      lastRom.delete()
    }
  }

  private fun namedRom(title: String): File {
    val bytes = ROM.readBytes()
    for (address in 0x0134 until 0x0143) {
      bytes[address] = 0
    }
    title.toByteArray(Charsets.US_ASCII).copyInto(bytes, 0x0134, endIndex = title.length.coerceAtMost(15))
    return Files.createTempFile("coffee-gb-$title", ".gbc").toFile().also { it.writeBytes(bytes) }
  }

  private companion object {
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()

    const val TIMEOUT_SECONDS = 10L
  }
}
