package eu.rekawek.coffeegb.emulator.session

import eu.rekawek.coffeegb.Gameboy
import eu.rekawek.coffeegb.controller.Button
import eu.rekawek.coffeegb.controller.Joypad
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.swing.emulator.session.Input
import eu.rekawek.coffeegb.swing.emulator.session.LinkedSession
import eu.rekawek.coffeegb.swing.emulator.session.LinkedSession.RemoteButtonStateEvent
import eu.rekawek.coffeegb.swing.emulator.session.StateHistory.GameboyJoypadPressEvent
import eu.rekawek.coffeegb.swing.events.register
import eu.rekawek.coffeegb.testing.RandomJoypad
import java.nio.file.Paths
import kotlin.test.assertEquals
import org.junit.Test

class LinkedSessionTest {

  @Test
  fun recordsAndRewinds() {
    val eventBus = EventBus()
    val buttons = mutableListOf<Joypad.JoypadPressEvent>()
    eventBus.register<Joypad.JoypadPressEvent> { buttons += it }
    val sut = LinkedSession(eventBus, ROM, null)
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
        EventBus().also { eb ->
          eb.register<GameboyJoypadPressEvent> { e ->
            if (e.gameboy == 0) {
              buttons += Joypad.JoypadPressEvent(e.button, e.tick)
            }
          }
        }

    eventBus.post(RemoteButtonStateEvent(1, Input(listOf(Button.UP), emptyList())))
    repeat(Gameboy.TICKS_PER_FRAME * 5) { tickRunnable.run() }

    val actualButtons = buttons.toList()

    val ticks =
        (expectedButtons.map { it.tick }.toSet() + actualButtons.map { it.tick() }.toSet())
            .toList()
            .sorted()
    for (t in ticks) {
      val exp = expectedButtons.filter { it.tick == t }.map { it.button }.sorted()
      val act = actualButtons.filter { it.tick == t }.map { it.button }.sorted()
      println("Tick $t: $exp -> $act")
      assertEquals(exp, act, "At tick $t, frame ${t/Gameboy.TICKS_PER_FRAME}")
    }
  }

  private companion object {
    val ROM = Paths.get("src/test/resources/roms/blargg", "cpu_instrs.gb").toFile()
  }
}
