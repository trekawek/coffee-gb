package eu.rekawek.coffeegb.swing.gui.properties

class DisplayProperties(private val properties: EmulatorProperties) {
  val scale
    get() = properties.getProperty(EmulatorProperties.Key.DisplayScale, "2").toInt()

  val grayscale
    get() = properties.getProperty(EmulatorProperties.Key.DisplayGrayscale, "false").toBoolean()
}
