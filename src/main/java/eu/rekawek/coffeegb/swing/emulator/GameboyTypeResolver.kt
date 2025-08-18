package eu.rekawek.coffeegb.swing.emulator

import eu.rekawek.coffeegb.GameboyType
import eu.rekawek.coffeegb.memory.cart.Rom
import eu.rekawek.coffeegb.swing.gui.properties.SystemProperties

class GameboyTypeResolver(private val props: SystemProperties) {
  fun getGameboyType(rom: Rom): GameboyType {
    if (rom.gameboyColorFlag == Rom.GameboyColorFlag.CGB ||
        rom.gameboyColorFlag == Rom.GameboyColorFlag.UNIVERSAL) {
      if (props.cgbGamesType == GameboyType.SGB && !rom.isSuperGameboyFlag) {
        return GameboyType.CGB
      }
      return props.cgbGamesType
    }
    return props.dmgGamesType
  }
}
