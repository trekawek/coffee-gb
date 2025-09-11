package eu.rekawek.coffeegb.controller.link

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent
import kotlin.random.Random
import kotlin.ranges.random

class RandomJoypad(private val eventBus: EventBus, seed: Int? = null) {

  private val rand = if (seed != null) Random(seed) else Random.Default

  private val buttons = mutableSetOf<PressedButton>()

  private var tick: Long = 0

  fun tick() {
    val expired = buttons.filter { it.start + it.duration == tick }
    buttons.removeAll(expired)
    expired.forEach { eventBus.post(ButtonReleaseEvent(it.b)) }

    if (tick % INTERVAL == 0L) {
      if (rand.nextDouble() < PRESS_ARROW) {
        val allowedArrows = getAllowedArrows().toList()
        if (allowedArrows.isNotEmpty()) {
          press(allowedArrows.random())
        }
      }
      if (rand.nextDouble() < PRESS_AB) {
        press(listOf(Button.A, Button.B).random())
      }
      if (rand.nextDouble() < PRESS_START) {
        press(Button.START)
      }
      if (rand.nextDouble() < PRESS_SELECT) {
        press(Button.SELECT)
      }
    }
    tick++
  }

  private fun press(button: Button) {
    eventBus.post(ButtonPressEvent(button))
    buttons.add(PressedButton(button, tick, DURATION.random().toLong()))
  }

  private fun getAllowedArrows(): Set<Button> {
    return buildSet {
      addAll(ARROWS)
      val pressed = buttons.map { it.b }.toSet()
      if (pressed.contains(Button.LEFT)) {
        remove(Button.RIGHT)
      }
      if (pressed.contains(Button.RIGHT)) {
        remove(Button.LEFT)
      }
      if (pressed.contains(Button.DOWN)) {
        remove(Button.UP)
      }
      if (pressed.contains(Button.UP)) {
        remove(Button.DOWN)
      }
    }
  }

  private data class PressedButton(val b: Button, val start: Long, val duration: Long)

  private companion object {
    const val PRESS_ARROW = 0.4

    const val PRESS_AB = 0.2

    const val PRESS_START = 0

    const val PRESS_SELECT = 0

    private val DURATION = (Gameboy.TICKS_PER_FRAME * 2..Gameboy.TICKS_PER_FRAME * 10)

    private val INTERVAL = Gameboy.TICKS_PER_FRAME / 3

    private val ARROWS = setOf(Button.UP, Button.DOWN, Button.LEFT, Button.RIGHT)
  }
}
