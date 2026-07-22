package eu.rekawek.coffeegb.swing

import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JCheckBox
import javax.swing.JList
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.UIManager

/** A list whose selected values are presented and manipulated as independent checkboxes. */
internal class CheckBoxList<T>(
    items: List<T>,
    private val textProvider: (T) -> String,
    private val toolTipProvider: (T) -> String? = { null },
) : JList<T>(createModel(items)) {

  var activeIndex: Int = if (items.isEmpty()) -1 else 0
    private set

  init {
    selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
    cellRenderer =
        ListCellRenderer { list, value, index, _, _ ->
          val checked = list.isSelectedIndex(index)
          val active = list.hasFocus() && index == activeIndex
          JCheckBox(textProvider(value), checked).apply {
            isOpaque = true
            isEnabled = list.isEnabled
            font = list.font
            foreground = if (checked || active) list.selectionForeground else list.foreground
            background = if (checked || active) list.selectionBackground else list.background
            toolTipText = toolTipProvider(value)
            border =
                if (active) UIManager.getBorder("List.focusCellHighlightBorder")
                else BorderFactory.createEmptyBorder(1, 1, 1, 1)
          }
        }

    inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), TOGGLE_ACTIVE)
    actionMap.put(
        TOGGLE_ACTIVE,
        object : AbstractAction() {
          override fun actionPerformed(event: ActionEvent) = toggleActive()
        },
    )
    inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), MOVE_UP)
    actionMap.put(
        MOVE_UP,
        object : AbstractAction() {
          override fun actionPerformed(event: ActionEvent) = moveActiveIndex(-1)
        },
    )
    inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), MOVE_DOWN)
    actionMap.put(
        MOVE_DOWN,
        object : AbstractAction() {
          override fun actionPerformed(event: ActionEvent) = moveActiveIndex(1)
        },
    )
  }

  override fun processMouseEvent(event: MouseEvent) {
    if (SwingUtilities.isLeftMouseButton(event)) {
      val index = locationToIndex(event.point)
      val bounds = if (index >= 0) getCellBounds(index, index) else null
      if (bounds?.contains(event.point) == true) {
        if (event.id == MouseEvent.MOUSE_PRESSED) {
          requestFocusInWindow()
          val previousActive = activeIndex
          activeIndex = index
          // The second press of a double-click must not immediately undo the first one.
          if (event.clickCount == 1) {
            toggleIndex(index)
          }
          if (previousActive >= 0) {
            repaint(getCellBounds(previousActive, previousActive))
          }
          repaint(bounds)
        }
        // Do not let BasicListUI replace all checked rows with the clicked row.
        event.consume()
        return
      }
    }
    super.processMouseEvent(event)
  }

  fun moveActiveIndex(offset: Int) {
    if (model.size == 0) {
      return
    }
    val previousActive = activeIndex
    activeIndex =
        if (activeIndex < 0) {
          if (offset > 0) 0 else model.size - 1
        } else {
          (activeIndex + offset).coerceIn(0, model.size - 1)
        }
    if (previousActive >= 0) {
      repaint(getCellBounds(previousActive, previousActive))
    }
    repaint(getCellBounds(activeIndex, activeIndex))
    ensureIndexIsVisible(activeIndex)
  }

  fun toggleActive() {
    toggleIndex(activeIndex)
  }

  private fun toggleIndex(index: Int) {
    if (index !in 0 until model.size) {
      return
    }
    if (isSelectedIndex(index)) {
      removeSelectionInterval(index, index)
    } else {
      addSelectionInterval(index, index)
    }
    repaint(getCellBounds(index, index))
  }

  private companion object {
    const val TOGGLE_ACTIVE = "checkbox-list-toggle-active"
    const val MOVE_UP = "checkbox-list-move-up"
    const val MOVE_DOWN = "checkbox-list-move-down"

    fun <T> createModel(items: List<T>): DefaultListModel<T> =
        DefaultListModel<T>().apply { items.forEach(::addElement) }
  }
}
