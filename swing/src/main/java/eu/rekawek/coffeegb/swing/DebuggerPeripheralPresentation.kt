package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.debug.DebugAudioChannelInspection
import eu.rekawek.coffeegb.core.debug.DebugAudioInspection
import eu.rekawek.coffeegb.core.debug.DebugByteData
import eu.rekawek.coffeegb.core.debug.DebugGraphicsHardwareMode
import eu.rekawek.coffeegb.core.debug.DebugGraphicsInspection
import java.util.Collections
import java.util.Locale

/**
 * Pure presentation of detached graphics and audio inspections.
 *
 * All emulation-owned memory has already been copied by the core DTOs. This object performs only
 * deterministic decoding and returns immutable values, so callers can use it entirely on the EDT
 * without touching the emulation thread. Every color or flag also has a textual representation for
 * screen readers and non-color displays.
 */
internal object DebuggerPeripheralPresentation {

  fun graphics(inspection: DebugGraphicsInspection): DebuggerGraphicsView {
    val mode = inspection.hardwareMode()
    val nativeCgb = mode == DebugGraphicsHardwareMode.CGB_NATIVE
    val tileAddressing =
        if (inspection.lcdc() and 0x10 != 0) {
          DebuggerTileAddressing.UNSIGNED_8000
        } else {
          DebuggerTileAddressing.SIGNED_8800
        }
    val backgroundMap =
        tileMap(
            inspection,
            if (inspection.lcdc() and 0x08 != 0) 0x9c00 else 0x9800,
            tileAddressing,
        )
    val windowMap =
        tileMap(
            inspection,
            if (inspection.lcdc() and 0x40 != 0) 0x9c00 else 0x9800,
            tileAddressing,
        )
    val objectHeight = if (inspection.lcdc() and 0x04 != 0) 16 else 8
    val dmgPalettes =
        immutableList(
            listOf(
                dmgPalette("Background", "BGP", inspection.bgp(), objectPalette = false),
                dmgPalette("Object 0", "OBP0", inspection.obp0(), objectPalette = true),
                dmgPalette("Object 1", "OBP1", inspection.obp1(), objectPalette = true),
            )
        )
    val backgroundPalettes =
        rgb555Palettes(
            "Background",
            inspection.cgbBackgroundPalette(),
            objectPalette = false,
        )
    val objectPalettes =
        rgb555Palettes("Object", inspection.cgbObjectPalette(), objectPalette = true)
    val objects = objects(inspection, objectHeight)
    val modeText =
        when (mode) {
          DebugGraphicsHardwareMode.DMG -> "DMG"
          DebugGraphicsHardwareMode.CGB_COMPATIBILITY -> "CGB in DMG compatibility mode"
          DebugGraphicsHardwareMode.CGB_NATIVE -> "CGB native"
        }
    val lcdc = lcdc(inspection.lcdc())
    val backgroundPaletteIndex = inspection.bgPaletteIndex()
    val objectPaletteIndex = inspection.objectPaletteIndex()
    return DebuggerGraphicsView(
        hardwareMode = mode,
        hardwareModeText = modeText,
        selectedVramBank = inspection.selectedVramBank(),
        selectedVramBankText = "CPU-selected VRAM bank ${inspection.selectedVramBank()}",
        lcdc = lcdc,
        tileAddressing = tileAddressing,
        backgroundMap = backgroundMap,
        windowMap = windowMap,
        objectHeight = objectHeight,
        objects = objects,
        backgroundPaletteIndex = backgroundPaletteIndex,
        backgroundPaletteIndexText = paletteIndexText("Background", backgroundPaletteIndex),
        objectPaletteIndex = objectPaletteIndex,
        objectPaletteIndexText = paletteIndexText("Object", objectPaletteIndex),
        dmgPalettes = dmgPalettes,
        backgroundPalettes = backgroundPalettes,
        objectPalettes = objectPalettes,
        accessibilityText =
            "$modeText graphics; ${lcdc.accessibilityText}; " +
                "${objects.count { it.visibleOnScreen }} of ${objects.size} objects intersect " +
                "the screen; " +
                if (nativeCgb) {
                  "CGB tile attributes and RGB555 palettes are active; " +
                      "${paletteIndexText("background", backgroundPaletteIndex)}; " +
                      "${paletteIndexText("object", objectPaletteIndex)}."
                } else {
                  "CGB tile attributes are not active."
                },
    )
  }

  /** Decode one map entry on demand, including CGB bank, palette, and flip attributes. */
  fun tile(
      inspection: DebugGraphicsInspection,
      entry: DebuggerTileMapEntryView,
  ): DebuggerTileView {
    val bankBytes =
        if (entry.attributes.vramBank == 1) inspection.vramBank1() else inspection.vramBank0()
    require(bankBytes.length() == VRAM_LENGTH) {
      "VRAM bank ${entry.attributes.vramBank} is unavailable for this tile"
    }
    val offset = entry.tileDataAddress - VRAM_START
    require(offset in 0..(bankBytes.length() - TILE_BYTES)) {
      "Tile data address is outside VRAM: ${word(entry.tileDataAddress)}"
    }
    val rows = ArrayList<List<Int>>(TILE_HEIGHT)
    for (screenY in 0 until TILE_HEIGHT) {
      val sourceY = if (entry.attributes.yFlip) TILE_HEIGHT - 1 - screenY else screenY
      val low = bankBytes.unsignedByteAt(offset + sourceY * 2)
      val high = bankBytes.unsignedByteAt(offset + sourceY * 2 + 1)
      val row = ArrayList<Int>(TILE_WIDTH)
      for (screenX in 0 until TILE_WIDTH) {
        val sourceX = if (entry.attributes.xFlip) TILE_WIDTH - 1 - screenX else screenX
        val bit = 7 - sourceX
        row += ((high ushr bit) and 1) shl 1 or ((low ushr bit) and 1)
      }
      rows += immutableList(row)
    }
    val immutableRows = immutableList(rows)
    return DebuggerTileView(
        tileNumber = entry.tileNumber,
        tileNumberText = byte(entry.tileNumber),
        signedTileNumber = entry.signedTileNumber,
        tileDataAddress = entry.tileDataAddress,
        tileDataAddressText = word(entry.tileDataAddress),
        attributes = entry.attributes,
        pixels = immutableRows,
        textRows =
            immutableList(
                immutableRows.map { row -> row.joinToString(separator = "") { it.toString() } }
            ),
        accessibilityText =
            "Tile ${byte(entry.tileNumber)} at ${word(entry.tileDataAddress)}, " +
                "${entry.attributes.accessibilityText}; color-index rows " +
                immutableRows.joinToString(" / ") { row -> row.joinToString("") },
    )
  }

  /** Create a synthetic map entry for inspecting an arbitrary tile number. */
  fun tileEntry(
      tileNumber: Int,
      addressing: DebuggerTileAddressing,
      cgbAttributes: Int? = null,
  ): DebuggerTileMapEntryView {
    require(tileNumber in 0..0xff) { "Tile number must fit in one byte" }
    require(cgbAttributes == null || cgbAttributes in 0..0xff) {
      "CGB attributes must fit in one byte"
    }
    val attributes = tileAttributes(cgbAttributes)
    val signedNumber = tileNumber.toByte().toInt()
    val address =
        when (addressing) {
          DebuggerTileAddressing.UNSIGNED_8000 -> 0x8000 + tileNumber * TILE_BYTES
          DebuggerTileAddressing.SIGNED_8800 -> 0x9000 + signedNumber * TILE_BYTES
        }
    return DebuggerTileMapEntryView(
        index = -1,
        row = -1,
        column = -1,
        mapAddress = -1,
        mapAddressText = "Not mapped",
        tileNumber = tileNumber,
        signedTileNumber = signedNumber,
        tileDataAddress = address,
        tileDataAddressText = word(address),
        attributes = attributes,
        accessibilityText =
            "Tile ${byte(tileNumber)}, data ${word(address)}, ${attributes.accessibilityText}",
    )
  }

  fun audio(inspection: DebugAudioInspection): DebuggerAudioView {
    val nr50 = inspection.nr50()
    val nr51 = inspection.nr51()
    val nr52 = inspection.nr52()
    val leftVolumeSetting = (nr50 ushr 4) and 7
    val rightVolumeSetting = nr50 and 7
    val frameSequencerText =
        if (inspection.frameSequencerStep() < 0) "Frame sequencer unavailable"
        else "Next frame-sequencer step ${inspection.frameSequencerStep()} of 7"
    val channels =
        immutableList(
            inspection.channels().sortedBy { it.channel }.map { channel ->
              audioChannel(channel, nr51)
            }
        )
    val globalRegisters =
        immutableList(
            listOf(
                DebuggerAudioRegisterView(
                    "NR50",
                    0xff24,
                    word(0xff24),
                    nr50,
                    byte(nr50),
                    "Left volume setting $leftVolumeSetting of 7, gain " +
                        "${leftVolumeSetting + 1} of 8; right volume setting " +
                        "$rightVolumeSetting of 7, gain ${rightVolumeSetting + 1} of 8; " +
                        "VIN to left ${yesNo(nr50 and 0x80 != 0)}, " +
                        "VIN to right ${yesNo(nr50 and 0x08 != 0)}.",
                ),
                DebuggerAudioRegisterView(
                    "NR51",
                    0xff25,
                    word(0xff25),
                    nr51,
                    byte(nr51),
                    channels.joinToString("; ") { "CH${it.channel}: ${it.routingText}" } + ".",
                ),
                DebuggerAudioRegisterView(
                    "NR52",
                    0xff26,
                    word(0xff26),
                    nr52,
                    byte(nr52),
                    "APU ${onOff(inspection.enabled())}; channel status bits " +
                        (1..4).joinToString(", ") { channel ->
                          "CH$channel ${onOff(nr52 and (1 shl (channel - 1)) != 0)}"
                        } + ".",
                ),
            )
        )
    val samples = waveSamples(inspection.waveRam())
    return DebuggerAudioView(
        enabled = inspection.enabled(),
        enabledText = "APU ${onOff(inspection.enabled())}",
        frameSequencerStep = inspection.frameSequencerStep(),
        frameSequencerText = frameSequencerText,
        leftVolume = leftVolumeSetting,
        rightVolume = rightVolumeSetting,
        vinToLeft = nr50 and 0x80 != 0,
        vinToRight = nr50 and 0x08 != 0,
        globalRegisters = globalRegisters,
        channels = channels,
        waveSamples = samples,
        accessibilityText =
            "APU ${onOff(inspection.enabled())}; $frameSequencerText; " +
                "left mixer gain ${leftVolumeSetting + 1} of 8 from NR50 setting " +
                "$leftVolumeSetting; right mixer gain ${rightVolumeSetting + 1} of 8 " +
                "from NR50 setting $rightVolumeSetting; " +
                channels.joinToString("; ") { it.accessibilityText },
    )
  }

  private fun tileMap(
      inspection: DebugGraphicsInspection,
      baseAddress: Int,
      addressing: DebuggerTileAddressing,
  ): DebuggerTileMapView {
    val bank0 = inspection.vramBank0()
    val bank1 = inspection.vramBank1()
    val nativeCgb = inspection.hardwareMode() == DebugGraphicsHardwareMode.CGB_NATIVE
    val entries = ArrayList<DebuggerTileMapEntryView>(TILE_MAP_ENTRIES)
    val baseOffset = baseAddress - VRAM_START
    for (index in 0 until TILE_MAP_ENTRIES) {
      val tileNumber = bank0.unsignedByteAt(baseOffset + index)
      val attributesValue = if (nativeCgb) bank1.unsignedByteAt(baseOffset + index) else null
      val synthetic = tileEntry(tileNumber, addressing, attributesValue)
      val address = baseAddress + index
      entries +=
          synthetic.copy(
              index = index,
              row = index / TILE_MAP_WIDTH,
              column = index % TILE_MAP_WIDTH,
              mapAddress = address,
              mapAddressText = word(address),
              accessibilityText =
                  "Map row ${index / TILE_MAP_WIDTH}, column ${index % TILE_MAP_WIDTH}, " +
                      "address ${word(address)}, tile ${byte(tileNumber)}, " +
                      "data ${synthetic.tileDataAddressText}, " +
                      synthetic.attributes.accessibilityText,
          )
    }
    return DebuggerTileMapView(
        baseAddress = baseAddress,
        baseAddressText = word(baseAddress),
        addressing = addressing,
        addressingText =
            when (addressing) {
              DebuggerTileAddressing.UNSIGNED_8000 ->
                  "Unsigned tile numbers in ${word(0x8000)}-${word(0x8fff)}"
              DebuggerTileAddressing.SIGNED_8800 ->
                  "Signed tile numbers relative to ${word(0x9000)}"
            },
        entries = immutableList(entries),
    )
  }

  private fun tileAttributes(value: Int?): DebuggerTileAttributesView {
    if (value == null) {
      return DebuggerTileAttributesView(
          available = false,
          rawValue = 0,
          rawValueText = "Unavailable",
          palette = 0,
          vramBank = 0,
          xFlip = false,
          yFlip = false,
          priority = false,
          accessibilityText =
              "CGB map attributes unavailable; VRAM bank 0 is used, with no per-tile " +
                  "CGB palette, flip, or priority fields",
      )
    }
    val palette = value and 7
    val bank = (value ushr 3) and 1
    val xFlip = value and 0x20 != 0
    val yFlip = value and 0x40 != 0
    val priority = value and 0x80 != 0
    return DebuggerTileAttributesView(
        available = true,
        rawValue = value,
        rawValueText = byte(value),
        palette = palette,
        vramBank = bank,
        xFlip = xFlip,
        yFlip = yFlip,
        priority = priority,
        accessibilityText =
            "CGB attributes ${byte(value)}: palette $palette, VRAM bank $bank, " +
                "horizontal flip ${yesNo(xFlip)}, vertical flip ${yesNo(yFlip)}, " +
                "background priority ${yesNo(priority)}",
    )
  }

  private fun objects(
      inspection: DebugGraphicsInspection,
      height: Int,
  ): List<DebuggerObjectView> {
    val oam = inspection.oam()
    val nativeCgb = inspection.hardwareMode() == DebugGraphicsHardwareMode.CGB_NATIVE
    val objects = ArrayList<DebuggerObjectView>(OAM_ENTRIES)
    for (index in 0 until OAM_ENTRIES) {
      val offset = index * OAM_ENTRY_BYTES
      val rawY = oam.unsignedByteAt(offset)
      val rawX = oam.unsignedByteAt(offset + 1)
      val rawTile = oam.unsignedByteAt(offset + 2)
      val flags = oam.unsignedByteAt(offset + 3)
      val screenX = rawX - 8
      val screenY = rawY - 16
      val effectiveTile = if (height == 16) rawTile and 0xfe else rawTile
      val xFlip = flags and 0x20 != 0
      val yFlip = flags and 0x40 != 0
      val behindBackground = flags and 0x80 != 0
      val priorityText =
          objectPriorityText(
              nativeCgb = nativeCgb,
              backgroundMasterPriority = inspection.lcdc() and 0x01 != 0,
              behindBackground = behindBackground,
          )
      val palette = if (nativeCgb) flags and 7 else (flags ushr 4) and 1
      val bank = if (nativeCgb) (flags ushr 3) and 1 else 0
      val paletteText = if (nativeCgb) "CGB object palette $palette" else "DMG OBP$palette"
      val visible = screenX <= SCREEN_WIDTH - 1 && screenX + 7 >= 0 &&
          screenY <= SCREEN_HEIGHT - 1 && screenY + height - 1 >= 0
      val tileText =
          if (height == 16 && effectiveTile != rawTile) {
            "${byte(effectiveTile)} (raw ${byte(rawTile)}, low bit ignored in 8 by 16 mode)"
          } else {
            byte(effectiveTile)
          }
      objects +=
          DebuggerObjectView(
              index = index,
              oamAddress = OAM_START + offset,
              oamAddressText = word(OAM_START + offset),
              rawX = rawX,
              rawY = rawY,
              screenX = screenX,
              screenY = screenY,
              coordinateText = "X $screenX (raw $rawX), Y $screenY (raw $rawY)",
              width = 8,
              height = height,
              rawTileNumber = rawTile,
              effectiveTileNumber = effectiveTile,
              tileText = tileText,
              rawFlags = flags,
              rawFlagsText = byte(flags),
              palette = palette,
              paletteText = paletteText,
              vramBank = bank,
              xFlip = xFlip,
              yFlip = yFlip,
              behindBackground = behindBackground,
              priorityText = priorityText,
              visibleOnScreen = visible,
              accessibilityText =
                  "Object $index at X $screenX from raw $rawX, Y $screenY from raw $rawY; " +
                      "${if (visible) "intersects" else "does not intersect"} the screen; " +
                      "tile $tileText; $paletteText; VRAM bank $bank; " +
                      "horizontal flip ${yesNo(xFlip)}; vertical flip ${yesNo(yFlip)}; " +
                      "$priorityText; flags ${byte(flags)}",
          )
    }
    return immutableList(objects)
  }

  private fun dmgPalette(
      label: String,
      registerName: String,
      register: Int,
      objectPalette: Boolean,
  ): DebuggerPaletteView {
    val swatches =
        (0..3).map { colorNumber ->
          val shade = (register ushr (colorNumber * 2)) and 3
          val component5 = DMG_COMPONENTS_5[shade]
          val component8 = expand5(component5)
          val transparent = objectPalette && colorNumber == 0
          DebuggerColorSwatchView(
              colorIndex = colorNumber,
              transparent = transparent,
              rawValue = shade,
              rawValueText = "DMG shade $shade",
              red5 = component5,
              green5 = component5,
              blue5 = component5,
              red8 = component8,
              green8 = component8,
              blue8 = component8,
              rgb888 = (component8 shl 16) or (component8 shl 8) or component8,
              hexColor = hexColor(component8, component8, component8),
              colorName =
                  if (transparent) "transparent (stored ${DMG_SHADE_NAMES[shade]})"
                  else DMG_SHADE_NAMES[shade],
              accessibilityText =
                  if (transparent) {
                    "Color number 0 is transparent for objects; its stored mapping is DMG " +
                        "shade $shade, ${DMG_SHADE_NAMES[shade]}, neutral preview " +
                        hexColor(component8, component8, component8)
                  } else {
                    "Color number $colorNumber maps to DMG shade $shade, " +
                        "${DMG_SHADE_NAMES[shade]}, neutral preview " +
                        hexColor(component8, component8, component8)
                  },
          )
        }
    return DebuggerPaletteView(
        index = 0,
        label = label,
        sourceText = "$registerName ${byte(register)}",
        swatches = immutableList(swatches),
        accessibilityText =
            "$label palette from $registerName ${byte(register)}: " +
                swatches.joinToString("; ") { it.accessibilityText },
    )
  }

  private fun rgb555Palettes(
      label: String,
      bytes: DebugByteData,
      objectPalette: Boolean,
  ): List<DebuggerPaletteView> {
    if (bytes.length() == 0) return emptyList()
    require(bytes.length() == CGB_PALETTE_BYTES) {
      "$label CGB palette data must contain 64 bytes"
    }
    val palettes = ArrayList<DebuggerPaletteView>(CGB_PALETTES)
    for (paletteIndex in 0 until CGB_PALETTES) {
      val swatches = ArrayList<DebuggerColorSwatchView>(COLORS_PER_PALETTE)
      for (colorIndex in 0 until COLORS_PER_PALETTE) {
        val offset = (paletteIndex * COLORS_PER_PALETTE + colorIndex) * 2
        val raw = bytes.unsignedByteAt(offset) or (bytes.unsignedByteAt(offset + 1) shl 8)
        val red5 = raw and 0x1f
        val green5 = (raw ushr 5) and 0x1f
        val blue5 = (raw ushr 10) and 0x1f
        val red8 = expand5(red5)
        val green8 = expand5(green5)
        val blue8 = expand5(blue5)
        val hex = hexColor(red8, green8, blue8)
        val transparent = objectPalette && colorIndex == 0
        swatches +=
            DebuggerColorSwatchView(
                colorIndex = colorIndex,
                transparent = transparent,
                rawValue = raw,
                rawValueText = word(raw),
                red5 = red5,
                green5 = green5,
                blue5 = blue5,
                red8 = red8,
                green8 = green8,
                blue8 = blue8,
                rgb888 = (red8 shl 16) or (green8 shl 8) or blue8,
                hexColor = hex,
                colorName = if (transparent) "transparent (stored RGB555)" else "RGB555",
                accessibilityText =
                    if (transparent) {
                      "Color 0 is transparent for objects; its stored RGB555 value is " +
                          "${word(raw)}, red $red5 of 31, green $green5 of 31, " +
                          "blue $blue5 of 31, preview $hex"
                    } else {
                      "Color $colorIndex, RGB555 ${word(raw)}, red $red5 of 31, " +
                          "green $green5 of 31, blue $blue5 of 31, preview $hex"
                    },
            )
      }
      palettes +=
          DebuggerPaletteView(
              index = paletteIndex,
              label = "$label $paletteIndex",
              sourceText = "CGB RGB555",
              swatches = immutableList(swatches),
              accessibilityText =
                  "$label palette $paletteIndex: " +
                      swatches.joinToString("; ") { it.accessibilityText },
          )
    }
    return immutableList(palettes)
  }

  private fun lcdc(value: Int): DebuggerLcdcView {
    val unsignedTiles = value and 0x10 != 0
    return DebuggerLcdcView(
        rawValue = value,
        rawValueText = byte(value),
        lcdEnabled = value and 0x80 != 0,
        windowMapBase = if (value and 0x40 != 0) 0x9c00 else 0x9800,
        windowEnabled = value and 0x20 != 0,
        tileAddressing =
            if (unsignedTiles) DebuggerTileAddressing.UNSIGNED_8000
            else DebuggerTileAddressing.SIGNED_8800,
        backgroundMapBase = if (value and 0x08 != 0) 0x9c00 else 0x9800,
        objectHeight = if (value and 0x04 != 0) 16 else 8,
        objectsEnabled = value and 0x02 != 0,
        backgroundWindowEnabledOrPriority = value and 0x01 != 0,
        accessibilityText =
            "LCDC ${byte(value)}: LCD ${onOff(value and 0x80 != 0)}, " +
                "window ${onOff(value and 0x20 != 0)} using map " +
                "${word(if (value and 0x40 != 0) 0x9c00 else 0x9800)}, " +
                "background map ${word(if (value and 0x08 != 0) 0x9c00 else 0x9800)}, " +
                "${if (unsignedTiles) "unsigned ${word(0x8000)} tile addressing" else "signed ${word(0x8800)}-${word(0x97ff)} tile area relative to ${word(0x9000)}"}, " +
                "objects ${onOff(value and 0x02 != 0)} at 8 by " +
                "${if (value and 0x04 != 0) 16 else 8}, " +
                "background/window enable or priority ${onOff(value and 0x01 != 0)}",
    )
  }

  private fun audioChannel(
      channel: DebugAudioChannelInspection,
      nr51: Int,
  ): DebuggerAudioChannelView {
    val number = channel.channel
    val routedRight = nr51 and (1 shl (number - 1)) != 0
    val routedLeft = nr51 and (1 shl (number + 3)) != 0
    val routing =
        when {
          routedLeft && routedRight -> "left and right"
          routedLeft -> "left only"
          routedRight -> "right only"
          else -> "not routed"
        }
    val kind =
        when (number) {
          1 -> "Square with sweep"
          2 -> "Square"
          3 -> "Wave"
          4 -> "Noise"
          else -> throw IllegalArgumentException("Audio channel must be between 1 and 4")
        }
    val registers = audioRegisters(channel)
    val details = channelDetails(channel)
    return DebuggerAudioChannelView(
        channel = number,
        name = "Channel $number",
        kind = kind,
        enabled = channel.enabled,
        enabledText = "Channel ${onOff(channel.enabled)}",
        dacEnabled = channel.dacEnabled,
        dacEnabledText = "DAC ${onOff(channel.dacEnabled)}",
        output = channel.output,
        outputText = "Digital output ${channel.output} of 15",
        lengthCounter = channel.lengthCounter,
        lengthEnabled = channel.lengthEnabled,
        lengthText =
            "Length counter ${channel.lengthCounter}, " +
                "length expiration ${if (channel.lengthEnabled) "enabled" else "disabled"}",
        routedLeft = routedLeft,
        routedRight = routedRight,
        routingText = routing,
        registers = registers,
        details = details,
        accessibilityText =
            "Channel $number, $kind, ${onOff(channel.enabled)}, DAC " +
                "${onOff(channel.dacEnabled)}, output ${channel.output} of 15, " +
                "length ${channel.lengthCounter} with expiration " +
                "${if (channel.lengthEnabled) "enabled" else "disabled"}, $routing; " +
                details.joinToString("; "),
    )
  }

  private fun audioRegisters(
      channel: DebugAudioChannelInspection,
  ): List<DebuggerAudioRegisterView> {
    val base = 0xff10 + (channel.channel - 1) * 5
    val names =
        when (channel.channel) {
          1 -> listOf("NR10", "NR11", "NR12", "NR13", "NR14")
          2 -> listOf("Reserved", "NR21", "NR22", "NR23", "NR24")
          3 -> listOf("NR30", "NR31", "NR32", "NR33", "NR34")
          4 -> listOf("Reserved", "NR41", "NR42", "NR43", "NR44")
          else -> throw IllegalArgumentException("Audio channel must be between 1 and 4")
        }
    val values = listOf(channel.nr0, channel.nr1, channel.nr2, channel.nr3, channel.nr4)
    return immutableList(
        names.indices.map { index ->
          DebuggerAudioRegisterView(
              name = names[index],
              address = base + index,
              addressText = word(base + index),
              value = values[index],
              valueText = byte(values[index]),
              description = registerDescription(channel.channel, index, values),
          )
        }
    )
  }

  private fun registerDescription(
      channel: Int,
      registerIndex: Int,
      values: List<Int>,
  ): String {
    val value = values[registerIndex]
    return when (channel to registerIndex) {
      1 to 0 ->
          "Sweep pace ${(value ushr 4) and 7}, direction " +
              "${if (value and 0x08 != 0) "decrease" else "increase"}, shift ${value and 7}."
      1 to 1, 2 to 1 ->
          "Duty ${DUTY_NAMES[(value ushr 6) and 3]}, initial length ${64 - (value and 0x3f)}."
      1 to 2, 2 to 2, 4 to 2 -> envelopeDescription(value)
      1 to 3, 2 to 3, 3 to 3 -> "Frequency period low byte ${byte(value)}."
      1 to 4, 2 to 4, 3 to 4 ->
          "Trigger ${setClear(value and 0x80 != 0)}, length ${if (value and 0x40 != 0) "enabled" else "disabled"}, " +
              "frequency period ${((value and 7) shl 8) or values[3]}."
      2 to 0, 4 to 0 -> "Unused hardware register."
      3 to 0 -> "Wave DAC ${onOff(value and 0x80 != 0)}."
      3 to 1 -> "Initial length ${256 - value}."
      3 to 2 -> "Output level ${WAVE_LEVEL_NAMES[(value ushr 5) and 3]}."
      4 to 1 -> "Initial length ${64 - (value and 0x3f)}."
      4 to 3 ->
          "Clock shift ${(value ushr 4) and 0x0f}, LFSR width " +
              "${if (value and 0x08 != 0) "7-bit" else "15-bit"}, divisor " +
              NOISE_DIVISORS[value and 7] + "."
      4 to 4 ->
          "Trigger ${setClear(value and 0x80 != 0)}, length ${if (value and 0x40 != 0) "enabled" else "disabled"}."
      else -> "Register ${byte(value)}."
    }
  }

  private fun channelDetails(channel: DebugAudioChannelInspection): List<String> {
    val values = listOf(channel.nr0, channel.nr1, channel.nr2, channel.nr3, channel.nr4)
    val details =
        when (channel.channel) {
          1 ->
              listOf(
                  registerDescription(1, 0, values).removeSuffix("."),
                  registerDescription(1, 1, values).removeSuffix("."),
                  registerDescription(1, 2, values).removeSuffix("."),
                  "Frequency period ${((channel.nr4 and 7) shl 8) or channel.nr3}",
              )
          2 ->
              listOf(
                  registerDescription(2, 1, values).removeSuffix("."),
                  registerDescription(2, 2, values).removeSuffix("."),
                  "Frequency period ${((channel.nr4 and 7) shl 8) or channel.nr3}",
              )
          3 ->
              listOf(
                  "Wave DAC ${onOff(channel.nr0 and 0x80 != 0)}",
                  "Output level ${WAVE_LEVEL_NAMES[(channel.nr2 ushr 5) and 3]}",
                  "Frequency period ${((channel.nr4 and 7) shl 8) or channel.nr3}",
              )
          4 ->
              listOf(
                  registerDescription(4, 2, values).removeSuffix("."),
                  registerDescription(4, 3, values).removeSuffix("."),
              )
          else -> throw IllegalArgumentException("Audio channel must be between 1 and 4")
        }
    return immutableList(details)
  }

  private fun envelopeDescription(value: Int): String =
      "Initial volume ${(value ushr 4) and 0x0f}, envelope " +
          "${if (value and 0x08 != 0) "increase" else "decrease"}, pace ${value and 7}, " +
          "DAC ${onOff(value and 0xf8 != 0)}."

  private fun objectPriorityText(
      nativeCgb: Boolean,
      backgroundMasterPriority: Boolean,
      behindBackground: Boolean,
  ): String =
      when {
        nativeCgb && !backgroundMasterPriority ->
            "OAM priority bit ${setClear(behindBackground)}; LCDC background master priority " +
                "is off, so the object remains above background pixels"
        nativeCgb && behindBackground ->
            "OAM priority bit set; the object yields to nonzero background pixels, and CGB " +
                "tile-priority attributes also apply"
        nativeCgb ->
            "OAM priority bit clear; the object is above ordinary background pixels, but CGB " +
                "tile-priority attributes can still place background above it"
        !backgroundMasterPriority ->
            "OAM priority bit ${setClear(behindBackground)}; background display is off, so no " +
                "nonzero background pixel can cover the object"
        behindBackground ->
            "OAM priority bit set; the object yields to nonzero background pixels"
        else -> "OAM priority bit clear; the object is above background pixels"
      }

  private fun waveSamples(waveRam: DebugByteData): List<DebuggerWaveSampleView> {
    require(waveRam.length() == WAVE_RAM_BYTES) { "Wave RAM must contain 16 bytes" }
    val samples = ArrayList<DebuggerWaveSampleView>(WAVE_SAMPLES)
    for (index in 0 until WAVE_SAMPLES) {
      val packed = waveRam.unsignedByteAt(index / 2)
      val value = if (index % 2 == 0) packed ushr 4 else packed and 0x0f
      samples +=
          DebuggerWaveSampleView(
              index = index,
              value = value,
              valueText = value.toString(16).uppercase(Locale.ROOT),
              accessibilityText = "Wave sample $index, value $value of 15",
          )
    }
    return immutableList(samples)
  }

  private fun expand5(value: Int): Int = (value shl 3) or (value ushr 2)

  private fun paletteIndexText(label: String, value: Int): String =
      if (value < 0) {
        "$label CGB palette index unavailable"
      } else {
        "$label CGB palette index ${value and 0x3f} of 63, auto-increment " +
            yesNo(value and 0x80 != 0) + ", raw " + byte(value)
      }

  private fun hexColor(red: Int, green: Int, blue: Int): String =
      "#" + listOf(red, green, blue).joinToString("") {
        it.toString(16).padStart(2, '0').uppercase(Locale.ROOT)
      }

  private fun byte(value: Int): String = DebuggerPresentation.formatByte(value)

  private fun word(value: Int): String = DebuggerPresentation.formatWord(value)

  private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

  private fun onOff(value: Boolean): String = if (value) "on" else "off"

  private fun setClear(value: Boolean): String = if (value) "set" else "clear"

  private fun <T> immutableList(values: Collection<T>): List<T> =
      Collections.unmodifiableList(ArrayList(values))

  private const val VRAM_START = 0x8000
  private const val VRAM_LENGTH = 0x2000
  private const val TILE_BYTES = 16
  private const val TILE_WIDTH = 8
  private const val TILE_HEIGHT = 8
  private const val TILE_MAP_WIDTH = 32
  private const val TILE_MAP_ENTRIES = 32 * 32
  private const val OAM_START = 0xfe00
  private const val OAM_ENTRY_BYTES = 4
  private const val OAM_ENTRIES = 40
  private const val SCREEN_WIDTH = 160
  private const val SCREEN_HEIGHT = 144
  private const val CGB_PALETTE_BYTES = 64
  private const val CGB_PALETTES = 8
  private const val COLORS_PER_PALETTE = 4
  private const val WAVE_RAM_BYTES = 16
  private const val WAVE_SAMPLES = 32

  private val DMG_COMPONENTS_5 = intArrayOf(31, 21, 10, 0)
  private val DMG_SHADE_NAMES = arrayOf("white", "light gray", "dark gray", "black")
  private val DUTY_NAMES = arrayOf("12.5 percent", "25 percent", "50 percent", "75 percent")
  private val WAVE_LEVEL_NAMES = arrayOf("muted", "100 percent", "50 percent", "25 percent")
  private val NOISE_DIVISORS = arrayOf("0.5", "1", "2", "3", "4", "5", "6", "7")
}

internal enum class DebuggerTileAddressing {
  UNSIGNED_8000,
  SIGNED_8800,
}

internal data class DebuggerLcdcView(
    val rawValue: Int,
    val rawValueText: String,
    val lcdEnabled: Boolean,
    val windowMapBase: Int,
    val windowEnabled: Boolean,
    val tileAddressing: DebuggerTileAddressing,
    val backgroundMapBase: Int,
    val objectHeight: Int,
    val objectsEnabled: Boolean,
    val backgroundWindowEnabledOrPriority: Boolean,
    val accessibilityText: String,
)

internal data class DebuggerGraphicsView(
    val hardwareMode: DebugGraphicsHardwareMode,
    val hardwareModeText: String,
    val selectedVramBank: Int,
    val selectedVramBankText: String,
    val lcdc: DebuggerLcdcView,
    val tileAddressing: DebuggerTileAddressing,
    val backgroundMap: DebuggerTileMapView,
    val windowMap: DebuggerTileMapView,
    val objectHeight: Int,
    val objects: List<DebuggerObjectView>,
    val backgroundPaletteIndex: Int,
    val backgroundPaletteIndexText: String,
    val objectPaletteIndex: Int,
    val objectPaletteIndexText: String,
    val dmgPalettes: List<DebuggerPaletteView>,
    val backgroundPalettes: List<DebuggerPaletteView>,
    val objectPalettes: List<DebuggerPaletteView>,
    val accessibilityText: String,
)

internal data class DebuggerTileMapView(
    val baseAddress: Int,
    val baseAddressText: String,
    val addressing: DebuggerTileAddressing,
    val addressingText: String,
    val entries: List<DebuggerTileMapEntryView>,
)

internal data class DebuggerTileMapEntryView(
    val index: Int,
    val row: Int,
    val column: Int,
    val mapAddress: Int,
    val mapAddressText: String,
    val tileNumber: Int,
    val signedTileNumber: Int,
    val tileDataAddress: Int,
    val tileDataAddressText: String,
    val attributes: DebuggerTileAttributesView,
    val accessibilityText: String,
)

internal data class DebuggerTileAttributesView(
    val available: Boolean,
    val rawValue: Int,
    val rawValueText: String,
    val palette: Int,
    val vramBank: Int,
    val xFlip: Boolean,
    val yFlip: Boolean,
    val priority: Boolean,
    val accessibilityText: String,
)

internal data class DebuggerTileView(
    val tileNumber: Int,
    val tileNumberText: String,
    val signedTileNumber: Int,
    val tileDataAddress: Int,
    val tileDataAddressText: String,
    val attributes: DebuggerTileAttributesView,
    val pixels: List<List<Int>>,
    val textRows: List<String>,
    val accessibilityText: String,
)

internal data class DebuggerObjectView(
    val index: Int,
    val oamAddress: Int,
    val oamAddressText: String,
    val rawX: Int,
    val rawY: Int,
    val screenX: Int,
    val screenY: Int,
    val coordinateText: String,
    val width: Int,
    val height: Int,
    val rawTileNumber: Int,
    val effectiveTileNumber: Int,
    val tileText: String,
    val rawFlags: Int,
    val rawFlagsText: String,
    val palette: Int,
    val paletteText: String,
    val vramBank: Int,
    val xFlip: Boolean,
    val yFlip: Boolean,
    val behindBackground: Boolean,
    val priorityText: String,
    val visibleOnScreen: Boolean,
    val accessibilityText: String,
)

internal data class DebuggerPaletteView(
    val index: Int,
    val label: String,
    val sourceText: String,
    val swatches: List<DebuggerColorSwatchView>,
    val accessibilityText: String,
)

internal data class DebuggerColorSwatchView(
    val colorIndex: Int,
    val transparent: Boolean,
    val rawValue: Int,
    val rawValueText: String,
    val red5: Int,
    val green5: Int,
    val blue5: Int,
    val red8: Int,
    val green8: Int,
    val blue8: Int,
    val rgb888: Int,
    val hexColor: String,
    val colorName: String,
    val accessibilityText: String,
)

internal data class DebuggerAudioView(
    val enabled: Boolean,
    val enabledText: String,
    val frameSequencerStep: Int,
    val frameSequencerText: String,
    val leftVolume: Int,
    val rightVolume: Int,
    val vinToLeft: Boolean,
    val vinToRight: Boolean,
    val globalRegisters: List<DebuggerAudioRegisterView>,
    val channels: List<DebuggerAudioChannelView>,
    val waveSamples: List<DebuggerWaveSampleView>,
    val accessibilityText: String,
)

internal data class DebuggerAudioChannelView(
    val channel: Int,
    val name: String,
    val kind: String,
    val enabled: Boolean,
    val enabledText: String,
    val dacEnabled: Boolean,
    val dacEnabledText: String,
    val output: Int,
    val outputText: String,
    val lengthCounter: Int,
    val lengthEnabled: Boolean,
    val lengthText: String,
    val routedLeft: Boolean,
    val routedRight: Boolean,
    val routingText: String,
    val registers: List<DebuggerAudioRegisterView>,
    val details: List<String>,
    val accessibilityText: String,
)

internal data class DebuggerAudioRegisterView(
    val name: String,
    val address: Int,
    val addressText: String,
    val value: Int,
    val valueText: String,
    val description: String,
)

internal data class DebuggerWaveSampleView(
    val index: Int,
    val value: Int,
    val valueText: String,
    val accessibilityText: String,
)
