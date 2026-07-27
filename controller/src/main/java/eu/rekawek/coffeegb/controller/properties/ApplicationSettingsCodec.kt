package eu.rekawek.coffeegb.controller.properties

import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import java.nio.file.Path
import java.util.Locale
import java.util.Properties

class UnsupportedApplicationSettingsVersionException(val version: String) :
    IllegalArgumentException(
        "Settings schema $version is newer than supported schema " +
            ApplicationSettings.CURRENT_SCHEMA_VERSION)

/** Pure decoder/migrator and canonical schema-1 property encoder. */
object ApplicationSettingsCodec {
  const val SCHEMA_VERSION_KEY = "settings.schemaVersion"
  const val BATTERY_SAVES_KEY = "saves.batteryEnabled"

  private val fixedLegacyKeys = EmulatorProperties.Key.entries.mapTo(mutableSetOf()) { it.propertyName }
  private val knownFixedKeys =
      fixedLegacyKeys + setOf(SCHEMA_VERSION_KEY, BATTERY_SAVES_KEY)

  fun decode(raw: Map<String, String>): ApplicationSettingsDocument {
    validateStringEntries(raw)
    val encodedVersion = raw[SCHEMA_VERSION_KEY]
    if (encodedVersion == null) {
      return decodeVersion0Or1(raw, legacyDefaults = true)
    }
    val version = parseVersion(encodedVersion)
    if (version.length > SUPPORTED_SCHEMA_VERSION.length ||
        (version.length == SUPPORTED_SCHEMA_VERSION.length &&
            version > SUPPORTED_SCHEMA_VERSION)) {
      throw UnsupportedApplicationSettingsVersionException(encodedVersion)
    }
    require(version == SUPPORTED_SCHEMA_VERSION) {
      "Unsupported settings schema $version"
    }
    return decodeVersion0Or1(raw, legacyDefaults = false)
  }

  /** Absence of a schema marker is the only legacy-v0 discriminator. */
  fun migrateLegacy(raw: Map<String, String>): ApplicationSettingsDocument {
    validateStringEntries(raw)
    require(!raw.containsKey(SCHEMA_VERSION_KEY)) { "Legacy settings must not contain a schema marker" }
    return decodeVersion0Or1(raw, legacyDefaults = true)
  }

  fun encode(document: ApplicationSettingsDocument): Map<String, String> {
    val settings = document.settings
    val result = linkedMapOf<String, String>()
    document.unknownProperties.toSortedMap().forEach { (key, value) -> result[key] = value }
    result[SCHEMA_VERSION_KEY] = ApplicationSettings.CURRENT_SCHEMA_VERSION.toString()

    settings.general.romDirectory?.let {
      result[EmulatorProperties.Key.RomDirectory.propertyName] = it.toString()
    }
    settings.general.recentRoms.forEachIndexed { index, path ->
      result["rom.recent.$index"] = path.toString()
    }

    result[EmulatorProperties.Key.DisplayScale.propertyName] = settings.display.scale.toString()
    result[EmulatorProperties.Key.DisplayGrayscale.propertyName] = settings.display.grayscale.toString()
    result[EmulatorProperties.Key.DisplayBlending.propertyName] = settings.display.blending.toString()
    result[EmulatorProperties.Key.DisplayColorCorrection.propertyName] =
        settings.display.colorCorrection.toString()
    result[EmulatorProperties.Key.DisplayRotation.propertyName] =
        settings.display.rotation.degrees.toString()
    result[EmulatorProperties.Key.ShowSgbBorder.propertyName] =
        settings.display.showSgbBorder.toString()
    result[EmulatorProperties.Key.SoundEnabled.propertyName] = settings.audio.enabled.toString()
    result[BATTERY_SAVES_KEY] = settings.saves.batterySavesEnabled.toString()

    encodeProfile(
        result,
        EmulatorProperties.Key.DmgGamesType.propertyName,
        settings.advanced.dmgGamesProfile,
    )
    encodeProfile(
        result,
        EmulatorProperties.Key.CgbGamesType.propertyName,
        settings.advanced.cgbGamesProfile,
    )
    result[EmulatorProperties.Key.BootstrapMode.propertyName] = settings.advanced.bootstrapMode.name
    settings.advanced.datelSlotRom?.let {
      result[EmulatorProperties.Key.DatelSlotRom.propertyName] = it.toString()
    }
    settings.advanced.fullChangerCharacter?.let {
      result[EmulatorProperties.Key.FullChangerCharacter.propertyName] = it
    }
    encodeInput(settings.input, result)
    return result.toSortedMap()
  }

  private fun decodeVersion0Or1(
      raw: Map<String, String>,
      legacyDefaults: Boolean,
  ): ApplicationSettingsDocument {
    val inputProperties = Properties()
    raw.forEach { (key, value) -> inputProperties.setProperty(key, value) }
    val input = ControllerProperties.getInputSettings(inputProperties, legacyDefaults)

    val recent =
        (0 until ApplicationSettings.MAX_RECENT_ROMS).mapNotNull { index ->
          raw["rom.recent.$index"]?.let(::parsePath)
        }
    val display =
        ApplicationSettings.Display(
            scale =
                raw[EmulatorProperties.Key.DisplayScale.propertyName]?.let {
                  parseInt(it, EmulatorProperties.Key.DisplayScale.propertyName)
                } ?: 2,
            grayscale =
                parseBoolean(
                    raw[EmulatorProperties.Key.DisplayGrayscale.propertyName],
                    false,
                    EmulatorProperties.Key.DisplayGrayscale.propertyName,
                ),
            blending =
                parseBoolean(
                    raw[EmulatorProperties.Key.DisplayBlending.propertyName],
                    true,
                    EmulatorProperties.Key.DisplayBlending.propertyName,
                ),
            colorCorrection =
                parseBoolean(
                    raw[EmulatorProperties.Key.DisplayColorCorrection.propertyName],
                    true,
                    EmulatorProperties.Key.DisplayColorCorrection.propertyName,
                ),
            rotation =
                raw[EmulatorProperties.Key.DisplayRotation.propertyName]?.let {
                  ApplicationSettings.Rotation.fromDegrees(
                      parseInt(it, EmulatorProperties.Key.DisplayRotation.propertyName))
                } ?: ApplicationSettings.Rotation.DEG_0,
            showSgbBorder =
                parseBoolean(
                    raw[EmulatorProperties.Key.ShowSgbBorder.propertyName],
                    false,
                    EmulatorProperties.Key.ShowSgbBorder.propertyName,
                ),
        )

    val settings =
        ApplicationSettings(
            general =
                ApplicationSettings.General(
                    romDirectory =
                        raw[EmulatorProperties.Key.RomDirectory.propertyName]?.let(::parsePath),
                    recentRoms = recent,
                ),
            display = display,
            audio =
                ApplicationSettings.Audio(
                    parseBoolean(
                        raw[EmulatorProperties.Key.SoundEnabled.propertyName],
                        true,
                        EmulatorProperties.Key.SoundEnabled.propertyName,
                    )),
            input = input,
            saves =
                ApplicationSettings.Saves(
                    batterySavesEnabled =
                        parseBoolean(raw[BATTERY_SAVES_KEY], true, BATTERY_SAVES_KEY),
                ),
            advanced =
                ApplicationSettings.Advanced(
                    dmgGamesProfile =
                        parseProfile(raw[EmulatorProperties.Key.DmgGamesType.propertyName]),
                    cgbGamesProfile =
                        parseProfile(raw[EmulatorProperties.Key.CgbGamesType.propertyName]),
                    bootstrapMode =
                        raw[EmulatorProperties.Key.BootstrapMode.propertyName]?.let {
                          try {
                            BootstrapMode.valueOf(it)
                          } catch (_: IllegalArgumentException) {
                            throw IllegalArgumentException(
                                "Invalid ${EmulatorProperties.Key.BootstrapMode.propertyName}: $it")
                          }
                        } ?: BootstrapMode.SKIP,
                    datelSlotRom =
                        raw[EmulatorProperties.Key.DatelSlotRom.propertyName]
                            ?.takeUnless(String::isEmpty)
                            ?.let(::parsePath),
                    fullChangerCharacter =
                        raw[EmulatorProperties.Key.FullChangerCharacter.propertyName],
                ),
        )

    val unknown =
        raw.filterKeys { key ->
          key !in knownFixedKeys &&
              !isKnownRecentKey(key) &&
              !key.startsWith("btn_") &&
              !key.startsWith("input.")
        }
    return ApplicationSettingsDocument(settings, unknown.toSortedMap())
  }

  private fun validateStringEntries(raw: Map<String, String>) {
    require(raw.size <= 2_048) { "Settings contain more than 2048 properties" }
    raw.forEach { (key, value) ->
      require(key.isNotEmpty()) { "Settings property names must not be empty" }
      require(key.length <= 256) { "Settings property name is longer than 256 characters" }
      require(value.length <= 65_536) { "Settings property $key is longer than 65536 characters" }
    }
  }

  private fun parseVersion(value: String): String {
    require(value.isNotEmpty() && value.all { it in '0'..'9' }) {
      "Invalid $SCHEMA_VERSION_KEY: $value"
    }
    return value.trimStart('0').ifEmpty { "0" }
  }

  private fun parseInt(value: String, key: String): Int =
      value.toIntOrNull() ?: throw IllegalArgumentException("Invalid $key: $value")

  private fun parseBoolean(value: String?, default: Boolean, key: String): Boolean {
    if (value == null) return default
    return when (value.lowercase(Locale.ROOT)) {
      "true" -> true
      "false" -> false
      else -> throw IllegalArgumentException("Invalid $key: $value (expected true or false)")
    }
  }

  private fun parsePath(value: String): Path =
      try {
        Path.of(value)
      } catch (failure: RuntimeException) {
        throw IllegalArgumentException("Invalid settings path: $value", failure)
      }

  private fun parseProfile(value: String?): ApplicationSettings.ProfileSelection {
    if (value == null) return ApplicationSettings.ProfileSelection.Auto
    return ApplicationSettings.ProfileSelection.Explicit(
        try {
          HardwareProfileRegistry.resolveSetting(value)
        } catch (failure: IllegalArgumentException) {
          throw IllegalArgumentException(
              "Invalid hardware profile setting '$value': ${failure.message}", failure)
        })
  }

  private fun encodeProfile(
      target: MutableMap<String, String>,
      key: String,
      selection: ApplicationSettings.ProfileSelection,
  ) {
    selection.explicitProfileOrNull()?.let { target[key] = it.id() }
  }

  private fun encodeInput(
      input: ApplicationSettings.Input,
      target: MutableMap<String, String>,
  ) {
    input.keyboard.entries
        .sortedWith(compareBy({ it.key.player }, { it.key.button.ordinal }))
        .forEach { (binding, key) ->
          target[
              "input.p${binding.player + 1}.btn_${binding.button.name.lowercase(Locale.ROOT)}"] =
              key.propertyName
        }
    input.gamepads.toSortedMap().forEach { (player, selection) ->
      val value =
          when (selection) {
            ApplicationSettings.GamepadSelection.Disabled -> "none"
            ApplicationSettings.GamepadSelection.Auto -> ControllerProperties.GamepadAssignment.AUTO
            is ApplicationSettings.GamepadSelection.Device -> selection.stableId
          }
      target["input.p${player + 1}.gamepad"] = value
    }
  }

  private fun isKnownRecentKey(key: String): Boolean {
    if (!key.startsWith("rom.recent.")) return false
    val index = key.removePrefix("rom.recent.").toIntOrNull() ?: return false
    return index in 0 until ApplicationSettings.MAX_RECENT_ROMS && key == "rom.recent.$index"
  }

  private val SUPPORTED_SCHEMA_VERSION = ApplicationSettings.CURRENT_SCHEMA_VERSION.toString()
}
