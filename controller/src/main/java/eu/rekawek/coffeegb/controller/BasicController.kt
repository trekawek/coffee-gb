package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.events.EventQueue
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.state.BatteryStorageResolver
import eu.rekawek.coffeegb.controller.state.DetachedStateAdapter
import eu.rekawek.coffeegb.controller.state.ExclusiveWriteRecovery
import eu.rekawek.coffeegb.controller.state.ResolvedBatteryStorage
import eu.rekawek.coffeegb.controller.state.StateCatalogReadyEvent
import eu.rekawek.coffeegb.controller.state.StateCatalogRequestEvent
import eu.rekawek.coffeegb.controller.state.StateCompression
import eu.rekawek.coffeegb.controller.state.StateDeleteRequestEvent
import eu.rekawek.coffeegb.controller.state.StateDiagnosticMetadata
import eu.rekawek.coffeegb.controller.state.StateDiagnosticRedactor
import eu.rekawek.coffeegb.controller.state.StateEntryKey
import eu.rekawek.coffeegb.controller.state.StateExportRequestEvent
import eu.rekawek.coffeegb.controller.state.StateExternalActions
import eu.rekawek.coffeegb.controller.state.StateFile
import eu.rekawek.coffeegb.controller.state.StateIdentity
import eu.rekawek.coffeegb.controller.state.StateLoadRefRequestEvent
import eu.rekawek.coffeegb.controller.state.StateLoadRequestEvent
import eu.rekawek.coffeegb.controller.state.MachineStateRoot
import eu.rekawek.coffeegb.controller.state.SessionStateRoot
import eu.rekawek.coffeegb.controller.state.StateOpenFolderRequestEvent
import eu.rekawek.coffeegb.controller.state.StateOperation
import eu.rekawek.coffeegb.controller.state.StateOperationCompletedEvent
import eu.rekawek.coffeegb.controller.state.StateOperationFailedEvent
import eu.rekawek.coffeegb.controller.state.StateOperationWorker
import eu.rekawek.coffeegb.controller.state.StatePrepareCloseCompletedEvent
import eu.rekawek.coffeegb.controller.state.StatePrepareCloseRequestEvent
import eu.rekawek.coffeegb.controller.state.StateReadResult
import eu.rekawek.coffeegb.controller.state.StateRecovery
import eu.rekawek.coffeegb.controller.state.StateRef
import eu.rekawek.coffeegb.controller.state.StateRomHashes
import eu.rekawek.coffeegb.controller.state.StateResumeAvailableEvent
import eu.rekawek.coffeegb.controller.state.StateResumeDecisionEvent
import eu.rekawek.coffeegb.controller.state.StateSaveRequestEvent
import eu.rekawek.coffeegb.controller.state.StateSaveMetadata
import eu.rekawek.coffeegb.controller.state.StateScreenshotRequestEvent
import eu.rekawek.coffeegb.controller.state.StateSkipCloseAutosaveRequestEvent
import eu.rekawek.coffeegb.controller.state.StateStoragePaths
import eu.rekawek.coffeegb.controller.state.StateStorageResolver
import eu.rekawek.coffeegb.controller.state.StateUserError
import eu.rekawek.coffeegb.controller.state.StateUxSessionEvent
import eu.rekawek.coffeegb.controller.state.StateWorkerCompletedEvent
import eu.rekawek.coffeegb.controller.state.StateWorkerContext
import eu.rekawek.coffeegb.controller.state.StateWorkerPurpose
import eu.rekawek.coffeegb.controller.state.StateWorkerResult
import eu.rekawek.coffeegb.controller.state.StateWorkspace
import eu.rekawek.coffeegb.controller.state.StateCodec
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
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint
import eu.rekawek.coffeegb.core.sgb.SgbDisplay
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class BasicController private constructor(
    parentEventBus: EventBus,
    private val properties: EmulatorProperties,
    private val console: Console?,
    private val sessionPreparer: SessionPreparer,
    private val loadExecutor: ExecutorService,
    private val snapshotManagerFactory: SnapshotManagerFactory,
    private var rewindManager: RewindManager,
    private val stateWorkspaceFactory: StateWorkspaceFactory,
    private val stateWorkerFactory: StateOperationWorkerFactory,
    private val closeTimeoutMillis: Long,
    private val liveBatteryStorageResolver: LiveBatteryStorageResolver =
        LiveBatteryStorageResolver.DEFAULT,
    private val mobileAdapterConfigurationProvider: Controller.MobileAdapterConfigurationProvider =
        SYNTHETIC_OFFLINE_MOBILE_CONFIGURATION,
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
      configuredRewindManager(properties),
      StateWorkspaceFactory.DEFAULT,
      StateOperationWorkerFactory.DEFAULT,
      CONTROLLER_CLOSE_TIMEOUT_MILLIS,
  )

  constructor(
      parentEventBus: EventBus,
      properties: EmulatorProperties,
      console: Console?,
      externalActions: StateExternalActions,
  ) : this(
      parentEventBus,
      properties,
      console,
      RomSessionPreparer(),
      createLoadExecutor(),
      SnapshotManagerFactory.DEFAULT,
      configuredRewindManager(properties),
      StateWorkspaceFactory.DEFAULT,
      StateOperationWorkerFactory {
        StateOperationWorker(it, externalActions = externalActions)
      },
      CONTROLLER_CLOSE_TIMEOUT_MILLIS,
  )

  constructor(
      parentEventBus: EventBus,
      properties: EmulatorProperties,
      console: Console?,
      externalActions: StateExternalActions,
      mobileAdapterConfigurationProvider: Controller.MobileAdapterConfigurationProvider,
  ) : this(
      parentEventBus,
      properties,
      console,
      RomSessionPreparer(),
      createLoadExecutor(),
      SnapshotManagerFactory.DEFAULT,
      configuredRewindManager(properties),
      StateWorkspaceFactory.DEFAULT,
      StateOperationWorkerFactory {
        StateOperationWorker(it, externalActions = externalActions)
      },
      CONTROLLER_CLOSE_TIMEOUT_MILLIS,
      mobileAdapterConfigurationProvider = mobileAdapterConfigurationProvider,
  )

  internal constructor(
      parentEventBus: EventBus,
      properties: EmulatorProperties,
      console: Console?,
      sessionPreparer: SessionPreparer,
      mobileAdapterConfigurationProvider: Controller.MobileAdapterConfigurationProvider =
          SYNTHETIC_OFFLINE_MOBILE_CONFIGURATION,
  ) : this(
      parentEventBus,
      properties,
      console,
      sessionPreparer,
      createLoadExecutor(),
      SnapshotManagerFactory.DEFAULT,
      configuredRewindManager(properties),
      StateWorkspaceFactory.DEFAULT,
      StateOperationWorkerFactory.DEFAULT,
      CONTROLLER_CLOSE_TIMEOUT_MILLIS,
      mobileAdapterConfigurationProvider = mobileAdapterConfigurationProvider,
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
      configuredRewindManager(properties),
      StateWorkspaceFactory.DEFAULT,
      StateOperationWorkerFactory.DEFAULT,
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
      configuredRewindManager(properties),
      StateWorkspaceFactory.DEFAULT,
      StateOperationWorkerFactory.DEFAULT,
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
      StateWorkspaceFactory.DEFAULT,
      StateOperationWorkerFactory.DEFAULT,
      CONTROLLER_CLOSE_TIMEOUT_MILLIS,
  )

  internal constructor(
      parentEventBus: EventBus,
      properties: EmulatorProperties,
      console: Console?,
      sessionPreparer: SessionPreparer,
      snapshotManagerFactory: SnapshotManagerFactory,
      rewindManager: RewindManager,
      stateWorkspaceFactory: StateWorkspaceFactory,
      stateWorkerFactory: StateOperationWorkerFactory,
      closeTimeoutMillis: Long = CONTROLLER_CLOSE_TIMEOUT_MILLIS,
      liveBatteryStorageResolver: LiveBatteryStorageResolver =
          LiveBatteryStorageResolver.DEFAULT,
      mobileAdapterConfigurationProvider: Controller.MobileAdapterConfigurationProvider =
          SYNTHETIC_OFFLINE_MOBILE_CONFIGURATION,
  ) : this(
      parentEventBus,
      properties,
      console,
      sessionPreparer,
      createLoadExecutor(),
      snapshotManagerFactory,
      rewindManager,
      stateWorkspaceFactory,
      stateWorkerFactory,
      closeTimeoutMillis,
      liveBatteryStorageResolver,
      mobileAdapterConfigurationProvider,
  )

  private val timingTicker = TimingTicker()

  private val eventBus: EventBus = parentEventBus.fork("session")

  private val eventQueue = EventQueue(eventBus)

  private val stateWorker = stateWorkerFactory.create(eventBus)

  private var session: Session? = null

  private var snapshotManager: SnapshotManager? = null

  private var stateContext: StateWorkerContext? = null

  private var currentRomHashes: StateRomHashes? = null

  private var stateSessionId = 0L

  private val internalStateRequestId = AtomicLong(1L shl 60)

  private val latestStateRequests = mutableMapOf<StateOperation, Long>()

  private val latestSaveRequests = mutableMapOf<StateRef, Long>()

  private var pendingResume: PendingResume? = null

  private var pendingRomSwitch: PendingRomSwitch? = null

  private var pendingCloseRequestId: Long? = null

  private var sessionStartedNanos: Long? = null

  /** User pause state retained while an automatic resume scan/dialog owns the pause. */
  private var pauseStateBeforeResume: Boolean? = null

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

  private var closeAutosaveCapture: StateFile? = null

  private var closeAutosavePlayDurationNanos: Long? = null

  private var closeAutosavePlayDurationCaptured = false

  private var closeAutosaveAttempt: RetainedCloseAutosave? = null

  private var closeAutosaveCompletedSessionId: Long? = null

  private var closeAutosaveSkippedSessionId: Long? = null

  private var closeAutosaveWaiverSessionId: Long? = null

  private var closeAutosaveWaivableRequestId: Long? = null

  private var closed = false

  /** The user's pause state before the current chain of coalesced load requests. */
  private var pauseStateBeforeLoading: Boolean? = null

  /** One explicit owner replaces the former independently mutable peripheral booleans. */
  private var serialPeripheralSelection =
      Controller.SerialPeripheralSelection.PEER_TO_PEER

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
    eventQueue.register<Controller.LoadRomEvent> { requestLoadWithAutosave(properties, it) }
    eventQueue.register<Controller.CancelRomOpenEvent> { cancelOpenRequest(it.openRequestId) }
    eventQueue.register<Controller.RetryRomReplacementEvent> { retryPersistence(it.requestId) }
    eventQueue.register<Controller.CancelRomReplacementEvent> { cancelPersistence(it.requestId) }
    eventQueue.register<Controller.RestoreSnapshotEvent> { e -> loadSnapshot(e.slot) }
    eventQueue.register<Controller.SaveSnapshotEvent> { e -> saveSnapshot(e.slot) }
    eventQueue.register<StateCatalogRequestEvent> { requestStateCatalog(it) }
    eventQueue.register<StateSaveRequestEvent> { requestStateSave(it) }
    eventQueue.register<StateLoadRequestEvent> { requestStateLoad(it) }
    eventQueue.register<StateLoadRefRequestEvent> { requestStateLoadRef(it) }
    eventQueue.register<StateDeleteRequestEvent> { requestStateDelete(it) }
    eventQueue.register<StateExportRequestEvent> { requestStateExport(it) }
    eventQueue.register<StateScreenshotRequestEvent> { requestScreenshot(it) }
    eventQueue.register<StateOpenFolderRequestEvent> { requestOpenStateFolder(it) }
    eventQueue.register<StateResumeDecisionEvent> { applyResumeDecision(it) }
    eventQueue.register<StatePrepareCloseRequestEvent> { prepareClose(it) }
    eventQueue.register<StateSkipCloseAutosaveRequestEvent> {
      val currentSessionId = stateSessionId
      val waiverIsCurrent =
          it.sessionId == currentSessionId && closeAutosaveWaiverSessionId == currentSessionId
      if (waiverIsCurrent) {
        closeAutosaveSkippedSessionId = currentSessionId
        closeAutosaveWaiverSessionId = null
      }
      eventBus.post(
          StatePrepareCloseCompletedEvent(
              it.requestId,
              currentSessionId,
              autosaved = false,
              error =
                  if (waiverIsCurrent) {
                    null
                  } else {
                    StateUserError(
                        "The close choice is no longer current.",
                        "The game session or autosave attempt changed before the waiver was applied.",
                        "Retry closing to autosave the current game, or choose close without autosave again.",
                    )
                  },
          ))
    }
    eventQueue.register<StateWorkerCompletedEvent> { finishStateWorkerRequest(it) }
    eventQueue.register<Controller.PauseEmulationEvent> {
      if (pauseStateBeforeLoading != null) {
        pauseStateBeforeLoading = true
      } else if (pauseStateBeforeResume != null) {
        pauseStateBeforeResume = true
      }
      setPaused(true)
    }
    eventQueue.register<Controller.ResumeEmulationEvent> {
      if (pauseStateBeforeLoading != null) {
        pauseStateBeforeLoading = false
      } else if (pauseStateBeforeResume != null) {
        pauseStateBeforeResume = false
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
      session?.config?.let { config ->
        config.setDisplaySgbBorder(it.borderEnabled)
        currentRomHashes?.let { hashes ->
          stateContext =
              stateContext?.copy(identity = StateIdentity.from(config, hashes))
        }
      }
    }
    eventQueue.register<Controller.ResetEmulationEvent> {
      session?.config?.rom?.image?.let {
        requestLoad(properties, Controller.LoadRomEvent(it), clearPatches = false)
      }
    }
    eventQueue.register<Controller.StopEmulationEvent> {
      requestStop()
    }
    eventQueue.register<Controller.SetSerialPeripheralEvent> {
      selectSerialPeripheral(it.selection)
    }
    eventQueue.register<Controller.SetBarcodeBoyEvent> {
      applyLegacySerialSelection(
          Controller.SerialPeripheralSelection.BARCODE_BOY,
          it.enabled,
      )
    }
    eventQueue.register<Controller.ScanBarcodeEvent> {
      (session?.serialEndpoint as? BarcodeBoySerialEndpoint)?.scan(it.barcode)
    }
    eventQueue.register<Controller.SetPrinterEvent> {
      applyLegacySerialSelection(
          Controller.SerialPeripheralSelection.PRINTER,
          it.enabled,
      )
    }
    eventQueue.register<Controller.SetGpsReceiverEvent> {
      applyLegacySerialSelection(
          Controller.SerialPeripheralSelection.GPS_RECEIVER,
          it.enabled,
      )
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
    eventQueue.register<Controller.UpdatedSavesSettingsEvent> {
      applySavesSettings(it.saves)
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
            session?.let { rewindManager.rewindOneStep(it) } == true

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
      session?.let { rewindManager.record(it) }
    }
  }

  private fun createSession(
      config: Gameboy.GameboyConfiguration,
      prebuiltGameboy: Gameboy? = null,
  ): Session {
    val sessionBus = StagedEventBus(eventBus.fork("main"))
    try {
      val serialEndpoint =
          createLinkDevice(serialPeripheralSelection, sessionBus, config.clockSpec)
      return Session(
          config,
          sessionBus,
          console,
          serialEndpoint,
          prebuiltGameboy = prebuiltGameboy,
      )
    } catch (failure: Controller.SerialPeripheralPreparationException) {
      postSerialPeripheralStatus(
          serialPeripheralSelection,
          Controller.SerialPeripheralStatus.UNAVAILABLE,
          failure.error,
      )
      try {
        sessionBus.close()
      } catch (cleanupException: Exception) {
        failure.addSuppressed(cleanupException)
      }
      throw failure
    } catch (e: Exception) {
      try {
        sessionBus.close()
      } catch (cleanupException: Exception) {
        e.addSuppressed(cleanupException)
      }
      throw e
    }
  }

  private fun requestStateCatalog(event: StateCatalogRequestEvent) {
    val context =
        requireStateContext(
            event.requestId,
            event.expectedSessionId,
            StateOperation.CATALOG,
        ) ?: return
    latestStateRequests[StateOperation.CATALOG] = event.requestId
    stateWorker.catalog(context, event.requestId)
  }

  private fun requestStateSave(event: StateSaveRequestEvent) {
    val context =
        requireStateContext(
            event.requestId,
            event.expectedSessionId,
            StateOperation.SAVE,
        ) ?: return
    val currentSession = session ?: return
    try {
      val captured = capturePortableState(currentSession)
      latestSaveRequests[event.ref] = event.requestId
      stateWorker.save(
          context,
          event.requestId,
          StateWorkerPurpose.MANUAL,
          event.ref,
          captured,
          event.label,
          currentPlayDurationNanos(),
          event.thumbnail,
      )
    } catch (failure: Throwable) {
      postStateFailure(
          event.requestId,
          StateOperation.SAVE,
          stateError(
              "State could not be captured.",
              failure,
              "The running game was not changed. Retry at the next frame.",
          ),
      )
    }
  }

  private fun requestStateLoad(event: StateLoadRequestEvent) {
    val context =
        requireStateContext(
            event.requestId,
            event.expectedSessionId,
            StateOperation.LOAD,
        ) ?: return
    latestStateRequests[StateOperation.LOAD] = event.requestId
    stateWorker.load(
        context,
        event.requestId,
        StateWorkerPurpose.MANUAL,
        event.key,
    )
  }

  private fun requestStateLoadRef(event: StateLoadRefRequestEvent) {
    val context =
        requireStateContext(
            event.requestId,
            event.expectedSessionId,
            StateOperation.LOAD,
        ) ?: return
    latestStateRequests[StateOperation.LOAD] = event.requestId
    val compatibilityManager = snapshotManager
    stateWorker.loadFirst(context, event.requestId, event.ref) {
      compatibilityManager?.readSnapshotReadOnly(event.ref.index)
    }
  }

  private fun requestStateDelete(event: StateDeleteRequestEvent) {
    val context =
        requireStateContext(
            event.requestId,
            event.expectedSessionId,
            StateOperation.DELETE,
        ) ?: return
    latestStateRequests[StateOperation.DELETE] = event.requestId
    stateWorker.delete(context, event.requestId, event.key)
  }

  private fun requestStateExport(event: StateExportRequestEvent) {
    val context =
        requireStateContext(
            event.requestId,
            event.expectedSessionId,
            StateOperation.EXPORT,
        ) ?: return
    latestStateRequests[StateOperation.EXPORT] = event.requestId
    stateWorker.export(context, event.requestId, event.key, event.destination)
  }

  private fun requestScreenshot(event: StateScreenshotRequestEvent) {
    val context =
        requireStateContext(
            event.requestId,
            event.expectedSessionId,
            StateOperation.SCREENSHOT,
        ) ?: return
    latestStateRequests[StateOperation.SCREENSHOT] = event.requestId
    stateWorker.screenshot(context, event.requestId, event.image)
  }

  private fun requestOpenStateFolder(event: StateOpenFolderRequestEvent) {
    val context =
        requireStateContext(
            event.requestId,
            event.expectedSessionId,
            StateOperation.OPEN_FOLDER,
        ) ?: return
    latestStateRequests[StateOperation.OPEN_FOLDER] = event.requestId
    stateWorker.openFolder(context, event.requestId)
  }

  private fun prepareClose(event: StatePrepareCloseRequestEvent) {
    val context = stateContext
    val currentSession = session
    if (currentSession == null ||
        properties.saves.autosavePolicy !=
            eu.rekawek.coffeegb.controller.properties.ApplicationSettings.AutosavePolicy
                .ON_CLOSE_AND_ROM_SWITCH) {
      eventBus.post(
          StatePrepareCloseCompletedEvent(
              event.requestId,
              context?.sessionId ?: stateSessionId,
              autosaved = false,
              error = null,
          ))
      return
    }
    if (context == null) {
      closeAutosaveWaiverSessionId = stateSessionId
      eventBus.post(
          StatePrepareCloseCompletedEvent(
              event.requestId,
              stateSessionId,
              autosaved = false,
              error =
                  StateUserError(
                      "Close autosave is unavailable.",
                      "The configured save workspace could not be initialized for this game.",
                      "Choose a writable Saves directory, retry, or explicitly close without autosave.",
                  ),
          ))
      return
    }
    if (pendingCloseRequestId != null) {
      eventBus.post(
          StatePrepareCloseCompletedEvent(
              event.requestId,
              context.sessionId,
              autosaved = false,
              error =
                  StateUserError(
                      "Close autosave is already in progress.",
                      "Another close preparation owns the current immutable state capture.",
                      "Wait for that save to finish before closing again.",
                  ),
          ))
      return
    }
    try {
      closeAutosaveWaiverSessionId = null
      pendingCloseRequestId = event.requestId
      stateWorker.save(
          context,
          event.requestId,
          StateWorkerPurpose.AUTOSAVE_CLOSE,
          StateRef.Autosave,
          capturePortableState(currentSession),
          label = "Autosave",
          playDurationNanos = currentPlayDurationNanos(),
          thumbnail = null,
      )
    } catch (failure: Throwable) {
      pendingCloseRequestId = null
      eventBus.post(
          StatePrepareCloseCompletedEvent(
              event.requestId,
              context.sessionId,
              autosaved = false,
              error =
                  stateError(
                      "Close autosave could not be captured.",
                      failure,
                      "The running game is still active. Retry or explicitly close without saving.",
                  ),
          ))
    }
  }

  private fun requestLoadWithAutosave(
      properties: EmulatorProperties,
      event: Controller.LoadRomEvent,
  ) {
    cancelPendingRomSwitch(restorePause = true)
    val currentSession = session
    val context = stateContext
    val autosaveRequired =
        currentSession != null &&
            properties.saves.autosavePolicy ==
                eu.rekawek.coffeegb.controller.properties.ApplicationSettings.AutosavePolicy
                    .ON_CLOSE_AND_ROM_SWITCH
    if (!autosaveRequired) {
      requestLoad(properties, event)
      return
    }

    // A newer request owns the active-session autosave. Prevent an older prepared replacement
    // from committing a different session while this state write is in flight.
    cancelLoadJob()
    discardReplacement(restorePause = true)
    discardStop(restorePause = true)
    acquireLoadingPause()

    val requestId = nextPersistenceRequestId++
    if (context == null) {
      val error =
          StateUserError(
              "ROM switch was cancelled because autosave is unavailable.",
              "The configured save workspace could not be initialized for the active game.",
              "Choose a writable Saves directory, retry opening the ROM, or disable autosave.",
          )
      restorePauseStateAfterLoading()
      eventBus.post(
          Controller.LoadRomFailedEvent(
              event.rom,
              error.summary,
              event.openRequestId,
              Controller.RomLoadFailureKind.PERSISTENCE,
              error.detail,
          ))
      return
    }
    try {
      val pending =
          PendingRomSwitch(
              requestId,
              event,
              checkNotNull(context),
              capturePortableState(checkNotNull(currentSession)),
              currentPlayDurationNanos(),
          )
      pendingRomSwitch = pending
      submitRomSwitchAutosave(pending)
    } catch (failure: Throwable) {
      pendingRomSwitch = null
      restorePauseStateAfterLoading()
      val error =
          stateError(
              "ROM switch was cancelled because autosave could not be captured.",
              failure,
              "The current game remains active. Retry opening the ROM or disable autosave in Preferences.",
          )
      eventBus.post(
          Controller.LoadRomFailedEvent(
              event.rom,
              error.summary,
              event.openRequestId,
              Controller.RomLoadFailureKind.PERSISTENCE,
              error.detail,
          ))
    }
  }

  private fun submitRomSwitchAutosave(pending: PendingRomSwitch) {
    pending.awaitingDecision = false
    stateWorker.save(
        pending.context,
        pending.requestId,
        StateWorkerPurpose.AUTOSAVE_ROM_SWITCH,
        StateRef.Autosave,
        pending.state,
        label = "Autosave",
        playDurationNanos = pending.playDurationNanos,
        thumbnail = null,
    )
  }

  private fun finishStateWorkerRequest(event: StateWorkerCompletedEvent) {
    val context = stateContext
    if (context == null ||
        event.context.sessionId != context.sessionId) {
      return
    }

    val failure = event.result as? StateWorkerResult.Failure
    if (failure != null) {
      finishStateWorkerFailure(event, failure.error)
      return
    }

    when (val result = event.result) {
      is StateWorkerResult.Catalog -> {
        if (!isLatest(event.operation, event.requestId)) return
        eventBus.post(
            StateCatalogReadyEvent(
                event.requestId,
                context.sessionId,
                result.catalog,
            ))
      }
      is StateWorkerResult.Saved -> finishStateSave(event, result)
      is StateWorkerResult.Loaded -> {
        if (event.purpose == StateWorkerPurpose.MANUAL) {
          if (!isLatest(StateOperation.LOAD, event.requestId)) return
          applyLoadedState(
              event.requestId,
              StateOperation.LOAD,
              result.key,
              result.result,
          )
        }
      }
      is StateWorkerResult.Missing -> {
        if (!isLatest(StateOperation.LOAD, event.requestId)) return
        postMissingQuickSlot(event.requestId, result.ref)
      }
      is StateWorkerResult.CompatibilityLoaded -> {
        if (!isLatest(StateOperation.LOAD, event.requestId)) return
        finishCompatibilityQuickLoad(event.requestId, result.ref, result.snapshot)
      }
      is StateWorkerResult.Deleted -> {
        if (!isLatest(StateOperation.DELETE, event.requestId)) return
        eventBus.post(
            StateOperationCompletedEvent(
                event.requestId,
                context.sessionId,
                StateOperation.DELETE,
                result.key.ref,
                message =
                    if (result.result.deletedArtifacts == 0) {
                      "State was already absent."
                    } else {
                      "State deleted."
                    },
                recoveryMessages = recoveryMessages(result.result.recovery),
            ))
      }
      is StateWorkerResult.Exported -> {
        if (!isLatest(StateOperation.EXPORT, event.requestId)) return
        eventBus.post(
            StateOperationCompletedEvent(
                event.requestId,
                context.sessionId,
                StateOperation.EXPORT,
                result.key.ref,
                path = result.result.destination,
                message = "State exported to ${result.result.destination.fileName}.",
                recoveryMessages = recoveryMessages(result.result.recovery, "export"),
            ))
      }
      is StateWorkerResult.Screenshot -> {
        if (!isLatest(StateOperation.SCREENSHOT, event.requestId)) return
        eventBus.post(
            StateOperationCompletedEvent(
                event.requestId,
                context.sessionId,
                StateOperation.SCREENSHOT,
                path = result.result.path,
                message = "Screenshot saved as ${result.result.path.fileName}.",
                recoveryMessages = recoveryMessages(result.result.recovery, "screenshot"),
            ))
      }
      is StateWorkerResult.Folder -> {
        if (!isLatest(StateOperation.OPEN_FOLDER, event.requestId)) return
        eventBus.post(
            StateOperationCompletedEvent(
                event.requestId,
                context.sessionId,
              StateOperation.OPEN_FOLDER,
              path = result.path,
              message =
                  if (result.opened) "Save folder opened."
                  else "Desktop folder integration is unavailable.",
              folderOpened = result.opened,
            ))
      }
      is StateWorkerResult.Resume -> finishResumeScan(event, result)
      is StateWorkerResult.Failure -> error("Handled above")
    }
  }

  private fun finishStateSave(
      event: StateWorkerCompletedEvent,
      result: StateWorkerResult.Saved,
  ) {
    val context = checkNotNull(stateContext)
    when (event.purpose) {
      StateWorkerPurpose.MANUAL -> {
        if (latestSaveRequests[result.ref] != event.requestId) return
        val warnings =
            buildList {
              result.result.state.metadataFailure?.let {
                add("State metadata was not updated: $it")
              }
              result.result.thumbnailFailure?.let {
                add("State thumbnail was not updated: $it")
              }
              addAll(recoveryMessages(result.result.state.recovery))
              recoveryMessage("thumbnail", result.result.thumbnailRecovery)?.let(::add)
            }
        eventBus.post(
            StateOperationCompletedEvent(
                event.requestId,
                context.sessionId,
                StateOperation.SAVE,
                result.ref,
                message =
                    if (warnings.isEmpty()) "State saved."
                    else "State saved; optional UI metadata needs attention.",
                recoveryMessages = warnings,
            ))
      }
      StateWorkerPurpose.AUTOSAVE_ROM_SWITCH -> {
        val pending = pendingRomSwitch
        if (pending == null || pending.requestId != event.requestId) return
        pendingRomSwitch = null
        requestLoad(properties, pending.event)
      }
      StateWorkerPurpose.AUTOSAVE_CLOSE -> {
        if (pendingCloseRequestId != event.requestId) return
        pendingCloseRequestId = null
        closeAutosaveCompletedSessionId = context.sessionId
        closeAutosaveWaiverSessionId = null
        eventBus.post(
            StatePrepareCloseCompletedEvent(
                event.requestId,
                context.sessionId,
                autosaved = true,
                error = null,
            ))
      }
      StateWorkerPurpose.RESUME_SCAN -> Unit
    }
  }

  private fun finishStateWorkerFailure(
      event: StateWorkerCompletedEvent,
      error: StateUserError,
  ) {
    when (event.purpose) {
      StateWorkerPurpose.AUTOSAVE_ROM_SWITCH -> {
        val pending = pendingRomSwitch
        if (pending == null || pending.requestId != event.requestId) return
        pending.awaitingDecision = true
        postPersistenceFailure(
            pending.requestId,
            Controller.PersistenceBarrierOperation.ROM_REPLACEMENT,
            "autosave state",
            "${error.summary} ${error.suggestedAction}",
            pending.event.openRequestId,
        )
      }
      StateWorkerPurpose.AUTOSAVE_CLOSE -> {
        if (pendingCloseRequestId != event.requestId) return
        pendingCloseRequestId = null
        closeAutosaveWaiverSessionId = event.context.sessionId
        eventBus.post(
            StatePrepareCloseCompletedEvent(
                event.requestId,
                event.context.sessionId,
                autosaved = false,
                error = error,
            ))
      }
      StateWorkerPurpose.RESUME_SCAN -> {
        if (isLatest(StateOperation.RESUME, event.requestId)) {
          postStateFailure(event.requestId, StateOperation.RESUME, error)
          pendingResume = null
          releaseResumePause()
        }
      }
      StateWorkerPurpose.MANUAL -> {
        val latest =
            if (event.operation == StateOperation.SAVE) {
              // A failed manual save result carries no ref. Request IDs are globally unique in
              // the desktop and any newer save should suppress this stale notification.
              event.requestId in latestSaveRequests.values
            } else {
              isLatest(event.operation, event.requestId)
            }
        if (latest) postStateFailure(event.requestId, event.operation, error)
      }
    }
  }

  private fun finishResumeScan(
      event: StateWorkerCompletedEvent,
      result: StateWorkerResult.Resume,
  ) {
    if (!isLatest(StateOperation.RESUME, event.requestId)) return
    val located =
        result.located
            ?: run {
              releaseResumePause()
              return
            }
    when (properties.saves.resumePolicy) {
      eu.rekawek.coffeegb.controller.properties.ApplicationSettings.ResumePolicy.NEVER ->
          releaseResumePause()
      eu.rekawek.coffeegb.controller.properties.ApplicationSettings.ResumePolicy.ALWAYS -> {
        applyLoadedState(event.requestId, StateOperation.RESUME, located.first, located.second)
        releaseResumePause()
      }
      eu.rekawek.coffeegb.controller.properties.ApplicationSettings.ResumePolicy.ASK -> {
        pendingResume =
            PendingResume(
                event.requestId,
                event.context.sessionId,
                located.first,
                located.second,
            )
        eventBus.post(
            StateResumeAvailableEvent(
                event.requestId,
                event.context.sessionId,
                located.first,
                located.second.metadata?.savedAt,
                located.second.metadata?.playDurationNanos,
            ))
      }
    }
  }

  private fun applyResumeDecision(event: StateResumeDecisionEvent) {
    val pending = pendingResume ?: return
    if (pending.requestId != event.requestId ||
        pending.sessionId != event.expectedSessionId ||
        stateContext?.sessionId != event.expectedSessionId) {
      return
    }
    pendingResume = null
    if (event.accept) {
      applyLoadedState(
          event.requestId,
          StateOperation.RESUME,
          pending.key,
          pending.read,
      )
    }
    releaseResumePause()
  }

  private fun applyLoadedState(
      requestId: Long,
      operation: StateOperation,
      key: StateEntryKey,
      read: StateReadResult,
  ) {
    val currentSession = session ?: return
    val context = stateContext ?: return
    try {
      when (read.state.root) {
        is SessionStateRoot ->
            StateCodec.applyDecoded(read.state, currentSession, context.identity)
        is MachineStateRoot -> {
          // Released managed states predate session roots. They have no serial continuation, so
          // a successful load deterministically cancels any live endpoint parser/backend
          // ownership. Apply first so an invalid old file retains the complete active session.
          StateCodec.applyDecoded(
              read.state,
              currentSession.config,
              currentSession.gameboy,
              context.identity,
          )
          currentSession.serialEndpoint.disconnect()
        }
        else -> StateCodec.applyDecoded(read.state, currentSession, context.identity)
      }
      // A portable state contains the cartridge clock pause bit. Pause ownership belongs to the
      // live desktop workflow, so loading must not let an old capture override the effective
      // pause selected for this session.
      currentSession.gameboy.setCartridgeClockPaused(isPaused)
      rewindManager.clear()
      eventBus.post(
          StateOperationCompletedEvent(
              requestId,
              context.sessionId,
              operation,
              key.ref,
              message =
                  if (operation == StateOperation.RESUME) {
                    "Autosave resumed."
                  } else {
                    "State loaded."
                  },
              recoveryMessages = recoveryMessages(read.recovery),
          ))
    } catch (failure: Throwable) {
      postStateFailure(
          requestId,
          operation,
          stateError(
              "State was rejected before it could replace the running game.",
              failure,
              "The active session was preserved. Export or delete the state from the browser.",
          ),
      )
    }
  }

  /**
   * A managed quick load reaches this compatibility path only after every configured managed source
   * reported the slot absent. A present but corrupt/incompatible managed state fails in the worker
   * and never falls through here.
   */
  private fun finishCompatibilityQuickLoad(
      requestId: Long,
      ref: StateRef,
      snapshot: CompatibilitySnapshot,
  ) {
    val slot = ref as? StateRef.Slot
    if (slot == null) {
      postStateFailure(
          requestId,
          StateOperation.LOAD,
          StateUserError(
              "State could not be loaded.",
              "No managed state exists for ${ref.storageKey()}.",
              "Choose an available state in Manage States and retry.",
          ),
      )
      return
    }
    val currentSession = session ?: return
    if (loadJob != null || replacementJob != null || stopJob != null) {
      postStateFailure(
          requestId,
          StateOperation.LOAD,
          StateUserError(
              "State could not be loaded.",
              "A ROM replacement or shutdown is in progress.",
              "Retry the state restore after the current operation finishes.",
          ),
      )
      return
    }
    val manager = snapshotManager
    if (manager == null) {
      postMissingQuickSlot(requestId, slot)
      return
    }
    try {
      manager.applySnapshotReadOnly(snapshot, currentSession)
      // Match managed-load ownership: a saved cartridge clock pause bit must not override the
      // effective pause chosen for the live desktop session.
      currentSession.gameboy.setCartridgeClockPaused(isPaused)
      rewindManager.clear()
      eventBus.post(
          StateOperationCompletedEvent(
              requestId,
              checkNotNull(stateContext).sessionId,
              StateOperation.LOAD,
              slot,
              message = "Legacy state loaded from Slot ${slot.index}.",
          ))
    } catch (failure: Throwable) {
      LOG.warn("Unable to load legacy snapshot slot {} as managed fallback", slot.index, failure)
      postStateFailure(
          requestId,
          StateOperation.LOAD,
          stateError(
              "State could not be loaded.",
              failure,
              "The running game was preserved. Save a new managed state or inspect the legacy sidecar with an older Coffee GB build.",
          ),
      )
    }
  }

  private fun postMissingQuickSlot(
      requestId: Long,
      ref: StateRef,
  ) {
    val slot = ref as? StateRef.Slot
    if (slot == null) {
      postStateFailure(
          requestId,
          StateOperation.LOAD,
          StateUserError(
              "State could not be loaded.",
              "No managed state exists for ${ref.storageKey()}.",
              "Choose an available state in Manage States and retry.",
          ),
      )
      return
    }
    postStateFailure(
        requestId,
        StateOperation.LOAD,
        StateUserError(
            "No state is saved in Slot ${slot.index}.",
            "The slot is empty in managed storage and no preserved .sn${slot.index} sidecar is available.",
            "Save a state into this slot or choose another slot.",
        ),
    )
  }

  private fun requireStateContext(
      requestId: Long,
      expectedSessionId: Long,
      operation: StateOperation,
  ): StateWorkerContext? {
    val context = stateContext
    if (context == null ||
        session == null ||
        context.sessionId != expectedSessionId) {
      postStateFailure(
          requestId,
          operation,
          StateUserError(
              "The state request is no longer current.",
              if (context == null || session == null) {
                "State operations require an active standalone emulation session."
              } else {
                "The request targeted session $expectedSessionId, but session " +
                    "${context.sessionId} now owns the emulator."
              },
              "Return to the current game and retry the operation.",
          ),
          sessionId = expectedSessionId,
      )
      return null
    }
    return context
  }

  private fun capturePortableState(currentSession: Session): StateFile =
      StateCodec.capture(
          currentSession,
          checkNotNull(stateContext) {
                "Portable state capture requires an initialized state context"
              }
              .identity,
          StateDiagnosticMetadata(
              BasicController::class.java.`package`?.implementationVersion ?: "development",
              "desktop",
          ),
      )

  private fun currentPlayDurationNanos(): Long? =
      sessionStartedNanos?.let { started -> (System.nanoTime() - started).coerceAtLeast(0L) }

  private fun acquireResumePause() {
    if (pauseStateBeforeResume == null) {
      pauseStateBeforeResume = isPaused
    }
    setPaused(true)
  }

  private fun releaseResumePause() {
    val restorePaused = pauseStateBeforeResume ?: return
    pauseStateBeforeResume = null
    if (pauseStateBeforeLoading == null) {
      setPaused(restorePaused)
    } else {
      // A ROM load may have acquired its own pause while the resume scan was still queued. The
      // loading owner keeps the old session frozen until replacement/cancellation completes.
      setPaused(true)
    }
    // setPaused intentionally avoids redundant core calls. State loading may have restored a
    // different cartridge-clock bit, so explicitly establish the effective live value.
    session?.gameboy?.setCartridgeClockPaused(isPaused)
  }

  private fun acquireLoadingPause() {
    if (pauseStateBeforeLoading == null) {
      // A resume scan owns a forced pause, so isPaused itself is not the user's desired state.
      pauseStateBeforeLoading = pauseStateBeforeResume ?: isPaused
    }
    pendingResume = null
    pauseStateBeforeResume = null
    latestStateRequests.remove(StateOperation.RESUME)
    setPaused(true)
  }

  private fun nextInternalStateRequestId(): Long = internalStateRequestId.incrementAndGet()

  private fun isLatest(operation: StateOperation, requestId: Long): Boolean =
      latestStateRequests[operation] == requestId

  private fun postStateFailure(
      requestId: Long,
      operation: StateOperation,
      error: StateUserError,
      sessionId: Long = stateContext?.sessionId ?: stateSessionId,
  ) {
    eventBus.post(
        StateOperationFailedEvent(
            requestId,
            sessionId,
            operation,
            error,
        ))
  }

  private fun stateError(
      summary: String,
      failure: Throwable,
      action: String,
  ): StateUserError {
    val detail =
        generateSequence(failure) { it.cause }
            .take(8)
            .joinToString("\n") {
              val message =
                  it.message?.let { value ->
                    StateDiagnosticRedactor.redact(
                        value,
                        stateContext?.workspace?.sensitivePaths().orEmpty(),
                        900,
                    )
                  }
              "${it.javaClass.simpleName}${message?.let { value -> ": $value" } ?: ""}"
            }
            .take(StateUserError.MAX_DETAIL_CHARS)
    return StateUserError(summary, detail.ifBlank { failure.javaClass.name }, action)
  }

  private fun recoveryMessages(recovery: StateRecovery): List<String> =
      listOfNotNull(
          recoveryMessage("state", recovery.state),
          recoveryMessage("metadata", recovery.metadata),
      )

  private fun recoveryMessages(
      recovery: ExclusiveWriteRecovery,
      artifact: String,
  ): List<String> =
      if (recovery.staleTemporaryFilesRemoved > 0) {
        listOf(
            "${recovery.staleTemporaryFilesRemoved} stale $artifact temporary file(s) removed")
      } else {
        emptyList()
      }

  private fun recoveryMessage(
      artifact: String,
      report: eu.rekawek.coffeegb.core.persistence.AtomicFileWriter.RecoveryReport,
  ): String? {
    val actions =
        buildList {
          if (report.backupRestored()) add("$artifact backup restored")
          if (report.staleBackupRemoved()) add("stale $artifact backup removed")
          if (report.staleTemporaryFilesRemoved() > 0) {
            add("${report.staleTemporaryFilesRemoved()} stale $artifact temporary file(s) removed")
          }
        }
    return actions.takeIf(List<String>::isNotEmpty)?.joinToString(", ")
  }

  private fun requestLoad(
      properties: EmulatorProperties,
      event: Controller.LoadRomEvent,
      clearPatches: Boolean = true,
  ) {
    if (doStop) {
      return
    }

    cancelPendingRomSwitch()
    discardStop(restorePause = true)
    acquireLoadingPause()
    cancelLoadJob()
    discardReplacement(restorePause = false)

    // Keep the last completed frame on screen, but stop the old game immediately. Continuing to
    // animate while the window says that another ROM is loading makes it look as though the load
    // request was ignored and also allows the old game to consume input meant for the new one.
    eventBus.post(Controller.RomLoadingEvent(event.rom, event.openRequestId))
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
    pendingRomSwitch?.takeIf { it.requestId == requestId }?.let { pending ->
      if (pending.awaitingDecision) {
        submitRomSwitchAutosave(pending)
      }
      return
    }
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
    pendingRomSwitch?.takeIf { it.requestId == requestId }?.let {
      cancelPendingRomSwitch()
      return
    }
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
      eventBus.post(
          Controller.RomLoadingCancelledEvent(job.event.rom, job.event.openRequestId))
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
    cancelPendingRomSwitch()
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
    postPersistenceFailure(
        requestId,
        operation,
        result.fileName(),
        result.message(),
        replacementJob?.event?.openRequestId,
    )
  }

  private fun postPersistenceFailure(
      requestId: Long,
      operation: Controller.PersistenceBarrierOperation,
      fileName: String,
      message: String,
      openRequestId: Long?,
  ) {
    try {
      eventBus.post(
          Controller.RomReplacementPersistenceFailedEvent(
              requestId,
              fileName,
              message,
              operation,
              openRequestId,
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
      nextSnapshotManager =
          if (job.prepared.config.rom.origin.persistencePath(".sn0").isPresent) {
            snapshotManagerFactory.create(job.prepared.config)
          } else {
            null
          }
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
    currentRomHashes = job.prepared.romHashes
    snapshotManager = nextSnapshotManager
    nextSession = null
    nextSnapshotManager = null
    pauseStateBeforeLoading = null

    previousSession?.let { oldSession ->
      postSessionEventSafely(oldSession, Controller.EmulationStoppedEvent())
      postSerialPeripheralStatus(
          serialPeripheralSelection,
          Controller.SerialPeripheralStatus.DETACHED,
      )
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
      start(job.event.openRequestId)
      if (pauseStateBeforeResume != null) {
        // start() acquired the resume-scan pause for the new session. Carry the loading workflow's
        // desired user state underneath it without releasing either pause owner.
        pauseStateBeforeResume = pauseNewSession
        setPaused(true)
      } else {
        setPaused(pauseNewSession)
      }
    } catch (activationFailure: RuntimeException) {
      // Rolling back would reattach an already stopped/closing machine. Keep the committed
      // candidate retained and paused so the failure is explicit without corrupting ownership.
      LOG.error("ROM ownership committed but candidate activation failed", activationFailure)
      setPaused(true)
      val message =
          activationFailure.message?.takeIf { it.isNotBlank() }
              ?: activationFailure.javaClass.simpleName
      eventBus.post(
          Controller.LoadRomFailedEvent(
              job.event.rom,
              message,
              job.event.openRequestId,
              Controller.RomLoadFailureKind.CORE_STARTUP,
              sanitizedPersistenceDetail(activationFailure),
          ))
    }
  }

  private fun reportLoadFailure(event: Controller.LoadRomEvent, error: Throwable) {
    LOG.error("Can't load ROM ${event.rom}", error)
    val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    eventBus.post(
        Controller.LoadRomFailedEvent(
            event.rom,
            message,
            event.openRequestId,
            Controller.RomLoadFailureKind.CORE_STARTUP,
            sanitizedPersistenceDetail(error),
        ))
    restorePauseStateAfterLoading()
  }

  private fun cancelLoadJob(notifyCancellation: Boolean = true) {
    val job = loadJob ?: return
    loadJob = null
    job.task.cancelAndDiscard()
    if (notifyCancellation) {
      eventBus.post(
          Controller.RomLoadingCancelledEvent(job.event.rom, job.event.openRequestId))
    }
  }

  private fun cancelPendingRomSwitch(
      notifyCancellation: Boolean = true,
      restorePause: Boolean = true,
  ) {
    val pending = pendingRomSwitch ?: return
    pendingRomSwitch = null
    if (notifyCancellation) {
      eventBus.post(
          Controller.RomLoadingCancelledEvent(
              pending.event.rom,
              pending.event.openRequestId,
          ))
    }
    if (restorePause) {
      restorePauseStateAfterLoading()
    }
  }

  private fun cancelOpenRequest(openRequestId: Long) {
    pendingRomSwitch?.takeIf { it.event.openRequestId == openRequestId }?.let {
      cancelPendingRomSwitch()
      return
    }
    loadJob?.takeIf { it.event.openRequestId == openRequestId }?.let {
      cancelLoadJob()
      restorePauseStateAfterLoading()
      return
    }
    replacementJob?.takeIf { it.event.openRequestId == openRequestId }?.let {
      discardReplacement(restorePause = true)
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

  private fun createLinkDevice(
      selection: Controller.SerialPeripheralSelection,
      sessionBus: EventBus,
      clockSpec: ClockSpec,
  ): SerialEndpoint =
      when (selection) {
        Controller.SerialPeripheralSelection.NONE -> SerialEndpoint.NULL_ENDPOINT
        Controller.SerialPeripheralSelection.PRINTER ->
            GameboyPrinterSerialEndpoint { argb, width, height, top, bottom, exposure ->
              sessionBus.post(
                  Controller.PrinterPrintEvent(argb, width, height, top, bottom, exposure))
            }
        Controller.SerialPeripheralSelection.BARCODE_BOY -> BarcodeBoySerialEndpoint()
        Controller.SerialPeripheralSelection.GPS_RECEIVER ->
            GpsReceiverSerialEndpoint(clockSpec)
        Controller.SerialPeripheralSelection.MOBILE_ADAPTER_GB ->
            createMobileAdapterEndpoint(clockSpec)
        Controller.SerialPeripheralSelection.PEER_TO_PEER -> Peer2PeerSerialEndpoint()
      }

  private fun createMobileAdapterEndpoint(clockSpec: ClockSpec): SerialEndpoint {
    val configuration =
        try {
          mobileAdapterConfigurationProvider.load()
        } catch (failure: Controller.SerialPeripheralPreparationException) {
          throw failure
        } catch (_: IllegalArgumentException) {
          throw Controller.SerialPeripheralPreparationException(
              Controller.SerialPeripheralError.CONFIGURATION_INVALID)
        } catch (failure: Exception) {
          // Do not copy exception text into logs or UI: a durable provider may include a path,
          // endpoint, account name, or configuration fragment in its original failure.
          LOG.warn(
              "Mobile Adapter configuration provider failed with {}",
              failure.javaClass.name,
          )
          throw Controller.SerialPeripheralPreparationException(
              Controller.SerialPeripheralError.STORAGE_FAILED)
        }
    return try {
      MobileAdapterSerialEndpoint(
          clockSpec,
          configuration.deviceId,
          configuration.copyBytes(),
      )
    } catch (_: IllegalArgumentException) {
      throw Controller.SerialPeripheralPreparationException(
          Controller.SerialPeripheralError.CONFIGURATION_INVALID)
    }
  }

  private fun applyLegacySerialSelection(
      legacySelection: Controller.SerialPeripheralSelection,
      enabled: Boolean,
  ) {
    if (enabled) {
      selectSerialPeripheral(legacySelection)
    } else if (serialPeripheralSelection == legacySelection) {
      selectSerialPeripheral(Controller.SerialPeripheralSelection.PEER_TO_PEER)
    }
  }

  /** Prepares first, then commits one exclusive endpoint at the controller frame safe point. */
  private fun selectSerialPeripheral(selection: Controller.SerialPeripheralSelection) {
    if (serialPeripheralSelection == selection) {
      return
    }
    val currentSession = session
    if (currentSession == null) {
      serialPeripheralSelection = selection
      postSerialPeripheralEventSafely(
          Controller.SerialPeripheralSelectionChangedEvent(selection))
      postSerialPeripheralStatus(selection, Controller.SerialPeripheralStatus.DETACHED)
      return
    }

    val endpoint =
        try {
          createLinkDevice(selection, currentSession.eventBus, currentSession.config.clockSpec)
        } catch (failure: Controller.SerialPeripheralPreparationException) {
          postSerialPeripheralStatus(
              selection,
              Controller.SerialPeripheralStatus.UNAVAILABLE,
              failure.error,
          )
          return
        }
    try {
      currentSession.setSerialEndpoint(endpoint)
    } catch (failure: RuntimeException) {
      LOG.warn(
          "Unable to attach serial peripheral {} after preparation",
          selection,
      )
      postSerialPeripheralStatus(
          selection,
          Controller.SerialPeripheralStatus.UNAVAILABLE,
          Controller.SerialPeripheralError.ENDPOINT_UNAVAILABLE,
      )
      return
    }
    val previousSelection = serialPeripheralSelection
    // The session handoff is the ownership commit. Update the controller's internal selection
    // before invoking any presentation subscriber, because EventBus dispatch is synchronous and
    // an unrelated UI/plugin callback must not split endpoint ownership from controller state.
    serialPeripheralSelection = selection
    // Session rewind entries are pinned to one endpoint identity. Only a committed handoff
    // invalidates them; preparation and handoff failures leave the old endpoint/history intact.
    rewindManager.clear()
    postSerialPeripheralStatus(
        previousSelection,
        Controller.SerialPeripheralStatus.DETACHED,
    )
    postSerialPeripheralEventSafely(
        Controller.SerialPeripheralSelectionChangedEvent(selection))
    postSerialPeripheralStatus(selection, Controller.SerialPeripheralStatus.ATTACHED)
  }

  private fun postSerialPeripheralStatus(
      selection: Controller.SerialPeripheralSelection,
      status: Controller.SerialPeripheralStatus,
      error: Controller.SerialPeripheralError? = null,
  ) {
    postSerialPeripheralEventSafely(
        Controller.SerialPeripheralStatusEvent(selection, status, error))
  }

  private fun postSerialPeripheralEventSafely(event: Event) {
    try {
      eventBus.post(event)
    } catch (_: RuntimeException) {
      // These notifications describe an already-decided controller transition. Keep subscriber
      // failures outside the ownership transaction and omit details that may contain paths or
      // private configuration material.
      LOG.warn(
          "Serial peripheral presentation subscriber failed for {}",
          event.javaClass.simpleName,
      )
    }
  }

  private fun start(openRequestId: Long? = null) {
    val session = session ?: return

    isPaused = false
    pauseStateBeforeResume = null
    sessionStartedNanos = System.nanoTime()
    stateSessionId = nextStateSessionId()
    closeAutosaveCapture = null
    closeAutosavePlayDurationNanos = null
    closeAutosavePlayDurationCaptured = false
    closeAutosaveAttempt = null
    closeAutosaveCompletedSessionId = null
    closeAutosaveSkippedSessionId = null
    closeAutosaveWaiverSessionId = null
    closeAutosaveWaivableRequestId = null
    var stateUnavailableReason: StateUserError? = null
    stateContext =
        try {
          val identity =
              StateIdentity.from(
                  session.config,
                  checkNotNull(currentRomHashes) {
                    "Activated session has no precomputed ROM identity"
                  },
              )
          val paths =
              StateStorageResolver.resolve(
                  properties.applicationSettings.saves,
                  session.config,
                  identity,
              )
          StateWorkerContext(
              stateSessionId,
              stateWorkspaceFactory.create(paths),
              identity,
              session.config.hardwareProfile.id(),
          )
        } catch (failure: Throwable) {
          LOG.warn("Unable to initialize desktop state storage", failure)
          stateUnavailableReason =
              stateError(
                  "State management is unavailable for this game.",
                  failure,
                  "Choose a writable Saves directory in Preferences, then retry.",
              )
          null
        }

    postSessionEventSafely(session, AddPatches(patches))
    postSessionEventSafely(session, Controller.GameboyTypeEvent(session.config.gameboyType))
    postSessionEventSafely(session, Controller.HardwareProfileEvent(session.config.hardwareProfile))
    postSessionEventSafely(session, Controller.SessionPauseSupportEvent(true))
    postSessionEventSafely(
        session,
        Controller.SessionSnapshotSupportEvent(if (snapshotManager == null) null else this),
    )
    postSessionEventSafely(
        session,
        Controller.EmulationStartedEvent(
            session.config.rom.title,
            session.config.rom.origin,
            openRequestId,
        ))
    val context = stateContext
    postSessionEventSafely(
        session,
        StateUxSessionEvent(
            stateSessionId,
            context != null,
            context?.workspace?.activeGameDirectory(),
            stateUnavailableReason,
        ))
    if (context != null &&
        properties.saves.resumePolicy !=
            eu.rekawek.coffeegb.controller.properties.ApplicationSettings.ResumePolicy.NEVER) {
      acquireResumePause()
      val requestId = nextInternalStateRequestId()
      latestStateRequests[StateOperation.RESUME] = requestId
      stateWorker.scanResume(context, requestId)
    }
    postSerialPeripheralEventSafely(
        Controller.SerialPeripheralSelectionChangedEvent(serialPeripheralSelection))
    postSerialPeripheralStatus(
        serialPeripheralSelection,
        Controller.SerialPeripheralStatus.ATTACHED,
    )
  }

  private fun applySavesSettings(
      saves: eu.rekawek.coffeegb.controller.properties.ApplicationSettings.Saves
  ) {
    cancelPendingRomSwitch()
    pendingResume = null
    releaseResumePause()
    isRewinding = false
    rewindManager = configuredRewindManager(saves)
    val currentSession = session ?: return
    val romHashes =
        checkNotNull(currentRomHashes) {
          "Active session has no precomputed ROM identity"
        }
    val batteryStorage =
        try {
          liveBatteryStorageResolver.resolve(
              saves,
              currentSession.config,
              romHashes,
          )
        } catch (failure: RuntimeException) {
          LOG.warn(
              "Unable to reconfigure battery storage; retaining the active destination",
              failure,
          )
          null
        }
    if (batteryStorage != null) {
      // Resolution is pure. Commit both views only after both primary and slot paths passed, so a
      // malformed update cannot replace the active cartridge's previous destination.
      currentSession.gameboy.setBatteryStorage(
          batteryStorage.primary,
          batteryStorage.slot,
      )
      currentSession.config.setBatteryStorage(
          batteryStorage.primary,
          batteryStorage.slot,
      )
    }
    stateSessionId = nextStateSessionId()
    closeAutosaveCapture = null
    closeAutosavePlayDurationNanos = null
    closeAutosavePlayDurationCaptured = false
    closeAutosaveAttempt = null
    closeAutosaveCompletedSessionId = null
    closeAutosaveSkippedSessionId = null
    closeAutosaveWaiverSessionId = null
    closeAutosaveWaivableRequestId = null
    var stateUnavailableReason: StateUserError? = null
    stateContext =
        try {
          val identity =
              StateIdentity.from(
                  currentSession.config,
                  romHashes,
              )
          val paths = StateStorageResolver.resolve(saves, currentSession.config, identity)
          StateWorkerContext(
              stateSessionId,
              stateWorkspaceFactory.create(paths),
              identity,
              currentSession.config.hardwareProfile.id(),
          )
        } catch (failure: Throwable) {
          LOG.warn("Unable to reconfigure desktop state storage", failure)
          stateUnavailableReason =
              stateError(
                  "State management could not use the selected directory.",
                  failure,
                  "Choose a writable Saves directory in Preferences, then retry.",
              )
          null
        }
    pendingResume = null
    pendingCloseRequestId = null
    latestStateRequests.clear()
    latestSaveRequests.clear()
    postSessionEventSafely(
        currentSession,
        StateUxSessionEvent(
            stateSessionId,
            stateContext != null,
            stateContext?.workspace?.activeGameDirectory(),
            stateUnavailableReason,
        ))
  }

  private fun stop(
      afterCartridgeFlush: Boolean = false,
      notifyLifecycle: Boolean = true,
      closeDeadlineNanos: Long? = null,
  ) {
    val session = session ?: return
    postSerialPeripheralStatus(
        serialPeripheralSelection,
        Controller.SerialPeripheralStatus.DETACHED,
    )
    if (notifyLifecycle) {
      postSessionEventSafely(session, StateUxSessionEvent(stateSessionId, false, null))
      postSessionEventSafely(session, Controller.EmulationStoppedEvent())
    }
    stateContext = null
    pendingResume = null
    pauseStateBeforeResume = null
    cancelPendingRomSwitch()
    pendingCloseRequestId = null
    latestStateRequests.clear()
    latestSaveRequests.clear()
    sessionStartedNanos = null
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
    currentRomHashes = null
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
      manager.saveSnapshot(slot, currentSession)
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
      if (manager.loadSnapshot(slot, currentSession)) {
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
    cancelPendingRomSwitch(notifyCancellation = false, restorePause = false)
    discardReplacement(restorePause = false, notifyCancellation = false)
    discardStop(restorePause = false)
    pauseStateBeforeLoading = null
    pauseStateBeforeResume = null
    setPaused(true)

    if (closeState == null) {
      closeState =
          session?.let {
            Controller.ControllerState(DetachedStateAdapter.capture(it.gameboy), it.config.rom)
          }
    }
    if (!closeAutosavePlayDurationCaptured) {
      closeAutosavePlayDurationNanos = currentPlayDurationNanos()
      closeAutosavePlayDurationCaptured = true
    }
    if (closeAutosaveCapture == null &&
        stateContext != null &&
        shouldPersistCloseAutosave()) {
      try {
        closeAutosaveCapture = session?.let(::capturePortableState)
      } catch (failure: Throwable) {
        throw closeBarrierFailure(
            "Close autosave could not be captured. The session is retained.",
            failure,
            "autosave state",
            autosaveWaivable = true,
        )
      }
    }
    if (closeCapture == null) {
      closeCapture = session?.gameboy?.prepareCartridgeFlush() ?: BatteryFlush.none()
    }

    // Once timing has stopped, drain every already-admitted ordinary state mutation. The exact
    // terminal autosave is written directly only after this worker is closed, so a queued delete
    // or older save can never run after and overwrite/remove it.
    try {
      stateWorker.close(
          remainingCloseNanos(closeDeadlineNanos, "state worker teardown"),
          TimeUnit.NANOSECONDS,
      )
    } catch (failure: IllegalStateException) {
      throw closeBarrierFailure(
          "State worker did not stop before the close deadline. Close can be retried.",
          failure,
      )
    }

    persistCloseAutosave(closeDeadlineNanos)

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
      shutdownExecutors(closeDeadlineNanos)
      session?.let {
        postSessionEventSafely(it, StateUxSessionEvent(stateSessionId, false, null))
      }
      eventBus.close(
          remainingCloseNanos(closeDeadlineNanos, "controller event-bus teardown"),
          TimeUnit.NANOSECONDS,
      )
      // Only after every task and subscriber that can still observe the live machine has
      // quiesced may final close release Session/Gameboy resources.
      stop(
          afterCartridgeFlush = true,
          notifyLifecycle = false,
          closeDeadlineNanos = closeDeadlineNanos,
      )
      isPaused = false
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
    closeAutosaveCapture = null
    closeAutosavePlayDurationNanos = null
    closeAutosavePlayDurationCaptured = false
    closeAutosaveAttempt = null
    closeAutosaveCompletedSessionId = null
    closeAutosaveSkippedSessionId = null
    closeAutosaveWaiverSessionId = null
    closeAutosaveWaivableRequestId = null
    return state
  }

  private fun persistCloseAutosave(closeDeadlineNanos: Long) {
    if (!shouldPersistCloseAutosave()) return
    val currentSession = session ?: return
    val context =
        stateContext
            ?: throw closeBarrierFailure(
                "Close autosave is unavailable because the configured save workspace could not " +
                    "be initialized. Choose a writable Saves directory or close explicitly " +
                    "without autosave.",
                java.io.IOException("Managed state workspace is unavailable"),
                "autosave state",
                autosaveWaivable = true,
            )
    val capture =
        closeAutosaveCapture
            ?: try {
              capturePortableState(currentSession).also { closeAutosaveCapture = it }
            } catch (failure: Throwable) {
              throw closeBarrierFailure(
                  "Close autosave could not be captured. The session is retained.",
                  failure,
                  "autosave state",
                  autosaveWaivable = true,
              )
            }
    val attempt =
        closeAutosaveAttempt
            ?: RetainedCloseAutosave(
                    Callable {
                      try {
                        val encoded = StateCodec.encode(capture, StateCompression.DEFLATE)
                        context.workspace.save(
                            StateRef.Autosave,
                            encoded,
                            StateSaveMetadata(
                                label = "Autosave",
                                savedAt = java.time.Instant.now(),
                                playDurationNanos = closeAutosavePlayDurationNanos,
                            ),
                            null,
                        )
                        CloseAutosaveResult.Success
                      } catch (failure: Throwable) {
                        CloseAutosaveResult.Failure(
                            stateError(
                                "Close autosave could not be completed.",
                                failure,
                                "The session is retained. Check the Saves directory and retry.",
                            ))
                      }
                    },
                    persistenceExecutor,
                )
                .also { closeAutosaveAttempt = it }
    val result =
        attempt.await(
            remainingCloseNanos(closeDeadlineNanos, "close autosave"),
            TimeUnit.NANOSECONDS,
        )
    when (result) {
      null ->
          throw closeBarrierFailure(
              "Close autosave did not finish before the controller close deadline. " +
                  "The immutable capture remains retained and close can be retried.",
              java.io.IOException("Close autosave timed out"),
              "autosave state",
              autosaveWaivable = false,
          )
      is CloseAutosaveResult.Failure -> {
        if (attempt.isDone) {
          closeAutosaveAttempt = null
        }
        throw closeBarrierFailure(
            "${result.error.summary} ${result.error.suggestedAction}",
            java.io.IOException(result.error.detail),
            "autosave state",
            autosaveWaivable = true,
        )
      }
      CloseAutosaveResult.Success -> {
        closeAutosaveCompletedSessionId = context.sessionId
        closeAutosaveSkippedSessionId = null
        closeAutosaveCapture = null
        closeAutosaveAttempt = null
      }
    }
  }

  private fun shouldPersistCloseAutosave(): Boolean =
      session != null &&
          properties.saves.autosavePolicy ==
              eu.rekawek.coffeegb.controller.properties.ApplicationSettings.AutosavePolicy
                  .ON_CLOSE_AND_ROM_SWITCH &&
          closeAutosaveCompletedSessionId != stateSessionId &&
          closeAutosaveSkippedSessionId != stateSessionId

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
            ?: RetainedClosePersistence(capture, persistenceExecutor).also {
              closePersistenceAttempt = it
            }
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
      fileName: String = closeFileName(),
      autosaveWaivable: Boolean = false,
  ): Controller.PersistenceBarrierException {
    val requestId = closeRequestId ?: nextPersistenceRequestId++
    closeRequestId = requestId
    closeAutosaveWaivableRequestId = requestId.takeIf { autosaveWaivable }
    return Controller.PersistenceBarrierException(
        requestId,
        Controller.PersistenceBarrierOperation.CLOSE,
        fileName,
        message,
        cause,
        closeAutosaveWaivable = autosaveWaivable,
    )
  }

  @Synchronized
  override fun waiveCloseAutosave(requestId: Long): Boolean {
    if (closed ||
        closeRequestId != requestId ||
        closeAutosaveWaivableRequestId != requestId ||
        session == null ||
        properties.saves.autosavePolicy !=
            eu.rekawek.coffeegb.controller.properties.ApplicationSettings.AutosavePolicy
                .ON_CLOSE_AND_ROM_SWITCH ||
        closeAutosaveCompletedSessionId == stateSessionId ||
        closeAutosaveAttempt?.isDone == false) {
      return false
    }
    closeAutosaveSkippedSessionId = stateSessionId
    closeAutosaveCapture = null
    closeAutosavePlayDurationNanos = null
    closeAutosavePlayDurationCaptured = false
    closeAutosaveAttempt = null
    closeAutosaveWaivableRequestId = null
    return true
  }

  private fun closeFileName(): String =
      session?.config?.rom?.origin?.displayName()
          ?: closeState?.rom?.origin?.displayName()
          ?: "battery save"

  private companion object {
    val LOG: Logger = LoggerFactory.getLogger(BasicController::class.java)

    const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 8_000L

    val STATE_SESSION_IDS = AtomicLong()

    val SYNTHETIC_OFFLINE_MOBILE_CONFIGURATION =
        Controller.MobileAdapterConfigurationProvider {
          Controller.MobileAdapterConfiguration.syntheticOffline()
        }

    fun nextStateSessionId(): Long =
        STATE_SESSION_IDS.updateAndGet { current -> Math.addExact(current, 1L) }

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

  private data class PendingRomSwitch(
      val requestId: Long,
      val event: Controller.LoadRomEvent,
      val context: StateWorkerContext,
      val state: StateFile,
      val playDurationNanos: Long?,
      var awaitingDecision: Boolean = false,
  )

  private data class PendingResume(
      val requestId: Long,
      val sessionId: Long,
      val key: StateEntryKey,
      val read: StateReadResult,
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

/**
 * Owns exactly one close writer across caller-side timeouts. A timed-out task is deliberately not
 * cancelled or replaced: some filesystems ignore interruption, and overlapping atomic writers
 * could otherwise publish stale bytes after a retry.
 */
internal class RetainedClosePersistence(
    capture: BatteryFlush,
    persistenceExecutor: ExecutorService,
    persist: (BatteryFlush) -> BatteryPersistenceResult = BatteryFlush::persist,
) {
  private val task =
      FutureTask<BatteryPersistenceResult>(Callable { persist(capture) }).also {
        // Replacement, stop, and close persistence share one ordered writer. A cancelled task can
        // remain inside filesystem code after ignoring interrupt; queueing close behind it keeps
        // an older generation from publishing after the final close capture.
        persistenceExecutor.execute(it)
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

internal sealed interface CloseAutosaveResult {
  data object Success : CloseAutosaveResult

  data class Failure(val error: StateUserError) : CloseAutosaveResult
}

/**
 * Retains one exact close-autosave writer across caller-side timeout and retry boundaries.
 * Persistence shares the controller's ordered physical writer with battery replacement.
 */
internal class RetainedCloseAutosave(
    callable: Callable<CloseAutosaveResult>,
    persistenceExecutor: ExecutorService,
) {
  private val task =
      FutureTask(callable).also {
        persistenceExecutor.execute(it)
      }

  val isDone: Boolean
    get() = task.isDone

  /** A null result means the same task is still retained for a later bounded retry. */
  fun await(timeout: Long, unit: TimeUnit): CloseAutosaveResult? {
    require(timeout > 0) { "Close autosave timeout must be positive" }
    return try {
      task.get(timeout, unit)
    } catch (_: TimeoutException) {
      null
    } catch (_: InterruptedException) {
      Thread.currentThread().interrupt()
      null
    } catch (failure: Exception) {
      CloseAutosaveResult.Failure(
          StateUserError(
              "Close autosave worker failed.",
              failure.cause?.message ?: failure.message ?: failure.javaClass.name,
              "The session is retained. Retry closing after checking the Saves directory.",
          ))
    }
  }
}

internal fun interface SnapshotManagerFactory {
  fun create(configuration: Gameboy.GameboyConfiguration): SnapshotManager

  companion object {
    val DEFAULT = SnapshotManagerFactory(::SnapshotManager)
  }
}

internal fun interface StateWorkspaceFactory {
  fun create(paths: StateStoragePaths): StateWorkspace

  companion object {
    val DEFAULT = StateWorkspaceFactory { StateWorkspace(it) }
  }
}

internal fun interface StateOperationWorkerFactory {
  fun create(eventBus: EventBus): StateOperationWorker

  companion object {
    val DEFAULT = StateOperationWorkerFactory { StateOperationWorker(it) }
  }
}

internal fun interface LiveBatteryStorageResolver {
  fun resolve(
      saves: ApplicationSettings.Saves,
      configuration: Gameboy.GameboyConfiguration,
      hashes: StateRomHashes,
  ): ResolvedBatteryStorage

  companion object {
    val DEFAULT = LiveBatteryStorageResolver(BatteryStorageResolver::resolve)
  }
}

private fun configuredRewindManager(
    properties: EmulatorProperties
): RewindManager = configuredRewindManager(properties.applicationSettings.saves)

private fun configuredRewindManager(
    saves: ApplicationSettings.Saves
): RewindManager =
    RewindManager(
        enabled = saves.rewindEnabled,
        durationSeconds = saves.rewindSeconds,
        memoryBudgetBytes = saves.rewindMemoryMiB.toLong() * 1024L * 1024L,
    )
