package eu.rekawek.coffeegb.swing.emulator.session

import eu.rekawek.coffeegb.events.Event

interface Session {
  fun start()

  fun stop()

  fun reset()

  fun getRomName(): String

  class EmulationStartedEvent(val romName: String) : Event

  class EmulationStoppedEvent : Event
}
