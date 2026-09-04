package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.memory.cart.Rom
import java.nio.file.Path

data class StateStoragePaths(
    val layout: StateStorageLayout,
    val screenshotsDirectory: Path,
    val fallbackLayouts: List<StateStorageLayout>,
    val replaysDirectory: Path = layout.replaysDirectory,
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
  ): StateStoragePaths =
      resolve(saves, configuration, StateIdentity.from(configuration))

  fun resolve(
      saves: ApplicationSettings.Saves,
      configuration: Gameboy.GameboyConfiguration,
      identity: MachineIdentity,
  ): StateStoragePaths {
    val romFile =
        requireNotNull(configuration.rom.file) {
          "Desktop state storage requires a file-backed ROM"
        }
    return resolve(saves, romFile.toPath(), identity.primaryRom.hex())
  }

  /**
   * Resolves the managed workspace for an unopened recent ROM. This is intentionally separate
   * from machine-state compatibility: Home only needs the hash-bound autosave preview.
   */
  fun resolve(
      saves: ApplicationSettings.Saves,
      rom: Rom,
  ): StateStoragePaths {
    val romFile =
        requireNotNull(rom.file) {
          "Desktop state storage requires a file-backed ROM"
        }
    return resolve(saves, romFile.toPath(), StateIdentity.hash(rom).hex())
  }

  private fun resolve(
      saves: ApplicationSettings.Saves,
      romPath: Path,
      romIdentity: String,
  ): StateStoragePaths {
    val defaultRoot = defaultRoot(romPath)
    val activeRoot = normalizeRoot(saves.directory ?: defaultRoot)
    val layout = StateStorageLayout(gameDirectory(activeRoot, romIdentity))
    val fallbacks =
        (listOf(defaultRoot) + saves.previousDirectories)
            .asSequence()
            .map(::normalizeRoot)
            .filter { it != activeRoot }
            .distinct()
            .take(ApplicationSettings.MAX_PREVIOUS_SAVE_DIRECTORIES)
            .map { StateStorageLayout(gameDirectory(it, romIdentity)) }
            .toList()
    return StateStoragePaths(
        layout,
        layout.gameDirectory.resolve(SCREENSHOTS_DIRECTORY).toAbsolutePath().normalize(),
        fallbacks,
        layout.gameDirectory.resolve(REPLAYS_DIRECTORY).toAbsolutePath().normalize(),
    )
  }

  internal fun defaultRoot(romPath: Path): Path =
      requireNotNull(romPath.toAbsolutePath().normalize().parent).resolve(DEFAULT_DIRECTORY)

  internal fun normalizeRoot(path: Path): Path {
    val normalized = path.toAbsolutePath().normalize()
    require(normalized.fileName != null && normalized.parent != null) {
      "Save root must have a name and parent"
    }
    return normalized
  }

  internal fun gameDirectory(root: Path, romSha256: String): Path =
      root.resolve(GAMES_DIRECTORY).resolve(romSha256).toAbsolutePath().normalize().also {
        require(it.startsWith(root)) { "Resolved game directory escapes the save root" }
      }

  const val DEFAULT_DIRECTORY = ".coffee-gb"
  const val GAMES_DIRECTORY = "games"
  const val SCREENSHOTS_DIRECTORY = "screenshots"
  const val REPLAYS_DIRECTORY = "replays"
}
