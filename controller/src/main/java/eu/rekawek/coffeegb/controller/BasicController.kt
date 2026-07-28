package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.events.EventQueue
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.state.DetachedStateAdapter
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.hardware.ClockSpec
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.EventBusTeardownTimeoutException
import eu.rekawek.coffeegb.core.genie.AddPatches
import eu.rekawek.coffeegb.core.genie.CheatPatch
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryFlush
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryPersistenceResult
import eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint
import eu.rekawek.coffeegb.core.serial.GameboyPrinterSerialEndpoint
import eu.rekawek.coffeegb.core.serial.GpsReceiverSerialEndpoint
import eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import eu.rekawek.coffeegb.core.sgb.SgbDisplay
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class BasicController private constructor(
    parentEventBus: EventBus,
    properties: EmulatorProperties,
    private val console: Console?,
    private val sessionPreparer: SessionPreparer,
    private val loadExecutor: ExecutorService,
    private val snapshotManagerFactory: SnapshotManagerFactory,
    private val rewindManager: RewindManager,
    private val closeTimeoutMillis: Long,
) : Controller, SnapshotSupport {

  constructor(
      parentEventBus: EventBus,
      properties: EmulatorProperties,
      console: Console?,
  ) : this(
      parentEventBus,
      properties,
      console,
      RomSessionPreparer(),
      createLoadExecutor(),
      SnapshotManagerFactory.DEFAULT,
      RewindManager(),
      CONTROLLER_CLOSE_TIMEOUT_MILLIS,
  )

  internal constructor(
      parentEventBus: EventBus,
      properties: EmulatorProperties,
      console: Console?,
      sessionPreparer: SessionPreparer,
  ) : this(
      parentEventBus,
      properties,
      console,
      sessionPreparer,
      createLoadExecutor(),
      SnapshotManagerFactory.DEFAULT,
      RewindManager(),
      CONTROLLER_CLOSE_TIMEOUT_MILLIS,
  )

  internal constructor(
      parentEventBus: EventBus,
      properties: EmulatorProperties,
      console: Console?,
      sessionPreparer: SessionPreparer,
      closeTimeoutMillis: Long,
  ) : this(
      parentEventBus,
      properties,
      console,
      sessionPreparer,
      createLoadExecutor(),
      SnapshotManagerFactory.DEFAULT,
      RewindManager(),
      closeTimeoutMillis,
  )

  internal constructor(
      parentEventBus: EventBus,
      properties: EmulatorProperties,
      console: Console?,
      sessionPreparer: SessionPreparer,
      snapshotManagerFactory: SnapshotManagerFactory,
  ) : this(
      parentEventBus,
      properties,
      console,
      sessionPreparer,
      createLoadExecutor(),
      snapshotManagerFactory,
      RewindManager(),
      CONTROLLER_CLOSE_TIMEOUT_MILLIS,
  )

  internal constructor(
      parentEventBus: EventBus,
      properties: EmulatorProperties,
      console: Console?,
      sessionPreparer: SessionPreparer,
      snapshotManagerFactory: SnapshotManagerFactory,
      rewindManager: RewindManager,
  ) : this(
      parentEventBus,
      properties,
      console,
      sessionPreparer,
      createLoadExecutor(),
      snapshotManagerFactory,
      rewindManager,
      CONTROLLER_CLOSE_TIMEOUT_MILLIS,
  )

  private val timingTicker = TimingTicker()

  private val eventBus: EventBus = parentEventBus.fork("session")

  private val eventQueue = EventQueue(eventBus)

  private var session: Session? = null

  private var snapshotManager: SnapshotManager? = null

  @Volatile private var doStop = false

  private var isPaused = false

  private var isRewinding = false

  private val patches = mutableListOf<CheatPatch>()

  private var loadJob: LoadJob? = null

  private val persistenceExecutor = createPersistenceExecutor()

  private var replacementJob: ReplacementJob? = null

  private var stopJob: StopJob? = null

  private var nextPersistenceRequestId = 1L

  private var closeCapture: BatteryFlush? = null

  private var closeState: Controller.ControllerState? = null

  private var closeRequestId: Long? = null

  private var closePersistenceAttempt: RetainedClosePersistence? = null

  private var closed = false

  /** The user's pause state before the current chain of coalesced load requests. */
  private var pauseStateBeforeLoading: Boolean? = null

  // when the Barcode Boy is connected the session uses a BarcodeBoySerialEndpoint instead
  // of the netplay peer endpoint; scans are routed to it
  private var barcodeBoyEnabled = false

  private var barcodeBoy: BarcodeBoySerialEndpoint? = null

  // the Game Boy Printer is likewise wired in place of the netplay peer endpoint; its
  // finished bands are forwarded to the UI as PrinterPrintEvents
  private var printerEnabled = false

  // GPS Boy uses a Trimble receiver connected to the same link port through a software UART.
  private var gpsReceiverEnabled = false

  private val thread =
      Thread(
          {
            while (!doStop) {
              runFrame()
            }
          },
          "coffee-gb-controller",
      )

  init {
    require(closeTimeoutMillis > 0) { "Controller close timeout must be positive" }
    eventQueue.register<AddPatches> { patches.addAll(it.patches) }
    eventQueue.register<Controller.LoadRomEvent> { requestLoad(properties, it) }
    eventQueue.register<Controller.RetryRomReplacementEvent> { retryPersistence(it.requestId) }
    eventQueue.register<Controller.CancelRomReplacementEvent> { cancelPersistence(it.requestId) }
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
    eventQueue.register<Controller.RewindEvent> {
      // Disabled rewind is a real no-work mode: the key cannot freeze forward emulation and
      // runFrame never reaches a machine capture.
      isRewinding = rewindManager.enabled && it.active
    }
    eventQueue.register<SgbDisplay.SetSgbBorder> {
      // The display consumes the same event on its session bus. Keep the configuration identity
      // synchronized at this frame boundary so later portable captures describe the live option.
      session?.config?.setDisplaySgbBorder(it.borderEnabled)
    }
    eventQueue.register<Controller.ResetEmulationEvent> {
      session?.config?.rom?.image?.let {
        requestLoad(properties, Controller.LoadRomEvent(it), clearPatches = false)
      }
    }
    eventQueue.register<Controller.StopEmulationEvent> {
      requestStop()
    }
    eventQueue.register<Controller.SetBarcodeBoyEvent> {
      if (barcodeBoyEnabled != it.enabled) {
        barcodeBoyEnabled = it.enabled
        // the Barcode Boy and the printer share the link port, so only one at a time
        if (barcodeBoyEnabled) {
          printerEnabled = false
          gpsReceiverEnabled = false
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
          gpsReceiverEnabled = false
        }
        reconnectLinkDevice()
      }
    }
    eventQueue.register<Controller.SetGpsReceiverEvent> {
      if (gpsReceiverEnabled != it.enabled) {
        gpsReceiverEnabled = it.enabled
        if (gpsReceiverEnabled) {
          barcodeBoyEnabled = false
          printerEnabled = false
        }
        reconnectLinkDevice()
      }
    }
    eventQueue.register<Controller.UpdatedSystemMappingEvent> {
      session?.config?.let { config ->
        val newProfile = Controller.getHardwareProfile(properties.system, config.rom)
        val newBootstrapMode = properties.system.bootstrapMode
        if (newProfile != config.hardwareProfile || newBootstrapMode != config.bootstrapMode) {
          eventBus.post(Controller.LoadRomEvent(config.rom.image))
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
    finishReplacement()
    finishStop()

    // rewinding restores one recorded state and then emulates a single frame from it,
    // so the display and audio play backwards at RewindManager.RECORD_INTERVAL speed
    val rewound =
        loadJob == null &&
            replacementJob == null &&
            stopJob == null &&
            isRewinding &&
            session?.gameboy?.let { rewindManager.rewindOneStep(it) } == true

    var emulated = false
    val clockSpec = session?.gameboy?.clockSpec ?: ClockSpec.LEGACY
    repeat(clockSpec.controllerTicksPerFrame()) {
      if (rewound || (!isPaused && !isRewinding)) {
        session?.gameboy?.tick()
        emulated = true
      }
      timingTicker.run(clockSpec)
    }
    if (emulated && !rewound) {
      session?.gameboy?.let { rewindManager.record(it) }
    }
  }

  private fun createSession(
      config: Gameboy.GameboyConfiguration,
      prebuiltGameboy: Gameboy? = null,
  ): Session {
    val sessionBus = StagedEventBus(eventBus.fork("main"))
    try {
      return Session(
          config,
          sessionBus,
          console,
          createLinkDevice(sessionBus, config.clockSpec),
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

    discardStop(restorePause = true)
    if (pauseStateBeforeLoading == null) {
      pauseStateBeforeLoading = isPaused
    }
    cancelLoadJob()
    discardReplacement(restorePause = false)

    // Keep the last completed frame on screen, but stop the old game immediately. Continuing to
    // animate while the window says that another ROM is loading makes it look as though the load
    // request was ignored and also allows the old game to consume input meant for the new one.
    setPaused(true)

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
      beginReplacement(job, job.task.take())
    } catch (_: CancellationException) {
      restorePauseStateAfterLoading()
    } catch (e: ExecutionException) {
      reportLoadFailure(job.event, e.cause ?: e)
    } catch (e: Exception) {
      reportLoadFailure(job.event, e)
    }
  }

  private fun beginReplacement(job: LoadJob, prepared: PreparedSession) {
    setPaused(true)
    val capture = session?.gameboy?.prepareCartridgeFlush() ?: BatteryFlush.none()
    val attempt = ReplacementTask(capture, prepared)
    replacementJob =
        ReplacementJob(
            requestId = nextPersistenceRequestId++,
            event = job.event,
            clearPatches = job.clearPatches,
            prepared = prepared,
            capture = capture,
            attempt = attempt,
        )
    persistenceExecutor.execute(attempt)
  }

  private fun finishReplacement() {
    val job = replacementJob ?: return
    val attempt = job.attempt ?: return
    if (!attempt.isDone) {
      return
    }

    val outcome =
        try {
          attempt.take()
        } catch (_: CancellationException) {
          return
        } catch (e: Exception) {
          val cause = e.cause ?: e
          replacementJob = null
          job.prepared.discard()
          reportLoadFailure(job.event, cause)
          return
        }
    job.attempt = null

    if (outcome is ReplacementAttemptResult.PersistenceFailure) {
      postPersistenceFailure(
          job.requestId,
          Controller.PersistenceBarrierOperation.ROM_REPLACEMENT,
          outcome.result,
      )
      return
    }

    outcome as ReplacementAttemptResult.Ready
    job.capture.complete(outcome.persistence)
    replacementJob = null
    activatePreparedLoad(job, outcome.gameboy)
  }

  private fun retryPersistence(requestId: Long) {
    replacementJob?.let { job ->
      if (job.requestId == requestId && job.attempt == null) {
        val attempt = ReplacementTask(job.capture, job.prepared)
        job.attempt = attempt
        persistenceExecutor.execute(attempt)
      }
      return
    }
    stopJob?.let { job ->
      if (job.requestId == requestId && job.attempt == null) {
        val attempt = PersistenceTask(job.capture)
        job.attempt = attempt
        persistenceExecutor.execute(attempt)
      }
    }
  }

  private fun cancelPersistence(requestId: Long) {
    replacementJob?.let { job ->
      if (job.requestId == requestId) {
        discardReplacement(restorePause = true)
      }
      return
    }
    stopJob?.let { job ->
      if (job.requestId == requestId) {
        discardStop(restorePause = true)
      }
    }
  }

  private fun discardReplacement(restorePause: Boolean, notifyCancellation: Boolean = true) {
    val job = replacementJob ?: return
    replacementJob = null
    job.attempt?.cancelAndDiscard()
    job.prepared.discard()
    if (notifyCancellation) {
      eventBus.post(Controller.RomLoadingCancelledEvent(job.event.rom))
    }
    if (restorePause) {
      restorePauseStateAfterLoading()
    }
  }

  private fun requestStop() {
    if (stopJob != null) {
      return
    }
    val pausedBeforeStop = pauseStateBeforeLoading ?: isPaused
    cancelLoadJob()
    discardReplacement(restorePause = false)
    pauseStateBeforeLoading = null
    val currentSession = session
    if (currentSession == null) {
      isPaused = false
      return
    }

    setPaused(true)
    val capture = currentSession.gameboy.prepareCartridgeFlush()
    val attempt = PersistenceTask(capture)
    stopJob =
        StopJob(
            requestId = nextPersistenceRequestId++,
            pausedBeforeStop = pausedBeforeStop,
            capture = capture,
            attempt = attempt,
        )
    persistenceExecutor.execute(attempt)
  }

  private fun finishStop() {
    val job = stopJob ?: return
    val attempt = job.attempt ?: return
    if (!attempt.isDone) {
      return
    }
    val result =
        try {
          attempt.get()
        } catch (_: CancellationException) {
          return
        } catch (failure: Exception) {
          unexpectedPersistenceFailure(failure)
        }
    job.attempt = null
    if (result is BatteryPersistenceResult.Failure) {
      postPersistenceFailure(
          job.requestId,
          Controller.PersistenceBarrierOperation.STOP,
          result,
      )
      return
    }

    job.capture.complete(result)
    stopJob = null
    stop(afterCartridgeFlush = true)
    isPaused = false
  }

  private fun discardStop(restorePause: Boolean) {
    val job = stopJob ?: return
    stopJob = null
    job.attempt?.cancel(true)
    if (restorePause) {
      setPaused(job.pausedBeforeStop)
    }
  }

  private fun postPersistenceFailure(
      requestId: Long,
      operation: Controller.PersistenceBarrierOperation,
      result: BatteryPersistenceResult.Failure,
  ) {
    try {
      eventBus.post(
          Controller.RomReplacementPersistenceFailedEvent(
              requestId,
              result.fileName(),
              result.message(),
              operation,
          ))
    } catch (subscriberFailure: RuntimeException) {
      LOG.warn("Persistence failure subscriber threw an exception", subscriberFailure)
    }
  }

  private fun unexpectedPersistenceFailure(failure: Exception): BatteryPersistenceResult.Failure {
    val cause = failure.cause ?: failure
    val ioFailure =
        if (cause is java.io.IOException) {
          cause
        } else {
          java.io.IOException("Unexpected persistence worker failure", cause)
        }
    return BatteryPersistenceResult.Failure(
        BatteryPersistenceResult.FailureKind.WRITE_FAILED,
        session?.config?.rom?.origin?.displayName() ?: "battery save",
        "Unable to persist the current session. Changes remain pending and can be retried.",
        ioFailure,
    )
  }

  private fun activatePreparedLoad(job: ReplacementJob, preparedGameboy: Gameboy) {
    var nextGameboy: Gameboy? = preparedGameboy
    var nextSession: Session? = null
    var nextSnapshotManager: SnapshotManager? = null
    try {
      // Finish constructing and initializing the candidate before releasing the current session.
      // A core-startup failure must leave the old game available for resume/cancel semantics.
      nextSession = createSession(job.prepared.config, nextGameboy)
      nextGameboy = null
      nextSnapshotManager = snapshotManagerFactory.create(job.prepared.config)
    } catch (e: Exception) {
      try {
        nextSession?.discardUnstarted()
      } catch (cleanupFailure: RuntimeException) {
        e.addSuppressed(cleanupFailure)
      }
      try {
        nextGameboy?.discardUnstarted()
      } catch (cleanupFailure: RuntimeException) {
        e.addSuppressed(cleanupFailure)
      }
      console?.setGameboy(session?.gameboy)
      reconnectLinkDevice()
      reportLoadFailure(job.event, e)
      return
    }

    setPaused(true)
    if (job.clearPatches) {
      patches.clear()
    }
    rewindManager.clear()

    val previousSession = session
    val committedSession = checkNotNull(nextSession)
    val pauseNewSession = pauseStateBeforeLoading == true

    // This assignment is the ownership commit. From here on the old session is never resumed:
    // its bus may need deferred cleanup, but it cannot invalidate the fully staged candidate.
    session = committedSession
    snapshotManager = checkNotNull(nextSnapshotManager)
    nextSession = null
    nextSnapshotManager = null
    pauseStateBeforeLoading = null

    previousSession?.let { oldSession ->
      postSessionEventSafely(oldSession, Controller.EmulationStoppedEvent())
      try {
        oldSession.closeAfterCartridgeFlush()
      } catch (cleanupFailure: RuntimeException) {
        LOG.warn(
            "Old session cleanup failed after replacement ownership committed; continuing activation",
            cleanupFailure,
        )
      }
    }

    try {
      committedSession.activate()
      start()
      setPaused(pauseNewSession)
    } catch (activationFailure: RuntimeException) {
      // Rolling back would reattach an already stopped/closing machine. Keep the committed
      // candidate retained and paused so the failure is explicit without corrupting ownership.
      LOG.error("ROM ownership committed but candidate activation failed", activationFailure)
      setPaused(true)
      val message =
          activationFailure.message?.takeIf { it.isNotBlank() }
              ?: activationFailure.javaClass.simpleName
      eventBus.post(Controller.LoadRomFailedEvent(job.event.rom, message))
    }
  }

  private fun reportLoadFailure(event: Controller.LoadRomEvent, error: Throwable) {
    LOG.error("Can't load ROM ${event.rom}", error)
    val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    eventBus.post(Controller.LoadRomFailedEvent(event.rom, message))
    restorePauseStateAfterLoading()
  }

  private fun cancelLoadJob(notifyCancellation: Boolean = true) {
    val job = loadJob ?: return
    loadJob = null
    job.task.cancelAndDiscard()
    if (notifyCancellation) {
      eventBus.post(Controller.RomLoadingCancelledEvent(job.event.rom))
    }
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

  private fun createLinkDevice(sessionBus: EventBus, clockSpec: ClockSpec): SerialEndpoint =
      if (printerEnabled) {
        barcodeBoy = null
        GameboyPrinterSerialEndpoint { argb, width, height, top, bottom, exposure ->
          sessionBus.post(Controller.PrinterPrintEvent(argb, width, height, top, bottom, exposure))
        }
      } else if (barcodeBoyEnabled) {
        BarcodeBoySerialEndpoint().also { barcodeBoy = it }
      } else if (gpsReceiverEnabled) {
        barcodeBoy = null
        GpsReceiverSerialEndpoint(clockSpec)
      } else {
        barcodeBoy = null
        Peer2PeerSerialEndpoint()
      }

  /**
   * Plug the currently selected link-port device into the running session without a reset, so
   * connecting the printer, Barcode Boy or GPS receiver doesn't restart the game.
   */
  private fun reconnectLinkDevice() {
    val session = session ?: return
    session.setSerialEndpoint(createLinkDevice(session.eventBus, session.config.clockSpec))
  }

  private fun start() {
    val session = session ?: return

    isPaused = false
    checkNotNull(snapshotManager) { "Snapshot manager must be staged before session activation" }

    postSessionEventSafely(session, AddPatches(patches))
    postSessionEventSafely(session, Controller.GameboyTypeEvent(session.config.gameboyType))
    postSessionEventSafely(session, Controller.HardwareProfileEvent(session.config.hardwareProfile))
    postSessionEventSafely(session, Controller.SessionPauseSupportEvent(true))
    postSessionEventSafely(session, Controller.SessionSnapshotSupportEvent(this))
    postSessionEventSafely(session, Controller.EmulationStartedEvent(session.config.rom.title))
  }

  private fun stop(
      afterCartridgeFlush: Boolean = false,
      notifyLifecycle: Boolean = true,
      closeDeadlineNanos: Long? = null,
  ) {
    val session = session ?: return
    if (notifyLifecycle) {
      postSessionEventSafely(session, Controller.EmulationStoppedEvent())
    }
    if (afterCartridgeFlush) {
      if (closeDeadlineNanos == null) {
        session.closeAfterCartridgeFlush()
      } else {
        session.closeAfterCartridgeFlush(
            remainingCloseNanos(closeDeadlineNanos, "session teardown"),
            TimeUnit.NANOSECONDS,
        )
      }
    } else {
      session.close()
    }
    console?.setGameboy(null)
    this.session = null
    snapshotManager = null
  }

  private fun postSessionEventSafely(session: Session, event: Event) {
    try {
      session.eventBus.post(event)
    } catch (subscriberFailure: RuntimeException) {
      LOG.warn("Session lifecycle event subscriber failed for {}", event.javaClass.simpleName, subscriberFailure)
    }
  }

  private fun saveSnapshot(slot: Int) {
    val currentSession = session ?: return
    val manager = snapshotManager ?: return
    try {
      manager.saveSnapshot(slot, currentSession.gameboy)
      currentSession.eventBus.post(Controller.SnapshotSavedEvent(slot))
    } catch (e: Exception) {
      LOG.warn("Unable to save snapshot slot {}", slot, e)
      currentSession.eventBus.post(
          Controller.SnapshotSaveFailedEvent(
              slot,
              "Unable to save state slot $slot. Any previous state remains recoverable. " +
                  sanitizedPersistenceDetail(e),
          ))
    }
  }

  private fun loadSnapshot(slot: Int) {
    val currentSession = session ?: return
    if (loadJob != null || replacementJob != null || stopJob != null) {
      currentSession.eventBus.post(
          Controller.SnapshotLoadFailedEvent(
              slot,
              "A ROM replacement is in progress. Retry the state restore after it is cancelled.",
          ))
      return
    }
    val manager = snapshotManager ?: return
    try {
      if (manager.loadSnapshot(slot, currentSession.gameboy)) {
        rewindManager.clear()
        currentSession.eventBus.post(Controller.SnapshotRestoredEvent(slot))
      }
    } catch (e: Exception) {
      LOG.warn("Unable to load snapshot slot {}", slot, e)
      currentSession.eventBus.post(
          Controller.SnapshotLoadFailedEvent(
              slot,
              e.message ?: "The state file is invalid or incompatible.",
          ))
    }
  }

  override fun snapshotAvailable(slot: Int): Boolean {
    return snapshotManager?.snapshotAvailable(slot) ?: false
  }

  override fun close() {
    closeWithState()
  }

  @Synchronized
  override fun closeWithState(): Controller.ControllerState? {
    if (closed) {
      return null
    }
    val closeDeadlineNanos =
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(closeTimeoutMillis)
    doStop = true
    awaitTimingThread(closeDeadlineNanos)

    // Close is a synchronous API: its caller owns retry/cancel presentation. Avoid invoking
    // arbitrary lifecycle subscribers on this thread while the one overall deadline is running.
    cancelLoadJob(notifyCancellation = false)
    discardReplacement(restorePause = false, notifyCancellation = false)
    discardStop(restorePause = false)
    pauseStateBeforeLoading = null
    setPaused(true)

    if (closeState == null) {
      closeState =
          session?.let {
            Controller.ControllerState(DetachedStateAdapter.capture(it.gameboy), it.config.rom)
          }
    }
    if (closeCapture == null) {
      closeCapture = session?.gameboy?.prepareCartridgeFlush() ?: BatteryFlush.none()
    }
    val capture = checkNotNull(closeCapture)
    val persistence = persistCloseCapture(capture, closeDeadlineNanos)
    if (persistence is BatteryPersistenceResult.Failure) {
      val requestId = closeRequestId ?: nextPersistenceRequestId++
      closeRequestId = requestId
      throw Controller.PersistenceBarrierException(
          requestId,
          Controller.PersistenceBarrierOperation.CLOSE,
          persistence.fileName(),
          persistence.message(),
          persistence.cause(),
      )
    }
    capture.complete(persistence)
    val state = closeState

    try {
      stop(
          afterCartridgeFlush = true,
          notifyLifecycle = false,
          closeDeadlineNanos = closeDeadlineNanos,
      )
      isPaused = false
      shutdownExecutors(closeDeadlineNanos)
      eventBus.close(
          remainingCloseNanos(closeDeadlineNanos, "controller event-bus teardown"),
          TimeUnit.NANOSECONDS,
      )
    } catch (failure: EventBusTeardownTimeoutException) {
      throw closeBarrierFailure(
          "Controller event subscribers did not stop before the close deadline. " +
              "The persisted session remains retained and close can be retried.",
          failure,
      )
    }

    closed = true
    closeCapture = null
    closeState = null
    closeRequestId = null
    closePersistenceAttempt = null
    return state
  }

  private fun awaitTimingThread(closeDeadlineNanos: Long) {
    if (Thread.currentThread() === thread || !thread.isAlive) {
      return
    }
    val remainingNanos = remainingCloseNanos(closeDeadlineNanos, "controller timing-thread stop")
    try {
      thread.join(
          TimeUnit.NANOSECONDS.toMillis(remainingNanos),
          (remainingNanos % 1_000_000).toInt(),
      )
    } catch (failure: InterruptedException) {
      Thread.currentThread().interrupt()
      throw closeBarrierFailure(
          "Interrupted while waiting for the controller timing thread to stop.",
          failure,
      )
    }
    if (thread.isAlive) {
      throw closeBarrierFailure(
          "Controller timing thread did not stop before the close deadline. " +
              "The running session remains retained and close can be retried.",
          java.io.IOException("Controller timing-thread stop timed out"),
      )
    }
  }

  private fun persistCloseCapture(
      capture: BatteryFlush,
      closeDeadlineNanos: Long,
  ): BatteryPersistenceResult {
    val fileName = closeFileName()
    val attempt =
        closePersistenceAttempt
            ?: RetainedClosePersistence(capture).also { closePersistenceAttempt = it }
    val result =
        attempt.await(
            fileName,
            remainingCloseNanos(closeDeadlineNanos, "battery persistence"),
            TimeUnit.NANOSECONDS,
            ::unexpectedPersistenceFailure,
        )
    // A completed failure is safe to retry with a new writer. A timeout/interrupted wait retains
    // the exact in-flight task, so no second writer can race a first task that ignored interrupt.
    if (result is BatteryPersistenceResult.Failure && attempt.isDone) {
      closePersistenceAttempt = null
    }
    return result
  }

  private fun shutdownExecutors(closeDeadlineNanos: Long) {
    loadExecutor.shutdownNow()
    persistenceExecutor.shutdownNow()
    try {
      awaitExecutorTermination(loadExecutor, "ROM loader", closeDeadlineNanos)
      if (!persistenceExecutor.awaitTermination(
          remainingCloseNanos(closeDeadlineNanos, "persistence-worker stop"),
          TimeUnit.NANOSECONDS,
      )) {
        throw closeBarrierFailure(
            "Persistence worker did not stop before the close deadline. Close can be retried.",
            java.io.IOException("Persistence-worker stop timed out"),
        )
      }
    } catch (failure: InterruptedException) {
      Thread.currentThread().interrupt()
      throw closeBarrierFailure(
          "Interrupted while waiting for controller workers to stop.",
          failure,
      )
    }
  }

  private fun awaitExecutorTermination(
      executor: ExecutorService,
      name: String,
      closeDeadlineNanos: Long,
  ) {
    if (!executor.awaitTermination(
        remainingCloseNanos(closeDeadlineNanos, "$name stop"),
        TimeUnit.NANOSECONDS,
    )) {
      throw closeBarrierFailure(
          "$name did not stop before the close deadline. Close can be retried.",
          java.io.IOException("$name stop timed out"),
      )
    }
  }

  private fun remainingCloseNanos(closeDeadlineNanos: Long, stage: String): Long {
    val remainingNanos = closeDeadlineNanos - System.nanoTime()
    if (remainingNanos <= 0) {
      throw closeBarrierFailure(
          "Controller close timed out during $stage. The session remains retained and close can be retried.",
          java.io.IOException("Controller close deadline expired during $stage"),
      )
    }
    return remainingNanos
  }

  private fun closeBarrierFailure(
      message: String,
      cause: Throwable,
  ): Controller.PersistenceBarrierException {
    val requestId = closeRequestId ?: nextPersistenceRequestId++
    closeRequestId = requestId
    return Controller.PersistenceBarrierException(
        requestId,
        Controller.PersistenceBarrierOperation.CLOSE,
        closeFileName(),
        message,
        cause,
    )
  }

  private fun closeFileName(): String =
      session?.config?.rom?.origin?.displayName()
          ?: closeState?.rom?.origin?.displayName()
          ?: "battery save"

  private companion object {
    val LOG: Logger = LoggerFactory.getLogger(BasicController::class.java)

    const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 8_000L

    fun createLoadExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
          Thread(runnable, "coffee-gb-rom-loader").apply { isDaemon = true }
        }

    fun createPersistenceExecutor(): ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
          Thread(runnable, "coffee-gb-persistence").apply { isDaemon = true }
        }

  }

  private fun sanitizedPersistenceDetail(error: Throwable): String {
    val detail =
        error.message
            ?.replace(Regex("[\\r\\n\\t]+"), " ")
            ?.trim()
            ?.take(320)
            ?.takeIf { it.isNotEmpty() }
    return detail ?: error.javaClass.simpleName
  }

  private data class LoadJob(
      val event: Controller.LoadRomEvent,
      val clearPatches: Boolean,
      val task: PreparedLoadTask,
  )

  private data class ReplacementJob(
      val requestId: Long,
      val event: Controller.LoadRomEvent,
      val clearPatches: Boolean,
      val prepared: PreparedSession,
      val capture: BatteryFlush,
      var attempt: ReplacementTask?,
  )

  private data class StopJob(
      val requestId: Long,
      val pausedBeforeStop: Boolean,
      val capture: BatteryFlush,
      var attempt: PersistenceTask?,
  )

  private class PersistenceTask(capture: BatteryFlush) :
      FutureTask<BatteryPersistenceResult>(Callable { capture.persist() })

  private sealed interface ReplacementAttemptResult {

    data class PersistenceFailure(
        val result: BatteryPersistenceResult.Failure,
    ) : ReplacementAttemptResult

    data class Ready(
        val persistence: BatteryPersistenceResult.Success,
        val gameboy: Gameboy,
    ) : ReplacementAttemptResult
  }

  private class ReplacementTask(
      capture: BatteryFlush,
      private val prepared: PreparedSession,
  ) : FutureTask<ReplacementAttemptResult>(
          Callable {
            when (val persistence = capture.persist()) {
              is BatteryPersistenceResult.Failure ->
                  ReplacementAttemptResult.PersistenceFailure(persistence)
              is BatteryPersistenceResult.Success ->
                  ReplacementAttemptResult.Ready(persistence, prepared.materialize())
              else -> error("Unknown battery persistence result")
            }
          }) {

    private val materialized = AtomicReference<Gameboy>()

    override fun set(value: ReplacementAttemptResult) {
      if (value is ReplacementAttemptResult.Ready) {
        materialized.set(value.gameboy)
      }
      super.set(value)
      if (isCancelled) {
        materialized.getAndSet(null)?.discardUnstarted()
      }
    }

    override fun done() {
      if (isCancelled) {
        materialized.getAndSet(null)?.discardUnstarted()
      }
    }

    fun take(): ReplacementAttemptResult {
      val value = get()
      if (value is ReplacementAttemptResult.Ready) {
        materialized.compareAndSet(value.gameboy, null)
      }
      return value
    }

    fun cancelAndDiscard() {
      cancel(true)
      prepared.discard()
      materialized.getAndSet(null)?.discardUnstarted()
    }
  }

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

/**
 * Owns exactly one close writer across caller-side timeouts. A timed-out task is deliberately not
 * cancelled or replaced: some filesystems ignore interruption, and overlapping atomic writers
 * could otherwise publish stale bytes after a retry.
 */
internal class RetainedClosePersistence(capture: BatteryFlush) {
  private val task =
      FutureTask<BatteryPersistenceResult>(Callable { capture.persist() })
          .also { future ->
            Thread(future, "coffee-gb-close-persistence").apply {
              isDaemon = true
              start()
            }
          }

  val isDone: Boolean
    get() = task.isDone

  fun await(
      fileName: String,
      timeout: Long,
      unit: TimeUnit,
      unexpectedFailure: (Exception) -> BatteryPersistenceResult.Failure,
  ): BatteryPersistenceResult {
    require(timeout > 0) { "Persistence timeout must be positive" }
    return try {
      task.get(timeout, unit)
    } catch (timeoutFailure: TimeoutException) {
      timedOut(fileName, timeoutFailure)
    } catch (interrupted: InterruptedException) {
      Thread.currentThread().interrupt()
      timedOut(fileName, interrupted)
    } catch (failure: Exception) {
      unexpectedFailure(failure)
    }
  }

  private fun timedOut(fileName: String, cause: Exception) =
      BatteryPersistenceResult.Failure(
          BatteryPersistenceResult.FailureKind.TIMED_OUT,
          fileName,
          "Battery persistence timed out. Changes remain pending and can be retried.",
          java.io.IOException("Battery persistence timed out", cause),
      )
}

internal fun interface SnapshotManagerFactory {
  fun create(configuration: Gameboy.GameboyConfiguration): SnapshotManager

  companion object {
    val DEFAULT = SnapshotManagerFactory(::SnapshotManager)
  }
}
