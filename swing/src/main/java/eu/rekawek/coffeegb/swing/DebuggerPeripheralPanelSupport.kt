package eu.rekawek.coffeegb.swing

import java.awt.Component
import java.awt.Container
import java.awt.Font
import java.awt.GridLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.IdentityHashMap
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.JTextField
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

/**
 * A compact, read-only form for a peripheral overview.
 *
 * Unlike a multi-line summary, each captured property has a stable labelled control. Values do
 * not take keyboard focus, so they cannot intercept gameplay shortcuts when the debugger is not
 * the active window.
 */
internal class DebuggerOverviewPropertiesPanel(
    private val title: String,
    private val labels: List<String>,
    columns: Int = 2,
) : JPanel(java.awt.BorderLayout(4, 4)) {
  private val values = labels.associateWith { JTextField(NO_VALUE) }

  init {
    require(columns > 0) { "Overview properties must have at least one column" }
    require(labels.distinct().size == labels.size) { "Overview property labels must be unique" }
    border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
    val fields = JPanel(GridLayout(0, columns, 16, 8))
    labels.forEach { label -> fields.add(field(label, values.getValue(label))) }
    repeat((columns - labels.size % columns) % columns) { fields.add(JPanel()) }
    add(fields, java.awt.BorderLayout.NORTH)
    getAccessibleContext().accessibleName = title
    getAccessibleContext().accessibleDescription = "No properties loaded"
  }

  fun render(properties: List<DebuggerOverviewProperty>) {
    val byLabel = properties.associateBy(DebuggerOverviewProperty::label)
    require(byLabel.size == properties.size) { "Overview property labels must be unique" }
    require(byLabel.keys.all(labels::contains)) {
      "Overview properties must have a matching visible control"
    }
    labels.forEach { label -> setValue(label, byLabel[label]?.value ?: NO_VALUE) }
    getAccessibleContext().accessibleDescription =
        properties.joinToString("; ") { property -> "${property.label}: ${property.value}" }
  }

  fun clear(message: String) {
    labels.forEach { label -> setValue(label, NO_VALUE) }
    if ("Capture status" in labels) setValue("Capture status", message)
    getAccessibleContext().accessibleDescription = message
  }

  fun value(label: String): String = values.getValue(label).text

  fun copyText(): String = labels.joinToString("\n") { label -> "$label\t${value(label)}" }

  private fun setValue(label: String, value: String) {
    values.getValue(label).text = value
    values.getValue(label).toolTipText = value.takeUnless { it == NO_VALUE }
  }

  private fun field(label: String, value: JTextField): JPanel =
      JPanel(java.awt.BorderLayout(4, 0)).apply {
        val caption = JLabel("$label:")
        caption.labelFor = value
        add(caption, java.awt.BorderLayout.WEST)
        value.apply {
          isEditable = false
          isFocusable = false
          columns = 22
          getAccessibleContext().accessibleName = "$title $label"
          getAccessibleContext().accessibleDescription = "Current $label"
        }
        add(value, java.awt.BorderLayout.CENTER)
      }

  private companion object {
    const val NO_VALUE = "—"
  }
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
