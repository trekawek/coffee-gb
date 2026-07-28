package eu.rekawek.coffeegb.controller.properties

import java.nio.file.Path

class RecentRoms(private val emulatorProperties: EmulatorProperties) {
  fun getRoms(): List<String> =
      getPaths().map(Path::toString)

  fun getPaths(): List<Path> =
      emulatorProperties.applicationSettings.general.let { general ->
        general.recentRoms
            .asSequence()
            .map(::normalize)
            .distinct()
            .take(general.recentFileCapacity)
            .toList()
      }

  /** Records only a ROM whose controller start event has committed successfully. */
  fun recordSuccessfulOpen(path: Path) {
    val normalized = normalize(path)
    emulatorProperties.updateSettings { current ->
      val recent =
          current.general.recentRoms
              .asSequence()
              .map(::normalize)
              .filterNot { it == normalized }
              .distinct()
              .toMutableList()
      recent.add(0, normalized)
      current.general.recentFileCapacity.let { maximum ->
        while (recent.size > maximum) recent.removeLast()
      }
      current.copy(general = current.general.copy(recentRoms = recent.toList()))
    }
  }

  fun remove(path: Path) {
    val normalized = normalize(path)
    emulatorProperties.updateSettings { current ->
      val recent =
          current.general.recentRoms
              .asSequence()
              .map(::normalize)
              .filterNot { it == normalized }
              .distinct()
              .take(current.general.recentFileCapacity)
              .toList()
      current.copy(general = current.general.copy(recentRoms = recent))
    }
  }

  /** Compatibility wrapper retained for existing menu and Java-adapter call sites. */
  fun addRom(rom: String) = recordSuccessfulOpen(Path.of(rom))

  private fun normalize(path: Path): Path = path.toAbsolutePath().normalize()
}
