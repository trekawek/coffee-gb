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

/** Pure decoder/migrator and canonical current-schema property encoder. */
object ApplicationSettingsCodec {
  const val SCHEMA_VERSION_KEY = "settings.schemaVersion"
  const val BATTERY_SAVES_KEY = "saves.batteryEnabled"
  const val RECENT_FILE_CAPACITY_KEY = "general.recentFileCapacity"
  const val RECENT_ROM_PREFIX = "general.recent."
  const val ROM_CHANGE_CONFIRMATION_POLICY_KEY = "general.romChangeConfirmationPolicy"
  internal const val PRESERVED_UNKNOWN_COLLISIONS_PREFIX =
      "settings.preservedUnknownCollisions."

  private val fixedLegacyKeys = EmulatorProperties.Key.entries.mapTo(mutableSetOf()) { it.propertyName }
  private val versionOneFixedKeys =
      fixedLegacyKeys + setOf(SCHEMA_VERSION_KEY, BATTERY_SAVES_KEY)
  private val versionTwoFixedKeys =
      versionOneFixedKeys +
          setOf(RECENT_FILE_CAPACITY_KEY, ROM_CHANGE_CONFIRMATION_POLICY_KEY)

  fun decode(raw: Map<String, String>): ApplicationSettingsDocument {
    validateStringEntries(raw)
    val encodedVersion = raw[SCHEMA_VERSION_KEY]
    if (encodedVersion == null) {
      return decodeSupportedVersion(raw, sourceVersion = 0)
    }
    val version = parseVersion(encodedVersion)
    if (version.length > SUPPORTED_SCHEMA_VERSION.length ||
        (version.length == SUPPORTED_SCHEMA_VERSION.length &&
            version > SUPPORTED_SCHEMA_VERSION)) {
      throw UnsupportedApplicationSettingsVersionException(encodedVersion)
    }
    require(version == "1" || version == SUPPORTED_SCHEMA_VERSION) {
      "Unsupported settings schema $version"
    }
    return decodeSupportedVersion(raw, sourceVersion = version.toInt())
  }

  /** Absence of a schema marker is the only legacy-v0 discriminator. */
  fun migrateLegacy(raw: Map<String, String>): ApplicationSettingsDocument {
    validateStringEntries(raw)
    require(!raw.containsKey(SCHEMA_VERSION_KEY)) { "Legacy settings must not contain a schema marker" }
    return decodeSupportedVersion(raw, sourceVersion = 0)
  }

  fun encode(document: ApplicationSettingsDocument): Map<String, String> {
    validateStringEntries(document.unknownProperties)
    val known = encodeKnownSettings(document.settings)
    require(known.size + document.unknownProperties.size <= MAX_SETTINGS_PROPERTIES) {
      "Settings contain more than $MAX_SETTINGS_PROPERTIES logical properties"
    }

    val collisions =
        document.unknownProperties.filterKeys { key ->
          key in known || isReservedCurrentKey(key)
        }
    val result = linkedMapOf<String, String>()
    document.unknownProperties
        .filterKeys { it !in collisions }
        .toSortedMap()
        .forEach { (key, value) -> result[key] = value }
    encodeUnknownCollisions(collisions, result)
    result.putAll(known)
    return result.toSortedMap().also(::validateStringEntries)
  }

  private fun encodeKnownSettings(
      settings: ApplicationSettings
  ): LinkedHashMap<String, String> {
    val known = linkedMapOf<String, String>()
    known[SCHEMA_VERSION_KEY] = ApplicationSettings.CURRENT_SCHEMA_VERSION.toString()

    settings.general.romDirectory?.let {
      known[EmulatorProperties.Key.RomDirectory.propertyName] = it.toString()
    }
    known[RECENT_FILE_CAPACITY_KEY] = settings.general.recentFileCapacity.toString()
    known[ROM_CHANGE_CONFIRMATION_POLICY_KEY] =
        settings.general.romChangeConfirmationPolicy.name
    settings.general.recentRoms.forEachIndexed { index, path ->
      known["$RECENT_ROM_PREFIX$index"] = path.toString()
    }

    known[EmulatorProperties.Key.DisplayScale.propertyName] = settings.display.scale.toString()
    known[EmulatorProperties.Key.DisplayGrayscale.propertyName] = settings.display.grayscale.toString()
    known[EmulatorProperties.Key.DisplayBlending.propertyName] = settings.display.blending.toString()
    known[EmulatorProperties.Key.DisplayColorCorrection.propertyName] =
        settings.display.colorCorrection.toString()
    known[EmulatorProperties.Key.DisplayRotation.propertyName] =
        settings.display.rotation.degrees.toString()
    known[EmulatorProperties.Key.ShowSgbBorder.propertyName] =
        settings.display.showSgbBorder.toString()
    known[EmulatorProperties.Key.SoundEnabled.propertyName] = settings.audio.enabled.toString()
    known[BATTERY_SAVES_KEY] = settings.saves.batterySavesEnabled.toString()

    encodeProfile(
        known,
        EmulatorProperties.Key.DmgGamesType.propertyName,
        settings.advanced.dmgGamesProfile,
    )
    encodeProfile(
        known,
        EmulatorProperties.Key.CgbGamesType.propertyName,
        settings.advanced.cgbGamesProfile,
    )
    known[EmulatorProperties.Key.BootstrapMode.propertyName] = settings.advanced.bootstrapMode.name
    settings.advanced.datelSlotRom?.let {
      known[EmulatorProperties.Key.DatelSlotRom.propertyName] = it.toString()
    }
    settings.advanced.fullChangerCharacter?.let {
      known[EmulatorProperties.Key.FullChangerCharacter.propertyName] = it
    }
    encodeInput(settings.input, known)
    return known
  }

  private fun decodeSupportedVersion(
      raw: Map<String, String>,
      sourceVersion: Int,
  ): ApplicationSettingsDocument {
    val legacyDefaults = sourceVersion == 0
    val inputProperties = Properties()
    raw.forEach { (key, value) -> inputProperties.setProperty(key, value) }
    val input = ControllerProperties.getInputSettings(inputProperties, legacyDefaults)

    val recentFileCapacityValue =
        if (sourceVersion >= 2) raw[RECENT_FILE_CAPACITY_KEY] else null
    val recentFileCapacity =
        recentFileCapacityValue?.let {
          parseInt(it, RECENT_FILE_CAPACITY_KEY).also { capacity ->
            require(
                capacity in
                    ApplicationSettings.MIN_RECENT_FILE_CAPACITY..
                        ApplicationSettings.MAX_RECENT_FILE_CAPACITY) {
                  "Invalid $RECENT_FILE_CAPACITY_KEY: $capacity (expected " +
                      "${ApplicationSettings.MIN_RECENT_FILE_CAPACITY}.." +
                      "${ApplicationSettings.MAX_RECENT_FILE_CAPACITY})"
                }
          }
        } ?: ApplicationSettings.DEFAULT_RECENT_FILE_CAPACITY
    val recent =
        (0 until recentFileCapacity).mapNotNull { index ->
          val canonical =
              if (sourceVersion >= 2) raw["$RECENT_ROM_PREFIX$index"] else null
          canonical?.let(::parsePath)
              ?: index
                  .takeIf { it < ApplicationSettings.DEFAULT_RECENT_FILE_CAPACITY }
                  ?.let { raw["rom.recent.$it"] }
                  ?.let(::parsePath)
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
                    recentFileCapacity = recentFileCapacity,
                    romChangeConfirmationPolicy =
                        parseRomChangeConfirmationPolicy(
                            if (sourceVersion >= 2) {
                              raw[ROM_CHANGE_CONFIRMATION_POLICY_KEY]
                            } else {
                              null
                            }),
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

    val preservedCollisions =
        if (sourceVersion >= 2) decodeUnknownCollisions(raw) else emptyMap()
    val knownFixedKeys =
        if (sourceVersion >= 2) versionTwoFixedKeys else versionOneFixedKeys
    val unknown =
        raw
            .filterKeys { key ->
              key !in knownFixedKeys &&
                  (sourceVersion < 2 ||
                      !key.startsWith(PRESERVED_UNKNOWN_COLLISIONS_PREFIX)) &&
                  !isKnownRecentKey(
                      key,
                      supportsCanonicalRecentKeys = sourceVersion >= 2,
                  ) &&
                  !key.startsWith("btn_") &&
                  !key.startsWith("input.")
            }
            .toMutableMap()
            .apply { putAll(preservedCollisions) }
    require(encodeKnownSettings(settings).size + unknown.size <= MAX_SETTINGS_PROPERTIES) {
      "Settings contain more than $MAX_SETTINGS_PROPERTIES logical properties"
    }
    return ApplicationSettingsDocument(settings, unknown.toSortedMap()).also {
      // Accept only documents whose exact canonical representation remains within every bound.
      // Collision metadata can require more physical properties than its logical entries.
      encode(it)
    }
  }

  private fun validateStringEntries(raw: Map<String, String>) {
    require(raw.size <= MAX_SETTINGS_PROPERTIES) {
      "Settings contain more than $MAX_SETTINGS_PROPERTIES properties"
    }
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

  private fun parseRomChangeConfirmationPolicy(
      value: String?
  ): ApplicationSettings.RomChangeConfirmationPolicy {
    if (value == null) return ApplicationSettings.RomChangeConfirmationPolicy.WHEN_RUNNING
    return try {
      ApplicationSettings.RomChangeConfirmationPolicy.valueOf(value)
    } catch (_: IllegalArgumentException) {
      throw IllegalArgumentException(
          "Invalid $ROM_CHANGE_CONFIRMATION_POLICY_KEY: $value " +
              "(expected ALWAYS, WHEN_RUNNING, or NEVER)")
    }
  }

  private fun encodeUnknownCollisions(
      collisions: Map<String, String>,
      target: MutableMap<String, String>,
  ) {
    if (collisions.isEmpty()) return
    val serialized =
        buildString {
          collisions.toSortedMap().forEach { (key, value) ->
            appendLengthPrefixed(key)
            appendLengthPrefixed(value)
          }
        }
    require(serialized.length <= COLLISION_CHUNK_SIZE * MAX_COLLISION_CHUNKS) {
      "Preserved unknown collisions exceed the bounded metadata capacity"
    }
    serialized.chunked(COLLISION_CHUNK_SIZE).forEachIndexed { index, chunk ->
      target["$PRESERVED_UNKNOWN_COLLISIONS_PREFIX$index"] = chunk
    }
  }

  private fun StringBuilder.appendLengthPrefixed(value: String) {
    append(value.length).append(':').append(value)
  }

  private fun decodeUnknownCollisions(raw: Map<String, String>): Map<String, String> {
    val chunks =
        raw
            .filterKeys { it.startsWith(PRESERVED_UNKNOWN_COLLISIONS_PREFIX) }
            .map { (key, value) ->
              val suffix = key.removePrefix(PRESERVED_UNKNOWN_COLLISIONS_PREFIX)
              val index = suffix.toIntOrNull()
              require(
                  index != null &&
                      index in 0 until MAX_COLLISION_CHUNKS &&
                      suffix == index.toString()) {
                "Invalid preserved-unknown collision chunk: $key"
              }
              index to value
            }
            .sortedBy(Pair<Int, String>::first)
    if (chunks.isEmpty()) return emptyMap()
    require(chunks.map(Pair<Int, String>::first) == chunks.indices.toList()) {
      "Preserved-unknown collision chunks must be contiguous from zero"
    }

    val serialized = chunks.joinToString(separator = "") { it.second }
    require(serialized.isNotEmpty()) {
      "Preserved-unknown collision metadata must not be empty"
    }
    require(serialized.length <= COLLISION_CHUNK_SIZE * MAX_COLLISION_CHUNKS) {
      "Preserved unknown collisions exceed the bounded metadata capacity"
    }
    val result = linkedMapOf<String, String>()
    var offset = 0
    while (offset < serialized.length) {
      val (key, afterKey) = readLengthPrefixed(serialized, offset)
      val (value, afterValue) = readLengthPrefixed(serialized, afterKey)
      require(key.isNotEmpty() && key.length <= 256) {
        "Invalid preserved unknown property name"
      }
      require(value.length <= 65_536) {
        "Preserved unknown property $key is longer than 65536 characters"
      }
      require(isReservedCurrentKey(key)) {
        "Preserved unknown property does not collide with a reserved key: $key"
      }
      require(result.size < MAX_SETTINGS_PROPERTIES) {
        "Preserved unknown collisions contain more than $MAX_SETTINGS_PROPERTIES properties"
      }
      require(result.put(key, value) == null) {
        "Duplicate preserved unknown property: $key"
      }
      offset = afterValue
    }
    return result
  }

  private fun readLengthPrefixed(
      serialized: String,
      offset: Int,
  ): Pair<String, Int> {
    val separator = serialized.indexOf(':', offset)
    require(separator > offset) { "Invalid preserved-unknown collision encoding" }
    val encodedLength = serialized.substring(offset, separator)
    require(
        encodedLength.all { it in '0'..'9' } &&
            (encodedLength == "0" || !encodedLength.startsWith('0'))) {
      "Invalid preserved-unknown collision length"
    }
    val length =
        encodedLength.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid preserved-unknown collision length")
    val valueStart = separator + 1
    val valueEnd = valueStart.toLong() + length
    require(valueEnd <= serialized.length) {
      "Truncated preserved-unknown collision value"
    }
    return serialized.substring(valueStart, valueEnd.toInt()) to valueEnd.toInt()
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

  private fun isKnownRecentKey(
      key: String,
      supportsCanonicalRecentKeys: Boolean,
  ): Boolean {
    if (key.startsWith("rom.recent.")) {
      val index = key.removePrefix("rom.recent.").toIntOrNull() ?: return false
      return index in
          ApplicationSettings.MIN_RECENT_FILE_CAPACITY until
              ApplicationSettings.DEFAULT_RECENT_FILE_CAPACITY &&
          key == "rom.recent.$index"
    }
    if (!supportsCanonicalRecentKeys || !key.startsWith(RECENT_ROM_PREFIX)) return false
    val index = key.removePrefix(RECENT_ROM_PREFIX).toIntOrNull() ?: return false
    return index in
        ApplicationSettings.MIN_RECENT_FILE_CAPACITY until
            ApplicationSettings.MAX_RECENT_FILE_CAPACITY &&
        key == "$RECENT_ROM_PREFIX$index"
  }

  private fun isReservedCurrentKey(key: String): Boolean =
      key in versionTwoFixedKeys ||
          key.startsWith(PRESERVED_UNKNOWN_COLLISIONS_PREFIX) ||
          isKnownRecentKey(key, supportsCanonicalRecentKeys = true) ||
          key.startsWith("btn_") ||
          key.startsWith("input.")

  private const val COLLISION_CHUNK_SIZE = 60_000
  private const val MAX_COLLISION_CHUNKS = 32
  private const val MAX_SETTINGS_PROPERTIES = 2_048
  private val SUPPORTED_SCHEMA_VERSION = ApplicationSettings.CURRENT_SCHEMA_VERSION.toString()
}
