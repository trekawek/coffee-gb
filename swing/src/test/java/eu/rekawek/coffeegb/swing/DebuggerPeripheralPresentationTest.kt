package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.debug.DebugAudioChannelInspection
import eu.rekawek.coffeegb.core.debug.DebugAudioInspection
import eu.rekawek.coffeegb.core.debug.DebugByteData
import eu.rekawek.coffeegb.core.debug.DebugGraphicsHardwareMode
import eu.rekawek.coffeegb.core.debug.DebugGraphicsInspection
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class DebuggerPeripheralPresentationTest {

  @Test
  fun `tile addresses follow unsigned and signed Game Boy addressing at every boundary`() {
    assertEquals(
        0x8000,
        DebuggerPeripheralPresentation.tileEntry(0x00, DebuggerTileAddressing.UNSIGNED_8000)
            .tileDataAddress,
    )
    assertEquals(
        0x8ff0,
        DebuggerPeripheralPresentation.tileEntry(0xff, DebuggerTileAddressing.UNSIGNED_8000)
            .tileDataAddress,
    )

    val minus128 =
        DebuggerPeripheralPresentation.tileEntry(0x80, DebuggerTileAddressing.SIGNED_8800)
    val minus1 =
        DebuggerPeripheralPresentation.tileEntry(0xff, DebuggerTileAddressing.SIGNED_8800)
    val zero = DebuggerPeripheralPresentation.tileEntry(0x00, DebuggerTileAddressing.SIGNED_8800)
    val plus127 =
        DebuggerPeripheralPresentation.tileEntry(0x7f, DebuggerTileAddressing.SIGNED_8800)

    assertEquals(-128, minus128.signedTileNumber)
    assertEquals(0x8800, minus128.tileDataAddress)
    assertEquals(-1, minus1.signedTileNumber)
    assertEquals(0x8ff0, minus1.tileDataAddress)
    assertEquals(0x9000, zero.tileDataAddress)
    assertEquals(127, plus127.signedTileNumber)
    assertEquals(0x97f0, plus127.tileDataAddress)
  }

  @Test
  fun `tile decoder combines low and high bitplanes from most significant pixel first`() {
    val bank0 = ByteArray(VRAM_LENGTH)
    bank0[0] = 0xaa.toByte()
    bank0[1] = 0xcc.toByte()
    val inspection = graphics(vramBank0 = bank0)
    val tile =
        DebuggerPeripheralPresentation.tile(
            inspection,
            DebuggerPeripheralPresentation.tileEntry(
                0,
                DebuggerTileAddressing.UNSIGNED_8000,
            ),
        )

    assertEquals(listOf(3, 2, 1, 0, 3, 2, 1, 0), tile.pixels.first())
    assertEquals("32103210", tile.textRows.first())
    assertEquals(List(8) { 0 }, tile.pixels.last())
    assertContains(tile.accessibilityText, "color-index rows")
  }

  @Test
  fun `native CGB tile attributes select bank palette flips and priority`() {
    val bank1 = ByteArray(VRAM_LENGTH)
    bank1[14] = 0x80.toByte()
    val inspection = graphics(vramBank1 = bank1)
    val entry =
        DebuggerPeripheralPresentation.tileEntry(
            tileNumber = 0,
            addressing = DebuggerTileAddressing.UNSIGNED_8000,
            cgbAttributes = 0xed,
        )
    val tile = DebuggerPeripheralPresentation.tile(inspection, entry)

    assertTrue(entry.attributes.available)
    assertEquals(5, entry.attributes.palette)
    assertEquals(1, entry.attributes.vramBank)
    assertTrue(entry.attributes.xFlip)
    assertTrue(entry.attributes.yFlip)
    assertTrue(entry.attributes.priority)
    assertEquals("00000001", tile.textRows.first())
    assertEquals("00000000", tile.textRows.last())
    assertContains(entry.attributes.accessibilityText, "palette 5")
    assertContains(entry.attributes.accessibilityText, "background priority yes")
  }

  @Test
  fun `graphics maps obey LCDC bases and ignore CGB attributes in compatibility mode`() {
    val bank0 = ByteArray(VRAM_LENGTH)
    val bank1 = ByteArray(VRAM_LENGTH)
    bank0[0x1c00] = 0x80.toByte()
    bank1[0x1c00] = 0xef.toByte()
    val native =
        DebuggerPeripheralPresentation.graphics(
            graphics(lcdc = 0x18, vramBank0 = bank0, vramBank1 = bank1)
        )

    assertEquals(0x9c00, native.backgroundMap.baseAddress)
    assertEquals(0x9800, native.windowMap.baseAddress)
    assertEquals(DebuggerTileAddressing.UNSIGNED_8000, native.tileAddressing)
    assertEquals(0x80, native.backgroundMap.entries.first().tileNumber)
    assertEquals(0x8800, native.backgroundMap.entries.first().tileDataAddress)
    assertTrue(native.backgroundMap.entries.first().attributes.available)
    assertEquals(1, native.backgroundMap.entries.first().attributes.vramBank)

    val compatibility =
        DebuggerPeripheralPresentation.graphics(
            graphics(
                mode = DebugGraphicsHardwareMode.CGB_COMPATIBILITY,
                lcdc = 0x18,
                vramBank0 = bank0,
                vramBank1 = bank1,
            )
        )
    val compatibilityEntry = compatibility.backgroundMap.entries.first()
    assertFalse(compatibilityEntry.attributes.available)
    assertEquals(0, compatibilityEntry.attributes.vramBank)
    assertEquals("Unavailable", compatibilityEntry.attributes.rawValueText)
    assertContains(compatibilityEntry.attributes.accessibilityText, "unavailable")
    assertContains(compatibilityEntry.attributes.accessibilityText, "no per-tile CGB palette")
    assertContains(compatibility.hardwareModeText, "compatibility")
  }

  @Test
  fun `OAM presentation applies coordinates 8 by 16 tile masking and native CGB flags`() {
    val oam = ByteArray(OAM_LENGTH)
    oam[0] = 16
    oam[1] = 8
    oam[2] = 7
    oam[3] = 0xed.toByte()
    oam[4] = 0
    oam[5] = 0
    val view = DebuggerPeripheralPresentation.graphics(graphics(lcdc = 0x04, oam = oam))
    val first = view.objects.first()

    assertEquals(16, view.objectHeight)
    assertEquals(0, first.screenX)
    assertEquals(0, first.screenY)
    assertEquals(7, first.rawTileNumber)
    assertEquals(6, first.effectiveTileNumber)
    assertContains(first.tileText, "low bit ignored")
    assertEquals(5, first.palette)
    assertEquals(1, first.vramBank)
    assertTrue(first.xFlip)
    assertTrue(first.yFlip)
    assertTrue(first.behindBackground)
    assertTrue(first.visibleOnScreen)
    assertFalse(view.objects[1].visibleOnScreen)
    assertContains(first.accessibilityText, "raw 8")
    assertContains(first.priorityText, "OAM priority bit set")
    assertContains(first.priorityText, "master priority is off")
    assertContains(first.accessibilityText, first.priorityText)
  }

  @Test
  fun `native CGB OAM priority remains qualified by tile priority when master priority is on`() {
    val oam = ByteArray(OAM_LENGTH)
    oam[0] = 16
    oam[1] = 8
    val front = DebuggerPeripheralPresentation.graphics(graphics(lcdc = 0x01, oam = oam))

    assertFalse(front.objects.first().behindBackground)
    assertContains(front.objects.first().priorityText, "tile-priority attributes")

    oam[3] = 0x80.toByte()
    val behind = DebuggerPeripheralPresentation.graphics(graphics(lcdc = 0x01, oam = oam))
    assertTrue(behind.objects.first().behindBackground)
    assertContains(behind.objects.first().priorityText, "yields to nonzero background")
    assertContains(behind.objects.first().priorityText, "tile-priority attributes")
  }

  @Test
  fun `DMG object flags select OBP registers instead of CGB palette and bank bits`() {
    val oam = ByteArray(OAM_LENGTH)
    oam[0] = 17
    oam[1] = 9
    oam[2] = 3
    oam[3] = 0x1f
    val view =
        DebuggerPeripheralPresentation.graphics(
            graphics(
                mode = DebugGraphicsHardwareMode.DMG,
                lcdc = 0,
                oam = oam,
            )
        )
    val first = view.objects.first()

    assertEquals(3, first.effectiveTileNumber)
    assertEquals(1, first.palette)
    assertEquals("DMG OBP1", first.paletteText)
    assertEquals(0, first.vramBank)
    assertFalse(first.xFlip)
    assertFalse(first.yFlip)
    assertEquals(0, view.backgroundPalettes.size)
    assertEquals(0, view.objectPalettes.size)
  }

  @Test
  fun `DMG palette mappings expose neutral swatches and shade names in text`() {
    val view =
        DebuggerPeripheralPresentation.graphics(
            graphics(
                bgp = 0xe4,
                obp0 = 0x1b,
                obp1 = 0xff,
            )
        )
    val background = view.dmgPalettes[0]

    assertEquals(listOf(0, 1, 2, 3), background.swatches.map { it.rawValue })
    assertEquals(listOf("white", "light gray", "dark gray", "black"),
        background.swatches.map { it.colorName })
    assertEquals(listOf("#FFFFFF", "#ADADAD", "#525252", "#000000"),
        background.swatches.map { it.hexColor })
    assertEquals(
        listOf(0xffffff, 0xadadad, 0x525252, 0x000000),
        background.swatches.map { it.rgb888 },
    )
    assertContains(background.sourceText, "BGP")
    assertFalse(background.swatches.first().transparent)
    background.swatches.forEach { swatch ->
      assertContains(swatch.accessibilityText, "DMG shade")
      assertContains(swatch.accessibilityText, swatch.hexColor)
    }
    val objectColorZero = view.dmgPalettes[1].swatches.first()
    assertTrue(objectColorZero.transparent)
    assertContains(objectColorZero.colorName, "transparent")
    assertContains(objectColorZero.accessibilityText, "transparent for objects")
    assertContains(objectColorZero.accessibilityText, "stored mapping")
  }

  @Test
  fun `CGB palettes decode little endian RGB555 and repeat five bit components exactly`() {
    val palettes = ByteArray(CGB_PALETTE_LENGTH)
    putRgb555(palettes, 0, 0x001f)
    putRgb555(palettes, 1, 0x03e0)
    putRgb555(palettes, 2, 0x7c00)
    putRgb555(palettes, 3, 0x7fff)
    val view =
        DebuggerPeripheralPresentation.graphics(
            graphics(
                bgPaletteIndex = 0x85,
                objectPaletteIndex = 0x3e,
                cgbBackgroundPalette = palettes,
            )
        )
    val swatches = view.backgroundPalettes.first().swatches

    assertEquals(8, view.backgroundPalettes.size)
    assertEquals(listOf("#FF0000", "#00FF00", "#0000FF", "#FFFFFF"),
        swatches.map { it.hexColor })
    assertEquals(
        listOf(0xff0000, 0x00ff00, 0x0000ff, 0xffffff),
        swatches.map { it.rgb888 },
    )
    assertEquals(listOf(0x1f, 0x03e0, 0x7c00, 0x7fff), swatches.map { it.rawValue })
    assertEquals(31, swatches[0].red5)
    assertEquals(0, swatches[0].green5)
    assertEquals(0, swatches[0].blue5)
    assertEquals(0x85, view.backgroundPaletteIndex)
    assertContains(view.backgroundPaletteIndexText, "index 5 of 63")
    assertContains(view.backgroundPaletteIndexText, "auto-increment yes")
    assertContains(view.objectPaletteIndexText, "index 62 of 63")
    swatches.forEach { swatch ->
      assertContains(swatch.accessibilityText, "RGB555")
      assertContains(swatch.accessibilityText, "red")
      assertContains(swatch.accessibilityText, swatch.hexColor)
    }
    assertFalse(swatches.first().transparent)
    val objectColorZero = view.objectPalettes.first().swatches.first()
    assertTrue(objectColorZero.transparent)
    assertContains(objectColorZero.colorName, "transparent")
    assertContains(objectColorZero.accessibilityText, "stored RGB555")
  }

  @Test
  fun `audio presentation decodes mixer volumes routing and all four channel types`() {
    val view = DebuggerPeripheralPresentation.audio(audio())

    assertEquals(7, view.leftVolume)
    assertEquals(2, view.rightVolume)
    assertTrue(view.vinToLeft)
    assertTrue(view.vinToRight)
    assertEquals("left and right", view.channels[0].routingText)
    assertEquals("left only", view.channels[1].routingText)
    assertEquals("right only", view.channels[2].routingText)
    assertEquals("not routed", view.channels[3].routingText)
    assertEquals(
        listOf("Square with sweep", "Square", "Wave", "Noise"),
        view.channels.map { it.kind },
    )
    assertContains(view.globalRegisters[0].description, "Left volume setting 7 of 7")
    assertContains(view.globalRegisters[0].description, "gain 8 of 8")
    assertContains(view.globalRegisters[0].description, "gain 3 of 8")
    assertContains(view.globalRegisters[1].description, "CH1: left and right")
    assertContains(view.globalRegisters[2].description, "APU on")
    assertEquals("Next frame-sequencer step 5 of 7", view.frameSequencerText)
    assertContains(view.accessibilityText, view.frameSequencerText)
    assertContains(view.accessibilityText, "left mixer gain 8 of 8")
  }

  @Test
  fun `NR50 zero is decoded as one eighth gain instead of silence`() {
    val view = DebuggerPeripheralPresentation.audio(audio(nr50 = 0))

    assertEquals(0, view.leftVolume)
    assertEquals(0, view.rightVolume)
    assertContains(view.globalRegisters.first().description, "gain 1 of 8")
    assertContains(view.accessibilityText, "left mixer gain 1 of 8")
    assertContains(view.accessibilityText, "right mixer gain 1 of 8")
  }

  @Test
  fun `audio register descriptions decode sweep duty envelope frequency wave and noise fields`() {
    val view = DebuggerPeripheralPresentation.audio(audio())
    val channel1 = view.channels[0]
    val channel2 = view.channels[1]
    val channel3 = view.channels[2]
    val channel4 = view.channels[3]

    assertEquals(listOf("NR10", "NR11", "NR12", "NR13", "NR14"),
        channel1.registers.map { it.name })
    assertEquals(0xff10, channel1.registers.first().address)
    assertEquals(0xff23, channel4.registers.last().address)
    assertContains(channel1.registers[0].description, "Sweep pace 6")
    assertContains(channel1.registers[0].description, "direction decrease")
    assertContains(channel1.registers[1].description, "Duty 75 percent")
    assertContains(channel1.registers[2].description, "Initial volume 15")
    assertContains(channel1.registers[4].description, "frequency period 1332")
    assertContains(channel2.registers[0].description, "Unused")
    assertContains(channel2.registers[1].description, "Duty 25 percent")
    assertContains(channel3.registers[0].description, "Wave DAC on")
    assertContains(channel3.registers[2].description, "Output level 50 percent")
    assertContains(channel4.registers[3].description, "LFSR width 7-bit")
    assertContains(channel4.registers[3].description, "divisor 7")
    assertContains(channel4.accessibilityText, "Noise")
  }

  @Test
  fun `wave RAM expands high nibble before low nibble into textual sample values`() {
    val wave = ByteArray(16) { index ->
      (((index * 2) and 0x0f) shl 4 or ((index * 2 + 1) and 0x0f)).toByte()
    }
    val view = DebuggerPeripheralPresentation.audio(audio(waveRam = wave))

    assertEquals(32, view.waveSamples.size)
    assertEquals((0 until 16).toList() + (0 until 16).toList(),
        view.waveSamples.map { it.value })
    assertEquals(listOf("0", "1", "2", "3"), view.waveSamples.take(4).map { it.valueText })
    assertContains(view.waveSamples[15].accessibilityText, "value 15 of 15")
  }

  @Test
  fun `presentation values are detached deeply immutable and never rely on color alone`() {
    val vram = ByteArray(VRAM_LENGTH)
    val palette = ByteArray(CGB_PALETTE_LENGTH)
    putRgb555(palette, 0, 0x001f)
    val inspection = graphics(vramBank0 = vram, cgbBackgroundPalette = palette)
    val graphics = DebuggerPeripheralPresentation.graphics(inspection)
    val tile =
        DebuggerPeripheralPresentation.tile(
            inspection,
            graphics.backgroundMap.entries.first(),
        )
    vram[0x1800] = 0x7f
    palette[0] = 0

    assertEquals(0, graphics.backgroundMap.entries.first().tileNumber)
    assertEquals("#FF0000", graphics.backgroundPalettes.first().swatches.first().hexColor)
    assertContains(graphics.backgroundPalettes.first().swatches.first().accessibilityText, "red 31")
    assertFailsWith<UnsupportedOperationException> {
      (graphics.objects as MutableList).clear()
    }
    assertFailsWith<UnsupportedOperationException> {
      (graphics.backgroundMap.entries as MutableList).clear()
    }
    assertFailsWith<UnsupportedOperationException> {
      (graphics.backgroundPalettes.first().swatches as MutableList).clear()
    }
    assertFailsWith<UnsupportedOperationException> {
      (tile.pixels as MutableList).clear()
    }
    assertFailsWith<UnsupportedOperationException> {
      (tile.pixels.first() as MutableList).clear()
    }

    val audio = DebuggerPeripheralPresentation.audio(audio())
    assertFailsWith<UnsupportedOperationException> {
      (audio.channels as MutableList).clear()
    }
    assertFailsWith<UnsupportedOperationException> {
      (audio.channels.first().registers as MutableList).clear()
    }
    assertFailsWith<UnsupportedOperationException> {
      (audio.waveSamples as MutableList).clear()
    }
  }

  @Test
  fun `disabled APU and unavailable frame sequencer remain explicit textual states`() {
    val channels =
        (1..4).map { channel ->
          DebugAudioChannelInspection(channel, false, false, 0, 0, false, 0, 0, 0, 0, 0)
        }
    val view =
        DebuggerPeripheralPresentation.audio(
            DebugAudioInspection(false, -1, 0, 0, 0, channels, DebugByteData(ByteArray(16)))
        )

    assertEquals("APU off", view.enabledText)
    assertEquals("Frame sequencer unavailable", view.frameSequencerText)
    assertTrue(view.channels.all { !it.enabled && !it.dacEnabled })
    assertContains(view.accessibilityText, "APU off")
  }

  private fun graphics(
      mode: DebugGraphicsHardwareMode = DebugGraphicsHardwareMode.CGB_NATIVE,
      lcdc: Int = 0x10,
      bgp: Int = 0xe4,
      obp0: Int = 0xe4,
      obp1: Int = 0xe4,
      bgPaletteIndex: Int = if (mode == DebugGraphicsHardwareMode.DMG) -1 else 0,
      objectPaletteIndex: Int = if (mode == DebugGraphicsHardwareMode.DMG) -1 else 0,
      vramBank0: ByteArray = ByteArray(VRAM_LENGTH),
      vramBank1: ByteArray =
          if (mode == DebugGraphicsHardwareMode.DMG) ByteArray(0) else ByteArray(VRAM_LENGTH),
      oam: ByteArray = ByteArray(OAM_LENGTH),
      cgbBackgroundPalette: ByteArray =
          if (mode == DebugGraphicsHardwareMode.DMG) ByteArray(0)
          else ByteArray(CGB_PALETTE_LENGTH),
      cgbObjectPalette: ByteArray =
          if (mode == DebugGraphicsHardwareMode.DMG) ByteArray(0)
          else ByteArray(CGB_PALETTE_LENGTH),
  ): DebugGraphicsInspection =
      DebugGraphicsInspection(
          mode,
          0,
          lcdc,
          bgp,
          obp0,
          obp1,
          bgPaletteIndex,
          objectPaletteIndex,
          DebugByteData(vramBank0),
          DebugByteData(vramBank1),
          DebugByteData(oam),
          DebugByteData(cgbBackgroundPalette),
          DebugByteData(cgbObjectPalette),
      )

  private fun audio(
      waveRam: ByteArray = defaultWaveRam(),
      nr50: Int = 0xfa,
      frameSequencerStep: Int = 5,
  ): DebugAudioInspection =
      DebugAudioInspection(
          true,
          frameSequencerStep,
          nr50,
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
          DebugByteData(waveRam),
      )

  private fun defaultWaveRam(): ByteArray = ByteArray(16) { it.toByte() }

  private fun putRgb555(bytes: ByteArray, colorIndex: Int, value: Int) {
    bytes[colorIndex * 2] = value.toByte()
    bytes[colorIndex * 2 + 1] = (value ushr 8).toByte()
  }

  companion object {
    private const val VRAM_LENGTH = 0x2000
    private const val OAM_LENGTH = 0xa0
    private const val CGB_PALETTE_LENGTH = 0x40
  }
}
