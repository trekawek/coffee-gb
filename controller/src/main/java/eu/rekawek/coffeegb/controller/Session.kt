package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memento.Memento
import eu.rekawek.coffeegb.core.memento.Originator
import eu.rekawek.coffeegb.core.serial.SerialEndpoint

class Session(
    val config: Gameboy.GameboyConfiguration,
    val eventBus: EventBus,
    private val console: Console?,
    serialEndpoint: SerialEndpoint = SerialEndpoint.NULL_ENDPOINT,
    infraredEndpoint: InfraredEndpoint = InfraredEndpoint.NULL_ENDPOINT,
    prebuiltGameboy: Gameboy? = null,
) : AutoCloseable, Originator<Session> {

  internal val gameboy: Gameboy = prebuiltGameboy ?: config.build()

  internal var serialEndpoint: SerialEndpoint = serialEndpoint
    private set

  init {
    gameboy.init(eventBus, serialEndpoint, infraredEndpoint, console)
  }

  /** Hot-swaps the link-port device (e.g. connecting the printer) without a reset. */
  fun setSerialEndpoint(endpoint: SerialEndpoint) {
    serialEndpoint = endpoint
    gameboy.setSerialEndpoint(endpoint)
  }

  override fun close() {
    gameboy.stop()
    gameboy.close()
    console?.setGameboy(null)
    eventBus.close()
  }

  // held buttons live outside the memento (the joypad keeps physical input across a
  // single-player rewind); netplay snapshots them separately so a held button survives a rebase
  var heldButtons: Set<Button>
    get() = gameboy.pressedButtons
    set(value) {
      gameboy.setPressedButtons(value)
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

  internal data class SessionMemento(
      val gameboyMemento: Memento<Gameboy>,
      val serialEndpointMemento: Memento<SerialEndpoint>?
  ) : Memento<Session>
}
