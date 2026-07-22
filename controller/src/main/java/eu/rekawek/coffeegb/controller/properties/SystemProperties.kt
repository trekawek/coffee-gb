package eu.rekawek.coffeegb.controller.properties

import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
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

  val bootstrapMode
    get() =
        BootstrapMode.valueOf(
            properties.getProperty(
                EmulatorProperties.Key.BootstrapMode,
                BootstrapMode.SKIP.name,
            ))
}
