package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter
import java.io.IOException
import java.nio.file.Path
import java.util.Collections

enum class StateCatalogStatus {
  AVAILABLE,
  INCOMPATIBLE,
  CORRUPT,
  IO_ERROR,
}

enum class StateMetadataWarningReason {
  CORRUPT,
  UNREADABLE,
  REFERENCE_MISMATCH,
  STATE_HASH_MISMATCH,
}

data class StateMetadataWarning(
    val reason: StateMetadataWarningReason,
    val message: String,
)

data class StateRecovery(
    val state: AtomicFileWriter.RecoveryReport,
    val metadata: AtomicFileWriter.RecoveryReport,
) {
  val recoveredAnything: Boolean
    get() = state.recoveredAnything() || metadata.recoveredAnything()
}

data class StateReadResult(
    val ref: StateRef,
    val state: StateFile,
    val inspection: StateFileInspection,
    val stateSha256: String,
    val metadata: StateMetadata?,
    val metadataWarning: StateMetadataWarning?,
    val recovery: StateRecovery,
)

data class StateSaveResult(
    val ref: StateRef,
    val stateSha256: String,
    val metadata: StateMetadata,
    val metadataCommitted: Boolean,
    val metadataFailure: String?,
    val recovery: StateRecovery,
)

data class StateAssetSaveResult(
    val state: StateSaveResult,
    val thumbnailCommitted: Boolean,
    val thumbnailFailure: String?,
    val thumbnailRecovery: AtomicFileWriter.RecoveryReport,
)

class StateThumbnailReadResult(
    bytes: ByteArray?,
    val recovery: AtomicFileWriter.RecoveryReport,
) {
  private val ownedBytes = bytes?.clone()

  fun copyBytes(): ByteArray? = ownedBytes?.clone()
}

data class StateDeleteResult(
    val ref: StateRef,
    val deletedArtifacts: Int,
    val recovery: StateRecovery,
)

data class StateExportResult(
    val ref: StateRef,
    val destination: Path,
    val stateSha256: String,
    val recovery: ExclusiveWriteRecovery = ExclusiveWriteRecovery.NONE,
)

class StateRepositoryCapacityException(message: String) : IOException(message)

data class StateCatalogEntry(
    val ref: StateRef,
    val status: StateCatalogStatus,
    val detail: String?,
    val inspection: StateFileInspection?,
    val stateSha256: String?,
    val metadata: StateMetadata?,
    val metadataWarning: StateMetadataWarning?,
    val compatibility: StateCompatibilityResult?,
    val recovery: StateRecovery?,
)

class StateCatalog
@JvmOverloads
constructor(
    entries: Collection<StateCatalogEntry>,
    val namedStatesTruncated: Boolean,
    /** Non-null when the bounded named namespace could not be enumerated safely. */
    val namedStatesError: String? = null,
) {
  val entries: List<StateCatalogEntry> =
      Collections.unmodifiableList(ArrayList(entries))
}
