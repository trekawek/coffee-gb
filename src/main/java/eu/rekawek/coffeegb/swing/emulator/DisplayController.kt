package eu.rekawek.coffeegb.swing.emulator

import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.swing.io.SwingDisplay

class DisplayController(private val eventBus: EventBus) {
  private var localScale: Int = 0

  private var localGrayscale: Boolean = false

  var scale: Int
    get() = localScale
    set(value) {
      eventBus.post(SwingDisplay.SetScale(value))
      localScale = value
    }

  var grayscale: Boolean
    get() = localGrayscale
    set(value) {
      eventBus.post(SwingDisplay.SetGrayscale(value))
      localGrayscale = value
    }
}
