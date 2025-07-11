package eu.rekawek.coffeegb.swing.io.network

import eu.rekawek.coffeegb.Gameboy.TICKS_PER_SEC
import eu.rekawek.coffeegb.controller.ButtonPressEvent
import eu.rekawek.coffeegb.controller.ButtonReleaseEvent
import eu.rekawek.coffeegb.events.Event
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.swing.events.register

class ButtonSender(private val eventBus: EventBus) : Runnable {

  private var tick = 0

  private val buttonEvents = mutableListOf<Connection.ButtonEvent>()

  init {
    eventBus.register<ButtonPressEvent> {
      synchronized(this) { buttonEvents.add(Connection.ButtonEvent(tick, it.button, null)) }
    }
    eventBus.register<ButtonReleaseEvent> {
      synchronized(this) { buttonEvents.add(Connection.ButtonEvent(tick, null, it.button)) }
    }
  }

  override fun run() {
    tick++
    if (tick == UPDATE_TICKS) {
      eventBus.post(SendSyncMessage(UPDATE_TICKS, buttonEvents.toList()))
      tick = 0
      buttonEvents.clear()
    }
  }

  data class SendSyncMessage(val ticks: Int, val events: List<Connection.ButtonEvent>) : Event

  private companion object {
    const val UPDATE_MILLIS = 40

    const val UPDATE_TICKS = TICKS_PER_SEC / 1000 * UPDATE_MILLIS
  }
}
