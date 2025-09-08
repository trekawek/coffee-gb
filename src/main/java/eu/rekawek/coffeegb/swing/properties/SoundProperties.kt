package eu.rekawek.coffeegb.swing.properties

class SoundProperties(private val properties: EmulatorProperties) {
  val soundEnabled
    get() = properties.getProperty(EmulatorProperties.Key.SoundEnabled, "true").toBoolean()
}
