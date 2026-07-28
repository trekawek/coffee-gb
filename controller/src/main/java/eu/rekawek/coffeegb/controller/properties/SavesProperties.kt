package eu.rekawek.coffeegb.controller.properties

import java.nio.file.Path

class SavesProperties(private val properties: EmulatorProperties) {
  val directory: Path?
    get() = properties.applicationSettings.saves.directory

  val previousDirectories: List<Path>
    get() = properties.applicationSettings.saves.previousDirectories

  val batterySavesEnabled: Boolean
    get() =
        properties.overrides.batterySavesEnabled
            ?: properties.applicationSettings.saves.batterySavesEnabled

  val rewindEnabled: Boolean
    get() = properties.applicationSettings.saves.rewindEnabled

  val rewindSeconds: Int
    get() = properties.applicationSettings.saves.rewindSeconds

  val autosavePolicy: ApplicationSettings.AutosavePolicy
    get() = properties.applicationSettings.saves.autosavePolicy

  val resumePolicy: ApplicationSettings.ResumePolicy
    get() = properties.applicationSettings.saves.resumePolicy
}
