package eu.rekawek.coffeegb.controller.properties

import eu.rekawek.coffeegb.core.hardware.HardwareProfile
import java.nio.file.Files

internal fun testEmulatorProperties(profileOverride: HardwareProfile? = null): EmulatorProperties {
  val path = Files.createTempDirectory("coffee-gb-settings-test").resolve("settings.properties")
  return EmulatorProperties(
      settingsPath = path,
      overrides = ApplicationSettingsOverrides(hardwareProfile = profileOverride),
      debounceMillis = 0,
  )
}
