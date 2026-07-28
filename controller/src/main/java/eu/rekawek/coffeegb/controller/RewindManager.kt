package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.controller.state.MachineSnapshot

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

  private val states = ArrayDeque<MachineSnapshot>()

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
    if (frameCounter++ % RECORD_INTERVAL != 0) {
      return
    }
    if (states.size == capacity) {
      states.removeFirst()
    }
    val snapshot = MachineSnapshot.capture(gameboy, states.lastOrNull())
    states.addLast(snapshot)
    approximateRetainedBytes =
        saturatingAdd(approximateRetainedBytes, approximateCaptureBytes(snapshot))
    captureCount++
    enforceMemoryBudget()
  }

  /** Restores the most recent recorded state; returns false when the history is empty. */
  fun rewindOneStep(gameboy: Gameboy): Boolean {
    if (!enabled) return false
    val state = states.removeLastOrNull() ?: return false
    state.restore(gameboy)
    return true
  }

  fun clear() {
    states.clear()
    frameCounter = 0
    approximateRetainedBytes = 0
  }

  internal val historySize: Int
    get() = states.size

  internal fun snapshotsForTesting(): List<MachineSnapshot> = states.toList()

  internal fun retainedBytesForTesting(): Long =
      MachineSnapshot.retainedStats(states).modeledRetainedBytes

  private fun enforceMemoryBudget() {
    if (approximateRetainedBytes <= memoryBudgetBytes) return
    val snapshots = states.toList()
    var retained = MachineSnapshot.retainedStats(snapshots).modeledRetainedBytes
    if (retained > memoryBudgetBytes && snapshots.size > 1) {
      // Retention is monotonic for suffixes. Locate the smallest eviction in logarithmic exact
      // scans instead of repeatedly traversing the entire graph once per removed snapshot.
      var minimumRemoval = 1
      var maximumRemoval = snapshots.lastIndex
      while (minimumRemoval < maximumRemoval) {
        val candidate = minimumRemoval + (maximumRemoval - minimumRemoval) / 2
        val candidateBytes =
            MachineSnapshot.retainedStats(
                    snapshots.subList(candidate, snapshots.size))
                .modeledRetainedBytes
        if (candidateBytes <= memoryBudgetBytes) {
          maximumRemoval = candidate
        } else {
          minimumRemoval = candidate + 1
        }
      }
      repeat(minimumRemoval) { states.removeFirst() }
      budgetEvictionCount += minimumRemoval
      retained = MachineSnapshot.retainedStats(states).modeledRetainedBytes
    }
    // Reset drift to the exact retained graph after the exceptional budget path.
    approximateRetainedBytes = retained
  }

  private fun approximateCaptureBytes(snapshot: MachineSnapshot): Long {
    val stats = snapshot.captureStats
    // A copied payload page has an aligned array/object header. Value nodes vary by type; 256
    // bytes per new node intentionally overestimates the current bounded snapshot graph.
    val pageBytes =
        saturatingAdd(stats.copiedPageBytes, stats.copiedPages.toLong() * PAGE_OVERHEAD_BYTES)
    val nodeBytes = stats.newValueNodes.toLong() * CONSERVATIVE_VALUE_NODE_BYTES
    return saturatingAdd(SNAPSHOT_OVERHEAD_BYTES, saturatingAdd(pageBytes, nodeBytes))
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
