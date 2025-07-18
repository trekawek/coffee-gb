package eu.rekawek.coffeegb.swing.emulator.session

import eu.rekawek.coffeegb.Gameboy
import eu.rekawek.coffeegb.debug.Console
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.memory.cart.Cartridge
import eu.rekawek.coffeegb.serial.SerialEndpoint
import eu.rekawek.coffeegb.swing.emulator.SnapshotManager
import eu.rekawek.coffeegb.swing.emulator.TimingTicker
import eu.rekawek.coffeegb.swing.emulator.session.Session.EmulationStartedEvent
import eu.rekawek.coffeegb.swing.emulator.session.Session.EmulationStoppedEvent
import java.io.File

class SimpleSession(
    private val eventBus: EventBus,
    rom: File,
    private val console: Console?,
) : Session, SnapshotSupport {

  private val cart = Cartridge(rom)

  private val snapshotManager = SnapshotManager(rom)

  private var gameboy: Gameboy? = null

  private var localEventBus: EventBus? = null

  @Synchronized
  override fun start() {
    init(Gameboy(cart))
  }

  private fun init(gameboy: Gameboy) {
    stop()

    this.gameboy = gameboy
    localEventBus = eventBus.fork("main")
    gameboy.init(localEventBus, SerialEndpoint.NULL_ENDPOINT, console)
    gameboy.registerTickListener(TimingTicker())
    Thread(gameboy).start()
    localEventBus!!.post(EmulationStartedEvent(cart.title))
  }

  @Synchronized
  override fun stop() {
    if (gameboy == null) {
      return
    }
    gameboy?.stop()
    cart.flushBattery()
    console?.setGameboy(null)
    localEventBus!!.post(EmulationStoppedEvent())
    localEventBus!!.stop()

    localEventBus = null
    gameboy = null
  }

  @Synchronized
  override fun reset() {
    stop()
    start()
  }

  override fun getRomName(): String {
    return cart.title
  }

  @Synchronized
  override fun pause() {
    gameboy?.pause()
  }

  @Synchronized
  override fun resume() {
    gameboy?.resume()
  }

  @Synchronized
  override fun saveSnapshot(slot: Int) {
    gameboy?.let { snapshotManager.saveSnapshot(slot, it) }
  }

  @Synchronized
  override fun loadSnapshot(slot: Int) {
    gameboy?.let { snapshotManager.loadSnapshot(slot, it) }
  }

  override fun snapshotAvailable(slot: Int): Boolean {
    return snapshotManager.snapshotAvailable(slot)
  }

  override fun shutDown() {
    stop()
    eventBus.stop()
  }
}
