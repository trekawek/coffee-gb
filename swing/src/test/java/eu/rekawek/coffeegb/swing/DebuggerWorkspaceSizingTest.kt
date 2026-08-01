package eu.rekawek.coffeegb.swing

import java.awt.Dimension
import java.awt.Rectangle
import kotlin.test.assertEquals
import org.junit.Test

class DebuggerWorkspaceSizingTest {
  @Test
  fun `tool defaults match the usable debugger layouts`() {
    assertEquals(Dimension(1_600, 1_900), DebuggerWorkspaceTool.EXECUTION.preferredSize)
    assertEquals(Dimension(1_400, 1_100), DebuggerWorkspaceTool.MEMORY.preferredSize)
    assertEquals(Dimension(1_460, 1_420), DebuggerWorkspaceTool.BREAKPOINTS.preferredSize)
    assertEquals(Dimension(1_400, 1_660), DebuggerWorkspaceTool.VIDEO.preferredSize)
    assertEquals(Dimension(2_060, 1_200), DebuggerWorkspaceTool.HARDWARE.preferredSize)
    assertEquals(Dimension(1_880, 1_050), DebuggerWorkspaceTool.AUDIO.preferredSize)
    assertEquals(Dimension(1_800, 1_180), DebuggerWorkspaceTool.TIMELINE.preferredSize)
  }

  @Test
  fun `default sizes stay within the usable display work area`() {
    val screen = Rectangle(120, 80, 1_920, 1_080)

    assertEquals(
        Dimension(1_400, 1_032),
        debuggerToolDefaultSize(DebuggerWorkspaceTool.MEMORY, screen),
    )
    assertEquals(
        Dimension(1_872, 1_032),
        debuggerToolDefaultSize(DebuggerWorkspaceTool.AUDIO, screen),
    )
  }
}
