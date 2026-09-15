package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.memory.cart.type.SonarScene
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JMenu
import javax.swing.JMenuItem

internal fun pocketSonarMenu(
    configure: (SonarScene?, Boolean) -> Unit,
    loadImage: () -> Unit,
): JMenu = JMenu("Pocket Sonar").apply {
  fun command(label: String, action: () -> Unit) {
    add(JMenuItem(label).apply { addActionListener { action() } })
  }
  command("Simulated seabed and fish") { configure(SonarScene.demo(), true) }
  command("Open water") { configure(SonarScene.openWater(), true) }
  command("Load sonar image…", loadImage)
  addSeparator()
  command("Power on") { configure(null, true) }
  command("Power off") { configure(null, false) }
}

/** A four-shade field: white water, black echoes, dark gray floor and light gray sediment. */
internal fun loadSonarImage(path: Path): SonarScene {
  ImageIO.createImageInputStream(path.toFile()).use { input ->
    require(input != null) { "Cannot read this image." }
    val readers = ImageIO.getImageReaders(input)
    require(readers.hasNext()) { "Choose a PNG sonar image." }
    val reader = readers.next()
    try {
      reader.input = input
      require(reader.formatName.equals("png", ignoreCase = true)) { "Choose a PNG sonar image." }
      val height = reader.getHeight(0)
      require(reader.getWidth(0) == 160 && height in listOf(96, 192)) {
        "Sonar images must be 160 × 96 or 160 × 192 pixels."
      }
      val image = reader.read(0)
      val samples = ByteArray(SonarScene.WIDTH * SonarScene.HEIGHT)
      for (x in 0 until 160) {
        var floor = false
        for (y in 0 until height) {
          val rgb = image.getRGB(x, y)
          val luma = ((rgb shr 16 and 255) * 299 + (rgb shr 8 and 255) * 587 +
              (rgb and 255) * 114) / 1000
          val sample = when {
            luma < 43 -> if (floor) 0 else 1
            luma < 128 -> { floor = true; 0 }
            luma < 213 -> if (floor) 7 else 2
            else -> 7
          }
          samples[y * 160 + x] = sample.toByte()
        }
        for (y in height until 192) samples[y * 160 + x] = samples[(height - 1) * 160 + x]
      }
      return SonarScene(samples)
    } finally {
      reader.dispose()
    }
  }
}
