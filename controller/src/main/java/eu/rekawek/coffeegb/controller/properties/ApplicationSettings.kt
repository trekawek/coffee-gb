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
    require(display.scale in SUPPORTED_SCALES) {
      "Display scale must be one of ${SUPPORTED_SCALES.sorted()}"
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
      val scale: Int = 2,
      val grayscale: Boolean = false,
      val blending: Boolean = true,
      val colorCorrection: Boolean = true,
      val rotation: Rotation = Rotation.DEG_0,
      val showSgbBorder: Boolean = false,
  )

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

  data class Audio(val enabled: Boolean = true)

  class Input(
      keyboard: Map<ControllerProperties.PlayerButton, KeyboardKey>,
      gamepads: Map<Int, GamepadSelection>,
  ) {
    val keyboard: Map<ControllerProperties.PlayerButton, KeyboardKey> = immutableMapCopy(keyboard)

    val gamepads: Map<Int, GamepadSelection> = immutableMapCopy(gamepads)

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
    ): Input = Input(keyboard, gamepads)

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is Input && keyboard == other.keyboard && gamepads == other.gamepads)

    override fun hashCode(): Int = 31 * keyboard.hashCode() + gamepads.hashCode()

    override fun toString(): String = "Input(keyboard=$keyboard, gamepads=$gamepads)"

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

  data class Saves(val batterySavesEnabled: Boolean = true)

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
    const val CURRENT_SCHEMA_VERSION = 2
    const val MIN_RECENT_FILE_CAPACITY = 0
    const val DEFAULT_RECENT_FILE_CAPACITY = 10
    const val MAX_RECENT_FILE_CAPACITY = 50
    private val SUPPORTED_SCALES: Set<Int> =
        Collections.unmodifiableSet(linkedSetOf(1, 2, 4))
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
