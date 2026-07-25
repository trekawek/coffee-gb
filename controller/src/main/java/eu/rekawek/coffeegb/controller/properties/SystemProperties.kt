package eu.rekawek.coffeegb.controller.properties

import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry

class SystemProperties(private val properties: EmulatorProperties) {
  val profileOverride
    get() = properties.profileOverride

  val dmgGamesProfile
    get() =
        HardwareProfileRegistry.resolveSetting(
            properties.getProperty(
                EmulatorProperties.Key.DmgGamesType,
                HardwareProfileRegistry.SGB.id(),
            ))

  val cgbGamesProfile
    get() =
        HardwareProfileRegistry.resolveSetting(
            properties.getProperty(
                EmulatorProperties.Key.CgbGamesType,
                HardwareProfileRegistry.CGB.id(),
            ))

  @Deprecated("Use dmgGamesProfile")
  val dmgGamesType
    get() = GameboyType.fromHardwareProfile(dmgGamesProfile)

  @Deprecated("Use cgbGamesProfile")
  val cgbGamesType
    get() = GameboyType.fromHardwareProfile(cgbGamesProfile)

  val bootstrapMode
    get() =
        BootstrapMode.valueOf(
            properties.getProperty(
                EmulatorProperties.Key.BootstrapMode,
                BootstrapMode.SKIP.name,
            ))
}
