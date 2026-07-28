package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.swing.io.AudioDeviceSnapshot
import eu.rekawek.coffeegb.swing.io.AudioRuntimeConfiguration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class SwingEmulatorSettingsTest {
  @Test
  fun `typed audio settings map completely to the host runtime`() {
    val deviceId = "java-sound-" + "a".repeat(64)
    val runtime =
        ApplicationSettings.Audio(
                enabled = false,
                output = ApplicationSettings.AudioOutputSelection.Device(deviceId),
                volume = 37,
                latency = ApplicationSettings.AudioLatency.SAFE,
            )
            .toRuntimeConfiguration()

    assertEquals(deviceId, runtime.outputDeviceId())
    assertEquals(37, runtime.masterVolume())
    assertTrue(runtime.muted())
    assertEquals(AudioRuntimeConfiguration.LatencyPreset.SAFE, runtime.latencyPreset())
  }

  @Test
  fun `default audio output retains the symbolic host selection`() {
    val runtime = ApplicationSettings.Audio().toRuntimeConfiguration()

    assertEquals(AudioDeviceSnapshot.SYSTEM_DEFAULT_ID, runtime.outputDeviceId())
    assertFalse(runtime.muted())
  }

  @Test
  fun `gamepad assignments and per-device tuning cross the runtime boundary`() {
    val stableId = "sdl-" + "b".repeat(64)
    val input =
        ApplicationSettings.Input.defaults().copy(
            gamepads =
                mapOf(
                    0 to ApplicationSettings.GamepadSelection.Device(stableId),
                    1 to ApplicationSettings.GamepadSelection.Auto,
                ),
            gamepadTunings =
                mapOf(
                    stableId to
                        ApplicationSettings.GamepadTuning(
                            movementDeadZone = 1234,
                            tiltDeadZone = 5678,
                            invertMovementX = true,
                            invertMovementY = false,
                            invertTiltX = true,
                            invertTiltY = true,
                        )),
        )

    val runtime = ApplicationSettings(input = input).toGamepadConfiguration()

    assertEquals(input.toPlayerMapping().gamepads, runtime.assignments())
    assertEquals(1234, runtime.tuningFor(stableId).movementDeadZone())
    assertEquals(5678, runtime.tuningFor(stableId).tiltDeadZone())
    assertTrue(runtime.tuningFor(stableId).invertMovementX())
    assertFalse(runtime.tuningFor(stableId).invertMovementY())
    assertTrue(runtime.tuningFor(stableId).invertTiltX())
    assertTrue(runtime.tuningFor(stableId).invertTiltY())
  }
}
