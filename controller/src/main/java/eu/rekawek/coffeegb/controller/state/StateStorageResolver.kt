package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.core.Gameboy
import java.nio.file.Path

data class StateStoragePaths(
    val layout: StateStorageLayout,
    val screenshotsDirectory: Path,
    val fallbackLayouts: List<StateStorageLayout>,
)

/**
 * Maps one exact ROM identity to a path without incorporating the untrusted ROM title or filename.
 *
 * With default settings, Phase-4 assets live below `<ROM directory>/.coffee-gb/games/<ROM SHA-256>`.
 * A configured save root uses `<configured root>/games/<ROM SHA-256>`. Previous configured roots
 * remain bounded read fallbacks so changing Preferences never strands state files.
 */
object StateStorageResolver {
  fun resolve(
      saves: ApplicationSettings.Saves,
      configuration: Gameboy.GameboyConfiguration,
  ): StateStoragePaths {
    val romFile =
        requireNotNull(configuration.rom.file) {
          "Desktop state storage requires a file-backed ROM"
        }
    val identity = StateIdentity.from(configuration).primaryRom.hex()
    val defaultRoot =
        requireNotNull(romFile.toPath().toAbsolutePath().normalize().parent)
            .resolve(DEFAULT_DIRECTORY)
    val activeRoot = normalizeRoot(saves.directory ?: defaultRoot)
    val layout = StateStorageLayout(gameDirectory(activeRoot, identity))
    val fallbacks =
        (listOf(defaultRoot) + saves.previousDirectories)
            .asSequence()
            .map(::normalizeRoot)
            .filter { it != activeRoot }
            .distinct()
            .take(ApplicationSettings.MAX_PREVIOUS_SAVE_DIRECTORIES)
            .map { StateStorageLayout(gameDirectory(it, identity)) }
            .toList()
    return StateStoragePaths(
        layout,
        layout.gameDirectory.resolve(SCREENSHOTS_DIRECTORY).toAbsolutePath().normalize(),
        fallbacks,
    )
  }

  private fun normalizeRoot(path: Path): Path {
    val normalized = path.toAbsolutePath().normalize()
    require(normalized.fileName != null && normalized.parent != null) {
      "Save root must have a name and parent"
    }
    return normalized
  }

  private fun gameDirectory(root: Path, romSha256: String): Path =
      root.resolve(GAMES_DIRECTORY).resolve(romSha256).toAbsolutePath().normalize().also {
        require(it.startsWith(root)) { "Resolved game directory escapes the save root" }
      }

  const val DEFAULT_DIRECTORY = ".coffee-gb"
  const val GAMES_DIRECTORY = "games"
  const val SCREENSHOTS_DIRECTORY = "screenshots"
}
