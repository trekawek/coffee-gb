package eu.rekawek.coffeegb.swing

import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/** A normal multi-select list where an unmodified click toggles only the clicked row. */
internal class ToggleSelectionList<T>(items: List<T>) : JList<T>(createModel(items)) {

  init {
    selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
  }

  override fun processMouseEvent(event: MouseEvent) {
    if (SwingUtilities.isLeftMouseButton(event)) {
      val index = locationToIndex(event.point)
      val bounds = if (index >= 0) getCellBounds(index, index) else null
      if (bounds?.contains(event.point) == true) {
        if (event.id == MouseEvent.MOUSE_PRESSED && event.clickCount == 1) {
          requestFocusInWindow()
          if (isSelectedIndex(index)) {
            removeSelectionInterval(index, index)
          } else {
            addSelectionInterval(index, index)
          }
        }
        // Do not let BasicListUI clear the other selected rows or treat a double-click as OK.
        event.consume()
        return
      }
    }
    super.processMouseEvent(event)
  }

  private companion object {
    fun <T> createModel(items: List<T>): DefaultListModel<T> =
        DefaultListModel<T>().apply { items.forEach(::addElement) }
  }
}
