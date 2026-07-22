package eu.rekawek.coffeegb.swing

import java.awt.event.MouseEvent
import javax.swing.JCheckBox
import javax.swing.SwingUtilities
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class CheckBoxListTest {

  @Test
  fun mouseClicksToggleRowsWithoutClearingOtherChecks() =
      onEventThread {
        val list = createList()
        list.selectedIndex = 0

        click(list, 1)
        assertContentEquals(intArrayOf(0, 1), list.selectedIndices)

        click(list, 0)
        assertContentEquals(intArrayOf(1), list.selectedIndices)
      }

  @Test
  fun secondPressOfADoubleClickDoesNotUndoTheFirstPress() =
      onEventThread {
        val list = createList()

        click(list, 1)
        click(list, 1, 2)

        assertContentEquals(intArrayOf(1), list.selectedIndices)
      }

  @Test
  fun arrowsMoveTheActiveRowWithoutChangingChecksAndSpaceTogglesIt() =
      onEventThread {
        val list = createList()
        list.addSelectionInterval(0, 0)
        list.addSelectionInterval(2, 2)

        list.moveActiveIndex(1)
        assertEquals(1, list.activeIndex)
        assertContentEquals(intArrayOf(0, 2), list.selectedIndices)

        list.toggleActive()
        assertContentEquals(intArrayOf(0, 1, 2), list.selectedIndices)
        list.toggleActive()
        assertContentEquals(intArrayOf(0, 2), list.selectedIndices)
      }

  @Test
  fun rendererShowsSelectionAsACheckbox() =
      onEventThread {
        val list = createList()
        val renderer = list.cellRenderer

        val unchecked =
            renderer.getListCellRendererComponent(list, "Infinite lives", 0, false, false)
        assertIs<JCheckBox>(unchecked)
        assertFalse(unchecked.isSelected)
        assertEquals("Infinite lives", unchecked.text)
        assertEquals("code: Infinite lives", unchecked.toolTipText)

        list.selectedIndex = 0
        val checked =
            renderer.getListCellRendererComponent(list, "Infinite lives", 0, true, false)
        assertIs<JCheckBox>(checked)
        assertTrue(checked.isSelected)
      }

  private fun createList(): CheckBoxList<String> =
      CheckBoxList(
              listOf("Infinite lives", "Infinite health", "Start at level 5"),
              { it },
              { "code: $it" },
          )
          .apply {
            fixedCellWidth = 240
            fixedCellHeight = 24
            setSize(240, 72)
          }

  private fun click(list: CheckBoxList<String>, index: Int, clickCount: Int = 1) {
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
