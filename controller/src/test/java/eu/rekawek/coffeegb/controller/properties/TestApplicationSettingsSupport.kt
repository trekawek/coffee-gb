package eu.rekawek.coffeegb.controller.properties

import eu.rekawek.coffeegb.core.hardware.HardwareProfile
import eu.rekawek.coffeegb.core.ExecutionMode
import java.nio.file.Files

internal fun testEmulatorProperties(
    profileOverride: HardwareProfile? = null,
    executionMode: ExecutionMode? = null,
): EmulatorProperties {
  val path = Files.createTempDirectory("coffee-gb-settings-test").resolve("settings.properties")
  return EmulatorProperties(
      settingsPath = path,
      overrides =
          ApplicationSettingsOverrides(
              hardwareProfile = profileOverride,
              executionMode = executionMode,
          ),
      debounceMillis = 0,
  )
}
