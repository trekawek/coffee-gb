package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.events.EventQueue
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.debug.DebugCheckpointHistory
import eu.rekawek.coffeegb.controller.debug.DebugHistorySessionBusyException
import eu.rekawek.coffeegb.controller.debug.DebugInstructionReplayer
import eu.rekawek.coffeegb.controller.debug.QueuedDebugCommand
import eu.rekawek.coffeegb.controller.debug.QueuedDebugPort
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationError
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterGuestConfigurationOfferResult
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterGuestConfigurationPersistencePhase
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterGuestConfigurationSink
import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterGuestConfigurationWrite
import eu.rekawek.coffeegb.controller.mobile.network.MobileAdapterNetworkBackend
import eu.rekawek.coffeegb.controller.mobile.network.MobileAdapterNetworkError as BackendNetworkError
import eu.rekawek.coffeegb.controller.mobile.network.MobileAdapterNetworkPhase as BackendNetworkPhase
import eu.rekawek.coffeegb.controller.mobile.network.MobileAdapterNetworkStatus
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
import eu.rekawek.coffeegb.controller.state.StateImage
import eu.rekawek.coffeegb.controller.state.StateIdentity
import eu.rekawek.coffeegb.controller.state.StateLoadRefRequestEvent
import eu.rekawek.coffeegb.controller.state.StateLoadRequestEvent
import eu.rekawek.coffeegb.controller.state.MachineStateRoot
import eu.rekawek.coffeegb.controller.state.MachineIdentity
import eu.rekawek.coffeegb.controller.state.SessionStateRoot
import eu.rekawek.coffeegb.controller.state.StateOpenFolderRequestEvent
import eu.rekawek.coffeegb.controller.state.StateOperation
import eu.rekawek.coffeegb.controller.state.StateOperationCompletedEvent
import eu.rekawek.coffeegb.controller.state.StateOperationFailedEvent
import eu.rekawek.coffeegb.controller.state.StateOperationWorker
import eu.rekawek.coffeegb.controller.state.StatePngCodec
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
import eu.rekawek.coffeegb.controller.state.StateSlotLoadAvailabilityEvent
import eu.rekawek.coffeegb.controller.state.StateSlotLoadAvailabilityRequestEvent
import eu.rekawek.coffeegb.controller.state.StateStoragePaths
import eu.rekawek.coffeegb.controller.state.StateStorageResolver
import eu.rekawek.coffeegb.controller.state.StateStore
import eu.rekawek.coffeegb.controller.state.StateUserError
import eu.rekawek.coffeegb.controller.state.StateUxSessionEvent
import eu.rekawek.coffeegb.controller.state.StateWorkerCompletedEvent
import eu.rekawek.coffeegb.controller.state.StateWorkerContext
import eu.rekawek.coffeegb.controller.state.StateWorkerPurpose
import eu.rekawek.coffeegb.controller.state.StateWorkerResult
import eu.rekawek.coffeegb.controller.state.StateWorkspace
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.ExecutionMode
import eu.rekawek.coffeegb.core.hardware.ClockSpec
import eu.rekawek.coffeegb.core.debug.Console
import eu.rekawek.coffeegb.core.debug.DebugButton
import eu.rekawek.coffeegb.core.debug.DebugBreakpointHit
import eu.rekawek.coffeegb.core.debug.DebugBreakpointList
import eu.rekawek.coffeegb.core.debug.DebugCapabilities
import eu.rekawek.coffeegb.core.debug.DebugCpuState
import eu.rekawek.coffeegb.core.debug.DebugErrorCode
import eu.rekawek.coffeegb.core.debug.DebugInstrumentation
import eu.rekawek.coffeegb.core.debug.DebugInspectionSection
import eu.rekawek.coffeegb.core.debug.DebugResult
import eu.rekawek.coffeegb.core.debug.DebugSnapshot
import eu.rekawek.coffeegb.core.debug.DebugStepKind
import eu.rekawek.coffeegb.core.debug.DebugStepResult
import eu.rekawek.coffeegb.core.debug.DebugStepStopReason
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryCapabilities
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryConfiguration
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryPosition
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryTruncationReason
import eu.rekawek.coffeegb.core.debug.history.DebugReverseStepResult
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory
import eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration
import eu.rekawek.coffeegb.core.debug.trace.TraceReadRequest
import eu.rekawek.coffeegb.core.debug.trace.TraceReadResult
import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.events.EventBusTeardownTimeoutException
import eu.rekawek.coffeegb.core.genie.AddPatches
import eu.rekawek.coffeegb.core.genie.CheatPatch
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent
import eu.rekawek.coffeegb.core.joypad.JoypadButtonMask
import eu.rekawek.coffeegb.core.memory.cart.Cartridge
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryFlush
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryPersistenceResult
import eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint
import eu.rekawek.coffeegb.core.serial.GameboyPrinterSerialEndpoint
import eu.rekawek.coffeegb.core.serial.GpsReceiverSerialEndpoint
import eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterEngine
import eu.rekawek.coffeegb.core.serial.mobile.MobileAdapterSerialEndpoint
import eu.rekawek.coffeegb.core.sgb.SgbDisplay
import java.util.EnumSet
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
    /**
     * Captures the most recently presented display image without making the controller depend on
     * a desktop toolkit. Autosaves remain valid if a presentation implementation cannot provide
     * an image, but the desktop injects this for the Home recent-game previews.
     */
    private val autosaveThumbnailProvider: () -> StateImage? = { null },
    private val mobileAdapterGuestConfigurationSink: MobileAdapterGuestConfigurationSink =
        MobileAdapterGuestConfigurationSink.NO_OP,
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
      autosaveThumbnailProvider: () -> StateImage? = { null },
      mobileAdapterGuestConfigurationSink: MobileAdapterGuestConfigurationSink =
          MobileAdapterGuestConfigurationSink.NO_OP,
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
      autosaveThumbnailProvider = autosaveThumbnailProvider,
      mobileAdapterGuestConfigurationSink = mobileAdapterGuestConfigurationSink,
  )

  internal constructor(
      parentEventBus: EventBus,
      properties: EmulatorProperties,
      console: Console?,
      sessionPreparer: SessionPreparer,
      mobileAdapterConfigurationProvider: Controller.MobileAdapterConfigurationProvider =
          SYNTHETIC_OFFLINE_MOBILE_CONFIGURATION,
      autosaveThumbnailProvider: () -> StateImage? = { null },
      mobileAdapterGuestConfigurationSink: MobileAdapterGuestConfigurationSink =
          MobileAdapterGuestConfigurationSink.NO_OP,
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
      autosaveThumbnailProvider = autosaveThumbnailProvider,
      mobileAdapterGuestConfigurationSink = mobileAdapterGuestConfigurationSink,
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
      autosaveThumbnailProvider: () -> StateImage? = { null },
      mobileAdapterGuestConfigurationSink: MobileAdapterGuestConfigurationSink =
          MobileAdapterGuestConfigurationSink.NO_OP,
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
      autosaveThumbnailProvider,
      mobileAdapterGuestConfigurationSink,
  )

  private val timingTicker = TimingTicker()

  private val eventBus: EventBus = parentEventBus.fork("session")

  private val eventQueue = EventQueue(eventBus)

  /** Two coherent constant-space control slots drained only by the controller frame owner. */
  private val mobileAdapterControlLane = MobileAdapterControlLane()

  /** Session-bound, bounded debugger lane; commands are executed only by [thread]. */
  private var debugPort: QueuedDebugPort? = null

  /** Explicitly opt-in; the ordinary run retains no reverse-debug snapshots. */
  private var debugCheckpointHistory = DebugCheckpointHistory()

  /** Lazily owns the service-isolated scratch machine used only by reverse-instruction. */
  private var debugInstructionReplayer = DebugInstructionReplayer()

  private var debugInstrumentation: DebugInstrumentation? = null

  private var lastDebugBreakpointHit: DebugBreakpointHit? = null

  /** Keeps background resume discovery from advancing past an automatic mid-frame stop. */
  private var debugBreakpointPauseActive = false

  private val nextDebugSessionGeneration = AtomicLong()

  private var debugSequence = 0L

  private var debugMasterTick = 0L

  private var debugFrame = 0L

  /** Emulated ticks since the last controller frame lattice boundary. */
  private var debugFramePosition = 0

  /** Benchmark-only epoch state; ordinary sessions never consult these fields in the hot loop. */
  private var benchmarkArmed = false
  private var benchmarkCoreFrozen = false
  private var benchmarkGeneration = 0L

  private var debugTrackingEnabled = false

  private var pendingDebugAction: PendingDebugAction? = null

  private val stateWorker = stateWorkerFactory.create(eventBus)

  private var session: Session? = null

  private var snapshotManager: SnapshotManager? = null

  private var stateContext: StateWorkerContext? = null

  private var currentRomHashes: StateRomHashes? = null

  private var stateSessionId = 0L

  private val internalStateRequestId = AtomicLong(1L shl 60)

  private val latestStateRequests = mutableMapOf<StateOperation, Long>()

  private val latestSaveRequests = mutableMapOf<StateRef, Long>()

  private var pendingSlotLoadAvailability: PendingSlotLoadAvailability? = null

  /** Successful manual saves whose captures intentionally normalized live host I/O. */
  private val mobileAdapterExternalIoSaveRequests = mutableSetOf<Long>()

  private var pendingResume: PendingResume? = null

  private var pendingRomSwitch: PendingRomSwitch? = null

  /** One explicit non-terminal battery persistence barrier, if any. */
  private var batteryFlushJob: BatteryFlushJob? = null

  private var pendingCloseRequestId: Long? = null

  private var sessionStartedNanos: Long? = null

  /** User pause state retained while an automatic resume scan/dialog owns the pause. */
  private var pauseStateBeforeResume: Boolean? = null

  @Volatile private var doStop = false

  private var isPaused = false

  /** Debugger pause ownership is independent from the desktop/workflow pause owner. */
  private var debugPaused = false

  /** Correlates authoritative playback events with the currently committed UI lifecycle. */
  private var playbackSessionGeneration: Long? = null

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

  /** Captured alongside [closeAutosaveCapture] so retries persist the exact same preview. */
  private var closeAutosaveThumbnail: StateImage? = null

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
    eventQueue.register<StateSlotLoadAvailabilityRequestEvent> {
      requestStateSlotLoadAvailability(it)
    }
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
    eventQueue.register<Controller.BenchmarkArmEvent> {
      val currentSession = session
      if (properties.overrides.benchmarkPolicyEnabled && !benchmarkArmed && currentSession != null) {
        benchmarkGeneration = it.generation
        benchmarkArmed = true
        benchmarkCoreFrozen = false
        currentSession.gameboy.resetPerformanceBulkCounters()
        debugFrame = 0L
        debugMasterTick = 0L
        debugFramePosition = 0
        timingTicker.resetForBenchmark()
        postSessionEventSafely(
            currentSession,
            Controller.BenchmarkArmAcknowledgedEvent(it.generation, it.token),
        )
      }
    }
    eventQueue.register<Controller.BenchmarkPhysicalFrameBoundaryEvent> {
      if (properties.overrides.benchmarkPolicyEnabled && benchmarkArmed
          && !benchmarkCoreFrozen && it.frame == 600L
          && it.generation == benchmarkGeneration) {
        val currentSession = session ?: return@register
        val speedMode = currentSession.gameboy.getSpeedMode()
        val gpu = currentSession.gameboy.getGpu()
        postSessionEventSafely(
            currentSession,
            Controller.BenchmarkFrameBoundaryEvent(
                it.frame,
                gpu.isGbc(),
                gpu.isDmgCompatMode(),
                speedMode.getSpeedMode(),
                currentSession.gameboy.getPerformanceBulkSpanCount(),
                currentSession.gameboy.getPerformanceBulkTicks(),
            ),
        )
        benchmarkCoreFrozen = true
        setPaused(true)
      }
    }
    eventQueue.register<Controller.ResumeEmulationEvent> {
      if (properties.overrides.benchmarkPolicyEnabled && benchmarkCoreFrozen) {
        // The 600th measured core boundary owns the terminal freeze.  A lifecycle/audio resume
        // cannot accidentally create an unmeasured 601st frame before the host collects SF data.
        return@register
      }
      if (pauseStateBeforeLoading != null) {
        pauseStateBeforeLoading = false
      } else if (pauseStateBeforeResume != null) {
        pauseStateBeforeResume = false
      } else {
        releaseInteractivePauseOwners(releaseApplicationPause = true, releaseDebuggerPause = true)
      }
    }
    eventQueue.register<Controller.FlushBatteryEvent> { requestBatteryFlush(it) }
    eventQueue.register<Controller.RewindEvent> {
      // Disabled rewind is a real no-work mode: the key cannot freeze forward emulation and
      // runFrame never reaches a machine capture.
      if (rewindManager.enabled && it.active && !isRewinding) {
        debugCheckpointHistory.clear(DebugHistoryTruncationReason.USER_REWIND)
      }
      if (rewindManager.enabled && it.active && !isRewinding && hasMobileAdapterExternalIo()) {
        // Rewind history cannot bridge an interval whose host effects are deliberately absent
        // from snapshots. This is also the last-resort boundary when external ownership appears
        // between the frame owner's ordinary observations.
        rewindManager.clear()
        postMobileAdapterStateBoundary(Controller.MobileAdapterStateBoundary.REWIND)
        disconnectMobileAdapter(Controller.MobileAdapterDisconnectReason.REWIND)
      }
      isRewinding = rewindManager.enabled && it.active
      // Rewind restores arbitrary machine positions; keep host output complete rather than
      // carrying an adaptive request across a non-forward presentation path.
      session?.gameboy?.requestFrameRenderSuppression(false)
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
      if (hasMobileAdapterExternalIo()) {
        postMobileAdapterStateBoundary(Controller.MobileAdapterStateBoundary.RESET)
        disconnectMobileAdapter(Controller.MobileAdapterDisconnectReason.PROTOCOL_RESET)
      }
      session?.config?.rom?.image?.let {
        requestLoad(
            properties,
            Controller.LoadRomEvent(it, allowAutosaveResume = false),
            clearPatches = false,
        )
      }
    }
    eventQueue.register<Controller.StopEmulationEvent> {
      requestStop()
    }
    eventQueue.register<Controller.SetSerialPeripheralEvent> {
      selectSerialPeripheral(it.selection)
    }
    eventBus.register<Controller.RefreshMobileAdapterConfigurationEvent> { event ->
      mobileAdapterControlLane.offerRefresh(event.revision)
    }
    eventBus.register<Controller.CancelMobileAdapterNetworkEvent> {
      mobileAdapterControlLane.offerCancel()
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
        val newExecutionMode = properties.system.executionMode
        if (newProfile != config.hardwareProfile ||
            newBootstrapMode != config.bootstrapMode ||
            newExecutionMode != config.executionMode) {
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
    // An instruction-safe pause may stop between controller frame boundaries. Keep ordinary
    // application/state events at their established frame safe point while the private debug lane
    // remains responsive and can resume or advance the machine to the next lattice boundary.
    if (debugFramePosition != 0 || pendingDebugAction != null) {
      runDebugContinuation()
      return
    }

    eventQueue.dispatch()
    drainMobileAdapterControlLane()
    pollMobileAdapterBackend()
    pollMobileAdapterGuestConfigurationPersistence()
    // A live request or logical connection makes every earlier rewind entry discontinuous.
    // Clear even while paused; after ownership ends, recording starts a fresh history instead of
    // appending post-I/O captures to states from before the unrepresentable host interaction.
    if (hasMobileAdapterExternalIo()) {
      rewindManager.clear()
      debugCheckpointHistory.clear(DebugHistoryTruncationReason.NONDETERMINISTIC_IO)
    }
    finishPreparedLoad()
    finishReplacement()
    finishStop()
    finishBatteryFlush()

    // rewinding restores one recorded state and then emulates a single frame from it,
    // so the display and audio play backwards at RewindManager.RECORD_INTERVAL speed
    val rewound =
        loadJob == null &&
            replacementJob == null &&
            stopJob == null &&
            isRewinding &&
            session?.let { rewindManager.rewindOneStep(it) } == true
    if (rewound) {
      relinquishDebugBreakpointPauseOwnership()
    }

    releaseClosedDebugPortIfNeeded()
    drainDebugCommands()

    var emulated = false
    val clockSpec = session?.gameboy?.clockSpec ?: ClockSpec.LEGACY
    val frameTicks = clockSpec.controllerTicksPerFrame()
    if (!rewound && (debugPaused || pendingDebugAction != null)) {
      session?.gameboy?.requestFrameRenderSuppression(false)
      runDebugTickWindow(clockSpec, stopAtNextBoundary = false)
      return
    }

    if (!rewound &&
        !isEffectivelyPaused() &&
        !isRewinding &&
        debugInstrumentation?.hasEnabledBreakpoints() == true) {
      session?.gameboy?.requestFrameRenderSuppression(false)
      runDebugTickWindow(clockSpec, stopAtNextBoundary = false)
      return
    }

    val suppressDebugObservation = rewound && debugInstrumentation?.isActive == true
    if (suppressDebugObservation) {
      session?.gameboy?.let(::detachAndClearDebugTimelineObservation)
    }

    val trackDebugHistory = !rewound && debugCheckpointHistory.enabled
    session?.gameboy?.requestFrameRenderSuppression(
        !rewound &&
            !isEffectivelyPaused() &&
            !isRewinding &&
            debugPort == null &&
            debugInstrumentation == null &&
            !debugCheckpointHistory.enabled &&
            !properties.overrides.benchmarkPolicyEnabled &&
            !timingTicker.disabled &&
            timingTicker.hasPacingDebt,
    )
    val gameboy = session?.gameboy
    if (gameboy != null && (rewound || (!isEffectivelyPaused() && !isRewinding))) {
      relinquishDebugBreakpointPauseOwnership()
      if (!trackDebugHistory && !rewound && gameboy.executionMode == ExecutionMode.PERFORMANCE) {
        // The core owns the frame-sized loop in ordinary PERFORMANCE mode. Debug/history
        // paths remain on their per-tick hooks so every observation and checkpoint boundary is
        // still materialized before the next callback.
        gameboy.runTicks(frameTicks)
      } else {
        repeat(frameTicks) {
          if (trackDebugHistory) {
            tickWithDebugHistory(gameboy, frameTicks)
          } else {
            gameboy.tick()
          }
        }
      }
      emulated = true
    }
    timingTicker.runFrame(clockSpec)
    if (emulated) {
      debugMasterTick = Math.addExact(debugMasterTick, frameTicks.toLong())
      debugFrame = Math.addExact(debugFrame, 1L)
      debugFramePosition = 0
    }
    if (suppressDebugObservation) {
      syncDebugInstrumentation()
    }
    if (emulated && !rewound) {
      recordCompletedFrame()
    }
  }

  private fun runDebugContinuation() {
    session?.gameboy?.requestFrameRenderSuppression(false)
    if (pendingDebugAction == null) {
      serviceDebugSafePointControls()
    }
    releaseClosedDebugPortIfNeeded()
    drainDebugCommands()
    if (session == null) {
      debugFramePosition = 0
      return
    }
    if (debugFramePosition == 0 && pendingDebugAction == null && !debugPaused) {
      // Resume at a frame boundary re-enters the ordinary path so queued desktop/state events are
      // dispatched before the next guest tick.
      return
    }
    val clockSpec = session?.gameboy?.clockSpec ?: ClockSpec.LEGACY
    val queuedBoundaryControl =
        eventQueue.anyEvent { event ->
          requiresDebugFrameBoundary(event) &&
              !(debugBreakpointPauseActive &&
                  event is StateWorkerCompletedEvent &&
                  event.purpose == StateWorkerPurpose.RESUME_SCAN)
        }
    val completedLifecycleWork = hasCompletedLifecycleWork()
    // Detaching the debugger cannot leave its partial frame as a hidden third pause owner. Finish
    // that frame once even when the desktop pause remains active, then restore ordinary FIFO work.
    val finishPartialFrame =
        debugFramePosition != 0 &&
            pendingDebugAction == null &&
            (!isEffectivelyPaused() ||
                queuedBoundaryControl ||
                completedLifecycleWork ||
                debugPort == null)
    runDebugTickWindow(clockSpec, stopAtNextBoundary = finishPartialFrame)
  }

  /**
   * Applies only pause-ownership changes while an instruction-safe pause is between ordinary frame
   * boundaries. Those controls cannot replace or mutate the session, so they may bypass ordinary
   * frame-boundary work. Lifecycle controls never bypass: their presence completes the partial
   * frame and the entire queue is then dispatched in producer order.
   */
  private fun serviceDebugSafePointControls() {
    var remaining = MAX_DEBUG_CONTROL_EVENTS_PER_SAFE_POINT
    while (remaining-- > 0 && eventQueue.dispatchFirstMatching(::isImmediateDebugControl)) {
      // Relative order among pause-ownership changes and among retained events is preserved.
    }
    drainMobileAdapterControlLane()
  }

  private fun isImmediateDebugControl(event: Event): Boolean =
      event is Controller.PauseEmulationEvent || event is Controller.ResumeEmulationEvent

  /**
   * Lifecycle controls that must not be starved by a debugger-owned mid-frame pause. Immediate
   * pause-ownership controls are handled at the next safe point instead of advancing paused state.
   */
  private fun requiresDebugFrameBoundary(event: Event): Boolean =
      event is Controller.LoadRomEvent ||
          event is Controller.CancelRomOpenEvent ||
          event is Controller.RetryRomReplacementEvent ||
          event is Controller.CancelRomReplacementEvent ||
          event is Controller.ResetEmulationEvent ||
          event is Controller.StopEmulationEvent ||
          event is Controller.UpdatedSystemMappingEvent ||
          event is StatePrepareCloseRequestEvent ||
          event is StateSkipCloseAutosaveRequestEvent ||
          event is StateResumeDecisionEvent ||
          (event is StateWorkerCompletedEvent && event.purpose != StateWorkerPurpose.MANUAL)

  private fun hasCompletedLifecycleWork(): Boolean =
      loadJob?.task?.isDone == true ||
          replacementJob?.attempt?.isDone == true ||
          stopJob?.attempt?.isDone == true

  private fun runDebugTickWindow(clockSpec: ClockSpec, stopAtNextBoundary: Boolean) {
    session?.gameboy?.requestFrameRenderSuppression(false)
    val frameTicks = clockSpec.controllerTicksPerFrame()
    var holdAtBoundary = false
    repeat(frameTicks) {
      if (!holdAtBoundary && !isRewinding) {
        // A queued lifecycle control may explicitly supersede a breakpoint-owned mid-frame stop.
        // Retain the debugger pause while finishing this partial frame, but make the hit
        // historical before the first guest tick so the stop reason cannot describe moved state.
        val shouldTick =
            pendingDebugAction != null || !isEffectivelyPaused() || stopAtNextBoundary
        val gameboy = session?.gameboy
        if (shouldTick && gameboy != null) {
          relinquishDebugBreakpointPauseOwnership()
          if (debugCheckpointHistory.enabled) {
            tickWithDebugHistory(gameboy, frameTicks)
          } else {
            gameboy.tick()
          }
          debugMasterTick = Math.addExact(debugMasterTick, 1L)
          debugFramePosition++
          if (debugFramePosition == frameTicks) {
            debugFramePosition = 0
            debugFrame = Math.addExact(debugFrame, 1L)
            debugInstrumentation?.onFrameBoundary(debugFrame)
            recordCompletedFrame()
            if (stopAtNextBoundary) {
              holdAtBoundary = true
            }
          }
          if (handleDebugBreakpointMatch(gameboy)) {
            // Even a continuation that was completing a resumed partial frame must stop at the
            // newly observed breakpoint. Explicit lifecycle work can supersede it on the next
            // owner iteration; background resume discovery waits for the debugger to advance.
            holdAtBoundary = true
          } else {
            finishPendingDebugAction(gameboy, frameTicks)
          }
        }
      }
      timingTicker.run(clockSpec)
    }
  }

  /** Executes one complete guest tick and advances the separate historical cursor atomically. */
  private fun tickWithDebugHistory(gameboy: Gameboy, frameTicks: Int) {
    if (!debugCheckpointHistory.inputTimelineValid) {
      debugCheckpointHistory.clear(DebugHistoryTruncationReason.NONDETERMINISTIC_IO)
    } else {
      debugCheckpointHistory.invalidateFuture(checkNotNull(session))
    }
    val retirement = gameboy.debugRetirementSequence
    debugCheckpointHistory.onTickStarted(isEffectivelyPaused())
    try {
      gameboy.tick()
    } catch (failure: Throwable) {
      debugCheckpointHistory.abortTick()
      throw failure
    }
    debugCheckpointHistory.onTickCompleted(
        gameboy.debugRetirementSequence != retirement,
        frameTicks,
    )
  }

  private fun recordCompletedFrame() {
    val currentSession = session ?: return
    drainMobileAdapterGuestConfiguration(currentSession)
    pollMobileAdapterGuestConfigurationPersistence()
    // A CGB can admit and finish a backend request between two controller frame boundaries. The
    // endpoint's one-shot fence prevents rewind history from bridging that nondeterministic host
    // effect even when no request or logical connection remains live at the end of this frame.
    val mobileExternalIoObserved = consumeMobileAdapterExternalIoActivity()
    if (mobileExternalIoObserved || hasMobileAdapterExternalIo()) {
      rewindManager.clear()
      if (mobileExternalIoObserved) {
        debugCheckpointHistory.clear(DebugHistoryTruncationReason.NONDETERMINISTIC_IO)
      }
    } else {
      rewindManager.record(currentSession)
    }
    if (!debugCheckpointHistory.enabled) return
    if (!supportsDebugHistory(currentSession)) {
      debugCheckpointHistory.clear(debugHistoryUnavailableReason())
    } else {
      try {
        debugCheckpointHistory.recordFrame(currentSession)
      } catch (failure: Exception) {
        // History is optional. A capture failure releases retained snapshots and cannot stop the
        // emulation owner or recur at every later frame.
        LOG.warn("Reverse-history checkpoint capture failed; disabling history", failure)
        debugCheckpointHistory.disable(DebugHistoryTruncationReason.SESSION_BOUNDARY)
      }
    }
  }

  private fun releaseClosedDebugPortIfNeeded(notifyLifecycle: Boolean = true) {
    val port = debugPort ?: return
    if (!port.isClosed) {
      return
    }
    debugCheckpointHistory.disable(DebugHistoryTruncationReason.SESSION_BOUNDARY)
    debugInstructionReplayer.close()
    val currentSession = session
    if (debugTrackingEnabled) {
      currentSession?.gameboy?.disableDebugRetirementTracking()
    }
    currentSession?.gameboy?.updateDebugInstrumentation(null, debugMasterTick)
    pendingDebugAction = null
    debugTrackingEnabled = false
    debugInstrumentation = null
    lastDebugBreakpointHit = null
    setDebugPaused(false)
    debugPort = null
    console?.setDebugPort(null)
    if (notifyLifecycle && currentSession != null) {
      postSessionEventSafely(
          currentSession,
          Controller.SessionDebugPortEvent(port.sessionGeneration(), null),
      )
    }
  }

  private fun drainDebugCommands() {
    val port = debugPort ?: return
    if (pendingDebugAction != null) {
      return
    }
    repeat(MAX_DEBUG_COMMANDS_PER_SAFE_POINT) {
      val command = port.pollCommand() ?: return
      // Producers can enqueue a desktop pause and then a debug command while the owner is between
      // its control-lane and command-lane polls. Recheck the leading FIFO controls after claiming
      // the command so that an already-visible desktop ownership change wins this safe point.
      serviceDebugSafePointControls()
      if (command.sessionGeneration != port.sessionGeneration()) {
        command.fail(
            DebugErrorCode.SESSION_REPLACED,
            "The debug command belongs to a replaced session",
        )
      } else if (session == null) {
        command.fail(DebugErrorCode.NO_ACTIVE_SESSION, "There is no active emulation session")
      } else if (debugSessionBusy()) {
        command.fail(
            DebugErrorCode.SESSION_BUSY,
            "The session is changing state and has no debugger safe point",
        )
      } else {
        handleDebugCommand(command)
      }
      if (pendingDebugAction != null) {
        return
      }
    }
  }

  private fun debugSessionBusy(): Boolean =
      loadJob != null || replacementJob != null || stopJob != null || isRewinding

  private fun handleDebugCommand(command: QueuedDebugCommand<*>) {
    try {
      when (command) {
        is QueuedDebugCommand.Pause -> handleDebugPause(command)
        is QueuedDebugCommand.Resume -> {
          // The desktop menu and the debugger's execution window are two views of the same
          // playback control. A regular desktop pause therefore remains resumable from the
          // debugger, while workflow pauses (loading and autosave resume discovery) stay owned
          // by their workflow until it reaches a safe decision point.
          val canReleaseApplicationPause =
              isPaused && pauseStateBeforeLoading == null && pauseStateBeforeResume == null
          if (!debugPaused && !canReleaseApplicationPause) {
            command.fail(DebugErrorCode.ALREADY_RUNNING, "The emulator is already running")
            return
          }
          debugCheckpointHistory.invalidateFuture(checkNotNull(session))
          releaseInteractivePauseOwners(
              releaseApplicationPause = canReleaseApplicationPause,
              releaseDebuggerPause = debugPaused,
          )
          command.complete(DebugResult.success(captureDebugSnapshot()))
        }
        is QueuedDebugCommand.Snapshot ->
            command.complete(DebugResult.success(captureDebugSnapshot()))
        is QueuedDebugCommand.Inspect -> handleDebugInspection(command)
        is QueuedDebugCommand.Step -> handleDebugStep(command)
        is QueuedDebugCommand.ConfigureHistory -> handleDebugConfigureHistory(command)
        is QueuedDebugCommand.HistoryStatus ->
            command.complete(DebugResult.success(debugCheckpointHistory.status()))
        is QueuedDebugCommand.StepBackward -> handleDebugStepBackward(command)
        is QueuedDebugCommand.ReadMemory -> handleDebugMemoryRead(command)
        is QueuedDebugCommand.WriteMemory -> handleDebugMemoryWrite(command)
        is QueuedDebugCommand.SetAudioChannel -> handleDebugSetAudioChannel(command)
        is QueuedDebugCommand.SetButton -> handleDebugButton(command)
        is QueuedDebugCommand.SetBreakpoint -> handleDebugSetBreakpoint(command)
        is QueuedDebugCommand.RemoveBreakpoint -> handleDebugRemoveBreakpoint(command)
        is QueuedDebugCommand.ListBreakpoints ->
            command.complete(
                DebugResult.success(checkNotNull(debugInstrumentation).listBreakpoints()))
        is QueuedDebugCommand.LastBreakpointHit -> {
          val hit = lastDebugBreakpointHit
          if (hit == null) {
            command.fail(
                DebugErrorCode.NO_BREAKPOINT_HIT,
                "No breakpoint has stopped this session",
            )
          } else {
            command.complete(DebugResult.success(hit))
          }
        }
        is QueuedDebugCommand.ConfigureTrace -> handleDebugConfigureTrace(command)
        is QueuedDebugCommand.ReadTrace ->
            command.complete(
                DebugResult.success(
                    checkNotNull(debugInstrumentation).readTrace(command.request)))
      }
    } catch (failure: Exception) {
      LOG.warn("Debug command {} failed", command.javaClass.simpleName, failure)
      command.fail(DebugErrorCode.INTERNAL_ERROR, "The debug command could not be completed")
    }
  }

  private fun handleDebugPause(command: QueuedDebugCommand.Pause) {
    ensureDebugTracking()
    if (debugPaused) {
      command.fail(DebugErrorCode.ALREADY_PAUSED, "The debugger already owns a pause")
      return
    }
    val current = captureDebugSnapshot()
    if (isPaused || current.execution().cpuState().isDebuggerIdle()) {
      setDebugPaused(true)
      command.complete(DebugResult.success(captureDebugSnapshot()))
      return
    }
    val retirement = checkNotNull(session).gameboy.debugRetirementSequence
    pendingDebugAction =
        PendingDebugAction.Pause(
            command,
            debugMasterTick,
            Math.addExact(retirement, 1L),
        )
  }

  private fun handleDebugStep(command: QueuedDebugCommand.Step) {
    if (!isEffectivelyPaused()) {
      command.fail(DebugErrorCode.NOT_PAUSED, "Pause the debug session before stepping")
      return
    }
    ensureDebugTracking()
    val gameboy = checkNotNull(session).gameboy
    val before = captureDebugSnapshot()
    when (command.kind) {
      DebugStepKind.MACHINE_CYCLE ->
          command.fail(
              DebugErrorCode.UNSUPPORTED_STEP,
              "Machine-cycle stepping is not supported by this session",
          )
      DebugStepKind.INSTRUCTION -> {
        when (before.execution().cpuState()) {
          DebugCpuState.LOCKED -> {
            command.fail(DebugErrorCode.CPU_LOCKED, "The CPU is locked by an illegal opcode")
            return
          }
          DebugCpuState.HALTED,
          DebugCpuState.STOPPED,
          DebugCpuState.SPEED_SWITCH -> {
            command.fail(DebugErrorCode.CPU_IDLE, "The CPU cannot retire an instruction now")
            return
          }
          else -> Unit
        }
        debugCheckpointHistory.invalidateFuture(checkNotNull(session))
        // A desktop/workflow pause is sufficient to authorize the request, but the debugger
        // acquires its own ownership so the result remains stopped if that other owner resumes.
        relinquishDebugBreakpointPauseOwnership()
        setDebugPaused(true)
        val retirement = gameboy.debugRetirementSequence
        pendingDebugAction =
            PendingDebugAction.InstructionStep(
                command,
                debugMasterTick,
                retirement,
                Math.addExact(retirement, 1L),
            )
      }
      DebugStepKind.FRAME -> {
        debugCheckpointHistory.invalidateFuture(checkNotNull(session))
        relinquishDebugBreakpointPauseOwnership()
        setDebugPaused(true)
        val ticksToBoundary =
            if (debugFramePosition == 0) {
              gameboy.clockSpec.controllerTicksPerFrame()
            } else {
              gameboy.clockSpec.controllerTicksPerFrame() - debugFramePosition
            }
        pendingDebugAction =
            PendingDebugAction.FrameStep(
                command,
                debugMasterTick,
                gameboy.debugRetirementSequence,
                Math.addExact(debugMasterTick, ticksToBoundary.toLong()),
            )
      }
    }
  }

  private fun handleDebugConfigureHistory(command: QueuedDebugCommand.ConfigureHistory) {
    val currentSession = checkNotNull(session)
    val requested = command.configuration
    if (requested.enabled() && !supportsDebugHistory(currentSession)) {
      command.fail(
          DebugErrorCode.UNSUPPORTED_TOPOLOGY,
          "Reverse history requires isolated I/O and cartridges without live sensors",
      )
      return
    }
    try {
      debugInstructionReplayer.close()
      if (requested.enabled()) {
        ensureDebugTracking()
      }
      command.complete(
          DebugResult.success(
              debugCheckpointHistory.configure(
                  requested,
                  currentSession,
                  debugMasterTick,
                  debugFrame,
                  debugFramePosition,
              )))
    } catch (failure: DebugHistorySessionBusyException) {
      LOG.debug("Reverse history input timeline is already owned", failure)
      command.fail(
          DebugErrorCode.SESSION_BUSY,
          "Another deterministic capture already owns the session input timeline",
      )
    } catch (failure: Exception) {
      debugCheckpointHistory.disable(DebugHistoryTruncationReason.SESSION_BOUNDARY)
      LOG.warn("Unable to configure reverse history", failure)
      command.fail(DebugErrorCode.INTERNAL_ERROR, "Reverse history could not be configured")
    }
  }

  private fun handleDebugStepBackward(command: QueuedDebugCommand.StepBackward) {
    if (!isEffectivelyPaused()) {
      command.fail(DebugErrorCode.NOT_PAUSED, "Pause the debug session before stepping backward")
      return
    }
    if (command.kind == DebugStepKind.MACHINE_CYCLE) {
      command.fail(DebugErrorCode.UNSUPPORTED_STEP, "Reverse machine-cycle is unavailable")
      return
    }
    val currentSession = checkNotNull(session)
    if (!supportsDebugHistory(currentSession)) {
      debugCheckpointHistory.clear(debugHistoryUnavailableReason())
      command.fail(
          DebugErrorCode.UNSUPPORTED_TOPOLOGY,
          "Reverse history requires isolated I/O and cartridges without live sensors",
      )
      return
    }
    if (!debugCheckpointHistory.enabled) {
      command.fail(DebugErrorCode.HISTORY_DISABLED, "Reverse history is disabled")
      return
    }
    if (!debugCheckpointHistory.inputTimelineValid) {
      debugCheckpointHistory.clear(DebugHistoryTruncationReason.NONDETERMINISTIC_IO)
      command.fail(
          DebugErrorCode.HISTORY_EXHAUSTED,
          "Reverse history was reset after an unrepresentable input transition",
      )
      return
    }
    when (command.kind) {
      DebugStepKind.FRAME -> reverseDebugFrame(command, currentSession)
      DebugStepKind.INSTRUCTION -> reverseDebugInstruction(command, currentSession)
      DebugStepKind.MACHINE_CYCLE -> error("Handled above")
    }
  }

  private fun reverseDebugFrame(
      command: QueuedDebugCommand.StepBackward,
      currentSession: Session,
  ) {
    val outcome =
        try {
          debugCheckpointHistory.restorePreviousFrame(
              currentSession,
              atFrameBoundary = debugFramePosition == 0,
              effectiveCartridgePause = true,
          )
        } catch (failure: Exception) {
          // Machine/serial rollback cannot reconstruct transient producer correlation. Break the
          // observation timeline before the generic owner containment reports INTERNAL_ERROR.
          resetDebugTimelineObservation(currentSession.gameboy)
          throw failure
        }
    when (outcome) {
      DebugCheckpointHistory.RestoreOutcome.Disabled ->
          command.fail(DebugErrorCode.HISTORY_DISABLED, "Reverse history is disabled")
      DebugCheckpointHistory.RestoreOutcome.Exhausted ->
          command.fail(
              DebugErrorCode.HISTORY_EXHAUSTED,
              "No preceding frame checkpoint is retained",
          )
      is DebugCheckpointHistory.RestoreOutcome.Restored -> {
        finishDebugReverse(outcome.position, currentSession)
        command.complete(
            DebugResult.success(
                DebugReverseStepResult(
                    DebugStepKind.FRAME,
                    outcome.position,
                    outcome.anchor,
                    captureDebugSnapshot(),
                    outcome.status,
                )))
      }
    }
  }

  private fun reverseDebugInstruction(
      command: QueuedDebugCommand.StepBackward,
      currentSession: Session,
  ) {
    if (!supportsDebugInstructionHistory(currentSession)) {
      command.fail(
          DebugErrorCode.UNSUPPORTED_TOPOLOGY,
          "Reverse-instruction requires an isolated serial link and replay-safe cartridge",
      )
      return
    }
    val plan = debugCheckpointHistory.planPreviousInstruction()
    if (plan == null) {
      command.fail(
          DebugErrorCode.HISTORY_EXHAUSTED,
          "No preceding instruction boundary is retained",
      )
      return
    }
    val gameboy = currentSession.gameboy
    val result =
        debugInstructionReplayer.replay(
            currentSession,
            plan,
            gameboy.clockSpec.controllerTicksPerFrame(),
        )

    detachAndClearDebugTimelineObservation(gameboy)
    val status =
        try {
          result.snapshot.restore(currentSession, effectiveCartridgePause = true)
          gameboy.seedDeterministicReplayInput(
              JoypadButtonMask.toButtons(result.input.legacyMask),
              result.input.physical,
          )
          debugCheckpointHistory.commitInstructionReverse(plan, result.position)
        } catch (failure: Exception) {
          syncDebugInstrumentation()
          throw failure
        }
    finishDebugReverse(result.position, currentSession, resetObservation = false)
    syncDebugInstrumentation()
    command.complete(
        DebugResult.success(
            DebugReverseStepResult(
                DebugStepKind.INSTRUCTION,
                result.position,
                plan.anchor,
                captureDebugSnapshot(),
                status,
            )))
  }

  private fun finishDebugReverse(
      position: DebugHistoryPosition,
      currentSession: Session,
      resetObservation: Boolean = true,
  ) {
    // Public observation coordinates remain monotonic. Only the frame-lattice phase follows the
    // restored machine; the result/status expose the original historical coordinate separately.
    rewindManager.clear()
    debugFramePosition = position.framePosition()
    relinquishDebugBreakpointPauseOwnership()
    setDebugPaused(true)
    if (resetObservation) {
      resetDebugTimelineObservation(currentSession.gameboy)
    }
  }

  private fun supportsDebugHistory(currentSession: Session): Boolean {
    val serialEndpoint = currentSession.serialEndpoint
    return (serialEndpoint === SerialEndpoint.NULL_ENDPOINT ||
        serialEndpoint is Peer2PeerSerialEndpoint && !serialEndpoint.isConnected) &&
        currentSession.infraredEndpoint === InfraredEndpoint.NULL_ENDPOINT &&
        !hasMobileAdapterExternalIo() &&
        !hasLiveCartridgeSensor(currentSession)
  }

  private fun supportsDebugInstructionHistory(currentSession: Session): Boolean =
      supportsDebugHistory(currentSession) &&
          supportsDebugInstructionReplay(currentSession)

  /** Static session capability; temporary host-I/O topology is checked when history is used. */
  private fun supportsDebugInstructionReplay(currentSession: Session): Boolean =
      isInstructionReplayCartridge(currentSession.config.rom) &&
          currentSession.config.slotRom?.let(::isInstructionReplayCartridge) != false

  private fun isInstructionReplayCartridge(rom: Rom): Boolean {
    val mapper = rom.cartridgeProperties.mapper
    return !rom.type.isHuc3 &&
        !rom.type.isTama5 &&
        mapper != CartridgeProperties.Mapper.DATEL &&
        mapper != CartridgeProperties.Mapper.SL_MULTICART
  }

  private fun debugHistoryUnavailableReason(): DebugHistoryTruncationReason =
      if (hasMobileAdapterExternalIo()) {
        DebugHistoryTruncationReason.NONDETERMINISTIC_IO
      } else {
        DebugHistoryTruncationReason.TOPOLOGY_CHANGED
      }

  private fun hasLiveCartridgeSensor(currentSession: Session): Boolean =
      isLiveSensorCartridge(currentSession.config.rom) ||
          currentSession.config.slotRom?.let(::isLiveSensorCartridge) == true

  private fun isLiveSensorCartridge(rom: Rom): Boolean {
    val type = rom.type
    return type.isMbc7 ||
        type.isPocketCamera ||
        rom.cartridgeProperties.mapper == CartridgeProperties.Mapper.POCKET_CAMERA
  }

  private fun handleDebugMemoryRead(command: QueuedDebugCommand.ReadMemory) {
    val gameboy = checkNotNull(session).gameboy
    try {
      command.complete(DebugResult.success(gameboy.readDebugMemory(command.request)))
    } catch (_: UnsupportedOperationException) {
      command.fail(
          DebugErrorCode.UNSUPPORTED_ADDRESS_SPACE,
          "The requested address space has no side-effect-free debugger view",
      )
    } catch (_: IllegalArgumentException) {
      command.fail(
          DebugErrorCode.SIDE_EFFECTFUL_ADDRESS,
          "The requested range contains a side-effectful or unavailable address",
      )
    }
  }

  private fun handleDebugMemoryWrite(command: QueuedDebugCommand.WriteMemory) {
    if (!isEffectivelyPaused()) {
      command.fail(DebugErrorCode.NOT_PAUSED, "Pause the debug session before editing memory")
      return
    }
    val currentSession = checkNotNull(session)
    val gameboy = currentSession.gameboy
    try {
      gameboy.writeDebugMemory(command.write)
      // A direct RAM change cannot be reconstructed by the existing input-only replay log. Drop
      // retained rewind/reverse observations rather than making a later reverse appear valid.
      rewindManager.clear()
      debugCheckpointHistory.clear(DebugHistoryTruncationReason.BRANCH_INVALIDATED)
      resetDebugTimelineObservation(gameboy)
      command.complete(DebugResult.success(captureDebugSnapshot()))
    } catch (_: UnsupportedOperationException) {
      command.fail(
          DebugErrorCode.UNSUPPORTED_ADDRESS_SPACE,
          "The selected address space is not writable by the debugger",
      )
    } catch (_: IllegalArgumentException) {
      command.fail(
          DebugErrorCode.SIDE_EFFECTFUL_ADDRESS,
          "The selected address is side-effectful or unavailable for debugger writes",
      )
    }
  }

  private fun handleDebugSetAudioChannel(command: QueuedDebugCommand.SetAudioChannel) {
    val gameboy = checkNotNull(session).gameboy
    try {
      // This is an output-only debugger mixer override. It intentionally remains available while
      // running and does not invalidate rewind history because emulated registers and RAM stay
      // untouched.
      gameboy.setDebugAudioChannelEnabled(command.channel, command.enabled)
      command.complete(DebugResult.success(captureDebugSnapshot()))
    } catch (_: IllegalArgumentException) {
      command.fail(DebugErrorCode.INVALID_ARGUMENT, "Audio channel must be between 1 and 4")
    }
  }

  private fun handleDebugInspection(command: QueuedDebugCommand.Inspect) {
    val gameboy = checkNotNull(session).gameboy
    try {
      val snapshot = captureDebugSnapshot()
      val trace =
          command.request.traceRequest().map { request ->
            checkNotNull(debugInstrumentation).readTrace(request)
          }.orElse(null)
      command.complete(
          DebugResult.success(gameboy.inspectDebugMemory(snapshot, command.request, trace)))
    } catch (_: UnsupportedOperationException) {
      command.fail(
          DebugErrorCode.UNSUPPORTED_ADDRESS_SPACE,
          "An inspection range has no side-effect-free debugger view",
      )
    } catch (_: IllegalArgumentException) {
      command.fail(
          DebugErrorCode.SIDE_EFFECTFUL_ADDRESS,
          "An inspection range contains a side-effectful or unavailable address",
      )
    }
  }

  private fun handleDebugButton(command: QueuedDebugCommand.SetButton) {
    val currentSession = checkNotNull(session)
    val button = Button.valueOf(command.button.name)
    if (currentSession.gameboy.legacyPressedButtons.contains(button) == command.pressed) {
      command.complete(DebugResult.success())
      return
    }
    debugCheckpointHistory.invalidateFuture(currentSession)
    if (command.pressed) {
      currentSession.eventBus.post(ButtonPressEvent(button))
    } else {
      currentSession.eventBus.post(ButtonReleaseEvent(button))
    }
    command.complete(DebugResult.success())
  }

  private fun handleDebugSetBreakpoint(command: QueuedDebugCommand.SetBreakpoint) {
    val instrumentation = checkNotNull(debugInstrumentation)
    try {
      command.complete(DebugResult.success(instrumentation.setBreakpoint(command.breakpoint)))
      syncDebugInstrumentation()
    } catch (_: UnsupportedOperationException) {
      command.fail(
          DebugErrorCode.UNSUPPORTED_BREAKPOINT,
          "Requested breakpoint kind is unavailable",
      )
    } catch (_: IllegalStateException) {
      command.fail(DebugErrorCode.BREAKPOINT_LIMIT, "Breakpoint capacity is exhausted")
    }
  }

  private fun handleDebugRemoveBreakpoint(command: QueuedDebugCommand.RemoveBreakpoint) {
    val removed = checkNotNull(debugInstrumentation).removeBreakpoint(command.breakpointId)
    if (!removed) {
      command.fail(
          DebugErrorCode.BREAKPOINT_NOT_FOUND,
          "No breakpoint has id ${command.breakpointId.value()}",
      )
      return
    }
    syncDebugInstrumentation()
    command.complete(DebugResult.success())
  }

  private fun handleDebugConfigureTrace(command: QueuedDebugCommand.ConfigureTrace) {
    val instrumentation = checkNotNull(debugInstrumentation)
    try {
      val configured = instrumentation.configureTrace(command.configuration)
      syncDebugInstrumentation()
      command.complete(DebugResult.success(configured))
    } catch (_: UnsupportedOperationException) {
      command.fail(
          DebugErrorCode.UNSUPPORTED_TRACE_CATEGORY,
          "Trace configuration contains an unsupported category",
      )
    } catch (_: IllegalArgumentException) {
      command.fail(DebugErrorCode.TRACE_LIMIT, "Trace capacity exceeds the negotiated limit")
    }
  }

  private fun syncDebugInstrumentation() {
    val gameboy = session?.gameboy ?: return
    val instrumentation = debugInstrumentation
    instrumentation?.alignOwnerFrame(debugFrame)
    gameboy.updateDebugInstrumentation(
        instrumentation?.takeIf { it.isActive },
        debugMasterTick,
    )
  }

  /** Breaks trace/match continuity around a hidden replay or in-place state replacement. */
  private fun detachAndClearDebugTimelineObservation(gameboy: Gameboy) {
    gameboy.updateDebugInstrumentation(null, debugMasterTick)
    debugInstrumentation?.let { instrumentation ->
      instrumentation.clearTimelineCorrelation()
      instrumentation.configureTrace(instrumentation.traceConfiguration())
    }
  }

  private fun resetDebugTimelineObservation(gameboy: Gameboy) {
    detachAndClearDebugTimelineObservation(gameboy)
    syncDebugInstrumentation()
  }

  private fun ensureDebugTracking() {
    if (debugTrackingEnabled) {
      return
    }
    checkNotNull(session).gameboy.enableDebugRetirementTracking()
    debugTrackingEnabled = true
  }

  private fun captureDebugSnapshot(): DebugSnapshot {
    ensureDebugTracking()
    val port = checkNotNull(debugPort)
    debugSequence = Math.addExact(debugSequence, 1L)
    return checkNotNull(session)
        .gameboy
        .captureDebugSnapshot(
            port.sessionGeneration(),
            debugSequence,
            debugMasterTick,
            debugFrame,
            debugFramePosition,
            isEffectivelyPaused(),
        )
  }

  /** Returns true when this completed tick was the breakpoint safe point. */
  private fun handleDebugBreakpointMatch(gameboy: Gameboy): Boolean {
    val match = debugInstrumentation?.pollBreakpointMatch() ?: return false
    setDebugPaused(true)
    debugBreakpointPauseActive = true
    val snapshot = captureDebugSnapshot()
    lastDebugBreakpointHit =
        DebugBreakpointHit(match.breakpoint(), match.matchMasterTick(), snapshot, true)

    when (val action = pendingDebugAction) {
      null -> Unit
      is PendingDebugAction.Pause -> {
        pendingDebugAction = null
        action.command.complete(DebugResult.success(snapshot))
      }
      is PendingDebugAction.InstructionStep -> {
        pendingDebugAction = null
        action.command.complete(
            DebugResult.success(
                DebugStepResult(
                    DebugStepKind.INSTRUCTION,
                    DebugStepStopReason.BREAKPOINT,
                    debugMasterTick - action.startMasterTick,
                    gameboy.debugRetirementSequence - action.startRetirement,
                    snapshot,
                )))
      }
      is PendingDebugAction.FrameStep -> {
        pendingDebugAction = null
        action.command.complete(
            DebugResult.success(
                DebugStepResult(
                    DebugStepKind.FRAME,
                    DebugStepStopReason.BREAKPOINT,
                    debugMasterTick - action.startMasterTick,
                    gameboy.debugRetirementSequence - action.startRetirement,
                    snapshot,
                )))
      }
    }
    return true
  }

  private fun finishPendingDebugAction(gameboy: Gameboy, frameTicks: Int) {
    when (val action = pendingDebugAction) {
      null -> return
      is PendingDebugAction.Pause -> {
        if (gameboy.debugRetirementSequence >= action.targetRetirement) {
          pendingDebugAction = null
          setDebugPaused(true)
          action.command.complete(DebugResult.success(captureDebugSnapshot()))
        } else if (debugMasterTick - action.startMasterTick >= frameTicks) {
          pendingDebugAction = null
          action.command.fail(
              DebugErrorCode.STEP_LIMIT,
              "Pause did not reach an instruction safe point within one frame",
          )
        }
      }
      is PendingDebugAction.InstructionStep -> {
        if (gameboy.debugRetirementSequence >= action.targetRetirement) {
          pendingDebugAction = null
          val snapshot = captureDebugSnapshot()
          val stopReason =
              if (snapshot.execution().cpuState() == DebugCpuState.LOCKED) {
                DebugStepStopReason.CPU_LOCKED
              } else {
                DebugStepStopReason.INSTRUCTION_RETIRED
              }
          action.command.complete(
              DebugResult.success(
                  DebugStepResult(
                      DebugStepKind.INSTRUCTION,
                      stopReason,
                      debugMasterTick - action.startMasterTick,
                      gameboy.debugRetirementSequence - action.startRetirement,
                      snapshot,
                  )))
        } else if (debugMasterTick - action.startMasterTick >= frameTicks) {
          pendingDebugAction = null
          action.command.fail(
              DebugErrorCode.STEP_LIMIT,
              "Instruction step exceeded one controller frame",
          )
        }
      }
      is PendingDebugAction.FrameStep -> {
        if (debugMasterTick >= action.targetMasterTick) {
          pendingDebugAction = null
          val snapshot = captureDebugSnapshot()
          action.command.complete(
              DebugResult.success(
                  DebugStepResult(
                      DebugStepKind.FRAME,
                      DebugStepStopReason.FRAME_BOUNDARY,
                      debugMasterTick - action.startMasterTick,
                      gameboy.debugRetirementSequence - action.startRetirement,
                      snapshot,
                  )))
        }
      }
    }
  }

  private fun DebugCpuState.isDebuggerIdle(): Boolean =
      this == DebugCpuState.HALTED ||
          this == DebugCpuState.STOPPED ||
          this == DebugCpuState.SPEED_SWITCH ||
          this == DebugCpuState.LOCKED

  /** Coalesces an arbitrary producer burst into one cancellation and the newest revision. */
  private fun drainMobileAdapterControlLane() {
    val controls = mobileAdapterControlLane.drain()
    when {
      controls.hasCancel && controls.hasRefresh && controls.cancelOrder < controls.refreshOrder -> {
        disconnectMobileAdapter(Controller.MobileAdapterDisconnectReason.USER_CANCELLED)
        if (refreshMobileAdapterConfiguration(controls.refreshRevision) ==
            MobileAdapterRefreshResult.BLOCKED) {
          // Cancellation preceded refresh, so retrying only the refresh preserves the requested
          // final ordering once the guest configuration has been retained by its durable owner.
          mobileAdapterControlLane.restoreDeferred(
              controls.copy(cancelOrder = MobileAdapterControlLane.Pending.EMPTY.cancelOrder))
        }
      }
      controls.hasCancel && controls.hasRefresh -> {
        val refreshResult = refreshMobileAdapterConfiguration(controls.refreshRevision)
        disconnectMobileAdapter(Controller.MobileAdapterDisconnectReason.USER_CANCELLED)
        if (refreshResult == MobileAdapterRefreshResult.BLOCKED) {
          // Refresh preceded cancellation. Requeue both in that order so a deferred refresh can
          // never make the later user cancellation appear to have been undone.
          mobileAdapterControlLane.restoreDeferred(controls)
        }
      }
      controls.hasCancel ->
          disconnectMobileAdapter(Controller.MobileAdapterDisconnectReason.USER_CANCELLED)
      controls.hasRefresh -> {
        if (refreshMobileAdapterConfiguration(controls.refreshRevision) ==
            MobileAdapterRefreshResult.BLOCKED) {
          mobileAdapterControlLane.restoreDeferred(controls)
        }
      }
    }
  }

  private fun createSession(
      config: Gameboy.GameboyConfiguration,
      prebuiltGameboy: Gameboy? = null,
      stateStore: StateStore? = null,
  ): Session {
    val sessionBus = StagedEventBus(eventBus.fork("main"))
    try {
      val serialEndpoint =
          createLinkDevice(serialPeripheralSelection, sessionBus, config.clockSpec)
      return Session(
          config,
          sessionBus,
          console,
          serialEndpoint.endpoint,
          prebuiltGameboy = prebuiltGameboy,
          serialEndpointDisconnect = serialEndpoint.disconnect,
          stateStore = stateStore,
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
      val externalIoAtCapture = mobileAdapterEndpointHasExternalIo()
      val captured = capturePortableState(currentSession)
      if (externalIoAtCapture) mobileAdapterExternalIoSaveRequests.add(event.requestId)
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
      mobileAdapterExternalIoSaveRequests.remove(event.requestId)
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

  private fun requestStateSlotLoadAvailability(
      event: StateSlotLoadAvailabilityRequestEvent,
  ) {
    val context = stateContext
    if (context == null || session == null || context.sessionId != event.expectedSessionId) {
      eventBus.post(
          StateSlotLoadAvailabilityEvent(
              event.requestId,
              event.expectedSessionId,
              event.ref,
              available = false,
          ))
      return
    }
    latestStateRequests[StateOperation.LOAD_AVAILABILITY] = event.requestId
    pendingSlotLoadAvailability =
        PendingSlotLoadAvailability(event.requestId, context.sessionId, event.ref)
    val compatibilityManager = snapshotManager
    stateWorker.probeLoadFirst(context, event.requestId, event.ref) {
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
    if (currentSession == null) {
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
                      "Choose a writable Saves directory, retry, or explicitly close without saving.",
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
          thumbnail = captureAutosaveThumbnail(),
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
    val autosaveRequired = currentSession != null
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
              "Choose a writable Saves directory, then retry opening the ROM.",
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
              captureAutosaveThumbnail(),
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
              "The current game remains active. Retry opening the ROM after resolving the problem.",
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
        thumbnail = pending.thumbnail,
    )
  }

  private fun finishStateWorkerRequest(event: StateWorkerCompletedEvent) {
    val context = stateContext
    if (context == null ||
        event.context.sessionId != context.sessionId) {
      if (event.purpose == StateWorkerPurpose.MANUAL &&
          event.operation == StateOperation.SAVE) {
        mobileAdapterExternalIoSaveRequests.remove(event.requestId)
      }
      return
    }

    if (event.operation == StateOperation.LOAD_AVAILABILITY) {
      finishStateSlotLoadAvailability(event)
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

  private fun finishStateSlotLoadAvailability(event: StateWorkerCompletedEvent) {
    if (!isLatest(StateOperation.LOAD_AVAILABILITY, event.requestId)) return
    val pending = pendingSlotLoadAvailability ?: return
    if (pending.requestId != event.requestId || pending.sessionId != event.context.sessionId) return
    pendingSlotLoadAvailability = null
    latestStateRequests.remove(StateOperation.LOAD_AVAILABILITY)
    val currentSession = session
    val context = stateContext
    val available =
        if (currentSession == null || context == null || context.sessionId != pending.sessionId) {
          false
        } else {
          when (val result = event.result) {
            is StateWorkerResult.Loaded ->
                managedStateApplicable(result.result, currentSession, context)
            is StateWorkerResult.CompatibilityLoaded ->
                compatibilityStateApplicable(result.snapshot, currentSession)
            else -> false
          }
        }
    eventBus.post(
        StateSlotLoadAvailabilityEvent(
            pending.requestId,
            pending.sessionId,
            pending.ref,
            available,
        ))
  }

  private fun finishStateSave(
      event: StateWorkerCompletedEvent,
      result: StateWorkerResult.Saved,
  ) {
    val context = checkNotNull(stateContext)
    when (event.purpose) {
      StateWorkerPurpose.MANUAL -> {
        val mobileExternalIo = mobileAdapterExternalIoSaveRequests.remove(event.requestId)
        if (mobileExternalIo) {
          // The state is already durable. Its non-restorable-I/O disclosure is a safety boundary,
          // not ordinary request presentation: a newer same-ref request may suppress this save's
          // success UI, but must not hide the boundary if that newer write subsequently fails.
          postMobileAdapterSaveBoundary()
        }
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
      StateWorkerPurpose.AUTOSAVE_STOP -> {
        val job = stopJob
        if (job == null || job.requestId != event.requestId) return
        job.awaitingAutosaveDecision = false
        beginStopPersistence(job)
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
      StateWorkerPurpose.AUTOSAVE_STOP -> {
        val job = stopJob
        if (job == null || job.requestId != event.requestId) return
        postStopAutosaveFailure(job, error)
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
        mobileAdapterExternalIoSaveRequests.remove(event.requestId)
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
    val currentSession = session
    val context = stateContext
    if (currentSession == null ||
        context == null ||
        !managedStateApplicable(located.second, currentSession, context)) {
      pendingResume = null
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

  private fun managedStateApplicable(
      read: StateReadResult,
      currentSession: Session,
      context: StateWorkerContext,
  ): Boolean =
      try {
        when (read.state.root) {
          is SessionStateRoot ->
              StateCodec.validateDecodedForApply(read.state, currentSession, context.identity)
          is MachineStateRoot ->
              StateCodec.validateDecodedForApply(
                  read.state,
                  currentSession.config,
                  currentSession.gameboy,
                  context.identity,
              )
          else -> return false
        }
        true
      } catch (_: Exception) {
        false
      }

  private fun compatibilityStateApplicable(
      snapshot: CompatibilitySnapshot,
      currentSession: Session,
  ): Boolean =
      try {
        val manager = snapshotManager ?: return false
        manager.validateSnapshotReadOnly(snapshot, currentSession)
        true
      } catch (_: Exception) {
        false
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
    val mobileExternalIo = hasMobileAdapterExternalIo()
    val mobileBackendOwnership = mobileAdapterBackendOwnershipVersion()
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
      relinquishDebugBreakpointPauseOwnership()
      // A portable state contains the cartridge clock pause bit. Pause ownership belongs to the
      // live desktop workflow, so loading must not let an old capture override the effective
      // pause selected for this session.
      currentSession.gameboy.setCartridgeClockPaused(isEffectivelyPaused())
      resetDebugTimelineObservation(currentSession.gameboy)
      rewindManager.clear()
      debugCheckpointHistory.clear(DebugHistoryTruncationReason.SESSION_BOUNDARY)
      if (mobileExternalIo || hasMobileAdapterDisconnectedExternalIoMarker()) {
        mobileAdapterStateLoadCompleted()
      }
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
      if (mobileExternalIo && mobileAdapterBackendOwnershipVersion() != mobileBackendOwnership) {
        mobileAdapterStateLoadCompleted()
      }
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
    if (loadJob != null || pendingRomSwitch != null || replacementJob != null || stopJob != null) {
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
    val mobileExternalIo = hasMobileAdapterExternalIo()
    val mobileBackendOwnership = mobileAdapterBackendOwnershipVersion()
    try {
      manager.applySnapshotReadOnly(snapshot, currentSession)
      relinquishDebugBreakpointPauseOwnership()
      // Match managed-load ownership: a saved cartridge clock pause bit must not override the
      // effective pause chosen for the live desktop session.
      currentSession.gameboy.setCartridgeClockPaused(isEffectivelyPaused())
      resetDebugTimelineObservation(currentSession.gameboy)
      rewindManager.clear()
      debugCheckpointHistory.clear(DebugHistoryTruncationReason.SESSION_BOUNDARY)
      if (mobileExternalIo || hasMobileAdapterDisconnectedExternalIoMarker()) {
        mobileAdapterStateLoadCompleted()
      }
      eventBus.post(
          StateOperationCompletedEvent(
              requestId,
              checkNotNull(stateContext).sessionId,
              StateOperation.LOAD,
              slot,
              message = "Legacy state loaded from Slot ${slot.index}.",
          ))
    } catch (failure: Throwable) {
      if (mobileExternalIo && mobileAdapterBackendOwnershipVersion() != mobileBackendOwnership) {
        mobileAdapterStateLoadCompleted()
      }
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

  /** A missing preview is non-authoritative, so it cannot prevent the state itself from saving. */
  private fun captureAutosaveThumbnail(): StateImage? =
      try {
        autosaveThumbnailProvider()
      } catch (failure: RuntimeException) {
        LOG.warn("Unable to capture an autosave thumbnail", failure)
        null
      }

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
    session?.gameboy?.setCartridgeClockPaused(isEffectivelyPaused())
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
    job.ready = outcome
    continueReplacementAfterGuestConfiguration(job)
  }

  private fun continueReplacementAfterGuestConfiguration(job: ReplacementJob) {
    val outcome = job.ready ?: return
    val drainResult =
        session?.let(::drainMobileAdapterGuestConfiguration)
            ?: MobileAdapterGuestConfigurationDrainResult.SAFE
    if (drainResult == MobileAdapterGuestConfigurationDrainResult.BLOCKED) {
      postMobileAdapterGuestConfigurationBarrierFailure(
          job.requestId,
          Controller.PersistenceBarrierOperation.ROM_REPLACEMENT,
          job.event.openRequestId,
      )
      return
    }

    job.capture.complete(outcome.persistence)
    job.ready = null
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
      if (job.requestId == requestId) {
        if (job.ready != null) {
          continueReplacementAfterGuestConfiguration(job)
        } else if (job.attempt == null) {
          val attempt = ReplacementTask(job.capture, job.prepared)
          job.attempt = attempt
          persistenceExecutor.execute(attempt)
        }
      }
      return
    }
    stopJob?.let { job ->
      if (job.requestId == requestId && job.attempt == null) {
        if (job.awaitingGuestConfiguration) {
          continueStopAfterGuestConfiguration(job)
        } else if (job.awaitingAutosaveDecision) {
          submitStopAutosave(job)
        } else {
          beginStopPersistence(job)
        }
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
    job.ready?.gameboy?.discardUnstarted()
    job.ready = null
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

    val guestConfigurationDrain = drainMobileAdapterGuestConfiguration(currentSession)
    setPaused(true)
    val capture = currentSession.gameboy.prepareCartridgeFlush()
    val job =
        StopJob(
            requestId = nextPersistenceRequestId++,
            pausedBeforeStop = pausedBeforeStop,
            capture = capture,
            attempt = null,
        )
    stopJob = job
    if (guestConfigurationDrain == MobileAdapterGuestConfigurationDrainResult.BLOCKED) {
      job.awaitingGuestConfiguration = true
      postMobileAdapterGuestConfigurationBarrierFailure(
          job.requestId,
          Controller.PersistenceBarrierOperation.STOP,
      )
      return
    }
    submitStopAutosave(job)
  }

  private fun continueStopAfterGuestConfiguration(job: StopJob) {
    val currentSession = session
    val drainResult =
        currentSession?.let(::drainMobileAdapterGuestConfiguration)
            ?: MobileAdapterGuestConfigurationDrainResult.SAFE
    if (drainResult == MobileAdapterGuestConfigurationDrainResult.BLOCKED) {
      job.awaitingGuestConfiguration = true
      postMobileAdapterGuestConfigurationBarrierFailure(
          job.requestId,
          Controller.PersistenceBarrierOperation.STOP,
      )
      return
    }

    job.awaitingGuestConfiguration = false
    val persistence = job.completedPersistence
    if (persistence != null) {
      job.capture.complete(persistence)
      job.completedPersistence = null
      stopJob = null
      stop(afterCartridgeFlush = true)
      isPaused = false
    } else {
      submitStopAutosave(job)
    }
  }

  /** Writes the terminal autosave before battery persistence can release the current machine. */
  private fun submitStopAutosave(job: StopJob) {
    val currentSession = session
    val context = stateContext
    if (currentSession == null || context == null) {
      postStopAutosaveFailure(
          job,
          StateUserError(
              "Game unload was cancelled because autosave is unavailable.",
              "The configured save workspace could not be initialized for the active game.",
              "Choose a writable Saves directory, then retry closing the game.",
          ),
      )
      return
    }
    if (job.state == null) {
      try {
        job.context = context
        job.state = capturePortableState(currentSession)
        job.playDurationNanos = currentPlayDurationNanos()
        job.thumbnail = captureAutosaveThumbnail()
      } catch (failure: Throwable) {
        postStopAutosaveFailure(
            job,
            stateError(
                "Game unload was cancelled because autosave could not be captured.",
                failure,
                "The game is still active. Retry closing it after resolving the problem.",
            ),
        )
        return
      }
    }
    try {
      job.awaitingAutosaveDecision = false
      stateWorker.save(
          checkNotNull(job.context),
          job.requestId,
          StateWorkerPurpose.AUTOSAVE_STOP,
          StateRef.Autosave,
          checkNotNull(job.state),
          label = "Autosave",
          playDurationNanos = job.playDurationNanos,
          thumbnail = job.thumbnail,
      )
    } catch (failure: Throwable) {
      postStopAutosaveFailure(
          job,
          stateError(
              "Game unload was cancelled because autosave could not be scheduled.",
              failure,
              "The game is still active. Retry closing it after resolving the problem.",
          ),
      )
    }
  }

  private fun postStopAutosaveFailure(job: StopJob, error: StateUserError) {
    job.awaitingAutosaveDecision = true
    postPersistenceFailure(
        job.requestId,
        Controller.PersistenceBarrierOperation.STOP,
        "autosave state",
        "${error.summary} ${error.suggestedAction}",
        null,
    )
  }

  private fun beginStopPersistence(job: StopJob) {
    if (job.attempt != null) return
    val attempt = PersistenceTask(job.capture)
    job.attempt = attempt
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

    result as BatteryPersistenceResult.Success
    job.completedPersistence = result
    continueStopAfterGuestConfiguration(job)
  }

  private fun discardStop(restorePause: Boolean) {
    val job = stopJob ?: return
    stopJob = null
    job.attempt?.cancel(true)
    if (restorePause) {
      setPaused(job.pausedBeforeStop)
    }
  }

  private fun requestBatteryFlush(event: Controller.FlushBatteryEvent) {
    if (batteryFlushJob != null) {
      // A host owns request correlation, so it can retry after the current completion instead of
      // racing a second writer against the same cartridge capture.
      eventBus.post(Controller.BatteryFlushCompletedEvent(event.requestId, 0, false))
      return
    }
    val capture = session?.gameboy?.prepareCartridgeFlush() ?: BatteryFlush.none()
    val attempt = PersistenceTask(capture)
    batteryFlushJob = BatteryFlushJob(event.requestId, capture, attempt)
    persistenceExecutor.execute(attempt)
  }

  private fun finishBatteryFlush() {
    val job = batteryFlushJob ?: return
    if (!job.attempt.isDone) {
      return
    }
    batteryFlushJob = null
    val result =
        try {
          job.attempt.get()
        } catch (_: CancellationException) {
          return
        } catch (failure: Exception) {
          unexpectedPersistenceFailure(failure)
        }
    job.capture.complete(result)
    when (result) {
      is BatteryPersistenceResult.Success ->
          eventBus.post(Controller.BatteryFlushCompletedEvent(job.requestId, result.filesWritten(), true))
      is BatteryPersistenceResult.Failure ->
          eventBus.post(Controller.BatteryFlushCompletedEvent(job.requestId, 0, false))
    }
  }

  private fun discardBatteryFlush() {
    val job = batteryFlushJob ?: return
    batteryFlushJob = null
    job.attempt.cancel(true)
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
    var preparedRewindSeed: RewindManager.PreparedSessionSeed? = null
    try {
      // Finish constructing and initializing the candidate before releasing the current session.
      // A core-startup failure must leave the old game available for resume/cancel semantics.
      nextSession = createSession(job.prepared.config, nextGameboy, job.prepared.stateStore)
      nextGameboy = null
      nextSnapshotManager =
          if (job.prepared.config.rom.origin.persistencePath(".sn0").isPresent) {
            snapshotManagerFactory.create(job.prepared.config)
          } else {
            null
          }
      // Keep snapshot preparation inside the candidate transaction: a capture failure must
      // discard this still-staged session and leave the live session/history untouched.
      preparedRewindSeed = rewindManager.prepareSessionSeed(checkNotNull(nextSession))
    } catch (e: Exception) {
      preparedRewindSeed?.discard()
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
      reportLoadFailure(job.event, e)
      return
    }

    setPaused(true)
    if (job.clearPatches) {
      patches.clear()
    }
    rewindManager.beginSession(checkNotNull(nextSession), preparedRewindSeed)
    preparedRewindSeed = null
    debugCheckpointHistory.clear(DebugHistoryTruncationReason.SESSION_BOUNDARY)

    val previousSession = session
    val committedSession = checkNotNull(nextSession)
    val pauseNewSession = pauseStateBeforeLoading == true

    // This assignment is the ownership commit. From here on the old session is never resumed:
    // its bus may need deferred cleanup, but it cannot invalidate the fully staged candidate.
    session = committedSession
    commitMobileAdapterAttachment(committedSession)
    currentRomHashes = job.prepared.romHashes
    snapshotManager = nextSnapshotManager
    nextSession = null
    nextSnapshotManager = null
    pauseStateBeforeLoading = null

    revokeDebugPort(previousSession, replaced = true)

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
    playbackSessionGeneration = null

    try {
      committedSession.activate()
      start(job.event.openRequestId, job.event.allowAutosaveResume)
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
    val wasEffectivelyPaused = isEffectivelyPaused()
    isPaused = paused
    updateCartridgePause(wasEffectivelyPaused)
    publishPlaybackState()
  }

  private fun setDebugPaused(paused: Boolean) {
    if (!paused) {
      relinquishDebugBreakpointPauseOwnership()
    }
    if (debugPaused == paused) {
      return
    }
    val wasEffectivelyPaused = isEffectivelyPaused()
    debugPaused = paused
    updateCartridgePause(wasEffectivelyPaused)
    publishPlaybackState()
  }

  /**
   * Releases pause ownership created by the main playback controls and/or debugger controls as
   * one effective playback transition. Loading and autosave-resume workflows retain their own
   * pause ownership and must be released by their lifecycle handlers.
   */
  private fun releaseInteractivePauseOwners(
      releaseApplicationPause: Boolean,
      releaseDebuggerPause: Boolean,
  ) {
    val applicationChanged = releaseApplicationPause && isPaused
    val debuggerChanged = releaseDebuggerPause && debugPaused
    if (!applicationChanged && !debuggerChanged) return
    val wasEffectivelyPaused = isEffectivelyPaused()
    if (applicationChanged) isPaused = false
    if (debuggerChanged) {
      debugPaused = false
      relinquishDebugBreakpointPauseOwnership()
    }
    updateCartridgePause(wasEffectivelyPaused)
    publishPlaybackState()
  }

  private fun publishPlaybackState() {
    val currentSession = session ?: return
    val generation = playbackSessionGeneration ?: return
    postSessionEventSafely(
        currentSession,
        Controller.SessionPlaybackStateEvent(generation, isEffectivelyPaused()),
    )
  }

  private fun relinquishDebugBreakpointPauseOwnership() {
    debugBreakpointPauseActive = false
    lastDebugBreakpointHit = lastDebugBreakpointHit?.withActivePause(false)
  }

  private fun updateCartridgePause(wasEffectivelyPaused: Boolean) {
    val currentSession = session ?: return
    val gameboy = currentSession.gameboy
    val effectivelyPaused = isEffectivelyPaused()
    val resumedPausedRtc =
        wasEffectivelyPaused &&
            !effectivelyPaused &&
            gameboy.hasPausedCartridgeRtc() &&
            debugCheckpointHistory.enabled
    if (resumedPausedRtc) {
      // The live MBC3 applies host elapsed time on resume. That duration is deliberately not in
      // the deterministic input transcript, so no checkpoint spanning this boundary is replayable.
      debugCheckpointHistory.clear(DebugHistoryTruncationReason.NONDETERMINISTIC_IO)
    }
    gameboy.setCartridgeClockPaused(effectivelyPaused)
    if (resumedPausedRtc && debugFramePosition == 0 && supportsDebugHistory(currentSession)) {
      try {
        debugCheckpointHistory.recordFrame(currentSession)
      } catch (failure: Exception) {
        LOG.warn("Unable to restart reverse history after RTC resume", failure)
        debugCheckpointHistory.disable(DebugHistoryTruncationReason.SESSION_BOUNDARY)
      }
    }
  }

  private fun isEffectivelyPaused(): Boolean = isPaused || debugPaused

  private fun restorePauseStateAfterLoading() {
    val restorePaused = pauseStateBeforeLoading ?: return
    pauseStateBeforeLoading = null
    setPaused(restorePaused)
  }

  private fun createLinkDevice(
      selection: Controller.SerialPeripheralSelection,
      sessionBus: EventBus,
      clockSpec: ClockSpec,
  ): PreparedSerialEndpoint =
      when (selection) {
        Controller.SerialPeripheralSelection.NONE ->
            PreparedSerialEndpoint(SerialEndpoint.NULL_ENDPOINT)
        Controller.SerialPeripheralSelection.PRINTER ->
            PreparedSerialEndpoint(
                GameboyPrinterSerialEndpoint { argb, width, height, top, bottom, exposure ->
                  sessionBus.post(
                      Controller.PrinterPrintEvent(argb, width, height, top, bottom, exposure))
                })
        Controller.SerialPeripheralSelection.BARCODE_BOY ->
            PreparedSerialEndpoint(BarcodeBoySerialEndpoint())
        Controller.SerialPeripheralSelection.GPS_RECEIVER ->
            PreparedSerialEndpoint(GpsReceiverSerialEndpoint(clockSpec))
        Controller.SerialPeripheralSelection.MOBILE_ADAPTER_GB ->
            createMobileAdapterEndpoint(clockSpec)
        Controller.SerialPeripheralSelection.PEER_TO_PEER ->
            PreparedSerialEndpoint(Peer2PeerSerialEndpoint())
      }

  private fun createMobileAdapterEndpoint(clockSpec: ClockSpec): PreparedSerialEndpoint {
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
    val lifecycle =
        MobileAdapterEndpointLifecycle(
            NEXT_MOBILE_ADAPTER_ATTACHMENT_ID.getAndIncrement(),
            configuration.policyRevision,
            configuration.networkBackend,
            configuration.runtimeNetworkConsent,
            configuration.runtimePrivateLocalDevelopment,
        )
    return try {
      val endpoint =
          configuration.networkBackend?.let { backend ->
            MobileAdapterSerialEndpoint(
                clockSpec,
                configuration.deviceId,
                configuration.copyBytes(),
                backend.port,
            )
          }
              ?: MobileAdapterSerialEndpoint(
                  clockSpec,
                  configuration.deviceId,
                  configuration.copyBytes(),
              )
      PreparedSerialEndpoint(endpoint, lifecycle)
    } catch (_: IllegalArgumentException) {
      lifecycle.invoke()
      throw Controller.SerialPeripheralPreparationException(
          Controller.SerialPeripheralError.CONFIGURATION_INVALID)
    } catch (failure: RuntimeException) {
      lifecycle.invoke()
      throw failure
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

    if (drainMobileAdapterGuestConfiguration(currentSession) ==
        MobileAdapterGuestConfigurationDrainResult.BLOCKED) {
      postSerialPeripheralStatus(
          selection,
          Controller.SerialPeripheralStatus.UNAVAILABLE,
          Controller.SerialPeripheralError.STORAGE_FAILED,
      )
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
      currentSession.setSerialEndpoint(endpoint.endpoint, endpoint.disconnect)
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
    commitMobileAdapterAttachment(currentSession)
    val previousSelection = serialPeripheralSelection
    // The session handoff is the ownership commit. Update the controller's internal selection
    // before invoking any presentation subscriber, because EventBus dispatch is synchronous and
    // an unrelated UI/plugin callback must not split endpoint ownership from controller state.
    serialPeripheralSelection = selection
    // Session rewind entries are pinned to one endpoint identity. Only a committed handoff
    // invalidates them; preparation and handoff failures leave the old endpoint/history intact.
    rewindManager.clear()
    debugCheckpointHistory.clear(DebugHistoryTruncationReason.TOPOLOGY_CHANGED)
    debugInstructionReplayer.close()
    postSerialPeripheralStatus(
        previousSelection,
        Controller.SerialPeripheralStatus.DETACHED,
    )
    postSerialPeripheralEventSafely(
        Controller.SerialPeripheralSelectionChangedEvent(selection))
    postSerialPeripheralStatus(selection, Controller.SerialPeripheralStatus.ATTACHED)
    postInitialMobileAdapterNetworkStatus()
  }

  /** Re-prepares the same selected endpoint so a policy change is an atomic ownership handoff. */
  private fun refreshMobileAdapterConfiguration(
      requestedRevision: Long
  ): MobileAdapterRefreshResult {
    if (serialPeripheralSelection != Controller.SerialPeripheralSelection.MOBILE_ADAPTER_GB) {
      return MobileAdapterRefreshResult.NOT_APPLICABLE
    }
    val currentSession = session ?: return MobileAdapterRefreshResult.NOT_APPLICABLE
    val currentLifecycle = mobileAdapterLifecycle(currentSession)
    if (currentLifecycle != null && requestedRevision <= currentLifecycle.policyRevision) {
      return MobileAdapterRefreshResult.COMPLETED
    }

    if (drainMobileAdapterGuestConfiguration(currentSession) ==
        MobileAdapterGuestConfigurationDrainResult.BLOCKED) {
      postSerialPeripheralStatus(
          Controller.SerialPeripheralSelection.MOBILE_ADAPTER_GB,
          Controller.SerialPeripheralStatus.UNAVAILABLE,
          Controller.SerialPeripheralError.STORAGE_FAILED,
      )
      return MobileAdapterRefreshResult.BLOCKED
    }

    // A newer policy request is an authority boundary, not merely a presentation refresh. Revoke
    // the live host capability before provider/core preparation so every failure is fail-closed.
    if (currentLifecycle?.backend != null) {
      currentLifecycle.backend.revokeAuthorization()
      if (currentLifecycle.disconnectReason == null) {
        disconnectMobileAdapter(Controller.MobileAdapterDisconnectReason.POLICY_CHANGED)
      }
    }

    val replacement =
        try {
          createMobileAdapterEndpoint(currentSession.config.clockSpec)
        } catch (failure: Controller.SerialPeripheralPreparationException) {
          postSerialPeripheralStatus(
              Controller.SerialPeripheralSelection.MOBILE_ADAPTER_GB,
              Controller.SerialPeripheralStatus.UNAVAILABLE,
              failure.error,
          )
          return MobileAdapterRefreshResult.COMPLETED
        }
    val replacementLifecycle = replacement.disconnect as? MobileAdapterEndpointLifecycle
    if (replacementLifecycle == null || replacementLifecycle.policyRevision < requestedRevision) {
      replacement.disconnect()
      postSerialPeripheralStatus(
          Controller.SerialPeripheralSelection.MOBILE_ADAPTER_GB,
          Controller.SerialPeripheralStatus.UNAVAILABLE,
          Controller.SerialPeripheralError.CONFIGURATION_INVALID,
      )
      return MobileAdapterRefreshResult.COMPLETED
    }
    try {
      currentSession.setSerialEndpoint(replacement.endpoint, replacement.disconnect)
    } catch (_: RuntimeException) {
      postSerialPeripheralStatus(
          Controller.SerialPeripheralSelection.MOBILE_ADAPTER_GB,
          Controller.SerialPeripheralStatus.UNAVAILABLE,
          Controller.SerialPeripheralError.ENDPOINT_UNAVAILABLE,
      )
      return MobileAdapterRefreshResult.COMPLETED
    }
    commitMobileAdapterAttachment(currentSession)
    rewindManager.clear()
    debugCheckpointHistory.clear(DebugHistoryTruncationReason.TOPOLOGY_CHANGED)
    debugInstructionReplayer.close()
    postSerialPeripheralStatus(
        Controller.SerialPeripheralSelection.MOBILE_ADAPTER_GB,
        Controller.SerialPeripheralStatus.ATTACHED,
    )
    postInitialMobileAdapterNetworkStatus()
    return MobileAdapterRefreshResult.COMPLETED
  }

  private fun pollMobileAdapterBackend() {
    val currentSession = session ?: return
    val endpoint = currentSession.serialEndpoint as? MobileAdapterSerialEndpoint ?: return
    val lifecycle = mobileAdapterLifecycle(currentSession) ?: return
    endpoint.pollBackendCompletion()
    val backend = lifecycle.backend ?: return
    while (true) {
      val status = backend.pollStatus() ?: break
      postMobileAdapterBackendStatus(lifecycle, status)
    }
  }

  /** Publishes the newest complete guest image at the frame owner's deterministic safe point. */
  private fun drainMobileAdapterGuestConfiguration(
      currentSession: Session
  ): MobileAdapterGuestConfigurationDrainResult {
    val endpoint =
        currentSession.serialEndpoint as? MobileAdapterSerialEndpoint
            ?: return MobileAdapterGuestConfigurationDrainResult.SAFE
    val lifecycle =
        mobileAdapterLifecycle(currentSession)
            ?: return MobileAdapterGuestConfigurationDrainResult.SAFE
    val mutation =
        endpoint.latestGuestConfigurationMutation()
            ?: return MobileAdapterGuestConfigurationDrainResult.SAFE

    if (mutation.revision() > lifecycle.guestConfigurationHistoryObservedRevision) {
      lifecycle.guestConfigurationHistoryObservedRevision = mutation.revision()
      // History invalidation follows the emulated side effect, independently of whether private
      // desktop persistence is currently able to retain the corresponding full image.
      rewindManager.clear()
      debugCheckpointHistory.clear(DebugHistoryTruncationReason.CONFIGURATION_CHANGED)
    }
    if (mutation.revision() <= lifecycle.guestConfigurationPersistenceAcceptedRevision) {
      return MobileAdapterGuestConfigurationDrainResult.SAFE
    }

    val offer =
        try {
          mobileAdapterGuestConfigurationSink.offer(
              MobileAdapterGuestConfigurationWrite(
                  lifecycle.attachmentId,
                  mutation.revision(),
                  mutation.configuration(),
              ))
        } catch (_: RuntimeException) {
          // A privileged sink is required to reject through its typed result. Contain an
          // implementation failure without logging private configuration or attachment details.
          LOG.warn("Mobile Adapter guest configuration sink failed")
          MobileAdapterGuestConfigurationOfferResult.CLOSED
        }
    if (offer != MobileAdapterGuestConfigurationOfferResult.ACCEPTED) {
      return MobileAdapterGuestConfigurationDrainResult.BLOCKED
    }
    lifecycle.guestConfigurationPersistenceAcceptedRevision = mutation.revision()
    return MobileAdapterGuestConfigurationDrainResult.ACCEPTED
  }

  private fun postMobileAdapterGuestConfigurationBarrierFailure(
      requestId: Long,
      operation: Controller.PersistenceBarrierOperation,
      openRequestId: Long? = null,
  ) {
    postPersistenceFailure(
        requestId,
        operation,
        MOBILE_ADAPTER_CONFIGURATION_FILE_LABEL,
        MOBILE_ADAPTER_CONFIGURATION_PENDING_MESSAGE,
        openRequestId,
    )
  }

  /** Only stable typed metadata crosses the application event tree; the image remains private. */
  private fun pollMobileAdapterGuestConfigurationPersistence() {
    val status =
        try {
          mobileAdapterGuestConfigurationSink.pollStatus()
        } catch (_: RuntimeException) {
          LOG.warn("Mobile Adapter guest configuration status provider failed")
          null
        } ?: return
    val phase =
        when (status.phase) {
          MobileAdapterGuestConfigurationPersistencePhase.PENDING ->
              Controller.MobileAdapterConfigurationPersistencePhase.PENDING
          MobileAdapterGuestConfigurationPersistencePhase.SAVED ->
              Controller.MobileAdapterConfigurationPersistencePhase.SAVED
          MobileAdapterGuestConfigurationPersistencePhase.SUPERSEDED ->
              Controller.MobileAdapterConfigurationPersistencePhase.SUPERSEDED
          MobileAdapterGuestConfigurationPersistencePhase.FAILED ->
              Controller.MobileAdapterConfigurationPersistencePhase.FAILED
        }
    postSerialPeripheralEventSafely(
        Controller.MobileAdapterConfigurationPersistenceStatusEvent(
            status.sequence,
            status.attachmentId,
            status.mutationRevision,
            phase,
            status.error,
        ))
  }

  /** Fences late work only after endpoint ownership has actually committed. */
  private fun commitMobileAdapterAttachment(currentSession: Session) {
    val lifecycle = mobileAdapterLifecycle(currentSession) ?: return
    try {
      mobileAdapterGuestConfigurationSink.attachmentCommitted(lifecycle.attachmentId)
    } catch (_: RuntimeException) {
      // Endpoint ownership is already committed and cannot be rolled back by a persistence seam.
      LOG.warn("Mobile Adapter guest configuration attachment fence failed")
    }
  }

  private fun postInitialMobileAdapterNetworkStatus() {
    val lifecycle = session?.let(::mobileAdapterLifecycle) ?: return
    val event =
        when {
          lifecycle.backend == null ->
              mobileAdapterStatus(lifecycle, Controller.MobileAdapterNetworkPhase.OFFLINE)
          !lifecycle.runtimeNetworkConsent ->
              mobileAdapterStatus(
                  lifecycle,
                  Controller.MobileAdapterNetworkPhase.FAILED,
                  error = Controller.MobileAdapterNetworkError.CONSENT_REQUIRED,
              )
          else -> mobileAdapterStatus(lifecycle, Controller.MobileAdapterNetworkPhase.READY)
        }
    postSerialPeripheralEventSafely(event)
  }

  private fun postMobileAdapterBackendStatus(
      lifecycle: MobileAdapterEndpointLifecycle,
      status: MobileAdapterNetworkStatus,
  ) {
    if (status.ownershipVersion < lifecycle.backendOwnershipVersion) return
    lifecycle.backendOwnershipVersion = status.ownershipVersion
    // The controller already published the user-visible cancelling/disconnected pair at the
    // cancellation boundary. Do not replay the backend's queued CANCELLING snapshot over that
    // terminal presentation; its following IDLE snapshot will reaffirm DISCONNECTED.
    if (lifecycle.disconnectReason != null &&
        status.phase == BackendNetworkPhase.CANCELLING) return
    val error = mapMobileAdapterNetworkError(status.error)
    val event =
        when {
          status.phase == BackendNetworkPhase.CANCELLING ->
              mobileAdapterStatus(
                  lifecycle,
                  Controller.MobileAdapterNetworkPhase.CANCELLING,
                  activeConnections = status.activeConnections,
              )
          status.error == BackendNetworkError.REMOTE_CLOSED -> {
            mobileAdapterStatus(
                lifecycle,
                Controller.MobileAdapterNetworkPhase.FAILED,
                slot = status.slot,
                activeConnections = status.activeConnections,
                error = Controller.MobileAdapterNetworkError.REMOTE_CLOSED,
            )
          }
          error != null ->
              mobileAdapterStatus(
                  lifecycle,
                  Controller.MobileAdapterNetworkPhase.FAILED,
                  slot = status.slot,
                  activeConnections = status.activeConnections,
                  error = error,
              )
          status.phase == BackendNetworkPhase.IDLE && lifecycle.disconnectReason != null ->
              mobileAdapterStatus(
                  lifecycle,
                  Controller.MobileAdapterNetworkPhase.DISCONNECTED,
                  activeConnections = status.activeConnections,
                  disconnectReason = checkNotNull(lifecycle.disconnectReason),
              )
          status.phase == BackendNetworkPhase.IDLE && !lifecycle.runtimeNetworkConsent ->
              mobileAdapterStatus(
                  lifecycle,
                  Controller.MobileAdapterNetworkPhase.FAILED,
                  error = Controller.MobileAdapterNetworkError.CONSENT_REQUIRED,
              )
          else -> {
            if (status.phase != BackendNetworkPhase.IDLE) lifecycle.disconnectReason = null
            mobileAdapterStatus(
                lifecycle,
                when (status.phase) {
                  BackendNetworkPhase.IDLE -> Controller.MobileAdapterNetworkPhase.READY
                  BackendNetworkPhase.RESOLVING -> Controller.MobileAdapterNetworkPhase.RESOLVING
                  BackendNetworkPhase.CONNECTING -> Controller.MobileAdapterNetworkPhase.CONNECTING
                  BackendNetworkPhase.CONNECTED -> Controller.MobileAdapterNetworkPhase.CONNECTED
                  BackendNetworkPhase.TRANSFERRING -> Controller.MobileAdapterNetworkPhase.TRANSFERRING
                  BackendNetworkPhase.CANCELLING -> Controller.MobileAdapterNetworkPhase.CANCELLING
                  BackendNetworkPhase.CLOSED -> Controller.MobileAdapterNetworkPhase.DISCONNECTED
                },
                slot = status.slot,
                activeConnections = status.activeConnections,
                disconnectReason =
                    Controller.MobileAdapterDisconnectReason.DETACHED.takeIf {
                      status.phase == BackendNetworkPhase.CLOSED
                    },
            )
          }
        }
    postSerialPeripheralEventSafely(event)
  }

  private fun mapMobileAdapterNetworkError(
      error: BackendNetworkError
  ): Controller.MobileAdapterNetworkError? =
      when (error) {
        BackendNetworkError.NONE -> null
        BackendNetworkError.NETWORK_CONSENT_REQUIRED ->
            Controller.MobileAdapterNetworkError.CONSENT_REQUIRED
        BackendNetworkError.PRIVATE_LOCAL_CONSENT_REQUIRED ->
            Controller.MobileAdapterNetworkError.PRIVATE_LOCAL_GATE_REQUIRED
        BackendNetworkError.DESTINATION_DENIED ->
            Controller.MobileAdapterNetworkError.DESTINATION_DENIED
        BackendNetworkError.INVALID_REQUEST ->
            Controller.MobileAdapterNetworkError.INVALID_REQUEST
        BackendNetworkError.TRANSFER_LIMIT ->
            Controller.MobileAdapterNetworkError.TRANSFER_LIMIT
        BackendNetworkError.DNS_TIMEOUT, BackendNetworkError.TIMEOUT ->
            Controller.MobileAdapterNetworkError.TIMEOUT
        BackendNetworkError.DNS_MALFORMED -> Controller.MobileAdapterNetworkError.DNS_INVALID
        BackendNetworkError.DNS_LOOKUP_FAILED -> Controller.MobileAdapterNetworkError.DNS_FAILED
        BackendNetworkError.DNS_UNREACHABLE -> Controller.MobileAdapterNetworkError.DNS_FAILED
        BackendNetworkError.CONNECTION_LIMIT ->
            Controller.MobileAdapterNetworkError.CONNECTION_LIMIT
        BackendNetworkError.QUEUE_FULL -> Controller.MobileAdapterNetworkError.QUEUE_EXHAUSTED
        BackendNetworkError.INVALID_CONNECTION ->
            Controller.MobileAdapterNetworkError.INVALID_CONNECTION
        BackendNetworkError.CONNECTION_REFUSED ->
            Controller.MobileAdapterNetworkError.CONNECTION_REFUSED
        BackendNetworkError.UNREACHABLE ->
            Controller.MobileAdapterNetworkError.DESTINATION_UNREACHABLE
        BackendNetworkError.REMOTE_CLOSED -> Controller.MobileAdapterNetworkError.REMOTE_CLOSED
        BackendNetworkError.CANCELLED -> Controller.MobileAdapterNetworkError.CANCELLED
        BackendNetworkError.IO_FAILURE -> Controller.MobileAdapterNetworkError.IO_FAILED
      }

  private fun mobileAdapterStatus(
      lifecycle: MobileAdapterEndpointLifecycle,
      phase: Controller.MobileAdapterNetworkPhase,
      slot: Int? = null,
      activeConnections: Int = 0,
      error: Controller.MobileAdapterNetworkError? = null,
      disconnectReason: Controller.MobileAdapterDisconnectReason? = null,
  ) =
      Controller.MobileAdapterNetworkStatusEvent(
          attachmentId = lifecycle.attachmentId,
          policyRevision = lifecycle.policyRevision,
          phase = phase,
          slot = slot,
          activeConnections = activeConnections,
          error = error,
          disconnectReason = disconnectReason,
      )

  private fun disconnectMobileAdapter(reason: Controller.MobileAdapterDisconnectReason) {
    val currentSession = session ?: return
    val endpoint = currentSession.serialEndpoint as? MobileAdapterSerialEndpoint ?: return
    val lifecycle = mobileAdapterLifecycle(currentSession) ?: return
    lifecycle.disconnectReason = reason
    postSerialPeripheralEventSafely(
        mobileAdapterStatus(lifecycle, Controller.MobileAdapterNetworkPhase.CANCELLING))
    endpoint.disconnect()
    lifecycle.backendOwnershipVersion =
        lifecycle.backend?.ownershipVersion() ?: lifecycle.backendOwnershipVersion
    postSerialPeripheralEventSafely(
        mobileAdapterStatus(
            lifecycle,
            Controller.MobileAdapterNetworkPhase.DISCONNECTED,
            disconnectReason = reason,
        ))
  }

  private fun hasMobileAdapterExternalIo(): Boolean {
    val currentSession = session ?: return false
    val endpoint = currentSession.serialEndpoint as? MobileAdapterSerialEndpoint ?: return false
    val lifecycle = mobileAdapterLifecycle(currentSession)
    return endpoint.hasExternalIo() || lifecycle?.backend?.hasExternalWork() == true
  }

  private fun consumeMobileAdapterExternalIoActivity(): Boolean =
      (session?.serialEndpoint as? MobileAdapterSerialEndpoint)?.consumeExternalIoActivity() == true

  /** Exact predicate serialized by MobileAdapterSerialEndpoint capture normalization. */
  private fun mobileAdapterEndpointHasExternalIo(): Boolean =
      (session?.serialEndpoint as? MobileAdapterSerialEndpoint)?.hasExternalIo() == true

  private fun mobileAdapterBackendOwnershipVersion(): Long? =
      session?.let(::mobileAdapterLifecycle)?.backend?.ownershipVersion()

  private fun hasMobileAdapterDisconnectedExternalIoMarker(): Boolean =
      (session?.serialEndpoint as? MobileAdapterSerialEndpoint)?.snapshot()?.outcome() ==
          MobileAdapterEngine.Outcome.EXTERNAL_IO_DISCONNECTED

  private fun mobileAdapterLifecycle(session: Session): MobileAdapterEndpointLifecycle? =
      session.serialEndpointDisconnectHandle() as? MobileAdapterEndpointLifecycle

  private fun postMobileAdapterStateBoundary(boundary: Controller.MobileAdapterStateBoundary) {
    postSerialPeripheralEventSafely(
        Controller.MobileAdapterStateBoundaryEvent(
            boundary,
            Controller.MobileAdapterStateBoundaryImpact.DISCONNECTED_NOT_RESTORED,
        ))
  }

  private fun postMobileAdapterSaveBoundary() {
    postSerialPeripheralEventSafely(
        Controller.MobileAdapterStateBoundaryEvent(
            Controller.MobileAdapterStateBoundary.SAVE,
            Controller.MobileAdapterStateBoundaryImpact.SAVED_WITH_NON_RESTORABLE_IO,
        ))
  }

  private fun mobileAdapterStateLoadCompleted() {
    val currentSession = session ?: return
    val lifecycle = mobileAdapterLifecycle(currentSession) ?: return
    lifecycle.disconnectReason = Controller.MobileAdapterDisconnectReason.STATE_LOAD
    lifecycle.backendOwnershipVersion =
        lifecycle.backend?.ownershipVersion() ?: lifecycle.backendOwnershipVersion
    // Applying the portable state already rotates the backend generation. Do not call endpoint
    // disconnect here: that would overwrite the restored deterministic
    // EXTERNAL_IO_DISCONNECTED outcome with the generic detach/cancel outcome.
    postMobileAdapterStateBoundary(Controller.MobileAdapterStateBoundary.LOAD)
    postSerialPeripheralEventSafely(
        mobileAdapterStatus(
            lifecycle,
            Controller.MobileAdapterNetworkPhase.DISCONNECTED,
            disconnectReason = Controller.MobileAdapterDisconnectReason.STATE_LOAD,
        ))
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

  private fun start(
      openRequestId: Long? = null,
      allowAutosaveResume: Boolean = true,
  ) {
    val session = session ?: return

    // Benchmark sessions materialize into an explicit anchor-ready pause.  The host arms one
    // generation after capturing its compositor baseline; normal sessions retain auto-resume.
    isPaused = properties.overrides.benchmarkPolicyEnabled
    benchmarkArmed = false
    benchmarkCoreFrozen = false
    benchmarkGeneration = 0L
    debugPaused = false
    playbackSessionGeneration = SessionPresentationGeneration.next()
    pauseStateBeforeResume = null
    pendingSlotLoadAvailability = null
    sessionStartedNanos = System.nanoTime()
    stateSessionId = nextStateSessionId()
    installDebugPort(session)
    closeAutosaveCapture = null
    closeAutosaveThumbnail = null
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
          StateWorkerContext(
              stateSessionId,
              stateWorkspace(session, identity, properties.applicationSettings.saves),
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
    val effectiveSpeedMode = session.gameboy.getSpeedMode()
    val effectiveGpu = session.gameboy.getGpu()
    postSessionEventSafely(
        session,
        Controller.HardwareProfileEvent(
            session.config.hardwareProfile,
            effectiveGbc = effectiveGpu.isGbc(),
            effectiveDmgCompat = effectiveGpu.isDmgCompatMode(),
            effectiveSpeedMode = effectiveSpeedMode.getSpeedMode(),
        ))
    postSessionEventSafely(session, Controller.SessionPauseSupportEvent(true))
    postSessionEventSafely(
        session,
        Controller.SessionDebugPortEvent(
            checkNotNull(debugPort).sessionGeneration(),
            debugPort,
        ),
    )
    postSessionEventSafely(
        session,
        Controller.SessionSnapshotSupportEvent(if (snapshotManager == null) null else this),
    )
    val presentationTitle = session.config.rom.title.trim().ifBlank {
      session.config.rom.origin.displayName().trim().ifBlank { "UNTITLED ROM" }
    }
    postSessionEventSafely(
        session,
        Controller.EmulationStartedEvent(
            presentationTitle,
            session.config.rom.origin,
            openRequestId,
            playbackSessionGeneration,
        ))
    postSessionEventSafely(
        session,
        Controller.SessionPresentationEvent(
            presentationTitle,
            Cartridge.supportsBatterySave(session.config.rom),
            playbackSessionGeneration,
        ),
    )
    publishPlaybackState()
    val context = stateContext
    postSessionEventSafely(
        session,
        StateUxSessionEvent(
            stateSessionId,
            context != null,
            context?.workspace?.activeGameDirectory(),
            stateUnavailableReason,
        ))
    if (allowAutosaveResume &&
        context != null &&
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
    postInitialMobileAdapterNetworkStatus()
  }

  /**
   * Keeps pathless host sessions on the state store resolved together with their battery storage.
   * Desktop sessions retain their configured writable root and read fallbacks.
   */
  private fun stateWorkspace(
      session: Session,
      identity: MachineIdentity,
      saves: ApplicationSettings.Saves,
  ): StateWorkspace {
    val hostStore = session.stateStore
    if (hostStore != null) {
      val layout = hostStore.layout
      return StateWorkspace(
          StateStoragePaths(layout, layout.screenshotsDirectory, emptyList()),
      ) { hostStore.repository() }
    }
    return stateWorkspaceFactory.create(
        StateStorageResolver.resolve(saves, session.config, identity),
    )
  }

  private fun installDebugPort(session: Session) {
    check(debugPort == null) { "Previous debug session was not revoked" }
    debugCheckpointHistory = DebugCheckpointHistory()
    debugInstructionReplayer.close()
    debugInstructionReplayer = DebugInstructionReplayer()
    debugSequence = 0
    debugMasterTick = 0
    debugFrame = 0
    debugFramePosition = 0
    debugTrackingEnabled = false
    pendingDebugAction = null
    lastDebugBreakpointHit = null
    debugBreakpointPauseActive = false
    session.gameboy.disableDebugRetirementTracking()
    session.gameboy.updateDebugInstrumentation(null, 0)
    debugInstrumentation =
        DebugInstrumentation(
            MAX_BREAKPOINTS,
            MAX_TRACE_CAPACITY,
            DEFAULT_TRACE_CAPACITY,
            BREAKPOINT_KINDS,
            TRACE_CATEGORIES,
        )
    val port =
        QueuedDebugPort(
            nextDebugSessionGeneration.incrementAndGet(),
            debugCapabilities(supportsDebugInstructionReplay(session)),
        )
    debugPort = port
    console?.setDebugPort(port)
  }

  /** Revokes a port before its owning session can be replaced or torn down. */
  private fun revokeDebugPort(owner: Session?, replaced: Boolean) {
    val port = debugPort ?: return
    if (replaced) {
      port.invalidateForSessionReplacement()
    } else {
      port.close()
    }
    if (debugTrackingEnabled) {
      owner?.gameboy?.disableDebugRetirementTracking()
    }
    debugCheckpointHistory.disable(DebugHistoryTruncationReason.SESSION_BOUNDARY)
    debugInstructionReplayer.close()
    owner?.gameboy?.updateDebugInstrumentation(null, debugMasterTick)
    debugPort = null
    debugInstrumentation = null
    lastDebugBreakpointHit = null
    debugBreakpointPauseActive = false
    pendingDebugAction = null
    debugTrackingEnabled = false
    debugPaused = false
    console?.setDebugPort(null)
    owner?.let {
      postSessionEventSafely(
          it,
          Controller.SessionDebugPortEvent(port.sessionGeneration(), null),
      )
    }
  }

  private fun applySavesSettings(
      saves: eu.rekawek.coffeegb.controller.properties.ApplicationSettings.Saves
  ) {
    cancelPendingRomSwitch()
    pendingResume = null
    releaseResumePause()
    isRewinding = false
    rewindManager = configuredRewindManager(saves, properties.overrides.rewindEnabled)
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
    closeAutosaveThumbnail = null
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
          StateWorkerContext(
              stateSessionId,
              stateWorkspace(currentSession, identity, saves),
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
    pendingSlotLoadAvailability = null
    latestStateRequests.clear()
    latestSaveRequests.clear()
    mobileAdapterExternalIoSaveRequests.clear()
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
    revokeDebugPort(session, replaced = false)
    postSerialPeripheralStatus(
        serialPeripheralSelection,
        Controller.SerialPeripheralStatus.DETACHED,
    )
    if (notifyLifecycle) {
      postSessionEventSafely(session, StateUxSessionEvent(stateSessionId, false, null))
      postSessionEventSafely(session, Controller.EmulationStoppedEvent())
    }
    playbackSessionGeneration = null
    stateContext = null
    pendingResume = null
    pendingSlotLoadAvailability = null
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
    console?.setDebugPort(null)
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
    val manager = snapshotManager
    if (manager == null) {
      requestStateSave(
          StateSaveRequestEvent(
              nextInternalStateRequestId(),
              stateSessionId,
              StateRef.Slot(slot),
              null,
              null,
          ))
      return
    }
    val mobileExternalIo = mobileAdapterEndpointHasExternalIo()
    try {
      manager.saveSnapshot(slot, currentSession)
    } catch (e: Exception) {
      LOG.warn("Unable to save snapshot slot {}", slot, e)
      postSessionEventSafely(
          currentSession,
          Controller.SnapshotSaveFailedEvent(
              slot,
              "Unable to save state slot $slot. Any previous state remains recoverable. " +
                  sanitizedPersistenceDetail(e),
          ))
      return
    }
    if (mobileExternalIo) postMobileAdapterSaveBoundary()
    // Persistence has completed. Keep success presentation outside the persistence catch so a
    // subscriber failure cannot recast a durable snapshot as a failed write or suppress the
    // non-restorable-I/O disclosure above.
    postSessionEventSafely(currentSession, Controller.SnapshotSavedEvent(slot))
  }

  private fun loadSnapshot(slot: Int) {
    val currentSession = session ?: return
    if (loadJob != null || pendingRomSwitch != null || replacementJob != null || stopJob != null) {
      currentSession.eventBus.post(
          Controller.SnapshotLoadFailedEvent(
              slot,
              "A ROM replacement is in progress. Retry the state restore after it is cancelled.",
          ))
      return
    }
    val manager = snapshotManager
    if (manager == null) {
      requestStateLoadRef(
          StateLoadRefRequestEvent(
              nextInternalStateRequestId(),
              stateSessionId,
              StateRef.Slot(slot),
          ))
      return
    }
    val mobileExternalIo = hasMobileAdapterExternalIo()
    val mobileBackendOwnership = mobileAdapterBackendOwnershipVersion()
    try {
      if (manager.loadSnapshot(slot, currentSession)) {
        relinquishDebugBreakpointPauseOwnership()
        rewindManager.clear()
        debugCheckpointHistory.clear(DebugHistoryTruncationReason.SESSION_BOUNDARY)
        if (mobileExternalIo || hasMobileAdapterDisconnectedExternalIoMarker()) {
          mobileAdapterStateLoadCompleted()
        }
        currentSession.eventBus.post(Controller.SnapshotRestoredEvent(slot))
      }
    } catch (e: Exception) {
      if (mobileExternalIo && mobileAdapterBackendOwnershipVersion() != mobileBackendOwnership) {
        mobileAdapterStateLoadCompleted()
      }
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
    // Reject already-admitted and future requests before the sole emulation owner is stopped.
    // A retryable persistence failure intentionally retains the frozen machine, but must never
    // leave an apparently open port whose commands have nobody left to service them.
    debugPort?.close()
    console?.setDebugPort(null)
    doStop = true
    awaitTimingThread(closeDeadlineNanos)

    // Close is a synchronous API: its caller owns retry/cancel presentation. Avoid invoking
    // arbitrary lifecycle subscribers on this thread while the one overall deadline is running.
    cancelLoadJob(notifyCancellation = false)
    cancelPendingRomSwitch(notifyCancellation = false, restorePause = false)
    discardReplacement(restorePause = false, notifyCancellation = false)
    discardStop(restorePause = false)
    discardBatteryFlush()
    pauseStateBeforeLoading = null
    pauseStateBeforeResume = null
    setPaused(true)
    releaseClosedDebugPortIfNeeded(notifyLifecycle = false)
    val guestConfigurationDrain =
        session?.let(::drainMobileAdapterGuestConfiguration)
            ?: MobileAdapterGuestConfigurationDrainResult.SAFE
    if (guestConfigurationDrain == MobileAdapterGuestConfigurationDrainResult.BLOCKED) {
      throw closeBarrierFailure(
          "$MOBILE_ADAPTER_CONFIGURATION_PENDING_MESSAGE Close can be retried.",
          java.io.IOException("Mobile Adapter configuration write was not accepted"),
          MOBILE_ADAPTER_CONFIGURATION_FILE_LABEL,
      )
    }
    flushMobileAdapterGuestConfiguration(closeDeadlineNanos)

    try {
      if (!properties.overrides.benchmarkPolicyEnabled && closeState == null) {
        closeState =
            session?.let {
              Controller.ControllerState(DetachedStateAdapter.capture(it.gameboy), it.config.rom)
            }
      }
      if (!closeAutosavePlayDurationCaptured) {
        closeAutosavePlayDurationNanos = currentPlayDurationNanos()
        closeAutosavePlayDurationCaptured = true
      }
      if (!properties.overrides.benchmarkPolicyEnabled && closeAutosaveCapture == null &&
          stateContext != null &&
          shouldPersistCloseAutosave()) {
        try {
          closeAutosaveCapture = session?.let(::capturePortableState)
          closeAutosaveThumbnail = captureAutosaveThumbnail()
        } catch (failure: Throwable) {
          throw closeBarrierFailure(
              "Close autosave could not be captured. The session is retained.",
              failure,
              "autosave state",
              autosaveWaivable = true,
          )
        }
      }
    } finally {
      // Capture first so a successful close autosave records the deterministic
      // non-restorable-I/O marker. Revocation is nevertheless guaranteed even when capture
      // itself fails and the retryable close barrier retains the session.
      if (hasMobileAdapterExternalIo()) {
        disconnectMobileAdapter(Controller.MobileAdapterDisconnectReason.SHUTDOWN)
      }
    }
    if (!properties.overrides.benchmarkPolicyEnabled && closeCapture == null) {
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

    val capture = closeCapture ?: BatteryFlush.none()
    if (properties.overrides.benchmarkPolicyEnabled) {
      // A benchmark process is a disposable observation.  Do not flush battery/RTC bytes or
      // write an autosave when the runner force-stops/recreates it between paired runs.
      capture.complete(BatteryPersistenceResult.Success(0))
    } else {
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
    }

    // The battery flush owns the persistence executor first. A close-autosave must not sit ahead
    // of an already-admitted replacement/stop writer, or a cancelled writer can starve the final
    // battery capture indefinitely. The portable state was captured before either write, after
    // ordinary state work was drained, so writing it after the battery flush remains coherent.
    if (!properties.overrides.benchmarkPolicyEnabled) {
      persistCloseAutosave(closeDeadlineNanos)
    }
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
    closeAutosaveThumbnail = null
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
                            closeAutosaveThumbnail?.thumbnail()?.let(StatePngCodec::encode),
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
        closeAutosaveThumbnail = null
        closeAutosaveAttempt = null
      }
    }
  }

  private fun shouldPersistCloseAutosave(): Boolean =
      !properties.overrides.suppressCloseAutosave &&
          session?.config?.rom?.file != null &&
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

  private fun flushMobileAdapterGuestConfiguration(closeDeadlineNanos: Long) {
    val result =
        try {
          mobileAdapterGuestConfigurationSink.flush(
              remainingCloseNanos(
                  closeDeadlineNanos,
                  "Mobile Adapter configuration persistence",
              ),
              TimeUnit.NANOSECONDS,
          )
        } catch (_: TimeoutException) {
          throw closeBarrierFailure(
              "Mobile Adapter configuration persistence timed out. " +
                  "The session remains retained and close can be retried.",
              TimeoutException("Mobile Adapter configuration persistence timed out"),
              MOBILE_ADAPTER_CONFIGURATION_FILE_LABEL,
          )
        } catch (_: InterruptedException) {
          Thread.currentThread().interrupt()
          throw closeBarrierFailure(
              "Mobile Adapter configuration persistence was interrupted. " +
                  "The session remains retained and close can be retried.",
              InterruptedException("Mobile Adapter configuration persistence was interrupted"),
              MOBILE_ADAPTER_CONFIGURATION_FILE_LABEL,
          )
        } catch (_: RuntimeException) {
          throw closeBarrierFailure(
              "Mobile Adapter configuration could not be saved. " +
                  "The session remains retained and close can be retried.",
              // A privileged sink has seen private adapter bytes. Do not retain its arbitrary
              // exception text or cause graph in the public controller failure.
              java.io.IOException("Mobile Adapter configuration writer failed"),
              MOBILE_ADAPTER_CONFIGURATION_FILE_LABEL,
          )
        }
    if (result.saved) return
    val error = result.error ?: MobileAdapterConfigurationError.STORAGE_WRITE_FAILED
    throw closeBarrierFailure(
        "${error.userMessage} The session remains retained and close can be retried.",
        java.io.IOException(error.code),
        MOBILE_ADAPTER_CONFIGURATION_FILE_LABEL,
    )
  }

  @Synchronized
  override fun waiveCloseAutosave(requestId: Long): Boolean {
    if (closed ||
        closeRequestId != requestId ||
        closeAutosaveWaivableRequestId != requestId ||
        session == null ||
        closeAutosaveCompletedSessionId == stateSessionId ||
        closeAutosaveAttempt?.isDone == false) {
      return false
    }
    closeAutosaveSkippedSessionId = stateSessionId
    closeAutosaveCapture = null
    closeAutosaveThumbnail = null
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

  private sealed interface PendingDebugAction {
    data class Pause(
        val command: QueuedDebugCommand.Pause,
        val startMasterTick: Long,
        val targetRetirement: Long,
    ) : PendingDebugAction

    data class InstructionStep(
        val command: QueuedDebugCommand.Step,
        val startMasterTick: Long,
        val startRetirement: Long,
        val targetRetirement: Long,
    ) : PendingDebugAction

    data class FrameStep(
        val command: QueuedDebugCommand.Step,
        val startMasterTick: Long,
        val startRetirement: Long,
        val targetMasterTick: Long,
    ) : PendingDebugAction
  }

  private data class PreparedSerialEndpoint(
      val endpoint: SerialEndpoint,
      val disconnect: () -> Unit = {},
  )

  private enum class MobileAdapterGuestConfigurationDrainResult {
    /** No unaccepted guest image remains for the committed attachment. */
    SAFE,

    /** The newest complete guest image was retained by the privileged sink. */
    ACCEPTED,

    /** The current image remains private in core memory and must be retried before teardown. */
    BLOCKED,
  }

  private enum class MobileAdapterRefreshResult {
    NOT_APPLICABLE,
    COMPLETED,
    BLOCKED,
  }

  /** Final backend owner; endpoint.disconnect() itself remains a reusable cancellation boundary. */
  private class MobileAdapterEndpointLifecycle(
      val attachmentId: Long,
      val policyRevision: Long,
      val backend: MobileAdapterNetworkBackend?,
      val runtimeNetworkConsent: Boolean,
      val runtimePrivateLocalDevelopment: Boolean,
  ) : () -> Unit {
    var disconnectReason: Controller.MobileAdapterDisconnectReason? = null
    var backendOwnershipVersion: Long = backend?.ownershipVersion() ?: 0
    var guestConfigurationHistoryObservedRevision: Long = 0
    var guestConfigurationPersistenceAcceptedRevision: Long = 0

    override fun invoke() {
      backend?.close()
    }
  }

  private companion object {
    val LOG: Logger = LoggerFactory.getLogger(BasicController::class.java)

    const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 8_000L

    const val MOBILE_ADAPTER_CONFIGURATION_FILE_LABEL = "Mobile Adapter configuration"

    const val MOBILE_ADAPTER_CONFIGURATION_PENDING_MESSAGE =
        "Mobile Adapter configuration changes could not be queued for storage. " +
            "The active session was preserved; retry after configuration storage is available."

    val STATE_SESSION_IDS = AtomicLong()

    val NEXT_MOBILE_ADAPTER_ATTACHMENT_ID = AtomicLong(1)

    const val NO_MOBILE_ADAPTER_CONFIGURATION_REVISION = -1L

    const val MAX_DEBUG_COMMANDS_PER_SAFE_POINT = 64

    const val MAX_DEBUG_CONTROL_EVENTS_PER_SAFE_POINT = 64

    const val MAX_BREAKPOINTS = 128

    const val MAX_TRACE_CAPACITY = 65_536

    const val DEFAULT_TRACE_CAPACITY = 4096

    const val MAX_TRACE_READ_ENTRIES = 1024

    val BREAKPOINT_KINDS: Set<DebugBreakpointKind> =
        EnumSet.of(
            DebugBreakpointKind.PROGRAM_COUNTER,
            DebugBreakpointKind.MEMORY,
            DebugBreakpointKind.OPCODE,
            DebugBreakpointKind.INTERRUPT,
            DebugBreakpointKind.PPU_STATE,
            DebugBreakpointKind.SERIAL,
            DebugBreakpointKind.COUNTER,
        )

    val TRACE_CATEGORIES: Set<TraceCategory> =
        EnumSet.allOf(TraceCategory::class.java)

    val DEBUG_CAPABILITIES = debugCapabilities(reverseInstruction = true)

    fun debugCapabilities(reverseInstruction: Boolean) =
        DebugCapabilities(
            true,
            true,
            true,
            false,
            true,
            true,
            true,
            true,
            4_096,
            BREAKPOINT_KINDS,
            MAX_BREAKPOINTS,
            TRACE_CATEGORIES,
            MAX_TRACE_CAPACITY,
            MAX_TRACE_READ_ENTRIES,
            DebugHistoryCapabilities(
                true,
                true,
                reverseInstruction,
                DebugHistoryConfiguration.MAX_FRAMES,
                DebugHistoryConfiguration.MAX_MEMORY_BUDGET_BYTES,
            ),
            EnumSet.allOf(DebugInspectionSection::class.java),
            MAX_TRACE_READ_ENTRIES,
        )

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
      var ready: ReplacementAttemptResult.Ready? = null,
  )

  private data class StopJob(
      val requestId: Long,
      val pausedBeforeStop: Boolean,
      val capture: BatteryFlush,
      var context: StateWorkerContext? = null,
      var state: StateFile? = null,
      var playDurationNanos: Long? = null,
      var thumbnail: StateImage? = null,
      var awaitingAutosaveDecision: Boolean = false,
      var attempt: PersistenceTask?,
      var awaitingGuestConfiguration: Boolean = false,
      var completedPersistence: BatteryPersistenceResult.Success? = null,
  )

  private data class BatteryFlushJob(
      val requestId: Long,
      val capture: BatteryFlush,
      val attempt: PersistenceTask,
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
      val thumbnail: StateImage?,
      var awaitingDecision: Boolean = false,
  )

  private data class PendingResume(
      val requestId: Long,
      val sessionId: Long,
      val key: StateEntryKey,
      val read: StateReadResult,
  )

  private data class PendingSlotLoadAvailability(
      val requestId: Long,
      val sessionId: Long,
      val ref: StateRef.Slot,
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

/** A coherent, constant-space handoff from arbitrary event producers to the frame owner. */
internal class MobileAdapterControlLane {
  data class Pending(
      val cancelOrder: Long,
      val refreshOrder: Long,
      val refreshRevision: Long,
  ) {
    val hasCancel: Boolean
      get() = cancelOrder >= 0

    val hasRefresh: Boolean
      get() = refreshOrder >= 0

    companion object {
      val EMPTY = Pending(-1, -1, -1)
    }
  }

  private val lock = Any()
  private var sequence = 0L
  private var pending = Pending.EMPTY

  fun offerCancel() {
    synchronized(lock) {
      sequence = Math.addExact(sequence, 1)
      pending = pending.copy(cancelOrder = sequence)
    }
  }

  fun offerRefresh(revision: Long) {
    require(revision >= 0) { "Mobile Adapter configuration revision must not be negative" }
    synchronized(lock) {
      sequence = Math.addExact(sequence, 1)
      if (revision >= pending.refreshRevision) {
        pending =
            pending.copy(
                refreshOrder = sequence,
                refreshRevision = revision,
            )
      }
    }
  }

  fun drain(): Pending =
      synchronized(lock) {
        pending.also { pending = Pending.EMPTY }
      }

  /** Merges unfinished controls without moving them after controls offered while work was blocked. */
  fun restoreDeferred(deferred: Pending) {
    synchronized(lock) {
      val refresh =
          when {
            deferred.refreshRevision > pending.refreshRevision -> deferred
            deferred.refreshRevision < pending.refreshRevision -> pending
            deferred.refreshOrder > pending.refreshOrder -> deferred
            else -> pending
          }
      pending =
          Pending(
              cancelOrder = maxOf(pending.cancelOrder, deferred.cancelOrder),
              refreshOrder = refresh.refreshOrder,
              refreshRevision = refresh.refreshRevision,
          )
    }
  }

  internal fun snapshot(): Pending = synchronized(lock) { pending }
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
): RewindManager =
    configuredRewindManager(
        properties.applicationSettings.saves,
        properties.overrides.rewindEnabled,
    )

private fun configuredRewindManager(
    saves: ApplicationSettings.Saves,
    rewindEnabledOverride: Boolean? = null,
): RewindManager =
    RewindManager(
        enabled = rewindEnabledOverride ?: saves.rewindEnabled,
        durationSeconds = saves.rewindSeconds,
        memoryBudgetBytes = saves.rewindMemoryMiB.toLong() * 1024L * 1024L,
    )
