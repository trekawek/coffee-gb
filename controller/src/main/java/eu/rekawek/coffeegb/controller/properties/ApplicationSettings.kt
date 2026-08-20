package eu.rekawek.coffeegb.controller.properties

import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.hardware.HardwareProfile
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.joypad.Button
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
    val peripherals: Peripherals = Peripherals(),
    val saves: Saves = Saves(),
    val advanced: Advanced = Advanced(),
    val desktop: Desktop = Desktop(),
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

  /** Desktop-shell preferences and legacy state independent from emulated display settings. */
  data class Desktop(
      val windowSize: WindowSize? = null,
      val appearance: Appearance = Appearance.LIGHT,
      val commandBarVisible: Boolean = true,
  )

  enum class Appearance {
    LIGHT,
    DARK,
    SYSTEM,
  }

  /** Last normal, windowed outer-frame size. Placement and maximized state are not persisted. */
  data class WindowSize(
      val width: Int,
      val height: Int,
  ) {
    init {
      require(width > 0 && height > 0) { "Desktop window size must be positive" }
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
      val byKey = linkedMapOf<KeyboardKey, ControllerProperties.PlayerButton>()
      keyboard.entries.sortedWith(
              compareBy<Map.Entry<ControllerProperties.PlayerButton, KeyboardKey>>(
                  { it.key.player }, { it.key.button.ordinal }))
          .forEach { (binding, key) ->
            val previous = byKey.put(key, binding)
            require(previous == null) {
              "Key ${key.propertyName} is assigned to both " +
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
            put(Button.LEFT, KeyboardKey.parse("VK_LEFT", "default left button"))
            put(Button.RIGHT, KeyboardKey.parse("VK_RIGHT", "default right button"))
            put(Button.UP, KeyboardKey.parse("VK_UP", "default up button"))
            put(Button.DOWN, KeyboardKey.parse("VK_DOWN", "default down button"))
            put(Button.A, KeyboardKey.parse("VK_Z", "default A button"))
            put(Button.B, KeyboardKey.parse("VK_X", "default B button"))
            put(Button.START, KeyboardKey.parse("VK_ENTER", "default start button"))
            put(Button.SELECT, KeyboardKey.parse("VK_SHIFT", "default select button"))
          }
    }
  }

  /** A portable persisted keyboard token. Each frontend resolves it to its own host key code. */
  class KeyboardKey private constructor(val propertyName: String) {
    override fun equals(other: Any?): Boolean =
        other is KeyboardKey && propertyName == other.propertyName

    override fun hashCode(): Int = propertyName.hashCode()

    override fun toString(): String = propertyName

    companion object {
      fun parse(propertyName: String, property: String): KeyboardKey {
        val canonicalName =
            when (propertyName) {
              // The JDK exposes both spellings for the same key code. Keep the historical
              // lexicographic canonical form so duplicate bindings remain invalid everywhere.
              "VK_SEPARATOR" -> "VK_SEPARATER"
              else -> propertyName
            }
        require(canonicalName in KEY_NAMES) {
          "$property must use a known VK_* keyboard token"
        }
        return KeyboardKey(canonicalName)
      }

      /**
       * Stable names copied from the desktop key-code contract, rather than resolving a desktop
       * class at runtime. The desktop shell performs its own token-to-key-code resolution.
       */
      private val KEY_NAMES =
          """
          VK_ENTER VK_BACK_SPACE VK_TAB VK_CANCEL VK_CLEAR VK_SHIFT VK_CONTROL VK_ALT VK_PAUSE
          VK_CAPS_LOCK VK_ESCAPE VK_SPACE VK_PAGE_UP VK_PAGE_DOWN VK_END VK_HOME VK_LEFT VK_UP
          VK_RIGHT VK_DOWN VK_COMMA VK_MINUS VK_PERIOD VK_SLASH VK_0 VK_1 VK_2 VK_3 VK_4 VK_5
          VK_6 VK_7 VK_8 VK_9 VK_SEMICOLON VK_EQUALS VK_A VK_B VK_C VK_D VK_E VK_F VK_G VK_H
          VK_I VK_J VK_K VK_L VK_M VK_N VK_O VK_P VK_Q VK_R VK_S VK_T VK_U VK_V VK_W VK_X VK_Y
          VK_Z VK_OPEN_BRACKET VK_BACK_SLASH VK_CLOSE_BRACKET VK_NUMPAD0 VK_NUMPAD1 VK_NUMPAD2
          VK_NUMPAD3 VK_NUMPAD4 VK_NUMPAD5 VK_NUMPAD6 VK_NUMPAD7 VK_NUMPAD8 VK_NUMPAD9
          VK_MULTIPLY VK_ADD VK_SEPARATER VK_SEPARATOR VK_SUBTRACT VK_DECIMAL VK_DIVIDE VK_DELETE
          VK_NUM_LOCK VK_SCROLL_LOCK VK_F1 VK_F2 VK_F3 VK_F4 VK_F5 VK_F6 VK_F7 VK_F8 VK_F9 VK_F10
          VK_F11 VK_F12 VK_F13 VK_F14 VK_F15 VK_F16 VK_F17 VK_F18 VK_F19 VK_F20 VK_F21 VK_F22
          VK_F23 VK_F24 VK_PRINTSCREEN VK_INSERT VK_HELP VK_META VK_BACK_QUOTE VK_QUOTE VK_KP_UP
          VK_KP_DOWN VK_KP_LEFT VK_KP_RIGHT VK_DEAD_GRAVE VK_DEAD_ACUTE VK_DEAD_CIRCUMFLEX
          VK_DEAD_TILDE VK_DEAD_MACRON VK_DEAD_BREVE VK_DEAD_ABOVEDOT VK_DEAD_DIAERESIS
          VK_DEAD_ABOVERING VK_DEAD_DOUBLEACUTE VK_DEAD_CARON VK_DEAD_CEDILLA VK_DEAD_OGONEK
          VK_DEAD_IOTA VK_DEAD_VOICED_SOUND VK_DEAD_SEMIVOICED_SOUND VK_AMPERSAND VK_ASTERISK
          VK_QUOTEDBL VK_LESS VK_GREATER VK_BRACELEFT VK_BRACERIGHT VK_AT VK_COLON VK_CIRCUMFLEX
          VK_DOLLAR VK_EURO_SIGN VK_EXCLAMATION_MARK VK_INVERTED_EXCLAMATION_MARK
          VK_LEFT_PARENTHESIS VK_NUMBER_SIGN VK_PLUS VK_RIGHT_PARENTHESIS VK_UNDERSCORE VK_WINDOWS
          VK_CONTEXT_MENU VK_FINAL VK_CONVERT VK_NONCONVERT VK_ACCEPT VK_MODECHANGE VK_KANA VK_KANJI
          VK_ALPHANUMERIC VK_KATAKANA VK_HIRAGANA VK_FULL_WIDTH VK_HALF_WIDTH VK_ROMAN_CHARACTERS
          VK_ALL_CANDIDATES VK_PREVIOUS_CANDIDATE VK_CODE_INPUT VK_JAPANESE_KATAKANA
          VK_JAPANESE_HIRAGANA VK_JAPANESE_ROMAN VK_KANA_LOCK VK_INPUT_METHOD_ON_OFF VK_CUT VK_COPY
          VK_PASTE VK_UNDO VK_AGAIN VK_FIND VK_PROPS VK_STOP VK_COMPOSE VK_ALT_GRAPH VK_BEGIN
          VK_UNDEFINED
          """
              .trimIndent()
              .split(Regex("\\s+"))
              .toSet()
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

  /** Host peripherals whose selection is independent from emulated link-port state. */
  data class Peripherals(
      val cameraDeviceIndex: Int = DEFAULT_CAMERA_DEVICE_INDEX,
  ) {
    init {
      require(cameraDeviceIndex in MIN_CAMERA_DEVICE_INDEX..MAX_CAMERA_DEVICE_INDEX) {
        "Camera device index must be between $MIN_CAMERA_DEVICE_INDEX and " +
            MAX_CAMERA_DEVICE_INDEX
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

  class Saves
  @JvmOverloads
  constructor(
      val directory: Path? = null,
      previousDirectories: List<Path> = emptyList(),
      val batterySavesEnabled: Boolean = true,
      val rewindEnabled: Boolean = true,
      val rewindSeconds: Int = DEFAULT_REWIND_SECONDS,
      /** Retained solely to read older settings files; autosave is now always enabled. */
      val autosavePolicy: AutosavePolicy = AutosavePolicy.ON_CLOSE_AND_ROM_SWITCH,
      val resumePolicy: ResumePolicy = ResumePolicy.ASK,
      val rewindMemoryMiB: Int = DEFAULT_REWIND_MEMORY_MIB,
  ) {
    val previousDirectories: List<Path> = immutableListCopy(previousDirectories)

    init {
      require(directory == null || directory.toString().isNotEmpty()) {
        "Save directory must not be an empty path"
      }
      require(directory == null || isStructurallySafeDirectory(directory)) {
        "Save directory must be a named directory below the filesystem root"
      }
      require(this.previousDirectories.none { it.toString().isEmpty() }) {
        "Previous save directories must not contain an empty path"
      }
      require(this.previousDirectories.all(::isStructurallySafeDirectory)) {
        "Previous save directories must be named directories below the filesystem root"
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
      require(rewindMemoryMiB in MIN_REWIND_MEMORY_MIB..MAX_REWIND_MEMORY_MIB) {
        "Rewind memory must be between $MIN_REWIND_MEMORY_MIB and $MAX_REWIND_MEMORY_MIB MiB"
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
        rewindMemoryMiB: Int = this.rewindMemoryMiB,
    ): Saves =
        Saves(
            directory,
            previousDirectories,
            batterySavesEnabled,
            rewindEnabled,
            rewindSeconds,
            autosavePolicy,
            resumePolicy,
            rewindMemoryMiB,
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
                resumePolicy == other.resumePolicy &&
                rewindMemoryMiB == other.rewindMemoryMiB)

    override fun hashCode(): Int {
      var result = directory?.hashCode() ?: 0
      result = 31 * result + previousDirectories.hashCode()
      result = 31 * result + batterySavesEnabled.hashCode()
      result = 31 * result + rewindEnabled.hashCode()
      result = 31 * result + rewindSeconds
      result = 31 * result + autosavePolicy.hashCode()
      result = 31 * result + resumePolicy.hashCode()
      result = 31 * result + rewindMemoryMiB
      return result
    }

    override fun toString(): String =
        "Saves(directory=$directory, previousDirectories=$previousDirectories, " +
            "batterySavesEnabled=$batterySavesEnabled, rewindEnabled=$rewindEnabled, " +
            "rewindSeconds=$rewindSeconds, autosavePolicy=$autosavePolicy, " +
            "resumePolicy=$resumePolicy, rewindMemoryMiB=$rewindMemoryMiB)"

    companion object {
      /**
       * Managed save roots must have both a final name and a parent after absolute
       * normalization. A filesystem root cannot safely own the fixed `games` hierarchy.
       */
      @JvmStatic
      fun isStructurallySafeDirectory(directory: Path): Boolean =
          try {
            val normalized = directory.toAbsolutePath().normalize()
            normalized.fileName != null && normalized.parent != null
          } catch (_: RuntimeException) {
            false
          }
    }
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
    const val CURRENT_SCHEMA_VERSION = 8
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
    const val MIN_CAMERA_DEVICE_INDEX = 0
    const val DEFAULT_CAMERA_DEVICE_INDEX = 0
    const val MAX_CAMERA_DEVICE_INDEX = 15
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
    const val MIN_REWIND_MEMORY_MIB = 8
    const val DEFAULT_REWIND_MEMORY_MIB = 64
    const val MAX_REWIND_MEMORY_MIB = 512

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
    val rewindEnabled: Boolean? = null,
    val runtimeWarmupEnabled: Boolean? = null,
    /** Transient benchmark policy; never persisted into user settings or save state. */
    val benchmarkPolicyEnabled: Boolean = false,
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
