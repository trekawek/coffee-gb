package eu.rekawek.coffeegb.controller.properties

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.memory.cart.Rom
import java.nio.file.Paths
import kotlin.test.assertEquals
import org.junit.Test

class SystemPropertiesTest {

  @Test
  fun `bootstrap mode defaults to skip`() {
    val properties = EmulatorProperties()
    properties.properties.remove(EmulatorProperties.Key.BootstrapMode.propertyName)

    assertEquals(BootstrapMode.SKIP, properties.system.bootstrapMode)
  }

  @Test
  fun `stored bootstrap mode is preserved`() {
    val properties = EmulatorProperties()

    for (mode in BootstrapMode.entries) {
      properties.properties[EmulatorProperties.Key.BootstrapMode.propertyName] = mode.name
      assertEquals(mode, properties.system.bootstrapMode)
    }
  }

  @Test
  fun `controller configuration uses selected bootstrap mode`() {
    val properties = EmulatorProperties()
    val rom = Rom(Paths.get("src/test/resources/roms", "cpu_instrs.gb").toFile())

    for (mode in BootstrapMode.entries) {
      properties.properties[EmulatorProperties.Key.BootstrapMode.propertyName] = mode.name
      assertEquals(mode, Controller.createGameboyConfig(properties, rom).bootstrapMode)
    }
  }
}
