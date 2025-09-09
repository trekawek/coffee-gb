package eu.rekawek.coffeegb.controller.properties

import eu.rekawek.coffeegb.core.GameboyType

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
