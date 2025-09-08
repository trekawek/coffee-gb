package eu.rekawek.coffeegb.swing.properties

import eu.rekawek.coffeegb.GameboyType

class SystemProperties(private val properties: EmulatorProperties) {
  val dmgGamesType
    get() =
        GameboyType.valueOf(
            properties.getProperty(EmulatorProperties.Key.DmgGamesType, GameboyType.SGB.name))

  val cgbGamesType
    get() =
        GameboyType.valueOf(
            properties.getProperty(EmulatorProperties.Key.CgbGamesType, GameboyType.CGB.name))
}
