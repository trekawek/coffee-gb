package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.core.events.Event
import java.nio.file.Path
import java.time.Instant

enum class StateOperation {
  CATALOG,
  SAVE,
  LOAD,
  LOAD_AVAILABILITY,
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

data class StateCatalogRequestEvent(
    val requestId: Long,
    val expectedSessionId: Long,
) : Event {
  init {
    requireRequestId(requestId)
    requireSessionId(expectedSessionId)
  }
}

data class StateCatalogReadyEvent(
    val requestId: Long,
    val sessionId: Long,
    val catalog: StateBrowserCatalog,
) : Event

data class StateSaveRequestEvent(
    val requestId: Long,
    val expectedSessionId: Long,
    val ref: StateRef,
    val label: String?,
    val thumbnail: StateImage?,
) : Event {
  init {
    requireRequestId(requestId)
    requireSessionId(expectedSessionId)
    require(ref != StateRef.Autosave) { "Autosave is controller-owned" }
  }
}

data class StateLoadRequestEvent(
    val requestId: Long,
    val expectedSessionId: Long,
    val key: StateEntryKey,
) : Event {
  init {
    requireRequestId(requestId)
    requireSessionId(expectedSessionId)
  }
}

/**
 * Loads the first managed state for [ref], resolving the active repository before configured
 * fallback repositories. Unlike [StateLoadRequestEvent], the caller does not need a browser
 * catalog (and therefore cannot interfere with catalog refresh coalescing).
 *
 * The desktop currently exposes this only for stable slots. If no managed source contains the
 * slot, BasicController may consult the preserved legacy `.snN` sidecar as a read-only
 * compatibility fallback.
 */
data class StateLoadRefRequestEvent(
    val requestId: Long,
    val expectedSessionId: Long,
    val ref: StateRef.Slot,
) : Event {
  init {
    requireRequestId(requestId)
    requireSessionId(expectedSessionId)
  }
}

/**
 * Asynchronously preflights the selected quick slot through the same managed-first, legacy
 * compatibility path as [StateLoadRefRequestEvent], without mutating the active session.
 */
data class StateSlotLoadAvailabilityRequestEvent(
    val requestId: Long,
    val expectedSessionId: Long,
    val ref: StateRef.Slot,
) : Event {
  init {
    requireRequestId(requestId)
    requireSessionId(expectedSessionId)
  }
}

/** A current-session answer for one selected quick slot. */
data class StateSlotLoadAvailabilityEvent(
    val requestId: Long,
    val sessionId: Long,
    val ref: StateRef.Slot,
    val available: Boolean,
) : Event

data class StateDeleteRequestEvent(
    val requestId: Long,
    val expectedSessionId: Long,
    val key: StateEntryKey,
) : Event {
  init {
    requireRequestId(requestId)
    requireSessionId(expectedSessionId)
  }
}

data class StateExportRequestEvent(
    val requestId: Long,
    val expectedSessionId: Long,
    val key: StateEntryKey,
    val destination: Path,
) : Event {
  init {
    requireRequestId(requestId)
    requireSessionId(expectedSessionId)
  }
}

data class StateScreenshotRequestEvent(
    val requestId: Long,
    val expectedSessionId: Long,
    val image: StateImage,
) : Event {
  init {
    requireRequestId(requestId)
    requireSessionId(expectedSessionId)
  }
}

data class StateOpenFolderRequestEvent(
    val requestId: Long,
    val expectedSessionId: Long,
) : Event {
  init {
    requireRequestId(requestId)
    requireSessionId(expectedSessionId)
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
    val expectedSessionId: Long,
    val accept: Boolean,
) : Event {
  init {
    requireRequestId(requestId)
    requireSessionId(expectedSessionId)
  }
}

data class StatePrepareCloseRequestEvent(val requestId: Long) : Event {
  init {
    requireRequestId(requestId)
  }
}

/**
 * Explicit user waiver after a failed/unavailable close autosave; never inferred silently.
 *
 * The session correlation prevents a delayed dialog choice from waiving autosave for a
 * replacement game.
 */
data class StateSkipCloseAutosaveRequestEvent(
    val requestId: Long,
    val sessionId: Long,
) : Event {
  init {
    requireRequestId(requestId)
    require(sessionId > 0) { "State session ID must be positive" }
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

private fun requireSessionId(value: Long) {
  require(value > 0) { "Expected state session ID must be positive" }
}
