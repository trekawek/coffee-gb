package eu.rekawek.coffeegb.swing.session

import eu.rekawek.coffeegb.Gameboy
import eu.rekawek.coffeegb.Gameboy.GameboyConfiguration
import eu.rekawek.coffeegb.debug.Console
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.memory.cart.Cartridge
import eu.rekawek.coffeegb.memory.cart.Rom
import eu.rekawek.coffeegb.serial.SerialEndpoint
import eu.rekawek.coffeegb.swing.events.register
import eu.rekawek.coffeegb.swing.gui.properties.EmulatorProperties
import eu.rekawek.coffeegb.swing.session.Session.Companion.getGameboyType
import eu.rekawek.coffeegb.swing.session.Session.EmulationStartedEvent
import eu.rekawek.coffeegb.swing.session.Session.EmulationStoppedEvent

class SimpleSession(
    parentEventBus: EventBus,
    properties: EmulatorProperties,
    private val console: Console?,
) : Session, SnapshotSupport {

  private val eventBus: EventBus = parentEventBus.fork("session")

  private var config: GameboyConfiguration? = null

  private var cart: Cartridge? = null

  private var snapshotManager: SnapshotManager? = null

  private var gameboy: Gameboy? = null

  private var localEventBus: EventBus? = null

  init {
    eventBus.register<Session.LoadRomEvent> {
      stop()
      val config = Session.createGameboyConfig(properties, Rom(it.rom))
      start(config)
    }

    eventBus.register<Session.StartEmulationEvent> { start(config) }
    eventBus.register<Session.RestoreSnapshotEvent> { e -> loadSnapshot(e.slot) }
    eventBus.register<Session.SaveSnapshotEvent> { e -> saveSnapshot(e.slot) }
    eventBus.register<Session.PauseEmulationEvent> { pause() }
    eventBus.register<Session.ResumeEmulationEvent> { resume() }
    eventBus.register<Session.ResetEmulationEvent> { reset() }
    eventBus.register<Session.StopEmulationEvent> { stop() }
    eventBus.register<Session.UpdatedSystemMappingEvent> {
      if (config != null) {
        val newType = getGameboyType(properties.system, config!!.rom)
        if (newType != config!!.gameboyType) {
          eventBus.post(Session.LoadRomEvent(config!!.rom.file))
        }
      }
    }
  }

  @Synchronized
  fun start(config: GameboyConfiguration?) {
    if (config == null) {
      return
    }

    this.config = config
    snapshotManager = SnapshotManager(config.rom.file)
    localEventBus = eventBus.fork("main")
    localEventBus?.post(Session.GameboyTypeEvent(config.gameboyType))

    gameboy = config.build()
    gameboy?.init(localEventBus, SerialEndpoint.NULL_ENDPOINT, console)
    gameboy?.registerTickListener(TimingTicker())
    Thread(gameboy).start()

    localEventBus?.post(Session.SessionPauseSupportEvent(true))
    localEventBus?.post(Session.SessionSnapshotSupportEvent(this))
    localEventBus?.post(EmulationStartedEvent(config.rom.title))
  }

  @Synchronized
  fun stop() {
    if (gameboy == null) {
      return
    }
    gameboy?.stop()
    gameboy?.close()
    cart?.flushBattery()
    console?.setGameboy(null)
    localEventBus!!.post(EmulationStoppedEvent())
    localEventBus!!.close()

    localEventBus = null
    gameboy = null
    cart = null
  }

  @Synchronized
  fun reset() {
    stop()
    start(config)
  }

  @Synchronized
  fun pause() {
    gameboy?.pause()
  }

  @Synchronized
  fun resume() {
    gameboy?.resume()
  }

  @Synchronized
  override fun saveSnapshot(slot: Int) {
    gameboy?.let { snapshotManager?.saveSnapshot(slot, it) }
  }

  @Synchronized
  override fun loadSnapshot(slot: Int) {
    start(config)
    gameboy?.let { snapshotManager?.loadSnapshot(slot, it) }
  }

  override fun snapshotAvailable(slot: Int): Boolean {
    return snapshotManager?.snapshotAvailable(slot) ?: false
  }

  override fun close() {
    stop()
    eventBus.close()
  }
}
