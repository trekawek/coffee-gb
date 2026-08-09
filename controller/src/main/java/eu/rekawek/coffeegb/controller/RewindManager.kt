package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.controller.state.MachineSnapshot
import eu.rekawek.coffeegb.controller.state.SessionSnapshot

/**
 * Rolling history of emulation states for the rewind feature. A state is recorded every
 * [RECORD_INTERVAL] frames. Both duration and retained-memory limits are user-configurable.
 * Rewinding restores one recorded state per rendered frame, so it plays backwards at
 * [RECORD_INTERVAL] times the game speed.
 */
internal class RewindManager(
    val enabled: Boolean = true,
    durationSeconds: Int = DEFAULT_DURATION_SECONDS,
    private val memoryBudgetBytes: Long = DEFAULT_MEMORY_BUDGET_BYTES,
) {

  private sealed interface RewindEntry {
    val machine: MachineSnapshot
    val auxiliaryCaptureBytes: Long
  }

  private data class MachineEntry(
      override val machine: MachineSnapshot,
  ) : RewindEntry {
    override val auxiliaryCaptureBytes: Long = 0
  }

  private data class SessionEntry(
      val snapshot: SessionSnapshot,
  ) : RewindEntry {
    override val machine: MachineSnapshot = snapshot.machine
    override val auxiliaryCaptureBytes: Long = snapshot.serialModeledBytes
  }

  private enum class HistoryKind {
    MACHINE,
    SESSION,
  }

  private val states = ArrayDeque<RewindEntry>()

  private var historyKind: HistoryKind? = null

  private val capacity =
      Math.multiplyExact(durationSeconds, APPROXIMATE_FRAMES_PER_SECOND) / RECORD_INTERVAL

  /**
   * Cheap conservative running estimate. Exact graph retention is measured only after this
   * crosses the configured budget; that keeps the normal six-frame capture path lightweight.
   */
  private var approximateRetainedBytes = 0L

  private var frameCounter = 0

  internal var captureCount = 0
    private set

  internal var budgetEvictionCount = 0
    private set

  init {
    require(durationSeconds in MIN_DURATION_SECONDS..MAX_DURATION_SECONDS) {
      "Rewind duration must be between $MIN_DURATION_SECONDS and $MAX_DURATION_SECONDS seconds"
    }
    require(memoryBudgetBytes >= MIN_MEMORY_BUDGET_BYTES) {
      "Rewind memory budget must be at least $MIN_MEMORY_BUDGET_BYTES bytes"
    }
  }

  fun record(gameboy: Gameboy) {
    // This is intentionally the first operation. A disabled manager does not advance cadence,
    // inspect the live machine, allocate a capture DTO, or create a snapshot.
    if (!enabled) return
    // A suppressed frame is intentionally not a rewind point: resuming a snapshot from the
    // matching full-output phase keeps rewind presentation coherent. Check before advancing the
    // six-frame cadence so sustained every-other-frame suppression cannot phase-lock captures.
    if (!gameboy.isCurrentVisibleFrameFullyRendering) return
    selectHistory(HistoryKind.MACHINE)
    if (frameCounter++ % RECORD_INTERVAL != 0) {
      return
    }
    if (states.size == capacity) {
      states.removeFirst()
    }
    val entry = MachineEntry(MachineSnapshot.capture(gameboy, states.lastOrNull()?.machine))
    states.addLast(entry)
    approximateRetainedBytes =
        saturatingAdd(approximateRetainedBytes, approximateCaptureBytes(entry))
    captureCount++
    enforceMemoryBudget()
  }

  /** Captures machine plus active serial-endpoint state at the controller frame safe point. */
  fun record(session: Session) {
    if (!enabled) return
    if (!session.gameboy.isCurrentVisibleFrameFullyRendering) return
    selectHistory(HistoryKind.SESSION)
    if (frameCounter++ % RECORD_INTERVAL != 0) return
    if (states.size == capacity) states.removeFirst()
    val previous = (states.lastOrNull() as? SessionEntry)?.snapshot
    val entry = SessionEntry(SessionSnapshot.capture(session, previous))
    states.addLast(entry)
    approximateRetainedBytes =
        saturatingAdd(approximateRetainedBytes, approximateCaptureBytes(entry))
    captureCount++
    enforceMemoryBudget()
  }

  /** Restores the most recent recorded state; returns false when the history is empty. */
  fun rewindOneStep(gameboy: Gameboy): Boolean {
    if (!enabled) return false
    if (historyKind == HistoryKind.SESSION) {
      throw IllegalStateException("Session rewind history requires rewindOneStep(Session)")
    }
    val state = states.removeLastOrNull() as? MachineEntry ?: return false
    state.machine.restore(gameboy)
    gameboy.resumeFullFrameRenderingAfterRewindRestore()
    return true
  }

  /** Restores machine and serial endpoint state, cancelling live endpoint work before mutation. */
  fun rewindOneStep(session: Session): Boolean {
    if (!enabled) return false
    if (historyKind == HistoryKind.MACHINE) {
      throw IllegalStateException("Machine-only rewind history cannot restore a Session")
    }
    val state = states.removeLastOrNull() as? SessionEntry ?: return false
    state.snapshot.restore(session)
    session.gameboy.resumeFullFrameRenderingAfterRewindRestore()
    return true
  }

  fun clear() {
    states.clear()
    frameCounter = 0
    approximateRetainedBytes = 0
    historyKind = null
  }

  internal val historySize: Int
    get() = states.size

  internal fun snapshotsForTesting(): List<MachineSnapshot> = states.map(RewindEntry::machine)

  internal fun retainedBytesForTesting(): Long =
      retainedBytes(states.toList())

  private fun selectHistory(requested: HistoryKind) {
    if (historyKind != null && historyKind != requested) clear()
    historyKind = requested
  }

  private fun enforceMemoryBudget() {
    if (approximateRetainedBytes <= memoryBudgetBytes) return
    val snapshots = states.toList()
    var retained = retainedBytes(snapshots)
    if (retained > memoryBudgetBytes && snapshots.size > 1) {
      // Retention is monotonic for suffixes. Locate the smallest eviction in logarithmic exact
      // scans instead of repeatedly traversing the entire graph once per removed snapshot.
      var minimumRemoval = 1
      var maximumRemoval = snapshots.lastIndex
      while (minimumRemoval < maximumRemoval) {
        val candidate = minimumRemoval + (maximumRemoval - minimumRemoval) / 2
        val candidateBytes =
            retainedBytes(snapshots.subList(candidate, snapshots.size))
        if (candidateBytes <= memoryBudgetBytes) {
          maximumRemoval = candidate
        } else {
          minimumRemoval = candidate + 1
        }
      }
      repeat(minimumRemoval) { states.removeFirst() }
      budgetEvictionCount += minimumRemoval
      retained = retainedBytes(states.toList())
    }
    // Reset drift to the exact retained graph after the exceptional budget path.
    approximateRetainedBytes = retained
  }

  private fun retainedBytes(entries: List<RewindEntry>): Long =
      when (historyKind) {
        HistoryKind.SESSION ->
            SessionSnapshot.retainedBytes(entries.map { (it as SessionEntry).snapshot })
        HistoryKind.MACHINE, null ->
            MachineSnapshot.retainedStats(entries.map(RewindEntry::machine)).modeledRetainedBytes
      }

  private fun approximateCaptureBytes(entry: RewindEntry): Long {
    val stats = entry.machine.captureStats
    // A copied payload page has an aligned array/object header. Value nodes vary by type; 256
    // bytes per new node intentionally overestimates the current bounded snapshot graph.
    val pageBytes =
        saturatingAdd(stats.copiedPageBytes, stats.copiedPages.toLong() * PAGE_OVERHEAD_BYTES)
    val nodeBytes = stats.newValueNodes.toLong() * CONSERVATIVE_VALUE_NODE_BYTES
    return saturatingAdd(
        entry.auxiliaryCaptureBytes,
        saturatingAdd(SNAPSHOT_OVERHEAD_BYTES, saturatingAdd(pageBytes, nodeBytes)),
    )
  }

  private fun saturatingAdd(left: Long, right: Long): Long =
      if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

  companion object {
    const val RECORD_INTERVAL = 6

    const val MIN_DURATION_SECONDS = 5
    const val DEFAULT_DURATION_SECONDS = 30
    const val MAX_DURATION_SECONDS = 120

    const val MIN_MEMORY_BUDGET_BYTES = 8L * 1024L * 1024L
    const val DEFAULT_MEMORY_BUDGET_BYTES = 64L * 1024L * 1024L

    /** Retained for source/API compatibility; this is the default 30-second capacity. */
    const val CAPACITY = 300

    private const val APPROXIMATE_FRAMES_PER_SECOND = 60
    private const val PAGE_OVERHEAD_BYTES = 32L
    private const val CONSERVATIVE_VALUE_NODE_BYTES = 256L
    private const val SNAPSHOT_OVERHEAD_BYTES = 256L
  }
}
