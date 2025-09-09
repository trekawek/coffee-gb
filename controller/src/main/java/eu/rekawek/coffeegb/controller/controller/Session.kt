package eu.rekawek.coffeegb.controller.controller

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.memento.Memento
import eu.rekawek.coffeegb.core.memento.Originator
import eu.rekawek.coffeegb.core.serial.SerialEndpoint

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
