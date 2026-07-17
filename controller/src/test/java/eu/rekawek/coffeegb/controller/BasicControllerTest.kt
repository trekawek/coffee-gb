package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.Controller.EmulationStartedEvent
import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.Controller.LoadRomFailedEvent
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.events.EventBusImpl
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

  private companion object {
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()

    const val TIMEOUT_SECONDS = 10L
  }
}
