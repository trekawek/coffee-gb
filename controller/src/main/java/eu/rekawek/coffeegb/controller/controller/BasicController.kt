package eu.rekawek.coffeegb.controller.controller

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.controller.controller.Controller.Companion.getGameboyType
import eu.rekawek.coffeegb.controller.controller.Controller.EmulationStartedEvent
import eu.rekawek.coffeegb.controller.controller.Controller.EmulationStoppedEvent
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties

class BasicController(
    parentEventBus: EventBus,
    properties: EmulatorProperties,
    private val console: Console?,
) : Controller, SnapshotSupport {

  private val eventBus: EventBus = parentEventBus.fork("session")

  private var session: Session? = null

  private var snapshotManager: SnapshotManager? = null

  init {
    eventBus.register<Controller.LoadRomEvent> {
      stop()
      val config = Controller.createGameboyConfig(properties, Rom(it.rom))
      session = createSession(config)
      start()
    }

    eventBus.register<Controller.RestoreSnapshotEvent> { e -> loadSnapshot(e.slot) }
    eventBus.register<Controller.SaveSnapshotEvent> { e -> saveSnapshot(e.slot) }
    eventBus.register<Controller.PauseEmulationEvent> { pause() }
    eventBus.register<Controller.ResumeEmulationEvent> { resume() }
    eventBus.register<Controller.ResetEmulationEvent> { reset() }
    eventBus.register<Controller.StopEmulationEvent> { stop() }
    eventBus.register<Controller.UpdatedSystemMappingEvent> {
      session?.config?.let { config ->
        val newType = getGameboyType(properties.system, config.rom)
        if (newType != config.gameboyType) {
          eventBus.post(Controller.LoadRomEvent(config.rom.file))
        }
      }
    }
  }

  private fun createSession(config: Gameboy.GameboyConfiguration) =
      Session(config, eventBus.fork("main"), console)

  @Synchronized
  fun start() {
    val session = session ?: return

    snapshotManager = SnapshotManager(session.config.rom.file)

    session.eventBus.post(Controller.GameboyTypeEvent(session.config.gameboyType))
    session.eventBus.post(Controller.SessionPauseSupportEvent(true))
    session.eventBus.post(Controller.SessionSnapshotSupportEvent(this))
    session.eventBus.post(EmulationStartedEvent(session.config.rom.title))

    session.gameboy.registerTickListener(TimingTicker())
    Thread(session.gameboy).start()
  }

  @Synchronized
  fun stop() {
    val session = session ?: return
    session.eventBus.post(EmulationStoppedEvent())
    session.close()
    this.session = null
  }

  @Synchronized
  fun reset() {
    val session = session ?: return
    val config = session.config
    stop()
    this.session = createSession(config)
    start()
  }

  @Synchronized
  fun pause() {
    session?.gameboy?.pause()
  }

  @Synchronized
  fun resume() {
    session?.gameboy?.resume()
  }

  @Synchronized
  override fun saveSnapshot(slot: Int) {
    session?.gameboy?.let { snapshotManager?.saveSnapshot(slot, it) }
  }

  @Synchronized
  override fun loadSnapshot(slot: Int) {
    session?.gameboy?.let { snapshotManager?.loadSnapshot(slot, it) }
  }

  override fun snapshotAvailable(slot: Int): Boolean {
    return snapshotManager?.snapshotAvailable(slot) ?: false
  }

  override fun close() {
    stop()
    eventBus.close()
  }
}
