package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.state.StateImage
import eu.rekawek.coffeegb.controller.state.StateStorageResolver
import eu.rekawek.coffeegb.controller.state.StateWorkspace
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.RomOrigin
import eu.rekawek.coffeegb.core.memory.cart.RomSourceSnapshot
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Immutable Home-card data; a missing image is represented by a deliberate neutral preview. */
internal data class DesktopRecentGame(
    val path: Path,
    val thumbnail: StateImage? = null,
    val lastPlayed: Instant? = null,
    val title: String = recentGameFallbackTitle(path),
    val origin: RomOrigin? = null,
    val active: Boolean = false,
) {
  init {
    require(title.isNotBlank())
  }
}

/**
 * Reads recent-game previews off the EDT. A thumbnail is strictly supplemental: unreadable ROMs,
 * missing autosaves, and legacy saves without images simply retain the neutral Home-card preview.
 */
internal class RecentGamePreviewLoader(
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
          Thread(runnable, "coffee-gb-recent-game-previews").apply { isDaemon = true }
        },
    private val openRom: (Path, RomOrigin?) -> Rom = ::openExactRecentRom,
    private val workspace: (ApplicationSettings.Saves, Rom) -> StateWorkspace =
        { saves, rom -> StateWorkspace(StateStorageResolver.resolve(saves, rom)) },
) : AutoCloseable {

  fun load(
      games: List<DesktopRecentGame>,
      saves: ApplicationSettings.Saves,
      onLoaded: (List<DesktopRecentGame>) -> Unit,
  ) {
    val requested = games.toList()
    executor.execute {
      onLoaded(
          requested.map { game ->
            readRecentGame(saves, game)
          })
    }
  }

  override fun close() {
    executor.shutdownNow()
  }

  private fun readRecentGame(
      saves: ApplicationSettings.Saves,
      game: DesktopRecentGame,
  ): DesktopRecentGame =
      try {
        val rom = openRom(game.path, game.origin)
        val workspace = workspace(saves, rom)
        game.copy(
            thumbnail = workspace.autosaveThumbnail(),
            lastPlayed = game.lastPlayed ?: workspace.autosaveSavedAt(),
            title =
                if (game.origin == null) {
                  rom.title.trim().ifBlank { game.title }
                } else {
                  game.title
                },
            origin = game.origin ?: rom.origin,
        )
      } catch (_: Exception) {
        game
      }
}

/** Loads only the exact persisted archive occurrence; a mismatch must never preview another ROM. */
internal fun openExactRecentRom(path: Path, origin: RomOrigin?): Rom =
    RomSourceSnapshot.open(path).use { snapshot ->
      val image =
          if (origin?.kind() == RomOrigin.Kind.ARCHIVE_ENTRY) {
            val candidate = exactRecentArchiveCandidate(snapshot, path, origin)
                ?: throw IOException("The saved recent archive entry is no longer present")
            snapshot.load(candidate.token())
          } else if (snapshot.isArchive) {
            throw IOException("An exact recent archive entry has not been recorded")
          } else {
            snapshot.loadSingle()
          }
      Rom(image)
    }

internal fun recentGameFallbackTitle(path: Path): String =
    path.fileName?.toString()?.trim()?.ifBlank { null }
        ?: path.toAbsolutePath().normalize().toString()

/** Combines persisted metadata with the authoritative current session identity. */
internal fun recentGameSeed(
    path: Path,
    metadata: DesktopRecentGameMetadata?,
    activeOrigin: RomOrigin?,
    activeTitle: String?,
): DesktopRecentGame {
  val normalized = normalizedRecentPath(path)
  val currentOrigin =
      activeOrigin?.takeIf { it.containerPath().orElse(null) == normalized }
  return DesktopRecentGame(
      path = normalized,
      lastPlayed = metadata?.playedAt,
      title =
          currentOrigin?.let { activeTitle?.trim()?.ifBlank { null } }
              ?: metadata?.title
              ?: recentGameFallbackTitle(normalized),
      origin = currentOrigin ?: metadata?.origin,
      active = currentOrigin != null,
  )
}

/** Concise identity used only when two recent ROM titles would otherwise be indistinguishable. */
internal fun recentGameQualifier(game: DesktopRecentGame): String {
  val origin = game.origin
  if (origin?.kind() == RomOrigin.Kind.ARCHIVE_ENTRY) {
    val parts =
        origin.archiveEntry().orElse(origin.displayName()).replace('\\', '/').split('/')
            .filter(String::isNotBlank)
    val entry = parts.takeLast(2).joinToString("/").ifBlank { origin.displayName() }
    val occurrence = origin.archiveEntryOccurrence()
    return if (occurrence == 0) entry else "$entry #${occurrence + 1}"
  }
  val normalized = normalizedRecentPath(game.path)
  val fileName = normalized.fileName?.toString() ?: normalized.toString()
  val parent = normalized.parent?.fileName?.toString()?.takeIf(String::isNotBlank)
  return if (parent == null) fileName else "$parent/$fileName"
}
