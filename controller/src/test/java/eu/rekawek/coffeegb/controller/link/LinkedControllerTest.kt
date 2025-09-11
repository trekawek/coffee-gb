package eu.rekawek.coffeegb.controller.link

import eu.rekawek.coffeegb.controller.Input
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.link.StateHistory.GameboyJoypadPressEvent
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.Joypad
import eu.rekawek.coffeegb.core.memory.cart.Rom
import org.junit.Test
import java.nio.file.Paths
import kotlin.test.assertEquals

class LinkedSessionTest {

  @Test
  fun localChangesAreReplayedOnRewind() {
    val eventBus = EventBusImpl()
    val buttons = mutableListOf<Joypad.JoypadPressEvent>()
    eventBus.register<Joypad.JoypadPressEvent> { buttons += it }
    val mainConfig = Gameboy.GameboyConfiguration(ROM)
    val peerConfig = Gameboy.GameboyConfiguration(Rom(ROM_BYTES))
    val sut = LinkedController(eventBus, EmulatorProperties(), null)
    val randomJoypad = RandomJoypad(eventBus)
    val tickRunnable = sut.init(mainConfig, peerConfig)
    repeat(Gameboy.TICKS_PER_FRAME * 100) {
      tickRunnable.run()
      if (it > Gameboy.TICKS_PER_FRAME) {
        randomJoypad.tick()
      }
    }
    repeat(Gameboy.TICKS_PER_FRAME) { tickRunnable.run() }

    val expectedButtons = buttons.toList()
    buttons.clear()

    sut.stateHistory!!.debugEventBus =
        EventBusImpl().also { eb ->
          eb.register<GameboyJoypadPressEvent> { e ->
            if (e.gameboy == 0) {
              buttons += Joypad.JoypadPressEvent(e.button, e.tick)
            }
          }
        }

    eventBus.post(LinkedController.RemoteButtonStateEvent(1, Input(listOf(Button.UP), emptyList())))
    repeat(Gameboy.TICKS_PER_FRAME * 5) { tickRunnable.run() }
    eventBus.close()

    val actualButtons = buttons.toList()

    assertJoypadEventsEqual(expectedButtons, actualButtons)
  }

  @Test
  fun remoteChangesAreSentCorrectly() {
    val eventBus1 = EventBusImpl()
    val buttons1 = mutableListOf<Joypad.JoypadPressEvent>()
    eventBus1.register<Joypad.JoypadPressEvent> { buttons1 += it }
    val mainConfig = Gameboy.GameboyConfiguration(ROM)
    val peerConfig = Gameboy.GameboyConfiguration(Rom(ROM_BYTES))
    val sut1 = LinkedController(eventBus1, EmulatorProperties(), null)
    val randomJoypad = RandomJoypad(eventBus1)
    val tickRunnable1 = sut1.init(mainConfig, peerConfig)

    val eventBus2 = EventBusImpl()
    val buttons2 = mutableListOf<Joypad.JoypadPressEvent>()
    val sut2 = LinkedController(eventBus2, EmulatorProperties(), null)
    val tickRunnable2 = sut2.init(mainConfig, peerConfig)
    sut2.stateHistory!!.debugEventBus =
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

    repeat(Gameboy.TICKS_PER_FRAME * 100) {
      tickRunnable1.run()
      tickRunnable2.run()
      if (it > Gameboy.TICKS_PER_FRAME) {
        randomJoypad.tick()
      }
    }
    repeat(Gameboy.TICKS_PER_FRAME * 5) {
      tickRunnable1.run()
      tickRunnable2.run()
    }

    assertJoypadEventsEqual(buttons1, buttons2)
  }

  @Test
  fun twoWayCommunicationProducesSameResults() {
    val eventBus1 = EventBusImpl()
    val buttons1 = mutableListOf<Joypad.JoypadPressEvent>()
    eventBus1.register<Joypad.JoypadPressEvent> { buttons1 += it }
    val mainConfig = Gameboy.GameboyConfiguration(ROM)
    val peerConfig = Gameboy.GameboyConfiguration(Rom(ROM_BYTES))
    val sut1 = LinkedController(eventBus1, EmulatorProperties(), null)
    val randomJoypad1 = RandomJoypad(eventBus1)
    val tickRunnable1 = sut1.init(mainConfig, peerConfig)

    val eventBus2 = EventBusImpl()
    val buttons2 = mutableListOf<Joypad.JoypadPressEvent>()
    val sut2 = LinkedController(eventBus2, EmulatorProperties(), null)
    val randomJoypad2 = RandomJoypad(eventBus2)
    val tickRunnable2 = sut2.init(mainConfig, peerConfig)
    sut2.stateHistory!!.debugEventBus =
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

    repeat(Gameboy.TICKS_PER_FRAME * 100) {
      tickRunnable1.run()
      tickRunnable2.run()
      if (it > Gameboy.TICKS_PER_FRAME) {
        randomJoypad1.tick()
        randomJoypad2.tick()
      }
    }
    repeat(Gameboy.TICKS_PER_FRAME * 5) {
      tickRunnable1.run()
      tickRunnable2.run()
    }

    assertJoypadEventsEqual(buttons1, buttons2)
  }

  private companion object {
    val ROM = Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile()

    val ROM_BYTES = ROM.readBytes()

    fun assertJoypadEventsEqual(
        expectedButtons: List<Joypad.JoypadPressEvent>,
        actualButtons: List<Joypad.JoypadPressEvent>
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
