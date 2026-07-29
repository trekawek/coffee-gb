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
  const val AUDIO_OUTPUT_KEY = "audio.outputDevice"
  const val AUDIO_VOLUME_KEY = "audio.masterVolume"
  const val AUDIO_LATENCY_KEY = "audio.latencyPreset"
  const val GAMEPAD_TUNING_PREFIX = "input.gamepad."
  const val CAMERA_DEVICE_INDEX_KEY = "peripherals.cameraDeviceIndex"
  const val DISPLAY_SCALING_MODE_KEY = "display.scalingMode"
  const val DISPLAY_LETTERBOX_COLOR_KEY = "display.letterboxColor"
  const val DISPLAY_FULLSCREEN_KEY = "display.fullscreen"
  const val DESKTOP_WINDOW_WIDTH_KEY = "desktop.windowWidth"
  const val DESKTOP_WINDOW_HEIGHT_KEY = "desktop.windowHeight"
  const val SAVE_DIRECTORY_KEY = "saves.directory"
  const val PREVIOUS_SAVE_DIRECTORY_PREFIX = "saves.previousDirectory."
  const val REWIND_ENABLED_KEY = "saves.rewindEnabled"
  const val REWIND_SECONDS_KEY = "saves.rewindSeconds"
  const val REWIND_MEMORY_MIB_KEY = "saves.rewindMemoryMiB"
  const val AUTOSAVE_POLICY_KEY = "saves.autosavePolicy"
  const val RESUME_POLICY_KEY = "saves.resumePolicy"
  internal const val PRESERVED_UNKNOWN_COLLISIONS_PREFIX =
      "settings.preservedUnknownCollisions."

  private val fixedLegacyKeys = EmulatorProperties.Key.entries.mapTo(mutableSetOf()) { it.propertyName }
  private val versionOneFixedKeys =
      fixedLegacyKeys + setOf(SCHEMA_VERSION_KEY, BATTERY_SAVES_KEY)
  private val versionTwoFixedKeys =
      versionOneFixedKeys +
          setOf(RECENT_FILE_CAPACITY_KEY, ROM_CHANGE_CONFIRMATION_POLICY_KEY)
  private val versionThreeFixedKeys =
      versionTwoFixedKeys +
          setOf(AUDIO_OUTPUT_KEY, AUDIO_VOLUME_KEY, AUDIO_LATENCY_KEY)
  private val versionFourFixedKeys =
      versionThreeFixedKeys +
          setOf(DISPLAY_SCALING_MODE_KEY, DISPLAY_LETTERBOX_COLOR_KEY, DISPLAY_FULLSCREEN_KEY)
  private val versionFiveFixedKeys =
      versionFourFixedKeys +
          setOf(
              SAVE_DIRECTORY_KEY,
              REWIND_ENABLED_KEY,
              REWIND_SECONDS_KEY,
              REWIND_MEMORY_MIB_KEY,
              AUTOSAVE_POLICY_KEY,
              RESUME_POLICY_KEY,
          )
  private val versionSixFixedKeys =
      versionFiveFixedKeys + setOf(DESKTOP_WINDOW_WIDTH_KEY, DESKTOP_WINDOW_HEIGHT_KEY)
  private val versionSevenFixedKeys = versionSixFixedKeys + CAMERA_DEVICE_INDEX_KEY

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
    require(
        version == "1" ||
            version == "2" ||
            version == "3" ||
            version == "4" ||
            version == "5" ||
            version == "6" ||
            version == SUPPORTED_SCHEMA_VERSION) {
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

    known[DISPLAY_SCALING_MODE_KEY] = settings.display.scalingMode.name
    known[EmulatorProperties.Key.DisplayScale.propertyName] =
        settings.display.explicitScale.toString()
    known[DISPLAY_LETTERBOX_COLOR_KEY] =
        "%06X".format(Locale.ROOT, settings.display.letterboxColor)
    known[DISPLAY_FULLSCREEN_KEY] = settings.display.fullscreen.toString()
    settings.desktop.windowSize?.let { size ->
      known[DESKTOP_WINDOW_WIDTH_KEY] = size.width.toString()
      known[DESKTOP_WINDOW_HEIGHT_KEY] = size.height.toString()
    }
    known[EmulatorProperties.Key.DisplayGrayscale.propertyName] = settings.display.grayscale.toString()
    known[EmulatorProperties.Key.DisplayBlending.propertyName] = settings.display.blending.toString()
    known[EmulatorProperties.Key.DisplayColorCorrection.propertyName] =
        settings.display.colorCorrection.toString()
    known[EmulatorProperties.Key.DisplayRotation.propertyName] =
        settings.display.rotation.degrees.toString()
    known[EmulatorProperties.Key.ShowSgbBorder.propertyName] =
        settings.display.showSgbBorder.toString()
    known[EmulatorProperties.Key.SoundEnabled.propertyName] = settings.audio.enabled.toString()
    known[AUDIO_OUTPUT_KEY] =
        when (val output = settings.audio.output) {
          ApplicationSettings.AudioOutputSelection.Default -> DEFAULT_AUDIO_OUTPUT
          is ApplicationSettings.AudioOutputSelection.Device -> output.stableId
        }
    known[AUDIO_VOLUME_KEY] = settings.audio.volume.toString()
    known[AUDIO_LATENCY_KEY] = settings.audio.latency.name
    known[CAMERA_DEVICE_INDEX_KEY] = settings.peripherals.cameraDeviceIndex.toString()
    known[BATTERY_SAVES_KEY] = settings.saves.batterySavesEnabled.toString()
    settings.saves.directory?.let { known[SAVE_DIRECTORY_KEY] = it.toString() }
    settings.saves.previousDirectories.forEachIndexed { index, path ->
      known["$PREVIOUS_SAVE_DIRECTORY_PREFIX$index"] = path.toString()
    }
    known[REWIND_ENABLED_KEY] = settings.saves.rewindEnabled.toString()
    known[REWIND_SECONDS_KEY] = settings.saves.rewindSeconds.toString()
    known[REWIND_MEMORY_MIB_KEY] = settings.saves.rewindMemoryMiB.toString()
    known[AUTOSAVE_POLICY_KEY] = settings.saves.autosavePolicy.name
    known[RESUME_POLICY_KEY] = settings.saves.resumePolicy.name

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
    raw
        .filterKeys { !isGamepadTuningKey(it) }
        .forEach { (key, value) -> inputProperties.setProperty(key, value) }
    val input =
        ControllerProperties.getInputSettings(inputProperties, legacyDefaults).copy(
            gamepadTunings =
                if (sourceVersion >= 3) {
                  parseGamepadTunings(raw)
                } else {
                  emptyMap()
                })

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
            scalingMode =
                if (sourceVersion >= 4) {
                  parseDisplayScalingMode(raw[DISPLAY_SCALING_MODE_KEY])
                } else {
                  ApplicationSettings.DisplayScalingMode.EXPLICIT
                },
            explicitScale =
                parseDisplayScale(
                    raw[EmulatorProperties.Key.DisplayScale.propertyName],
                    sourceVersion,
                ),
            letterboxColor =
                if (sourceVersion >= 4) {
                  parseLetterboxColor(raw[DISPLAY_LETTERBOX_COLOR_KEY])
                } else {
                  ApplicationSettings.DEFAULT_LETTERBOX_COLOR
                },
            fullscreen =
                if (sourceVersion >= 4) {
                  parseBoolean(raw[DISPLAY_FULLSCREEN_KEY], false, DISPLAY_FULLSCREEN_KEY)
                } else {
                  false
                },
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
                    enabled =
                        parseBoolean(
                            raw[EmulatorProperties.Key.SoundEnabled.propertyName],
                            true,
                            EmulatorProperties.Key.SoundEnabled.propertyName,
                        ),
                    output =
                        if (sourceVersion >= 3) {
                          parseAudioOutput(raw[AUDIO_OUTPUT_KEY])
                        } else {
                          ApplicationSettings.AudioOutputSelection.Default
                        },
                    volume =
                        if (sourceVersion >= 3) {
                          parseRangedInt(
                              raw[AUDIO_VOLUME_KEY],
                              ApplicationSettings.DEFAULT_AUDIO_VOLUME,
                              AUDIO_VOLUME_KEY,
                              ApplicationSettings.MIN_AUDIO_VOLUME,
                              ApplicationSettings.MAX_AUDIO_VOLUME,
                          )
                        } else {
                          ApplicationSettings.DEFAULT_AUDIO_VOLUME
                        },
                    latency =
                        if (sourceVersion >= 3) {
                          parseAudioLatency(raw[AUDIO_LATENCY_KEY])
                        } else {
                          ApplicationSettings.AudioLatency.BALANCED
                        },
                ),
            input = input,
            peripherals =
                ApplicationSettings.Peripherals(
                    cameraDeviceIndex =
                        if (sourceVersion >= 7) {
                          parseRangedInt(
                              raw[CAMERA_DEVICE_INDEX_KEY],
                              ApplicationSettings.DEFAULT_CAMERA_DEVICE_INDEX,
                              CAMERA_DEVICE_INDEX_KEY,
                              ApplicationSettings.MIN_CAMERA_DEVICE_INDEX,
                              ApplicationSettings.MAX_CAMERA_DEVICE_INDEX,
                          )
                        } else {
                          ApplicationSettings.DEFAULT_CAMERA_DEVICE_INDEX
                        }),
            saves =
                ApplicationSettings.Saves(
                    directory =
                        if (sourceVersion >= 5) {
                          raw[SAVE_DIRECTORY_KEY]?.let {
                            parseSaveDirectory(it, SAVE_DIRECTORY_KEY)
                          }
                        } else {
                          null
                        },
                    previousDirectories =
                        if (sourceVersion >= 5) {
                          parsePreviousSaveDirectories(raw)
                        } else {
                          emptyList()
                        },
                    batterySavesEnabled =
                        parseBoolean(raw[BATTERY_SAVES_KEY], true, BATTERY_SAVES_KEY),
                    rewindEnabled =
                        if (sourceVersion >= 5) {
                          parseBoolean(raw[REWIND_ENABLED_KEY], true, REWIND_ENABLED_KEY)
                        } else {
                          true
                        },
                    rewindSeconds =
                        if (sourceVersion >= 5) {
                          parseRangedInt(
                              raw[REWIND_SECONDS_KEY],
                              ApplicationSettings.DEFAULT_REWIND_SECONDS,
                              REWIND_SECONDS_KEY,
                              ApplicationSettings.MIN_REWIND_SECONDS,
                              ApplicationSettings.MAX_REWIND_SECONDS,
                          )
                        } else {
                          ApplicationSettings.DEFAULT_REWIND_SECONDS
                        },
                    autosavePolicy =
                        if (sourceVersion >= 5) {
                          parseAutosavePolicy(raw[AUTOSAVE_POLICY_KEY])
                        } else {
                          ApplicationSettings.AutosavePolicy.DISABLED
                        },
                    resumePolicy =
                        if (sourceVersion >= 5) {
                          parseResumePolicy(raw[RESUME_POLICY_KEY])
                        } else {
                          ApplicationSettings.ResumePolicy.ASK
                        },
                    rewindMemoryMiB =
                        if (sourceVersion >= 5) {
                          parseRangedInt(
                              raw[REWIND_MEMORY_MIB_KEY],
                              ApplicationSettings.DEFAULT_REWIND_MEMORY_MIB,
                              REWIND_MEMORY_MIB_KEY,
                              ApplicationSettings.MIN_REWIND_MEMORY_MIB,
                              ApplicationSettings.MAX_REWIND_MEMORY_MIB,
                          )
                        } else {
                          ApplicationSettings.DEFAULT_REWIND_MEMORY_MIB
                        },
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
            desktop =
                ApplicationSettings.Desktop(
                    windowSize =
                        if (sourceVersion >= 6) {
                          parseDesktopWindowSize(raw)
                        } else {
                          null
                        }),
        )

    val preservedCollisions =
        if (sourceVersion >= 2) decodeUnknownCollisions(raw) else emptyMap()
    val knownFixedKeys =
        when {
          sourceVersion >= 7 -> versionSevenFixedKeys
          sourceVersion >= 6 -> versionSixFixedKeys
          sourceVersion >= 5 -> versionFiveFixedKeys
          sourceVersion >= 4 -> versionFourFixedKeys
          sourceVersion >= 3 -> versionThreeFixedKeys
          sourceVersion >= 2 -> versionTwoFixedKeys
          else -> versionOneFixedKeys
        }
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
                  !isKnownPreviousSaveDirectoryKey(
                      key,
                      supportsPreviousDirectories = sourceVersion >= 5,
                  ) &&
                  !key.startsWith("btn_") &&
                  (!key.startsWith("input.") ||
                      (sourceVersion < 3 && isGamepadTuningKey(key)))
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

  private fun parseRangedInt(
      value: String?,
      default: Int,
      key: String,
      minimum: Int,
      maximum: Int,
  ): Int {
    if (value == null) return default
    val parsed = parseInt(value, key)
    require(parsed in minimum..maximum) {
      "Invalid $key: $parsed (expected $minimum..$maximum)"
    }
    return parsed
  }

  private fun parseBoolean(value: String?, default: Boolean, key: String): Boolean {
    if (value == null) return default
    return when (value.lowercase(Locale.ROOT)) {
      "true" -> true
      "false" -> false
      else -> throw IllegalArgumentException("Invalid $key: $value (expected true or false)")
    }
  }

  private fun parseAudioOutput(value: String?): ApplicationSettings.AudioOutputSelection {
    if (value == null || value == DEFAULT_AUDIO_OUTPUT) {
      return ApplicationSettings.AudioOutputSelection.Default
    }
    return try {
      ApplicationSettings.AudioOutputSelection.Device(value)
    } catch (failure: IllegalArgumentException) {
      throw IllegalArgumentException(
          "Invalid $AUDIO_OUTPUT_KEY: $value " +
              "(expected '$DEFAULT_AUDIO_OUTPUT' or java-sound- followed by 64 lowercase hex digits)",
          failure,
      )
    }
  }

  private fun parseAudioLatency(value: String?): ApplicationSettings.AudioLatency {
    if (value == null) return ApplicationSettings.AudioLatency.BALANCED
    return try {
      ApplicationSettings.AudioLatency.valueOf(value)
    } catch (_: IllegalArgumentException) {
      throw IllegalArgumentException(
          "Invalid $AUDIO_LATENCY_KEY: $value (expected LOW, BALANCED, or SAFE)")
    }
  }

  private fun parseAutosavePolicy(value: String?): ApplicationSettings.AutosavePolicy {
    if (value == null) return ApplicationSettings.AutosavePolicy.DISABLED
    return try {
      ApplicationSettings.AutosavePolicy.valueOf(value)
    } catch (_: IllegalArgumentException) {
      throw IllegalArgumentException(
          "Invalid $AUTOSAVE_POLICY_KEY: $value " +
              "(expected DISABLED or ON_CLOSE_AND_ROM_SWITCH)")
    }
  }

  private fun parseResumePolicy(value: String?): ApplicationSettings.ResumePolicy {
    if (value == null) return ApplicationSettings.ResumePolicy.ASK
    return try {
      ApplicationSettings.ResumePolicy.valueOf(value)
    } catch (_: IllegalArgumentException) {
      throw IllegalArgumentException(
          "Invalid $RESUME_POLICY_KEY: $value (expected NEVER, ASK, or ALWAYS)")
    }
  }

  private fun parsePreviousSaveDirectories(raw: Map<String, String>): List<Path> {
    val indexed =
        raw
            .filterKeys { it.startsWith(PREVIOUS_SAVE_DIRECTORY_PREFIX) }
            .mapNotNull { (key, value) ->
              val suffix = key.removePrefix(PREVIOUS_SAVE_DIRECTORY_PREFIX)
              val index = suffix.toIntOrNull()
              if (index == null ||
                  index !in 0 until ApplicationSettings.MAX_PREVIOUS_SAVE_DIRECTORIES ||
                  suffix != index.toString()) {
                null
              } else {
                index to parseSaveDirectory(value, key)
              }
            }
            .sortedBy(Pair<Int, Path>::first)
    require(indexed.map(Pair<Int, Path>::first) == indexed.indices.toList()) {
      "Previous save directories must be contiguous from zero"
    }
    return indexed.map(Pair<Int, Path>::second)
  }

  private fun parseDisplayScalingMode(value: String?): ApplicationSettings.DisplayScalingMode {
    if (value == null) return ApplicationSettings.DisplayScalingMode.EXPLICIT
    return try {
      ApplicationSettings.DisplayScalingMode.valueOf(value)
    } catch (_: IllegalArgumentException) {
      throw IllegalArgumentException(
          "Invalid $DISPLAY_SCALING_MODE_KEY: $value " +
              "(expected INTEGER_FIT, ASPECT_FIT, or EXPLICIT)")
    }
  }

  private fun parseDisplayScale(value: String?, sourceVersion: Int): Int {
    if (value == null) return ApplicationSettings.DEFAULT_EXPLICIT_DISPLAY_SCALE
    val parsed = parseInt(value, EmulatorProperties.Key.DisplayScale.propertyName)
    val accepted =
        if (sourceVersion >= 4) {
          parsed in
              ApplicationSettings.MIN_EXPLICIT_DISPLAY_SCALE..
                  ApplicationSettings.MAX_EXPLICIT_DISPLAY_SCALE
        } else {
          parsed == 1 || parsed == 2 || parsed == 4
        }
    require(accepted) {
      if (sourceVersion >= 4) {
        "Invalid ${EmulatorProperties.Key.DisplayScale.propertyName}: $parsed " +
            "(expected ${ApplicationSettings.MIN_EXPLICIT_DISPLAY_SCALE}.." +
            "${ApplicationSettings.MAX_EXPLICIT_DISPLAY_SCALE})"
      } else {
        "Invalid ${EmulatorProperties.Key.DisplayScale.propertyName}: $parsed " +
            "(expected 1, 2, or 4)"
      }
    }
    return parsed
  }

  private fun parseDesktopWindowSize(
      raw: Map<String, String>
  ): ApplicationSettings.WindowSize? {
    val widthValue = raw[DESKTOP_WINDOW_WIDTH_KEY]
    val heightValue = raw[DESKTOP_WINDOW_HEIGHT_KEY]
    require((widthValue == null) == (heightValue == null)) {
      "$DESKTOP_WINDOW_WIDTH_KEY and $DESKTOP_WINDOW_HEIGHT_KEY must be stored together"
    }
    if (widthValue == null || heightValue == null) return null
    val width = parseInt(widthValue, DESKTOP_WINDOW_WIDTH_KEY)
    val height = parseInt(heightValue, DESKTOP_WINDOW_HEIGHT_KEY)
    return try {
      ApplicationSettings.WindowSize(width, height)
    } catch (failure: IllegalArgumentException) {
      throw IllegalArgumentException(
          "Invalid desktop window size: ${width}x$height (expected positive dimensions)",
          failure,
      )
    }
  }

  private fun parseLetterboxColor(value: String?): Int {
    if (value == null) return ApplicationSettings.DEFAULT_LETTERBOX_COLOR
    require(CANONICAL_RGB.matches(value)) {
      "Invalid $DISPLAY_LETTERBOX_COLOR_KEY: $value (expected six uppercase hexadecimal digits)"
    }
    return value.toInt(16)
  }

  private fun parseGamepadTunings(
      raw: Map<String, String>
  ): Map<String, ApplicationSettings.GamepadTuning> {
    val stableIds =
        raw.keys
            .mapNotNull { key -> gamepadTuningKey.matchEntire(key)?.groupValues?.get(1) }
            .toSortedSet()
    require(stableIds.size <= ApplicationSettings.MAX_GAMEPAD_TUNINGS) {
      "At most ${ApplicationSettings.MAX_GAMEPAD_TUNINGS} gamepad tuning profiles may be stored"
    }
    return stableIds.associateWith { stableId ->
      val prefix = "$GAMEPAD_TUNING_PREFIX$stableId."
      ApplicationSettings.GamepadTuning(
          movementDeadZone =
              parseRangedInt(
                  raw["${prefix}movementDeadZone"],
                  ApplicationSettings.DEFAULT_GAMEPAD_MOVEMENT_DEAD_ZONE,
                  "${prefix}movementDeadZone",
                  ApplicationSettings.MIN_GAMEPAD_DEAD_ZONE,
                  ApplicationSettings.MAX_GAMEPAD_DEAD_ZONE,
              ),
          tiltDeadZone =
              parseRangedInt(
                  raw["${prefix}tiltDeadZone"],
                  ApplicationSettings.DEFAULT_GAMEPAD_TILT_DEAD_ZONE,
                  "${prefix}tiltDeadZone",
                  ApplicationSettings.MIN_GAMEPAD_DEAD_ZONE,
                  ApplicationSettings.MAX_GAMEPAD_DEAD_ZONE,
              ),
          invertMovementX =
              parseBoolean(raw["${prefix}invertMovementX"], false, "${prefix}invertMovementX"),
          invertMovementY =
              parseBoolean(raw["${prefix}invertMovementY"], false, "${prefix}invertMovementY"),
          invertTiltX =
              parseBoolean(raw["${prefix}invertTiltX"], false, "${prefix}invertTiltX"),
          invertTiltY =
              parseBoolean(raw["${prefix}invertTiltY"], false, "${prefix}invertTiltY"),
      )
    }
  }

  private fun parsePath(value: String): Path =
      try {
        Path.of(value)
      } catch (failure: RuntimeException) {
        throw IllegalArgumentException("Invalid settings path: $value", failure)
      }

  private fun parseSaveDirectory(value: String, key: String): Path =
      parsePath(value).also {
        require(ApplicationSettings.Saves.isStructurallySafeDirectory(it)) {
          "Invalid $key: save directory must be below the filesystem root"
        }
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
    input.gamepadTunings.toSortedMap().forEach { (stableId, tuning) ->
      val prefix = "$GAMEPAD_TUNING_PREFIX$stableId."
      target["${prefix}movementDeadZone"] = tuning.movementDeadZone.toString()
      target["${prefix}tiltDeadZone"] = tuning.tiltDeadZone.toString()
      target["${prefix}invertMovementX"] = tuning.invertMovementX.toString()
      target["${prefix}invertMovementY"] = tuning.invertMovementY.toString()
      target["${prefix}invertTiltX"] = tuning.invertTiltX.toString()
      target["${prefix}invertTiltY"] = tuning.invertTiltY.toString()
    }
  }

  private fun isGamepadTuningKey(key: String): Boolean = gamepadTuningKey.matches(key)

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

  private fun isKnownPreviousSaveDirectoryKey(
      key: String,
      supportsPreviousDirectories: Boolean,
  ): Boolean {
    if (!supportsPreviousDirectories || !key.startsWith(PREVIOUS_SAVE_DIRECTORY_PREFIX)) {
      return false
    }
    val index = key.removePrefix(PREVIOUS_SAVE_DIRECTORY_PREFIX).toIntOrNull() ?: return false
    return index in 0 until ApplicationSettings.MAX_PREVIOUS_SAVE_DIRECTORIES &&
        key == "$PREVIOUS_SAVE_DIRECTORY_PREFIX$index"
  }

  private fun isReservedCurrentKey(key: String): Boolean =
      key in versionSevenFixedKeys ||
          key.startsWith(PRESERVED_UNKNOWN_COLLISIONS_PREFIX) ||
          isKnownRecentKey(key, supportsCanonicalRecentKeys = true) ||
          isKnownPreviousSaveDirectoryKey(key, supportsPreviousDirectories = true) ||
          key.startsWith("btn_") ||
          key.startsWith("input.")

  private const val DEFAULT_AUDIO_OUTPUT = "default"
  private const val COLLISION_CHUNK_SIZE = 60_000
  private const val MAX_COLLISION_CHUNKS = 32
  private const val MAX_SETTINGS_PROPERTIES = 2_048
  private val CANONICAL_RGB = Regex("[0-9A-F]{6}")
  private val gamepadTuningKey =
      Regex(
          "input\\.gamepad\\.(sdl-[0-9a-f]{64})\\." +
              "(movementDeadZone|tiltDeadZone|invertMovementX|invertMovementY|" +
              "invertTiltX|invertTiltY)")
  private val SUPPORTED_SCHEMA_VERSION = ApplicationSettings.CURRENT_SCHEMA_VERSION.toString()
}
