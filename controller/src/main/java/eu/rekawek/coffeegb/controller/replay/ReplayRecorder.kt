package eu.rekawek.coffeegb.controller.replay

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateCompression
import eu.rekawek.coffeegb.core.ExecutionMode
import eu.rekawek.coffeegb.core.joypad.InputTimelineObserver
import eu.rekawek.coffeegb.core.joypad.PlayerInputSource
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource
import java.io.IOException

/** Recorder failures that are not compatibility failures. */
enum class ReplayRecordingReason {
  SENSITIVE_STATE_CONSENT_REQUIRED,
  BOOT_REFERENCE_REQUIRES_FRESH_SESSION,
  CAPTURE_ALREADY_ACTIVE,
  LEGACY_INPUT_DURING_TICK,
  INPUT_LIMIT_EXCEEDED,
  INPUT_RATE_LIMIT_EXCEEDED,
  CHECKPOINT_LIMIT_EXCEEDED,
  NO_EXECUTED_TICKS,
  WRONG_OWNER_THREAD,
  RECORDER_CLOSED,
}

class ReplayRecordingException(
    val reason: ReplayRecordingReason,
    message: String,
) : IOException(message)

/**
 * Explicit replay-capture policy.
 *
 * A boot reference is privacy-safe and is therefore the default. Embedding a StateFile can include
 * cartridge RAM and other full machine state, so [includeSensitiveInitialState] must be set by an
 * informed caller rather than inferred from [initialMode].
 */
data class ReplayRecordingOptions(
    val initialMode: ReplayInitialMode = ReplayInitialMode.BOOT_REFERENCE,
    val includeSensitiveInitialState: Boolean = false,
    val checkpointIntervalFrames: Int = DEFAULT_CHECKPOINT_INTERVAL_FRAMES,
    val rtcEpochMillis: Long = DEFAULT_RTC_EPOCH_MILLIS,
    val metadata: ReplayMetadata? = null,
) {
  init {
    require(checkpointIntervalFrames > 0) { "Replay checkpoint interval must be positive" }
  }

  companion object {
    const val DEFAULT_CHECKPOINT_INTERVAL_FRAMES = 60
    const val DEFAULT_RTC_EPOCH_MILLIS = 946_684_800_000L
  }
}

/**
 * Owner-thread deterministic recorder.
 *
 * The recorder owns calls to [tick] for its lifetime. Legacy events may be posted between calls;
 * physical input is sampled by the ordinary joypad path during [tick]. Both reach the same core
 * [InputTimelineObserver] and are stamped with the next zero-based executed tick.
 */
class ReplayRecorder private constructor(
    private val session: Session,
    private val options: ReplayRecordingOptions,
    private val identity: ReplayIdentity,
    private val embeddedState: ByteArray?,
    private val reservationToken: Any,
) : AutoCloseable {
  private val ownerThread = Thread.currentThread()
  private val frameTicks = session.gameboy.clockSpec.controllerTicksPerFrame()
  private val inputs = ArrayList<ReplayInputRecord>()
  private val checkpoints = ArrayList<ReplayCheckpoint>()

  private var completedTicks = 0L
  private var completedFrames = 0L
  @Volatile private var closed = false
  @Volatile private var pendingFailure: ReplayRecordingException? = null
  private var tickInProgress = false
  private var countedInputTick = -1L
  private var inputRecordsAtTick = 0

  private val observer =
      InputTimelineObserver { phase, player, buttonMask, changedMask ->
        if (Thread.currentThread() !== ownerThread) {
          fail(
              ReplayRecordingReason.WRONG_OWNER_THREAD,
              "Replay input arrived outside the recorder owner thread",
          )
          return@InputTimelineObserver
        }
        if (closed || pendingFailure != null) {
          return@InputTimelineObserver
        }
        if (phase == InputTimelineObserver.Phase.LEGACY_P1_BEFORE_TICK && tickInProgress) {
          fail(
              ReplayRecordingReason.LEGACY_INPUT_DURING_TICK,
              "Legacy replay input must be posted between recorder tick calls",
          )
          return@InputTimelineObserver
        }
        if (inputs.size == ReplayLimits.MAX_INPUT_RECORDS) {
          fail(
              ReplayRecordingReason.INPUT_LIMIT_EXCEEDED,
              "Replay input count exceeds ${ReplayLimits.MAX_INPUT_RECORDS}",
          )
          return@InputTimelineObserver
        }
        if (countedInputTick != completedTicks) {
          countedInputTick = completedTicks
          inputRecordsAtTick = 0
        }
        if (inputRecordsAtTick == ReplayLimits.MAX_INPUT_RECORDS_PER_TICK) {
          fail(
              ReplayRecordingReason.INPUT_RATE_LIMIT_EXCEEDED,
              "Replay input count at tick $completedTicks exceeds " +
                  ReplayLimits.MAX_INPUT_RECORDS_PER_TICK,
          )
          return@InputTimelineObserver
        }
        inputs +=
            ReplayInputRecord(
                completedTicks,
                when (phase) {
                  InputTimelineObserver.Phase.LEGACY_P1_BEFORE_TICK ->
                      ReplayInputPhase.LEGACY_P1_BEFORE_TICK
                  InputTimelineObserver.Phase.PHYSICAL_JOYPAD_SAMPLE ->
                      ReplayInputPhase.PHYSICAL_JOYPAD_SAMPLE
                },
                player,
                buttonMask,
                changedMask,
            )
        inputRecordsAtTick++
      }

  init {
    if (!session.gameboy.attachInputTimelineObserver(observer)) {
      throw ReplayRecordingException(
          ReplayRecordingReason.CAPTURE_ALREADY_ACTIVE,
          "The session input timeline is already owned by another capture",
      )
    }
  }

  val tickCount: Long
    get() = completedTicks

  val frameCount: Long
    get() = completedFrames

  /** Executes exactly one emulated master tick and records any transitions observed within it. */
  fun tick() {
    checkOwnerAndOpen()
    try {
      pendingFailure?.let { throw it }
      tickInProgress = true
      try {
        session.gameboy.tick()
      } finally {
        tickInProgress = false
      }
      completedTicks = Math.addExact(completedTicks, 1L)
      if (completedTicks % frameTicks.toLong() == 0L) {
        completedFrames = Math.addExact(completedFrames, 1L)
        if (completedFrames % options.checkpointIntervalFrames.toLong() == 0L) {
          captureCheckpoint()
        }
      }
      pendingFailure?.let { throw it }
    } catch (failure: Throwable) {
      detach()
      throw failure
    }
  }

  /** Executes one controller-frame tick budget without host pacing or live wall-clock reads. */
  fun frame() {
    checkOwnerAndOpen()
    repeat(frameTicks) { tick() }
  }

  /**
   * Detaches capture and returns an immutable replay. The last executed tick always has a final
   * checkpoint, independently of the periodic interval.
   */
  fun finish(): ReplayFile {
    checkOwnerAndOpen()
    try {
      pendingFailure?.let { throw it }
      if (completedTicks == 0L) {
        throw ReplayRecordingException(
            ReplayRecordingReason.NO_EXECUTED_TICKS,
            "A replay must execute at least one tick",
        )
      }
      val finalTick = completedTicks - 1L
      if (checkpoints.lastOrNull()?.tick != finalTick) {
        captureCheckpoint()
      }
      return ReplayFile(
          identity,
          ReplayInitialConditions(
              options.initialMode,
              options.rtcEpochMillis,
              initialTick = 0,
              initialFrame = 0,
          ),
          inputs,
          checkpoints,
          options.metadata,
          embeddedState,
      )
    } finally {
      detach()
    }
  }

  /** Discards the incomplete recording and removes the observer. */
  override fun close() {
    if (!closed) {
      checkOwner()
      detach()
    }
  }

  private fun captureCheckpoint() {
    if (checkpoints.size == ReplayLimits.MAX_CHECKPOINTS) {
      throw ReplayRecordingException(
          ReplayRecordingReason.CHECKPOINT_LIMIT_EXCEEDED,
          "Replay checkpoint count exceeds ${ReplayLimits.MAX_CHECKPOINTS}",
      )
    }
    checkpoints +=
        ReplayCheckpoint(
            tick = completedTicks - 1L,
            frame = completedFrames,
            hashes = ReplayStateHasher.hash(session),
        )
  }

  private fun fail(reason: ReplayRecordingReason, message: String) {
    if (pendingFailure == null) {
      pendingFailure = ReplayRecordingException(reason, message)
    }
  }

  private fun detach() {
    if (!closed) {
      closed = true
      try {
        session.gameboy.detachInputTimelineObserver(observer)
      } finally {
        session.releaseDeterministicCapture(reservationToken)
      }
    }
  }

  private fun checkOwnerAndOpen() {
    checkOwner()
    if (closed) {
      throw ReplayRecordingException(
          ReplayRecordingReason.RECORDER_CLOSED,
          "Replay recorder is already closed",
      )
    }
  }

  private fun checkOwner() {
    if (Thread.currentThread() !== ownerThread) {
      throw ReplayRecordingException(
          ReplayRecordingReason.WRONG_OWNER_THREAD,
          "Replay recorder may only be used by its owner thread",
      )
    }
  }

  companion object {
    /** Performs every compatibility/privacy preflight before installing a live observer. */
    fun start(
        session: Session,
        options: ReplayRecordingOptions = ReplayRecordingOptions(),
    ): ReplayRecorder {
      val reservationToken = Any()
      if (!session.reserveDeterministicCapture(reservationToken)) {
        throw ReplayRecordingException(
            ReplayRecordingReason.CAPTURE_ALREADY_ACTIVE,
            "The session is already closed or owned by another deterministic capture",
        )
      }
      try {
        if (options.initialMode == ReplayInitialMode.EMBEDDED_SESSION_STATE &&
            !options.includeSensitiveInitialState) {
          throw ReplayRecordingException(
              ReplayRecordingReason.SENSITIVE_STATE_CONSENT_REQUIRED,
              "Embedding a session StateFile requires explicit sensitive-state consent",
          )
        }
        ReplayCompatibility.validateRecording(session, options.initialMode)
        if (options.initialMode == ReplayInitialMode.BOOT_REFERENCE) {
          validateBootReference(session, options.rtcEpochMillis)
        }
        val embeddedState =
            if (options.initialMode == ReplayInitialMode.EMBEDDED_SESSION_STATE) {
              StateCodec.encode(StateCodec.captureVersion2(session), StateCompression.NONE)
            } else {
              null
            }
        return ReplayRecorder(
            session,
            options,
            ReplayCompatibility.identity(session.config),
            embeddedState,
            reservationToken,
        )
      } catch (failure: Throwable) {
        session.releaseDeterministicCapture(reservationToken)
        throw failure
      }
    }

    private fun validateBootReference(session: Session, rtcEpochMillis: Long) {
      val isolated =
          ReplayRuntime.configuration(
              session.config,
              VirtualTimeSource(rtcEpochMillis),
              PlayerInputSource.RELEASED,
              executionMode = ExecutionMode.ACCURACY,
          )
      val expected =
          ReplayRuntime.session(isolated, restoreImmediately = false).use { cleanSession ->
            StateCodec.encode(
                StateCodec.captureVersion2(cleanSession),
                StateCompression.NONE,
            )
          }
      val current =
          StateCodec.encode(
              StateCodec.captureVersion2(session),
              StateCompression.NONE,
          )
      if (!current.contentEquals(expected)) {
        throw ReplayRecordingException(
            ReplayRecordingReason.BOOT_REFERENCE_REQUIRES_FRESH_SESSION,
            "A privacy-safe boot reference can only record a fresh, battery-free session; " +
                "explicitly opt in to an embedded initial state for an in-progress session",
        )
      }
    }
  }
}
