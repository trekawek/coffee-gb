package eu.rekawek.coffeegb.controller.properties

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayPropertiesTest {
  @Test
  fun `frame blending is enabled for fresh profiles`() {
    val properties = EmulatorProperties()
    properties.properties.remove(EmulatorProperties.Key.DisplayBlending.propertyName)

    assertTrue(properties.display.blending)
  }

  @Test
  fun `explicit frame blending preference is preserved`() {
    val properties = EmulatorProperties()
    properties.properties[EmulatorProperties.Key.DisplayBlending.propertyName] = "false"

    assertFalse(properties.display.blending)
  }

  @Test
  fun `CGB color correction is enabled for fresh profiles`() {
    val properties = EmulatorProperties()
    properties.properties.remove(EmulatorProperties.Key.DisplayColorCorrection.propertyName)

    assertTrue(properties.display.colorCorrection)
  }

  @Test
  fun `explicit CGB color correction preference is preserved`() {
    val properties = EmulatorProperties()
    properties.properties[EmulatorProperties.Key.DisplayColorCorrection.propertyName] = "false"

    assertFalse(properties.display.colorCorrection)
  }
}
