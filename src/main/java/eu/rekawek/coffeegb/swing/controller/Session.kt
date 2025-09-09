package eu.rekawek.coffeegb.swing.controller

import eu.rekawek.coffeegb.Gameboy
import eu.rekawek.coffeegb.debug.Console
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.memento.Memento
import eu.rekawek.coffeegb.memento.Originator
import eu.rekawek.coffeegb.serial.SerialEndpoint

class Session(
    val config: Gameboy.GameboyConfiguration,
    val eventBus: EventBus,
    private val console: Console?,
    internal val serialEndpoint: SerialEndpoint = SerialEndpoint.NULL_ENDPOINT,
) : AutoCloseable, Originator<Session> {

  internal val gameboy: Gameboy = config.build()

  init {
    gameboy.init(eventBus, serialEndpoint, console)
  }

  override fun close() {
    gameboy.stop()
    gameboy.close()
    console?.setGameboy(null)
    eventBus.close()
  }

  override fun saveToMemento(): Memento<Session> {
    return SessionMemento(gameboy.saveToMemento(), serialEndpoint.saveToMemento())
  }

  override fun restoreFromMemento(memento: Memento<Session>) {
    if (memento !is SessionMemento) {
      throw IllegalArgumentException("Invalid memento")
    }
    gameboy.restoreFromMemento(memento.gameboyMemento)
    serialEndpoint.restoreFromMemento(memento.serialEndpointMemento)
  }

  private data class SessionMemento(
      val gameboyMemento: Memento<Gameboy>,
      val serialEndpointMemento: Memento<SerialEndpoint>?
  ) : Memento<Session>
}
