package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.debug.DebugAddressSpace
import eu.rekawek.coffeegb.core.debug.DebugApuState
import eu.rekawek.coffeegb.core.debug.DebugCapabilities
import eu.rekawek.coffeegb.core.debug.DebugCpuState
import eu.rekawek.coffeegb.core.debug.DebugExecutionState
import eu.rekawek.coffeegb.core.debug.DebugFeatureState
import eu.rekawek.coffeegb.core.debug.DebugInterruptState
import eu.rekawek.coffeegb.core.debug.DebugMapperState
import eu.rekawek.coffeegb.core.debug.DebugMemoryBlock
import eu.rekawek.coffeegb.core.debug.DebugPpuMode
import eu.rekawek.coffeegb.core.debug.DebugPpuState
import eu.rekawek.coffeegb.core.debug.DebugRegisters
import eu.rekawek.coffeegb.core.debug.DebugSnapshot
import eu.rekawek.coffeegb.core.debug.DebugTimerState
import javax.swing.SwingUtilities
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test

class DebuggerExecutionPanelTest {

  @Test
  fun `instruction context keeps the program counter visible at safe-view edges`() {
    val romStart = assertNotNull(pcExecutionMemoryRequest(0x0000, 40))
    val romEnd = assertNotNull(pcExecutionMemoryRequest(0x7fff, 40))
    val highRamEnd = assertNotNull(pcExecutionMemoryRequest(0xfffe, 40))

    assertEquals(0, romStart.offset())
    assertEquals(40, romStart.length())
    assertEquals(-12, romEnd.offset())
    assertEquals(13, romEnd.length())
    assertEquals(-12, highRamEnd.offset())
    assertEquals(13, highRamEnd.length())
  }

  @Test
  fun `execution view centers a bounded instruction context on the program counter`() =
      onEdt {
        val panel = DebuggerExecutionPanel {}
        panel.render(
            snapshot(pc = 0x0206),
            DebugMemoryBlock(
                DebugAddressSpace.ROM,
                0x0200,
                byteArrayOf(
                    0x00, // NOP
                    0x06, 0x12, // LD B,$12
                    0x21, 0x34, 0x12, // LD HL,$1234
                    0x3e, 0x56, // LD A,$56 (current PC)
                    0x00, // NOP
                    0x18, 0xfe.toByte(), // JR $FE
                    0x76, // HALT
                    0x00, // NOP
                ),
            ),
            null,
            DebugCapabilities(true, true, true, true, true, true, true, 256),
        )

        val table = panel.instructionTable
        val addresses = (0 until table.rowCount).map { row -> table.getValueAt(row, 0) }
        val currentRow = addresses.indexOf("${'$'}0206")

        assertEquals(listOf("${'$'}0200", "${'$'}0201", "${'$'}0203"), addresses.take(3))
        assertTrue(currentRow in 3 until table.rowCount)
        assertEquals(currentRow, table.selectedRow)
        assertContains(table.getValueAt(currentRow, 1).toString(), "3E 56")
        assertContains(panel.copyText(), "${'$'}0203")
        assertContains(panel.copyText(), "${'$'}020B")
      }

  private fun snapshot(pc: Int): DebugSnapshot =
      DebugSnapshot(
          1,
          2,
          3,
          4,
          0,
          true,
          DebugRegisters(0, 0, 0, 0, 0, 0, 0, 0, 0xc000, pc),
          DebugInterruptState(false, false, 0, 0, 0),
          DebugTimerState(0, 0, 0, 0, false, 0),
          DebugPpuState(true, DebugPpuMode.OAM_SEARCH, 0, 0, 0, 0, 0, 0, 0, 0, 0),
          DebugApuState(true, 0, false, false, false, false, 0, 0, 0),
          DebugMapperState(
              "test",
              -1,
              -1,
              DebugFeatureState.UNKNOWN,
              DebugFeatureState.UNKNOWN,
              DebugFeatureState.UNKNOWN,
          ),
          DebugExecutionState(DebugCpuState.HALTED, 0x76, -1, 0, false, false, 0),
      )

  private fun <T> onEdt(action: () -> T): T {
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(action) }
    return checkNotNull(result).getOrThrow()
  }
}
