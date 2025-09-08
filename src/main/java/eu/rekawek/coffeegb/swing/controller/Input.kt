package eu.rekawek.coffeegb.swing.controller

import eu.rekawek.coffeegb.joypad.Button
import eu.rekawek.coffeegb.joypad.ButtonPressEvent
import eu.rekawek.coffeegb.joypad.ButtonReleaseEvent
import eu.rekawek.coffeegb.events.EventBus

data class Input(val pressedButtons: List<Button>, val releasedButtons: List<Button>) {
  fun isEmpty() = pressedButtons.isEmpty() && releasedButtons.isEmpty()

  fun send(eventBus: EventBus) {
    pressedButtons.forEach { eventBus.post(ButtonPressEvent(it)) }
    releasedButtons.forEach { eventBus.post(ButtonReleaseEvent(it)) }
  }
}
