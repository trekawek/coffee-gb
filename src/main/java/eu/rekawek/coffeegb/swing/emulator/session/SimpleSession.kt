package eu.rekawek.coffeegb.swing.emulator.session

import eu.rekawek.coffeegb.Gameboy
import eu.rekawek.coffeegb.GameboyType
import eu.rekawek.coffeegb.debug.Console
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.memory.cart.Cartridge
import eu.rekawek.coffeegb.memory.cart.Rom
import eu.rekawek.coffeegb.serial.SerialEndpoint
import eu.rekawek.coffeegb.swing.emulator.SnapshotManager
import eu.rekawek.coffeegb.swing.emulator.TimingTicker
import eu.rekawek.coffeegb.swing.emulator.session.Session.EmulationStartedEvent
import eu.rekawek.coffeegb.swing.emulator.session.Session.EmulationStoppedEvent
import java.io.File

class SimpleSession(
    private val eventBus: EventBus,
    romFile: File,
    private val console: Console?,
) : Session, SnapshotSupport {

  private val rom = Rom(romFile)

  private var cart: Cartridge? = null

  private val snapshotManager = SnapshotManager(romFile)

  private var gameboy: Gameboy? = null

  private var localEventBus: EventBus? = null

  @Synchronized
  override fun start() {
    val config = Gameboy.GameboyConfiguration(rom)
    config.setGameboyType(GameboyType.CGB)

    if (rom.gameboyColorFlag == Rom.GameboyColorFlag.NON_CGB) {
      if (rom.isSuperGameboyFlag && SUPPORT_SGB) {
        config.setBootstrapMode(Gameboy.BootstrapMode.NORMAL)
        config.setGameboyType(GameboyType.SGB)
      } else {
        config.setBootstrapMode(Gameboy.BootstrapMode.NORMAL)
        config.setGameboyType(GameboyType.CGB)
      }
    }

    gameboy = config.build()
    localEventBus = eventBus.fork("main")
    gameboy?.init(localEventBus, SerialEndpoint.NULL_ENDPOINT, console)
    gameboy?.registerTickListener(TimingTicker())
    Thread(gameboy).start()
    localEventBus!!.post(EmulationStartedEvent(rom.title))
  }

  @Synchronized
  override fun stop() {
    if (gameboy == null) {
      return
    }
    gameboy?.stop()
    gameboy?.close()
    cart?.flushBattery()
    console?.setGameboy(null)
    localEventBus!!.post(EmulationStoppedEvent())
    localEventBus!!.stop()

    localEventBus = null
    gameboy = null
    cart = null
  }

  @Synchronized
  override fun reset() {
    stop()
    start()
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

  private companion object {
    const val SUPPORT_SGB = false
  }
}
