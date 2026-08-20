package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.swing.io.GamepadCatalog
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import org.junit.Test

class DesktopPortableSettingsTest {
  @Test
  fun `committed system display camera and gamepad choices do not reapply`() {
    val properties = EmulatorProperties()
    val eventBus = EventBusImpl()
    val displaySettings = FakeDisplaySettings(properties.applicationSettings.display)
    val displayController =
        onEdt {
          DesktopDisplayController(displaySettings, eventBus, FakeFullscreenRuntime())
        }
    var cameraEnableCalls = 0
    var cameraApplyCalls = 0
    var deviceApplyCalls = 0
    var systemEvents = 0
    val initialGamepad = properties.applicationSettings.input.gamepads[0]
    eventBus.register<Controller.UpdatedSystemMappingEvent> { systemEvents++ }
    val access =
        DesktopPortableSettingsAccess(
            properties = properties,
            eventBus = eventBus,
            displayController = displayController,
            gamepadCatalog = { GamepadCatalog.Snapshot(GamepadCatalog.Status.AVAILABLE, emptyList(), "") },
            applyDeviceSettings = { deviceApplyCalls++ },
            isCameraEnabled = { false },
            setCameraEnabled = { cameraEnableCalls++ },
            applyCameraSettings = { cameraApplyCalls++ },
        )

    onEdt {
      val snapshot = access.snapshot()
      access.applyChoice(
          PortableMenuSettingId.DMG_GAMES,
          snapshot.value(PortableMenuSettingId.DMG_GAMES)!!,
      )
      access.applyChoice(
          PortableMenuSettingId.DMG_COLORS,
          snapshot.value(PortableMenuSettingId.DMG_COLORS)!!,
      )
      access.applyChoice(PortableMenuSettingId.CAMERA, "off")
      val gamepad = snapshot.value(PortableMenuSettingId.GAMEPAD)
      if (gamepad != null && snapshot.choicesFor(PortableMenuSettingId.GAMEPAD).any {
            it.token == gamepad && it.enabled
          }) {
        access.applyChoice(PortableMenuSettingId.GAMEPAD, gamepad)
      }
    }

    assertEquals(0, systemEvents)
    assertEquals(0, displaySettings.replacements)
    assertEquals(0, cameraEnableCalls)
    assertEquals(0, cameraApplyCalls)
    assertEquals(0, deviceApplyCalls)
    assertEquals(initialGamepad, properties.applicationSettings.input.gamepads[0])
    onEdt { displayController.close() }
    eventBus.close()
    properties.close()
  }

  private fun <T> onEdt(action: () -> T): T {
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(action) }
    return checkNotNull(result).getOrThrow()
  }

  private class FakeDisplaySettings(
      var display: ApplicationSettings.Display,
  ) : DisplaySettingsAccess {
    var replacements = 0

    override fun current(): ApplicationSettings.Display = display

    override fun replace(display: ApplicationSettings.Display) {
      this.display = display
      replacements++
    }
  }

  private class FakeFullscreenRuntime : FullscreenRuntime {
    override fun isFullscreen(): Boolean = false

    override fun setFullscreen(fullscreen: Boolean) = Unit

    override fun refreshScreens() = Unit

    override fun close() = Unit
  }
}
