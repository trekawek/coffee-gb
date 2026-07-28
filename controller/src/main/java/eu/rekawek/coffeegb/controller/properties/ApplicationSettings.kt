package eu.rekawek.coffeegb.controller.properties

import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.hardware.HardwareProfile
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.joypad.Button
import java.awt.event.KeyEvent
import java.lang.reflect.Modifier
import java.nio.file.Path
import java.util.Collections
import java.util.EnumMap

/** Immutable, validated desktop settings independent from their on-disk representation. */
data class ApplicationSettings(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val general: General = General(),
    val display: Display = Display(),
    val audio: Audio = Audio(),
    val input: Input = Input.defaults(),
    val saves: Saves = Saves(),
    val advanced: Advanced = Advanced(),
) {
  init {
    require(schemaVersion == CURRENT_SCHEMA_VERSION) {
      "Application settings schema must be $CURRENT_SCHEMA_VERSION"
    }
    input.toPlayerMapping()
    advanced.dmgGamesProfile.explicitProfileOrNull()?.let(HardwareProfileRegistry::requireRegistered)
    advanced.cgbGamesProfile.explicitProfileOrNull()?.let(HardwareProfileRegistry::requireRegistered)
  }

  class General(
      val romDirectory: Path? = null,
      recentRoms: List<Path> = emptyList(),
      val recentFileCapacity: Int = DEFAULT_RECENT_FILE_CAPACITY,
      val romChangeConfirmationPolicy: RomChangeConfirmationPolicy =
          RomChangeConfirmationPolicy.WHEN_RUNNING,
  ) {
    val recentRoms: List<Path> = immutableListCopy(recentRoms)

    init {
      require(recentFileCapacity in MIN_RECENT_FILE_CAPACITY..MAX_RECENT_FILE_CAPACITY) {
        "Recent-file capacity must be between $MIN_RECENT_FILE_CAPACITY and " +
            "$MAX_RECENT_FILE_CAPACITY"
      }
      require(this.recentRoms.size <= recentFileCapacity) {
        "Recent ROM history contains ${this.recentRoms.size} entries but capacity is " +
            recentFileCapacity
      }
    }

    fun copy(
        romDirectory: Path? = this.romDirectory,
        recentRoms: List<Path> = this.recentRoms,
        recentFileCapacity: Int = this.recentFileCapacity,
        romChangeConfirmationPolicy: RomChangeConfirmationPolicy =
            this.romChangeConfirmationPolicy,
    ): General =
        General(
            romDirectory,
            recentRoms,
            recentFileCapacity,
            romChangeConfirmationPolicy,
        )

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is General &&
                romDirectory == other.romDirectory &&
                recentRoms == other.recentRoms &&
                recentFileCapacity == other.recentFileCapacity &&
                romChangeConfirmationPolicy == other.romChangeConfirmationPolicy)

    override fun hashCode(): Int {
      var result = romDirectory?.hashCode() ?: 0
      result = 31 * result + recentRoms.hashCode()
      result = 31 * result + recentFileCapacity
      result = 31 * result + romChangeConfirmationPolicy.hashCode()
      return result
    }

    override fun toString(): String =
        "General(romDirectory=$romDirectory, recentRoms=$recentRoms, " +
            "recentFileCapacity=$recentFileCapacity, " +
            "romChangeConfirmationPolicy=$romChangeConfirmationPolicy)"
  }

  enum class RomChangeConfirmationPolicy {
    /** Confirm every request, including an application close while no ROM is running. */
    ALWAYS,

    /** Confirm only when replacing or closing an active emulation session. */
    WHEN_RUNNING,

    /** Never show the ordinary ROM-change confirmation. Save failures still require a decision. */
    NEVER;

    fun shouldConfirm(isRomRunning: Boolean): Boolean =
        when (this) {
          ALWAYS -> true
          WHEN_RUNNING -> isRomRunning
          NEVER -> false
        }
  }

  data class Display(
      val scalingMode: DisplayScalingMode = DisplayScalingMode.EXPLICIT,
      val explicitScale: Int = DEFAULT_EXPLICIT_DISPLAY_SCALE,
      val letterboxColor: Int = DEFAULT_LETTERBOX_COLOR,
      val fullscreen: Boolean = false,
      val grayscale: Boolean = false,
      val blending: Boolean = true,
      val colorCorrection: Boolean = true,
      val rotation: Rotation = Rotation.DEG_0,
      val showSgbBorder: Boolean = false,
  ) {
    init {
      require(explicitScale in MIN_EXPLICIT_DISPLAY_SCALE..MAX_EXPLICIT_DISPLAY_SCALE) {
        "Explicit display scale must be between $MIN_EXPLICIT_DISPLAY_SCALE and " +
            MAX_EXPLICIT_DISPLAY_SCALE
      }
      require(letterboxColor in MIN_LETTERBOX_COLOR..MAX_LETTERBOX_COLOR) {
        "Letterbox color must be an RGB value between 0x000000 and 0xFFFFFF"
      }
    }

    /**
     * Compatibility view for callers that have not yet adopted fit modes. New code should use
     * [scalingMode] and [explicitScale].
     */
    val scale: Int
      get() = explicitScale
  }

  enum class DisplayScalingMode {
    INTEGER_FIT,
    ASPECT_FIT,
    EXPLICIT,
  }

  enum class Rotation(val degrees: Int) {
    DEG_0(0),
    DEG_90(90),
    DEG_180(180),
    DEG_270(270);

    companion object {
      fun fromDegrees(degrees: Int): Rotation =
          entries.firstOrNull { it.degrees == degrees }
              ?: throw IllegalArgumentException("Display rotation must be 0, 90, 180, or 270")
    }
  }

  data class Audio(
      /** Historical sound.enabled value; false is the persisted master-mute compatibility path. */
      val enabled: Boolean = true,
      val output: AudioOutputSelection = AudioOutputSelection.Default,
      val volume: Int = DEFAULT_AUDIO_VOLUME,
      val latency: AudioLatency = AudioLatency.BALANCED,
  ) {
    init {
      require(volume in MIN_AUDIO_VOLUME..MAX_AUDIO_VOLUME) {
        "Audio volume must be between $MIN_AUDIO_VOLUME and $MAX_AUDIO_VOLUME"
      }
    }
  }

  sealed class AudioOutputSelection {
    data object Default : AudioOutputSelection()

    data class Device(val stableId: String) : AudioOutputSelection() {
      init {
        require(isStableAudioOutputId(stableId)) {
          "Audio output device must be java-sound- followed by 64 lowercase hex digits"
        }
      }
    }
  }

  enum class AudioLatency {
    LOW,
    BALANCED,
    SAFE,
  }

  class Input(
      keyboard: Map<ControllerProperties.PlayerButton, KeyboardKey>,
      gamepads: Map<Int, GamepadSelection>,
      gamepadTunings: Map<String, GamepadTuning> = emptyMap(),
  ) {
    val keyboard: Map<ControllerProperties.PlayerButton, KeyboardKey> = immutableMapCopy(keyboard)

    val gamepads: Map<Int, GamepadSelection> = immutableMapCopy(gamepads)

    val gamepadTunings: Map<String, GamepadTuning> = immutableMapCopy(gamepadTunings)

    init {
      require(gamepads.keys.all { it in 0..3 }) { "Logical gamepad player must be P1 through P4" }
      require(gamepads.containsKey(0)) {
        "P1 gamepad selection must be explicit (Disabled, Auto, or Device)"
      }
      require(
          gamepads.none { (player, selection) ->
            player != 0 && selection == GamepadSelection.Disabled
          }) {
            "Disabled P2 through P4 gamepad selections must be omitted"
          }
      keyboard.keys.forEach { require(it.player in 0..3) }
      require(this.gamepadTunings.size <= MAX_GAMEPAD_TUNINGS) {
        "At most $MAX_GAMEPAD_TUNINGS gamepad tuning profiles may be stored"
      }
      this.gamepadTunings.keys.forEach { stableId ->
        require(ControllerProperties.isStableGamepadId(stableId)) {
          "Gamepad tuning device must be sdl- followed by 64 lowercase hex digits"
        }
      }
    }

    fun toPlayerMapping(): ControllerProperties.PlayerMapping {
      val byKey = linkedMapOf<Int, ControllerProperties.PlayerButton>()
      keyboard.entries.sortedWith(
              compareBy<Map.Entry<ControllerProperties.PlayerButton, KeyboardKey>>(
                  { it.key.player }, { it.key.button.ordinal }))
          .forEach { (binding, key) ->
            val previous = byKey.put(key.code, binding)
            require(previous == null) {
              "Key ${KeyEvent.getKeyText(key.code)} is assigned to both " +
                  "P${previous!!.player + 1} ${previous.button} and " +
                  "P${binding.player + 1} ${binding.button}"
            }
          }

      val assignments =
          gamepads.entries
              .mapNotNull { (player, selection) ->
                when (selection) {
                  GamepadSelection.Disabled -> null
                  GamepadSelection.Auto ->
                      ControllerProperties.GamepadAssignment(
                          player, ControllerProperties.GamepadAssignment.AUTO)
                  is GamepadSelection.Device ->
                      ControllerProperties.GamepadAssignment(player, selection.stableId)
                }
              }
              .sortedBy { it.player }
      val duplicate = assignments.groupBy { it.selector }.entries.firstOrNull { it.value.size > 1 }
      require(duplicate == null) {
        "Gamepad selector ${duplicate!!.key} is assigned to multiple logical players"
      }
      return ControllerProperties.PlayerMapping(byKey.toMap(), assignments)
    }

    fun copy(
        keyboard: Map<ControllerProperties.PlayerButton, KeyboardKey> = this.keyboard,
        gamepads: Map<Int, GamepadSelection> = this.gamepads,
        gamepadTunings: Map<String, GamepadTuning> = this.gamepadTunings,
    ): Input = Input(keyboard, gamepads, gamepadTunings)

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is Input &&
                keyboard == other.keyboard &&
                gamepads == other.gamepads &&
                gamepadTunings == other.gamepadTunings)

    override fun hashCode(): Int =
        31 * (31 * keyboard.hashCode() + gamepads.hashCode()) + gamepadTunings.hashCode()

    override fun toString(): String =
        "Input(keyboard=$keyboard, gamepads=$gamepads, gamepadTunings=$gamepadTunings)"

    companion object {
      fun defaults(): Input {
        val keyboard = linkedMapOf<ControllerProperties.PlayerButton, KeyboardKey>()
        defaultPrimaryKeyboard().forEach { (button, key) ->
          keyboard[ControllerProperties.PlayerButton(0, button)] = key
        }
        return Input(keyboard.toMap(), mapOf(0 to GamepadSelection.Auto))
      }

      internal fun defaultPrimaryKeyboard(): Map<Button, KeyboardKey> =
          EnumMap<Button, KeyboardKey>(Button::class.java).apply {
            put(Button.LEFT, KeyboardKey.of("VK_LEFT", KeyEvent.VK_LEFT))
            put(Button.RIGHT, KeyboardKey.of("VK_RIGHT", KeyEvent.VK_RIGHT))
            put(Button.UP, KeyboardKey.of("VK_UP", KeyEvent.VK_UP))
            put(Button.DOWN, KeyboardKey.of("VK_DOWN", KeyEvent.VK_DOWN))
            put(Button.A, KeyboardKey.of("VK_Z", KeyEvent.VK_Z))
            put(Button.B, KeyboardKey.of("VK_X", KeyEvent.VK_X))
            put(Button.START, KeyboardKey.of("VK_ENTER", KeyEvent.VK_ENTER))
            put(Button.SELECT, KeyboardKey.of("VK_SHIFT", KeyEvent.VK_SHIFT))
          }
    }
  }

  class KeyboardKey private constructor(val propertyName: String, val code: Int) {
    override fun equals(other: Any?): Boolean =
        other is KeyboardKey && propertyName == other.propertyName && code == other.code

    override fun hashCode(): Int = 31 * propertyName.hashCode() + code

    override fun toString(): String = propertyName

    companion object {
      /**
       * Resolves a captured AWT key code to the canonical persisted VK_* name. Some JDK constants
       * are aliases, so lexicographic ordering makes the serialized result stable across reflection
       * order and runs.
       */
      fun fromKeyCode(keyCode: Int): KeyboardKey {
        require(keyCode != KeyEvent.VK_UNDEFINED) {
          "VK_UNDEFINED cannot be used as a keyboard binding"
        }
        val propertyName =
            canonicalPropertyNamesByCode[keyCode]
                ?: throw IllegalArgumentException(
                    "Key code $keyCode does not name a java.awt.event.KeyEvent VK_* constant")
        return KeyboardKey(propertyName, keyCode)
      }

      fun parse(propertyName: String, property: String): KeyboardKey {
        require(propertyName.startsWith("VK_")) {
          "$property must name a java.awt.event.KeyEvent VK_* constant"
        }
        val field =
            try {
              KeyEvent::class.java.getField(propertyName)
            } catch (_: ReflectiveOperationException) {
              throw IllegalArgumentException("Unknown keyboard key in $property: $propertyName")
            }
        require(field.type == Int::class.javaPrimitiveType) {
          "Invalid keyboard key in $property: $propertyName"
        }
        return KeyboardKey(propertyName, field.getInt(null))
      }

      internal fun of(propertyName: String, code: Int) = KeyboardKey(propertyName, code)

      private val canonicalPropertyNamesByCode: Map<Int, String> by lazy {
        KeyEvent::class.java.fields
            .asSequence()
            .filter {
              it.name.startsWith("VK_") &&
                  it.type == Int::class.javaPrimitiveType &&
                  Modifier.isStatic(it.modifiers)
            }
            .groupBy { it.getInt(null) }
            .mapValues { (_, fields) -> fields.minOf { it.name } }
      }
    }
  }

  sealed class GamepadSelection {
    data object Disabled : GamepadSelection()

    data object Auto : GamepadSelection()

    data class Device(val stableId: String) : GamepadSelection() {
      init {
        require(ControllerProperties.isStableGamepadId(stableId)) {
          "Gamepad device must be sdl- followed by 64 lowercase hex digits"
        }
      }
    }
  }

  data class GamepadTuning(
      val movementDeadZone: Int = DEFAULT_GAMEPAD_MOVEMENT_DEAD_ZONE,
      val tiltDeadZone: Int = DEFAULT_GAMEPAD_TILT_DEAD_ZONE,
      val invertMovementX: Boolean = false,
      val invertMovementY: Boolean = false,
      val invertTiltX: Boolean = false,
      val invertTiltY: Boolean = false,
  ) {
    init {
      require(movementDeadZone in MIN_GAMEPAD_DEAD_ZONE..MAX_GAMEPAD_DEAD_ZONE) {
        "Gamepad movement dead zone must be between $MIN_GAMEPAD_DEAD_ZONE and " +
            MAX_GAMEPAD_DEAD_ZONE
      }
      require(tiltDeadZone in MIN_GAMEPAD_DEAD_ZONE..MAX_GAMEPAD_DEAD_ZONE) {
        "Gamepad tilt dead zone must be between $MIN_GAMEPAD_DEAD_ZONE and " +
            MAX_GAMEPAD_DEAD_ZONE
      }
    }
  }

  class Saves(
      val directory: Path? = null,
      previousDirectories: List<Path> = emptyList(),
      val batterySavesEnabled: Boolean = true,
      val rewindEnabled: Boolean = true,
      val rewindSeconds: Int = DEFAULT_REWIND_SECONDS,
      val autosavePolicy: AutosavePolicy = AutosavePolicy.DISABLED,
      val resumePolicy: ResumePolicy = ResumePolicy.ASK,
  ) {
    val previousDirectories: List<Path> = immutableListCopy(previousDirectories)

    init {
      require(directory == null || directory.toString().isNotEmpty()) {
        "Save directory must not be an empty path"
      }
      require(this.previousDirectories.none { it.toString().isEmpty() }) {
        "Previous save directories must not contain an empty path"
      }
      require(this.previousDirectories.size <= MAX_PREVIOUS_SAVE_DIRECTORIES) {
        "At most $MAX_PREVIOUS_SAVE_DIRECTORIES previous save directories may be stored"
      }
      require(
          this.previousDirectories.map { it.normalize() }.distinct().size ==
              this.previousDirectories.size) {
        "Previous save directories must be unique"
      }
      require(
          directory == null ||
              this.previousDirectories.none { it.normalize() == directory.normalize() }) {
        "The active save directory must not also be a previous save directory"
      }
      require(rewindSeconds in MIN_REWIND_SECONDS..MAX_REWIND_SECONDS) {
        "Rewind duration must be between $MIN_REWIND_SECONDS and $MAX_REWIND_SECONDS seconds"
      }
    }

    fun copy(
        directory: Path? = this.directory,
        previousDirectories: List<Path> = this.previousDirectories,
        batterySavesEnabled: Boolean = this.batterySavesEnabled,
        rewindEnabled: Boolean = this.rewindEnabled,
        rewindSeconds: Int = this.rewindSeconds,
        autosavePolicy: AutosavePolicy = this.autosavePolicy,
        resumePolicy: ResumePolicy = this.resumePolicy,
    ): Saves =
        Saves(
            directory,
            previousDirectories,
            batterySavesEnabled,
            rewindEnabled,
            rewindSeconds,
            autosavePolicy,
            resumePolicy,
        )

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is Saves &&
                directory == other.directory &&
                previousDirectories == other.previousDirectories &&
                batterySavesEnabled == other.batterySavesEnabled &&
                rewindEnabled == other.rewindEnabled &&
                rewindSeconds == other.rewindSeconds &&
                autosavePolicy == other.autosavePolicy &&
                resumePolicy == other.resumePolicy)

    override fun hashCode(): Int {
      var result = directory?.hashCode() ?: 0
      result = 31 * result + previousDirectories.hashCode()
      result = 31 * result + batterySavesEnabled.hashCode()
      result = 31 * result + rewindEnabled.hashCode()
      result = 31 * result + rewindSeconds
      result = 31 * result + autosavePolicy.hashCode()
      result = 31 * result + resumePolicy.hashCode()
      return result
    }

    override fun toString(): String =
        "Saves(directory=$directory, previousDirectories=$previousDirectories, " +
            "batterySavesEnabled=$batterySavesEnabled, rewindEnabled=$rewindEnabled, " +
            "rewindSeconds=$rewindSeconds, autosavePolicy=$autosavePolicy, " +
            "resumePolicy=$resumePolicy)"
  }

  enum class AutosavePolicy {
    DISABLED,
    ON_CLOSE_AND_ROM_SWITCH,
  }

  enum class ResumePolicy {
    NEVER,
    ASK,
    ALWAYS,
  }

  data class Advanced(
      val dmgGamesProfile: ProfileSelection = ProfileSelection.Auto,
      val cgbGamesProfile: ProfileSelection = ProfileSelection.Auto,
      val bootstrapMode: BootstrapMode = BootstrapMode.SKIP,
      val datelSlotRom: Path? = null,
      val fullChangerCharacter: String? = null,
  )

  sealed class ProfileSelection {
    data object Auto : ProfileSelection()

    data class Explicit(val profile: HardwareProfile) : ProfileSelection() {
      init {
        HardwareProfileRegistry.requireRegistered(profile)
      }
    }

    fun explicitProfileOrNull(): HardwareProfile? = (this as? Explicit)?.profile

    fun effective(defaultProfile: HardwareProfile): HardwareProfile =
        explicitProfileOrNull() ?: defaultProfile
  }

  companion object {
    const val CURRENT_SCHEMA_VERSION = 5
    const val MIN_RECENT_FILE_CAPACITY = 0
    const val DEFAULT_RECENT_FILE_CAPACITY = 10
    const val MAX_RECENT_FILE_CAPACITY = 50
    const val MIN_AUDIO_VOLUME = 0
    const val DEFAULT_AUDIO_VOLUME = 100
    const val MAX_AUDIO_VOLUME = 100
    const val MIN_GAMEPAD_DEAD_ZONE = 0
    const val DEFAULT_GAMEPAD_MOVEMENT_DEAD_ZONE = 16_384
    const val DEFAULT_GAMEPAD_TILT_DEAD_ZONE = 4_096
    const val MAX_GAMEPAD_DEAD_ZONE = 32_766
    const val MAX_GAMEPAD_TUNINGS = 32
    const val MIN_EXPLICIT_DISPLAY_SCALE = 1
    const val DEFAULT_EXPLICIT_DISPLAY_SCALE = 2
    const val MAX_EXPLICIT_DISPLAY_SCALE = 4
    const val MIN_LETTERBOX_COLOR = 0x000000
    const val DEFAULT_LETTERBOX_COLOR = 0x000000
    const val MAX_LETTERBOX_COLOR = 0xFFFFFF
    const val MAX_PREVIOUS_SAVE_DIRECTORIES = 4
    const val MIN_REWIND_SECONDS = 5
    const val DEFAULT_REWIND_SECONDS = 30
    const val MAX_REWIND_SECONDS = 120

    internal fun isStableAudioOutputId(value: String): Boolean =
        value.matches(STABLE_AUDIO_OUTPUT_ID)

    private val STABLE_AUDIO_OUTPUT_ID = Regex("java-sound-[0-9a-f]{64}")
  }
}

/** Transient highest-precedence launch values. They are deliberately never persisted. */
data class ApplicationSettingsOverrides(
    val hardwareProfile: HardwareProfile? = null,
    val bootstrapMode: BootstrapMode? = null,
    val batterySavesEnabled: Boolean? = null,
) {
  init {
    hardwareProfile?.let(HardwareProfileRegistry::requireRegistered)
  }
}

/** Typed settings plus unrecognized legacy fields retained losslessly across versioned writes. */
class ApplicationSettingsDocument(
    val settings: ApplicationSettings,
    unknownProperties: Map<String, String> = emptyMap(),
) {
  val unknownProperties: Map<String, String> = immutableMapCopy(unknownProperties)

  fun copy(
      settings: ApplicationSettings = this.settings,
      unknownProperties: Map<String, String> = this.unknownProperties,
  ): ApplicationSettingsDocument = ApplicationSettingsDocument(settings, unknownProperties)

  override fun equals(other: Any?): Boolean =
      this === other ||
          (other is ApplicationSettingsDocument &&
              settings == other.settings &&
              unknownProperties == other.unknownProperties)

  override fun hashCode(): Int = 31 * settings.hashCode() + unknownProperties.hashCode()

  override fun toString(): String =
      "ApplicationSettingsDocument(settings=$settings, unknownProperties=$unknownProperties)"
}

private fun <T> immutableListCopy(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun <K, V> immutableMapCopy(values: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(values))
