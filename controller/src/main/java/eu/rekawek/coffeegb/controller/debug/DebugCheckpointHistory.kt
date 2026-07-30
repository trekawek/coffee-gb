package eu.rekawek.coffeegb.controller.debug

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.state.SessionSnapshot
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryConfiguration
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryPoint
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryStatus
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryTruncationReason

/**
 * Owner-thread checkpoint ring used by reverse debugging.
 *
 * The ring is entirely dormant until explicitly configured. Checkpoints are captured only at
 * controller frame boundaries and contain no host services. Reverse-frame is deliberately
 * destructive in this first slice: after a successful restore, checkpoints from the abandoned
 * future are released. Replay-forward branching is added by the following reverse-instruction
 * slice.
 */
internal class DebugCheckpointHistory {
  private data class Entry(
      val point: DebugHistoryPoint,
      val snapshot: SessionSnapshot,
  )

  internal sealed interface RestoreOutcome {
    data class Restored(
        val point: DebugHistoryPoint,
        val status: DebugHistoryStatus,
    ) : RestoreOutcome

    data object Disabled : RestoreOutcome

    data object Exhausted : RestoreOutcome
  }

  private val entries = ArrayDeque<Entry>()

  private var retentionLedger = SessionSnapshot.RetentionLedger()

  private var configuration = DebugHistoryConfiguration.disabled()

  private var nextCheckpointId = 1L

  private var evictedCheckpoints = 0L

  private var lastTruncationReason = DebugHistoryTruncationReason.NONE

  internal var captureCount = 0L
    private set

  val enabled: Boolean
    get() = configuration.enabled()

  /** Applies a new budget and captures the current boundary as its initial anchor when possible. */
  fun configure(
      requested: DebugHistoryConfiguration,
      session: Session,
      masterTick: Long,
      frame: Long,
      framePosition: Int,
  ): DebugHistoryStatus {
    val replacingExistingConfiguration = configuration.enabled()
    entries.clear()
    retentionLedger = SessionSnapshot.RetentionLedger()
    evictedCheckpoints = 0
    captureCount = 0
    configuration = requested
    lastTruncationReason =
        if (replacingExistingConfiguration) {
          DebugHistoryTruncationReason.CONFIGURATION_CHANGED
        } else {
          DebugHistoryTruncationReason.NONE
        }
    if (requested.enabled() && framePosition == 0) {
      recordFrame(session, masterTick, frame)
    }
    return status()
  }

  /** Records one completed frame boundary. Disabled history returns before any capture work. */
  fun recordFrame(
      session: Session,
      masterTick: Long,
      frame: Long,
  ) {
    if (!configuration.enabled()) return
    require(masterTick >= 0) { "Debug history master tick must not be negative" }
    require(frame >= 0) { "Debug history frame must not be negative" }
    if (nextCheckpointId == Long.MAX_VALUE) {
      throw IllegalStateException("Debug checkpoint identifiers are exhausted")
    }
    val previous = entries.lastOrNull()?.snapshot
    val snapshot = SessionSnapshot.capture(session, previous)
    val entry = Entry(DebugHistoryPoint(nextCheckpointId++, masterTick, frame), snapshot)
    retentionLedger.add(snapshot)
    entries.addLast(entry)
    captureCount++
    enforceFrameBudget()
    enforceMemoryBudget()
  }

  /**
   * Restores the preceding boundary without executing a guest tick.
   *
   * At a partial frame the newest checkpoint is the start of that frame. At a boundary the newest
   * checkpoint is the current state, so the preceding entry is selected instead. History is
   * mutated only after the rollback-protected session restore commits.
   */
  fun restorePreviousFrame(
      session: Session,
      atFrameBoundary: Boolean,
      effectiveCartridgePause: Boolean,
  ): RestoreOutcome {
    if (!configuration.enabled()) return RestoreOutcome.Disabled
    val targetIndex = entries.size - if (atFrameBoundary) 2 else 1
    if (targetIndex < 0) return RestoreOutcome.Exhausted
    val target = entries.elementAt(targetIndex)

    target.snapshot.restore(session, effectiveCartridgePause)

    var removedFuture = false
    while (entries.size > targetIndex + 1) {
      removeLast()
      removedFuture = true
    }
    if (removedFuture) {
      lastTruncationReason = DebugHistoryTruncationReason.REVERSE_STEP
    }
    return RestoreOutcome.Restored(target.point, status())
  }

  /** Clears retained state while keeping the opt-in configuration armed for later boundaries. */
  fun clear(reason: DebugHistoryTruncationReason) {
    require(reason != DebugHistoryTruncationReason.NONE) {
      "A cleared debug history requires a truncation reason"
    }
    if (entries.isNotEmpty()) {
      entries.clear()
      retentionLedger = SessionSnapshot.RetentionLedger()
    }
    lastTruncationReason = reason
  }

  /** Releases all retained snapshots and returns to the allocation-free disabled state. */
  fun disable(reason: DebugHistoryTruncationReason) {
    clear(reason)
    configuration = DebugHistoryConfiguration.disabled()
  }

  fun status(): DebugHistoryStatus {
    return DebugHistoryStatus(
        configuration,
        entries.size,
        retentionLedger.retainedBytes,
        evictedCheckpoints,
        entries.firstOrNull()?.point,
        entries.lastOrNull()?.point,
        lastTruncationReason,
    )
  }

  internal val checkpointCount: Int
    get() = entries.size

  /** Opaque identity seam proving that repeated empty clears do not replace the ledger. */
  internal val retentionLedgerIdentityForTesting: Any
    get() = retentionLedger

  private fun enforceFrameBudget() {
    var removed = 0L
    while (entries.size > configuration.maxFrames()) {
      removeFirst()
      removed++
    }
    if (removed != 0L) {
      evictedCheckpoints = saturatingAdd(evictedCheckpoints, removed)
      lastTruncationReason = DebugHistoryTruncationReason.FRAME_BUDGET
    }
  }

  private fun enforceMemoryBudget() {
    var removed = 0L
    while (retentionLedger.retainedBytes > configuration.memoryBudgetBytes() && entries.isNotEmpty()) {
      removeFirst()
      removed++
    }
    if (removed != 0L) {
      evictedCheckpoints = saturatingAdd(evictedCheckpoints, removed)
      lastTruncationReason = DebugHistoryTruncationReason.MEMORY_BUDGET
    }
  }

  private fun removeFirst() {
    retentionLedger.remove(entries.removeFirst().snapshot)
  }

  private fun removeLast() {
    retentionLedger.remove(entries.removeLast().snapshot)
  }

  private fun saturatingAdd(left: Long, right: Long): Long =
      if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
