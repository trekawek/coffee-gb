package eu.rekawek.coffeegb.controller.properties

class SavesProperties(private val properties: EmulatorProperties) {
  val batterySavesEnabled: Boolean
    get() =
        properties.overrides.batterySavesEnabled
            ?: properties.applicationSettings.saves.batterySavesEnabled
}
