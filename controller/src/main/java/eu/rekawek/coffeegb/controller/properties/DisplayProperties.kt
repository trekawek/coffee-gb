package eu.rekawek.coffeegb.controller.properties

class DisplayProperties(private val properties: EmulatorProperties) {
  val scale
    get() = properties.getProperty(EmulatorProperties.Key.DisplayScale, "2").toInt()

  val grayscale
    get() = properties.getProperty(EmulatorProperties.Key.DisplayGrayscale, "false").toBoolean()

  val showSgbBorder
    get() = properties.getProperty(EmulatorProperties.Key.ShowSgbBorder, "false").toBoolean()

  val blending
    get() = properties.getProperty(EmulatorProperties.Key.DisplayBlending, "false").toBoolean()

  val colorCorrection
    get() =
        properties.getProperty(EmulatorProperties.Key.DisplayColorCorrection, "false").toBoolean()
}
