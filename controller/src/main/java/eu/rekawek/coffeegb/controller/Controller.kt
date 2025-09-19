package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.controller.properties.SystemProperties
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.Event
import eu.rekawek.coffeegb.core.memory.cart.Rom
import java.io.File

interface Controller : AutoCloseable {

  fun startController()

  class EmulationStartedEvent(val romName: String) : Event

  class EmulationStoppedEvent : Event

  data class LoadRomEvent(val rom: File) : Event

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

  companion object {
    fun createGameboyConfig(
        properties: EmulatorProperties,
        rom: Rom,
    ): Gameboy.GameboyConfiguration {
      val config = Gameboy.GameboyConfiguration(rom)
      val gameboyType = getGameboyType(properties.system, rom)
      config.setGameboyType(gameboyType)
      if (rom.gameboyColorFlag == Rom.GameboyColorFlag.NON_CGB && gameboyType == GameboyType.CGB) {
        config.setBootstrapMode(Gameboy.BootstrapMode.NORMAL)
      } else {
        config.setBootstrapMode(Gameboy.BootstrapMode.SKIP)
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
              rom.gameboyColorFlag == Rom.GameboyColorFlag.UNIVERSAL
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
