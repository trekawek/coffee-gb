package eu.rekawek.coffeegb.controller.properties

import eu.rekawek.coffeegb.core.joypad.Button
import java.awt.event.KeyEvent
import java.util.EnumMap
import java.util.Locale
import java.util.Properties

/** Strict, backward-compatible desktop input property parser. */
object ControllerProperties {
  private val playerButton = Regex("input\\.p(\\d+)\\.btn_([a-zA-Z_]+)")
  private val playerGamepad = Regex("input\\.p(\\d+)\\.gamepad")
  private val stableGamepadId = Regex("sdl-[0-9a-f]{64}")

  data class PlayerButton(val player: Int, val button: Button) {
    init {
      require(player in 0..3) { "Logical player index must be in 0..3" }
    }
  }

  data class GamepadAssignment(val player: Int, val selector: String) {
    init {
      require(player in 0..3) { "Logical player index must be in 0..3" }
      require(selector == AUTO || stableGamepadId.matches(selector)) {
        "Gamepad selector must be 'auto' or sdl- followed by 64 lowercase hex digits"
      }
    }

    companion object {
      const val AUTO = "auto"
    }
  }

  data class PlayerMapping(
      val keyboard: Map<Int, PlayerButton>,
      val gamepads: List<GamepadAssignment>,
  ) {
    /** Historical P1 view retained for source compatibility with existing callers. */
    fun legacyPrimaryKeyboard(): Map<Int, Button> =
        keyboard.filterValues { it.player == 0 }.mapValues { it.value.button }
  }

  fun getPlayerMapping(properties: Properties): PlayerMapping {
    val perPlayer = List(4) { EnumMap<Button, Int>(Button::class.java) }
    defaultPrimaryKeys().forEach { (button, key) -> perPlayer[0][button] = key }
    val explicitLegacy = mutableSetOf<Button>()
    val explicitPlayer = mutableSetOf<Pair<Int, Button>>()
    val gamepads = mutableMapOf(0 to GamepadAssignment.AUTO)

    properties.stringPropertyNames().sorted().forEach { key ->
      val value = properties.getProperty(key).trim()
      when {
        key.startsWith("btn_") -> {
          val button = parseButton(key.substring(4), key)
          require((0 to button) !in explicitPlayer) {
            "$key duplicates input.p1.btn_${button.name.lowercase(Locale.ROOT)}"
          }
          explicitLegacy += button
          perPlayer[0][button] = parseKey(value, key)
        }
        playerButton.matches(key) -> {
          val match = checkNotNull(playerButton.matchEntire(key))
          val player = parsePlayer(match.groupValues[1], key)
          val button = parseButton(match.groupValues[2], key)
          require(player != 0 || button !in explicitLegacy) {
            "$key duplicates btn_${button.name.lowercase(Locale.ROOT)}"
          }
          require(explicitPlayer.add(player to button)) { "Duplicate mapping property $key" }
          perPlayer[player][button] = parseKey(value, key)
        }
        playerGamepad.matches(key) -> {
          val match = checkNotNull(playerGamepad.matchEntire(key))
          val player = parsePlayer(match.groupValues[1], key)
          require(value == "none" || value == GamepadAssignment.AUTO || stableGamepadId.matches(value)) {
            "$key must be 'none', 'auto', or sdl- followed by 64 lowercase hex digits"
          }
          gamepads[player] = value
        }
        key.startsWith("input.") -> throw IllegalArgumentException("Unknown input property: $key")
      }
    }

    val keyboard = linkedMapOf<Int, PlayerButton>()
    perPlayer.forEachIndexed { player, bindings ->
      bindings.forEach { (button, key) ->
        val previous = keyboard.put(key, PlayerButton(player, button))
        require(previous == null) {
          "Key ${KeyEvent.getKeyText(key)} is assigned to both P${previous!!.player + 1} " +
              "${previous.button} and P${player + 1} $button"
        }
      }
    }

    val assignments = gamepads.entries
        .filter { it.value != "none" }
        .sortedBy { it.key }
        .map { GamepadAssignment(it.key, it.value) }
    val duplicate = assignments.groupBy { it.selector }.entries.firstOrNull { it.value.size > 1 }
    require(duplicate == null) {
      "Gamepad selector ${duplicate!!.key} is assigned to multiple logical players"
    }
    return PlayerMapping(keyboard.toMap(), assignments)
  }

  fun getControllerMapping(properties: Properties): Map<Int, Button> =
      getPlayerMapping(properties).legacyPrimaryKeyboard()

  private fun defaultPrimaryKeys() = mapOf(
      Button.LEFT to KeyEvent.VK_LEFT,
      Button.RIGHT to KeyEvent.VK_RIGHT,
      Button.UP to KeyEvent.VK_UP,
      Button.DOWN to KeyEvent.VK_DOWN,
      Button.A to KeyEvent.VK_Z,
      Button.B to KeyEvent.VK_X,
      Button.START to KeyEvent.VK_ENTER,
      Button.SELECT to KeyEvent.VK_SHIFT,
  )

  private fun parsePlayer(value: String, property: String): Int {
    val human = value.toIntOrNull()
        ?: throw IllegalArgumentException("Invalid logical player in $property")
    require(human in 1..4) { "$property player must be P1 through P4" }
    return human - 1
  }

  private fun parseButton(value: String, property: String): Button = try {
    Button.valueOf(value.uppercase(Locale.ROOT))
  } catch (_: IllegalArgumentException) {
    throw IllegalArgumentException("Unknown Game Boy button in $property: $value")
  }

  private fun parseKey(value: String, property: String): Int {
    require(value.startsWith("VK_")) { "$property must name a java.awt.event.KeyEvent VK_* constant" }
    val field = try {
      KeyEvent::class.java.getField(value)
    } catch (_: ReflectiveOperationException) {
      throw IllegalArgumentException("Unknown keyboard key in $property: $value")
    }
    require(field.type == Int::class.javaPrimitiveType) { "Invalid keyboard key in $property: $value" }
    return field.getInt(null)
  }
}
