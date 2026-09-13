package eu.rekawek.coffeegb.controller.replay

import eu.rekawek.coffeegb.core.events.Event
import java.nio.file.Path

/** Netplay diagnostics use a separate format and owner from single-machine CGBR replays. */
data class NetplayRecordingStartEvent(val sessionId: Long, val path: Path) : Event

data class NetplayRecordingStopEvent(val sessionId: Long) : Event

data class NetplayRecordingRetryEvent(val sessionId: Long, val path: Path) : Event

data class NetplayRecordingStatusEvent(
    val sessionId: Long,
    val phase: ReplayRecordingPhase,
    val available: Boolean = true,
    val savedPath: Path? = null,
    val error: String? = null,
) : Event
