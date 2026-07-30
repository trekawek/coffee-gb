package eu.rekawek.coffeegb.controller.debug

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.controller.state.SessionSnapshot
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryConfiguration
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryPoint
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryPosition
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryStatus
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryTruncationReason
import eu.rekawek.coffeegb.core.joypad.InputTimelineObserver
import eu.rekawek.coffeegb.core.joypad.JoypadButtonMask
import eu.rekawek.coffeegb.core.joypad.PlayerInputSnapshot

/** The requested session's deterministic input seam is owned by another capture. */
internal class DebugHistorySessionBusyException : IllegalStateException(
    "The session input timeline is already owned",
)

/**
 * Owner-thread frame checkpoints and deterministic input transcript for reverse debugging.
 *
 * Debugger observation coordinates remain monotonic after a reverse. This class therefore owns a
 * separate historical cursor which follows the emulated branch. Reversing moves only that cursor;
 * the original future remains retained until the first forward/input mutation invalidates it.
 */
internal class DebugCheckpointHistory {
  internal data class InputBaseline(
      val legacyMask: Int,
      val physical: PlayerInputSnapshot,
  )

  internal data class InputRecord(
      val tick: Long,
      val phase: InputTimelineObserver.Phase,
      val player: Int,
      val absoluteMask: Int,
      val changedMask: Int,
  )

  internal data class PauseRun(
      val tickOffset: Int,
      val paused: Boolean,
  )

  private data class Entry(
      val point: DebugHistoryPoint,
      val snapshot: SessionSnapshot,
      val input: InputBaseline,
      val inputs: ArrayList<InputRecord> = ArrayList(),
      val pauseRuns: ArrayList<PauseRun> = ArrayList(),
      var retirementCount: Int = 0,
      var lastTickRetired: Boolean = false,
  )

  internal data class InstructionReplayPlan(
      val anchor: DebugHistoryPoint,
      val snapshot: SessionSnapshot,
      val input: InputBaseline,
      val inputs: List<InputRecord>,
      val pauseRuns: List<PauseRun>,
      val targetRetirementOrdinal: Int,
      val maximumTicks: Int,
      internal val expectedCursor: DebugHistoryPosition,
  )

  internal sealed interface RestoreOutcome {
    data class Restored(
        val position: DebugHistoryPosition,
        val anchor: DebugHistoryPoint,
        val status: DebugHistoryStatus,
    ) : RestoreOutcome {
      /** Phase-five compatibility for direct frame-checkpoint callers. */
      val point: DebugHistoryPoint
        get() = anchor
    }

    data object Disabled : RestoreOutcome

    data object Exhausted : RestoreOutcome
  }

  private val entries = ArrayDeque<Entry>()

  private var retentionLedger = SessionSnapshot.RetentionLedger()

  private var configuration = DebugHistoryConfiguration.disabled()

  private var nextCheckpointId = 1L

  private var evictedCheckpoints = 0L

  private var lastTruncationReason = DebugHistoryTruncationReason.NONE

  private var cursor: DebugHistoryPosition? = null

  /** Furthest position reached on the retained original branch. */
  private var tip: DebugHistoryPosition? = null

  private var cursorAnchorId: Long? = null

  private var cursorRetirementCount = 0

  private var cursorLastTickRetired = false

  private var ownerThread: Thread? = null

  private var observedGameboy: Gameboy? = null

  @Volatile private var timelineValid = true

  private var tickInProgress = false

  private var inputRecordsAtTick = 0

  private var countedInputTick = Long.MIN_VALUE

  private val observer =
      InputTimelineObserver { phase, player, buttonMask, changedMask ->
        if (Thread.currentThread() !== ownerThread ||
            phase == InputTimelineObserver.Phase.LEGACY_P1_BEFORE_TICK && tickInProgress ||
            phase == InputTimelineObserver.Phase.PHYSICAL_JOYPAD_SAMPLE && !tickInProgress) {
          timelineValid = false
          return@InputTimelineObserver
        }
        val position = cursor ?: return@InputTimelineObserver
        if (hasFuture) {
          // A live input source must not mutate a retained historical cursor before the owner has
          // explicitly created a new branch.
          timelineValid = false
          return@InputTimelineObserver
        }
        if (countedInputTick != position.masterTick()) {
          countedInputTick = position.masterTick()
          inputRecordsAtTick = 0
        }
        if (inputRecordsAtTick == MAX_INPUT_RECORDS_PER_TICK) {
          timelineValid = false
          return@InputTimelineObserver
        }
        val anchor = activeAnchor() ?: return@InputTimelineObserver
        anchor.inputs +=
            InputRecord(position.masterTick(), phase, player, buttonMask, changedMask)
        inputRecordsAtTick++
        enforceMemoryBudget()
      }

  internal var captureCount = 0L
    private set

  val enabled: Boolean
    get() = configuration.enabled()

  val hasFuture: Boolean
    get() = cursor != null && tip != null && cursor != tip

  val inputTimelineValid: Boolean
    get() = timelineValid

  /** Applies a new budget and captures the current boundary as its initial anchor when possible. */
  fun configure(
      requested: DebugHistoryConfiguration,
      session: Session,
      masterTick: Long,
      frame: Long,
      framePosition: Int,
  ): DebugHistoryStatus {
    require(masterTick >= 0 && frame >= 0 && framePosition >= 0)
    val targetGameboy = session.gameboy
    val retainingObserver = requested.enabled() && observedGameboy === targetGameboy
    if (requested.enabled() && !retainingObserver) {
      // Claim the new seam before changing any retained state. A failed claim is therefore an
      // atomic configuration rejection, including when this history still owns another session.
      if (!targetGameboy.attachInputTimelineObserver(observer)) {
        throw DebugHistorySessionBusyException()
      }
    }
    val replacingExistingConfiguration = configuration.enabled()
    if (!retainingObserver) {
      detachObserver()
    }
    releaseEntries()
    evictedCheckpoints = 0
    captureCount = 0
    configuration = requested
    cursor = DebugHistoryPosition(masterTick, frame, framePosition)
    tip = cursor
    cursorAnchorId = null
    cursorRetirementCount = 0
    cursorLastTickRetired = false
    timelineValid = true
    tickInProgress = false
    ownerThread = Thread.currentThread()
    lastTruncationReason =
        if (replacingExistingConfiguration) {
          DebugHistoryTruncationReason.CONFIGURATION_CHANGED
        } else {
          DebugHistoryTruncationReason.NONE
        }
    if (!requested.enabled()) {
      cursor = null
      tip = null
      ownerThread = null
      return status()
    }
    observedGameboy = targetGameboy
    if (framePosition == 0) {
      recordFrame(session)
    }
    return status()
  }

  /** Records the pause mode that governs the next guest tick. */
  fun onTickStarted(cartridgePaused: Boolean) {
    if (!configuration.enabled()) return
    check(Thread.currentThread() === ownerThread)
    check(!tickInProgress) { "Debug history tick already started" }
    check(!hasFuture) { "A retained future must be invalidated before forward execution" }
    tickInProgress = true
    val position = cursor ?: return
    val anchor = activeAnchor() ?: return
    val offset = Math.toIntExact(position.masterTick() - anchor.point.masterTick())
    if (anchor.pauseRuns.lastOrNull()?.paused != cartridgePaused) {
      anchor.pauseRuns += PauseRun(offset, cartridgePaused)
      enforceMemoryBudget()
    }
  }

  /** Advances the historical cursor after one complete [Gameboy.tick] boundary. */
  fun onTickCompleted(retiredInstruction: Boolean, frameTicks: Int) {
    if (!configuration.enabled()) return
    check(Thread.currentThread() === ownerThread)
    check(tickInProgress) { "Debug history tick did not start" }
    tickInProgress = false
    val current = cursor ?: return
    val nextPosition = current.framePosition() + 1
    cursor =
        if (nextPosition == frameTicks) {
          DebugHistoryPosition(
              Math.addExact(current.masterTick(), 1L),
              Math.addExact(current.frame(), 1L),
              0,
          )
        } else {
          DebugHistoryPosition(
              Math.addExact(current.masterTick(), 1L),
              current.frame(),
              nextPosition,
          )
        }
    tip = cursor
    val anchor = activeAnchor()
    if (anchor != null) {
      if (retiredInstruction) {
        anchor.retirementCount = Math.addExact(anchor.retirementCount, 1)
      }
      anchor.lastTickRetired = retiredInstruction
      cursorRetirementCount = anchor.retirementCount
      cursorLastTickRetired = retiredInstruction
    }
  }

  /** Releases the owner-side phase marker when a guest tick fails before reaching its boundary. */
  fun abortTick() {
    if (!configuration.enabled()) return
    check(Thread.currentThread() === ownerThread)
    tickInProgress = false
  }

  /** Records one completed frame boundary. Disabled history returns before any capture work. */
  fun recordFrame(session: Session) {
    if (!configuration.enabled()) return
    check(Thread.currentThread() === ownerThread)
    val position = checkNotNull(cursor)
    require(position.framePosition() == 0) {
      "Debug frame checkpoints require a frame lattice boundary"
    }
    check(!hasFuture) { "A retained future must be invalidated before checkpoint capture" }
    if (nextCheckpointId == Long.MAX_VALUE) {
      throw IllegalStateException("Debug checkpoint identifiers are exhausted")
    }
    val previous = entries.lastOrNull()?.snapshot
    val snapshot = SessionSnapshot.capture(session, previous)
    val point =
        DebugHistoryPoint(nextCheckpointId++, position.masterTick(), position.frame())
    val entry = Entry(point, snapshot, captureInput(session.gameboy))
    retentionLedger.add(snapshot)
    entries.addLast(entry)
    cursorAnchorId = point.checkpointId()
    cursorRetirementCount = 0
    cursorLastTickRetired = false
    captureCount++
    enforceFrameBudget()
    enforceMemoryBudget()
  }

  /** Phase-five test seam for manually advanced machines. */
  fun recordFrame(
      session: Session,
      masterTick: Long,
      frame: Long,
  ) {
    if (!configuration.enabled()) return
    cursor = DebugHistoryPosition(masterTick, frame, 0)
    tip = cursor
    recordFrame(session)
  }

  /**
   * Restores the preceding frame boundary without executing a guest tick.
   *
   * The original future remains retained. Input services are restored from the checkpoint's
   * explicit baseline because neither legacy buttons nor the physical P1-P4 latch is machine
   * state.
   */
  fun restorePreviousFrame(
      session: Session,
      atFrameBoundary: Boolean,
      effectiveCartridgePause: Boolean,
  ): RestoreOutcome {
    if (!configuration.enabled()) return RestoreOutcome.Disabled
    if (!timelineValid) return RestoreOutcome.Exhausted
    val current = cursor ?: return RestoreOutcome.Exhausted
    val target =
        entries.lastOrNull { entry ->
          if (atFrameBoundary || current.framePosition() == 0) {
            entry.point.masterTick() < current.masterTick()
          } else {
            entry.point.masterTick() <= current.masterTick()
          }
        } ?: return RestoreOutcome.Exhausted

    target.snapshot.restore(session, effectiveCartridgePause)
    session.gameboy.seedDeterministicReplayInput(
        JoypadButtonMask.toButtons(target.input.legacyMask),
        target.input.physical,
    )
    cursor = DebugHistoryPosition.atCheckpoint(target.point)
    cursorAnchorId = target.point.checkpointId()
    cursorRetirementCount = 0
    cursorLastTickRetired = false
    return RestoreOutcome.Restored(checkNotNull(cursor), target.point, status())
  }

  /** Selects one frame segment and retirement ordinal for bounded isolated replay. */
  fun planPreviousInstruction(): InstructionReplayPlan? {
    if (!configuration.enabled() || !timelineValid) return null
    val current = cursor ?: return null
    if (entries.isEmpty()) return null

    var index: Int
    var available: Int
    var exactRetirement: Boolean
    var maximumTicks: Int
    if (current.framePosition() != 0) {
      index = entries.indexOfLast { it.point.checkpointId() == cursorAnchorId }
      if (index < 0) return null
      available = cursorRetirementCount
      exactRetirement = cursorLastTickRetired
      maximumTicks = Math.toIntExact(current.masterTick() - entries.elementAt(index).point.masterTick())
    } else {
      val boundaryIndex = entries.indexOfLast { it.point.masterTick() == current.masterTick() }
      index = boundaryIndex - 1
      if (index < 0) return null
      val segment = entries.elementAt(index)
      available = segment.retirementCount
      exactRetirement = segment.lastTickRetired
      maximumTicks =
          Math.toIntExact(entries.elementAt(index + 1).point.masterTick() - segment.point.masterTick())
    }

    var targetOrdinal = available - if (exactRetirement) 1 else 0
    while (targetOrdinal <= 0) {
      index--
      if (index < 0) return null
      val segment = entries.elementAt(index)
      targetOrdinal = segment.retirementCount
      if (targetOrdinal > 0) {
        maximumTicks =
            Math.toIntExact(
                entries.elementAt(index + 1).point.masterTick() - segment.point.masterTick())
      }
    }
    val anchor = entries.elementAt(index)
    return InstructionReplayPlan(
        anchor.point,
        anchor.snapshot,
        anchor.input,
        anchor.inputs,
        anchor.pauseRuns,
        targetOrdinal,
        maximumTicks,
        current,
    )
  }

  /** Commits a prepared instruction target after the live snapshot restore succeeds. */
  fun commitInstructionReverse(
      plan: InstructionReplayPlan,
      target: DebugHistoryPosition,
  ): DebugHistoryStatus {
    check(cursor == plan.expectedCursor) { "Debug history cursor changed during replay" }
    check(target.masterTick() > plan.anchor.masterTick())
    check(target.masterTick() <= plan.anchor.masterTick() + plan.maximumTicks)
    cursor = target
    cursorAnchorId = plan.anchor.checkpointId()
    cursorRetirementCount = plan.targetRetirementOrdinal
    cursorLastTickRetired = true
    return status()
  }

  /** Discards the retained original future immediately before the first branch mutation. */
  fun invalidateFuture(session: Session): Boolean {
    if (!configuration.enabled() || !hasFuture) return false
    val position = checkNotNull(cursor)
    val anchorAtCursor =
        activeAnchor()?.point?.let { point ->
          point.masterTick() == position.masterTick() && position.framePosition() == 0
        } == true
    val replacement =
        if (!anchorAtCursor && position.framePosition() == 0) {
          // Capture before changing the retained deque. Session capture can fail (for example,
          // while reading mapper RTC state); in that case the original branch remains intact.
          check(nextCheckpointId != Long.MAX_VALUE) {
            "Debug checkpoint identifiers are exhausted"
          }
          val previous = checkNotNull(activeAnchor()).snapshot
          Entry(
              DebugHistoryPoint(nextCheckpointId, position.masterTick(), position.frame()),
              SessionSnapshot.capture(session, previous),
              captureInput(session.gameboy),
          )
        } else {
          null
        }
    while (entries.lastOrNull()?.point?.let { point ->
      point.masterTick() > position.masterTick() ||
          !anchorAtCursor &&
              position.framePosition() == 0 &&
              point.masterTick() == position.masterTick()
    } == true) {
      removeLast()
    }
    val anchor = activeAnchor()
    if (anchor != null) {
      val inputIterator = anchor.inputs.listIterator(anchor.inputs.size)
      while (inputIterator.hasPrevious()) {
        if (inputIterator.previous().tick >= position.masterTick()) inputIterator.remove()
      }
      val offset = Math.toIntExact(position.masterTick() - anchor.point.masterTick())
      val pauseIterator = anchor.pauseRuns.listIterator(anchor.pauseRuns.size)
      while (pauseIterator.hasPrevious()) {
        if (pauseIterator.previous().tickOffset >= offset) pauseIterator.remove()
      }
      anchor.retirementCount = cursorRetirementCount
      anchor.lastTickRetired = cursorLastTickRetired
    }
    tip = position
    if (replacement != null) {
      // An instruction replay can land on the same coordinate as an original-future checkpoint.
      // That checkpoint may include capture-time RTC effects, so replace it with the exact live
      // target instead of treating coordinate equality as state equality.
      retentionLedger.add(replacement.snapshot)
      entries.addLast(replacement)
      nextCheckpointId++
      cursorAnchorId = replacement.point.checkpointId()
      cursorRetirementCount = 0
      cursorLastTickRetired = false
      captureCount++
      enforceFrameBudget()
      enforceMemoryBudget()
    }
    lastTruncationReason = DebugHistoryTruncationReason.BRANCH_INVALIDATED
    return true
  }

  /** Clears retained state while keeping the opt-in configuration and observer armed. */
  fun clear(reason: DebugHistoryTruncationReason) {
    require(reason != DebugHistoryTruncationReason.NONE) {
      "A cleared debug history requires a truncation reason"
    }
    releaseEntries()
    cursorAnchorId = null
    cursorRetirementCount = 0
    cursorLastTickRetired = false
    tip = cursor
    timelineValid = true
    tickInProgress = false
    countedInputTick = Long.MIN_VALUE
    inputRecordsAtTick = 0
    lastTruncationReason = reason
  }

  /** Releases every retained service and returns to the allocation-free disabled state. */
  fun disable(reason: DebugHistoryTruncationReason) {
    clear(reason)
    detachObserver()
    configuration = DebugHistoryConfiguration.disabled()
    cursor = null
    tip = null
    ownerThread = null
    timelineValid = true
    tickInProgress = false
  }

  fun status(): DebugHistoryStatus {
    val current = cursor
    val future =
        if (current == null || !hasFuture) {
          0
        } else {
          val anchorIndex = entries.indexOfFirst { it.point.checkpointId() == cursorAnchorId }
          if (anchorIndex < 0) {
            entries.count { it.point.masterTick() > current.masterTick() }
          } else {
            entries.size - anchorIndex - 1
          }
        }
    return DebugHistoryStatus(
        configuration,
        entries.size,
        retainedBytes(),
        evictedCheckpoints,
        entries.firstOrNull()?.point,
        entries.lastOrNull()?.point,
        current,
        future,
        lastTruncationReason,
    )
  }

  internal val checkpointCount: Int
    get() = entries.size

  /** Opaque identity seam proving that repeated empty clears do not replace the ledger. */
  internal val retentionLedgerIdentityForTesting: Any
    get() = retentionLedger

  private fun activeAnchor(): Entry? {
    val id = cursorAnchorId ?: return null
    entries.lastOrNull()?.takeIf { it.point.checkpointId() == id }?.let { return it }
    return entries.lastOrNull { it.point.checkpointId() == id }
  }

  private fun captureInput(gameboy: Gameboy): InputBaseline =
      InputBaseline(
          JoypadButtonMask.fromButtons(gameboy.legacyPressedButtons),
          gameboy.sampledPlayerInput,
      )

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
    if (!configuration.enabled()) return
    var removed = 0L
    while (retainedBytes() > configuration.memoryBudgetBytes() && entries.isNotEmpty()) {
      removeFirst()
      removed++
    }
    if (removed != 0L) {
      evictedCheckpoints = saturatingAdd(evictedCheckpoints, removed)
      lastTruncationReason = DebugHistoryTruncationReason.MEMORY_BUDGET
    }
  }

  private fun retainedBytes(): Long {
    if (entries.isEmpty()) return 0
    var total = retentionLedger.retainedBytes
    entries.forEach { entry ->
      total = saturatingAdd(total, ENTRY_MODELED_BYTES)
      total = saturatingAdd(total, entry.inputs.size.toLong() * INPUT_RECORD_MODELED_BYTES)
      total = saturatingAdd(total, entry.pauseRuns.size.toLong() * PAUSE_RUN_MODELED_BYTES)
    }
    return total
  }

  private fun removeFirst() {
    val removed = entries.removeFirst()
    retentionLedger.remove(removed.snapshot)
    if (removed.point.checkpointId() == cursorAnchorId) {
      cursorAnchorId = null
      cursorRetirementCount = 0
      cursorLastTickRetired = false
    }
  }

  private fun removeLast() {
    val removed = entries.removeLast()
    retentionLedger.remove(removed.snapshot)
    if (removed.point.checkpointId() == cursorAnchorId) {
      cursorAnchorId = null
      cursorRetirementCount = 0
      cursorLastTickRetired = false
    }
  }

  private fun releaseEntries() {
    if (entries.isEmpty()) return
    entries.clear()
    retentionLedger = SessionSnapshot.RetentionLedger()
  }

  private fun detachObserver() {
    observedGameboy?.detachInputTimelineObserver(observer)
    observedGameboy = null
  }

  private fun saturatingAdd(left: Long, right: Long): Long =
      if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

  private companion object {
    const val MAX_INPUT_RECORDS_PER_TICK = 32
    const val ENTRY_MODELED_BYTES = 96L
    const val INPUT_RECORD_MODELED_BYTES = 40L
    const val PAUSE_RUN_MODELED_BYTES = 24L
  }
}
