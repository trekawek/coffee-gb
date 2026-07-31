package eu.rekawek.coffeegb.swing

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.AbstractAction
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.UIManager

/** Pure graphical model reconstructed from the payload-free peripheral pane rows. */
internal data class DebuggerGraphicsRenderModel(
    val tiles: List<DebuggerGraphicalTile>,
    val backgroundCells: List<DebuggerGraphicalMapCell>,
    val windowCells: List<DebuggerGraphicalMapCell>,
    val objects: List<DebuggerGraphicalObject>,
    val palettes: List<DebuggerGraphicalPalette>,
) {
  private val tilesByLocation = tiles.associateBy { DebuggerTileLocation(it.bank, it.address) }

  val availableBanks: List<Int> = tiles.map(DebuggerGraphicalTile::bank).distinct().sorted()

  fun tile(bank: Int, address: Int): DebuggerGraphicalTile? =
      tilesByLocation[DebuggerTileLocation(bank, address)]

  fun backgroundPalette(cell: DebuggerGraphicalMapCell): DebuggerGraphicalPalette =
      if (cell.attributesAvailable) {
        palettes.firstOrNull {
          it.group.equals(CGB_BACKGROUND_GROUP, ignoreCase = true) && it.index == cell.palette
        }
      } else {
        palettes.firstOrNull {
          it.group.equals(DMG_GROUP, ignoreCase = true) &&
              it.label.equals(DMG_BACKGROUND_LABEL, ignoreCase = true)
        }
      } ?: fallbackPalette()

  fun objectPalette(value: DebuggerGraphicalObject): DebuggerGraphicalPalette {
    val cgbIndex = CGB_OBJECT_PALETTE.find(value.paletteText)?.groupValues?.get(1)?.toIntOrNull()
    if (cgbIndex != null) {
      return palettes.firstOrNull {
        it.group.equals(CGB_OBJECT_GROUP, ignoreCase = true) && it.index == cgbIndex
      } ?: fallbackPalette()
    }
    val dmgIndex = DMG_OBJECT_PALETTE.find(value.paletteText)?.groupValues?.get(1)?.toIntOrNull()
    return palettes.firstOrNull {
      it.group.equals(DMG_GROUP, ignoreCase = true) &&
          it.label.equals("Object ${dmgIndex ?: 0}", ignoreCase = true)
    } ?: fallbackPalette()
  }

  fun fallbackPalette(): DebuggerGraphicalPalette =
      palettes.firstOrNull()
          ?: DebuggerGraphicalPalette(
              group = "Fallback",
              label = "Neutral",
              index = 0,
              swatches =
                  listOf(
                      DebuggerGraphicalSwatch(0, 0, 0xffffff, false, "White"),
                      DebuggerGraphicalSwatch(1, 0, 0xaaaaaa, false, "Light gray"),
                      DebuggerGraphicalSwatch(2, 0, 0x555555, false, "Dark gray"),
                      DebuggerGraphicalSwatch(3, 0, 0x000000, false, "Black"),
                  ),
          )

  private data class DebuggerTileLocation(val bank: Int, val address: Int)

  private companion object {
    const val DMG_GROUP = "DMG"
    const val DMG_BACKGROUND_LABEL = "Background"
    const val CGB_BACKGROUND_GROUP = "CGB background"
    const val CGB_OBJECT_GROUP = "CGB object"
    val CGB_OBJECT_PALETTE = Regex("CGB object palette\\s+(\\d+)", RegexOption.IGNORE_CASE)
    val DMG_OBJECT_PALETTE = Regex("DMG OBP(\\d+)", RegexOption.IGNORE_CASE)
  }
}

internal data class DebuggerGraphicalTile(
    val tableRow: Int,
    val bank: Int,
    val index: Int,
    val address: Int,
    val addressText: String,
    val pixels: IntArray,
    val accessibilityText: String,
)

internal data class DebuggerGraphicalMapCell(
    val tableRow: Int,
    val row: Int,
    val column: Int,
    val mapAddressText: String,
    val tileNumberText: String,
    val tileAddress: Int,
    val tileAddressText: String,
    val bank: Int,
    val palette: Int,
    val attributesAvailable: Boolean,
    val xFlip: Boolean,
    val yFlip: Boolean,
    val accessibilityText: String,
)

internal data class DebuggerGraphicalObject(
    val tableRow: Int,
    val index: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val tileNumber: Int,
    val tileAddress: Int,
    val bank: Int,
    val paletteText: String,
    val xFlip: Boolean,
    val yFlip: Boolean,
    val visibilityText: String,
    val accessibilityText: String,
)

internal data class DebuggerGraphicalPalette(
    val group: String,
    val label: String,
    val index: Int,
    val swatches: List<DebuggerGraphicalSwatch>,
) {
  val displayName: String = "$group · $label"

  fun color(colorIndex: Int): Int =
      swatches.firstOrNull { it.colorIndex == colorIndex }?.rgb888
          ?: FALLBACK_COLORS[colorIndex.coerceIn(0, 3)]

  fun isTransparent(colorIndex: Int): Boolean =
      swatches.firstOrNull { it.colorIndex == colorIndex }?.transparent == true

  private companion object {
    val FALLBACK_COLORS = intArrayOf(0xffffff, 0xaaaaaa, 0x555555, 0x000000)
  }
}

internal data class DebuggerGraphicalSwatch(
    val colorIndex: Int,
    val tableRow: Int,
    val rgb888: Int,
    val transparent: Boolean,
    val accessibilityText: String,
)

internal object DebuggerGraphicsRenderModelFactory {
  /** Returns the worker-prepared model; this overload deliberately performs no EDT decoding. */
  fun create(view: DebuggerGraphicsPaneView): DebuggerGraphicsRenderModel = view.renderModel

  fun create(
      tileRows: List<DebuggerTileBankRow>,
      backgroundMapRows: List<DebuggerTileMapTableRow>,
      windowMapRows: List<DebuggerTileMapTableRow>,
      objectRows: List<DebuggerObjectTableRow>,
      paletteRows: List<DebuggerPaletteTableRow>,
  ): DebuggerGraphicsRenderModel =
      DebuggerGraphicsRenderModel(
          tiles = tileRows.mapIndexed(::tile),
          backgroundCells = backgroundMapRows.mapIndexed(::mapCell),
          windowCells = windowMapRows.mapIndexed(::mapCell),
          objects = objectRows.mapIndexed(::objectRow),
          palettes = palettes(paletteRows),
      )

  private fun tile(tableRow: Int, row: DebuggerTileBankRow): DebuggerGraphicalTile =
      DebuggerGraphicalTile(
          tableRow,
          row.bank,
          row.tileIndex,
          parseHex(row.addressText),
          row.addressText,
          decodeTilePixels(row.colorIndexRows),
          row.accessibilityText,
      )

  private fun mapCell(tableRow: Int, row: DebuggerTileMapTableRow): DebuggerGraphicalMapCell =
      DebuggerGraphicalMapCell(
          tableRow,
          row.row,
          row.column,
          row.mapAddressText,
          row.tileNumberText,
          parseHex(row.tileDataAddressText),
          row.tileDataAddressText,
          row.bank,
          row.palette,
          !row.attributesText.equals("Unavailable", ignoreCase = true),
          row.flagsText.contains("horizontal flip", ignoreCase = true),
          row.flagsText.contains("vertical flip", ignoreCase = true),
          row.accessibilityText,
      )

  private fun objectRow(tableRow: Int, row: DebuggerObjectTableRow): DebuggerGraphicalObject {
    val coordinates = COORDINATES.find(row.coordinateText)
    val size = SIZE.find(row.sizeText)
    val tileNumber = parseHex(row.tileText)
    return DebuggerGraphicalObject(
        tableRow,
        row.index,
        coordinates?.groupValues?.get(1)?.toIntOrNull() ?: 0,
        coordinates?.groupValues?.get(2)?.toIntOrNull() ?: 0,
        size?.groupValues?.get(1)?.toIntOrNull() ?: 8,
        size?.groupValues?.get(2)?.toIntOrNull() ?: 8,
        tileNumber,
        VRAM_START + tileNumber * TILE_BYTES,
        row.bank,
        row.paletteText,
        row.flipText.contains("horizontal", ignoreCase = true),
        row.flipText.contains("vertical", ignoreCase = true),
        row.visibilityText,
        row.accessibilityText,
    )
  }

  private fun palettes(rows: List<DebuggerPaletteTableRow>): List<DebuggerGraphicalPalette> =
      rows
          .mapIndexed { index, row -> index to row }
          .groupBy({ (_, row) -> row.group to row.palette }, { it })
          .map { (key, entries) ->
            DebuggerGraphicalPalette(
                key.first,
                key.second,
                paletteIndex(key.second),
                entries
                    .sortedBy { (_, row) -> row.colorIndex }
                    .map { (tableRow, row) ->
                      DebuggerGraphicalSwatch(
                          row.colorIndex,
                          tableRow,
                          row.rgb888,
                          row.colorName.contains("transparent", ignoreCase = true),
                          row.accessibilityText,
                      )
                    },
            )
          }

  private fun paletteIndex(label: String): Int =
      label.substringAfterLast(' ', "0").toIntOrNull() ?: 0

  private fun decodeTilePixels(text: String): IntArray {
    val result = IntArray(TILE_PIXELS)
    var destination = 0
    for (character in text) {
      if (character in '0'..'3' && destination < result.size) {
        result[destination++] = character - '0'
      }
    }
    return result
  }

  private fun parseHex(text: String): Int =
      HEX.find(text)?.groupValues?.get(1)?.toIntOrNull(16) ?: 0

  private const val VRAM_START = 0x8000
  private const val TILE_BYTES = 16
  private const val TILE_PIXELS = 64
  private val HEX = Regex("(?:\\$|0[xX])([0-9A-Fa-f]{1,4})")
  private val COORDINATES = Regex("X\\s+(-?\\d+).*?Y\\s+(-?\\d+)")
  private val SIZE = Regex("(\\d+)\\s+by\\s+(\\d+)", RegexOption.IGNORE_CASE)
}

/** Common keyboard, focus, selection, and nearest-neighbour painting behavior for graphics views. */
internal abstract class DebuggerGraphicsCanvas(
    accessibleName: String,
    emptyDescription: String,
) : JPanel(), DesktopThemeRefreshHook {
  protected var emptyDescription: String = emptyDescription
  protected var selectedIndex: Int = -1

  init {
    isFocusable = true
    isOpaque = true
    background = uiColor("Panel.background", Color(32, 32, 32))
    foreground = uiColor("Label.foreground", Color.WHITE)
    getAccessibleContext().accessibleName = accessibleName
    getAccessibleContext().accessibleDescription = emptyDescription
    toolTipText = ""
    addMouseListener(
        object : MouseAdapter() {
          override fun mousePressed(event: MouseEvent) {
            if (SwingUtilities.isLeftMouseButton(event)) {
              requestFocusInWindow()
              indexAt(event.point)?.let { selectIndex(it, true) }
            }
          }
        }
    )
    bind(KeyEvent.VK_LEFT, "graphics-left") { moveSelection(-1, 0) }
    bind(KeyEvent.VK_RIGHT, "graphics-right") { moveSelection(1, 0) }
    bind(KeyEvent.VK_UP, "graphics-up") { moveSelection(0, -1) }
    bind(KeyEvent.VK_DOWN, "graphics-down") { moveSelection(0, 1) }
    bind(KeyEvent.VK_HOME, "graphics-home") { selectFirst() }
    bind(KeyEvent.VK_END, "graphics-end") { selectLast() }
  }

  abstract val itemCount: Int

  abstract fun clear()

  protected abstract fun indexAt(point: Point): Int?

  protected abstract fun moveSelection(horizontal: Int, vertical: Int)

  protected abstract fun selectionChanged(index: Int, notify: Boolean)

  protected abstract fun selectedDescription(index: Int): String

  protected fun selectIndex(index: Int, notify: Boolean) {
    if (itemCount == 0) {
      selectedIndex = -1
      getAccessibleContext().accessibleDescription = emptyDescription
      repaint()
      return
    }
    val next = index.coerceIn(0, itemCount - 1)
    selectedIndex = next
    getAccessibleContext().accessibleDescription = selectedDescription(next)
    selectionChanged(next, notify)
    repaint()
  }

  protected fun selectFirst() {
    if (itemCount > 0) selectIndex(0, true)
  }

  protected fun selectLast() {
    if (itemCount > 0) selectIndex(itemCount - 1, true)
  }

  protected fun prepareGraphics(graphics: Graphics): Graphics2D =
      (graphics.create() as Graphics2D).apply {
        setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR,
        )
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
      }

  protected fun paintEmpty(graphics: Graphics2D) {
    graphics.color = background
    graphics.fillRect(0, 0, width, height)
    graphics.color = uiColor("Label.disabledForeground", Color.GRAY)
    val metrics = graphics.fontMetrics
    graphics.drawString(emptyDescription, 8, (height / 2 + metrics.ascent / 2).coerceAtLeast(16))
  }

  protected fun paintFocus(graphics: Graphics2D) {
    if (!hasFocus()) return
    graphics.color = selectionColor()
    graphics.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(3f, 3f), 0f)
    graphics.drawRect(1, 1, width - 3, height - 3)
  }

  protected fun selectionColor(): Color =
      uiColor("Component.focusColor", uiColor("Table.selectionBackground", Color(220, 90, 45)))

  override fun desktopThemeChanged(tokens: DesktopThemeTokens) {
    background = uiColor("Panel.background", tokens.surface)
    foreground = uiColor("Label.foreground", tokens.primaryText)
    repaint()
  }

  override fun getToolTipText(event: MouseEvent): String? =
      indexAt(event.point)?.takeIf { it in 0 until itemCount }?.let(::selectedDescription)

  private fun bind(keyCode: Int, name: String, action: () -> Unit) {
    getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keyCode, 0), name)
    actionMap.put(
        name,
        object : AbstractAction() {
          override fun actionPerformed(event: ActionEvent?) = action()
        },
    )
  }
}

internal class DebuggerTileAtlasCanvas(
    private val onSelection: (DebuggerGraphicalTile) -> Unit,
) : DebuggerGraphicsCanvas("Graphical VRAM tile atlas", "No graphical tiles loaded") {
  private var model: DebuggerGraphicsRenderModel? = null
  private var visibleTiles: List<DebuggerGraphicalTile> = emptyList()
  private var bank = 0
  private var palette: DebuggerGraphicalPalette? = null
  private var zoom = DEFAULT_ZOOM
  private var showGrid = true
  private var image: BufferedImage? = null

  override val itemCount: Int
    get() = visibleTiles.size

  val selectedTile: DebuggerGraphicalTile?
    get() = visibleTiles.getOrNull(selectedIndex)

  val displayedBank: Int
    get() = bank

  fun render(
      nextModel: DebuggerGraphicsRenderModel,
      nextBank: Int,
      nextPalette: DebuggerGraphicalPalette,
  ) {
    model = nextModel
    bank = nextBank
    palette = nextPalette
    visibleTiles = nextModel.tiles.filter { it.bank == bank }.sortedBy { it.index }
    rebuildImage()
    updatePreferredSize()
    selectIndex(selectedIndex.takeIf { it in visibleTiles.indices } ?: 0, false)
  }

  fun setBank(nextBank: Int) {
    val currentModel = model ?: return
    val selectedPhysicalIndex = selectedTile?.index ?: 0
    bank = nextBank
    visibleTiles = currentModel.tiles.filter { it.bank == bank }.sortedBy { it.index }
    rebuildImage()
    updatePreferredSize()
    selectIndex(visibleTiles.indexOfFirst { it.index == selectedPhysicalIndex }.coerceAtLeast(0), false)
  }

  fun setPalette(nextPalette: DebuggerGraphicalPalette) {
    palette = nextPalette
    rebuildImage()
    repaint()
  }

  fun setZoom(value: Int) {
    zoom = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
    updatePreferredSize()
  }

  fun setGridVisible(visible: Boolean) {
    showGrid = visible
    repaint()
  }

  fun selectTile(tile: DebuggerGraphicalTile, notify: Boolean = false) {
    if (tile.bank != bank) setBank(tile.bank)
    visibleTiles.indexOfFirst { it.index == tile.index }.takeIf { it >= 0 }?.let {
      selectIndex(it, notify)
    }
  }

  override fun clear() {
    model = null
    visibleTiles = emptyList()
    image = null
    selectedIndex = -1
    getAccessibleContext().accessibleDescription = emptyDescription
    updatePreferredSize()
    repaint()
  }

  override fun paintComponent(graphics: Graphics) {
    super.paintComponent(graphics)
    val graphics2d = prepareGraphics(graphics)
    try {
      val source = image
      if (source == null) {
        paintEmpty(graphics2d)
        return
      }
      graphics2d.color = background
      graphics2d.fillRect(0, 0, width, height)
      graphics2d.drawImage(source, 0, 0, source.width * zoom, source.height * zoom, null)
      if (showGrid) paintTileGrid(graphics2d, ATLAS_COLUMNS, ATLAS_ROWS, zoom)
      if (selectedIndex in visibleTiles.indices) {
        val column = selectedIndex % ATLAS_COLUMNS
        val row = selectedIndex / ATLAS_COLUMNS
        graphics2d.color = selectionColor()
        graphics2d.stroke = BasicStroke(2f)
        graphics2d.drawRect(column * TILE_SIZE * zoom, row * TILE_SIZE * zoom, TILE_SIZE * zoom - 1, TILE_SIZE * zoom - 1)
      }
      paintFocus(graphics2d)
    } finally {
      graphics2d.dispose()
    }
  }

  override fun indexAt(point: Point): Int? {
    val column = point.x / (TILE_SIZE * zoom)
    val row = point.y / (TILE_SIZE * zoom)
    if (column !in 0 until ATLAS_COLUMNS || row !in 0 until ATLAS_ROWS) return null
    return (row * ATLAS_COLUMNS + column).takeIf { it in visibleTiles.indices }
  }

  override fun moveSelection(horizontal: Int, vertical: Int) {
    if (visibleTiles.isEmpty()) return
    val current = selectedIndex.coerceAtLeast(0)
    val column = (current % ATLAS_COLUMNS + horizontal).coerceIn(0, ATLAS_COLUMNS - 1)
    val row = (current / ATLAS_COLUMNS + vertical).coerceIn(0, ATLAS_ROWS - 1)
    selectIndex(row * ATLAS_COLUMNS + column, true)
    scrollRectToVisible(selectionBounds())
  }

  override fun selectionChanged(index: Int, notify: Boolean) {
    if (notify) visibleTiles.getOrNull(index)?.let(onSelection)
  }

  override fun selectedDescription(index: Int): String =
      visibleTiles.getOrNull(index)?.let {
        "VRAM bank ${it.bank}, tile ${it.index}, address ${it.addressText}. ${it.accessibilityText}"
      } ?: emptyDescription

  private fun rebuildImage() {
    if (visibleTiles.isEmpty()) {
      image = null
      return
    }
    val colors = palette ?: model?.fallbackPalette() ?: return
    image =
        BufferedImage(ATLAS_COLUMNS * TILE_SIZE, ATLAS_ROWS * TILE_SIZE, BufferedImage.TYPE_INT_RGB)
            .also { target ->
              visibleTiles.forEachIndexed { index, tile ->
                val originX = index % ATLAS_COLUMNS * TILE_SIZE
                val originY = index / ATLAS_COLUMNS * TILE_SIZE
                for (y in 0 until TILE_SIZE) {
                  for (x in 0 until TILE_SIZE) {
                    target.setRGB(originX + x, originY + y, opaque(colors.color(tile.pixels[y * TILE_SIZE + x])))
                  }
                }
              }
            }
  }

  private fun updatePreferredSize() {
    preferredSize =
        if (image == null) Dimension(ATLAS_COLUMNS * TILE_SIZE * zoom, 120)
        else Dimension(ATLAS_COLUMNS * TILE_SIZE * zoom, ATLAS_ROWS * TILE_SIZE * zoom)
    revalidate()
    repaint()
  }

  private fun selectionBounds(): Rectangle {
    val index = selectedIndex.coerceAtLeast(0)
    return Rectangle(
        index % ATLAS_COLUMNS * TILE_SIZE * zoom,
        index / ATLAS_COLUMNS * TILE_SIZE * zoom,
        TILE_SIZE * zoom,
        TILE_SIZE * zoom,
    )
  }

  private companion object {
    const val TILE_SIZE = 8
    const val ATLAS_COLUMNS = 16
    const val ATLAS_ROWS = 24
    const val DEFAULT_ZOOM = 2
    const val MIN_ZOOM = 1
    const val MAX_ZOOM = 5
  }
}

internal class DebuggerTileMapCanvas(
    accessibleName: String,
    private val onSelection: (DebuggerGraphicalMapCell) -> Unit,
) : DebuggerGraphicsCanvas(accessibleName, "No graphical tile map loaded") {
  private var model: DebuggerGraphicsRenderModel? = null
  private var cells: List<DebuggerGraphicalMapCell> = emptyList()
  private var cellsByIndex: Array<DebuggerGraphicalMapCell?> = arrayOfNulls(MAP_ENTRIES)
  private var zoom = DEFAULT_ZOOM
  private var showGrid = true
  private var image: BufferedImage? = null
  private var viewport: Rectangle? = null

  override val itemCount: Int
    get() = MAP_ENTRIES.takeIf { cells.isNotEmpty() } ?: 0

  val selectedCell: DebuggerGraphicalMapCell?
    get() = cellsByIndex.getOrNull(selectedIndex)

  val hasViewportOverlay: Boolean
    get() = viewport != null

  fun render(
      nextModel: DebuggerGraphicsRenderModel,
      nextCells: List<DebuggerGraphicalMapCell>,
      nextViewport: Rectangle? = null,
  ) {
    model = nextModel
    cells = nextCells
    cellsByIndex = arrayOfNulls(MAP_ENTRIES)
    cells.forEach { cell ->
      val index = cell.row * MAP_WIDTH + cell.column
      if (index in cellsByIndex.indices) cellsByIndex[index] = cell
    }
    viewport = nextViewport
    rebuildImage()
    updatePreferredSize()
    selectIndex(selectedIndex.takeIf { it in cellsByIndex.indices } ?: 0, false)
  }

  fun setZoom(value: Int) {
    zoom = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
    updatePreferredSize()
  }

  fun setGridVisible(visible: Boolean) {
    showGrid = visible
    repaint()
  }

  fun selectCell(cell: DebuggerGraphicalMapCell, notify: Boolean = false) {
    selectIndex(cell.row * MAP_WIDTH + cell.column, notify)
  }

  internal fun selectCellIndex(index: Int, notify: Boolean = true) {
    selectIndex(index, notify)
  }

  override fun clear() {
    model = null
    cells = emptyList()
    cellsByIndex = arrayOfNulls(MAP_ENTRIES)
    image = null
    viewport = null
    selectedIndex = -1
    getAccessibleContext().accessibleDescription = emptyDescription
    updatePreferredSize()
    repaint()
  }

  override fun paintComponent(graphics: Graphics) {
    super.paintComponent(graphics)
    val graphics2d = prepareGraphics(graphics)
    try {
      val source = image
      if (source == null) {
        paintEmpty(graphics2d)
        return
      }
      graphics2d.color = background
      graphics2d.fillRect(0, 0, width, height)
      graphics2d.drawImage(source, 0, 0, MAP_PIXELS * zoom, MAP_PIXELS * zoom, null)
      if (showGrid) paintTileGrid(graphics2d, MAP_WIDTH, MAP_WIDTH, zoom)
      viewport?.let { value ->
        graphics2d.color = uiColor("Actions.Yellow", Color(255, 190, 45))
        graphics2d.stroke = BasicStroke(2f)
        graphics2d.drawRect(
            value.x * zoom,
            value.y * zoom,
            value.width * zoom - 1,
            value.height * zoom - 1,
        )
      }
      if (selectedIndex in cellsByIndex.indices && cellsByIndex[selectedIndex] != null) {
        val column = selectedIndex % MAP_WIDTH
        val row = selectedIndex / MAP_WIDTH
        graphics2d.color = selectionColor()
        graphics2d.stroke = BasicStroke(2f)
        graphics2d.drawRect(column * TILE_SIZE * zoom, row * TILE_SIZE * zoom, TILE_SIZE * zoom - 1, TILE_SIZE * zoom - 1)
      }
      paintFocus(graphics2d)
    } finally {
      graphics2d.dispose()
    }
  }

  override fun indexAt(point: Point): Int? {
    val column = point.x / (TILE_SIZE * zoom)
    val row = point.y / (TILE_SIZE * zoom)
    if (column !in 0 until MAP_WIDTH || row !in 0 until MAP_WIDTH) return null
    return (row * MAP_WIDTH + column).takeIf { cellsByIndex[it] != null }
  }

  override fun moveSelection(horizontal: Int, vertical: Int) {
    if (cells.isEmpty()) return
    val current = selectedIndex.coerceAtLeast(0)
    val column = (current % MAP_WIDTH + horizontal).coerceIn(0, MAP_WIDTH - 1)
    val row = (current / MAP_WIDTH + vertical).coerceIn(0, MAP_WIDTH - 1)
    val index = row * MAP_WIDTH + column
    if (cellsByIndex[index] != null) {
      selectIndex(index, true)
      scrollRectToVisible(selectionBounds())
    }
  }

  override fun selectionChanged(index: Int, notify: Boolean) {
    if (notify) cellsByIndex.getOrNull(index)?.let { it?.let(onSelection) }
  }

  override fun selectedDescription(index: Int): String =
      cellsByIndex.getOrNull(index)?.let { cell ->
        cell?.let {
          "Map row ${it.row}, column ${it.column}, ${it.mapAddressText}; tile ${it.tileNumberText}, data ${it.tileAddressText}, bank ${it.bank}, palette ${it.palette}. ${it.accessibilityText}"
        }
      } ?: emptyDescription

  private fun rebuildImage() {
    val currentModel = model
    if (currentModel == null || cells.isEmpty()) {
      image = null
      return
    }
    image = BufferedImage(MAP_PIXELS, MAP_PIXELS, BufferedImage.TYPE_INT_RGB).also { target ->
      cells.forEach { cell ->
        val tile = currentModel.tile(cell.bank, cell.tileAddress) ?: return@forEach
        val palette = currentModel.backgroundPalette(cell)
        for (displayY in 0 until TILE_SIZE) {
          val sourceY = if (cell.yFlip) TILE_SIZE - 1 - displayY else displayY
          for (displayX in 0 until TILE_SIZE) {
            val sourceX = if (cell.xFlip) TILE_SIZE - 1 - displayX else displayX
            val colorIndex = tile.pixels[sourceY * TILE_SIZE + sourceX]
            target.setRGB(
                cell.column * TILE_SIZE + displayX,
                cell.row * TILE_SIZE + displayY,
                opaque(palette.color(colorIndex)),
            )
          }
        }
      }
    }
  }

  private fun updatePreferredSize() {
    preferredSize = Dimension(MAP_PIXELS * zoom, if (image == null) 160 else MAP_PIXELS * zoom)
    revalidate()
    repaint()
  }

  private fun selectionBounds(): Rectangle {
    val index = selectedIndex.coerceAtLeast(0)
    return Rectangle(
        index % MAP_WIDTH * TILE_SIZE * zoom,
        index / MAP_WIDTH * TILE_SIZE * zoom,
        TILE_SIZE * zoom,
        TILE_SIZE * zoom,
    )
  }

  private companion object {
    const val TILE_SIZE = 8
    const val MAP_WIDTH = 32
    const val MAP_ENTRIES = MAP_WIDTH * MAP_WIDTH
    const val MAP_PIXELS = MAP_WIDTH * TILE_SIZE
    const val DEFAULT_ZOOM = 2
    const val MIN_ZOOM = 1
    const val MAX_ZOOM = 4
  }
}

internal class DebuggerOamThumbnailCanvas(
    private val onSelection: (DebuggerGraphicalObject) -> Unit,
) : DebuggerGraphicsCanvas("Graphical OAM sprite thumbnails", "No graphical OAM objects loaded") {
  private var model: DebuggerGraphicsRenderModel? = null
  private var objects: List<DebuggerGraphicalObject> = emptyList()
  private var image: BufferedImage? = null
  private var zoom = DEFAULT_ZOOM

  override val itemCount: Int
    get() = objects.size

  val selectedObject: DebuggerGraphicalObject?
    get() = objects.getOrNull(selectedIndex)

  fun render(nextModel: DebuggerGraphicsRenderModel) {
    model = nextModel
    objects = nextModel.objects.sortedBy { it.index }
    rebuildImage()
    updatePreferredSize()
    selectIndex(selectedIndex.takeIf { it in objects.indices } ?: 0, false)
  }

  fun selectObject(value: DebuggerGraphicalObject, notify: Boolean = false) {
    objects.indexOfFirst { it.index == value.index }.takeIf { it >= 0 }?.let {
      selectIndex(it, notify)
    }
  }

  fun setZoom(value: Int) {
    zoom = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
    updatePreferredSize()
  }

  override fun clear() {
    model = null
    objects = emptyList()
    image = null
    selectedIndex = -1
    getAccessibleContext().accessibleDescription = emptyDescription
    updatePreferredSize()
    repaint()
  }

  override fun paintComponent(graphics: Graphics) {
    super.paintComponent(graphics)
    val graphics2d = prepareGraphics(graphics)
    try {
      val source = image
      if (source == null) {
        paintEmpty(graphics2d)
        return
      }
      paintCheckerboard(graphics2d, width, height, 8)
      graphics2d.drawImage(source, 0, 0, source.width * zoom, source.height * zoom, null)
      if (selectedIndex in objects.indices) {
        val column = selectedIndex % COLUMNS
        val row = selectedIndex / COLUMNS
        graphics2d.color = selectionColor()
        graphics2d.stroke = BasicStroke(2f)
        graphics2d.drawRect(column * CELL_WIDTH * zoom, row * CELL_HEIGHT * zoom, CELL_WIDTH * zoom - 1, CELL_HEIGHT * zoom - 1)
      }
      paintFocus(graphics2d)
    } finally {
      graphics2d.dispose()
    }
  }

  override fun indexAt(point: Point): Int? {
    val column = point.x / (CELL_WIDTH * zoom)
    val row = point.y / (CELL_HEIGHT * zoom)
    if (column !in 0 until COLUMNS) return null
    return (row * COLUMNS + column).takeIf { it in objects.indices }
  }

  override fun moveSelection(horizontal: Int, vertical: Int) {
    if (objects.isEmpty()) return
    val current = selectedIndex.coerceAtLeast(0)
    val column = (current % COLUMNS + horizontal).coerceIn(0, COLUMNS - 1)
    val row = (current / COLUMNS + vertical).coerceIn(0, (objects.lastIndex / COLUMNS))
    selectIndex(row * COLUMNS + column, true)
    scrollRectToVisible(
        Rectangle(column * CELL_WIDTH * zoom, row * CELL_HEIGHT * zoom, CELL_WIDTH * zoom, CELL_HEIGHT * zoom)
    )
  }

  override fun selectionChanged(index: Int, notify: Boolean) {
    if (notify) objects.getOrNull(index)?.let(onSelection)
  }

  override fun selectedDescription(index: Int): String =
      objects.getOrNull(index)?.let { "OAM object ${it.index}. ${it.accessibilityText}" }
          ?: emptyDescription

  private fun rebuildImage() {
    val currentModel = model
    if (currentModel == null || objects.isEmpty()) {
      image = null
      return
    }
    val rows = (objects.size + COLUMNS - 1) / COLUMNS
    image = BufferedImage(COLUMNS * CELL_WIDTH, rows * CELL_HEIGHT, BufferedImage.TYPE_INT_ARGB).also {
      target ->
      val graphics = target.createGraphics()
      try {
        graphics.composite = java.awt.AlphaComposite.Clear
        graphics.fillRect(0, 0, target.width, target.height)
        graphics.composite = java.awt.AlphaComposite.SrcOver
      } finally {
        graphics.dispose()
      }
      objects.forEachIndexed { index, value ->
        val originX = index % COLUMNS * CELL_WIDTH + (CELL_WIDTH - value.width) / 2
        val originY = index / COLUMNS * CELL_HEIGHT + (CELL_HEIGHT - value.height) / 2
        drawObject(target, currentModel, value, originX, originY)
      }
    }
  }

  private fun updatePreferredSize() {
    val rows = ((objects.size + COLUMNS - 1) / COLUMNS).coerceAtLeast(2)
    preferredSize = Dimension(COLUMNS * CELL_WIDTH * zoom, rows * CELL_HEIGHT * zoom)
    revalidate()
    repaint()
  }

  private companion object {
    const val COLUMNS = 10
    const val CELL_WIDTH = 12
    const val CELL_HEIGHT = 20
    const val DEFAULT_ZOOM = 2
    const val MIN_ZOOM = 1
    const val MAX_ZOOM = 4
  }
}

internal class DebuggerOamPlacementCanvas(
    private val onSelection: (DebuggerGraphicalObject) -> Unit,
) : DebuggerGraphicsCanvas("Graphical OAM screen placement", "No graphical OAM placement loaded") {
  private var model: DebuggerGraphicsRenderModel? = null
  private var objects: List<DebuggerGraphicalObject> = emptyList()
  private var image: BufferedImage? = null
  private var zoom = DEFAULT_ZOOM
  private var showGrid = true

  override val itemCount: Int
    get() = objects.size

  val selectedObject: DebuggerGraphicalObject?
    get() = objects.getOrNull(selectedIndex)

  fun render(nextModel: DebuggerGraphicsRenderModel) {
    model = nextModel
    objects = nextModel.objects.sortedBy { it.index }
    rebuildImage()
    preferredSize = Dimension(SCREEN_WIDTH * zoom, SCREEN_HEIGHT * zoom)
    revalidate()
    selectIndex(selectedIndex.takeIf { it in objects.indices } ?: 0, false)
  }

  fun setGridVisible(visible: Boolean) {
    showGrid = visible
    repaint()
  }

  fun setZoom(value: Int) {
    zoom = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
    preferredSize = Dimension(SCREEN_WIDTH * zoom, SCREEN_HEIGHT * zoom)
    revalidate()
    repaint()
  }

  fun selectObject(value: DebuggerGraphicalObject, notify: Boolean = false) {
    objects.indexOfFirst { it.index == value.index }.takeIf { it >= 0 }?.let {
      selectIndex(it, notify)
    }
  }

  override fun clear() {
    model = null
    objects = emptyList()
    image = null
    selectedIndex = -1
    getAccessibleContext().accessibleDescription = emptyDescription
    preferredSize = Dimension(SCREEN_WIDTH * zoom, SCREEN_HEIGHT * zoom)
    revalidate()
    repaint()
  }

  override fun paintComponent(graphics: Graphics) {
    super.paintComponent(graphics)
    val graphics2d = prepareGraphics(graphics)
    try {
      val source = image
      if (source == null) {
        paintEmpty(graphics2d)
        return
      }
      paintCheckerboard(graphics2d, SCREEN_WIDTH * zoom, SCREEN_HEIGHT * zoom, 8 * zoom)
      graphics2d.drawImage(source, 0, 0, SCREEN_WIDTH * zoom, SCREEN_HEIGHT * zoom, null)
      if (showGrid) {
        graphics2d.color = withAlpha(uiColor("Separator.foreground", Color.GRAY), 80)
        graphics2d.stroke = BasicStroke(1f)
        for (x in 8 until SCREEN_WIDTH step 8) graphics2d.drawLine(x * zoom, 0, x * zoom, SCREEN_HEIGHT * zoom)
        for (y in 8 until SCREEN_HEIGHT step 8) graphics2d.drawLine(0, y * zoom, SCREEN_WIDTH * zoom, y * zoom)
      }
      selectedObject?.let { value ->
        graphics2d.color = selectionColor()
        graphics2d.stroke = BasicStroke(2f)
        graphics2d.drawRect(
            value.x * zoom,
            value.y * zoom,
            value.width * zoom - 1,
            value.height * zoom - 1,
        )
      }
      paintFocus(graphics2d)
    } finally {
      graphics2d.dispose()
    }
  }

  override fun indexAt(point: Point): Int? {
    val screenX = point.x / zoom
    val screenY = point.y / zoom
    return objects.indexOfFirst { value ->
      screenX in value.x until (value.x + value.width) &&
          screenY in value.y until (value.y + value.height)
    }.takeIf { it >= 0 }
  }

  override fun moveSelection(horizontal: Int, vertical: Int) {
    if (objects.isEmpty()) return
    val delta = if (vertical != 0) vertical * 10 else horizontal
    selectIndex((selectedIndex.coerceAtLeast(0) + delta).coerceIn(0, objects.lastIndex), true)
  }

  override fun selectionChanged(index: Int, notify: Boolean) {
    if (notify) objects.getOrNull(index)?.let(onSelection)
  }

  override fun selectedDescription(index: Int): String =
      objects.getOrNull(index)?.let {
        "OAM object ${it.index} at screen X ${it.x}, Y ${it.y}; ${it.width} by ${it.height}; ${it.visibilityText}. ${it.accessibilityText}"
      } ?: emptyDescription

  private fun rebuildImage() {
    val currentModel = model
    if (currentModel == null || objects.isEmpty()) {
      image = null
      return
    }
    image = BufferedImage(SCREEN_WIDTH, SCREEN_HEIGHT, BufferedImage.TYPE_INT_ARGB).also { target ->
      // Lowest OAM indexes remain visible on top in this diagnostic placement view.
      objects.asReversed().forEach { value -> drawObject(target, currentModel, value, value.x, value.y) }
    }
  }

  private companion object {
    const val SCREEN_WIDTH = 160
    const val SCREEN_HEIGHT = 144
    const val DEFAULT_ZOOM = 2
    const val MIN_ZOOM = 1
    const val MAX_ZOOM = 4
  }
}

internal class DebuggerPaletteCanvas(
    private val onSelection: (DebuggerGraphicalPalette, DebuggerGraphicalSwatch) -> Unit,
) : DebuggerGraphicsCanvas("Graphical graphics palette swatches", "No graphical palettes loaded") {
  private var palettes: List<DebuggerGraphicalPalette> = emptyList()
  private var selectedColor = 0

  override val itemCount: Int
    get() = palettes.size

  val selectedPalette: DebuggerGraphicalPalette?
    get() = palettes.getOrNull(selectedIndex)

  val selectedSwatch: DebuggerGraphicalSwatch?
    get() = selectedPalette?.swatches?.firstOrNull { it.colorIndex == selectedColor }

  fun render(model: DebuggerGraphicsRenderModel) {
    palettes = model.palettes
    preferredSize = Dimension(PREFERRED_WIDTH, (palettes.size * ROW_HEIGHT).coerceAtLeast(120))
    revalidate()
    selectIndex(selectedIndex.takeIf { it in palettes.indices } ?: 0, false)
  }

  fun selectSwatch(
      palette: DebuggerGraphicalPalette,
      swatch: DebuggerGraphicalSwatch,
      notify: Boolean = false,
  ) {
    val index = palettes.indexOfFirst { it.group == palette.group && it.label == palette.label }
    if (index < 0) return
    selectedColor = swatch.colorIndex
    selectIndex(index, notify)
  }

  override fun clear() {
    palettes = emptyList()
    selectedIndex = -1
    selectedColor = 0
    getAccessibleContext().accessibleDescription = emptyDescription
    preferredSize = Dimension(PREFERRED_WIDTH, 120)
    revalidate()
    repaint()
  }

  override fun paintComponent(graphics: Graphics) {
    super.paintComponent(graphics)
    val graphics2d = prepareGraphics(graphics)
    try {
      if (palettes.isEmpty()) {
        paintEmpty(graphics2d)
        return
      }
      graphics2d.color = background
      graphics2d.fillRect(0, 0, width, height)
      val labelColor = uiColor("Label.foreground", foreground)
      val muted = uiColor("Label.disabledForeground", Color.GRAY)
      palettes.forEachIndexed { row, palette ->
        val y = row * ROW_HEIGHT
        graphics2d.color = if (row == selectedIndex) uiColor("Table.selectionBackground", Color(70, 90, 120)) else background
        graphics2d.fillRect(0, y, width, ROW_HEIGHT)
        graphics2d.color = if (row == selectedIndex) uiColor("Table.selectionForeground", Color.WHITE) else labelColor
        graphics2d.drawString(palette.displayName, 6, y + 18)
        for (colorIndex in 0 until COLORS_PER_PALETTE) {
          val swatch = palette.swatches.firstOrNull { it.colorIndex == colorIndex }
          val x = LABEL_WIDTH + colorIndex * SWATCH_WIDTH
          val transparent = swatch?.transparent == true
          if (transparent) {
            paintCheckerboard(graphics2d, x, y + 3, SWATCH_WIDTH - 3, ROW_HEIGHT - 6, 5)
            val stored = Color(swatch?.rgb888 ?: palette.color(colorIndex))
            graphics2d.color = Color(stored.red, stored.green, stored.blue, 115)
          } else {
            graphics2d.color = Color(swatch?.rgb888 ?: palette.color(colorIndex))
          }
          graphics2d.fillRect(x, y + 3, SWATCH_WIDTH - 3, ROW_HEIGHT - 6)
          graphics2d.color = muted
          graphics2d.drawRect(x, y + 3, SWATCH_WIDTH - 3, ROW_HEIGHT - 6)
          if (transparent) {
            graphics2d.drawLine(x + 2, y + ROW_HEIGHT - 5, x + SWATCH_WIDTH - 6, y + 5)
          }
          if (row == selectedIndex && colorIndex == selectedColor) {
            graphics2d.color = selectionColor()
            graphics2d.stroke = BasicStroke(2f)
            graphics2d.drawRect(x - 1, y + 2, SWATCH_WIDTH - 1, ROW_HEIGHT - 4)
          }
        }
      }
      paintFocus(graphics2d)
    } finally {
      graphics2d.dispose()
    }
  }

  override fun indexAt(point: Point): Int? =
      (point.y / ROW_HEIGHT).takeIf { it in palettes.indices }

  override fun moveSelection(horizontal: Int, vertical: Int) {
    if (palettes.isEmpty()) return
    if (horizontal != 0) selectedColor = (selectedColor + horizontal).coerceIn(0, COLORS_PER_PALETTE - 1)
    val row = (selectedIndex.coerceAtLeast(0) + vertical).coerceIn(0, palettes.lastIndex)
    selectIndex(row, true)
    scrollRectToVisible(Rectangle(0, row * ROW_HEIGHT, preferredSize.width, ROW_HEIGHT))
  }

  override fun selectionChanged(index: Int, notify: Boolean) {
    if (!notify) return
    val palette = palettes.getOrNull(index) ?: return
    palette.swatches.firstOrNull { it.colorIndex == selectedColor }?.let { onSelection(palette, it) }
  }

  override fun selectedDescription(index: Int): String {
    val palette = palettes.getOrNull(index) ?: return emptyDescription
    val swatch = palette.swatches.firstOrNull { it.colorIndex == selectedColor }
    return "${palette.displayName}, color $selectedColor. ${swatch?.accessibilityText.orEmpty()}"
  }

  override fun getToolTipText(event: MouseEvent): String? {
    val row = indexAt(event.point) ?: return null
    val color = ((event.x - LABEL_WIDTH) / SWATCH_WIDTH).coerceIn(0, COLORS_PER_PALETTE - 1)
    val palette = palettes[row]
    val swatch = palette.swatches.firstOrNull { it.colorIndex == color }
    return "${palette.displayName}, color $color. ${swatch?.accessibilityText.orEmpty()}"
  }

  override fun processMouseEvent(event: MouseEvent) {
    if (event.id == MouseEvent.MOUSE_PRESSED && SwingUtilities.isLeftMouseButton(event)) {
      selectedColor = ((event.x - LABEL_WIDTH) / SWATCH_WIDTH).coerceIn(0, COLORS_PER_PALETTE - 1)
    }
    super.processMouseEvent(event)
  }

  private companion object {
    const val LABEL_WIDTH = 150
    const val SWATCH_WIDTH = 46
    const val ROW_HEIGHT = 28
    const val COLORS_PER_PALETTE = 4
    const val PREFERRED_WIDTH = LABEL_WIDTH + COLORS_PER_PALETTE * SWATCH_WIDTH + 8
  }
}

private fun drawObject(
    target: BufferedImage,
    model: DebuggerGraphicsRenderModel,
    value: DebuggerGraphicalObject,
    destinationX: Int,
    destinationY: Int,
) {
  val palette = model.objectPalette(value)
  for (displayY in 0 until value.height) {
    val sourceY = if (value.yFlip) value.height - 1 - displayY else displayY
    val tileAddress = value.tileAddress + sourceY / 8 * 16
    val tile = model.tile(value.bank, tileAddress) ?: continue
    val tileY = sourceY % 8
    for (displayX in 0 until value.width) {
      val sourceX = if (value.xFlip) value.width - 1 - displayX else displayX
      val colorIndex = tile.pixels[tileY * 8 + sourceX.coerceIn(0, 7)]
      if (colorIndex == 0 || palette.isTransparent(colorIndex)) continue
      val x = destinationX + displayX
      val y = destinationY + displayY
      if (x in 0 until target.width && y in 0 until target.height) {
        target.setRGB(x, y, opaque(palette.color(colorIndex)))
      }
    }
  }
}

private fun paintTileGrid(
    graphics: Graphics2D,
    columns: Int,
    rows: Int,
    zoom: Int,
) {
  graphics.color = withAlpha(uiColor("Separator.foreground", Color.GRAY), 105)
  graphics.stroke = BasicStroke(1f)
  val cell = 8 * zoom
  for (column in 1 until columns) graphics.drawLine(column * cell, 0, column * cell, rows * cell)
  for (row in 1 until rows) graphics.drawLine(0, row * cell, columns * cell, row * cell)
}

private fun paintCheckerboard(
    graphics: Graphics2D,
    width: Int,
    height: Int,
    cell: Int,
) = paintCheckerboard(graphics, 0, 0, width, height, cell)

private fun paintCheckerboard(
    graphics: Graphics2D,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    cell: Int,
) {
  val light = uiColor("Panel.background", Color(210, 210, 210))
  val dark = uiColor("Separator.foreground", Color(150, 150, 150))
  val rowCount = (height + cell - 1) / cell
  val columnCount = (width + cell - 1) / cell
  for (row in 0 until rowCount) {
    for (column in 0 until columnCount) {
      graphics.color = if ((row + column) and 1 == 0) light else dark
      graphics.fillRect(
          x + column * cell,
          y + row * cell,
          cell.coerceAtMost(width - column * cell),
          cell.coerceAtMost(height - row * cell),
      )
    }
  }
}

private fun opaque(rgb888: Int): Int = 0xff000000.toInt() or (rgb888 and 0xffffff)

private fun withAlpha(color: Color, alpha: Int): Color =
    Color(color.red, color.green, color.blue, alpha.coerceIn(0, 255))

private fun uiColor(key: String, fallback: Color): Color = UIManager.getColor(key) ?: fallback
