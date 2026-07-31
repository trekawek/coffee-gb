package eu.rekawek.coffeegb.swing

import java.awt.Color
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
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import kotlin.math.pow

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

internal data class DebuggerPalettePreview(
    val hexColor: String,
    val rgb888: Int,
) {
  override fun toString(): String = hexColor
}

internal class DebuggerPalettePreviewRenderer : DefaultTableCellRenderer() {
  override fun getTableCellRendererComponent(
      table: JTable,
      value: Any?,
      isSelected: Boolean,
      hasFocus: Boolean,
      row: Int,
      column: Int,
  ): Component {
    val component =
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            as JLabel
    component.toolTipText = null
    val preview = value as? DebuggerPalettePreview
    if (preview == null || isSelected) return component
    val color = Color(preview.rgb888)
    component.isOpaque = true
    component.background = color
    component.foreground = contrastingTextColor(color)
    component.text = preview.hexColor
    component.toolTipText = "Palette preview ${preview.hexColor}; textual values are in this row"
    return component
  }

  private fun contrastingTextColor(color: Color): Color {
    val luminance = relativeLuminance(color)
    val blackContrast = (luminance + 0.05) / 0.05
    val whiteContrast = 1.05 / (luminance + 0.05)
    return if (blackContrast >= whiteContrast) Color.BLACK else Color.WHITE
  }

  private fun relativeLuminance(color: Color): Double =
      0.2126 * linearComponent(color.red) +
          0.7152 * linearComponent(color.green) +
          0.0722 * linearComponent(color.blue)

  private fun linearComponent(component: Int): Double {
    val srgb = component / 255.0
    return if (srgb <= 0.04045) srgb / 12.92 else ((srgb + 0.055) / 1.055).pow(2.4)
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
      accessibleContext.accessibleName = accessibleName
      accessibleContext.accessibleDescription = "Copyable textual $accessibleName"
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
    fun capture(component: Component) {
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
    when (value) {
      is DebuggerPalettePreview -> value.hexColor
      else -> value.toString().replace('\t', ' ').replace('\n', ' ')
    }

private fun defaultPeripheralClipboard(value: String) {
  val selection = StringSelection(value)
  Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
}

private const val PERIPHERAL_COPY_ACTION = "debugger-peripheral-copy"
