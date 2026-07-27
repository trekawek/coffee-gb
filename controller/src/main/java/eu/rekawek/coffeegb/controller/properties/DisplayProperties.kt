package eu.rekawek.coffeegb.controller.properties

class DisplayProperties(private val properties: EmulatorProperties) {
  val scale
    get() = properties.applicationSettings.display.scale

  val grayscale
    get() = properties.applicationSettings.display.grayscale

  val showSgbBorder
    get() = properties.applicationSettings.display.showSgbBorder

  val blending
    get() = properties.applicationSettings.display.blending

  val colorCorrection
    get() = properties.applicationSettings.display.colorCorrection

  val rotation
    get() = properties.applicationSettings.display.rotation.degrees
}
