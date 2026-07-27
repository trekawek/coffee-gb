package eu.rekawek.coffeegb.controller.properties

import java.nio.file.Path

class RecentRoms(private val emulatorProperties: EmulatorProperties) {
  fun getRoms(): List<String> =
      emulatorProperties.applicationSettings.general.let { general ->
        general.recentRoms.take(general.recentFileCapacity).map(Path::toString)
      }

  fun addRom(rom: String) {
    val path = Path.of(rom)
    emulatorProperties.updateSettings { current ->
      val recent = current.general.recentRoms.filterNot { it.toString() == rom }.toMutableList()
      recent.add(0, path)
      current.general.recentFileCapacity.let { maximum ->
        while (recent.size > maximum) recent.removeLast()
      }
      current.copy(general = current.general.copy(recentRoms = recent.toList()))
    }
  }
}
