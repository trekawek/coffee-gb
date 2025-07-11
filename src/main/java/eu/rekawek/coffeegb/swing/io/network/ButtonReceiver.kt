package eu.rekawek.coffeegb.swing.io.network

import eu.rekawek.coffeegb.controller.ButtonPressEvent
import eu.rekawek.coffeegb.controller.ButtonReleaseEvent
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.swing.emulator.SwingEmulator.RunTicksEvent
import eu.rekawek.coffeegb.swing.events.register
import eu.rekawek.coffeegb.swing.io.network.Connection.PeerButtonEvents

object ButtonReceiver {
  fun peerToLocalButtons(eventBus: EventBus) {
    eventBus.register<PeerButtonEvents> { e ->
      var currentTick = 0
      for (b in e.buttonEvents) {
        if (b.tick > currentTick) {
          val ticks = b.tick - currentTick
          eventBus.post(RunTicksEvent(ticks))
          currentTick += ticks
        }
        if (b.buttonPressed != null) {
          eventBus.post(ButtonPressEvent(b.buttonPressed))
        }
        if (b.buttonReleased != null) {
          eventBus.post(ButtonReleaseEvent(b.buttonReleased))
        }
      }
      if (currentTick < e.ticks) {
        eventBus.post(RunTicksEvent(e.ticks - currentTick))
      }
    }
  }
}
