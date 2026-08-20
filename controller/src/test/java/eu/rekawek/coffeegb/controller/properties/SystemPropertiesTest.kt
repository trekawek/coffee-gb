package eu.rekawek.coffeegb.controller.properties

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.core.ExecutionMode
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class SystemPropertiesTest {

  @Test
  fun `bootstrap mode defaults to skip`() {
    val properties = testEmulatorProperties()
    properties.properties.remove(EmulatorProperties.Key.BootstrapMode.propertyName)

    assertEquals(BootstrapMode.SKIP, properties.system.bootstrapMode)
  }

  @Test
  fun `stored bootstrap mode is preserved`() {
    val properties = testEmulatorProperties()

    for (mode in BootstrapMode.entries) {
      properties.properties[EmulatorProperties.Key.BootstrapMode.propertyName] = mode.name
      assertEquals(mode, properties.system.bootstrapMode)
    }
  }

  @Test
  fun `execution mode defaults, persists, and transiently overrides session configuration`() {
    val properties = testEmulatorProperties()
    assertEquals(ExecutionMode.ACCURACY, properties.system.executionMode)

    properties.updateApplicationSettings { settings ->
      settings.copy(
          advanced = settings.advanced.copy(executionMode = ExecutionMode.PERFORMANCE))
    }
    assertEquals(ExecutionMode.PERFORMANCE, properties.system.executionMode)

    val rom = Rom(Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile())
    assertEquals(
        ExecutionMode.PERFORMANCE,
        Controller.createGameboyConfig(properties, rom).executionMode,
    )

    val overridden = testEmulatorProperties(executionMode = ExecutionMode.ACCURACY)
    overridden.updateApplicationSettings { settings ->
      settings.copy(
          advanced = settings.advanced.copy(executionMode = ExecutionMode.PERFORMANCE))
    }
    assertEquals(ExecutionMode.ACCURACY, overridden.system.executionMode)
    assertEquals(
        ExecutionMode.ACCURACY,
        Controller.createGameboyConfig(overridden, rom).executionMode,
    )
  }

  @Test
  fun `controller configuration uses selected bootstrap mode`() {
    val properties = testEmulatorProperties()
    val rom = Rom(Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile())

    for (mode in BootstrapMode.entries) {
      properties.properties[EmulatorProperties.Key.BootstrapMode.propertyName] = mode.name
      assertEquals(mode, Controller.createGameboyConfig(properties, rom).bootstrapMode)
    }
  }

  @Test
  fun `profile settings default to registry ids and migrate finite legacy values`() {
    val properties = testEmulatorProperties()
    properties.properties.remove(EmulatorProperties.Key.DmgGamesType.propertyName)
    properties.properties.remove(EmulatorProperties.Key.CgbGamesType.propertyName)
    assertEquals(HardwareProfileRegistry.SGB, properties.system.dmgGamesProfile)
    assertEquals(HardwareProfileRegistry.CGB, properties.system.cgbGamesProfile)

    properties.properties[EmulatorProperties.Key.DmgGamesType.propertyName] = "DMG"
    properties.properties[EmulatorProperties.Key.CgbGamesType.propertyName] = "CGB0"
    assertEquals(HardwareProfileRegistry.DMG, properties.system.dmgGamesProfile)
    assertEquals(HardwareProfileRegistry.CGB0, properties.system.cgbGamesProfile)

    properties.properties[EmulatorProperties.Key.DmgGamesType.propertyName] = "dmg"
    assertEquals(HardwareProfileRegistry.DMG, properties.system.dmgGamesProfile)
    properties.properties[EmulatorProperties.Key.DmgGamesType.propertyName] = "sgb2"
    assertEquals(HardwareProfileRegistry.SGB2, properties.system.dmgGamesProfile)
    properties.properties[EmulatorProperties.Key.DmgGamesType.propertyName] = "SGB"
    assertEquals(HardwareProfileRegistry.SGB, properties.system.dmgGamesProfile)
    properties.properties[EmulatorProperties.Key.DmgGamesType.propertyName] = "mgb"
    assertEquals(HardwareProfileRegistry.MGB, properties.system.dmgGamesProfile)
    properties.properties[EmulatorProperties.Key.DmgGamesType.propertyName] = "MGB"
    assertFailsWith<IllegalArgumentException> { properties.system.dmgGamesProfile }
  }

  @Test
  fun `unknown persisted profile fails actionably before configuration construction`() {
    val properties = testEmulatorProperties()
    properties.properties[EmulatorProperties.Key.DmgGamesType.propertyName] = "ordinal-0"
    properties.properties[EmulatorProperties.Key.CgbGamesType.propertyName] = "ordinal-0"

    val rom = Rom(Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile())
    val failure =
        assertFailsWith<IllegalArgumentException> {
          Controller.createGameboyConfig(properties, rom)
        }
    assertTrue(failure.message!!.contains("[dmg, cgb, cgb0, sgb, sgb2, mgb]"))
  }

  @Test
  fun `explicit profile override reaches constructed configuration`() {
    val properties = testEmulatorProperties(HardwareProfileRegistry.DMG)
    val rom = Rom(Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile())

    assertEquals(
        HardwareProfileRegistry.DMG,
        Controller.createGameboyConfig(properties, rom).hardwareProfile,
    )
  }

  @Test
  fun `desktop auto selection is represented by an absent mapping property`() {
    val properties = testEmulatorProperties()
    val key = EmulatorProperties.Key.DmgGamesType
    properties.properties.remove(key.propertyName)
    assertTrue(!properties.hasProperty(key))
    assertEquals(HardwareProfileRegistry.SGB, properties.system.dmgGamesProfile)

    properties.setProperty(key, HardwareProfileRegistry.SGB2.id())
    assertTrue(properties.hasProperty(key))
    assertEquals(HardwareProfileRegistry.SGB2, properties.system.dmgGamesProfile)
    properties.clearProperty(key)
    assertTrue(!properties.hasProperty(key))
    assertEquals(HardwareProfileRegistry.SGB, properties.system.dmgGamesProfile)

    properties.setProperty(key, HardwareProfileRegistry.MGB.id())
    assertEquals(HardwareProfileRegistry.MGB, properties.system.dmgGamesProfile)
  }
}
