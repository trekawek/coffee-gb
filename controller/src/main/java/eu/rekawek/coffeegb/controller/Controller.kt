package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.properties.SystemProperties
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.memento.Memento
import eu.rekawek.coffeegb.core.memory.cart.Cartridge
import eu.rekawek.coffeegb.core.memory.cart.Rom
import java.io.File

interface Controller : AutoCloseable {

  fun startController()

  fun closeWithState(): ControllerState?

  class EmulationStartedEvent(val romName: String) : Event

  class EmulationStoppedEvent : Event

  data class LoadRomEvent(val rom: File, val memento: Memento<Gameboy>? = null) : Event

  class PauseEmulationEvent : Event

  class ResumeEmulationEvent : Event

  class ResetEmulationEvent : Event

  class StopEmulationEvent : Event

  data class SaveSnapshotEvent(val slot: Int) : Event

  data class RestoreSnapshotEvent(val slot: Int) : Event

  data class SessionPauseSupportEvent(val enabled: Boolean) : Event

  data class SessionSnapshotSupportEvent(val snapshotSupport: SnapshotSupport?) : Event

  class UpdatedSystemMappingEvent : Event

  data class GameboyTypeEvent(val gameboyType: GameboyType) : Event

  /** Posted while the rewind key is held; the emulation plays backwards while active. */
  data class RewindEvent(val active: Boolean) : Event

  /** Connects or disconnects the Barcode Boy scanner on the link port (resets the game). */
  data class SetBarcodeBoyEvent(val enabled: Boolean) : Event

  /** Simulates swiping a card with the given 13-digit JAN-13 barcode on the Barcode Boy. */
  data class ScanBarcodeEvent(val barcode: String) : Event

  /** Connects or disconnects the Game Boy Printer on the link port (resets the game). */
  data class SetPrinterEvent(val enabled: Boolean) : Event

  /**
   * Emitted each time the game prints a band on the Game Boy Printer. [argb] holds
   * [width]×[height] ARGB pixels (top row first, [width] is always 160). [topMargin] and
   * [bottomMargin] are the paper feed before/after the band in 1/16-tile units; a non-zero
   * [bottomMargin] ends the sheet.
   */
  class PrinterPrintEvent(
      val argb: IntArray,
      val width: Int,
      val height: Int,
      val topMargin: Int,
      val bottomMargin: Int,
      val exposure: Int,
  ) : Event

  data class ControllerState(val memento: Memento<Gameboy>, val rom: Rom)

  companion object {
    fun createGameboyConfig(
        properties: EmulatorProperties,
        rom: Rom,
    ): Gameboy.GameboyConfiguration {
      val config = Gameboy.GameboyConfiguration(rom)
      if (Cartridge.isDatel(rom)) {
        properties.getProperty(EmulatorProperties.Key.DatelSlotRom, null)?.let { path ->
          val file = File(path)
          if (file.isFile) {
            config.setSlotRom(Rom(file))
          }
        }
      }
      val gameboyType = getGameboyType(properties.system, rom)
      config.setGameboyType(gameboyType)
      if (rom.gameboyColorFlag == Rom.GameboyColorFlag.NON_CGB &&
          gameboyType == GameboyType.CGB &&
          !Cartridge.isDatel(rom)) {
        // (Datel carts ship a deliberately bad logo; the visible boot ROM would hang on
        // it forever - FAST_FORWARD times out and falls back to the post-boot presets)
        config.setBootstrapMode(Gameboy.BootstrapMode.NORMAL)
      } else {
        // run the boot ROM invisibly: the post-boot timer/PPU phase relationships are
        // hardware-exact, which cycle-synced programs rely on (issue #37)
        config.setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
      }
      if (config.gameboyType == GameboyType.SGB && !rom.isSuperGameboyFlag) {
        config.setDisplaySgbBorder(false)
      } else {
        config.setDisplaySgbBorder(properties.display.showSgbBorder)
      }

      return config
    }

    fun getGameboyType(properties: SystemProperties, rom: Rom): GameboyType {
      if (
          rom.gameboyColorFlag == Rom.GameboyColorFlag.CGB ||
              rom.gameboyColorFlag == Rom.GameboyColorFlag.UNIVERSAL ||
              // the Action Replay dumps carry a garbage CGB flag; the real cart's ASIC
              // presents a colour header to the console
              Cartridge.isDatel(rom)
      ) {
        if (properties.cgbGamesType == GameboyType.SGB && !rom.isSuperGameboyFlag) {
          return GameboyType.CGB
        }
        return properties.cgbGamesType
      }
      return properties.dmgGamesType
    }
  }
}
