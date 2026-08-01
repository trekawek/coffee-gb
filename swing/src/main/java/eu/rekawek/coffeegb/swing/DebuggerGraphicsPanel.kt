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
import javax.swing.JTabbedPane
import javax.swing.KeyStroke
import javax.swing.SwingConstants

/** EDT-only renderer for a payload-free [DebuggerGraphicsPaneView]. */
internal class DebuggerGraphicsPanel(
    private val copyToClipboard: (String) -> Unit,
) : JPanel(BorderLayout(4, 4)) {
  internal val overviewArea = peripheralTextArea("graphics inspection summary")
  internal val tabs = JTabbedPane()
  internal val tileAtlasCanvas = DebuggerTileAtlasCanvas(::selectTile)
  internal val backgroundMapCanvas =
      DebuggerTileMapCanvas("Graphical background tile map", ::selectBackgroundCell)
  internal val windowMapCanvas =
      DebuggerTileMapCanvas("Graphical window tile map", ::selectWindowCell)
  internal val backgroundMapDetails = DebuggerMapCellDetailsPanel("Selected background map tile")
  internal val windowMapDetails = DebuggerMapCellDetailsPanel("Selected window map tile")
  internal val tileDetails =
      DebuggerSelectedGraphicsDetailsPanel(
          "Selected tile details",
          listOf("VRAM bank", "Tile", "Address", "Color-index rows"),
          columns = 2,
      )
  internal val objectDetails =
      DebuggerSelectedGraphicsDetailsPanel(
          "Selected object details",
          listOf(
              "Object",
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
          ),
          columns = 2,
      )
  internal val paletteDetails =
      DebuggerSelectedGraphicsDetailsPanel(
          "Selected palette swatch details",
          listOf("Group", "Palette", "Source", "Color index", "Raw", "Components", "Hex", "Name"),
          columns = 2,
      )
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

  private val overviewPane = JScrollPane(overviewArea)
  private val tilePane: Component
  private val backgroundMapPane: Component
  private val windowMapPane: Component
  private val objectPane: Component
  private val palettePane: Component
  private val fontScaler: DebuggerPeripheralFontScaler
  private var graphicsModel: DebuggerGraphicsRenderModel? = null
  private var tilePalettes: List<DebuggerGraphicalPalette> = emptyList()
  private var tileRows: List<DebuggerTileBankRow> = emptyList()
  private var objectRows: List<DebuggerObjectTableRow> = emptyList()
  private var paletteRows: List<DebuggerPaletteTableRow> = emptyList()
  private var updatingControls = false
  private var updatingSelection = false

  init {
    requirePeripheralEdt("Graphics debugger panel construction")
    border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
    getAccessibleContext().accessibleName = "Graphics debugger pane"
    getAccessibleContext().accessibleDescription = EMPTY_DESCRIPTION
    tabs.accessibleContext.accessibleName = "Graphics debugger sections"

    overviewPane.accessibleContext.accessibleName = "Graphics inspection overview"
    configureControls()
    tilePane =
        graphicsDetailPane(
            "Tile atlas",
            "Every VRAM tile is decoded graphically; select a tile for its exact textual data.",
            tileControls(),
            JScrollPane(tileAtlasCanvas),
            tileDetails,
        )
    backgroundMapPane =
        mapGraphicsDetailPane(
            "Background map",
            "The full 32 by 32 map is composed from captured tiles and palettes.",
            mapControls(backgroundZoomSlider, backgroundGridCheckBox),
            JScrollPane(backgroundMapCanvas),
            backgroundMapDetails,
        )
    windowMapPane =
        mapGraphicsDetailPane(
            "Window map",
            "The full 32 by 32 window map is composed from captured tiles and palettes.",
            mapControls(windowZoomSlider, windowGridCheckBox),
            JScrollPane(windowMapCanvas),
            windowMapDetails,
        )
    objectPane =
        graphicsDetailPane(
            "Objects",
            "Sprite thumbnails and their captured screen placement remain synchronized.",
            objectControls(),
            objectGraphicsPane(),
            objectDetails,
        )
    palettePane =
        graphicsDetailPane(
            "Palettes",
            "Select any swatch for raw RGB and accessible color details.",
            null,
            JScrollPane(paletteCanvas),
            paletteDetails,
        )
    installCanvasCopy(tileAtlasCanvas) { tileDetails.copyText() }
    installCanvasCopy(backgroundMapCanvas) { backgroundMapDetails.copyText() }
    installCanvasCopy(windowMapCanvas) { windowMapDetails.copyText() }
    installCanvasCopy(objectThumbnailCanvas) { objectDetails.copyText() }
    installCanvasCopy(objectPlacementCanvas) { objectDetails.copyText() }
    installCanvasCopy(paletteCanvas) { paletteDetails.copyText() }
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
    tileRows = view.tileRows
    objectRows = view.objectRows
    paletteRows = view.paletteRows
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
    tileRows = emptyList()
    objectRows = emptyList()
    paletteRows = emptyList()
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
    backgroundMapDetails.clear()
    windowMapDetails.clear()
    tileDetails.clear()
    objectDetails.clear()
    paletteDetails.clear()
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
      tilePane -> tileDetails.copyText()
      backgroundMapPane -> backgroundMapDetails.copyText()
      windowMapPane -> windowMapDetails.copyText()
      objectPane -> objectDetails.copyText()
      palettePane -> paletteDetails.copyText()
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
    backgroundMapCanvas.selectedCell?.let(backgroundMapDetails::render) ?: backgroundMapDetails.clear()
    windowMapCanvas.selectedCell?.let(windowMapDetails::render) ?: windowMapDetails.clear()
    objectThumbnailCanvas.render(model)
    objectPlacementCanvas.render(model)
    paletteCanvas.render(model)
    tileAtlasCanvas.selectedTile?.let(::renderTileDetails) ?: tileDetails.clear()
    objectThumbnailCanvas.selectedObject?.let(::renderObjectDetails) ?: objectDetails.clear()
    paletteCanvas.selectedSwatch?.let(::renderPaletteDetails) ?: paletteDetails.clear()
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

  private fun selectTile(tile: DebuggerGraphicalTile) {
    withLinkedSelection { renderTileDetails(tile) }
  }

  private fun selectBackgroundCell(cell: DebuggerGraphicalMapCell) {
    withLinkedSelection {
      backgroundMapCanvas.selectCell(cell)
      backgroundMapDetails.render(cell)
      linkTile(cell.bank, cell.tileAddress)
      graphicsModel?.backgroundPalette(cell)?.let(::linkPalette)
    }
  }

  private fun selectWindowCell(cell: DebuggerGraphicalMapCell) {
    withLinkedSelection {
      windowMapCanvas.selectCell(cell)
      windowMapDetails.render(cell)
      linkTile(cell.bank, cell.tileAddress)
      graphicsModel?.backgroundPalette(cell)?.let(::linkPalette)
    }
  }

  private fun selectObject(value: DebuggerGraphicalObject) {
    withLinkedSelection {
      objectThumbnailCanvas.selectObject(value)
      objectPlacementCanvas.selectObject(value)
      renderObjectDetails(value)
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
      renderPaletteDetails(swatch)
      selectTilePalette(palette)
    }
  }

  private fun linkTile(bank: Int, address: Int) {
    val tile = graphicsModel?.tile(bank, address) ?: return
    selectTileBank(bank)
    tileAtlasCanvas.selectTile(tile)
    renderTileDetails(tile)
  }

  private fun linkPalette(palette: DebuggerGraphicalPalette) {
    val swatch = palette.swatches.firstOrNull() ?: return
    paletteCanvas.selectSwatch(palette, swatch)
    renderPaletteDetails(swatch)
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
      details: Component,
  ): Component =
      JPanel(BorderLayout(2, 2)).apply {
        add(namedGraphicsPane(title, description, graphics, controls), BorderLayout.CENTER)
        add(details, BorderLayout.SOUTH)
        getAccessibleContext().accessibleName = "$title graphics and selected details"
        getAccessibleContext().accessibleDescription = description
      }

  private fun mapGraphicsDetailPane(
      title: String,
      description: String,
      controls: Component,
      graphics: Component,
      details: Component,
  ): Component =
      JPanel(BorderLayout(2, 2)).apply {
        add(namedGraphicsPane(title, description, graphics, controls), BorderLayout.CENTER)
        add(details, BorderLayout.SOUTH)
        getAccessibleContext().accessibleName = "$title graphics and selected tile details"
        getAccessibleContext().accessibleDescription = description
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

  private fun renderTileDetails(tile: DebuggerGraphicalTile) {
    val row = tileRows.getOrNull(tile.tableRow) ?: return tileDetails.clear()
    tileDetails.render(
        listOf(row.bank.toString(), row.tileIndex.toString(), row.addressText, row.colorIndexRows),
        row.accessibilityText,
    )
  }

  private fun renderObjectDetails(value: DebuggerGraphicalObject) {
    val row = objectRows.getOrNull(value.tableRow) ?: return objectDetails.clear()
    objectDetails.render(
        listOf(
            row.index.toString(),
            row.addressText,
            row.coordinateText,
            row.sizeText,
            row.tileText,
            row.paletteText,
            row.bank.toString(),
            row.flagsText,
            row.flipText,
            row.priorityText,
            row.visibilityText,
        ),
        row.accessibilityText,
    )
  }

  private fun renderPaletteDetails(swatch: DebuggerGraphicalSwatch) {
    val row = paletteRows.getOrNull(swatch.tableRow) ?: return paletteDetails.clear()
    paletteDetails.render(
        listOf(
            row.group,
            row.palette,
            row.sourceText,
            row.colorIndex.toString(),
            row.rawValueText,
            row.componentText,
            row.hexColor,
            row.colorName,
        ),
        row.accessibilityText,
    )
  }

  private companion object {
    const val EMPTY_DESCRIPTION = "Graphics inspection is not retained"
  }
}

/** Compact, copyable values for the one item selected in a graphical inspection canvas. */
internal class DebuggerSelectedGraphicsDetailsPanel(
    title: String,
    private val labels: List<String>,
    columns: Int,
) : JPanel(BorderLayout(4, 2)) {
  private val values = labels.associateWith { JLabel(NO_SELECTION) }

  init {
    require(columns > 0) { "Details panel must have at least one column" }
    border = BorderFactory.createTitledBorder(title)
    val fields = JPanel(GridLayout(0, columns, 12, 3))
    labels.forEach { label -> fields.add(field(label, requireNotNull(values[label]))) }
    repeat((columns - labels.size % columns) % columns) { fields.add(JPanel()) }
    add(fields, BorderLayout.CENTER)
    getAccessibleContext().accessibleName = title
    clear()
  }

  fun render(nextValues: List<String>, accessibilityText: String) {
    require(nextValues.size == labels.size) { "Selected graphics detail values must match their labels" }
    labels.zip(nextValues).forEach { (label, value) ->
      values.getValue(label).text = value
      values.getValue(label).toolTipText = value
    }
    getAccessibleContext().accessibleDescription = accessibilityText
  }

  fun clear() {
    values.values.forEach {
      it.text = NO_SELECTION
      it.toolTipText = null
    }
    getAccessibleContext().accessibleDescription = "No item selected"
  }

  fun value(label: String): String = values.getValue(label).text

  fun copyText(): String = labels.joinToString("\n") { label -> "$label\t${value(label)}" }

  private fun field(label: String, value: JLabel): Component =
      JPanel(BorderLayout(2, 0)).apply {
        add(JLabel("$label:"), BorderLayout.WEST)
        add(value, BorderLayout.CENTER)
      }

  private companion object {
    const val NO_SELECTION = "—"
  }
}

/** Compact, copyable details for the one tile selected in a graphical tile map. */
internal class DebuggerMapCellDetailsPanel(title: String) : JPanel(BorderLayout(4, 2)) {
  internal val mapCellValue = JLabel(NO_SELECTION)
  internal val mapAddressValue = JLabel(NO_SELECTION)
  internal val tileValue = JLabel(NO_SELECTION)
  internal val bankValue = JLabel(NO_SELECTION)
  internal val paletteValue = JLabel(NO_SELECTION)
  internal val attributesValue = JLabel(NO_SELECTION)

  init {
    border = BorderFactory.createTitledBorder(title)
    val fields = JPanel(GridLayout(2, 3, 12, 3))
    fields.add(field("Map cell", mapCellValue))
    fields.add(field("Map address", mapAddressValue))
    fields.add(field("Tile", tileValue))
    fields.add(field("VRAM bank", bankValue))
    fields.add(field("Palette", paletteValue))
    fields.add(field("Attributes", attributesValue))
    add(fields, BorderLayout.CENTER)
    getAccessibleContext().accessibleName = title
    clear()
  }

  fun render(cell: DebuggerGraphicalMapCell) {
    mapCellValue.text = "Row ${cell.row}, column ${cell.column}"
    mapAddressValue.text = cell.mapAddressText
    tileValue.text = "${cell.tileNumberText} at ${cell.tileAddressText}"
    bankValue.text = cell.bank.toString()
    paletteValue.text = cell.palette.toString()
    attributesValue.text =
        when {
          !cell.attributesAvailable -> "Not captured"
          cell.xFlip && cell.yFlip -> "X flip, Y flip"
          cell.xFlip -> "X flip"
          cell.yFlip -> "Y flip"
          else -> "None"
        }
    getAccessibleContext().accessibleDescription = cell.accessibilityText
  }

  fun clear() {
    mapCellValue.text = NO_SELECTION
    mapAddressValue.text = NO_SELECTION
    tileValue.text = NO_SELECTION
    bankValue.text = NO_SELECTION
    paletteValue.text = NO_SELECTION
    attributesValue.text = NO_SELECTION
    getAccessibleContext().accessibleDescription = "No map tile selected"
  }

  fun copyText(): String =
      listOf(
              "Map cell\t${mapCellValue.text}",
              "Map address\t${mapAddressValue.text}",
              "Tile\t${tileValue.text}",
              "VRAM bank\t${bankValue.text}",
              "Palette\t${paletteValue.text}",
              "Attributes\t${attributesValue.text}",
          )
          .joinToString("\n")

  private fun field(label: String, value: JLabel): Component =
      JPanel(BorderLayout(2, 0)).apply {
        add(JLabel("$label:"), BorderLayout.WEST)
        add(value, BorderLayout.CENTER)
      }

  private companion object {
    const val NO_SELECTION = "—"
  }
}
