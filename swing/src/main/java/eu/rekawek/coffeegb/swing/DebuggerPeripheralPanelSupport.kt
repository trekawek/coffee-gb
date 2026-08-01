package eu.rekawek.coffeegb.swing

import java.awt.Component
import java.awt.Container
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.IdentityHashMap
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel

internal class DebuggerPeripheralTableModel(
    private val columns: List<String>,
) : AbstractTableModel() {
  private var rows: List<List<Any>> = emptyList()

  override fun getRowCount(): Int = rows.size

  override fun getColumnCount(): Int = columns.size

  override fun getColumnName(column: Int): String = columns[column]

  override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rows[rowIndex][columnIndex]

  fun replace(nextRows: List<List<Any>>) {
    require(nextRows.all { it.size == columns.size }) {
      "Peripheral debugger table rows must match the column count"
    }
    rows = nextRows.map { it.toList() }
    fireTableDataChanged()
  }

  /**
   * Installs rows that were already defensively frozen by a worker-side presentation boundary.
   * This avoids copying thousands of graphics cells on the EDT for every live capture.
   */
  fun replacePrepared(nextRows: List<List<Any>>) {
    require(nextRows.all { it.size == columns.size }) {
      "Prepared peripheral debugger table rows must match the column count"
    }
    rows = nextRows
    fireTableDataChanged()
  }

  fun clear() {
    if (rows.isEmpty()) return
    rows = emptyList()
    fireTableDataChanged()
  }

  fun copyText(selectedRows: IntArray): String {
    val indexes =
        selectedRows
            .asSequence()
            .filter { it in rows.indices }
            .distinct()
            .sorted()
            .toList()
            .ifEmpty { rows.indices.toList() }
    return buildString {
          append(columns.joinToString("\t"))
          indexes.forEach { index ->
            append('\n')
            append(rows[index].joinToString("\t", transform = ::copyableCellText))
          }
        }
        .trimEnd()
  }
}

internal fun configurePeripheralTable(
    table: JTable,
    accessibleName: String,
    accessibleDescription: String,
    copyText: () -> String,
    copyToClipboard: (String) -> Unit = ::defaultPeripheralClipboard,
) {
  table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
  table.fillsViewportHeight = true
  table.autoCreateRowSorter = false
  table.autoResizeMode = JTable.AUTO_RESIZE_OFF
  table.rowSelectionAllowed = true
  table.columnSelectionAllowed = false
  table.accessibleContext.accessibleName = accessibleName
  table.accessibleContext.accessibleDescription = accessibleDescription
  table
      .getInputMap(JComponent.WHEN_FOCUSED)
      .put(
          KeyStroke.getKeyStroke(KeyEvent.VK_C, peripheralMenuShortcutMask()),
          PERIPHERAL_COPY_ACTION,
      )
  table.actionMap.put(
      PERIPHERAL_COPY_ACTION,
      object : AbstractAction() {
        override fun actionPerformed(event: ActionEvent?) {
          val text = copyText()
          if (text.isNotBlank()) copyToClipboard(text)
        }
      },
  )
}

internal fun peripheralTextArea(accessibleName: String): JTextArea =
    JTextArea(7, 70).apply {
      isEditable = false
      lineWrap = true
      wrapStyleWord = true
      font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
      getAccessibleContext().accessibleName = accessibleName
      getAccessibleContext().accessibleDescription = "Copyable textual $accessibleName"
      text = "No peripheral inspection loaded"
      caretPosition = 0
    }

internal fun requirePeripheralEdt(action: String) {
  check(SwingUtilities.isEventDispatchThread()) { "$action must run on the Swing EDT" }
}

internal class DebuggerPeripheralFontScaler(root: JComponent) {
  private data class FontBaseline(
      val font: Font,
      val rowHeight: Int?,
      val columnWidths: IntArray?,
  )

  private val baselines = IdentityHashMap<Component, FontBaseline>()

  init {
    capture(root)
  }

  private fun capture(component: Component) {
      component.font?.let { font ->
        val table = component as? JTable
        baselines[component] =
            FontBaseline(
                font,
                table?.rowHeight,
                table?.let { value ->
                  IntArray(value.columnModel.columnCount) { index ->
                    value.columnModel.getColumn(index).preferredWidth
                  }
                },
            )
      }
      (component as? Container)?.components?.forEach(::capture)
  }

  fun resetToBaseline() {
    baselines.forEach { (component, baseline) ->
      component.font = baseline.font
      if (component is JTable && baseline.rowHeight != null) {
        component.rowHeight = baseline.rowHeight
        baseline.columnWidths?.forEachIndexed { index, width ->
          if (index < component.columnModel.columnCount) {
            component.columnModel.getColumn(index).preferredWidth = width
            component.columnModel.getColumn(index).width = width
          }
        }
      }
    }
  }

  fun recapture(root: JComponent) {
    baselines.clear()
    capture(root)
  }

  fun apply(scalePercent: Int) {
    val factor =
        scalePercent.coerceIn(
            DebuggerUiPreferences.MIN_FONT_SCALE_PERCENT,
            DebuggerUiPreferences.MAX_FONT_SCALE_PERCENT,
        ) / 100f
    baselines.forEach { (component, baseline) ->
      component.font = baseline.font.deriveFont((baseline.font.size2D * factor).coerceAtLeast(1f))
      if (component is JTable && baseline.rowHeight != null) {
        component.rowHeight = (baseline.rowHeight * factor).toInt().coerceAtLeast(1)
        baseline.columnWidths?.forEachIndexed { index, width ->
          if (index < component.columnModel.columnCount) {
            val scaled = (width * factor).toInt().coerceAtLeast(1)
            component.columnModel.getColumn(index).preferredWidth = scaled
            component.columnModel.getColumn(index).width = scaled
          }
        }
      }
    }
  }
}

internal fun peripheralMenuShortcutMask(): Int =
    runCatching { Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx }
        .getOrDefault(InputEvent.CTRL_DOWN_MASK)

private fun copyableCellText(value: Any): String =
    value.toString().replace('\t', ' ').replace('\n', ' ')

private fun defaultPeripheralClipboard(value: String) {
  val selection = StringSelection(value)
  Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
}

private const val PERIPHERAL_COPY_ACTION = "debugger-peripheral-copy"
