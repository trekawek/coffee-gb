package eu.rekawek.coffeegb.controller.replay

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.headless.HeadlessMachineSession
import eu.rekawek.coffeegb.controller.state.StateFile
import eu.rekawek.coffeegb.core.ExecutionMode
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.joypad.JoypadButtonMask
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource
import java.io.IOException

enum class ReplayPlaybackReason {
  INVALID_INITIAL_POSITION,
  INVALID_INPUT_TIMELINE,
  INVALID_EXECUTION_BUDGET,
  EXECUTION_BUDGET_EXCEEDED,
  INPUT_MASK_MISMATCH,
  WRONG_OWNER_THREAD,
  PLAYER_CLOSED,
}

class ReplayPlaybackException(
    val reason: ReplayPlaybackReason,
    message: String,
) : IOException(message)

data class ReplayPosition(
    /** Last executed replay tick, or null before the first tick. */
    val tick: Long?,
    val frame: Long,
)

enum class ReplaySubsystem {
  FULL,
  CPU,
  MEMORY,
  PPU,
  APU,
  MAPPER,
  SERIAL,
  INPUT,
}

data class ReplayDivergence(
    val tick: Long,
    val frame: Long,
    val expected: ReplayStateHashes,
    val actual: ReplayStateHashes,
    val mismatchedSubsystems: Set<ReplaySubsystem>,
)

sealed interface ReplayPlaybackStatus {
  val position: ReplayPosition

  data class Advanced(override val position: ReplayPosition) : ReplayPlaybackStatus

  data class Completed(override val position: ReplayPosition) : ReplayPlaybackStatus

  data class Diverged(
      override val position: ReplayPosition,
      val divergence: ReplayDivergence,
  ) : ReplayPlaybackStatus
}

/**
 * Deterministic, owner-thread CGBR player.
 *
 * Playback always owns a new isolated [Session]. The caller's configuration supplies ROM bytes and
 * immutable hardware choices only; live keyboard/gamepad input, wall time, battery storage, serial,
 * and infrared services never enter the replay machine.
 */
class ReplayPlayer private constructor(
    private val replay: ReplayFile,
    internal val machine: HeadlessMachineSession,
    private val inputSource: ReplayInputSource,
) : AutoCloseable {
  private val ownerThread = Thread.currentThread()
  private val finalCheckpoint = replay.checkpoints.last()

  private var nextInputIndex = 0
  private var nextCheckpointIndex = 0
  private var legacyMask = JoypadButtonMask.fromButtons(machine.session.heldButtons)
  private var terminal: ReplayPlaybackStatus? = null
  private var closed = false

  val position: ReplayPosition
    get() = machine.replayPosition

  /** Executes at most one replay tick and stops immediately after the first divergent checkpoint. */
  fun step(): ReplayPlaybackStatus {
    checkOwnerAndOpen()
    terminal?.let { return it }
    val nextTick = machine.nextReplayTick
    if (nextTick > finalCheckpoint.tick) {
      return complete()
    }

    applyInputs(nextTick)
    val executedTick = machine.tick()

    while (nextCheckpointIndex < replay.checkpoints.size &&
        replay.checkpoints[nextCheckpointIndex].tick == executedTick) {
      val expected = replay.checkpoints[nextCheckpointIndex++]
      val actual = machine.hashes()
      if (actual != expected.hashes) {
        val divergence =
            ReplayDivergence(
                expected.tick,
                expected.frame,
                expected.hashes,
                actual,
                mismatchedSubsystems(expected.hashes, actual),
            )
        return ReplayPlaybackStatus.Diverged(
                machine.replayPosition,
                divergence,
            )
            .also { terminal = it }
      }
    }

    return if (executedTick == finalCheckpoint.tick) {
      complete()
    } else {
      ReplayPlaybackStatus.Advanced(machine.replayPosition)
    }
  }

  /**
   * Runs until the final expected checkpoint, first divergence, or caller-supplied work budget.
   * Requiring a budget prevents a tiny untrusted replay from requesting unbounded synchronous CPU.
   */
  fun playToEnd(maximumTicks: Long): ReplayPlaybackStatus {
    checkOwnerAndOpen()
    terminal?.let { return it }
    if (maximumTicks <= 0L) {
      throw ReplayPlaybackException(
          ReplayPlaybackReason.INVALID_EXECUTION_BUDGET,
          "Replay execution budget must be positive",
      )
    }
    var executed = 0L
    while (executed < maximumTicks) {
      when (val status = step()) {
        is ReplayPlaybackStatus.Advanced -> executed++
        is ReplayPlaybackStatus.Completed -> return status
        is ReplayPlaybackStatus.Diverged -> return status
      }
    }
    throw ReplayPlaybackException(
        ReplayPlaybackReason.EXECUTION_BUDGET_EXCEEDED,
        "Replay did not terminate within the $maximumTicks-tick execution budget",
    )
  }

  override fun close() {
    if (!closed) {
      checkOwner()
      machine.close()
      closed = true
    }
  }

  private fun applyInputs(tick: Long) {
    while (nextInputIndex < replay.inputs.size && replay.inputs[nextInputIndex].tick == tick) {
      val record = replay.inputs[nextInputIndex]
      when (record.phase) {
        ReplayInputPhase.LEGACY_P1_BEFORE_TICK -> {
          requireTransition(record, legacyMask)
          legacyMask = record.absoluteMask
          machine.session.heldButtons = JoypadButtonMask.toButtons(legacyMask)
        }
        ReplayInputPhase.PHYSICAL_JOYPAD_SAMPLE -> {
          requireTransition(record, inputSource.mask(record.player))
          inputSource.apply(record.player, record.absoluteMask)
        }
      }
      nextInputIndex++
    }
  }

  private fun requireTransition(record: ReplayInputRecord, previousMask: Int) {
    if ((previousMask xor record.absoluteMask) != record.changedMask) {
      throw ReplayPlaybackException(
          ReplayPlaybackReason.INPUT_MASK_MISMATCH,
          "Replay input changed mask is inconsistent at tick ${record.tick}",
      )
    }
  }

  private fun complete(): ReplayPlaybackStatus.Completed =
      ReplayPlaybackStatus.Completed(
              ReplayPosition(finalCheckpoint.tick, finalCheckpoint.frame),
          )
          .also { terminal = it }

  private fun checkOwnerAndOpen() {
    checkOwner()
    if (closed) {
      throw ReplayPlaybackException(
          ReplayPlaybackReason.PLAYER_CLOSED,
          "Replay player is already closed",
      )
    }
  }

  private fun checkOwner() {
    if (Thread.currentThread() !== ownerThread) {
      throw ReplayPlaybackException(
          ReplayPlaybackReason.WRONG_OWNER_THREAD,
          "Replay player may only be used by its owner thread",
      )
    }
  }

  companion object {
    /** Validates portable identity before constructing the isolated target for semantic prepare. */
    fun open(
        replay: ReplayFile,
        configuration: Gameboy.GameboyConfiguration,
    ): ReplayPlayer {
      ReplayCompatibility.validateIdentity(replay.identity, configuration)
      ReplayCompatibility.validatePlayback(configuration)
      if (replay.initialConditions.initialTick != 0L ||
          replay.initialConditions.initialFrame != 0L) {
        throw ReplayPlaybackException(
            ReplayPlaybackReason.INVALID_INITIAL_POSITION,
            "CGBR v1 playback requires a frame-aligned zero-based initial position",
        )
      }
      validateTimelineShape(
          replay,
          configuration.clockSpec.controllerTicksPerFrame(),
      )
      val embedded = replay.embeddedState
      val embeddedState: StateFile? =
          embedded?.let { ReplayCompatibility.validateEmbeddedState(it, configuration) }

      val inputSource = ReplayInputSource()
      val isolated =
          ReplayRuntime.configuration(
              configuration,
              VirtualTimeSource(replay.initialConditions.rtcEpochMillis),
              inputSource,
              executionMode = ExecutionMode.ACCURACY,
          )
      val session =
          ReplayRuntime.session(
              isolated,
              restoreImmediately = replay.initialConditions.mode == ReplayInitialMode.EMBEDDED_SESSION_STATE,
          )
      try {
        if (embeddedState != null) {
          ReplayCompatibility.applyEmbeddedState(embeddedState, session)
        }
        return ReplayPlayer(
            replay,
            HeadlessMachineSession(
                session,
                replay.initialConditions.initialTick,
                replay.initialConditions.initialFrame,
            ),
            inputSource,
        )
      } catch (failure: Throwable) {
        try {
          session.close()
        } catch (closeFailure: Throwable) {
          failure.addSuppressed(closeFailure)
        }
        throw failure
      }
    }

    private fun validateTimelineShape(
        replay: ReplayFile,
        frameTicks: Int,
    ) {
      var tick = -1L
      var physicalPhase = false
      var physicalPlayers = 0
      var recordsAtTick = 0
      replay.inputs.forEach { record ->
        if (record.tick != tick) {
          tick = record.tick
          physicalPhase = false
          physicalPlayers = 0
          recordsAtTick = 0
        }
        recordsAtTick++
        if (recordsAtTick > ReplayLimits.MAX_INPUT_RECORDS_PER_TICK) {
          invalidTimeline(
              "Replay has more than ${ReplayLimits.MAX_INPUT_RECORDS_PER_TICK} inputs at tick $tick",
          )
        }
        if (record.changedMask == 0) {
          invalidTimeline("Replay input at tick ${record.tick} has an empty changed mask")
        }
        when (record.phase) {
          ReplayInputPhase.LEGACY_P1_BEFORE_TICK -> {
            if (record.player != 0) {
              invalidTimeline("Legacy replay input must target player zero")
            }
            if (physicalPhase) {
              invalidTimeline("Legacy replay input appears after a physical sample at tick $tick")
            }
          }
          ReplayInputPhase.PHYSICAL_JOYPAD_SAMPLE -> {
            physicalPhase = true
            val playerBit = 1 shl record.player
            if (physicalPlayers and playerBit != 0) {
              invalidTimeline(
                  "Physical replay input repeats player ${record.player} at tick $tick",
              )
            }
            physicalPlayers = physicalPlayers or playerBit
          }
        }
      }

      replay.checkpoints.forEachIndexed { index, checkpoint ->
        val relativeTick = checkpoint.tick - replay.initialConditions.initialTick
        val quotient = relativeTick / frameTicks.toLong()
        val remainder = relativeTick % frameTicks.toLong()
        val expectedFrame =
            try {
              Math.addExact(
                  replay.initialConditions.initialFrame,
                  quotient + if (remainder == frameTicks.toLong() - 1L) 1L else 0L,
              )
            } catch (_: ArithmeticException) {
              invalidTimeline("Replay checkpoint frame overflows at tick ${checkpoint.tick}")
            }
        if (checkpoint.frame != expectedFrame) {
          invalidTimeline(
              "Replay checkpoint at tick ${checkpoint.tick} declares frame ${checkpoint.frame}, " +
                  "expected $expectedFrame",
          )
        }
        if (index != replay.checkpoints.lastIndex &&
            remainder != frameTicks.toLong() - 1L) {
          invalidTimeline(
              "Non-final replay checkpoint at tick ${checkpoint.tick} is not frame-aligned",
          )
        }
      }
    }

    private fun invalidTimeline(message: String): Nothing =
        throw ReplayPlaybackException(ReplayPlaybackReason.INVALID_INPUT_TIMELINE, message)

    private fun mismatchedSubsystems(
        expected: ReplayStateHashes,
        actual: ReplayStateHashes,
    ): Set<ReplaySubsystem> =
        buildSet {
          if (!expected.full.contentEquals(actual.full)) add(ReplaySubsystem.FULL)
          if (!expected.cpu.contentEquals(actual.cpu)) add(ReplaySubsystem.CPU)
          if (!expected.memory.contentEquals(actual.memory)) add(ReplaySubsystem.MEMORY)
          if (!expected.ppu.contentEquals(actual.ppu)) add(ReplaySubsystem.PPU)
          if (!expected.apu.contentEquals(actual.apu)) add(ReplaySubsystem.APU)
          if (!expected.mapper.contentEquals(actual.mapper)) add(ReplaySubsystem.MAPPER)
          if (!expected.serial.contentEquals(actual.serial)) add(ReplaySubsystem.SERIAL)
          if (!expected.input.contentEquals(actual.input)) add(ReplaySubsystem.INPUT)
        }
  }
}
