package eu.rekawek.coffeegb.controller.state

import java.nio.file.Path
import java.util.Collections

/** Stable browser identity: a state reference plus its bounded active/fallback repository index. */
data class StateEntryKey(
    val ref: StateRef,
    val sourceIndex: Int = 0,
) {
  init {
    require(sourceIndex in 0..MAX_SOURCE_INDEX) { "Invalid state source index" }
  }

  companion object {
    const val MAX_SOURCE_INDEX = 4
  }
}

/**
 * One browser row. [catalogEntry] is null only for an empty stable slot. The optional thumbnail is
 * already decoded and bounded off the UI thread.
 */
data class StateBrowserEntry(
    val key: StateEntryKey,
    val catalogEntry: StateCatalogEntry?,
    val thumbnail: StateImage?,
) {
  val ref: StateRef
    get() = key.ref

  val isEmpty: Boolean
    get() = catalogEntry == null

  val canLoad: Boolean
    get() = catalogEntry?.status == StateCatalogStatus.AVAILABLE

  val disabledReason: String?
    get() =
        when {
          catalogEntry == null -> "No state is saved in this slot."
          canLoad -> null
          else -> catalogEntry.detail ?: "This state is not loadable."
        }
}

class StateBrowserCatalog(
    entries: Collection<StateBrowserEntry>,
    val namedStatesTruncated: Boolean,
    val namedStatesError: String?,
    recoveryMessages: Collection<String>,
) {
  val entries: List<StateBrowserEntry> =
      Collections.unmodifiableList(ArrayList(entries))
  val recoveryMessages: List<String> =
      Collections.unmodifiableList(ArrayList(recoveryMessages))
}

data class StateScreenshotResult(
    val path: Path,
    val pngBytes: Int,
    val recovery: ExclusiveWriteRecovery = ExclusiveWriteRecovery.NONE,
)
