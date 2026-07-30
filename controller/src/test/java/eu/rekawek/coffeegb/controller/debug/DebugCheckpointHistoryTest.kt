package eu.rekawek.coffeegb.controller.debug

import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryConfiguration
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryTruncationReason
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
  fun `frame budget is exact and boundary reverse commits before discarding future`() {
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
      assertEquals(1, restored.status.checkpointCount())
      assertEquals(restored.point, restored.status.newest())
      assertEquals(
          DebugHistoryTruncationReason.REVERSE_STEP,
          restored.status.lastTruncationReason(),
      )

      assertEquals(
          DebugCheckpointHistory.RestoreOutcome.Exhausted,
          history.restorePreviousFrame(session, atFrameBoundary = true, effectiveCartridgePause = true),
      )
      assertEquals(0x21, session.gameboy.addressSpace.getByte(TEST_ADDRESS))
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
  }
}
