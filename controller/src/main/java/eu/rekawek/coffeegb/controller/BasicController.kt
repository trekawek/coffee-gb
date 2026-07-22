package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.events.EventQueue
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.Gameboy.TICKS_PER_FRAME
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.genie.AddPatches
import eu.rekawek.coffeegb.core.genie.Patch
import eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint
import eu.rekawek.coffeegb.core.serial.GameboyPrinterSerialEndpoint
import eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class BasicController private constructor(
    parentEventBus: EventBus,
    properties: EmulatorProperties,
    private val console: Console?,
    private val sessionPreparer: SessionPreparer,
    private val loadExecutor: ExecutorService,
) : Controller, SnapshotSupport {

  constructor(
      parentEventBus: EventBus,
      properties: EmulatorProperties,
      console: Console?,
  ) : this(parentEventBus, properties, console, RomSessionPreparer(), createLoadExecutor())

  internal constructor(
      parentEventBus: EventBus,
      properties: EmulatorProperties,
      console: Console?,
      sessionPreparer: SessionPreparer,
  ) : this(parentEventBus, properties, console, sessionPreparer, createLoadExecutor())

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

  private var loadJob: LoadJob? = null

  /** The user's pause state before the current chain of coalesced load requests. */
  private var pauseStateBeforeLoading: Boolean? = null

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
    eventQueue.register<Controller.LoadRomEvent> { requestLoad(properties, it) }
    eventQueue.register<Controller.RestoreSnapshotEvent> { e -> loadSnapshot(e.slot) }
    eventQueue.register<Controller.SaveSnapshotEvent> { e -> saveSnapshot(e.slot) }
    eventQueue.register<Controller.PauseEmulationEvent> {
      if (pauseStateBeforeLoading != null) {
        pauseStateBeforeLoading = true
      }
      setPaused(true)
    }
    eventQueue.register<Controller.ResumeEmulationEvent> {
      if (pauseStateBeforeLoading != null) {
        pauseStateBeforeLoading = false
      } else {
        setPaused(false)
      }
    }
    eventQueue.register<Controller.RewindEvent> { isRewinding = it.active }
    eventQueue.register<Controller.ResetEmulationEvent> {
      session?.config?.rom?.file?.let {
        requestLoad(properties, Controller.LoadRomEvent(it), clearPatches = false)
      }
    }
    eventQueue.register<Controller.StopEmulationEvent> {
      cancelLoadJob()
      restorePauseStateAfterLoading()
      stop()
    }
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
        val newBootstrapMode = properties.system.bootstrapMode
        if (newType != config.gameboyType || newBootstrapMode != config.bootstrapMode) {
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
    finishPreparedLoad()

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

  private fun createSession(
      config: Gameboy.GameboyConfiguration,
      prebuiltGameboy: Gameboy? = null,
  ): Session {
    val sessionBus = eventBus.fork("main")
    try {
      return Session(
          config,
          sessionBus,
          console,
          createLinkDevice(sessionBus),
          prebuiltGameboy = prebuiltGameboy,
      )
    } catch (e: Exception) {
      try {
        sessionBus.close()
      } catch (cleanupException: Exception) {
        e.addSuppressed(cleanupException)
      }
      throw e
    }
  }

  private fun requestLoad(
      properties: EmulatorProperties,
      event: Controller.LoadRomEvent,
      clearPatches: Boolean = true,
  ) {
    if (doStop) {
      return
    }

    if (pauseStateBeforeLoading == null) {
      pauseStateBeforeLoading = isPaused
    }
    cancelLoadJob()

    // Keep the last completed frame on screen, but stop the old game immediately. Continuing to
    // animate while the window says that another ROM is loading makes it look as though the load
    // request was ignored and also allows the old game to consume input meant for the new one.
    setPaused(true)

    // A fallback preparation for an exotic/RTC cartridge may use the real save file. When
    // reloading that exact file, flush the old cartridge first so the worker cannot observe stale
    // RAM. Ordinary cartridges use battery-free boot templates and don't need this extra flush.
    if (isCurrentRom(event.rom)) {
      session?.gameboy?.flushCartridge()
    }

    eventBus.post(Controller.RomLoadingEvent(event.rom))
    val task = PreparedLoadTask { sessionPreparer.prepare(properties, event) }
    loadJob = LoadJob(event, clearPatches, task)
    loadExecutor.execute(task)
  }

  private fun finishPreparedLoad() {
    val job = loadJob ?: return
    if (!job.task.isDone) {
      return
    }
    loadJob = null

    try {
      activatePreparedLoad(job, job.task.take())
    } catch (_: CancellationException) {
      restorePauseStateAfterLoading()
    } catch (e: ExecutionException) {
      reportLoadFailure(job.event, e.cause ?: e)
    } catch (e: Exception) {
      reportLoadFailure(job.event, e)
    }
  }

  private fun activatePreparedLoad(job: LoadJob, prepared: PreparedSession) {
    var nextGameboy: Gameboy? = null
    try {
      // The expensive BIOS run is complete. The old game has remained frozen during preparation;
      // now flush its save and atomically replace the session.
      setPaused(true)
      session?.gameboy?.flushCartridge()
      nextGameboy = prepared.materialize()

      stop()
      if (job.clearPatches) {
        patches.clear()
      }
      rewindManager.clear()

      session = createSession(prepared.config, nextGameboy)
      nextGameboy = null
      val pauseNewSession = pauseStateBeforeLoading == true
      pauseStateBeforeLoading = null
      start()
      setPaused(pauseNewSession)
    } catch (e: Exception) {
      nextGameboy?.discardUnstarted()
      reportLoadFailure(job.event, e)
    }
  }

  private fun reportLoadFailure(event: Controller.LoadRomEvent, error: Throwable) {
    LOG.error("Can't load ROM ${event.rom}", error)
    val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    eventBus.post(Controller.LoadRomFailedEvent(event.rom, message))
    restorePauseStateAfterLoading()
  }

  private fun cancelLoadJob() {
    val job = loadJob ?: return
    loadJob = null
    job.task.cancelAndDiscard()
    eventBus.post(Controller.RomLoadingCancelledEvent(job.event.rom))
  }

  private fun isCurrentRom(file: File): Boolean {
    val current = session?.config?.rom?.file ?: return false
    return canonicalFile(current) == canonicalFile(file)
  }

  private fun canonicalFile(file: File): File =
      try {
        file.canonicalFile
      } catch (_: Exception) {
        file.absoluteFile
      }

  private fun setPaused(paused: Boolean) {
    if (isPaused == paused) {
      return
    }
    session?.gameboy?.setCartridgeClockPaused(paused)
    isPaused = paused
  }

  private fun restorePauseStateAfterLoading() {
    val restorePaused = pauseStateBeforeLoading ?: return
    pauseStateBeforeLoading = null
    setPaused(restorePaused)
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

  private fun saveSnapshot(slot: Int) {
    val currentSession = session ?: return
    val manager = snapshotManager ?: return
    manager.saveSnapshot(slot, currentSession.gameboy)
    currentSession.eventBus.post(Controller.SnapshotSavedEvent(slot))
  }

  private fun loadSnapshot(slot: Int) {
    val currentSession = session ?: return
    val manager = snapshotManager ?: return
    if (manager.loadSnapshot(slot, currentSession.gameboy)) {
      rewindManager.clear()
      currentSession.eventBus.post(Controller.SnapshotRestoredEvent(slot))
    }
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

    cancelLoadJob()
    loadExecutor.shutdownNow()
    try {
      if (!loadExecutor.awaitTermination(LOAD_EXECUTOR_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
        LOG.warn("ROM loader did not terminate promptly")
      }
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      LOG.warn("Interrupted while waiting for the ROM loader to terminate")
    }
    restorePauseStateAfterLoading()

    val state =
        session?.let { Controller.ControllerState(it.gameboy.saveToMemento(), it.config.rom) }

    stop()
    eventBus.close()

    return state
  }

  private companion object {
    val LOG: Logger = LoggerFactory.getLogger(BasicController::class.java)

    const val LOAD_EXECUTOR_SHUTDOWN_SECONDS = 5L

    fun createLoadExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
          Thread(runnable, "coffee-gb-rom-loader").apply { isDaemon = true }
        }
  }

  private data class LoadJob(
      val event: Controller.LoadRomEvent,
      val clearPatches: Boolean,
      val task: PreparedLoadTask,
  )

  /** Owns a prepared fallback machine until the controller takes it, even across cancel races. */
  private class PreparedLoadTask(callable: Callable<PreparedSession>) :
      FutureTask<PreparedSession>(callable) {

    private val prepared = AtomicReference<PreparedSession>()

    override fun set(value: PreparedSession) {
      prepared.set(value)
      super.set(value)
      if (isCancelled) {
        prepared.getAndSet(null)?.discard()
      }
    }

    override fun done() {
      if (isCancelled) {
        prepared.getAndSet(null)?.discard()
      }
    }

    fun take(): PreparedSession {
      val value = get()
      prepared.compareAndSet(value, null)
      return value
    }

    fun cancelAndDiscard() {
      if (cancel(true)) {
        return
      }
      try {
        take().discard()
      } catch (_: Exception) {
        // A failed/cancelled preparation owns no live session resources.
      }
    }
  }
}
