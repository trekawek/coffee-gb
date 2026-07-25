package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.state.MachineSnapshot
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class RewindManagerTest {

  @Test
  fun capacityCadenceEvictionRewindAndClearRemainExact() {
    StateCodecTestSupport.session(configuration()).use { session ->
      val manager = RewindManager()
      var capturedIndex = 0
      var evictedSnapshot: MachineSnapshot? = null
      repeat((RewindManager.CAPACITY + EXTRA_CAPTURES) * RewindManager.RECORD_INTERVAL) { step ->
        if (step % RewindManager.RECORD_INTERVAL == 0) {
          session.gameboy.addressSpace.setByte(TEST_ADDRESS, capturedIndex and 0xff)
          capturedIndex++
        }
        manager.record(session.gameboy)
        if (manager.captureCount == 1) {
          evictedSnapshot = manager.snapshotsForTesting().single()
        }
      }

      assertEquals(RewindManager.CAPACITY, manager.historySize)
      assertEquals(RewindManager.CAPACITY + EXTRA_CAPTURES, manager.captureCount)
      assertTrue(manager.snapshotsForTesting().none { it === evictedSnapshot })
      for (index in capturedIndex - 1 downTo EXTRA_CAPTURES) {
        assertTrue(manager.rewindOneStep(session.gameboy))
        assertEquals(index and 0xff, session.gameboy.addressSpace.getByte(TEST_ADDRESS))
      }
      assertFalse(manager.rewindOneStep(session.gameboy))
      assertEquals(0, manager.historySize)
      val heldEvictedSnapshot = requireNotNull(evictedSnapshot)
      heldEvictedSnapshot.restore(session.gameboy)
      assertEquals(0, session.gameboy.addressSpace.getByte(TEST_ADDRESS))

      repeat(RewindManager.RECORD_INTERVAL * 3) { manager.record(session.gameboy) }
      assertTrue(manager.historySize > 0)
      manager.clear()
      assertEquals(0, manager.historySize)
      heldEvictedSnapshot.restore(session.gameboy)
      assertEquals(0, session.gameboy.addressSpace.getByte(TEST_ADDRESS))
      manager.record(session.gameboy)
      assertEquals(1, manager.historySize, "clear resets the six-frame cadence")
    }
  }

  @Test
  fun disabledManagerPerformsZeroCaptureOrCadenceWork() {
    StateCodecTestSupport.session(configuration()).use { session ->
      val manager = RewindManager(enabled = false)
      repeat(RewindManager.CAPACITY * RewindManager.RECORD_INTERVAL * 2) {
        manager.record(session.gameboy)
      }
      assertEquals(0, manager.captureCount)
      assertEquals(0, manager.historySize)
      assertFalse(manager.rewindOneStep(session.gameboy))
    }
  }

  @Test
  fun deterministicThreeHundredEntryWorkloadBeatsMeasuredMasterBaselineByHalf() {
    StateCodecTestSupport.session(configuration()).use { session ->
      val manager = RewindManager()
      session.gameboy.addressSpace.setByte(0x0000, 0x0a)
      repeat(RewindManager.CAPACITY * RewindManager.RECORD_INTERVAL) { frame ->
        emulateProductionFrame(session.gameboy, frame)
        manager.record(session.gameboy)
      }
      assertEquals(RewindManager.CAPACITY, manager.historySize)
      assertTrue(
          manager.snapshotsForTesting().all {
            it.captureStats.identityVerifiedPayloadArrays > 0 &&
                it.captureStats.identityVerifiedPayloadBytes > 0L
          },
          "production-cadence captures must identity-verify their live source payloads",
      )
      val retained = MachineSnapshot.retainedStats(manager.snapshotsForTesting())
      assertTrue(
          retained.retainedPrimitiveBytes * 2 < MASTER_BASELINE_RETAINED_PRIMITIVE_BYTES,
          "retained=${retained.retainedPrimitiveBytes}, " +
              "baseline=$MASTER_BASELINE_RETAINED_PRIMITIVE_BYTES",
      )
    }
  }

  private fun emulateProductionFrame(
      gameboy: Gameboy,
      frame: Int,
  ) {
    repeat(Gameboy.TICKS_PER_FRAME) { gameboy.tick() }
    val value = frame and 0xff
    gameboy.addressSpace.setByte(0xc000 + (frame * 37 and 0xfff), value)
    gameboy.addressSpace.setByte(0xd000 + (frame * 53 and 0xfff), value xor 0x5a)
    gameboy.gpu.videoRam0.setByte(0x8000 + (frame * 29 and 0x1fff), value xor 0xa5)
    gameboy.gpu.videoRam1.setByte(0x8000 + (frame * 31 and 0x1fff), value xor 0x3c)
    gameboy.addressSpace.setByte(0xfe00 + (frame % 0xa0), value)
    gameboy.addressSpace.setByte(0x4000, frame ushr 5 and 0x03)
    gameboy.addressSpace.setByte(0xa000 + (frame * 43 and 0x1fff), value xor 0xc3)
  }

  private fun configuration() =
      StateCodecTestSupport.configuration(
              StateCodecTestSupport.rom(cgb = true).also {
                it[0x147] = 0x1b
                it[0x149] = 0x03
              },
              GameboyType.CGB,
          )
          .setSupportBatterySave(false)

  companion object {
    /** Exact production-cadence primitive-array baseline measured on master 195d9172. */
    private const val MASTER_BASELINE_RETAINED_PRIMITIVE_BYTES = 337_665_600L
    private const val EXTRA_CAPTURES = 2
    private const val TEST_ADDRESS = 0xc100
  }
}
