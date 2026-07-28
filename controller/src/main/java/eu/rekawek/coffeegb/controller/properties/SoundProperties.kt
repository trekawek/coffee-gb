package eu.rekawek.coffeegb.controller.properties

class SoundProperties(private val properties: EmulatorProperties) {
  val soundEnabled
    get() = properties.applicationSettings.audio.enabled

  val output
    get() = properties.applicationSettings.audio.output

  val volume
    get() = properties.applicationSettings.audio.volume

  val latency
    get() = properties.applicationSettings.audio.latency
}
