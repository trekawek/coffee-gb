package eu.rekawek.coffeegb.controller.properties

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.controller.state.StateIdentity
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.joypad.Button
import eu.rekawek.coffeegb.core.memory.cart.Rom
import java.awt.event.KeyEvent
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class ControllerPropertiesTest {

  @Test
  fun defaultsPreserveLegacyPrimaryKeyboardAndOneAutomaticGamepad() {
    val mapping = ControllerProperties.getPlayerMapping(Properties())

    assertEquals(8, mapping.keyboard.size)
    assertEquals(
        ControllerProperties.PlayerButton(0, Button.A),
        mapping.keyboard[KeyEvent.VK_Z],
    )
    assertEquals(
        listOf(ControllerProperties.GamepadAssignment(0, "auto")),
        mapping.gamepads,
    )
    assertEquals(Button.B, mapping.legacyPrimaryKeyboard()[KeyEvent.VK_X])
  }

  @Test
  fun legacyOverridesAndDisjointP2ThroughP4GrammarAreAccepted() {
    val id = "sdl-" + "a".repeat(64)
    val properties =
        Properties().apply {
          setProperty("btn_a", "VK_Q")
          setProperty("input.p2.btn_a", "VK_W")
          setProperty("input.p3.btn_start", "VK_E")
          setProperty("input.p4.btn_select", "VK_R")
          setProperty("input.p1.gamepad", "none")
          setProperty("input.p4.gamepad", id)
        }

    val mapping = ControllerProperties.getPlayerMapping(properties)
    assertEquals(ControllerProperties.PlayerButton(0, Button.A), mapping.keyboard[KeyEvent.VK_Q])
    assertEquals(ControllerProperties.PlayerButton(1, Button.A), mapping.keyboard[KeyEvent.VK_W])
    assertEquals(ControllerProperties.PlayerButton(2, Button.START), mapping.keyboard[KeyEvent.VK_E])
    assertEquals(ControllerProperties.PlayerButton(3, Button.SELECT), mapping.keyboard[KeyEvent.VK_R])
    assertEquals(listOf(ControllerProperties.GamepadAssignment(3, id)), mapping.gamepads)
  }

  @Test
  fun malformedAndCollidingAssignmentsAreRejectedDeterministically() {
    val id = "sdl-" + "b".repeat(64)
    val cases =
        listOf(
            "input.p0.btn_a" to "VK_Q",
            "input.p5.btn_a" to "VK_Q",
            "input.p2.btn_fire" to "VK_Q",
            "input.p2.btn_a" to "NOT_A_KEY",
            "input.p2.unknown" to "VK_Q",
            "input.p2.gamepad" to "controller-zero",
        )
    cases.forEach { (key, value) ->
      assertFailsWith<IllegalArgumentException>(key) {
        ControllerProperties.getPlayerMapping(Properties().apply { setProperty(key, value) })
      }
    }

    assertFailsWith<IllegalArgumentException>("duplicate key") {
      ControllerProperties.getPlayerMapping(
          Properties().apply { setProperty("input.p2.btn_a", "VK_Z") })
    }
    assertFailsWith<IllegalArgumentException>("legacy/new duplicate") {
      ControllerProperties.getPlayerMapping(
          Properties().apply {
            setProperty("btn_a", "VK_Q")
            setProperty("input.p1.btn_a", "VK_W")
          })
    }
    assertFailsWith<IllegalArgumentException>("duplicate gamepad") {
      ControllerProperties.getPlayerMapping(
          Properties().apply {
            setProperty("input.p1.gamepad", id)
            setProperty("input.p2.gamepad", id)
          })
    }
    assertFailsWith<IllegalArgumentException>("duplicate auto") {
      ControllerProperties.getPlayerMapping(
          Properties().apply { setProperty("input.p2.gamepad", "auto") })
    }
  }

  @Test
  fun controllerConfigurationsShareTheExactLiveInputService() {
    val properties = EmulatorProperties()
    val rom = Rom(ByteArray(0x8000))
    val config = Controller.createGameboyConfig(properties, rom)

    assertSame(properties.playerInputSource, config.playerInputSource)
    assertSame(config.playerInputSource, config.forRestore().playerInputSource)
  }

  @Test
  fun sgbBorderPreferenceCannotEnterIncompatibleProfileMetadata() {
    val cgbRom = Rom(StateCodecTestSupport.rom(cgb = true))
    val sgbRom = Rom(StateCodecTestSupport.rom(sgb = true))

    listOf(HardwareProfileRegistry.CGB, HardwareProfileRegistry.CGB0).forEach { profile ->
      val properties = EmulatorProperties(profile)
      properties.properties[EmulatorProperties.Key.ShowSgbBorder.propertyName] = "true"
      val config = Controller.createGameboyConfig(properties, cgbRom)

      assertFalse(config.isDisplaySgbBorder, profile.id())
      assertFalse(StateIdentity.from(config).profile.displaySgbBorder, profile.id())
    }

    listOf(
            HardwareProfileRegistry.DMG,
            HardwareProfileRegistry.MGB,
        )
        .forEach { profile ->
          val config =
              Gameboy.GameboyConfiguration(Rom(StateCodecTestSupport.rom()))
                  .setHardwareProfile(profile)
                  .setDisplaySgbBorder(true)
          assertFalse(config.isDisplaySgbBorder, profile.id())
          assertFalse(StateIdentity.from(config).profile.displaySgbBorder, profile.id())
        }

    listOf(HardwareProfileRegistry.SGB, HardwareProfileRegistry.SGB2).forEach { profile ->
      val config =
          Gameboy.GameboyConfiguration(sgbRom)
              .setHardwareProfile(profile)
              .setDisplaySgbBorder(true)
      assertTrue(config.isDisplaySgbBorder, profile.id())
      assertTrue(StateIdentity.from(config).profile.displaySgbBorder, profile.id())

      config.setHardwareProfile(HardwareProfileRegistry.CGB)
      assertFalse(config.isDisplaySgbBorder, "transition from ${profile.id()} to cgb")
      assertFalse(StateIdentity.from(config).profile.displaySgbBorder)
    }
  }
}
