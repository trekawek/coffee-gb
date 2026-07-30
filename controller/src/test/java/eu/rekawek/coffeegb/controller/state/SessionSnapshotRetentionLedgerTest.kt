package eu.rekawek.coffeegb.controller.state

import kotlin.test.assertEquals
import org.junit.Test

class SessionSnapshotRetentionLedgerTest {
  @Test
  fun `incremental retention equals whole collection identity accounting`() {
    StateCodecTestSupport.session().use { session ->
      val snapshots = ArrayList<SessionSnapshot>()
      val ledger = SessionSnapshot.RetentionLedger()

      repeat(4) { index ->
        session.gameboy.addressSpace.setByte(TEST_ADDRESS, 0x20 + index)
        val snapshot = SessionSnapshot.capture(session, snapshots.lastOrNull())
        snapshots += snapshot
        ledger.add(snapshot)
        assertEquals(SessionSnapshot.retainedBytes(snapshots), ledger.retainedBytes)
      }

      val first = snapshots.removeFirst()
      ledger.remove(first)
      assertEquals(SessionSnapshot.retainedBytes(snapshots), ledger.retainedBytes)

      val last = snapshots.removeLast()
      ledger.remove(last)
      assertEquals(SessionSnapshot.retainedBytes(snapshots), ledger.retainedBytes)

      snapshots.toList().forEach {
        snapshots.remove(it)
        ledger.remove(it)
        assertEquals(SessionSnapshot.retainedBytes(snapshots), ledger.retainedBytes)
      }
      assertEquals(0, ledger.retainedBytes)
    }
  }

  private companion object {
    const val TEST_ADDRESS = 0xc123
  }
}
