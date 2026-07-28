package eu.rekawek.coffeegb.controller.properties

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayPropertiesTest {
  @Test
  fun `display facade exposes typed scaling letterbox and fullscreen settings`() {
    val properties = testEmulatorProperties()
    properties.updateApplicationSettings { current ->
      current.copy(
          display =
              current.display.copy(
                  scalingMode = ApplicationSettings.DisplayScalingMode.INTEGER_FIT,
                  explicitScale = 3,
                  letterboxColor = 0x203040,
                  fullscreen = true,
              ))
    }

    assertEquals(ApplicationSettings.DisplayScalingMode.INTEGER_FIT, properties.display.scalingMode)
    assertEquals(3, properties.display.explicitScale)
    assertEquals(3, properties.display.scale)
    assertEquals(0x203040, properties.display.letterboxColor)
    assertTrue(properties.display.fullscreen)
  }

  @Test
  fun `frame blending is enabled for fresh profiles`() {
    val properties = testEmulatorProperties()
    properties.properties.remove(EmulatorProperties.Key.DisplayBlending.propertyName)

    assertTrue(properties.display.blending)
  }

  @Test
  fun `explicit frame blending preference is preserved`() {
    val properties = testEmulatorProperties()
    properties.properties[EmulatorProperties.Key.DisplayBlending.propertyName] = "false"

    assertFalse(properties.display.blending)
  }

  @Test
  fun `CGB color correction is enabled for fresh profiles`() {
    val properties = testEmulatorProperties()
    properties.properties.remove(EmulatorProperties.Key.DisplayColorCorrection.propertyName)

    assertTrue(properties.display.colorCorrection)
  }

  @Test
  fun `explicit CGB color correction preference is preserved`() {
    val properties = testEmulatorProperties()
    properties.properties[EmulatorProperties.Key.DisplayColorCorrection.propertyName] = "false"

    assertFalse(properties.display.colorCorrection)
  }
}
