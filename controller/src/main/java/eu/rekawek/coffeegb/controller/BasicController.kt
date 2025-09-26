package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.events.EventQueue
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.Gameboy.TICKS_PER_FRAME
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.genie.AddPatches
import eu.rekawek.coffeegb.core.genie.Patch
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint

class BasicController(
    parentEventBus: EventBus,
    properties: EmulatorProperties,
    private val console: Console?,
) : Controller, SnapshotSupport {

  private val timingTicker = TimingTicker()

  private val eventBus: EventBus = parentEventBus.fork("session")

  private val eventQueue = EventQueue(eventBus)

  private var session: Session? = null

  private var snapshotManager: SnapshotManager? = null

  @Volatile private var doStop = false

  private var isPaused = false

  private val patches = mutableListOf<Patch>()

  private val thread = Thread {
    while (!doStop) {
      runFrame()
    }
  }

  init {
    eventQueue.register<AddPatches> { patches.addAll(it.patches) }
    eventQueue.register<Controller.LoadRomEvent> {
      stop()
      patches.clear()
      val config = Controller.createGameboyConfig(properties, Rom(it.rom))
      session = createSession(config)
      if (it.memento != null) {
        session?.gameboy?.restoreFromMemento(it.memento)
      }
      start()
    }
    eventQueue.register<Controller.RestoreSnapshotEvent> { e -> loadSnapshot(e.slot) }
    eventQueue.register<Controller.SaveSnapshotEvent> { e -> saveSnapshot(e.slot) }
    eventQueue.register<Controller.PauseEmulationEvent> { isPaused = true }
    eventQueue.register<Controller.ResumeEmulationEvent> { isPaused = false }
    eventQueue.register<Controller.ResetEmulationEvent> { reset() }
    eventQueue.register<Controller.StopEmulationEvent> { stop() }
    eventQueue.register<Controller.UpdatedSystemMappingEvent> {
      session?.config?.let { config ->
        val newType = Controller.getGameboyType(properties.system, config.rom)
        if (newType != config.gameboyType) {
          eventBus.post(Controller.LoadRomEvent(config.rom.file))
        }
      }
    }
  }

  override fun startController() {
    thread.start()
  }

  private fun runFrame() {
    eventQueue.dispatch()

    repeat(TICKS_PER_FRAME) {
      if (!isPaused) {
        session?.gameboy?.tick()
      }
      timingTicker.run()
    }
  }

  private fun createSession(config: Gameboy.GameboyConfiguration) =
      Session(config, eventBus.fork("main"), console, Peer2PeerSerialEndpoint())

  private fun start() {
    val session = session ?: return

    isPaused = false
    snapshotManager = SnapshotManager(session.config.rom.file)

    session.eventBus.post(AddPatches(patches))
    session.eventBus.post(Controller.GameboyTypeEvent(session.config.gameboyType))
    session.eventBus.post(Controller.SessionPauseSupportEvent(true))
    session.eventBus.post(Controller.SessionSnapshotSupportEvent(this))
    session.eventBus.post(Controller.EmulationStartedEvent(session.config.rom.title))
  }

  private fun stop() {
    val session = session ?: return
    session.eventBus.post(Controller.EmulationStoppedEvent())
    session.close()
    this.session = null
  }

  private fun reset() {
    val session = session ?: return
    val config = session.config
    stop()
    this.session = createSession(config)
    start()
  }

  private fun saveSnapshot(slot: Int) {
    session?.gameboy?.let { snapshotManager?.saveSnapshot(slot, it) }
  }

  private fun loadSnapshot(slot: Int) {
    session?.gameboy?.let { snapshotManager?.loadSnapshot(slot, it) }
  }

  override fun snapshotAvailable(slot: Int): Boolean {
    return snapshotManager?.snapshotAvailable(slot) ?: false
  }

  override fun close() {
    closeWithState()
  }

  override fun closeWithState(): Controller.ControllerState? {
    doStop = true
    thread.join()

    val state =
        session?.let { Controller.ControllerState(it.gameboy.saveToMemento(), it.config.rom) }

    stop()
    eventBus.close()

    return state
  }
}
