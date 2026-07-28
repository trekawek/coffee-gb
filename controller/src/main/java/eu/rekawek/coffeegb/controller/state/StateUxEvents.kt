package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.core.events.Event
import java.nio.file.Path
import java.time.Instant

enum class StateOperation {
  CATALOG,
  SAVE,
  LOAD,
  DELETE,
  EXPORT,
  SCREENSHOT,
  OPEN_FOLDER,
  AUTOSAVE,
  RESUME,
  PREPARE_CLOSE,
}

/** Bounded, copyable error information suitable for an actionable desktop details dialog. */
data class StateUserError(
    val summary: String,
    val detail: String,
    val suggestedAction: String,
) {
  init {
    require(summary.isNotBlank() && summary.length <= MAX_SUMMARY_CHARS)
    require(detail.isNotBlank() && detail.length <= MAX_DETAIL_CHARS)
    require(suggestedAction.isNotBlank() && suggestedAction.length <= MAX_ACTION_CHARS)
    require((summary + detail + suggestedAction).none { it == '\u0000' })
  }

  companion object {
    const val MAX_SUMMARY_CHARS = 240
    const val MAX_DETAIL_CHARS = 8192
    const val MAX_ACTION_CHARS = 500
  }
}

data class StateUxSessionEvent(
    val sessionId: Long,
    val available: Boolean,
    val gameDirectory: Path?,
    val unavailableReason: StateUserError? = null,
) : Event

data class StateCatalogRequestEvent(val requestId: Long) : Event {
  init {
    requireRequestId(requestId)
  }
}

data class StateCatalogReadyEvent(
    val requestId: Long,
    val sessionId: Long,
    val catalog: StateBrowserCatalog,
) : Event

data class StateSaveRequestEvent(
    val requestId: Long,
    val ref: StateRef,
    val label: String?,
    val thumbnail: StateImage?,
) : Event {
  init {
    requireRequestId(requestId)
    require(ref != StateRef.Autosave) { "Autosave is controller-owned" }
  }
}

data class StateLoadRequestEvent(
    val requestId: Long,
    val key: StateEntryKey,
) : Event {
  init {
    requireRequestId(requestId)
  }
}

data class StateDeleteRequestEvent(
    val requestId: Long,
    val key: StateEntryKey,
) : Event {
  init {
    requireRequestId(requestId)
  }
}

data class StateExportRequestEvent(
    val requestId: Long,
    val key: StateEntryKey,
    val destination: Path,
) : Event {
  init {
    requireRequestId(requestId)
  }
}

data class StateScreenshotRequestEvent(
    val requestId: Long,
    val image: StateImage,
) : Event {
  init {
    requireRequestId(requestId)
  }
}

data class StateOpenFolderRequestEvent(val requestId: Long) : Event {
  init {
    requireRequestId(requestId)
  }
}

data class StateOperationCompletedEvent(
    val requestId: Long,
    val sessionId: Long,
    val operation: StateOperation,
    val ref: StateRef? = null,
    val path: Path? = null,
    val message: String,
    val recoveryMessages: List<String> = emptyList(),
    val folderOpened: Boolean? = null,
) : Event

data class StateOperationFailedEvent(
    val requestId: Long,
    val sessionId: Long,
    val operation: StateOperation,
    val error: StateUserError,
) : Event

data class StateResumeAvailableEvent(
    val requestId: Long,
    val sessionId: Long,
    val key: StateEntryKey,
    val savedAt: Instant?,
    val playDurationNanos: Long?,
) : Event

data class StateResumeDecisionEvent(
    val requestId: Long,
    val accept: Boolean,
) : Event {
  init {
    requireRequestId(requestId)
  }
}

data class StatePrepareCloseRequestEvent(val requestId: Long) : Event {
  init {
    requireRequestId(requestId)
  }
}

data class StatePrepareCloseCompletedEvent(
    val requestId: Long,
    val sessionId: Long,
    val autosaved: Boolean,
    val error: StateUserError?,
) : Event

private fun requireRequestId(value: Long) {
  require(value > 0) { "State request ID must be positive" }
}
