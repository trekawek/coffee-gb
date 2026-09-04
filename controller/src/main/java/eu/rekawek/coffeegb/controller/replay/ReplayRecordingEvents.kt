package eu.rekawek.coffeegb.controller.replay

import eu.rekawek.coffeegb.core.events.Event
import java.nio.file.Path

/** Desktop-facing choices for creating one deterministic CGBR replay. */
enum class ReplayRecordingMode {
  /** Capture the current session, including its portable machine state. */
  CURRENT_SESSION,

  /** Start a fresh, battery-isolated machine and record from its first tick. */
  CLEAN_BOOT,
}

enum class ReplayRecordingPhase {
  IDLE,
  ARMING,
  RECORDING,
  SAVING,
  UNSAVED,
}

/** A consent-bound request from a presentation surface. */
data class ReplayRecordingStartRequestEvent(
    val requestId: Long,
    val expectedSessionId: Long,
    val mode: ReplayRecordingMode,
    /** Required only when [mode] captures an in-progress portable state. */
    val includeSensitiveInitialState: Boolean,
) : Event {
  init {
    require(requestId > 0) { "Replay recording request ID must be positive" }
    require(expectedSessionId > 0) { "Replay recording session ID must be positive" }
    require(mode != ReplayRecordingMode.CURRENT_SESSION || includeSensitiveInitialState) {
      "Current-session replay recording requires explicit sensitive-state consent"
    }
  }
}

data class ReplayRecordingStopRequestEvent(
    val requestId: Long,
    val expectedSessionId: Long,
) : Event {
  init {
    require(requestId > 0) { "Replay recording request ID must be positive" }
    require(expectedSessionId > 0) { "Replay recording session ID must be positive" }
  }
}

/** Requests persistence of the exact immutable replay retained after a recoverable write failure. */
data class ReplayRecordingRetrySaveEvent(
    val requestId: Long,
    val expectedSessionId: Long,
) : Event {
  init {
    require(requestId > 0) { "Replay retry request ID must be positive" }
    require(expectedSessionId > 0) { "Replay retry session ID must be positive" }
  }
}

/** Explicitly discards a completed replay that could not be saved. */
data class ReplayRecordingDiscardEvent(
    val requestId: Long,
    val expectedSessionId: Long,
) : Event {
  init {
    require(requestId > 0) { "Replay discard request ID must be positive" }
    require(expectedSessionId > 0) { "Replay discard session ID must be positive" }
  }
}

/** Authoritative, generation-scoped recording status. */
data class ReplayRecordingStatusEvent(
    val recordingId: Long?,
    val sessionId: Long?,
    val mode: ReplayRecordingMode?,
    val phase: ReplayRecordingPhase,
    val tickCount: Long = 0,
    val frameCount: Long = 0,
    val message: String? = null,
) : Event {
  init {
    require(tickCount >= 0) { "Replay recording tick count cannot be negative" }
    require(frameCount >= 0) { "Replay recording frame count cannot be negative" }
    if (phase == ReplayRecordingPhase.IDLE) {
      require(recordingId == null && mode == null) { "Idle replay recording cannot retain a job" }
    }
  }
}

data class ReplayRecordingSavedEvent(
    val recordingId: Long,
    val path: Path,
    val mode: ReplayRecordingMode,
    val tickCount: Long,
    val frameCount: Long,
) : Event

data class ReplayRecordingFailedEvent(
    val recordingId: Long?,
    val sessionId: Long?,
    val mode: ReplayRecordingMode?,
    val summary: String,
    val detail: String,
    val recoverable: Boolean,
) : Event {
  init {
    require(summary.isNotBlank()) { "Replay recording failure summary cannot be blank" }
    require(detail.isNotBlank()) { "Replay recording failure detail cannot be blank" }
  }
}
