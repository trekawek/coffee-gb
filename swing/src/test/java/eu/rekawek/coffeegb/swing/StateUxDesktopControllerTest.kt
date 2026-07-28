package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.state.StateOperation
import java.nio.file.Path
import java.util.concurrent.FutureTask
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.Test

class StateUxDesktopControllerTest {

  @Test
  fun `external quick save refreshes an open state browser without owning its status`() {
    assertEquals(
        StateBrowserCompletionAction.REFRESH_CATALOG,
        stateBrowserCompletionAction(StateOperation.SAVE, ownedByBrowser = false),
    )
    assertEquals(
        StateBrowserCompletionAction.IGNORE,
        stateBrowserCompletionAction(StateOperation.DELETE, ownedByBrowser = false),
    )
    assertEquals(
        StateBrowserCompletionAction.REFRESH_CATALOG,
        stateBrowserCompletionAction(StateOperation.DELETE, ownedByBrowser = true),
    )
    assertEquals(
        StateBrowserCompletionAction.UPDATE_SELECTION,
        stateBrowserCompletionAction(StateOperation.LOAD, ownedByBrowser = true),
    )
  }

  @Test
  fun `selectable path panel exposes normalized copyable path`() =
      onEdt {
        val path = Path.of("screenshots", "draft", "..", ".", "capture.png")
        val expected = path.toAbsolutePath().normalize().toString()

        val panel = selectablePathPanel("Screenshot saved", path)
        val message = panel.components.filterIsInstance<JLabel>().single()
        val field = panel.components.filterIsInstance<JTextField>().single()

        assertEquals("Screenshot saved", message.text)
        assertEquals(expected, field.text)
        assertFalse(field.isEditable)
        assertEquals("Copyable path", field.accessibleContext.accessibleName)

        field.selectAll()
        assertEquals(expected, field.selectedText)
      }

  private fun <T> onEdt(action: () -> T): T {
    val task = FutureTask(action)
    SwingUtilities.invokeAndWait(task)
    return task.get()
  }
}
