package eu.rekawek.coffeegb.swing

import java.awt.event.MouseEvent
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import org.junit.Test

class ToggleSelectionListTest {

  @Test
  fun singleClicksToggleOnlyTheClickedRows() =
      onEventThread {
        val list = createList()
        list.selectedIndex = 0

        click(list, 1)
        assertContentEquals(intArrayOf(0, 1), list.selectedIndices)

        click(list, 0)
        assertContentEquals(intArrayOf(1), list.selectedIndices)

        click(list, 2)
        assertContentEquals(intArrayOf(1, 2), list.selectedIndices)
        assertEquals(
            listOf("Infinite health", "Start at level 5"),
            list.selectedValuesList,
            "the OK handler receives every highlighted cheat",
        )
      }

  @Test
  fun secondPressOfADoubleClickDoesNotUndoTheSingleClickOrConfirmAnything() =
      onEventThread {
        val list = createList()

        click(list, 1)
        click(list, 1, 2)

        assertContentEquals(intArrayOf(1), list.selectedIndices)
      }

  @Test
  fun retainsTheNormalListRendererInsteadOfRenderingCheckboxes() =
      onEventThread {
        val list = createList()
        val component =
            list.cellRenderer.getListCellRendererComponent(
                list,
                "Infinite lives",
                0,
                true,
                true,
            )

        assertIs<JLabel>(component)
        assertFalse(component is JCheckBox)
      }

  private fun createList(): ToggleSelectionList<String> =
      ToggleSelectionList(
              listOf("Infinite lives", "Infinite health", "Start at level 5"),
          )
          .apply {
            fixedCellWidth = 240
            fixedCellHeight = 24
            setSize(240, 72)
          }

  private fun click(list: ToggleSelectionList<String>, index: Int, clickCount: Int = 1) {
    val bounds = list.getCellBounds(index, index)
    list.dispatchEvent(
        MouseEvent(
            list,
            MouseEvent.MOUSE_PRESSED,
            System.currentTimeMillis(),
            0,
            bounds.x + 4,
            bounds.y + bounds.height / 2,
            clickCount,
            false,
            MouseEvent.BUTTON1,
        ))
  }

  private fun onEventThread(block: () -> Unit) {
    SwingUtilities.invokeAndWait(block)
  }
}
