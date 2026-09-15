package eu.rekawek.coffeegb.swing

import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.*
import org.junit.Test

class PocketSonarMenuTest {
  @Test fun imageUsesFloorMarkerAndExtendsLastSampleForZoom() {
    val path = Files.createTempFile("sonar", ".png")
    try {
      val image = BufferedImage(160, 96, BufferedImage.TYPE_INT_RGB)
      for (x in 0 until 160) for (y in 0 until 96) image.setRGB(x, y,
          when { y < 32 -> 0xffffff; y == 32 -> 0; y < 64 -> 0xffffff;
            y == 64 -> 0x555555; else -> 0xaaaaaa })
      ImageIO.write(image, "png", path.toFile())
      val samples = loadSonarImage(path).copySamples()
      assertEquals(7, samples[0].toInt())
      assertEquals(1, samples[32 * 160].toInt())
      assertEquals(0, samples[64 * 160].toInt())
      assertEquals(7, samples[191 * 160].toInt())
      ImageIO.write(BufferedImage(161, 96, BufferedImage.TYPE_INT_RGB), "png", path.toFile())
      assertFailsWith<IllegalArgumentException> { loadSonarImage(path) }
    } finally { Files.deleteIfExists(path) }
  }

  @Test fun menuPowerActionKeepsTheLoadedScene() {
    SwingUtilities.invokeAndWait {
      var powered = true
      val menu = pocketSonarMenu({ scene, power -> assertNull(scene); powered = power }, {})
      menu.getItem(5).doClick()
      assertFalse(powered)
    }
  }
}
