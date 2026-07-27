package eu.rekawek.coffeegb.controller.properties

class SoundProperties(private val properties: EmulatorProperties) {
  val soundEnabled
    get() = properties.applicationSettings.audio.enabled
}
