package eu.rekawek.coffeegb.controller.properties

import eu.rekawek.coffeegb.core.ExecutionMode
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry

class SystemProperties(private val properties: EmulatorProperties) {
  val profileOverride
    get() = properties.overrides.hardwareProfile

  val dmgGamesSelection
    get() = properties.applicationSettings.advanced.dmgGamesProfile

  val cgbGamesSelection
    get() = properties.applicationSettings.advanced.cgbGamesProfile

  val dmgGamesProfile
    get() = dmgGamesSelection.effective(HardwareProfileRegistry.SGB)

  val cgbGamesProfile
    get() = cgbGamesSelection.effective(HardwareProfileRegistry.CGB)

  @Deprecated("Use dmgGamesProfile")
  val dmgGamesType
    get() = GameboyType.fromHardwareProfile(dmgGamesProfile)

  @Deprecated("Use cgbGamesProfile")
  val cgbGamesType
    get() = GameboyType.fromHardwareProfile(cgbGamesProfile)

  val bootstrapMode
    get() =
        properties.overrides.bootstrapMode
            ?: properties.applicationSettings.advanced.bootstrapMode

  val executionMode: ExecutionMode
    get() =
        properties.overrides.executionMode
            ?: properties.applicationSettings.advanced.executionMode
}
