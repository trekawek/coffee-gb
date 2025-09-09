package eu.rekawek.coffeegb.swing.controller

import eu.rekawek.coffeegb.Gameboy
import eu.rekawek.coffeegb.debug.Console
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.serial.SerialEndpoint

internal class Session(
  val config: Gameboy.GameboyConfiguration,
  val eventBus: EventBus,
  private val console: Console?,
  internal val serialEndpoint: SerialEndpoint = SerialEndpoint.NULL_ENDPOINT,
) : AutoCloseable {

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
}
