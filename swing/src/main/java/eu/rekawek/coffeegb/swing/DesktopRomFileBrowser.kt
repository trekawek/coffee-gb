package eu.rekawek.coffeegb.swing

import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.PriorityQueue

/**
 * Synchronous, presentation-neutral directory listing for the desktop ROM overlay.
 *
 * A null [Listing.location] represents the filesystem-roots view. This class deliberately owns no
 * executor: callers decide where listing work runs and can discard stale results using their own
 * lifecycle generation. Constructing the browser and choosing [initialLocation] are syntactic;
 * filesystem access starts only in [list].
 */
internal class DesktopRomFileBrowser(
    private val fallbackDirectory: () -> Path = { defaultFallbackDirectory() },
    private val rootDirectories: () -> Iterable<Path> = {
      FileSystems.getDefault().rootDirectories
    },
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

  init {
    require(maxEntries > 0) { "maxEntries must be positive" }
  }

  internal enum class EntryKind {
    PARENT,
    DIRECTORY,
    ROM,
  }

  /** [path] is the exact selection target; it is null only for `..` at a filesystem root. */
  internal data class Entry(
      val kind: EntryKind,
      val label: String,
      val path: Path?,
  ) {
    init {
      require(kind == EntryKind.PARENT || path != null) {
        "Only a parent entry may target the filesystem-roots view"
      }
    }
  }

  internal data class Listing(
      /** Null denotes the synthetic filesystem-roots view. */
      val location: Path?,
      val entries: List<Entry>,
      /** Human-readable failure text. Parent navigation remains available when possible. */
      val errorMessage: String? = null,
      /** True when supported children exceeded [maxEntries]. */
      val truncated: Boolean = false,
  )

  /**
   * Chooses an absolute, normalized starting point without checking whether it exists or is
   * readable. A stale configured/network directory can therefore be handled by [list] off the EDT.
   */
  internal fun initialLocation(preferredDirectory: Path?): Path =
      (preferredDirectory ?: fallbackDirectory()).toAbsolutePath().normalize()

  /**
   * Lists [location], or the filesystem roots when it is null. Directory traversal, attributes,
   * and root discovery all happen inside this call and should therefore run on a host-owned worker.
   */
  internal fun list(location: Path?): Listing =
      if (location == null) listRoots() else listDirectory(location.toAbsolutePath().normalize())

  private fun listRoots(): Listing {
    val bounded = BoundedEntries(maxEntries)
    return try {
      rootDirectories().forEach { root ->
        val path = root.toAbsolutePath().normalize()
        bounded.add(Entry(EntryKind.DIRECTORY, displayName(path), path))
      }
      Listing(null, bounded.sorted(), truncated = bounded.truncated)
    } catch (failure: IOException) {
      Listing(null, emptyList(), readableError("filesystem roots", failure))
    } catch (failure: DirectoryIteratorException) {
      Listing(null, emptyList(), readableError("filesystem roots", failure.cause ?: failure))
    } catch (failure: SecurityException) {
      Listing(null, emptyList(), readableError("filesystem roots", failure))
    }
  }

  private fun listDirectory(directory: Path): Listing {
    val parent = Entry(EntryKind.PARENT, "..", directory.parent)
    val bounded = BoundedEntries(maxEntries)
    return try {
      Files.newDirectoryStream(directory).use { stream ->
        for (path in stream) {
          classify(path)?.let(bounded::add)
        }
      }
      Listing(
          directory,
          listOf(parent) + bounded.sorted(),
          truncated = bounded.truncated,
      )
    } catch (failure: IOException) {
      Listing(directory, listOf(parent), readableError(displayName(directory), failure))
    } catch (failure: DirectoryIteratorException) {
      Listing(
          directory,
          listOf(parent),
          readableError(displayName(directory), failure.cause ?: failure),
      )
    } catch (failure: SecurityException) {
      Listing(directory, listOf(parent), readableError(displayName(directory), failure))
    }
  }

  private fun classify(path: Path): Entry? =
      try {
        when {
          Files.isDirectory(path) -> Entry(EntryKind.DIRECTORY, displayName(path), path)
          Files.isRegularFile(path) && supportedRomName(displayName(path)) ->
              Entry(EntryKind.ROM, displayName(path), path)
          else -> null
        }
      } catch (_: SecurityException) {
        // One inaccessible child must not make the containing directory unusable.
        null
      }

  private fun readableError(location: String, failure: Throwable): String {
    val detail = failure.message?.trim()?.takeIf(String::isNotEmpty)
    return if (detail == null) {
      "Unable to read $location."
    } else {
      "Unable to read $location: $detail"
    }
  }

  private class BoundedEntries(private val maximum: Int) {
    private val entries = PriorityQueue<Entry>(maximum, ENTRY_ORDER.reversed())
    private var accepted = 0

    val truncated: Boolean
      get() = accepted > maximum

    fun add(entry: Entry) {
      accepted++
      if (entries.size < maximum) {
        entries.add(entry)
      } else if (ENTRY_ORDER.compare(entry, entries.peek()) < 0) {
        entries.remove()
        entries.add(entry)
      }
    }

    fun sorted(): List<Entry> = entries.sortedWith(ENTRY_ORDER)
  }

  internal companion object {
    const val DEFAULT_MAX_ENTRIES = 4_096

    private val SUPPORTED_EXTENSIONS = setOf("gb", "gbc", "rom", "zip", "7z")

    private val ENTRY_ORDER =
        compareBy<Entry>(
            { if (it.kind == EntryKind.DIRECTORY) 0 else 1 },
            { it.label.lowercase(Locale.ROOT) },
            { it.label },
            { it.path.toString() },
        )

    private fun defaultFallbackDirectory(): Path {
      val home = System.getProperty("user.home")?.takeIf(String::isNotBlank) ?: "."
      return Path.of(home)
    }

    private fun displayName(path: Path): String =
        path.fileName?.toString()?.takeIf(String::isNotEmpty) ?: path.toString()

    private fun supportedRomName(name: String): Boolean {
      val separator = name.lastIndexOf('.')
      if (separator < 0 || separator == name.lastIndex) return false
      return name.substring(separator + 1).lowercase(Locale.ROOT) in SUPPORTED_EXTENSIONS
    }
  }
}
