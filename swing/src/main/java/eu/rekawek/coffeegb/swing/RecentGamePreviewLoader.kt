package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.state.StateImage
import eu.rekawek.coffeegb.controller.state.StateStorageResolver
import eu.rekawek.coffeegb.controller.state.StateWorkspace
import eu.rekawek.coffeegb.core.memory.cart.Rom
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Immutable Home-card data; a missing image is represented by a deliberate neutral preview. */
internal data class DesktopRecentGame(
    val path: Path,
    val thumbnail: StateImage? = null,
)

/**
 * Reads recent-game previews off the EDT. A thumbnail is strictly supplemental: unreadable ROMs,
 * missing autosaves, and legacy saves without images simply retain the neutral Home-card preview.
 */
internal class RecentGamePreviewLoader(
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
          Thread(runnable, "coffee-gb-recent-game-previews").apply { isDaemon = true }
        },
    private val openRom: (Path) -> Rom = { path -> Rom(path.toFile()) },
    private val workspace: (ApplicationSettings.Saves, Rom) -> StateWorkspace =
        { saves, rom -> StateWorkspace(StateStorageResolver.resolve(saves, rom)) },
) : AutoCloseable {

  fun load(
      paths: List<Path>,
      saves: ApplicationSettings.Saves,
      onLoaded: (List<DesktopRecentGame>) -> Unit,
  ) {
    val requested = paths.toList()
    executor.execute {
      onLoaded(
          requested.map { path ->
            DesktopRecentGame(path, readThumbnail(saves, path))
          })
    }
  }

  override fun close() {
    executor.shutdownNow()
  }

  private fun readThumbnail(
      saves: ApplicationSettings.Saves,
      path: Path,
  ): StateImage? =
      try {
        workspace(saves, openRom(path)).autosaveThumbnail()
      } catch (_: Exception) {
        null
      }
}
