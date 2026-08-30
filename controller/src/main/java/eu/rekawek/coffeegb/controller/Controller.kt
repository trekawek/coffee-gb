package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.mobile.config.MobileAdapterConfigurationError
import eu.rekawek.coffeegb.controller.mobile.network.MobileAdapterNetworkBackend
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.properties.SystemProperties
import eu.rekawek.coffeegb.controller.state.MachineState
import eu.rekawek.coffeegb.controller.state.RomPersistenceStore
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.debug.DebugPort
import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.hardware.HardwareProfile
import eu.rekawek.coffeegb.core.hardware.HardwareProfileIdentity
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.memory.Bios
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.RomSourceSnapshot
import eu.rekawek.coffeegb.core.memory.cart.RomImage
import eu.rekawek.coffeegb.core.memory.cart.RomOrigin
import java.io.File

interface Controller : AutoCloseable {

  fun startController()

  fun closeWithState(): ControllerState?

  /**
   * Waives only a completed/unavailable managed close-autosave barrier retained by this controller.
   * In-flight writers are never waivable, and stale request IDs are rejected.
   */
  fun waiveCloseAutosave(requestId: Long): Boolean = false

  data class EmulationStartedEvent
  @JvmOverloads
  constructor(
      val romName: String,
      val origin: RomOrigin? = null,
      val openRequestId: Long? = null,
      /** Globally unique generation for session-scoped presentation events, when available. */
      val sessionGeneration: Long? = null,
  ) : Event

  /**
   * Immutable cartridge presentation metadata for the active session.
   *
   * The battery flag expresses mapper capability only.  It intentionally does not reveal whether
   * a file exists, whether the save is dirty, or whether a storage backend is configured.
   */
  data class SessionPresentationEvent(
      val romTitle: String,
      val batterySaveActive: Boolean,
      val sessionGeneration: Long? = null,
  ) : Event

  class EmulationStoppedEvent : Event

  /**
   * Synchronous boundary around one near-60-Hz PERFORMANCE controller work cycle. Host
   * integrations use these singleton events to exclude controller pacing and idle time.
   */
  object PerformanceWorkStartedEvent : Event

  object PerformanceWorkCompletedEvent : Event

  /** Cancels a started work cycle which stopped before its complete controller tick budget. */
  object PerformanceWorkAbortedEvent : Event

  data class LoadRomEvent
  @JvmOverloads
  constructor(
      val rom: File,
      val state: MachineState? = null,
      val image: RomImage? = null,
      /** Host-provided storage for pathless ROM inputs. Null retains the desktop adapter. */
      val persistenceStore: RomPersistenceStore? = null,
      val openRequestId: Long? = null,
      /**
       * Whether a completed ROM activation may consult the autosave-resume policy. A Reset is a
       * deliberate fresh boot, so it sets this to false rather than restoring or proposing old
       * progress.
       */
      val allowAutosaveResume: Boolean = true,
  ) : Event {
    constructor(
        image: RomImage,
        state: MachineState? = null,
        persistenceStore: RomPersistenceStore? = null,
        openRequestId: Long? = null,
        allowAutosaveResume: Boolean = true,
    ) : this(
        image.origin().containerPath().map { it.toFile() }.orElse(File(image.origin().displayName())),
        state,
        image,
        persistenceStore,
        openRequestId,
        allowAutosaveResume,
    )
  }

  data class RomLoadingEvent
  @JvmOverloads
  constructor(
      val rom: File,
      val openRequestId: Long? = null,
  ) : Event

  data class RomLoadingCancelledEvent
  @JvmOverloads
  constructor(
      val rom: File,
      val openRequestId: Long? = null,
  ) : Event

  data class LoadRomFailedEvent
  @JvmOverloads
  constructor(
      val rom: File,
      val message: String,
      val openRequestId: Long? = null,
      val kind: RomLoadFailureKind = RomLoadFailureKind.CORE_STARTUP,
      val technicalDetails: String = message,
  ) : Event

  enum class RomLoadFailureKind {
    CORE_STARTUP,
    PERSISTENCE,
    INTERNAL,
  }

  /** Cancels only the matching user-facing open request; stale cancellation is a no-op. */
  data class CancelRomOpenEvent(val openRequestId: Long) : Event

  /**
   * A ROM replacement is paused at its persistence barrier. The old session remains alive until
   * the matching retry or cancel command is posted.
   */
  data class RomReplacementPersistenceFailedEvent
  @JvmOverloads
  constructor(
      val requestId: Long,
      val fileName: String,
      val message: String,
      val operation: PersistenceBarrierOperation = PersistenceBarrierOperation.ROM_REPLACEMENT,
      val openRequestId: Long? = null,
  ) : Event

  data class RetryRomReplacementEvent(val requestId: Long) : Event

  data class CancelRomReplacementEvent(val requestId: Long) : Event

  enum class PersistenceBarrierOperation {
    ROM_REPLACEMENT,
    STOP,
    RESET,
    CLOSE,
  }

  /**
   * A synchronous close did not release its session because persistence failed or timed out.
   * The caller must retain this controller and may invoke [closeWithState] again to retry.
   */
  class PersistenceBarrierException(
      val requestId: Long,
      val operation: PersistenceBarrierOperation,
      val fileName: String,
      message: String,
      cause: Throwable,
      val closeAutosaveWaivable: Boolean = false,
  ) : IllegalStateException(message, cause)

  class PauseEmulationEvent : Event

  class ResumeEmulationEvent : Event

  /**
   * Arms benchmark-only stop-aware execution until a native display endpoint requests an exact
   * pause. PERFORMANCE keeps its scheduler semantics; the session generation prevents a delayed
   * Android owner callback from controlling a replacement session.
   */
  data class BenchmarkGameplayScenarioStartEvent
  @JvmOverloads
  constructor(
      val sessionGeneration: Long,
      val expectedFrames: Int,
  ) : Event {
    init {
      require(sessionGeneration > 0L) { "Session generation must be positive" }
      require(expectedFrames > 0) { "Benchmark scenario frame count must be positive" }
    }
  }

  /**
   * Synchronous endpoint raised from a native Display callback on the controller owner thread.
   * Unlike [PauseEmulationEvent], this is not queued until the current runTicks tail completes.
   */
  data class BenchmarkGameplayScenarioEndpointEvent(
      val sessionGeneration: Long,
      val completedFrames: Int,
  ) : Event {
    init {
      require(sessionGeneration > 0L) { "Session generation must be positive" }
      require(completedFrames > 0) { "Benchmark scenario frame count must be positive" }
    }
  }

  /** Exact controller-owned completion evidence for one benchmark preconditioning scenario. */
  data class BenchmarkGameplayScenarioCompletedEvent(
      val sessionGeneration: Long,
      val completedFrames: Int,
      val expectedFrames: Int,
      val completed: Boolean,
  ) : Event {
    init {
      require(sessionGeneration > 0L) { "Session generation must be positive" }
      require(completedFrames > 0) { "Completed frame count must be positive" }
      require(expectedFrames > 0) { "Expected frame count must be positive" }
      require(!completed || completedFrames == expectedFrames) {
        "Successful benchmark scenario evidence must have an exact frame count"
      }
    }
  }

  /**
   * Captures the active cartridge at the controller's frame-safe point and persists it on the
   * controller-owned worker. Hosts can request a bounded background flush without observing a
   * live machine or blocking their UI thread.
   */
  data class FlushBatteryEvent(val requestId: Long) : Event

  /** Terminal result for one [FlushBatteryEvent]. A failed request remains safe to retry. */
  data class BatteryFlushCompletedEvent(
      val requestId: Long,
      val filesWritten: Int,
      val succeeded: Boolean,
  ) : Event

  class ResetEmulationEvent : Event

  class StopEmulationEvent : Event

  data class SaveSnapshotEvent(val slot: Int) : Event

  data class RestoreSnapshotEvent(val slot: Int) : Event

  /** Emitted after a snapshot has been written successfully. */
  data class SnapshotSavedEvent(val slot: Int) : Event

  /** Emitted when a snapshot replacement fails; any previous slot remains recoverable. */
  data class SnapshotSaveFailedEvent(val slot: Int, val message: String) : Event

  /** Emitted after a snapshot has been restored successfully. */
  data class SnapshotRestoredEvent(val slot: Int) : Event

  /** Emitted when a snapshot is rejected or cannot be applied without changing the session. */
  data class SnapshotLoadFailedEvent(val slot: Int, val message: String) : Event

  data class SessionPauseSupportEvent(val enabled: Boolean) : Event

  /**
   * Authoritative effective playback state for one committed emulation session. [paused] combines
   * every independent pause owner (currently application workflow and debugger ownership), so UI
   * consumers must present this result instead of interpreting pause/resume command requests.
   */
  data class SessionPlaybackStateEvent(
      val sessionGeneration: Long,
      val paused: Boolean,
  ) : Event {
    init {
      require(sessionGeneration > 0) { "Session generation must be positive" }
    }
  }

  data class SessionSnapshotSupportEvent(val snapshotSupport: SnapshotSupport?) : Event

  /**
   * Publishes the immutable command port for the committed session generation. Single-player
   * sessions expose functional capabilities; linked sessions expose a typed unavailable port.
   * A null port revokes the matching generation during replacement or stop.
   */
  data class SessionDebugPortEvent(val generation: Long, val debugPort: DebugPort?) : Event

  class UpdatedSystemMappingEvent : Event

  /** Applies the validated Saves preference section at the next controller frame boundary. */
  data class UpdatedSavesSettingsEvent(val saves: ApplicationSettings.Saves) : Event

  data class GameboyTypeEvent(val gameboyType: GameboyType) : Event

  /**
   * Canonical stable profile identity plus the machine state resolved at session activation.
   * The speed value is an initial/boot-resolved sample; it is not a promise that a game cannot
   * switch CPU speed later in the session.
   */
  data class HardwareProfileEvent(
      val profile: HardwareProfile,
      val identity: HardwareProfileIdentity = profile.identity(),
      /** Actual SpeedMode/GPU flags sampled after bootstrap or state materialization. */
      val effectiveGbc: Boolean? = null,
      val effectiveDmgCompat: Boolean? = null,
      val effectiveSpeedMode: Int? = null,
  ) : Event

  /**
   * Benchmark-only physical display-boundary evidence sampled on the controller owner thread. The
   * event is emitted only when [ApplicationSettingsOverrides.benchmarkPolicyEnabled] is true, so
   * ordinary sessions retain their zero-allocation frame loop. A frame-600 sample is taken from
   * the synchronous Display hand-off, rather than relabeling the controller/audio cadence.
   */
  data class BenchmarkFrameBoundaryEvent(
      val frame: Long,
      val effectiveGbc: Boolean,
      val effectiveDmgCompat: Boolean,
      val speedMode: Int,
      val performanceBulkSpans: Long = 0L,
      val performanceBulkTicks: Long = 0L,
      val performanceEpochCount: Long = 0L,
      val performanceEpochTicks: Long = 0L,
      val performanceEpochMaxTicks: Int = 0,
      val performanceEpochRasterFastTicks: Long = 0L,
      val performanceEpochMode2ReplayTicks: Long = 0L,
      val performanceEpochMode2BulkTicks: Long = 0L,
      val performanceEpochLcdOffTicks: Long = 0L,
      /** Host-only benchmark audio policy requested for this measured generation. */
      val benchmarkAudioPolicy: String = "canonical",
      val benchmarkAudioRequested: Boolean = false,
      val benchmarkAudioActiveAtBoundary: Boolean = false,
      val benchmarkAudioDisabledAfterBoundary: Boolean = false,
      val benchmarkAudioSkippedTicks: Long = 0L,
      val benchmarkAudioZeroSampleSlots: Long = 0L,
      val benchmarkAudioZeroSampleEvents: Long = 0L,
      val benchmarkAudioMaxDebt: Long = 0L,
      val benchmarkAudioApuReads: Long = 0L,
      val benchmarkAudioApuWrites: Long = 0L,
      val benchmarkAudioFrameSequencerCommits: Long = 0L,
      val benchmarkAudioDroppedChannelTicks: Long = 0L,
  ) : Event {
    /** Java/source compatibility for callers that predate bulk telemetry. */
    constructor(
        frame: Long,
        effectiveGbc: Boolean,
        effectiveDmgCompat: Boolean,
        speedMode: Int,
    ) : this(
        frame, effectiveGbc, effectiveDmgCompat, speedMode,
        0L, 0L, 0L, 0L, 0, 0L, 0L, 0L,
        0L, "canonical", false, false, false, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
    )

    /** Java/source compatibility for callers that supply the original bulk telemetry pair. */
    constructor(
        frame: Long,
        effectiveGbc: Boolean,
        effectiveDmgCompat: Boolean,
        speedMode: Int,
        performanceBulkSpans: Long,
        performanceBulkTicks: Long,
    ) : this(
        frame, effectiveGbc, effectiveDmgCompat, speedMode,
        performanceBulkSpans, performanceBulkTicks, 0L, 0L, 0, 0L, 0L, 0L,
        0L, "canonical", false, false, false, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
    )

    /** Java/source compatibility for callers that predate the mode-2 bulk subset metric. */
    constructor(
        frame: Long,
        effectiveGbc: Boolean,
        effectiveDmgCompat: Boolean,
        speedMode: Int,
        performanceBulkSpans: Long,
        performanceBulkTicks: Long,
        performanceEpochCount: Long,
        performanceEpochTicks: Long,
        performanceEpochMaxTicks: Int,
        performanceEpochRasterFastTicks: Long,
        performanceEpochMode2ReplayTicks: Long,
    ) : this(
        frame, effectiveGbc, effectiveDmgCompat, speedMode,
        performanceBulkSpans, performanceBulkTicks, performanceEpochCount,
        performanceEpochTicks, performanceEpochMaxTicks, performanceEpochRasterFastTicks,
        performanceEpochMode2ReplayTicks, 0L,
        0L, "canonical", false, false, false, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
    )

    /** Java/source compatibility for callers that supplied the complete pre-policy telemetry. */
    constructor(
        frame: Long,
        effectiveGbc: Boolean,
        effectiveDmgCompat: Boolean,
        speedMode: Int,
        performanceBulkSpans: Long,
        performanceBulkTicks: Long,
        performanceEpochCount: Long,
        performanceEpochTicks: Long,
        performanceEpochMaxTicks: Int,
        performanceEpochRasterFastTicks: Long,
        performanceEpochMode2ReplayTicks: Long,
        performanceEpochMode2BulkTicks: Long,
    ) : this(
        frame, effectiveGbc, effectiveDmgCompat, speedMode,
        performanceBulkSpans, performanceBulkTicks, performanceEpochCount,
        performanceEpochTicks, performanceEpochMaxTicks, performanceEpochRasterFastTicks,
        performanceEpochMode2ReplayTicks, performanceEpochMode2BulkTicks,
        0L, "canonical", false, false, false, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
    )

    /** Java/source compatibility constructor including fixed-x1 LCD-off epoch telemetry. */
    constructor(
        frame: Long,
        effectiveGbc: Boolean,
        effectiveDmgCompat: Boolean,
        speedMode: Int,
        performanceBulkSpans: Long,
        performanceBulkTicks: Long,
        performanceEpochCount: Long,
        performanceEpochTicks: Long,
        performanceEpochMaxTicks: Int,
        performanceEpochRasterFastTicks: Long,
        performanceEpochMode2ReplayTicks: Long,
        performanceEpochMode2BulkTicks: Long,
        performanceEpochLcdOffTicks: Long,
    ) : this(
        frame, effectiveGbc, effectiveDmgCompat, speedMode,
        performanceBulkSpans, performanceBulkTicks, performanceEpochCount,
        performanceEpochTicks, performanceEpochMaxTicks, performanceEpochRasterFastTicks,
        performanceEpochMode2ReplayTicks, performanceEpochMode2BulkTicks,
        performanceEpochLcdOffTicks,
        "canonical", false, false, false, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
    )

    init {
      require(frame > 0) { "Benchmark frame must be positive" }
      require(speedMode == 1 || speedMode == 2) { "Benchmark speed mode must be 1 or 2" }
      require(performanceBulkSpans >= 0L) { "Benchmark bulk span count must be non-negative" }
      require(performanceBulkTicks >= 0L) { "Benchmark bulk tick count must be non-negative" }
      require(performanceEpochCount >= 0L) { "Benchmark epoch count must be non-negative" }
      require(performanceEpochTicks >= 0L) { "Benchmark epoch tick count must be non-negative" }
      require(performanceEpochMaxTicks >= 0) { "Benchmark epoch maximum must be non-negative" }
      require(performanceEpochRasterFastTicks >= 0L) {
        "Benchmark epoch raster fast tick count must be non-negative"
      }
      require(performanceEpochMode2ReplayTicks >= 0L) {
        "Benchmark epoch mode-2 replay tick count must be non-negative"
      }
      require(performanceEpochMode2BulkTicks >= 0L) {
        "Benchmark epoch mode-2 bulk tick count must be non-negative"
      }
      require(performanceEpochLcdOffTicks >= 0L) {
        "Benchmark epoch LCD-off tick count must be non-negative"
      }
      require(benchmarkAudioPolicy == "canonical" || benchmarkAudioPolicy == "silent-pcm-v1"
          || benchmarkAudioPolicy == "silent-pcm-relaxed-apu-v1") {
        "Benchmark audio policy must be canonical, silent-pcm-v1, or silent-pcm-relaxed-apu-v1"
      }
      require(benchmarkAudioSkippedTicks >= 0L) {
        "Benchmark audio skipped tick count must be non-negative"
      }
      require(benchmarkAudioZeroSampleSlots >= 0L) {
        "Benchmark audio zero sample slot count must be non-negative"
      }
      require(benchmarkAudioZeroSampleEvents >= 0L) {
        "Benchmark audio zero sample event count must be non-negative"
      }
      require(benchmarkAudioMaxDebt >= 0L) {
        "Benchmark audio maximum debt must be non-negative"
      }
      require(benchmarkAudioApuReads >= 0L) {
        "Benchmark audio APU read count must be non-negative"
      }
      require(benchmarkAudioApuWrites >= 0L) {
        "Benchmark audio APU write count must be non-negative"
      }
      require(benchmarkAudioFrameSequencerCommits >= 0L) {
        "Benchmark audio frame-sequencer commit count must be non-negative"
      }
      require(benchmarkAudioDroppedChannelTicks >= 0L) {
        "Benchmark audio dropped channel tick count must be non-negative"
      }
    }
  }

  /**
   * Explicit benchmark arm token.  The Android harness posts this only after the host has
   * observed the materialized/paused session and captured its compositor baseline.  It is not
   * emitted or consumed by ordinary sessions.
   */
  data class BenchmarkArmEvent
  @JvmOverloads
  constructor(
      val generation: Long,
      val token: String,
      val sessionGeneration: Long,
      /** The selected silent policy token is carried through rejected arms as well. */
      val policy: String = "canonical",
  ) : Event {
    init {
      require(generation > 0L) { "Benchmark generation must be positive" }
      require(token.matches(Regex("[a-z0-9][a-z0-9._-]{15,63}"))) {
        "Benchmark arm token must be opaque and parser-safe"
      }
      require(sessionGeneration > 0L) { "Benchmark session generation must be positive" }
      require(policy == "canonical"
          || policy == BenchmarkSilentPcmPolicyEvent.POLICY
          || policy == BenchmarkSilentPcmPolicyEvent.RELAXED_APU_POLICY) {
        "Unsupported benchmark audio policy"
      }
    }
  }

  /**
   * Controller-thread acknowledgement emitted after the benchmark arm reset has taken effect.
   * Android uses this boundary to sample controller CPU/priority/environment state and to start
   * the measured frame epoch before posting Resume.
   */
  data class BenchmarkArmAcknowledgedEvent(
      val generation: Long,
      val token: String,
      val sessionGeneration: Long,
  ) : Event {
    init {
      require(generation > 0L) { "Benchmark generation must be positive" }
      require(token.matches(Regex("[a-z0-9][a-z0-9._-]{15,63}"))) {
        "Benchmark arm token must be opaque and parser-safe"
      }
      require(sessionGeneration > 0L) { "Benchmark session generation must be positive" }
    }
  }

  /**
   * Generation-bound host-audio policy decision.  The controller owns the only call that enables
   * the transient silent calendar; Android posts this event before the matching Resume event.
   */
  data class BenchmarkSilentPcmPolicyEvent
  @JvmOverloads
  constructor(
      val requested: Boolean,
      val generation: Long,
      val sessionGeneration: Long,
      val accepted: Boolean = true,
      val policy: String = if (requested) POLICY else "canonical",
  ) : Event {
    init {
      require(generation > 0L) { "Benchmark generation must be positive" }
      require(sessionGeneration > 0L) { "Benchmark session generation must be positive" }
      require(policy == "canonical" && !requested
          || policy == POLICY || policy == RELAXED_APU_POLICY) {
        "Unsupported benchmark audio policy"
      }
      require(!accepted || requested || policy == "canonical") {
        "Accepted benchmark audio policy must request canonical OFF or a silent calendar"
      }
    }

    companion object {
      const val POLICY: String = "silent-pcm-v1"
      const val RELAXED_APU_POLICY: String = "silent-pcm-relaxed-apu-v1"
    }
  }

  /** Owner-thread fence for a read-only system-audio failure detected during a frame callback. */
  data class BenchmarkSystemAudioViolationEvent(
      val generation: Long,
      val sessionGeneration: Long,
      val policy: String,
  ) : Event {
    init {
      require(generation > 0L) { "Benchmark generation must be positive" }
      require(sessionGeneration > 0L) { "Benchmark session generation must be positive" }
      require(policy == BenchmarkSilentPcmPolicyEvent.POLICY
          || policy == BenchmarkSilentPcmPolicyEvent.RELAXED_APU_POLICY) {
        "Unsupported benchmark audio policy"
      }
    }
  }

  /**
   * Synchronous physical Display publication boundary from the Android frame hand-off.  This is
   * distinct from the controller/audio cadence: 60-frame checkpoints carry benchmark probes,
   * while the 600th event is the exact display frame that must freeze the core.
   */
  data class BenchmarkPhysicalFrameBoundaryEvent(val frame: Long, val generation: Long) : Event {
    init {
      require(frame > 0L) { "Benchmark physical frame must be positive" }
      require(generation > 0L) { "Benchmark generation must be positive" }
    }
  }

  /** Cumulative temporary SGB epoch attribution sampled at a physical 60-frame boundary. */
  data class BenchmarkSgbEpochProbeEvent(
      val frame: Long,
      val totalTicks: Long,
      val expectedTicks: Long,
      val epochTicks: Long,
      val bulkTicks: Long,
      val scalarFallbackTicks: Long,
      val epochRasterTicks: Long,
      val sgbIdleOfferedTicks: Long,
      val sgbIdleCommittedTicks: Long,
      val epochMode2BulkTicks: Long,
      val epochLcdOffTicks: Long,
      val scalarModeHblankTicks: Long,
      val scalarModeVblankTicks: Long,
      val scalarMode2Ticks: Long,
      val scalarMode3Ticks: Long,
      val scalarModeOtherTicks: Long,
      val rejectGpuCommonTicks: Long,
      val rejectGpuHblankLineTicks: Long,
      val rejectGpuTimingOutputTicks: Long,
      val rejectGpuVisibleOutputTicks: Long,
      val rejectGpuLineEdgeTicks: Long,
      val rejectGpuOtherTicks: Long,
      val rejectCpuLifecycleTicks: Long,
      val rejectCpuIrqTicks: Long,
      val rejectCpuControlTicks: Long,
      val rejectCpuPpuPhaseTicks: Long,
      val rejectCpuPendingEiTicks: Long,
      val rejectCpuRawImeTrueTicks: Long,
      val rejectCpuRawImeFalseTicks: Long,
      val rejectCpuOtherTicks: Long,
      val rejectPreflightOwnerDmaTicks: Long,
      val rejectPreflightTimerTicks: Long,
      val rejectPreflightSoundTicks: Long,
      val rejectPreflightJoypadTicks: Long,
      val rejectPreflightSerialTicks: Long,
      val rejectPreflightStatTicks: Long,
      val rejectPreflightFinalGuardTicks: Long,
      val rejectPreflightOtherTicks: Long,
      val rejectExecPrefetchTicks: Long,
      val rejectExecDecodedReadTicks: Long,
      val rejectExecDecodedWriteTicks: Long,
      val rejectExecControlTicks: Long,
      val rejectExecLifecycleTicks: Long,
      val rejectExecOtherTicks: Long,
  ) : Event {
    init {
      require(frame in 60L..600L && frame % 60L == 0L) {
        "SGB epoch probe frame must be a 60-frame checkpoint"
      }
    }
  }

  /** Posted while the rewind key is held; the emulation plays backwards while active. */
  data class RewindEvent(val active: Boolean) : Event

  /**
   * The exclusive device selected for a standalone Game Boy link port.
   *
   * [PEER_TO_PEER] preserves the historical default: an unconnected link cable endpoint that can
   * later be paired by a linked controller. [NONE] models a physically empty port. Mobile Adapter
   * backend availability is reported separately through [SerialPeripheralStatusEvent].
   */
  enum class SerialPeripheralSelection {
    NONE,
    PRINTER,
    BARCODE_BOY,
    GPS_RECEIVER,
    MOBILE_ADAPTER_GB,
    PEER_TO_PEER,
  }

  /** Selects exactly one standalone link-port peripheral at the next controller safe point. */
  data class SetSerialPeripheralEvent(val selection: SerialPeripheralSelection) : Event

  /** Emitted after a commit and when a newly active session reasserts its authoritative choice. */
  data class SerialPeripheralSelectionChangedEvent(val selection: SerialPeripheralSelection) :
      Event

  enum class SerialPeripheralStatus {
    /** The choice is retained, but no emulation session currently owns an endpoint. */
    DETACHED,

    /** The selected endpoint is installed in the active standalone emulation session. */
    ATTACHED,

    /** The requested endpoint could not be prepared; the previous selection remains active. */
    UNAVAILABLE,
  }

  /**
   * Stable, presentation-safe peripheral failures. These values intentionally carry no exception
   * text, path, payload, host, account, or configuration bytes.
   */
  enum class SerialPeripheralError(
      val code: String,
      val userMessage: String,
  ) {
    ENDPOINT_UNAVAILABLE(
        "ENDPOINT_UNAVAILABLE",
        "The selected serial peripheral is not available in this configuration.",
    ),
    CONFIGURATION_INVALID(
        "CONFIGURATION_INVALID",
        "The selected serial peripheral configuration is invalid.",
    ),
    STORAGE_FAILED(
        "STORAGE_FAILED",
        "The selected serial peripheral configuration could not be loaded.",
    ),
    PORT_OWNED_BY_LINK(
        "PORT_OWNED_BY_LINK",
        "Stop the active network link before selecting a standalone serial peripheral.",
    ),
  }

  /** Current attachment state for a requested or committed standalone serial peripheral. */
  data class SerialPeripheralStatusEvent(
      val selection: SerialPeripheralSelection,
      val status: SerialPeripheralStatus,
      val error: SerialPeripheralError? = null,
  ) : Event {
    init {
      require((status == SerialPeripheralStatus.UNAVAILABLE) == (error != null)) {
        "Unavailable serial status and typed error must be present together"
      }
    }
  }

  /**
   * Rebuilds an attached Mobile Adapter from the latest private configuration snapshot.
   *
   * Only the monotonic revision crosses the application event tree. Resolver addresses, custom
   * names, port mappings, private configuration bytes, and runtime consent remain inside the
   * controller-owned provider.
   */
  data class RefreshMobileAdapterConfigurationEvent(val revision: Long) : Event {
    init {
      require(revision >= 0) { "Mobile Adapter configuration revision must not be negative" }
    }
  }

  /** Sanitized durability status for configuration bytes accepted from the emulated adapter. */
  data class MobileAdapterConfigurationPersistenceStatusEvent(
      val sequence: Long,
      val attachmentId: Long,
      val mutationRevision: Long,
      val phase: MobileAdapterConfigurationPersistencePhase,
      val error: MobileAdapterConfigurationError? = null,
  ) : Event {
    init {
      require(sequence > 0) { "Mobile Adapter persistence sequence must be positive" }
      require(attachmentId > 0) { "Mobile Adapter attachment ID must be positive" }
      require(mutationRevision > 0) { "Mobile Adapter mutation revision must be positive" }
      require((phase == MobileAdapterConfigurationPersistencePhase.FAILED) == (error != null)) {
        "Only a failed Mobile Adapter persistence event carries an error"
      }
    }
  }

  enum class MobileAdapterConfigurationPersistencePhase {
    PENDING,
    SAVED,
    SUPERSEDED,
    FAILED,
  }

  /** Cancels current Mobile Adapter host work without detaching its serial endpoint. */
  data object CancelMobileAdapterNetworkEvent : Event

  /** Presentation-safe controller phases for explicitly consented custom-server I/O. */
  enum class MobileAdapterNetworkPhase {
    OFFLINE,
    READY,
    RESOLVING,
    CONNECTING,
    CONNECTED,
    TRANSFERRING,
    CANCELLING,
    DISCONNECTED,
    FAILED,
  }

  /**
   * Stable custom-network failures. These values deliberately contain no remote text, host,
   * address, path, payload, account, credential, or exception message.
   */
  enum class MobileAdapterNetworkError(
      val code: String,
      val userMessage: String,
  ) {
    CONSENT_REQUIRED(
        "CONSENT_REQUIRED",
        "Outbound Mobile Adapter networking requires explicit consent for this session.",
    ),
    PRIVATE_LOCAL_GATE_REQUIRED(
        "PRIVATE_LOCAL_GATE_REQUIRED",
        "This destination requires the separate development-only private/LAN permission.",
    ),
    DESTINATION_DENIED(
        "DESTINATION_DENIED",
        "The requested destination is not allowed by the custom-server policy.",
    ),
    INVALID_REQUEST(
        "INVALID_REQUEST",
        "The Mobile Adapter custom-server request was malformed.",
    ),
    INVALID_CONNECTION(
        "INVALID_CONNECTION",
        "The Mobile Adapter connection slot is not open for this operation.",
    ),
    DNS_INVALID("DNS_INVALID", "The custom DNS response was malformed or outside its limits."),
    DNS_FAILED("DNS_FAILED", "The custom DNS lookup failed."),
    TIMEOUT("TIMEOUT", "The custom-server operation timed out."),
    CONNECTION_REFUSED("CONNECTION_REFUSED", "The custom server refused the connection."),
    DESTINATION_UNREACHABLE(
        "DESTINATION_UNREACHABLE",
        "The configured custom-server destination is unreachable.",
    ),
    CONNECTION_LIMIT(
        "CONNECTION_LIMIT",
        "Both bounded Mobile Adapter connection slots are occupied.",
    ),
    REMOTE_CLOSED("REMOTE_CLOSED", "The custom server closed the connection."),
    TRANSFER_LIMIT("TRANSFER_LIMIT", "The custom-server transfer exceeded a bounded limit."),
    QUEUE_EXHAUSTED("QUEUE_EXHAUSTED", "The bounded Mobile Adapter work queue is full."),
    CANCELLED("CANCELLED", "The custom-server operation was cancelled."),
    IO_FAILED("IO_FAILED", "The custom-server operation failed."),
  }

  enum class MobileAdapterDisconnectReason {
    USER_CANCELLED,
    POLICY_CHANGED,
    PROTOCOL_RESET,
    STATE_LOAD,
    REWIND,
    DETACHED,
    SESSION_STOPPED,
    SHUTDOWN,
  }

  /**
   * Immutable sanitized status drained from one bounded backend at a controller safe point.
   * [attachmentId] prevents late worker results from replacing a newer endpoint's presentation.
   */
  data class MobileAdapterNetworkStatusEvent(
      val attachmentId: Long,
      val policyRevision: Long,
      val phase: MobileAdapterNetworkPhase,
      val slot: Int? = null,
      val activeConnections: Int = 0,
      val error: MobileAdapterNetworkError? = null,
      val disconnectReason: MobileAdapterDisconnectReason? = null,
  ) : Event {
    init {
      require(attachmentId >= 0) { "Mobile Adapter attachment ID must not be negative" }
      require(policyRevision >= 0) { "Mobile Adapter policy revision must not be negative" }
      require(slot == null || slot in 0..1) { "Mobile Adapter slot must be 0 or 1" }
      require(activeConnections in 0..2) {
        "Mobile Adapter active connection count must be in 0..2"
      }
      require(phase != MobileAdapterNetworkPhase.DISCONNECTED || activeConnections == 0) {
        "A disconnected Mobile Adapter cannot retain a logical connection"
      }
      require(phase != MobileAdapterNetworkPhase.DISCONNECTED || slot == null) {
        "A disconnected Mobile Adapter cannot identify a live connection slot"
      }
      require((phase == MobileAdapterNetworkPhase.FAILED) == (error != null)) {
        "A failed Mobile Adapter network status must carry exactly one typed error"
      }
      require(error != MobileAdapterNetworkError.REMOTE_CLOSED || slot != null) {
        "A remote-close status must identify the affected connection slot"
      }
      require(
          (phase == MobileAdapterNetworkPhase.DISCONNECTED) == (disconnectReason != null)
      ) {
        "A disconnected Mobile Adapter status must carry exactly one typed reason"
      }
    }
  }

  enum class MobileAdapterStateBoundary {
    SAVE,
    LOAD,
    REWIND,
    RESET,
  }

  enum class MobileAdapterStateBoundaryImpact {
    /** Saving is observational, but restoring that capture will start disconnected. */
    SAVED_WITH_NON_RESTORABLE_IO,

    /** Live host work was cancelled and no state operation recreated it. */
    DISCONNECTED_NOT_RESTORED,
  }

  /** Typed privacy-safe notice used by desktop state/load/rewind presentation. */
  data class MobileAdapterStateBoundaryEvent(
      val boundary: MobileAdapterStateBoundary,
      val impact: MobileAdapterStateBoundaryImpact,
  ) : Event

  /** Immutable, defensively copied configuration supplied to an offline Mobile Adapter. */
  class MobileAdapterConfiguration(
      val deviceId: Int,
      configuration: ByteArray,
      val policyRevision: Long = 0,
      val networkBackend: MobileAdapterNetworkBackend? = null,
      val runtimeNetworkConsent: Boolean = false,
      val runtimePrivateLocalDevelopment: Boolean = false,
  ) {
    private val configuration = configuration.clone()

    init {
      require(deviceId in 0..0x7f) { "Mobile Adapter device ID must fit in seven bits" }
      require(this.configuration.size == MOBILE_ADAPTER_CONFIGURATION_BYTES) {
        "Mobile Adapter configuration must contain $MOBILE_ADAPTER_CONFIGURATION_BYTES bytes"
      }
      require(policyRevision >= 0) { "Mobile Adapter policy revision must not be negative" }
      require(networkBackend != null || !runtimeNetworkConsent) {
        "An offline Mobile Adapter cannot carry runtime network consent"
      }
      require(networkBackend != null || !runtimePrivateLocalDevelopment) {
        "An offline Mobile Adapter cannot carry private/local development consent"
      }
    }

    fun copyBytes(): ByteArray = configuration.clone()

    companion object {
      const val MOBILE_ADAPTER_CONFIGURATION_BYTES = 256

      /**
       * Fresh deterministic Phase-351 offline defaults: device 08, the documented MA header,
       * zero-filled private area, and the complete 00..7f public test pattern.
       */
      @JvmStatic
      fun syntheticOffline(): MobileAdapterConfiguration {
        val bytes = ByteArray(MOBILE_ADAPTER_CONFIGURATION_BYTES)
        bytes[0] = 0x4d
        bytes[1] = 0x41
        bytes[2] = 0x81.toByte()
        for (index in 128 until MOBILE_ADAPTER_CONFIGURATION_BYTES) {
          bytes[index] = (index - 128).toByte()
        }
        return MobileAdapterConfiguration(0x08, bytes)
      }
    }
  }

  /**
   * Synchronous preparation seam for already-local bounded configuration. Implementations must not
   * perform network I/O and should map durable-store failures to [SerialPeripheralPreparationException].
   */
  fun interface MobileAdapterConfigurationProvider {
    fun load(): MobileAdapterConfiguration
  }

  /** Typed provider failure whose public surface cannot carry raw storage or configuration data. */
  class SerialPeripheralPreparationException(val error: SerialPeripheralError) :
      IllegalStateException(error.code)

  /**
   * Legacy adapter for selecting the Barcode Boy. Disabling it only clears the port when Barcode
   * Boy still owns the exclusive selection.
   */
  data class SetBarcodeBoyEvent(val enabled: Boolean) : Event

  /** Simulates swiping a card with the given 13-digit JAN-13 barcode on the Barcode Boy. */
  data class ScanBarcodeEvent(val barcode: String) : Event

  /** Legacy ownership-aware adapter for selecting the Game Boy Printer. */
  data class SetPrinterEvent(val enabled: Boolean) : Event

  /** Legacy ownership-aware adapter for selecting a simulated Trimble GPS receiver. */
  data class SetGpsReceiverEvent(val enabled: Boolean) : Event

  /**
   * Emitted each time the game prints a band on the Game Boy Printer. [argb] holds
   * [width]×[height] ARGB pixels (top row first, [width] is always 160). [topMargin] and
   * [bottomMargin] are the paper feed before/after the band in 1/16-tile units; a non-zero
   * [bottomMargin] ends the sheet.
   */
  class PrinterPrintEvent(
      val argb: IntArray,
      val width: Int,
      val height: Int,
      val topMargin: Int,
      val bottomMargin: Int,
      val exposure: Int,
  ) : Event

  data class ControllerState(val state: MachineState, val rom: Rom)

  companion object {
    /**
     * Benchmark resume interlock. Ordinary/pre-arm resumes retain the historical behavior;
     * once an arm is accepted, a matching policy decision must have been processed before the
     * measured core can run. Canonical audio is an accepted decision with requested=false;
     * rejection or lifecycle revocation is accepted=false and cannot resume the generation.
     */
    internal fun benchmarkResumePolicyAllows(
        benchmarkPolicyEnabled: Boolean,
        benchmarkArmed: Boolean,
        benchmarkCoreFrozen: Boolean,
        policyProcessed: Boolean,
        policyAccepted: Boolean,
        policyGenerationMatches: Boolean,
        policyRequested: Boolean,
        calendarEnabled: Boolean,
    ): Boolean {
      if (!benchmarkPolicyEnabled || !benchmarkArmed) {
        return true
      }
      if (benchmarkCoreFrozen || !policyProcessed || !policyAccepted
          || !policyGenerationMatches) {
        return false
      }
      return !policyRequested || calendarEnabled
    }

    fun createGameboyConfig(
      properties: EmulatorProperties,
      rom: Rom,
    ): Gameboy.GameboyConfiguration {
      val config = Gameboy.GameboyConfiguration(rom)
      val isDatel =
          rom.cartridgeProperties.has(CartridgeProperties.Feature.DATEL_CGB_HEADER)
      if (isDatel) {
        properties.applicationSettings.advanced.datelSlotRom?.let { path ->
          val file = path.toFile()
          if (file.isFile) {
            RomSourceSnapshot.open(path).use { source ->
              val image =
                  if (source.isArchive) {
                    val candidate =
                        source.candidates().firstOrNull()
                            ?: throw IllegalArgumentException(
                                "Configured Datel slot archive contains no ROM")
                    source.load(candidate.token())
                  } else {
                    source.loadSingle()
                  }
              config.setSlotRom(Rom(image))
            }
          }
        }
      }
      val hardwareProfile = getHardwareProfile(properties.system, rom)
      val bootstrapMode = properties.system.bootstrapMode
      require(bootstrapMode == Gameboy.BootstrapMode.SKIP || Bios.hasBundledBootRom(hardwareProfile)) {
        "Profile ${hardwareProfile.id()} has no bundled boot ROM; " +
            "select skip bootstrap before starting the session"
      }
      config.setHardwareProfile(hardwareProfile)
      config.setBootstrapMode(bootstrapMode)
      config.setExecutionMode(properties.system.executionMode)
      config.setSupportBatterySave(properties.saves.batterySavesEnabled)
      if (properties.overrides.forceInMemoryBattery && rom.type.isBattery) {
        // A local netplay child must never touch the host's sidecar, but its detached checkpoint
        // still needs the same battery-state presence as the service-free network peer target.
        config.setBatteryData(byteArrayOf())
      }
      config.setPlayerInputSource(properties.playerInputSource)
      if (!config.hardwareProfile.capabilities().superGameboyBorder() ||
          !rom.isSuperGameboyFlag) {
        config.setDisplaySgbBorder(false)
      } else {
        config.setDisplaySgbBorder(properties.display.showSgbBorder)
      }

      return config
    }

    fun getHardwareProfile(properties: SystemProperties, rom: Rom): HardwareProfile {
      val colorSelection =
          rom.gameboyColorFlag == Rom.GameboyColorFlag.CGB ||
              rom.gameboyColorFlag == Rom.GameboyColorFlag.UNIVERSAL ||
              rom.cartridgeProperties.has(CartridgeProperties.Feature.DATEL_CGB_HEADER)
      val persistedSelection =
          if (colorSelection) properties.cgbGamesSelection else properties.dmgGamesSelection
      val selected =
          properties.profileOverride
              ?: if (colorSelection) {
                if (properties.cgbGamesProfile.capabilities().superGameboyCommands() &&
                    !rom.isSuperGameboyFlag) {
                  HardwareProfileRegistry.CGB
                } else {
                  properties.cgbGamesProfile
                }
              } else {
                properties.dmgGamesProfile
              }
      return if (
          selected == HardwareProfileRegistry.CGB &&
              properties.profileOverride == null &&
              persistedSelection is ApplicationSettings.ProfileSelection.Auto &&
              rom.cartridgeProperties.has(CartridgeProperties.Feature.CGB0_REVISION)
      ) {
        HardwareProfileRegistry.CGB0
      } else {
        selected
      }
    }

    /** @deprecated Use getHardwareProfile. */
    @Deprecated("Use getHardwareProfile")
    fun getGameboyType(properties: SystemProperties, rom: Rom): GameboyType {
      return GameboyType.fromHardwareProfile(getHardwareProfile(properties, rom))
    }
  }
}
