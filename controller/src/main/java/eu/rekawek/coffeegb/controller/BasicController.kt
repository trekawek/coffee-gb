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
import eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint
import eu.rekawek.coffeegb.core.serial.GameboyPrinterSerialEndpoint
import eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import org.slf4j.Logger
import org.slf4j.LoggerFactory

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

  private var isRewinding = false

  private val rewindManager = RewindManager()

  private val patches = mutableListOf<Patch>()

  // when the Barcode Boy is connected the session uses a BarcodeBoySerialEndpoint instead
  // of the netplay peer endpoint; scans are routed to it
  private var barcodeBoyEnabled = false

  private var barcodeBoy: BarcodeBoySerialEndpoint? = null

  // the Game Boy Printer is likewise wired in place of the netplay peer endpoint; its
  // finished bands are forwarded to the UI as PrinterPrintEvents
  private var printerEnabled = false

  private val thread = Thread {
    while (!doStop) {
      runFrame()
    }
  }

  init {
    eventQueue.register<AddPatches> { patches.addAll(it.patches) }
    eventQueue.register<Controller.LoadRomEvent> { loadRom(properties, it) }
    eventQueue.register<Controller.RestoreSnapshotEvent> { e -> loadSnapshot(e.slot) }
    eventQueue.register<Controller.SaveSnapshotEvent> { e -> saveSnapshot(e.slot) }
    eventQueue.register<Controller.PauseEmulationEvent> { isPaused = true }
    eventQueue.register<Controller.ResumeEmulationEvent> { isPaused = false }
    eventQueue.register<Controller.RewindEvent> { isRewinding = it.active }
    eventQueue.register<Controller.ResetEmulationEvent> { reset() }
    eventQueue.register<Controller.StopEmulationEvent> { stop() }
    eventQueue.register<Controller.SetBarcodeBoyEvent> {
      if (barcodeBoyEnabled != it.enabled) {
        barcodeBoyEnabled = it.enabled
        // the Barcode Boy and the printer share the link port, so only one at a time
        if (barcodeBoyEnabled) {
          printerEnabled = false
        }
        reconnectLinkDevice()
      }
    }
    eventQueue.register<Controller.ScanBarcodeEvent> { barcodeBoy?.scan(it.barcode) }
    eventQueue.register<Controller.SetPrinterEvent> {
      if (printerEnabled != it.enabled) {
        printerEnabled = it.enabled
        if (printerEnabled) {
          barcodeBoyEnabled = false
        }
        reconnectLinkDevice()
      }
    }
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

    // rewinding restores one recorded state and then emulates a single frame from it,
    // so the display and audio play backwards at RewindManager.RECORD_INTERVAL speed
    val rewound = isRewinding && session?.gameboy?.let { rewindManager.rewindOneStep(it) } == true

    var emulated = false
    repeat(TICKS_PER_FRAME) {
      if (rewound || (!isPaused && !isRewinding)) {
        session?.gameboy?.tick()
        emulated = true
      }
      timingTicker.run()
    }
    if (emulated && !rewound) {
      session?.gameboy?.let { rewindManager.record(it) }
    }
  }

  private fun createSession(config: Gameboy.GameboyConfiguration): Session {
    val sessionBus = eventBus.fork("main")
    try {
      return Session(config, sessionBus, console, createLinkDevice(sessionBus))
    } catch (e: Exception) {
      try {
        sessionBus.close()
      } catch (cleanupException: Exception) {
        e.addSuppressed(cleanupException)
      }
      throw e
    }
  }

  private fun loadRom(properties: EmulatorProperties, event: Controller.LoadRomEvent) {
    var nextSession: Session? = null
    try {
      // Validate the ROM before closing the running game. A bad file selected in the picker
      // should not interrupt the current session, and must never kill the controller thread.
      val config = Controller.createGameboyConfig(properties, Rom(event.rom))

      stop()
      patches.clear()
      rewindManager.clear()

      nextSession = createSession(config)
      event.memento?.let { nextSession.gameboy.restoreFromMemento(it) }
      session = nextSession
      nextSession = null
      start()
    } catch (e: Exception) {
      try {
        nextSession?.close()
      } catch (cleanupException: Exception) {
        e.addSuppressed(cleanupException)
      }
      LOG.error("Can't load ROM ${event.rom}", e)
      val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
      eventBus.post(Controller.LoadRomFailedEvent(event.rom, message))
    }
  }

  private fun createLinkDevice(sessionBus: EventBus): SerialEndpoint =
      if (printerEnabled) {
        barcodeBoy = null
        GameboyPrinterSerialEndpoint { argb, width, height, top, bottom, exposure ->
          sessionBus.post(Controller.PrinterPrintEvent(argb, width, height, top, bottom, exposure))
        }
      } else if (barcodeBoyEnabled) {
        BarcodeBoySerialEndpoint().also { barcodeBoy = it }
      } else {
        barcodeBoy = null
        Peer2PeerSerialEndpoint()
      }

  /**
   * Plug the currently selected link-port device into the running session without a reset, so
   * connecting the printer or Barcode Boy doesn't restart the game.
   */
  private fun reconnectLinkDevice() {
    val session = session ?: return
    session.setSerialEndpoint(createLinkDevice(session.eventBus))
  }

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
    rewindManager.clear()
    this.session = createSession(config)
    start()
  }

  private fun saveSnapshot(slot: Int) {
    session?.gameboy?.let { snapshotManager?.saveSnapshot(slot, it) }
  }

  private fun loadSnapshot(slot: Int) {
    session?.gameboy?.let { snapshotManager?.loadSnapshot(slot, it) }
    rewindManager.clear()
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

  private companion object {
    val LOG: Logger = LoggerFactory.getLogger(BasicController::class.java)
  }
}
