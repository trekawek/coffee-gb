package eu.rekawek.coffeegb.controller.link

import eu.rekawek.coffeegb.controller.Controller.LoadRomEvent
import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.StateHistory.GameboyJoypadPressEvent
import eu.rekawek.coffeegb.controller.network.Connection.PeerLoadedGameEvent
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.Joypad
import org.junit.Test
import java.nio.file.Paths
import kotlin.test.assertEquals

class LinkedControllerTest {

  @Test
  fun localChangesAreReplayedOnRewind() {
    val eventBus = EventBusImpl()
    val buttons = mutableListOf<Joypad.JoypadPressEvent>()
    eventBus.register<Joypad.JoypadPressEvent> { buttons += it }
    val sut = LinkedController(eventBus, EmulatorProperties(), null)
    sut.timingTicker.disabled = true
    val randomJoypad = RandomJoypad(eventBus)
    eventBus.post(LoadRomEvent(ROM))
    eventBus.post(
        PeerLoadedGameEvent(ROM_BYTES, null, GameboyType.DMG, Gameboy.BootstrapMode.SKIP, 0)
    )
    repeat(100) {
      sut.runFrame()
      if (it > Gameboy.TICKS_PER_FRAME) {
        randomJoypad.tick()
      }
    }
    sut.runFrame()

    val expectedButtons = buttons.toList()
    buttons.clear()

    sut.stateHistory.debugEventBus =
        EventBusImpl().also { eb ->
          eb.register<GameboyJoypadPressEvent> { e ->
            if (e.gameboy == 0) {
              buttons += Joypad.JoypadPressEvent(e.button, e.tick)
            }
          }
        }

    eventBus.post(LinkedController.RemoteButtonStateEvent(1, Input(listOf(Button.UP), emptyList())))
    repeat(5) { sut.runFrame() }
    eventBus.close()

    val actualButtons = buttons.toList()

    assertJoypadEventsEqual(expectedButtons, actualButtons)
  }

  @Test
  fun remoteChangesAreSentCorrectly() {
    val eventBus1 = EventBusImpl()
    val buttons1 = mutableListOf<Joypad.JoypadPressEvent>()
    eventBus1.register<Joypad.JoypadPressEvent> { buttons1 += it }
    val sut1 = LinkedController(eventBus1, EmulatorProperties(), null)
    sut1.timingTicker.disabled = true
    val randomJoypad = RandomJoypad(eventBus1)
    eventBus1.post(LoadRomEvent(ROM))
    eventBus1.post(
        PeerLoadedGameEvent(ROM_BYTES, null, GameboyType.DMG, Gameboy.BootstrapMode.SKIP, 0)
    )

    val eventBus2 = EventBusImpl()
    val buttons2 = mutableListOf<Joypad.JoypadPressEvent>()
    val sut2 = LinkedController(eventBus2, EmulatorProperties(), null)
    sut2.timingTicker.disabled = true
    eventBus2.post(LoadRomEvent(ROM))
    eventBus2.post(
        PeerLoadedGameEvent(ROM_BYTES, null, GameboyType.DMG, Gameboy.BootstrapMode.SKIP, 0)
    )
    sut2.stateHistory.debugEventBus =
        EventBusImpl().also { eb ->
          eb.register<GameboyJoypadPressEvent> { e ->
            if (e.gameboy == 1) {
              buttons2 += Joypad.JoypadPressEvent(e.button, e.tick)
            }
          }
        }

    eventBus1.register<LinkedController.LocalButtonStateEvent> {
      eventBus2.post(LinkedController.RemoteButtonStateEvent(it.frame, it.input))
    }

    repeat(100) {
      sut1.runFrame()
      sut2.runFrame()
      if (it > Gameboy.TICKS_PER_FRAME) {
        randomJoypad.tick()
      }
    }
    repeat(5) {
      sut1.runFrame()
      sut2.runFrame()
    }

    assertJoypadEventsEqual(buttons1, buttons2)
  }

  @Test
  fun twoWayCommunicationProducesSameResults() {
    val eventBus1 = EventBusImpl()
    val buttons1 = mutableListOf<Joypad.JoypadPressEvent>()
    eventBus1.register<Joypad.JoypadPressEvent> { buttons1 += it }
    val sut1 = LinkedController(eventBus1, EmulatorProperties(), null)
    sut1.timingTicker.disabled = true
    val randomJoypad1 = RandomJoypad(eventBus1)
    eventBus1.post(LoadRomEvent(ROM))
    eventBus1.post(
        PeerLoadedGameEvent(ROM_BYTES, null, GameboyType.DMG, Gameboy.BootstrapMode.SKIP, 0)
    )

    val eventBus2 = EventBusImpl()
    val buttons2 = mutableListOf<Joypad.JoypadPressEvent>()
    val sut2 = LinkedController(eventBus2, EmulatorProperties(), null)
    sut2.timingTicker.disabled = true
    val randomJoypad2 = RandomJoypad(eventBus2)
    eventBus2.post(LoadRomEvent(ROM))
    eventBus2.post(
        PeerLoadedGameEvent(ROM_BYTES, null, GameboyType.DMG, Gameboy.BootstrapMode.SKIP, 0)
    )
    sut2.stateHistory.debugEventBus =
        EventBusImpl().also { eb ->
          eb.register<GameboyJoypadPressEvent> { e ->
            if (e.gameboy == 1) {
              buttons2 += Joypad.JoypadPressEvent(e.button, e.tick)
            }
          }
        }

    eventBus1.register<LinkedController.LocalButtonStateEvent> {
      eventBus2.post(LinkedController.RemoteButtonStateEvent(it.frame, it.input))
    }
    eventBus2.register<LinkedController.LocalButtonStateEvent> {
      eventBus1.post(LinkedController.RemoteButtonStateEvent(it.frame, it.input))
    }

    repeat(100) {
      sut1.runFrame()
      sut2.runFrame()
      randomJoypad1.tick()
      randomJoypad2.tick()
    }
    repeat(5) {
      sut1.runFrame()
      sut2.runFrame()
    }

    assertJoypadEventsEqual(buttons1, buttons2)
  }

  private companion object {
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()

    val ROM_BYTES = ROM.readBytes()

    fun assertJoypadEventsEqual(
        expectedButtons: List<Joypad.JoypadPressEvent>,
        actualButtons: List<Joypad.JoypadPressEvent>,
    ) {
      val ticks =
          (expectedButtons.map { it.tick }.toSet() + actualButtons.map { it.tick() }.toSet())
              .toList()
              .sorted()
      for (t in ticks) {
        val exp = expectedButtons.filter { it.tick == t }.map { it.button }.sorted()
        val act = actualButtons.filter { it.tick == t }.map { it.button }.sorted()
        assertEquals(exp, act, "At tick $t, frame ${t/Gameboy.TICKS_PER_FRAME}")
      }
    }
  }
}
