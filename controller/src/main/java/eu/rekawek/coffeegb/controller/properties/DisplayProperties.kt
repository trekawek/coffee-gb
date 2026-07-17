package eu.rekawek.coffeegb.controller.properties

class DisplayProperties(private val properties: EmulatorProperties) {
  val scale
    get() = properties.getProperty(EmulatorProperties.Key.DisplayScale, "2").toInt()

  val grayscale
    get() = properties.getProperty(EmulatorProperties.Key.DisplayGrayscale, "false").toBoolean()

  val showSgbBorder
    get() = properties.getProperty(EmulatorProperties.Key.ShowSgbBorder, "false").toBoolean()

  val blending
    get() = properties.getProperty(EmulatorProperties.Key.DisplayBlending, "true").toBoolean()

  val colorCorrection
    get() =
        properties.getProperty(EmulatorProperties.Key.DisplayColorCorrection, "true").toBoolean()

  val rotation
    get() = properties.getProperty(EmulatorProperties.Key.DisplayRotation, "0").toInt()
}
