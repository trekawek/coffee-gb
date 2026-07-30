package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.debug.DebugAudioInspection
import eu.rekawek.coffeegb.core.debug.DebugGraphicsInspection
import java.util.Collections

/**
 * Pure preparation boundary between detached core inspections and Swing components.
 *
 * Graphics preparation deliberately decodes every tile and map entry. Callers should perform this
 * work on a debugger worker executor, never on the emulation/result-delivery thread or the EDT,
 * then deliver the returned immutable, payload-free value to the matching panel on the EDT.
 */
internal object DebuggerPeripheralPanePreparation {

  fun graphics(
      identity: DebuggerSnapshotIdentity,
      inspection: DebugGraphicsInspection,
  ): DebuggerGraphicsPaneView {
    ensurePeripheralPreparationActive()
    val graphics = DebuggerPeripheralPresentation.graphics(inspection)
    val banks = if (inspection.vramBank1().length() == 0) 1 else 2
    val tileRows = ArrayList<DebuggerTileBankRow>(banks * TILES_PER_BANK)
    for (bank in 0 until banks) {
      for (tileIndex in 0 until TILES_PER_BANK) {
        ensurePeripheralPreparationActive()
        val entry =
            if (tileIndex < UNSIGNED_TILE_COUNT) {
              DebuggerPeripheralPresentation.tileEntry(
                  tileIndex,
                  DebuggerTileAddressing.UNSIGNED_8000,
                  if (bank == 1) BANK_ONE_ATTRIBUTE else null,
              )
            } else {
              DebuggerPeripheralPresentation.tileEntry(
                  tileIndex - UNSIGNED_TILE_COUNT,
                  DebuggerTileAddressing.SIGNED_8800,
                  if (bank == 1) BANK_ONE_ATTRIBUTE else null,
              )
            }
        val tile = DebuggerPeripheralPresentation.tile(inspection, entry)
        tileRows +=
            DebuggerTileBankRow(
                bank = bank,
                tileIndex = tileIndex,
                addressText = tile.tileDataAddressText,
                colorIndexRows = tile.textRows.joinToString(" / "),
                accessibilityText =
                    "VRAM bank $bank tile $tileIndex, ${tile.accessibilityText}",
            )
      }
    }

    ensurePeripheralPreparationActive()
    val backgroundMapRows = graphics.backgroundMap.entries.map(::mapRow)
    ensurePeripheralPreparationActive()
    val windowMapRows = graphics.windowMap.entries.map(::mapRow)
    ensurePeripheralPreparationActive()
    val objectRows =
        graphics.objects.map { sprite ->
          DebuggerObjectTableRow(
              index = sprite.index,
              addressText = sprite.oamAddressText,
              coordinateText = sprite.coordinateText,
              sizeText = "${sprite.width} by ${sprite.height}",
              tileText = sprite.tileText,
              paletteText = sprite.paletteText,
              bank = sprite.vramBank,
              flagsText = sprite.rawFlagsText,
              flipText =
                  listOfNotNull(
                          "horizontal".takeIf { sprite.xFlip },
                          "vertical".takeIf { sprite.yFlip },
                      )
                      .ifEmpty { listOf("none") }
                      .joinToString(", "),
              priorityText = sprite.priorityText,
              visibilityText =
                  if (sprite.visibleOnScreen) "intersects screen" else "outside screen",
              accessibilityText = sprite.accessibilityText,
          )
        }
    ensurePeripheralPreparationActive()
    val paletteRows = ArrayList<DebuggerPaletteTableRow>()
    addPaletteRows(paletteRows, "DMG", graphics.dmgPalettes)
    ensurePeripheralPreparationActive()
    addPaletteRows(paletteRows, "CGB background", graphics.backgroundPalettes)
    ensurePeripheralPreparationActive()
    addPaletteRows(paletteRows, "CGB object", graphics.objectPalettes)
    ensurePeripheralPreparationActive()

    val overview =
        buildString {
          appendLine("Snapshot: ${identity.label}")
          appendLine(graphics.hardwareModeText)
          appendLine(graphics.selectedVramBankText)
          appendLine(graphics.lcdc.accessibilityText)
          appendLine(
              "Background map ${graphics.backgroundMap.baseAddressText}; " +
                  graphics.backgroundMap.addressingText
          )
          appendLine(
              "Window map ${graphics.windowMap.baseAddressText}; " +
                  graphics.windowMap.addressingText
          )
          appendLine("Objects are 8 by ${graphics.objectHeight} pixels; 40 OAM entries captured")
          appendLine(graphics.backgroundPaletteIndexText)
          append(graphics.objectPaletteIndexText)
        }
    return DebuggerGraphicsPaneView(
        identity = identity,
        overviewText = overview,
        accessibilityText = "${identity.label}. ${graphics.accessibilityText}",
        tileRows = immutableList(tileRows),
        backgroundMapRows = immutableList(backgroundMapRows),
        windowMapRows = immutableList(windowMapRows),
        objectRows = immutableList(objectRows),
        paletteRows = immutableList(paletteRows),
    )
  }

  fun audio(
      identity: DebuggerSnapshotIdentity,
      inspection: DebugAudioInspection,
  ): DebuggerAudioPaneView {
    ensurePeripheralPreparationActive()
    val audio = DebuggerPeripheralPresentation.audio(inspection)
    val channelRows =
        audio.channels.map { channel ->
          DebuggerAudioChannelTableRow(
              channel = channel.channel,
              kind = channel.kind,
              enabledText = channel.enabledText,
              dacText = channel.dacEnabledText,
              outputText = channel.outputText,
              lengthText = channel.lengthText,
              routingText = channel.routingText,
              detailsText = channel.details.joinToString("; "),
              accessibilityText = channel.accessibilityText,
          )
        }
    ensurePeripheralPreparationActive()
    val registerRows = ArrayList<DebuggerAudioRegisterTableRow>()
    audio.globalRegisters.forEach { register ->
      registerRows +=
          DebuggerAudioRegisterTableRow(
              scope = "Global",
              name = register.name,
              addressText = register.addressText,
              rawValueText = register.valueText,
              description = register.description,
          )
    }
    audio.channels.forEach { channel ->
      channel.registers.forEach { register ->
        registerRows +=
            DebuggerAudioRegisterTableRow(
                scope = "Channel ${channel.channel}",
                name = register.name,
                addressText = register.addressText,
                rawValueText = register.valueText,
                description = register.description,
            )
      }
    }
    val waveRows =
        audio.waveSamples.map { sample ->
          DebuggerWaveSampleTableRow(
              index = sample.index,
              hexadecimalValue = sample.valueText,
              decimalValue = sample.value,
              levelText = "${sample.value} of 15",
              accessibilityText = sample.accessibilityText,
          )
        }
    ensurePeripheralPreparationActive()
    val overview =
        buildString {
          appendLine("Snapshot: ${identity.label}")
          appendLine(audio.enabledText)
          appendLine(audio.frameSequencerText)
          appendLine(
              "Mixer left volume setting ${audio.leftVolume} of 7; " +
                  "gain ${audio.leftVolume + 1} of 8"
          )
          appendLine(
              "Mixer right volume setting ${audio.rightVolume} of 7; " +
                  "gain ${audio.rightVolume + 1} of 8"
          )
          appendLine("VIN to left ${yesNo(audio.vinToLeft)}")
          append("VIN to right ${yesNo(audio.vinToRight)}")
        }
    return DebuggerAudioPaneView(
        identity = identity,
        overviewText = overview,
        accessibilityText = "${identity.label}. ${audio.accessibilityText}",
        channelRows = immutableList(channelRows),
        registerRows = immutableList(registerRows),
        waveRows = immutableList(waveRows),
    )
  }

  private fun mapRow(entry: DebuggerTileMapEntryView): DebuggerTileMapTableRow =
      DebuggerTileMapTableRow(
          row = entry.row,
          column = entry.column,
          mapAddressText = entry.mapAddressText,
          tileNumberText = DebuggerPresentation.formatByte(entry.tileNumber),
          tileDataAddressText = entry.tileDataAddressText,
          bank = entry.attributes.vramBank,
          palette = entry.attributes.palette,
          attributesText = entry.attributes.rawValueText,
          flagsText =
              listOfNotNull(
                      "horizontal flip".takeIf { entry.attributes.xFlip },
                      "vertical flip".takeIf { entry.attributes.yFlip },
                      "background priority".takeIf { entry.attributes.priority },
                  )
                  .ifEmpty { listOf("none") }
                  .joinToString(", "),
          accessibilityText = entry.accessibilityText,
      )

  private fun addPaletteRows(
      destination: MutableList<DebuggerPaletteTableRow>,
      group: String,
      palettes: List<DebuggerPaletteView>,
  ) {
    palettes.forEach { palette ->
      palette.swatches.forEach { swatch ->
        destination +=
            DebuggerPaletteTableRow(
                group = group,
                palette = palette.label,
                sourceText = palette.sourceText,
                colorIndex = swatch.colorIndex,
                rawValueText = swatch.rawValueText,
                componentText =
                    if (group == "DMG") {
                      "Neutral preview R ${swatch.red5}/31, G ${swatch.green5}/31, " +
                          "B ${swatch.blue5}/31"
                    } else {
                      "RGB555 R ${swatch.red5}/31, G ${swatch.green5}/31, B ${swatch.blue5}/31"
                    },
                hexColor = swatch.hexColor,
                rgb888 = swatch.rgb888,
                colorName = swatch.colorName,
                accessibilityText = swatch.accessibilityText,
            )
      }
    }
  }

  private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

  private fun ensurePeripheralPreparationActive() {
    check(!Thread.currentThread().isInterrupted) { "Peripheral preparation was cancelled" }
  }

  private fun <T> immutableList(values: Collection<T>): List<T> =
      Collections.unmodifiableList(ArrayList(values))

  private const val TILES_PER_BANK = 384
  private const val UNSIGNED_TILE_COUNT = 256
  private const val BANK_ONE_ATTRIBUTE = 0x08
}

internal data class DebuggerGraphicsPaneView(
    val identity: DebuggerSnapshotIdentity,
    val overviewText: String,
    val accessibilityText: String,
    val tileRows: List<DebuggerTileBankRow>,
    val backgroundMapRows: List<DebuggerTileMapTableRow>,
    val windowMapRows: List<DebuggerTileMapTableRow>,
    val objectRows: List<DebuggerObjectTableRow>,
    val paletteRows: List<DebuggerPaletteTableRow>,
)

internal data class DebuggerTileBankRow(
    val bank: Int,
    val tileIndex: Int,
    val addressText: String,
    val colorIndexRows: String,
    val accessibilityText: String,
)

internal data class DebuggerTileMapTableRow(
    val row: Int,
    val column: Int,
    val mapAddressText: String,
    val tileNumberText: String,
    val tileDataAddressText: String,
    val bank: Int,
    val palette: Int,
    val attributesText: String,
    val flagsText: String,
    val accessibilityText: String,
)

internal data class DebuggerObjectTableRow(
    val index: Int,
    val addressText: String,
    val coordinateText: String,
    val sizeText: String,
    val tileText: String,
    val paletteText: String,
    val bank: Int,
    val flagsText: String,
    val flipText: String,
    val priorityText: String,
    val visibilityText: String,
    val accessibilityText: String,
)

internal data class DebuggerPaletteTableRow(
    val group: String,
    val palette: String,
    val sourceText: String,
    val colorIndex: Int,
    val rawValueText: String,
    val componentText: String,
    val hexColor: String,
    val rgb888: Int,
    val colorName: String,
    val accessibilityText: String,
)

internal data class DebuggerAudioPaneView(
    val identity: DebuggerSnapshotIdentity,
    val overviewText: String,
    val accessibilityText: String,
    val channelRows: List<DebuggerAudioChannelTableRow>,
    val registerRows: List<DebuggerAudioRegisterTableRow>,
    val waveRows: List<DebuggerWaveSampleTableRow>,
)

internal data class DebuggerAudioChannelTableRow(
    val channel: Int,
    val kind: String,
    val enabledText: String,
    val dacText: String,
    val outputText: String,
    val lengthText: String,
    val routingText: String,
    val detailsText: String,
    val accessibilityText: String,
)

internal data class DebuggerAudioRegisterTableRow(
    val scope: String,
    val name: String,
    val addressText: String,
    val rawValueText: String,
    val description: String,
)

internal data class DebuggerWaveSampleTableRow(
    val index: Int,
    val hexadecimalValue: String,
    val decimalValue: Int,
    val levelText: String,
    val accessibilityText: String,
)
