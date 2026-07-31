package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.debug.DebugAudioChannelInspection
import eu.rekawek.coffeegb.core.debug.DebugAudioInspection
import eu.rekawek.coffeegb.core.debug.DebugByteData
import eu.rekawek.coffeegb.core.debug.DebugGraphicsHardwareMode
import eu.rekawek.coffeegb.core.debug.DebugGraphicsInspection
import java.awt.Color
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class DebuggerPeripheralPanelsTest {

  @Test
  fun `graphics render model decodes tile pixels map attributes objects and palettes`() {
    val view = DebuggerPeripheralPanePreparation.graphics(SNAPSHOT_IDENTITY, graphicsInspection())
    val model = DebuggerGraphicsRenderModelFactory.create(view)

    assertEquals(768, model.tiles.size)
    assertContentEquals(
        intArrayOf(3, 2, 1, 0, 3, 2, 1, 0),
        model.tiles.first().pixels.copyOfRange(0, 8),
    )
    val windowCell = model.windowCells.first()
    assertEquals(1, windowCell.bank)
    assertEquals(5, windowCell.palette)
    assertEquals(0x8030, windowCell.tileAddress)
    assertTrue(windowCell.xFlip)
    assertTrue(windowCell.yFlip)
    val firstObject = model.objects.first()
    assertEquals(0, firstObject.x)
    assertEquals(0, firstObject.y)
    assertEquals(0x8070, firstObject.tileAddress)
    assertEquals(1, firstObject.bank)
    assertEquals(19, model.palettes.size)
    assertEquals(4, model.palettes.first().swatches.size)
  }

  @Test
  fun `worker preparation decodes complete graphics and audio payloads into text views`() {
    val threadName = AtomicReference<String>()
    val executor =
        Executors.newSingleThreadExecutor { command -> Thread(command, "debugger-pane-decode-test") }
    try {
      val graphicsFuture =
          CompletableFuture.supplyAsync(
              {
                threadName.set(Thread.currentThread().name)
                assertFalse(SwingUtilities.isEventDispatchThread())
                DebuggerPeripheralPanePreparation.graphics(SNAPSHOT_IDENTITY, graphicsInspection())
              },
              executor,
          )
      val audioFuture =
          CompletableFuture.supplyAsync(
              { DebuggerPeripheralPanePreparation.audio(SNAPSHOT_IDENTITY, audioInspection()) },
              executor,
          )
      val graphics = graphicsFuture.get(5, TimeUnit.SECONDS)
      val audio = audioFuture.get(5, TimeUnit.SECONDS)

      assertEquals("debugger-pane-decode-test", threadName.get())
      assertEquals(SNAPSHOT_IDENTITY, graphics.identity)
      assertEquals(SNAPSHOT_IDENTITY, audio.identity)
      assertContains(graphics.overviewText, SNAPSHOT_IDENTITY.label)
      assertContains(audio.overviewText, SNAPSHOT_IDENTITY.label)
      assertEquals(768, graphics.tileRows.size)
      assertEquals(setOf(0, 1), graphics.tileRows.map { it.bank }.toSet())
      assertEquals(1024, graphics.backgroundMapRows.size)
      assertEquals(1024, graphics.windowMapRows.size)
      assertEquals(40, graphics.objectRows.size)
      assertEquals(76, graphics.paletteRows.size)
      assertSame(graphics.renderModel, DebuggerGraphicsRenderModelFactory.create(graphics))
      assertContentEquals(
          intArrayOf(3, 2, 1, 0, 3, 2, 1, 0),
          graphics.renderModel.tiles.first().pixels.copyOfRange(0, 8),
      )
      assertEquals(768, graphics.tableData.tiles.size)
      assertEquals(1024, graphics.tableData.backgroundMap.size)
      assertEquals(1024, graphics.tableData.windowMap.size)
      assertContains(graphics.tileRows.first().colorIndexRows, "32103210")
      assertContains(graphics.objectRows.first().accessibilityText, "Object 0")
      assertContains(graphics.objectRows.first().priorityText, "master priority is off")
      assertTrue(graphics.paletteRows.all { it.rawValueText.isNotBlank() })
      assertTrue(graphics.paletteRows.all { it.hexColor.startsWith("#") })

      assertEquals(4, audio.channelRows.size)
      assertEquals(23, audio.registerRows.size)
      assertEquals(32, audio.waveRows.size)
      assertEquals("left and right", audio.channelRows.first().routingText)
      assertContains(audio.channelRows.first().accessibilityText, "Channel 1")
      assertContains(audio.overviewText, "gain 8 of 8")
      assertContains(audio.overviewText, "gain 3 of 8")
      assertEquals((0 until 16).toList() + (0 until 16).toList(),
          audio.waveRows.map { it.decimalValue })
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `graphics panel renders copyable accessible tables then releases all rendered state`() {
    val view =
        DebuggerPeripheralPanePreparation.graphics(SNAPSHOT_IDENTITY, graphicsInspection())
    val copied = AtomicReference<String>()
    val panel = onEdt { DebuggerGraphicsPanel(copied::set) }

    onEdt {
      panel.render(view)
      assertEquals(768, panel.tileTable.rowCount)
      assertEquals(1024, panel.backgroundMapTable.rowCount)
      assertEquals(1024, panel.windowMapTable.rowCount)
      assertEquals(40, panel.objectTable.rowCount)
      assertEquals(76, panel.paletteTable.rowCount)
      assertEquals(384, panel.tileAtlasCanvas.itemCount)
      assertEquals(1024, panel.backgroundMapCanvas.itemCount)
      assertEquals(1024, panel.windowMapCanvas.itemCount)
      assertEquals(40, panel.objectThumbnailCanvas.itemCount)
      assertEquals(40, panel.objectPlacementCanvas.itemCount)
      assertEquals(19, panel.paletteCanvas.itemCount)
      assertEquals("Graphical VRAM tile atlas", panel.tileAtlasCanvas.accessibleContext.accessibleName)
      assertEquals(
          "Graphical OAM screen placement",
          panel.objectPlacementCanvas.accessibleContext.accessibleName,
      )
      assertFalse(panel.tileBankSelector.isEditable)
      assertFalse(panel.tilePaletteSelector.isEditable)

      panel.windowMapCanvas.selectCellIndex(0)
      assertEquals(0, panel.windowMapTable.selectedRow)
      assertEquals(1, panel.tileAtlasCanvas.displayedBank)
      assertEquals(3, panel.tileAtlasCanvas.selectedTile?.index)
      assertEquals(5, panel.paletteCanvas.selectedPalette?.index)
      assertEquals("CGB background", panel.paletteCanvas.selectedPalette?.group)
      performKey(panel.windowMapCanvas, KeyEvent.VK_RIGHT)
      assertEquals(1, panel.windowMapTable.selectedRow)
      assertEquals(1, panel.windowMapCanvas.selectedCell?.column)
      assertContains(panel.windowMapCanvas.accessibleContext.accessibleDescription, "column 1")

      val model = DebuggerGraphicsRenderModelFactory.create(view)
      panel.objectThumbnailCanvas.selectObject(model.objects.first(), notify = true)
      assertEquals(0, panel.objectTable.selectedRow)
      assertEquals(0, panel.objectPlacementCanvas.selectedObject?.index)
      assertEquals(7, panel.tileAtlasCanvas.selectedTile?.index)
      assertEquals("CGB object", panel.paletteCanvas.selectedPalette?.group)

      val atlasImage =
          BufferedImage(
              panel.tileAtlasCanvas.preferredSize.width,
              panel.tileAtlasCanvas.preferredSize.height,
              BufferedImage.TYPE_INT_RGB,
          )
      panel.tileAtlasCanvas.size = panel.tileAtlasCanvas.preferredSize
      val atlasGraphics = atlasImage.createGraphics()
      try {
        panel.tileAtlasCanvas.paint(atlasGraphics)
      } finally {
        atlasGraphics.dispose()
      }
      val sampledColors =
          sequence {
                for (y in 0 until atlasImage.height step 8) {
                  for (x in 0 until atlasImage.width step 8) yield(atlasImage.getRGB(x, y))
                }
              }
              .toSet()
      assertTrue(sampledColors.size > 1)
      assertEquals("Graphics debugger pane", panel.accessibleContext.accessibleName)
      assertContains(panel.accessibleContext.accessibleDescription, "graphics")
      assertEquals("Object attribute memory", panel.objectTable.accessibleContext.accessibleName)
      assertContains(panel.objectTable.accessibleContext.accessibleDescription, "40 OAM")

      val preview = assertIs<DebuggerPalettePreview>(panel.paletteTable.getValueAt(0, 8))
      assertEquals(panel.paletteTable.getValueAt(0, 6), preview.hexColor)
      assertTrue(panel.paletteTable.getValueAt(0, 4).toString().isNotBlank())
      assertContains(panel.paletteTable.getValueAt(0, 5).toString(), "R ")
      assertContains(panel.objectTable.getValueAt(0, 9).toString(), "master priority is off")
      assertContains(panel.overviewArea.text, SNAPSHOT_IDENTITY.label)

      val redRow =
          (0 until panel.paletteTable.rowCount).first { row ->
            panel.paletteTable.getValueAt(row, 6) == "#FF0000"
          }
      val redPreview = assertIs<DebuggerPalettePreview>(panel.paletteTable.getValueAt(redRow, 8))
      val redCell =
          panel.paletteTable
              .getCellRenderer(redRow, 8)
              .getTableCellRendererComponent(
                  panel.paletteTable,
                  redPreview,
                  false,
                  false,
                  redRow,
                  8,
              ) as JLabel
      assertEquals(Color.BLACK, redCell.foreground)

      val originalHeight = panel.objectTable.rowHeight
      val originalWidth = panel.objectTable.columnModel.getColumn(2).preferredWidth
      panel.applyFontScale(150)
      assertTrue(panel.objectTable.rowHeight > originalHeight)
      assertEquals((originalWidth * 1.5f).toInt(),
          panel.objectTable.columnModel.getColumn(2).preferredWidth)
      assertEquals(
          panel.objectTable.columnModel.getColumn(2).preferredWidth,
          panel.objectTable.columnModel.getColumn(2).width,
      )
      panel.applyFontScale(100)
      assertEquals(originalWidth, panel.objectTable.columnModel.getColumn(2).preferredWidth)

      panel.tabs.selectedIndex = 5
      panel.paletteTable.setRowSelectionInterval(0, 0)
      performCopy(panel.paletteTable)
      assertContains(copied.get(), "Components")
      assertContains(copied.get(), preview.hexColor)

      panel.clear()
      assertEquals(0, panel.tileTable.rowCount)
      assertEquals(0, panel.backgroundMapTable.rowCount)
      assertEquals(0, panel.windowMapTable.rowCount)
      assertEquals(0, panel.objectTable.rowCount)
      assertEquals(0, panel.paletteTable.rowCount)
      assertEquals(0, panel.tileAtlasCanvas.itemCount)
      assertEquals(0, panel.backgroundMapCanvas.itemCount)
      assertEquals(0, panel.windowMapCanvas.itemCount)
      assertEquals(0, panel.objectThumbnailCanvas.itemCount)
      assertEquals(0, panel.objectPlacementCanvas.itemCount)
      assertEquals(0, panel.paletteCanvas.itemCount)
      assertContains(panel.overviewArea.text, "No graphics")
      assertContains(panel.accessibleContext.accessibleDescription, "not retained")
    }
  }

  @Test
  fun `audio panel renders textual routing registers and wave samples then clears graph state`() {
    val view = DebuggerPeripheralPanePreparation.audio(SNAPSHOT_IDENTITY, audioInspection())
    val copied = AtomicReference<String>()
    val panel = onEdt { DebuggerAudioPanel(copied::set) }

    onEdt {
      panel.render(view)
      assertEquals(4, panel.channelTable.rowCount)
      assertEquals(23, panel.registerTable.rowCount)
      assertEquals(32, panel.waveTable.rowCount)
      assertEquals(32, panel.waveGraph.sampleCount)
      assertEquals("Audio debugger pane", panel.accessibleContext.accessibleName)
      assertEquals("Audio channels and routing", panel.channelTable.accessibleContext.accessibleName)
      assertContains(panel.channelTable.getValueAt(0, 6).toString(), "left and right")
      assertContains(panel.registerTable.getValueAt(0, 4).toString(), "Left volume")
      assertContains(panel.overviewArea.text, "gain 8 of 8")
      assertContains(panel.overviewArea.text, "gain 3 of 8")
      assertContains(panel.overviewArea.text, SNAPSHOT_IDENTITY.label)
      assertEquals("15 of 15", panel.waveTable.getValueAt(15, 3))
      assertContains(panel.waveGraph.accessibleContext.accessibleDescription, "32 wave samples")
      assertContains(panel.waveGraph.accessibleContext.accessibleDescription, "15")

      val originalHeight = panel.waveTable.rowHeight
      panel.applyFontScale(175)
      assertTrue(panel.waveTable.rowHeight > originalHeight)

      panel.tabs.selectedIndex = 3
      panel.waveTable.setRowSelectionInterval(15, 15)
      performCopy(panel.waveTable)
      assertContains(copied.get(), "Sample\tHex\tDecimal\tLevel")
      assertContains(copied.get(), "15 of 15")

      panel.clear()
      assertEquals(0, panel.channelTable.rowCount)
      assertEquals(0, panel.registerTable.rowCount)
      assertEquals(0, panel.waveTable.rowCount)
      assertEquals(0, panel.waveGraph.sampleCount)
      assertContains(panel.overviewArea.text, "No audio")
      assertContains(panel.waveGraph.accessibleContext.accessibleDescription, "No wave samples")
      assertContains(panel.accessibleContext.accessibleDescription, "not retained")
    }
  }

  @Test
  fun `panel mutation APIs reject non EDT callers`() {
    val graphics =
        DebuggerPeripheralPanePreparation.graphics(SNAPSHOT_IDENTITY, graphicsInspection())
    val audio = DebuggerPeripheralPanePreparation.audio(SNAPSHOT_IDENTITY, audioInspection())
    val graphicsPanel = onEdt { DebuggerGraphicsPanel {} }
    val audioPanel = onEdt { DebuggerAudioPanel {} }

    assertFailsWith<IllegalStateException> { graphicsPanel.render(graphics) }
    assertFailsWith<IllegalStateException> { graphicsPanel.showNotCaptured(SNAPSHOT_IDENTITY) }
    assertFailsWith<IllegalStateException> { graphicsPanel.clear() }
    assertFailsWith<IllegalStateException> { graphicsPanel.applyFontScale(125) }
    assertFailsWith<IllegalStateException> { audioPanel.render(audio) }
    assertFailsWith<IllegalStateException> { audioPanel.showNotCaptured(SNAPSHOT_IDENTITY) }
    assertFailsWith<IllegalStateException> { audioPanel.clear() }
    assertFailsWith<IllegalStateException> { audioPanel.copyText() }
  }

  private fun performCopy(component: JComponent) {
    val key = KeyStroke.getKeyStroke(KeyEvent.VK_C, peripheralMenuShortcutMask())
    val actionKey = assertNotNull(component.getInputMap(JComponent.WHEN_FOCUSED).get(key))
    assertNotNull(component.actionMap.get(actionKey)).actionPerformed(null)
  }

  private fun performKey(component: JComponent, keyCode: Int) {
    val key = KeyStroke.getKeyStroke(keyCode, 0)
    val actionKey = assertNotNull(component.getInputMap(JComponent.WHEN_FOCUSED).get(key))
    assertNotNull(component.actionMap.get(actionKey)).actionPerformed(null)
  }

  private fun graphicsInspection(): DebugGraphicsInspection {
    val bank0 = ByteArray(VRAM_LENGTH)
    bank0[0] = 0xaa.toByte()
    bank0[1] = 0xcc.toByte()
    bank0[0x1800] = 3
    val bank1 = ByteArray(VRAM_LENGTH)
    bank1[0] = 0xff.toByte()
    bank1[1] = 0x00
    bank1[0x1800] = 0xed.toByte()
    val oam = ByteArray(OAM_LENGTH)
    oam[0] = 16
    oam[1] = 8
    oam[2] = 7
    oam[3] = 0xed.toByte()
    val backgroundPalettes = ByteArray(PALETTE_LENGTH)
    backgroundPalettes[0] = 0x1f
    backgroundPalettes[1] = 0
    val objectPalettes = ByteArray(PALETTE_LENGTH)
    objectPalettes[0] = 0
    objectPalettes[1] = 0x7c
    return DebugGraphicsInspection(
        DebugGraphicsHardwareMode.CGB_NATIVE,
        1,
        0x18,
        0xe4,
        0xd2,
        0x1b,
        0x85,
        0x02,
        DebugByteData(bank0),
        DebugByteData(bank1),
        DebugByteData(oam),
        DebugByteData(backgroundPalettes),
        DebugByteData(objectPalettes),
    )
  }

  private fun audioInspection(): DebugAudioInspection =
      DebugAudioInspection(
          true,
          5,
          0xfa,
          0x35,
          0xf1,
          listOf(
              DebugAudioChannelInspection(
                  1,
                  true,
                  true,
                  15,
                  32,
                  true,
                  0x6d,
                  0xc1,
                  0xf3,
                  0x34,
                  0xc5,
              ),
              DebugAudioChannelInspection(
                  2,
                  false,
                  true,
                  6,
                  12,
                  false,
                  0,
                  0x40,
                  0x8a,
                  0x56,
                  0x02,
              ),
              DebugAudioChannelInspection(
                  3,
                  false,
                  true,
                  9,
                  200,
                  true,
                  0x80,
                  0x38,
                  0x40,
                  0x78,
                  0x03,
              ),
              DebugAudioChannelInspection(
                  4,
                  false,
                  true,
                  4,
                  7,
                  true,
                  0,
                  0x3f,
                  0xa5,
                  0xbf,
                  0xc0,
              ),
          ),
          DebugByteData(
              ByteArray(16) { index ->
                (((index * 2) and 0x0f) shl 4 or ((index * 2 + 1) and 0x0f)).toByte()
              }
          ),
      )

  private fun <T> onEdt(action: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return action()
    val task = FutureTask(action)
    SwingUtilities.invokeAndWait(task)
    return task.get()
  }

  private companion object {
    val SNAPSHOT_IDENTITY = DebuggerSnapshotIdentity(7, 11, 250)
    const val VRAM_LENGTH = 0x2000
    const val OAM_LENGTH = 0xa0
    const val PALETTE_LENGTH = 0x40
  }
}
