package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.events.EventBus
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.swing.io.GamepadCatalog

/**
 * Desktop implementation of the typed in-screen settings port.
 *
 * The overlay never receives [EmulatorProperties], an event bus, or a native device API. This
 * adapter is the one place where an accepted choice is persisted and handed to the live Swing
 * runtime. All callers are menu actions on the EDT.
 */
internal class DesktopPortableSettingsAccess(
    private val properties: EmulatorProperties,
    private val eventBus: EventBus,
    private val displayController: DesktopDisplayController,
    private val gamepadCatalog: () -> GamepadCatalog.Snapshot,
    private val applyDeviceSettings: (ApplicationSettings) -> Unit,
    private val isCameraEnabled: () -> Boolean,
    private val setCameraEnabled: (Boolean) -> Unit,
    private val applyCameraSettings: (ApplicationSettings.Peripherals) -> Unit,
) : PortableMenuSettingsAccess {

  override fun snapshot(): PortableMenuSettingsSnapshot {
    val settings = properties.applicationSettings
    val advanced = settings.advanced
    val selectedGamepad = settings.input.gamepads[0] ?: ApplicationSettings.GamepadSelection.Auto
    val discoveredGamepads = gamepadCatalog().devices().sortedBy { it.stableId() }
    val cameraToken =
        if (isCameraEnabled()) {
          "camera-${settings.peripherals.cameraDeviceIndex}"
        } else {
          CAMERA_OFF
        }
    val gamepadToken = selectedGamepadToken(selectedGamepad)

    val dmgChoices =
        profileChoices(
            listOf("auto", "dmg", "cgb", "sgb"),
            advanced.dmgGamesProfile,
        )
    val cgbChoices =
        profileChoices(
            listOf("auto", "cgb"),
            advanced.cgbGamesProfile,
        )

    val gamepadChoices =
        buildList {
          add(PortableMenuSettingChoice(GAMEPAD_OFF, "OFF"))
          add(PortableMenuSettingChoice(GAMEPAD_AUTO, "AUTO"))
          discoveredGamepads.forEach {
            add(PortableMenuSettingChoice(it.stableId(), it.name().uppercase()))
          }
          // A selected device can disappear between polls. Preserve it in the picker so opening
          // and cancelling the route never silently changes the persisted assignment.
          if (selectedGamepad is ApplicationSettings.GamepadSelection.Device &&
              discoveredGamepads.none { it.stableId() == selectedGamepad.stableId }) {
            add(
                PortableMenuSettingChoice(
                    selectedGamepad.stableId,
                    "UNAVAILABLE ${selectedGamepad.stableId.takeLast(8).uppercase()}",
                    enabled = false,
                ))
          }
        }

    val cameraChoices =
        buildList {
          add(PortableMenuSettingChoice(CAMERA_OFF, "OFF"))
          (ApplicationSettings.MIN_CAMERA_DEVICE_INDEX..
                  ApplicationSettings.MAX_CAMERA_DEVICE_INDEX)
              .forEach { index ->
                add(PortableMenuSettingChoice("camera-$index", "CAMERA ${index + 1}"))
              }
        }

    val gamepadDisplayValue =
        when (selectedGamepad) {
          ApplicationSettings.GamepadSelection.Disabled -> "OFF"
          ApplicationSettings.GamepadSelection.Auto -> "AUTO"
          is ApplicationSettings.GamepadSelection.Device ->
              discoveredGamepads.firstOrNull { it.stableId() == selectedGamepad.stableId }?.name
                  ?.uppercase()
                  ?: "UNAVAILABLE ${selectedGamepad.stableId.takeLast(8).uppercase()}"
        }
    val cameraDisplayValue =
        if (cameraToken == CAMERA_OFF) {
          "OFF"
        } else {
          "CAMERA ${(settings.peripherals.cameraDeviceIndex + 1)}"
        }

    return PortableMenuSettingsSnapshot(
        values =
            mapOf(
                PortableMenuSettingId.DMG_GAMES to profileToken(advanced.dmgGamesProfile),
                PortableMenuSettingId.CGB_GAMES to profileToken(advanced.cgbGamesProfile),
                PortableMenuSettingId.BOOTSTRAP to bootstrapToken(advanced.bootstrapMode),
                PortableMenuSettingId.SGB_BORDER to if (settings.display.showSgbBorder) "on" else "off",
                PortableMenuSettingId.DMG_COLORS to if (settings.display.grayscale) "grey" else "green",
                PortableMenuSettingId.CAMERA to cameraToken,
                PortableMenuSettingId.GAMEPAD to gamepadToken,
            ),
        choices =
            mapOf(
                PortableMenuSettingId.DMG_GAMES to dmgChoices,
                PortableMenuSettingId.CGB_GAMES to cgbChoices,
                PortableMenuSettingId.BOOTSTRAP to
                    listOf(
                        PortableMenuSettingChoice("skip", "SKIP"),
                        PortableMenuSettingChoice("fast-forward", "FAST-FORWARD"),
                        PortableMenuSettingChoice("full", "FULL"),
                    ),
                PortableMenuSettingId.DMG_COLORS to
                    listOf(
                        PortableMenuSettingChoice("green", "GREEN"),
                        PortableMenuSettingChoice("grey", "GREY"),
                    ),
                PortableMenuSettingId.CAMERA to cameraChoices,
                PortableMenuSettingId.GAMEPAD to gamepadChoices,
            ),
        toggleIds = setOf(PortableMenuSettingId.SGB_BORDER),
        displayValues =
            mapOf(
                PortableMenuSettingId.DMG_GAMES to profileToken(advanced.dmgGamesProfile),
                PortableMenuSettingId.CGB_GAMES to profileToken(advanced.cgbGamesProfile),
                PortableMenuSettingId.BOOTSTRAP to bootstrapToken(advanced.bootstrapMode),
                PortableMenuSettingId.SGB_BORDER to
                    if (settings.display.showSgbBorder) "ON" else "OFF",
                PortableMenuSettingId.DMG_COLORS to
                    if (settings.display.grayscale) "GREY" else "GREEN",
                PortableMenuSettingId.CAMERA to cameraDisplayValue,
                PortableMenuSettingId.GAMEPAD to gamepadDisplayValue,
            ),
    )
  }

  override fun applyChoice(id: String, token: String) {
    val choice = snapshot().choicesFor(id).firstOrNull { it.token == token }
    require(choice != null && choice.enabled) {
      "Unknown choice '$token' for '$id'"
    }
    when (id) {
      PortableMenuSettingId.DMG_GAMES -> updateSystem { it.copy(dmgGamesProfile = profileSelection(token)) }
      PortableMenuSettingId.CGB_GAMES -> updateSystem { it.copy(cgbGamesProfile = profileSelection(token)) }
      PortableMenuSettingId.BOOTSTRAP ->
          updateSystem { it.copy(bootstrapMode = bootstrapMode(token)) }
      PortableMenuSettingId.DMG_COLORS -> updateDisplay { it.copy(grayscale = token == "grey") }
      PortableMenuSettingId.CAMERA -> applyCameraChoice(token)
      PortableMenuSettingId.GAMEPAD -> applyGamepadChoice(token)
      PortableMenuSettingId.SGB_BORDER -> error("SGB border is a toggle, not a picker")
      else -> error("Unknown portable setting: $id")
    }
  }

  override fun toggle(id: String) {
    require(id == PortableMenuSettingId.SGB_BORDER) { "Only SGB border is a portable toggle" }
    updateDisplay { it.copy(showSgbBorder = !it.showSgbBorder) }
  }

  private fun updateSystem(update: (ApplicationSettings.Advanced) -> ApplicationSettings.Advanced) {
    val current = properties.applicationSettings
    val nextAdvanced = update(current.advanced)
    if (nextAdvanced == current.advanced) return
    properties.updateApplicationSettings { current ->
      current.copy(advanced = nextAdvanced)
    }
    // BasicController re-resolves the next session's mapping from the persisted typed settings.
    // Keep this event for active-session semantics and for legacy observers.
    eventBus.post(Controller.UpdatedSystemMappingEvent())
  }

  private fun applyCameraChoice(token: String) {
    if (token == CAMERA_OFF) {
      if (isCameraEnabled()) setCameraEnabled(false)
      return
    }
    val index = token.removePrefix("camera-").toIntOrNull()
    require(index != null &&
        index in ApplicationSettings.MIN_CAMERA_DEVICE_INDEX..
            ApplicationSettings.MAX_CAMERA_DEVICE_INDEX)
    val currentPeripherals = properties.applicationSettings.peripherals
    val wasEnabled = isCameraEnabled()
    if (currentPeripherals.cameraDeviceIndex != index) {
      properties.updateApplicationSettings { current ->
        current.copy(peripherals = current.peripherals.copy(cameraDeviceIndex = index))
      }
      val peripherals = properties.applicationSettings.peripherals
      applyCameraSettings(peripherals)
    }
    if (!wasEnabled) setCameraEnabled(true)
  }

  private fun applyGamepadChoice(token: String) {
    val selection =
        when (token) {
          GAMEPAD_OFF -> ApplicationSettings.GamepadSelection.Disabled
          GAMEPAD_AUTO -> ApplicationSettings.GamepadSelection.Auto
          else -> ApplicationSettings.GamepadSelection.Device(token)
        }
    val current = properties.applicationSettings
    if (current.input.gamepads[0] == selection) return
    properties.updateApplicationSettings { current ->
      current.copy(input = current.input.copy(gamepads = current.input.gamepads + (0 to selection)))
    }
    applyDeviceSettings(properties.applicationSettings)
  }

  private fun updateDisplay(update: (ApplicationSettings.Display) -> ApplicationSettings.Display) {
    val current = displayController.current()
    val next = update(current)
    if (next != current) displayController.update { next }
  }

  private fun profileChoices(
      defaults: List<String>,
      current: ApplicationSettings.ProfileSelection,
  ): List<PortableMenuSettingChoice> {
    val currentToken = profileToken(current)
    val tokens = if (currentToken in defaults) defaults else defaults + currentToken
    return tokens.map { token -> PortableMenuSettingChoice(token, token.uppercase()) }
  }

  private fun profileToken(selection: ApplicationSettings.ProfileSelection): String =
      when (selection) {
        ApplicationSettings.ProfileSelection.Auto -> "auto"
        is ApplicationSettings.ProfileSelection.Explicit -> selection.profile.id()
      }

  private fun profileSelection(token: String): ApplicationSettings.ProfileSelection =
      if (token == "auto") {
        ApplicationSettings.ProfileSelection.Auto
      } else {
        ApplicationSettings.ProfileSelection.Explicit(HardwareProfileRegistry.resolve(token))
      }

  private fun bootstrapToken(mode: BootstrapMode): String =
      when (mode) {
        BootstrapMode.SKIP -> "skip"
        BootstrapMode.FAST_FORWARD -> "fast-forward"
        BootstrapMode.NORMAL -> "full"
      }

  private fun bootstrapMode(token: String): BootstrapMode =
      when (token) {
        "skip" -> BootstrapMode.SKIP
        "fast-forward" -> BootstrapMode.FAST_FORWARD
        "full" -> BootstrapMode.NORMAL
        else -> error("Unknown bootstrap choice: $token")
      }

  private fun selectedGamepadToken(selection: ApplicationSettings.GamepadSelection): String =
      when (selection) {
        ApplicationSettings.GamepadSelection.Disabled -> GAMEPAD_OFF
        ApplicationSettings.GamepadSelection.Auto -> GAMEPAD_AUTO
        is ApplicationSettings.GamepadSelection.Device -> selection.stableId
      }

  private companion object {
    const val CAMERA_OFF = "off"
    const val GAMEPAD_OFF = "off"
    const val GAMEPAD_AUTO = "auto"
  }
}
