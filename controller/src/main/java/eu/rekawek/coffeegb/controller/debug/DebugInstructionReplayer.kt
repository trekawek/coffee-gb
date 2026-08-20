package eu.rekawek.coffeegb.controller.debug

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.replay.ReplayInputSource
import eu.rekawek.coffeegb.controller.replay.ReplayRuntime
import eu.rekawek.coffeegb.controller.state.SessionSnapshot
import eu.rekawek.coffeegb.core.ExecutionMode
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryPosition
import eu.rekawek.coffeegb.core.joypad.InputTimelineObserver
import eu.rekawek.coffeegb.core.joypad.JoypadButtonMask
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource
import eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint
import eu.rekawek.coffeegb.core.serial.SerialEndpoint

/**
 * Reconstructs an instruction boundary in a service-isolated scratch session.
 *
 * The scratch event bus has no parent or host subscribers, its input and time sources are
 * deterministic, and its serial endpoint is never connected. Speculative ticks therefore cannot
 * publish audio/video/rumble/debug output or mutate live persistence. Only the final immutable
 * [SessionSnapshot] is returned to the owner for one atomic live restore.
 */
internal class DebugInstructionReplayer : AutoCloseable {
  internal data class Result(
      val snapshot: SessionSnapshot,
      val input: DebugCheckpointHistory.InputBaseline,
      val position: DebugHistoryPosition,
  )

  private data class Scratch(
      val source: Session,
      val input: ReplayInputSource,
      val session: Session,
  )

  private var scratch: Scratch? = null

  fun replay(
      liveSession: Session,
      plan: DebugCheckpointHistory.InstructionReplayPlan,
      frameTicks: Int,
  ): Result {
    require(frameTicks > 0)
    val isolated = scratchFor(liveSession)
    val gameboy = isolated.session.gameboy
    isolated.input.reset(plan.input.physical)
    val anchorPause =
        plan.pauseRuns.firstOrNull()?.takeIf { it.tickOffset == 0 }?.paused
            ?: throw IllegalStateException("Debug replay has no pause state at its anchor")
    // The checkpoint's pause timestamp belongs to the live TimeSource. Reanchor it to the
    // deterministic scratch clock while restoring so target capture cannot interpret the two
    // unrelated epochs as elapsed RTC time.
    plan.snapshot.restore(isolated.session, anchorPause)
    gameboy.seedDeterministicReplayInput(
        JoypadButtonMask.toButtons(plan.input.legacyMask),
        plan.input.physical,
    )

    var legacyMask = plan.input.legacyMask
    var inputIndex = 0
    var pauseIndex = 0
    var retirements = 0
    for (tickOffset in 0 until plan.maximumTicks) {
      while (pauseIndex < plan.pauseRuns.size &&
          plan.pauseRuns[pauseIndex].tickOffset == tickOffset) {
        gameboy.setCartridgeClockPaused(plan.pauseRuns[pauseIndex].paused)
        pauseIndex++
      }

      val tick = Math.addExact(plan.anchor.masterTick(), tickOffset.toLong())
      var physicalPhase = false
      while (inputIndex < plan.inputs.size && plan.inputs[inputIndex].tick == tick) {
        val input = plan.inputs[inputIndex++]
        require(input.changedMask != 0) { "Debug replay input has an empty change mask" }
        when (input.phase) {
          InputTimelineObserver.Phase.LEGACY_P1_BEFORE_TICK -> {
            require(!physicalPhase && input.player == 0) {
              "Debug replay legacy input is outside the pre-tick P1 phase"
            }
            require((legacyMask xor input.absoluteMask) == input.changedMask) {
              "Debug replay legacy input does not continue the retained timeline"
            }
            legacyMask = input.absoluteMask
            gameboy.applyDeterministicReplayLegacyInput(
                JoypadButtonMask.toButtons(legacyMask),
            )
          }
          InputTimelineObserver.Phase.PHYSICAL_JOYPAD_SAMPLE -> {
            physicalPhase = true
            require((isolated.input.mask(input.player) xor input.absoluteMask) ==
                input.changedMask) {
              "Debug replay physical input does not continue the retained timeline"
            }
            isolated.input.apply(input.player, input.absoluteMask)
          }
        }
      }

      val before = gameboy.debugRetirementSequence
      gameboy.tick()
      if (gameboy.debugRetirementSequence != before) {
        retirements++
        if (retirements == plan.targetRetirementOrdinal) {
          val completedTicks = tickOffset + 1
          val frameOffset = completedTicks / frameTicks
          val framePosition = completedTicks % frameTicks
          val position =
              DebugHistoryPosition(
                  Math.addExact(plan.anchor.masterTick(), completedTicks.toLong()),
                  Math.addExact(plan.anchor.frame(), frameOffset.toLong()),
                  framePosition,
              )
          return Result(
              SessionSnapshot.capture(isolated.session),
              DebugCheckpointHistory.InputBaseline(
                  legacyMask,
                  gameboy.sampledPlayerInput,
              ),
              position,
          )
        }
      }
    }
    throw IllegalStateException(
        "Retained debug replay did not reach instruction ${plan.targetRetirementOrdinal}",
    )
  }

  private fun scratchFor(liveSession: Session): Scratch {
    scratch?.takeIf { it.source === liveSession }?.let { return it }
    close()
    val input = ReplayInputSource()
    val configuration =
        liveSession.config
            .forDebugHistoryReplay(
                liveSession.gameboy,
                VirtualTimeSource(),
                input,
            )
            .setExecutionMode(ExecutionMode.ACCURACY)
    val serialEndpoint =
        when (liveSession.serialEndpoint) {
          SerialEndpoint.NULL_ENDPOINT -> SerialEndpoint.NULL_ENDPOINT
          is Peer2PeerSerialEndpoint -> Peer2PeerSerialEndpoint()
          else -> throw IllegalArgumentException("Unsupported debug replay serial endpoint")
        }
    val session =
        ReplayRuntime.session(
            configuration,
            restoreImmediately = true,
            serialEndpoint = serialEndpoint,
        )
    session.gameboy.enableDebugRetirementTracking()
    return Scratch(liveSession, input, session).also { scratch = it }
  }

  override fun close() {
    scratch?.session?.discardUnstarted()
    scratch = null
  }
}
