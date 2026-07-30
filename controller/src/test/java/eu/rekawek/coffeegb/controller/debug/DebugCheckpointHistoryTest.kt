package eu.rekawek.coffeegb.controller.debug

import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryConfiguration
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryPosition
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryTruncationReason
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent
import eu.rekawek.coffeegb.core.joypad.InputTimelineObserver
import eu.rekawek.coffeegb.core.joypad.JoypadButtonMask
import eu.rekawek.coffeegb.core.joypad.PlayerInputSnapshot
import eu.rekawek.coffeegb.core.joypad.PlayerInputSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class DebugCheckpointHistoryTest {
  @Test
  fun `disabled history performs no capture work`() {
    StateCodecTestSupport.session().use { session ->
      val history = DebugCheckpointHistory()

      repeat(20) { frame -> history.recordFrame(session, frame.toLong(), frame.toLong()) }

      assertFalse(history.enabled)
      assertEquals(0, history.captureCount)
      assertEquals(0, history.checkpointCount)
      assertEquals(
          DebugCheckpointHistory.RestoreOutcome.Disabled,
          history.restorePreviousFrame(session, true, true),
      )
    }
  }

  @Test
  fun `frame budget is exact and boundary reverse retains the original future`() {
    StateCodecTestSupport.session().use { session ->
      val history = DebugCheckpointHistory()
      val configuration =
          DebugHistoryConfiguration(
              true,
              2,
              DebugHistoryConfiguration.MAX_MEMORY_BUDGET_BYTES,
          )
      session.gameboy.addressSpace.setByte(TEST_ADDRESS, 0x10)
      history.configure(configuration, session, 0, 0, 0)

      session.gameboy.addressSpace.setByte(TEST_ADDRESS, 0x21)
      history.recordFrame(session, 10, 1)
      session.gameboy.addressSpace.setByte(TEST_ADDRESS, 0x32)
      history.recordFrame(session, 20, 2)

      val bounded = history.status()
      assertEquals(2, bounded.checkpointCount())
      assertEquals(1, bounded.evictedCheckpoints())
      assertEquals(DebugHistoryTruncationReason.FRAME_BUDGET, bounded.lastTruncationReason())
      assertEquals(1, bounded.oldest().frame())
      assertEquals(2, bounded.newest().frame())
      assertTrue(bounded.retainedBytes() in 1..configuration.memoryBudgetBytes())

      val restored =
          assertIs<DebugCheckpointHistory.RestoreOutcome.Restored>(
              history.restorePreviousFrame(session, atFrameBoundary = true, effectiveCartridgePause = true)
          )
      assertEquals(1, restored.point.frame())
      assertEquals(0x21, session.gameboy.addressSpace.getByte(TEST_ADDRESS))
      assertEquals(2, restored.status.checkpointCount())
      assertEquals(restored.point, restored.status.oldest())
      assertEquals(bounded.newest(), restored.status.newest())
      assertEquals(DebugHistoryPosition.atCheckpoint(restored.point), restored.status.cursor())
      assertEquals(1, restored.status.futureCheckpointCount())
      assertEquals(
          DebugHistoryTruncationReason.FRAME_BUDGET,
          restored.status.lastTruncationReason(),
      )
      assertTrue(history.hasFuture)

      assertEquals(
          DebugCheckpointHistory.RestoreOutcome.Exhausted,
          history.restorePreviousFrame(session, atFrameBoundary = true, effectiveCartridgePause = true),
      )
      assertEquals(0x21, session.gameboy.addressSpace.getByte(TEST_ADDRESS))

      assertTrue(history.invalidateFuture(session))
      val branched = history.status()
      assertEquals(1, branched.checkpointCount())
      assertEquals(restored.point, branched.newest())
      assertEquals(0, branched.futureCheckpointCount())
      assertEquals(DebugHistoryTruncationReason.BRANCH_INVALIDATED, branched.lastTruncationReason())
      assertFalse(history.hasFuture)
    }
  }

  @Test
  fun `memory budget evicts the oldest checkpoints and remains within the hard cap`() {
    val configuration =
        StateCodecTestSupport.configuration(
            StateCodecTestSupport.rom(cgb = true),
            GameboyType.CGB,
        )
    StateCodecTestSupport.session(configuration).use { session ->
      val history = DebugCheckpointHistory()
      val requested =
          DebugHistoryConfiguration(
              true,
              DebugHistoryConfiguration.MAX_FRAMES,
              DebugHistoryConfiguration.MIN_MEMORY_BUDGET_BYTES,
          )
      var status = history.configure(requested, session, 0, 0, 0)
      var frame = 1

      // Dirty every CGB work-RAM page so the minimum 8 MiB budget is crossed quickly while
      // retaining realistic structurally shared machine graphs.
      while (status.evictedCheckpoints() == 0L &&
          frame < DebugHistoryConfiguration.MAX_FRAMES) {
        session.gameboy.addressSpace.setByte(0xc123, frame)
        for (bank in 1..7) {
          session.gameboy.addressSpace.setByte(0xff70, bank)
          session.gameboy.addressSpace.setByte(0xd123, frame + bank)
        }
        history.recordFrame(session, frame.toLong(), frame.toLong())
        status = history.status()
        frame++
      }

      assertTrue(status.evictedCheckpoints() > 0, "history never crossed the 8 MiB cap")
      assertEquals(DebugHistoryTruncationReason.MEMORY_BUDGET, status.lastTruncationReason())
      assertTrue(status.checkpointCount() > 0)
      assertTrue(status.checkpointCount().toLong() < history.captureCount)
      assertEquals(
          history.captureCount,
          status.checkpointCount().toLong() + status.evictedCheckpoints(),
      )
      assertTrue(status.retainedBytes() <= requested.memoryBudgetBytes())
      assertTrue(status.oldest().checkpointId() > 1)
    }
  }

  @Test
  fun `partial frame reverse restores newest boundary without consuming it`() {
    StateCodecTestSupport.session().use { session ->
      val history = DebugCheckpointHistory()
      val configuration =
          DebugHistoryConfiguration(
              true,
              3,
              DebugHistoryConfiguration.MAX_MEMORY_BUDGET_BYTES,
          )
      session.gameboy.addressSpace.setByte(TEST_ADDRESS, 0x40)
      history.configure(configuration, session, 100, 5, 0)
      session.gameboy.addressSpace.setByte(TEST_ADDRESS, 0x51)
      history.recordFrame(session, 200, 6)
      history.onTickStarted(cartridgePaused = true)
      history.onTickCompleted(retiredInstruction = false, frameTicks = 100)
      session.gameboy.addressSpace.setByte(TEST_ADDRESS, 0x62)

      val before = history.status()
      val restored =
          assertIs<DebugCheckpointHistory.RestoreOutcome.Restored>(
              history.restorePreviousFrame(
                  session,
                  atFrameBoundary = false,
                  effectiveCartridgePause = true,
              ))

      assertEquals(before.newest(), restored.point)
      assertEquals(0x51, session.gameboy.addressSpace.getByte(TEST_ADDRESS))
      assertEquals(before.checkpointCount(), restored.status.checkpointCount())
      assertEquals(before.lastTruncationReason(), restored.status.lastTruncationReason())
      assertEquals(DebugHistoryPosition.atCheckpoint(restored.point), restored.status.cursor())
      assertEquals(0, restored.status.futureCheckpointCount())
      assertTrue(history.hasFuture, "a partial-frame future exists beyond the newest checkpoint")
    }
  }

  @Test
  fun `instruction plans target the previous retirement and preserve a partial frame future`() {
    StateCodecTestSupport.session().use { session ->
      val history = DebugCheckpointHistory()
      history.configure(DebugHistoryConfiguration.defaults(), session, 0, 0, 0)

      advanceHistory(history, frameTicks = 8, retired = booleanArrayOf(true, false, true, false))

      val latest = checkNotNull(history.planPreviousInstruction())
      assertEquals(0, latest.anchor.masterTick())
      assertEquals(2, latest.targetRetirementOrdinal)
      assertEquals(4, latest.maximumTicks)

      val latestPosition = DebugHistoryPosition(3, 0, 3)
      val reversed = history.commitInstructionReverse(latest, latestPosition)
      assertEquals(latestPosition, reversed.cursor())
      assertEquals(0, reversed.futureCheckpointCount())
      assertTrue(history.hasFuture, "future ticks need not contain a frame checkpoint")

      val previous = checkNotNull(history.planPreviousInstruction())
      assertEquals(latest.anchor, previous.anchor)
      assertEquals(1, previous.targetRetirementOrdinal)
      assertEquals(3, previous.maximumTicks)

      assertFailsWith<IllegalStateException> {
        history.commitInstructionReverse(latest, DebugHistoryPosition(1, 0, 1))
      }

      assertTrue(history.invalidateFuture(session))
      assertFalse(history.hasFuture)
      assertEquals(
          DebugHistoryTruncationReason.BRANCH_INVALIDATED,
          history.status().lastTruncationReason(),
      )
    }
  }

  @Test
  fun `instruction plans skip empty frames and exclude a retirement at the current boundary`() {
    StateCodecTestSupport.session().use { session ->
      val history = DebugCheckpointHistory()
      history.configure(DebugHistoryConfiguration.defaults(), session, 0, 0, 0)

      advanceHistory(history, frameTicks = 4, retired = booleanArrayOf(true, false, false, false))
      history.recordFrame(session)
      advanceHistory(history, frameTicks = 4, retired = BooleanArray(4))
      history.recordFrame(session)

      val acrossEmptyFrame = checkNotNull(history.planPreviousInstruction())
      assertEquals(0, acrossEmptyFrame.anchor.masterTick())
      assertEquals(1, acrossEmptyFrame.targetRetirementOrdinal)
      assertEquals(4, acrossEmptyFrame.maximumTicks)

      history.configure(DebugHistoryConfiguration.defaults(), session, 0, 0, 0)
      advanceHistory(history, frameTicks = 4, retired = booleanArrayOf(true, false, false, true))
      history.recordFrame(session)

      val precedingBoundaryRetirement = checkNotNull(history.planPreviousInstruction())
      assertEquals(0, precedingBoundaryRetirement.anchor.masterTick())
      assertEquals(1, precedingBoundaryRetirement.targetRetirementOrdinal)
      assertEquals(4, precedingBoundaryRetirement.maximumTicks)
    }
  }

  @Test
  fun `branching at an existing frame coordinate replaces the old checkpoint and its metadata`() {
    StateCodecTestSupport.session().use { session ->
      val history = DebugCheckpointHistory()
      session.gameboy.addressSpace.setByte(TEST_ADDRESS, 0x10)
      history.configure(DebugHistoryConfiguration.defaults(), session, 0, 0, 0)

      // The final tick retires an instruction, so replaying across the later empty frame lands
      // exactly on the already-captured frame-one coordinate.
      advanceHistory(history, frameTicks = 4, retired = booleanArrayOf(false, false, false, true))
      session.gameboy.addressSpace.setByte(TEST_ADDRESS, 0x44)
      history.recordFrame(session)
      val oldEqualCoordinate = checkNotNull(history.status().newest())
      session.eventBus.post(ButtonPressEvent(Button.A))

      advanceHistory(history, frameTicks = 4, retired = BooleanArray(4))
      session.gameboy.addressSpace.setByte(TEST_ADDRESS, 0x88)
      history.recordFrame(session)

      val originalPlan = checkNotNull(history.planPreviousInstruction())
      assertEquals(0, originalPlan.anchor.masterTick())
      assertEquals(1, originalPlan.targetRetirementOrdinal)
      assertEquals(4, originalPlan.maximumTicks)

      // Model the isolated replayer's live commit. Its target is at tick four but is deliberately
      // distinguishable from the old capture at that same coordinate.
      session.gameboy.addressSpace.setByte(TEST_ADDRESS, 0x4a)
      session.gameboy.seedDeterministicReplayInput(emptySet(), PlayerInputSnapshot.released())
      val target = DebugHistoryPosition(4, 1, 0)
      val retained = history.commitInstructionReverse(originalPlan, target)
      assertEquals(2, retained.futureCheckpointCount())
      assertTrue(history.hasFuture)

      assertTrue(history.invalidateFuture(session))
      val branched = history.status()
      val freshAnchor = checkNotNull(branched.newest())
      assertEquals(target, branched.cursor())
      assertEquals(2, branched.checkpointCount())
      assertEquals(0, branched.futureCheckpointCount())
      assertEquals(oldEqualCoordinate.masterTick(), freshAnchor.masterTick())
      assertEquals(oldEqualCoordinate.frame(), freshAnchor.frame())
      assertTrue(
          freshAnchor.checkpointId() > oldEqualCoordinate.checkpointId(),
          "the equal-coordinate original checkpoint must be replaced, not reused",
      )
      assertFalse(history.hasFuture)

      // Forward execution must append to the fresh anchor. The old branch recorded A at tick four;
      // the new branch records only B and carries its own retirement count.
      session.eventBus.post(ButtonPressEvent(Button.B))
      advanceHistory(history, frameTicks = 4, retired = booleanArrayOf(true, false))
      session.gameboy.addressSpace.setByte(TEST_ADDRESS, 0x5b)

      val newBranchPlan = checkNotNull(history.planPreviousInstruction())
      assertEquals(freshAnchor, newBranchPlan.anchor)
      assertEquals(1, newBranchPlan.targetRetirementOrdinal)
      assertEquals(2, newBranchPlan.maximumTicks)
      assertEquals(0, newBranchPlan.input.legacyMask)
      assertEquals(
          listOf(
              DebugCheckpointHistory.InputRecord(
                  4,
                  InputTimelineObserver.Phase.LEGACY_P1_BEFORE_TICK,
                  0,
                  JoypadButtonMask.fromButtons(setOf(Button.B)),
                  JoypadButtonMask.fromButtons(setOf(Button.B)),
              )),
          newBranchPlan.inputs,
      )

      newBranchPlan.snapshot.restore(session, effectiveCartridgePause = true)
      assertEquals(
          0x4a,
          session.gameboy.addressSpace.getByte(TEST_ADDRESS),
          "reverse replay must start from the freshly captured target, not the stale checkpoint",
      )
    }
  }

  @Test
  fun `instruction plans carry deterministic input and cartridge pause transcripts`() {
    var physical = PlayerInputSnapshot.released()
    val configuration =
        StateCodecTestSupport.configuration()
            .setPlayerInputSource(PlayerInputSource { physical })
    StateCodecTestSupport.session(configuration).use { session ->
      val history = DebugCheckpointHistory()
      history.configure(DebugHistoryConfiguration.defaults(), session, 0, 0, 0)

      session.eventBus.post(ButtonPressEvent(Button.A))
      physical = playerInput(player = 1, Button.START)
      tick(session, history, cartridgePaused = true, retiredInstruction = true)
      tick(session, history, cartridgePaused = true, retiredInstruction = false)

      session.eventBus.post(ButtonReleaseEvent(Button.A))
      physical = PlayerInputSnapshot.released()
      tick(session, history, cartridgePaused = false, retiredInstruction = false)

      val plan = checkNotNull(history.planPreviousInstruction())
      assertEquals(
          listOf(
              DebugCheckpointHistory.PauseRun(0, true),
              DebugCheckpointHistory.PauseRun(2, false),
          ),
          plan.pauseRuns,
      )
      assertEquals(
          listOf(
              DebugCheckpointHistory.InputRecord(
                  0,
                  InputTimelineObserver.Phase.LEGACY_P1_BEFORE_TICK,
                  0,
                  JoypadButtonMask.fromButtons(setOf(Button.A)),
                  JoypadButtonMask.fromButtons(setOf(Button.A)),
              ),
              DebugCheckpointHistory.InputRecord(
                  0,
                  InputTimelineObserver.Phase.PHYSICAL_JOYPAD_SAMPLE,
                  1,
                  JoypadButtonMask.fromButtons(setOf(Button.START)),
                  JoypadButtonMask.fromButtons(setOf(Button.START)),
              ),
              DebugCheckpointHistory.InputRecord(
                  2,
                  InputTimelineObserver.Phase.LEGACY_P1_BEFORE_TICK,
                  0,
                  0,
                  JoypadButtonMask.fromButtons(setOf(Button.A)),
              ),
              DebugCheckpointHistory.InputRecord(
                  2,
                  InputTimelineObserver.Phase.PHYSICAL_JOYPAD_SAMPLE,
                  1,
                  0,
                  JoypadButtonMask.fromButtons(setOf(Button.START)),
              ),
          ),
          plan.inputs,
      )
    }
  }

  @Test
  fun `configuration while mid frame arms the first completed boundary`() {
    StateCodecTestSupport.session().use { session ->
      val history = DebugCheckpointHistory()
      val configured =
          history.configure(DebugHistoryConfiguration.defaults(), session, 7, 0, 7)
      assertEquals(0, configured.checkpointCount())
      assertEquals(0, history.captureCount)

      history.recordFrame(session, 100, 1)
      assertEquals(1, history.status().checkpointCount())
      assertEquals(1, history.captureCount)
      assertEquals(1, history.status().newest().frame())
    }
  }

  @Test
  fun `configuration reason distinguishes initial enable from replacement`() {
    StateCodecTestSupport.session().use { session ->
      val history = DebugCheckpointHistory()

      val initial = history.configure(DebugHistoryConfiguration.defaults(), session, 0, 0, 0)
      assertEquals(DebugHistoryTruncationReason.NONE, initial.lastTruncationReason())

      val reset = history.configure(DebugHistoryConfiguration.defaults(), session, 0, 0, 0)
      assertEquals(
          DebugHistoryTruncationReason.CONFIGURATION_CHANGED,
          reset.lastTruncationReason(),
      )

      val replacement =
          history.configure(
              DebugHistoryConfiguration(
                  true,
                  60,
                  DebugHistoryConfiguration.DEFAULT_MEMORY_BUDGET_BYTES,
              ),
              session,
              0,
              0,
              0,
          )
      assertEquals(
          DebugHistoryTruncationReason.CONFIGURATION_CHANGED,
          replacement.lastTruncationReason(),
      )

      val disabled =
          history.configure(DebugHistoryConfiguration.disabled(), session, 0, 0, 0)
      assertEquals(
          DebugHistoryTruncationReason.CONFIGURATION_CHANGED,
          disabled.lastTruncationReason(),
      )
      assertEquals(0, disabled.checkpointCount())
    }
  }

  @Test
  fun `input observer contention rejects a replacement without changing retained history`() {
    StateCodecTestSupport.session().use { currentSession ->
      StateCodecTestSupport.session().use { contestedSession ->
        val history = DebugCheckpointHistory()
        val initialConfiguration = DebugHistoryConfiguration.defaults()
        history.configure(initialConfiguration, currentSession, 0, 0, 0)
        currentSession.gameboy.addressSpace.setByte(TEST_ADDRESS, 0x42)
        history.recordFrame(currentSession, 10, 1)
        val before = history.status()
        val capturesBefore = history.captureCount
        val blocker = InputTimelineObserver { _, _, _, _ -> }
        val ownershipProbe = InputTimelineObserver { _, _, _, _ -> }
        assertTrue(contestedSession.gameboy.attachInputTimelineObserver(blocker))

        try {
          assertFailsWith<DebugHistorySessionBusyException> {
            history.configure(
                DebugHistoryConfiguration(
                    true,
                    60,
                    DebugHistoryConfiguration.DEFAULT_MEMORY_BUDGET_BYTES,
                ),
                contestedSession,
                20,
                2,
                0,
            )
          }

          assertEquals(before, history.status())
          assertEquals(capturesBefore, history.captureCount)
          assertTrue(history.enabled)
          assertFalse(
              currentSession.gameboy.attachInputTimelineObserver(ownershipProbe),
              "the original session observer must remain installed",
          )
        } finally {
          contestedSession.gameboy.detachInputTimelineObserver(blocker)
          history.disable(DebugHistoryTruncationReason.SESSION_BOUNDARY)
        }
      }
    }
  }

  @Test
  fun `repeated empty clears reuse a pristine retention ledger`() {
    StateCodecTestSupport.session().use { session ->
      val history = DebugCheckpointHistory()
      val pristineLedger = history.retentionLedgerIdentityForTesting

      repeat(3) { history.clear(DebugHistoryTruncationReason.TOPOLOGY_CHANGED) }
      assertSame(pristineLedger, history.retentionLedgerIdentityForTesting)

      history.configure(DebugHistoryConfiguration.defaults(), session, 0, 0, 0)
      val populatedLedger = history.retentionLedgerIdentityForTesting
      history.clear(DebugHistoryTruncationReason.USER_REWIND)
      val releasedLedger = history.retentionLedgerIdentityForTesting
      assertNotSame(populatedLedger, releasedLedger)

      history.clear(DebugHistoryTruncationReason.TOPOLOGY_CHANGED)
      assertSame(releasedLedger, history.retentionLedgerIdentityForTesting)
    }
  }

  private companion object {
    const val TEST_ADDRESS = 0xc123

    fun advanceHistory(
        history: DebugCheckpointHistory,
        frameTicks: Int,
        retired: BooleanArray,
    ) {
      retired.forEach {
        history.onTickStarted(cartridgePaused = true)
        history.onTickCompleted(retiredInstruction = it, frameTicks = frameTicks)
      }
    }

    fun tick(
        session: eu.rekawek.coffeegb.controller.Session,
        history: DebugCheckpointHistory,
        cartridgePaused: Boolean,
        retiredInstruction: Boolean,
    ) {
      history.onTickStarted(cartridgePaused)
      session.gameboy.tick()
      history.onTickCompleted(retiredInstruction, frameTicks = 100)
    }

    fun playerInput(player: Int, vararg buttons: Button): PlayerInputSnapshot =
        PlayerInputSnapshot.of(
            List(PlayerInputSource.PLAYER_COUNT) { index ->
              if (index == player) buttons.toSet() else emptySet()
            })
  }
}
