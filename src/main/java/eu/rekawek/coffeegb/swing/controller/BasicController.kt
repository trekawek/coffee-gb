package eu.rekawek.coffeegb.swing.controller

import eu.rekawek.coffeegb.Gameboy
import eu.rekawek.coffeegb.Gameboy.GameboyConfiguration
import eu.rekawek.coffeegb.debug.Console
import eu.rekawek.coffeegb.events.EventBus
import eu.rekawek.coffeegb.memory.cart.Rom
import eu.rekawek.coffeegb.serial.SerialEndpoint
import eu.rekawek.coffeegb.swing.events.register
import eu.rekawek.coffeegb.swing.properties.EmulatorProperties
import eu.rekawek.coffeegb.swing.controller.Controller.Companion.getGameboyType
import eu.rekawek.coffeegb.swing.controller.Controller.EmulationStartedEvent
import eu.rekawek.coffeegb.swing.controller.Controller.EmulationStoppedEvent

class BasicController(
    parentEventBus: EventBus,
    properties: EmulatorProperties,
    private val console: Console?,
) : Controller, SnapshotSupport {

  private val eventBus: EventBus = parentEventBus.fork("session")

  private var config: GameboyConfiguration? = null

  private var gameboy: Gameboy? = null

  private var localEventBus: EventBus? = null

  private var snapshotManager: SnapshotManager? = null

  init {
    eventBus.register<Controller.LoadRomEvent> {
      stop()
      val config = Controller.createGameboyConfig(properties, Rom(it.rom))
      start(config)
    }

    eventBus.register<Controller.StartEmulationEvent> { start(config) }
    eventBus.register<Controller.RestoreSnapshotEvent> { e -> loadSnapshot(e.slot) }
    eventBus.register<Controller.SaveSnapshotEvent> { e -> saveSnapshot(e.slot) }
    eventBus.register<Controller.PauseEmulationEvent> { pause() }
    eventBus.register<Controller.ResumeEmulationEvent> { resume() }
    eventBus.register<Controller.ResetEmulationEvent> { reset() }
    eventBus.register<Controller.StopEmulationEvent> { stop() }
    eventBus.register<Controller.UpdatedSystemMappingEvent> {
      if (config != null) {
        val newType = getGameboyType(properties.system, config!!.rom)
        if (newType != config!!.gameboyType) {
          eventBus.post(Controller.LoadRomEvent(config!!.rom.file))
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
    localEventBus?.post(Controller.GameboyTypeEvent(config.gameboyType))

    gameboy = config.build()
    gameboy?.init(localEventBus, SerialEndpoint.NULL_ENDPOINT, console)
    gameboy?.registerTickListener(TimingTicker())
    Thread(gameboy).start()

    localEventBus?.post(Controller.SessionPauseSupportEvent(true))
    localEventBus?.post(Controller.SessionSnapshotSupportEvent(this))
    localEventBus?.post(EmulationStartedEvent(config.rom.title))
  }

  @Synchronized
  fun stop() {
    if (gameboy == null) {
      return
    }
    gameboy?.stop()
    gameboy?.close()
    console?.setGameboy(null)
    localEventBus!!.post(EmulationStoppedEvent())
    localEventBus!!.close()

    localEventBus = null
    gameboy = null
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
