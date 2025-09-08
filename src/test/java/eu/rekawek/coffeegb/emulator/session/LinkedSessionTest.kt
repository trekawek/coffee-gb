package eu.rekawek.coffeegb.emulator.session

import eu.rekawek.coffeegb.Gameboy
import eu.rekawek.coffeegb.joypad.Button
import eu.rekawek.coffeegb.joypad.Joypad
import eu.rekawek.coffeegb.events.EventBusImpl
import eu.rekawek.coffeegb.memory.cart.Rom
import eu.rekawek.coffeegb.swing.controller.Input
import eu.rekawek.coffeegb.swing.controller.LinkedController
import eu.rekawek.coffeegb.swing.controller.LinkedController.LocalButtonStateEvent
import eu.rekawek.coffeegb.swing.controller.LinkedController.RemoteButtonStateEvent
import eu.rekawek.coffeegb.swing.controller.StateHistory.GameboyJoypadPressEvent
import eu.rekawek.coffeegb.swing.events.register
import eu.rekawek.coffeegb.testing.RandomJoypad
import java.nio.file.Paths
import kotlin.test.assertEquals
import org.junit.Test

class LinkedSessionTest {

  @Test
  fun localChangesAreReplayedOnRewind() {
    val eventBus = EventBusImpl()
    val buttons = mutableListOf<Joypad.JoypadPressEvent>()
    eventBus.register<Joypad.JoypadPressEvent> { buttons += it }
    val mainConfig = Gameboy.GameboyConfiguration(ROM)
    val peerConfig = Gameboy.GameboyConfiguration(Rom(ROM_BYTES))
    val sut = LinkedController(eventBus, mainConfig, peerConfig, null)
    val randomJoypad = RandomJoypad(eventBus)
    val tickRunnable = sut.init()
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

    eventBus.post(RemoteButtonStateEvent(1, Input(listOf(Button.UP), emptyList())))
    repeat(Gameboy.TICKS_PER_FRAME * 5) { tickRunnable.run() }

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
    val sut1 = LinkedController(eventBus1, mainConfig, peerConfig, null)
    val randomJoypad = RandomJoypad(eventBus1)
    val tickRunnable1 = sut1.init()

    val eventBus2 = EventBusImpl()
    val buttons2 = mutableListOf<Joypad.JoypadPressEvent>()
    val sut2 = LinkedController(eventBus2, mainConfig, peerConfig, null)
    val tickRunnable2 = sut2.init()
    sut2.stateHistory!!.debugEventBus =
        EventBusImpl().also { eb ->
          eb.register<GameboyJoypadPressEvent> { e ->
            if (e.gameboy == 1) {
              buttons2 += Joypad.JoypadPressEvent(e.button, e.tick)
            }
          }
        }

    eventBus1.register<LocalButtonStateEvent> {
      eventBus2.post(RemoteButtonStateEvent(it.frame, it.input))
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
    val sut1 = LinkedController(eventBus1, mainConfig, peerConfig, null)
    val randomJoypad1 = RandomJoypad(eventBus1)
    val tickRunnable1 = sut1.init()

    val eventBus2 = EventBusImpl()
    val buttons2 = mutableListOf<Joypad.JoypadPressEvent>()
    val sut2 = LinkedController(eventBus2, mainConfig, peerConfig, null)
    val randomJoypad2 = RandomJoypad(eventBus2)
    val tickRunnable2 = sut2.init()
    sut2.stateHistory!!.debugEventBus =
        EventBusImpl().also { eb ->
          eb.register<GameboyJoypadPressEvent> { e ->
            if (e.gameboy == 1) {
              buttons2 += Joypad.JoypadPressEvent(e.button, e.tick)
            }
          }
        }

    eventBus1.register<LocalButtonStateEvent> {
      eventBus2.post(RemoteButtonStateEvent(it.frame, it.input))
    }
    eventBus2.register<LocalButtonStateEvent> {
      eventBus1.post(RemoteButtonStateEvent(it.frame, it.input))
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
    val ROM = Paths.get("src/test/resources/roms/blargg", "cpu_instrs.gb").toFile()

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
