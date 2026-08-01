package eu.rekawek.coffeegb.swing

import java.awt.Dimension
import java.awt.Rectangle
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class DebuggerWorkspaceSizingTest {
  @Test
  fun `tool defaults leave room for their primary controls and tables`() {
    assertTrue(DebuggerWorkspaceTool.EXECUTION.preferredSize.width >= 1_200)
    assertTrue(DebuggerWorkspaceTool.MEMORY.preferredSize.width >= 1_000)
    assertTrue(DebuggerWorkspaceTool.BREAKPOINTS.preferredSize.width >= 1_100)
    assertTrue(DebuggerWorkspaceTool.VIDEO.preferredSize.width >= 1_300)
    assertTrue(DebuggerWorkspaceTool.HARDWARE.preferredSize.width >= 1_500)
    assertTrue(DebuggerWorkspaceTool.AUDIO.preferredSize.width >= 1_900)
    assertTrue(DebuggerWorkspaceTool.TIMELINE.preferredSize.width >= 1_200)
  }

  @Test
  fun `default sizes stay within the usable display work area`() {
    val screen = Rectangle(120, 80, 1_920, 1_080)

    assertEquals(
        Dimension(1_080, 800),
        debuggerToolDefaultSize(DebuggerWorkspaceTool.MEMORY, screen),
    )
    assertEquals(
        Dimension(1_872, 900),
        debuggerToolDefaultSize(DebuggerWorkspaceTool.AUDIO, screen),
    )
  }
}
