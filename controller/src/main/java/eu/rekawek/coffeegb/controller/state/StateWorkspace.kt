package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import java.io.IOException
import java.nio.file.Path
import java.time.Instant

/**
 * Active state repository plus a bounded set of previous-directory read fallbacks.
 *
 * Writes always target source zero. A browser key remembers the exact source so deleting or
 * exporting a fallback entry cannot accidentally target a same-named active entry.
 */
class StateWorkspace(
    val paths: StateStoragePaths,
    repositoryFactory: (StateStorageLayout) -> StateRepository = ::StateRepository,
) {
  private val repositories =
      (listOf(paths.layout) + paths.fallbackLayouts)
          .take(StateEntryKey.MAX_SOURCE_INDEX + 1)
          .map(repositoryFactory)

  fun catalog(targetIdentity: MachineIdentity): StateBrowserCatalog {
    val catalogs = repositories.map { repository -> repository.catalog(targetIdentity) }
    val bySource =
        catalogs.map { catalog -> catalog.entries.associateBy(StateCatalogEntry::ref) }
    val rows = ArrayList<StateBrowserEntry>()
    val recovery = LinkedHashSet<String>()

    for (slot in StateRef.MIN_SLOT..StateRef.MAX_SLOT) {
      val ref = StateRef.Slot(slot)
      val located = firstLocated(bySource, ref)
      rows +=
          if (located == null) {
            StateBrowserEntry(StateEntryKey(ref), null, null)
          } else {
            browserEntry(located.first, located.second, recovery)
          }
    }

    val seenNamed = HashSet<StateRef.Named>()
    var namedCount = 0
    for (source in catalogs.indices) {
      for (entry in catalogs[source].entries) {
        val ref = entry.ref as? StateRef.Named ?: continue
        if (!seenNamed.add(ref)) continue
        if (namedCount == StateRepository.MAX_NAMED_STATES) break
        rows += browserEntry(source, entry, recovery)
        namedCount++
      }
      if (namedCount == StateRepository.MAX_NAMED_STATES) break
    }

    val autosave = firstLocated(bySource, StateRef.Autosave)
    autosave?.let { rows += browserEntry(it.first, it.second, recovery) }

    val errors =
        catalogs.mapIndexedNotNull { source, catalog ->
          catalog.namedStatesError?.let {
            "Source ${source + 1}: ${boundedMessage(it)}"
          }
        }
    return StateBrowserCatalog(
        rows,
        namedStatesTruncated =
            namedCount == StateRepository.MAX_NAMED_STATES ||
                catalogs.any(StateCatalog::namedStatesTruncated),
        namedStatesError = errors.takeIf(List<String>::isNotEmpty)?.joinToString("; "),
        recoveryMessages = recovery,
    )
  }

  fun save(
      ref: StateRef,
      encodedState: ByteArray,
      metadata: StateSaveMetadata,
      thumbnailPng: ByteArray?,
  ): StateAssetSaveResult =
      repositories.first().saveWithThumbnail(ref, encodedState, metadata, thumbnailPng)

  fun read(key: StateEntryKey): StateReadResult =
      repository(key).read(key.ref)

  /**
   * Reads the first active/fallback copy of [ref]. A corrupt, incompatible, or unreadable earlier
   * source is deliberately authoritative and fails instead of falling through to an older copy.
   */
  internal fun readFirst(ref: StateRef): Pair<StateEntryKey, StateReadResult>? {
    repositories.forEachIndexed { source, repository ->
      repository.readIfPresent(ref)?.let { read ->
        return StateEntryKey(ref, source) to read
      }
    }
    return null
  }

  fun delete(key: StateEntryKey): StateDeleteResult =
      repository(key).delete(key.ref)

  fun export(key: StateEntryKey, destination: Path): StateExportResult =
      repository(key).export(key.ref, destination)

  fun firstAutosave(targetIdentity: MachineIdentity): Pair<StateEntryKey, StateReadResult>? {
    repositories.forEachIndexed { source, repository ->
      val entry =
          repository.catalog(targetIdentity).entries
              .firstOrNull { it.ref == StateRef.Autosave }
              ?: return@forEachIndexed
      if (entry.status != StateCatalogStatus.AVAILABLE) return null
      return StateEntryKey(StateRef.Autosave, source) to repository.read(StateRef.Autosave)
    }
    return null
  }

  /**
   * Returns the thumbnail attached to the first authoritative autosave, if it is available.
   *
   * Home uses this without a live machine, so state compatibility is intentionally not checked.
   * An invalid active autosave remains authoritative and therefore prevents an older fallback
   * thumbnail from being shown for a different save root.
   */
  fun autosaveThumbnail(): StateImage? {
    repositories.forEach { repository ->
      val autosave =
          try {
            repository.readIfPresent(StateRef.Autosave)
          } catch (_: IOException) {
            return null
          } ?: return@forEach
      val metadata = autosave.metadata ?: return null
      val thumbnailSha = metadata.thumbnailSha256 ?: return null
      return try {
        repository
            .readThumbnail(StateRef.Autosave, autosave.stateSha256, thumbnailSha)
            .copyBytes()
            ?.let(StatePngCodec::decode)
      } catch (_: IOException) {
        null
      }
    }
    return null
  }

  /**
   * Returns the authoritative autosave timestamp without requiring a live machine identity.
   * Legacy recent entries may use this as a fallback when no successful-open timestamp exists.
   */
  fun autosaveSavedAt(): Instant? {
    repositories.forEach { repository ->
      val autosave =
          try {
            repository.readIfPresent(StateRef.Autosave)
          } catch (_: IOException) {
            return null
          } ?: return@forEach
      return autosave.metadata?.savedAt
    }
    return null
  }

  fun activeGameDirectory(): Path = paths.layout.gameDirectory

  internal fun sensitivePaths(): List<Path> =
      buildList {
        add(paths.layout.gameDirectory)
        add(paths.screenshotsDirectory)
        paths.fallbackLayouts.forEach { add(it.gameDirectory) }
      }

  private fun browserEntry(
      source: Int,
      entry: StateCatalogEntry,
      recoveryMessages: MutableSet<String>,
  ): StateBrowserEntry {
    entry.recovery?.let {
      recoveryMessage(entry.ref, it)?.let(recoveryMessages::add)
    }
    val thumbnail =
        entry.metadata?.thumbnailSha256?.let { thumbnailSha ->
          val stateSha = entry.stateSha256 ?: return@let null
          try {
            val thumbnailRead =
                repositories[source].readThumbnail(entry.ref, stateSha, thumbnailSha)
            recoveryMessage(entry.ref, thumbnailRead.recovery)
                ?.let(recoveryMessages::add)
            thumbnailRead.copyBytes()?.let(StatePngCodec::decode)
          } catch (_: IOException) {
            null
          }
        }
    return StateBrowserEntry(StateEntryKey(entry.ref, source), entry, thumbnail)
  }

  private fun firstLocated(
      bySource: List<Map<StateRef, StateCatalogEntry>>,
      ref: StateRef,
  ): Pair<Int, StateCatalogEntry>? {
    bySource.forEachIndexed { source, entries ->
      entries[ref]?.let { return source to it }
    }
    return null
  }

  private fun repository(key: StateEntryKey): StateRepository =
      repositories.getOrNull(key.sourceIndex)
          ?: throw IOException("State source ${key.sourceIndex} is no longer configured")

  private fun recoveryMessage(ref: StateRef, recovery: StateRecovery): String? {
    val actions =
        listOfNotNull(
            describeRecovery("state", recovery.state),
            describeRecovery("metadata", recovery.metadata),
        )
    return actions.takeIf(List<String>::isNotEmpty)
        ?.joinToString(
            prefix = "${ref.storageKey()}: ",
            separator = "; ",
        )
  }

  private fun recoveryMessage(
      ref: StateRef,
      recovery: AtomicFileWriter.RecoveryReport,
  ): String? =
      describeRecovery("thumbnail", recovery)?.let { "${ref.storageKey()}: $it" }

  private fun describeRecovery(
      artifact: String,
      report: AtomicFileWriter.RecoveryReport,
  ): String? {
    val actions = ArrayList<String>()
    if (report.backupRestored()) actions += "$artifact backup restored"
    if (report.staleBackupRemoved()) actions += "stale $artifact backup removed"
    if (report.staleTemporaryFilesRemoved() > 0) {
      actions +=
          "${report.staleTemporaryFilesRemoved()} stale $artifact temporary file(s) removed"
    }
    return actions.takeIf(List<String>::isNotEmpty)?.joinToString(", ")
  }

  private fun boundedMessage(value: String): String =
      StateDiagnosticRedactor.redact(value, sensitivePaths(), MAX_ERROR_CHARS)

  private companion object {
    const val MAX_ERROR_CHARS = 320
  }
}
