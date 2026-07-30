package eu.rekawek.coffeegb.swing

import java.awt.BorderLayout
import java.awt.Component
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable

/** EDT-only renderer for a payload-free [DebuggerGraphicsPaneView]. */
internal class DebuggerGraphicsPanel(
    private val copyToClipboard: (String) -> Unit,
) : JPanel(BorderLayout(4, 4)) {
  internal val overviewArea = peripheralTextArea("graphics inspection summary")
  internal val tabs = JTabbedPane()
  internal val tileTable = JTable()
  internal val backgroundMapTable = JTable()
  internal val windowMapTable = JTable()
  internal val objectTable = JTable()
  internal val paletteTable = JTable()

  private val tileModel =
      DebuggerPeripheralTableModel(
          listOf("Bank", "Index", "Address", "Color-index rows", "Description")
      )
  private val backgroundMapModel = mapModel()
  private val windowMapModel = mapModel()
  private val objectModel =
      DebuggerPeripheralTableModel(
          listOf(
              "Index",
              "OAM address",
              "Coordinates",
              "Size",
              "Tile",
              "Palette",
              "VRAM bank",
              "Raw flags",
              "Flip",
              "Priority",
              "Visibility",
              "Description",
          )
      )
  private val paletteModel =
      DebuggerPeripheralTableModel(
          listOf(
              "Group",
              "Palette",
              "Source",
              "Color",
              "Raw",
              "Components",
              "Hex",
              "Name",
              "Preview",
              "Description",
          )
      )
  private val overviewPane = JScrollPane(overviewArea)
  private val tilePane: Component
  private val backgroundMapPane: Component
  private val windowMapPane: Component
  private val objectPane: Component
  private val palettePane: Component
  private val fontScaler: DebuggerPeripheralFontScaler

  init {
    requirePeripheralEdt("Graphics debugger panel construction")
    border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
    getAccessibleContext().accessibleName = "Graphics debugger pane"
    getAccessibleContext().accessibleDescription = EMPTY_DESCRIPTION
    tabs.accessibleContext.accessibleName = "Graphics debugger sections"

    tileTable.model = tileModel
    backgroundMapTable.model = backgroundMapModel
    windowMapTable.model = windowMapModel
    objectTable.model = objectModel
    paletteTable.model = paletteModel

    configurePeripheralTable(
        tileTable,
        "VRAM tile banks",
        "Textual previews of every tile in each available VRAM bank",
        { tileModel.copyText(tileTable.selectedRows) },
        copyToClipboard,
    )
    configurePeripheralTable(
        backgroundMapTable,
        "Background tile map",
        "All 32 by 32 background map entries with tile attributes in text",
        { backgroundMapModel.copyText(backgroundMapTable.selectedRows) },
        copyToClipboard,
    )
    configurePeripheralTable(
        windowMapTable,
        "Window tile map",
        "All 32 by 32 window map entries with tile attributes in text",
        { windowMapModel.copyText(windowMapTable.selectedRows) },
        copyToClipboard,
    )
    configurePeripheralTable(
        objectTable,
        "Object attribute memory",
        "All 40 OAM objects with raw coordinates, flags, and textual visibility",
        { objectModel.copyText(objectTable.selectedRows) },
        copyToClipboard,
    )
    configurePeripheralTable(
        paletteTable,
        "Graphics palettes",
        "Raw palette values, RGB components, text colors, and supplemental swatches",
        { paletteModel.copyText(paletteTable.selectedRows) },
        copyToClipboard,
    )
    paletteTable.columnModel.getColumn(PALETTE_PREVIEW_COLUMN).cellRenderer =
        DebuggerPalettePreviewRenderer()

    setColumnWidths(tileTable, 55, 65, 85, 560, 640)
    setColumnWidths(backgroundMapTable, 45, 55, 90, 65, 90, 70, 60, 85, 170, 620)
    setColumnWidths(windowMapTable, 45, 55, 90, 65, 90, 70, 60, 85, 170, 620)
    setColumnWidths(objectTable, 50, 85, 180, 65, 170, 130, 70, 75, 90, 170, 110, 700)
    setColumnWidths(paletteTable, 115, 110, 105, 50, 80, 180, 75, 80, 80, 520)

    overviewPane.accessibleContext.accessibleName = "Graphics inspection overview"
    tilePane = tablePane("Decoded color-index rows; one text row per VRAM tile", tileTable)
    backgroundMapPane = tablePane("Background map entries", backgroundMapTable)
    windowMapPane = tablePane("Window map entries", windowMapTable)
    objectPane = tablePane("Object attribute memory: exactly 40 hardware entries", objectTable)
    palettePane =
        tablePane(
            "Every swatch also includes raw, component, hexadecimal, and descriptive text",
            paletteTable,
        )
    tabs.addTab("Overview", overviewPane)
    tabs.addTab("Tile banks", tilePane)
    tabs.addTab("Background map", backgroundMapPane)
    tabs.addTab("Window map", windowMapPane)
    tabs.addTab("Objects", objectPane)
    tabs.addTab("Palettes", palettePane)
    add(tabs, BorderLayout.CENTER)
    fontScaler = DebuggerPeripheralFontScaler(this)
  }

  fun render(view: DebuggerGraphicsPaneView) {
    requirePeripheralEdt("Graphics debugger rendering")
    overviewArea.text = view.overviewText
    overviewArea.caretPosition = 0
    tileModel.replace(
        view.tileRows.map { row ->
          listOf<Any>(
              row.bank,
              row.tileIndex,
              row.addressText,
              row.colorIndexRows,
              row.accessibilityText,
          )
        }
    )
    backgroundMapModel.replace(view.backgroundMapRows.map(::mapCells))
    windowMapModel.replace(view.windowMapRows.map(::mapCells))
    objectModel.replace(
        view.objectRows.map { row ->
          listOf<Any>(
              row.index,
              row.addressText,
              row.coordinateText,
              row.sizeText,
              row.tileText,
              row.paletteText,
              row.bank,
              row.flagsText,
              row.flipText,
              row.priorityText,
              row.visibilityText,
              row.accessibilityText,
          )
        }
    )
    paletteModel.replace(
        view.paletteRows.map { row ->
          listOf<Any>(
              row.group,
              row.palette,
              row.sourceText,
              row.colorIndex,
              row.rawValueText,
              row.componentText,
              row.hexColor,
              row.colorName,
              DebuggerPalettePreview(row.hexColor, row.rgb888),
              row.accessibilityText,
          )
        }
    )
    getAccessibleContext().accessibleDescription = view.accessibilityText
  }

  fun showNotCaptured(identity: DebuggerSnapshotIdentity) {
    requirePeripheralEdt("Graphics debugger snapshot transition")
    releaseRows()
    overviewArea.text =
        "Snapshot: ${identity.label}\n" +
            "Graphics inspection was not captured for this snapshot; select Graphics to refresh."
    overviewArea.caretPosition = 0
    getAccessibleContext().accessibleDescription =
        "${identity.label}. Graphics inspection was not captured for this snapshot."
  }

  fun clear() {
    requirePeripheralEdt("Graphics debugger clearing")
    overviewArea.text = "No graphics inspection loaded"
    overviewArea.caretPosition = 0
    releaseRows()
    getAccessibleContext().accessibleDescription = EMPTY_DESCRIPTION
  }

  private fun releaseRows() {
    tileModel.clear()
    backgroundMapModel.clear()
    windowMapModel.clear()
    objectModel.clear()
    paletteModel.clear()
  }

  fun applyFontScale(scalePercent: Int) {
    requirePeripheralEdt("Graphics debugger font scaling")
    fontScaler.apply(scalePercent)
    revalidate()
    repaint()
  }

  fun copyText(): String {
    requirePeripheralEdt("Graphics debugger copying")
    return when (tabs.selectedComponent) {
      overviewPane -> overviewArea.text
      tilePane -> tileModel.copyText(tileTable.selectedRows)
      backgroundMapPane -> backgroundMapModel.copyText(backgroundMapTable.selectedRows)
      windowMapPane -> windowMapModel.copyText(windowMapTable.selectedRows)
      objectPane -> objectModel.copyText(objectTable.selectedRows)
      palettePane -> paletteModel.copyText(paletteTable.selectedRows)
      else -> ""
    }
  }

  private fun mapCells(row: DebuggerTileMapTableRow): List<Any> =
      listOf(
          row.row,
          row.column,
          row.mapAddressText,
          row.tileNumberText,
          row.tileDataAddressText,
          row.bank,
          row.palette,
          row.attributesText,
          row.flagsText,
          row.accessibilityText,
      )

  private fun mapModel(): DebuggerPeripheralTableModel =
      DebuggerPeripheralTableModel(
          listOf(
              "Row",
              "Column",
              "Map address",
              "Tile",
              "Data address",
              "VRAM bank",
              "Palette",
              "Attributes",
              "Flags",
              "Description",
          )
      )

  private fun tablePane(description: String, table: JTable): Component =
      JPanel(BorderLayout(2, 2)).apply {
        val label = JLabel(description)
        label.accessibleContext.accessibleName = "$description description"
        add(label, BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
      }

  private fun setColumnWidths(table: JTable, vararg widths: Int) {
    widths.forEachIndexed { index, width -> table.columnModel.getColumn(index).preferredWidth = width }
  }

  private companion object {
    const val PALETTE_PREVIEW_COLUMN = 8
    const val EMPTY_DESCRIPTION = "Graphics inspection is not retained"
  }
}
