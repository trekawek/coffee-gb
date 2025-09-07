package eu.rekawek.coffeegb.swing.session

import eu.rekawek.coffeegb.controller.Button
import eu.rekawek.coffeegb.controller.ButtonPressEvent
import eu.rekawek.coffeegb.controller.ButtonReleaseEvent
import eu.rekawek.coffeegb.events.EventBus

data class Input(val pressedButtons: List<Button>, val releasedButtons: List<Button>) {
  fun isEmpty() = pressedButtons.isEmpty() && releasedButtons.isEmpty()

  fun send(eventBus: EventBus) {
    pressedButtons.forEach { eventBus.post(ButtonPressEvent(it)) }
    releasedButtons.forEach { eventBus.post(ButtonReleaseEvent(it)) }
  }
}
