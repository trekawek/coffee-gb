package eu.rekawek.coffeegb.swing

import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.SwingConstants

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
  internal val tileAtlasCanvas = DebuggerTileAtlasCanvas(::selectTile)
  internal val backgroundMapCanvas =
      DebuggerTileMapCanvas("Graphical background tile map", ::selectBackgroundCell)
  internal val windowMapCanvas =
      DebuggerTileMapCanvas("Graphical window tile map", ::selectWindowCell)
  internal val objectThumbnailCanvas = DebuggerOamThumbnailCanvas(::selectObject)
  internal val objectPlacementCanvas = DebuggerOamPlacementCanvas(::selectObject)
  internal val paletteCanvas = DebuggerPaletteCanvas(::selectPalette)
  internal val tileBankSelector = JComboBox<String>()
  internal val tilePaletteSelector = JComboBox<String>()
  internal val tileZoomSlider = graphicsZoomSlider("Tile atlas zoom", 1, 5, 2)
  internal val backgroundZoomSlider = graphicsZoomSlider("Background map zoom", 1, 4, 2)
  internal val windowZoomSlider = graphicsZoomSlider("Window map zoom", 1, 4, 2)
  internal val objectZoomSlider = graphicsZoomSlider("Object graphics zoom", 1, 4, 2)
  internal val tileGridCheckBox = graphicsCheckBox("Tile grid", true)
  internal val backgroundGridCheckBox = graphicsCheckBox("Tile grid", true)
  internal val windowGridCheckBox = graphicsCheckBox("Tile grid", true)
  internal val objectGridCheckBox = graphicsCheckBox("Screen grid", true)

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
  private var graphicsModel: DebuggerGraphicsRenderModel? = null
  private var tilePalettes: List<DebuggerGraphicalPalette> = emptyList()
  private var updatingControls = false
  private var updatingSelection = false

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
    configureControls()
    tilePane =
        graphicsDetailPane(
            "Tile atlas",
            "Every VRAM tile is decoded graphically; select a tile for its exact textual data.",
            tileControls(),
            JScrollPane(tileAtlasCanvas),
            "Decoded tile details",
            tileTable,
        )
    backgroundMapPane =
        graphicsDetailPane(
            "Background map",
            "The full 32 by 32 map is composed from captured tiles and palettes.",
            mapControls(backgroundZoomSlider, backgroundGridCheckBox),
            JScrollPane(backgroundMapCanvas),
            "Selected background map entry details",
            backgroundMapTable,
        )
    windowMapPane =
        graphicsDetailPane(
            "Window map",
            "The full 32 by 32 window map is composed from captured tiles and palettes.",
            mapControls(windowZoomSlider, windowGridCheckBox),
            JScrollPane(windowMapCanvas),
            "Selected window map entry details",
            windowMapTable,
        )
    objectPane =
        graphicsDetailPane(
            "Objects",
            "Sprite thumbnails and their captured screen placement remain synchronized.",
            objectControls(),
            objectGraphicsPane(),
            "Selected object attribute details",
            objectTable,
        )
    palettePane =
        graphicsDetailPane(
            "Palettes",
            "Select any swatch for raw RGB and accessible color details.",
            null,
            JScrollPane(paletteCanvas),
            "Selected palette swatch details",
            paletteTable,
        )
    installSelectionLinks()
    installCanvasCopy(tileAtlasCanvas) { tileModel.copyText(tileTable.selectedRows) }
    installCanvasCopy(backgroundMapCanvas) {
      backgroundMapModel.copyText(backgroundMapTable.selectedRows)
    }
    installCanvasCopy(windowMapCanvas) { windowMapModel.copyText(windowMapTable.selectedRows) }
    installCanvasCopy(objectThumbnailCanvas) { objectModel.copyText(objectTable.selectedRows) }
    installCanvasCopy(objectPlacementCanvas) { objectModel.copyText(objectTable.selectedRows) }
    installCanvasCopy(paletteCanvas) { paletteModel.copyText(paletteTable.selectedRows) }
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
    tileModel.replacePrepared(view.tableData.tiles)
    backgroundMapModel.replacePrepared(view.tableData.backgroundMap)
    windowMapModel.replacePrepared(view.tableData.windowMap)
    objectModel.replacePrepared(view.tableData.objects)
    paletteModel.replacePrepared(view.tableData.palettes)
    renderGraphics(view)
    getAccessibleContext().accessibleDescription = view.accessibilityText
  }

  fun showNotCaptured(identity: DebuggerSnapshotIdentity) {
    requirePeripheralEdt("Graphics debugger snapshot transition")
    releaseRows()
    overviewArea.text =
        "Snapshot: ${identity.label}\n" +
            "Graphics inspection was not captured for this snapshot; select Graphics for the next live capture."
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
    graphicsModel = null
    tilePalettes = emptyList()
    updatingControls = true
    try {
      tileBankSelector.removeAllItems()
      tilePaletteSelector.removeAllItems()
    } finally {
      updatingControls = false
    }
    tileAtlasCanvas.clear()
    backgroundMapCanvas.clear()
    windowMapCanvas.clear()
    objectThumbnailCanvas.clear()
    objectPlacementCanvas.clear()
    paletteCanvas.clear()
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

  internal fun resetFontScaleForThemeChange() = fontScaler.resetToBaseline()

  internal fun recaptureFontScaleBaseline() = fontScaler.recapture(this)

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

  private fun renderGraphics(view: DebuggerGraphicsPaneView) {
    val previousBank = tileAtlasCanvas.displayedBank
    val previousPaletteName = tilePaletteSelector.selectedItem as? String
    val model = DebuggerGraphicsRenderModelFactory.create(view)
    graphicsModel = model
    tilePalettes = model.palettes.ifEmpty { listOf(model.fallbackPalette()) }

    val bank = previousBank.takeIf { it in model.availableBanks } ?: model.availableBanks.firstOrNull() ?: 0
    val defaultPalette =
        tilePalettes.firstOrNull { it.displayName == previousPaletteName }
            ?: model.backgroundCells.firstOrNull()?.let(model::backgroundPalette)
            ?: tilePalettes.first()
    updatingControls = true
    try {
      tileBankSelector.removeAllItems()
      model.availableBanks.forEach { tileBankSelector.addItem("VRAM bank $it") }
      tileBankSelector.selectedIndex = model.availableBanks.indexOf(bank).coerceAtLeast(0)
      tilePaletteSelector.removeAllItems()
      tilePalettes.forEach { tilePaletteSelector.addItem(it.displayName) }
      tilePaletteSelector.selectedIndex = tilePalettes.indexOf(defaultPalette).coerceAtLeast(0)
    } finally {
      updatingControls = false
    }

    tileAtlasCanvas.render(model, bank, defaultPalette)
    backgroundMapCanvas.render(model, model.backgroundCells)
    windowMapCanvas.render(model, model.windowCells)
    objectThumbnailCanvas.render(model)
    objectPlacementCanvas.render(model)
    paletteCanvas.render(model)
  }

  private fun configureControls() {
    tileBankSelector.accessibleContext.accessibleName = "Tile atlas VRAM bank"
    tileBankSelector.accessibleContext.accessibleDescription =
        "Select which captured VRAM bank is shown in the graphical tile atlas"
    tileBankSelector.maximumRowCount = 4
    tileBankSelector.addActionListener {
      if (!updatingControls) {
        graphicsModel?.availableBanks?.getOrNull(tileBankSelector.selectedIndex)?.let { bank ->
          tileAtlasCanvas.setBank(bank)
          tileAtlasCanvas.selectedTile?.let(::selectTile)
        }
      }
    }
    tilePaletteSelector.accessibleContext.accessibleName = "Tile atlas preview palette"
    tilePaletteSelector.accessibleContext.accessibleDescription =
        "Select a captured palette used to color the graphical tile atlas"
    tilePaletteSelector.maximumRowCount = 20
    tilePaletteSelector.addActionListener {
      if (!updatingControls) {
        tilePalettes.getOrNull(tilePaletteSelector.selectedIndex)?.let { palette ->
          tileAtlasCanvas.setPalette(palette)
        }
      }
    }

    tileZoomSlider.addChangeListener { tileAtlasCanvas.setZoom(tileZoomSlider.value) }
    backgroundZoomSlider.addChangeListener {
      backgroundMapCanvas.setZoom(backgroundZoomSlider.value)
    }
    windowZoomSlider.addChangeListener { windowMapCanvas.setZoom(windowZoomSlider.value) }
    objectZoomSlider.addChangeListener {
      objectThumbnailCanvas.setZoom(objectZoomSlider.value)
      objectPlacementCanvas.setZoom(objectZoomSlider.value)
    }
    tileGridCheckBox.addActionListener {
      tileAtlasCanvas.setGridVisible(tileGridCheckBox.isSelected)
    }
    backgroundGridCheckBox.addActionListener {
      backgroundMapCanvas.setGridVisible(backgroundGridCheckBox.isSelected)
    }
    windowGridCheckBox.addActionListener {
      windowMapCanvas.setGridVisible(windowGridCheckBox.isSelected)
    }
    objectGridCheckBox.addActionListener {
      objectPlacementCanvas.setGridVisible(objectGridCheckBox.isSelected)
    }
  }

  private fun installSelectionLinks() {
    tileTable.selectionModel.addListSelectionListener {
      selectedTableRow(tileTable)?.takeIf { !updatingSelection }?.let { tableRow ->
        graphicsModel?.tiles?.firstOrNull { it.tableRow == tableRow }?.let { tile ->
          withLinkedSelection {
            selectTileBank(tile.bank)
            tileAtlasCanvas.selectTile(tile)
          }
        }
      }
    }
    backgroundMapTable.selectionModel.addListSelectionListener {
      selectedTableRow(backgroundMapTable)?.takeIf { !updatingSelection }?.let { tableRow ->
        graphicsModel?.backgroundCells?.firstOrNull { it.tableRow == tableRow }?.let { cell ->
          backgroundMapCanvas.selectCell(cell)
          selectBackgroundCell(cell)
        }
      }
    }
    windowMapTable.selectionModel.addListSelectionListener {
      selectedTableRow(windowMapTable)?.takeIf { !updatingSelection }?.let { tableRow ->
        graphicsModel?.windowCells?.firstOrNull { it.tableRow == tableRow }?.let { cell ->
          windowMapCanvas.selectCell(cell)
          selectWindowCell(cell)
        }
      }
    }
    objectTable.selectionModel.addListSelectionListener {
      selectedTableRow(objectTable)?.takeIf { !updatingSelection }?.let { tableRow ->
        graphicsModel?.objects?.firstOrNull { it.tableRow == tableRow }?.let(::selectObject)
      }
    }
    paletteTable.selectionModel.addListSelectionListener {
      selectedTableRow(paletteTable)?.takeIf { !updatingSelection }?.let { tableRow ->
        graphicsModel?.palettes?.forEach { palette ->
          palette.swatches.firstOrNull { it.tableRow == tableRow }?.let { swatch ->
            selectPalette(palette, swatch)
            return@addListSelectionListener
          }
        }
      }
    }
  }

  private fun selectTile(tile: DebuggerGraphicalTile) {
    withLinkedSelection { selectTableRow(tileTable, tile.tableRow) }
  }

  private fun selectBackgroundCell(cell: DebuggerGraphicalMapCell) {
    withLinkedSelection {
      selectTableRow(backgroundMapTable, cell.tableRow)
      linkTile(cell.bank, cell.tileAddress)
      graphicsModel?.backgroundPalette(cell)?.let(::linkPalette)
    }
  }

  private fun selectWindowCell(cell: DebuggerGraphicalMapCell) {
    withLinkedSelection {
      selectTableRow(windowMapTable, cell.tableRow)
      linkTile(cell.bank, cell.tileAddress)
      graphicsModel?.backgroundPalette(cell)?.let(::linkPalette)
    }
  }

  private fun selectObject(value: DebuggerGraphicalObject) {
    withLinkedSelection {
      objectThumbnailCanvas.selectObject(value)
      objectPlacementCanvas.selectObject(value)
      selectTableRow(objectTable, value.tableRow)
      linkTile(value.bank, value.tileAddress)
      graphicsModel?.objectPalette(value)?.let(::linkPalette)
    }
  }

  private fun selectPalette(
      palette: DebuggerGraphicalPalette,
      swatch: DebuggerGraphicalSwatch,
  ) {
    withLinkedSelection {
      paletteCanvas.selectSwatch(palette, swatch)
      selectTableRow(paletteTable, swatch.tableRow)
      selectTilePalette(palette)
    }
  }

  private fun linkTile(bank: Int, address: Int) {
    val tile = graphicsModel?.tile(bank, address) ?: return
    selectTileBank(bank)
    tileAtlasCanvas.selectTile(tile)
    selectTableRow(tileTable, tile.tableRow)
  }

  private fun linkPalette(palette: DebuggerGraphicalPalette) {
    val swatch = palette.swatches.firstOrNull() ?: return
    paletteCanvas.selectSwatch(palette, swatch)
    selectTableRow(paletteTable, swatch.tableRow)
    selectTilePalette(palette)
  }

  private fun selectTileBank(bank: Int) {
    val index = graphicsModel?.availableBanks?.indexOf(bank) ?: -1
    if (index < 0) return
    updatingControls = true
    try {
      tileBankSelector.selectedIndex = index
    } finally {
      updatingControls = false
    }
    tileAtlasCanvas.setBank(bank)
  }

  private fun selectTilePalette(palette: DebuggerGraphicalPalette) {
    val index = tilePalettes.indexOfFirst {
      it.group == palette.group && it.label == palette.label
    }
    if (index < 0) return
    updatingControls = true
    try {
      tilePaletteSelector.selectedIndex = index
    } finally {
      updatingControls = false
    }
    tileAtlasCanvas.setPalette(tilePalettes[index])
  }

  private inline fun withLinkedSelection(action: () -> Unit) {
    if (updatingSelection) return
    updatingSelection = true
    try {
      action()
    } finally {
      updatingSelection = false
    }
  }

  private fun selectedTableRow(table: JTable): Int? =
      table.selectionModel.leadSelectionIndex.takeIf { it in 0 until table.rowCount }

  private fun selectTableRow(table: JTable, row: Int) {
    if (row !in 0 until table.rowCount) return
    table.selectionModel.setSelectionInterval(row, row)
    table.scrollRectToVisible(table.getCellRect(row, 0, true))
  }

  private fun installCanvasCopy(canvas: JComponent, text: () -> String) {
    val actionName = "copy-graphics-selection"
    canvas
        .getInputMap(JComponent.WHEN_FOCUSED)
        .put(KeyStroke.getKeyStroke(KeyEvent.VK_C, peripheralMenuShortcutMask()), actionName)
    canvas.actionMap.put(
        actionName,
        object : AbstractAction() {
          override fun actionPerformed(event: ActionEvent?) {
            text().takeIf(String::isNotBlank)?.let(copyToClipboard)
          }
        },
    )
  }

  private fun tileControls(): Component =
      JPanel(FlowLayout(FlowLayout.LEADING, 8, 2)).apply {
        add(controlLabel("Bank:", tileBankSelector))
        add(tileBankSelector)
        add(controlLabel("Preview palette:", tilePaletteSelector))
        add(tilePaletteSelector)
        add(controlLabel("Zoom:", tileZoomSlider))
        add(tileZoomSlider)
        add(tileGridCheckBox)
      }

  private fun mapControls(zoom: JSlider, grid: JCheckBox): Component =
      JPanel(FlowLayout(FlowLayout.LEADING, 8, 2)).apply {
        add(controlLabel("Zoom:", zoom))
        add(zoom)
        add(grid)
        add(
            JLabel("Viewport overlay unavailable: scroll registers are not in this capture").apply {
              getAccessibleContext().accessibleName =
                  "Viewport overlay unavailable because scroll registers are not captured"
            }
        )
      }

  private fun objectControls(): Component =
      JPanel(FlowLayout(FlowLayout.LEADING, 8, 2)).apply {
        add(controlLabel("Zoom:", objectZoomSlider))
        add(objectZoomSlider)
        add(objectGridCheckBox)
      }

  private fun objectGraphicsPane(): Component =
      JPanel(GridLayout(1, 2, 4, 0)).apply {
        add(
            namedGraphicsPane(
                "Sprite thumbnails",
                "Forty decoded OAM objects; transparent pixels use a checkerboard.",
                JScrollPane(objectThumbnailCanvas),
            )
        )
        add(
            namedGraphicsPane(
                "Screen placement",
                "Objects positioned on the 160 by 144 pixel display, including clipping.",
                JScrollPane(objectPlacementCanvas),
            )
        )
      }

  private fun graphicsDetailPane(
      title: String,
      description: String,
      controls: Component?,
      graphics: Component,
      detailDescription: String,
      table: JTable,
  ): Component {
    val graphicalPane = namedGraphicsPane(title, description, graphics, controls)
    return JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            graphicalPane,
            tablePane(detailDescription, table),
        )
        .apply {
          resizeWeight = 0.72
          isOneTouchExpandable = true
          dividerSize = 8
          getAccessibleContext().accessibleName = "$title graphics and accessible details"
          getAccessibleContext().accessibleDescription = description
        }
  }

  private fun namedGraphicsPane(
      title: String,
      description: String,
      graphics: Component,
      controls: Component? = null,
  ): Component =
      JPanel(BorderLayout(2, 2)).apply {
        val heading =
            JPanel(BorderLayout()).apply {
              border = BorderFactory.createEmptyBorder(3, 4, 3, 4)
              add(JLabel(title).apply { font = font.deriveFont(java.awt.Font.BOLD) }, BorderLayout.NORTH)
              add(JLabel(description), BorderLayout.CENTER)
              controls?.let { add(it, BorderLayout.SOUTH) }
            }
        border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
        getAccessibleContext().accessibleName = title
        getAccessibleContext().accessibleDescription = description
        add(heading, BorderLayout.NORTH)
        add(graphics, BorderLayout.CENTER)
      }

  private fun controlLabel(text: String, target: Component): JLabel =
      JLabel(text).apply { labelFor = target }

  private fun graphicsZoomSlider(
      accessibleName: String,
      minimum: Int,
      maximum: Int,
      value: Int,
  ): JSlider =
      JSlider(SwingConstants.HORIZONTAL, minimum, maximum, value).apply {
        preferredSize = Dimension(92, preferredSize.height)
        majorTickSpacing = 1
        paintTicks = true
        snapToTicks = true
        getAccessibleContext().accessibleName = accessibleName
        getAccessibleContext().accessibleDescription = "$accessibleName, nearest-neighbor integer scale"
      }

  private fun graphicsCheckBox(text: String, selected: Boolean): JCheckBox =
      JCheckBox(text, selected).apply {
        getAccessibleContext().accessibleName = text
        getAccessibleContext().accessibleDescription = "Show or hide the $text overlay"
      }

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
