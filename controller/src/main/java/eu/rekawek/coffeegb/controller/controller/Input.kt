package eu.rekawek.coffeegb.controller.controller

import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent
import eu.rekawek.coffeegb.core.events.EventBus

data class Input(val pressedButtons: List<Button>, val releasedButtons: List<Button>) {
  fun isEmpty() = pressedButtons.isEmpty() && releasedButtons.isEmpty()

  fun send(eventBus: EventBus) {
    pressedButtons.forEach { eventBus.post(ButtonPressEvent(it)) }
    releasedButtons.forEach { eventBus.post(ButtonReleaseEvent(it)) }
  }
}
