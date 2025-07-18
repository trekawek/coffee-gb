package eu.rekawek.coffeegb.swing.emulator.session

import eu.rekawek.coffeegb.events.Event

interface Session {
  fun start()

  fun stop()

  fun reset()

  fun pause()

  fun resume()

  fun getRomName(): String

  fun shutDown()

    class EmulationStartedEvent(val romName: String) : Event

  class EmulationStoppedEvent : Event
}
