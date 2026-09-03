package eu.rekawek.coffeegb.controller

import com.google.common.annotations.VisibleForTesting
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.controller.state.MachineSnapshot
import eu.rekawek.coffeegb.controller.state.SessionSnapshot

/**
 * Rolling history of emulation states for the rewind feature. A state is recorded every
 * [RECORD_INTERVAL] frames. Both duration and retained-memory limits are user-configurable.
 * Rewinding restores one recorded state per rendered frame, so it plays backwards at
 * [RECORD_INTERVAL] times the game speed.
 */
internal open class RewindManager(
    val enabled: Boolean = true,
    durationSeconds: Int = DEFAULT_DURATION_SECONDS,
    private val memoryBudgetBytes: Long = DEFAULT_MEMORY_BUDGET_BYTES,
) {

  private sealed interface RewindEntry {
    val machine: MachineSnapshot
  }

  private data class MachineEntry(
      override val machine: MachineSnapshot,
  ) : RewindEntry

  private data class SessionEntry(
      val snapshot: SessionSnapshot,
  ) : RewindEntry {
    override val machine: MachineSnapshot = snapshot.machine
  }

  private enum class HistoryKind {
    MACHINE,
    SESSION,
  }

  private val states = ArrayDeque<RewindEntry>()

  private var machineRetention = MachineSnapshot.RetentionLedger()

  private var sessionRetention = SessionSnapshot.RetentionLedger()

  private var historyKind: HistoryKind? = null

  private val capacity =
      Math.multiplyExact(durationSeconds, APPROXIMATE_FRAMES_PER_SECOND) / RECORD_INTERVAL

  private var frameCounter = 0

  /**
   * Incremental baseline captured while a fully initialized candidate is still staged. It is not
   * a rewind entry: the first post-frame session capture consumes it only as an immutable sharing
   * source, then becomes the first history entry in its own right.
   */
  private var preparedSessionSeed: SessionSnapshot? = null

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
    // A session seed cannot describe a machine-only recording path. Discard it before the
    // suppressed-frame return as well: machine and session histories must never share it.
    discardPreparedSessionSeed()
    // A suppressed frame is intentionally not a rewind point: resuming a snapshot from the
    // matching full-output phase keeps rewind presentation coherent. Check before advancing the
    // six-frame cadence so sustained every-other-frame suppression cannot phase-lock captures.
    if (!gameboy.isCurrentVisibleFrameFullyRendering) return
    selectHistory(HistoryKind.MACHINE)
    if (frameCounter % RECORD_INTERVAL != 0) {
      frameCounter++
      return
    }
    val entry = MachineEntry(MachineSnapshot.capture(gameboy, states.lastOrNull()?.machine))
    addLastEntry(entry)
    if (states.size > capacity) removeFirstEntry()
    frameCounter++
    captureCount++
    enforceMemoryBudget()
  }

  /** Captures machine plus active serial-endpoint state at the controller frame safe point. */
  fun record(session: Session) {
    if (!enabled) return
    if (!session.gameboy.isCurrentVisibleFrameFullyRendering) return
    selectHistory(HistoryKind.SESSION)
    if (frameCounter % RECORD_INTERVAL != 0) {
      frameCounter++
      return
    }
    // Capture before mutating cadence, retained history, or the staged seed. A capture failure
    // therefore leaves the candidate baseline available for the next successful frame.
    val seed = preparedSessionSeed
    val previous = seed ?: (states.lastOrNull() as? SessionEntry)?.snapshot
    val entry = SessionEntry(SessionSnapshot.capture(session, previous))
    addLastEntry(entry)
    // Add the seed-relative entry before releasing the seed. Shared graph references must remain
    // continuously owned while the transient baseline changes into rewindable history.
    if (seed != null) {
      sessionRetention.remove(seed)
    }
    if (states.size > capacity) removeFirstEntry()
    frameCounter++
    preparedSessionSeed = null
    captureCount++
    enforceMemoryBudget()
  }

  /** Restores the most recent recorded state; returns false when the history is empty. */
  fun rewindOneStep(gameboy: Gameboy): Boolean {
    if (!enabled) return false
    if (historyKind == HistoryKind.SESSION) {
      throw IllegalStateException("Session rewind history requires rewindOneStep(Session)")
    }
    val state = removeLastEntryOrNull() as? MachineEntry ?: return false
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
    val state = removeLastEntryOrNull() as? SessionEntry ?: return false
    state.snapshot.restore(session)
    session.gameboy.resumeFullFrameRenderingAfterRewindRestore()
    return true
  }

  fun clear() {
    states.clear()
    frameCounter = 0
    historyKind = null
    preparedSessionSeed = null
    machineRetention = MachineSnapshot.RetentionLedger()
    sessionRetention = SessionSnapshot.RetentionLedger()
  }

  /**
   * Captures an incremental baseline for a fully constructed but not yet committed session.
   * Preparation deliberately has no history, cadence, or capture-count side effects.
   */
  internal open fun prepareSessionSeed(session: Session): PreparedSessionSeed? {
    if (!enabled) return null
    return PreparedSessionSeed(this, session, SessionSnapshot.capture(session))
  }

  /**
   * Starts a new session history at the ownership boundary. The prepared baseline remains hidden
   * until the first successful post-frame [record] uses it as its incremental predecessor.
   */
  internal fun beginSession(session: Session, seed: PreparedSessionSeed?) {
    clear()
    if (!enabled) {
      seed?.discard()
      return
    }
    preparedSessionSeed = seed?.take(this, session)
    // The manager owns this transient snapshot until the first real frame consumes it, even though
    // it is not itself rewindable history.
    preparedSessionSeed?.let(sessionRetention::add)
  }

  internal val historySize: Int
    get() = states.size

  internal fun snapshotsForTesting(): List<MachineSnapshot> = states.map(RewindEntry::machine)

  internal fun retainedBytesForTesting(): Long = retainedBytes()

  @VisibleForTesting
  internal fun retainedBytesByScanForTesting(): Long {
    val seed = preparedSessionSeed
    return when {
      seed != null -> {
        val snapshots = ArrayList<SessionSnapshot>(states.size + 1)
        snapshots += seed
        states.forEach { snapshots += (it as SessionEntry).snapshot }
        SessionSnapshot.retainedBytes(snapshots)
      }
      historyKind == HistoryKind.SESSION ->
          SessionSnapshot.retainedBytes(states.map { (it as SessionEntry).snapshot })
      else ->
          MachineSnapshot.retainedStats(states.map(RewindEntry::machine)).modeledRetainedBytes
    }
  }

  @VisibleForTesting
  internal val hasPreparedSessionSeed: Boolean
    get() = preparedSessionSeed != null

  private fun selectHistory(requested: HistoryKind) {
    if (historyKind != null && historyKind != requested) clear()
    historyKind = requested
  }

  private fun discardPreparedSessionSeed() {
    val seed = preparedSessionSeed ?: return
    sessionRetention.remove(seed)
    preparedSessionSeed = null
  }

  /** Opaque ownership token for a candidate-only snapshot baseline. */
  internal class PreparedSessionSeed private constructor(
      private val owner: RewindManager,
      private val session: Session,
      private var snapshot: SessionSnapshot?,
  ) {

    internal fun take(owner: RewindManager, session: Session): SessionSnapshot? {
      if (this.owner !== owner || this.session !== session) {
        discard()
        return null
      }
      return snapshot.also { snapshot = null }
    }

    internal fun discard() {
      snapshot = null
    }

    companion object {
      operator fun invoke(
          owner: RewindManager,
          session: Session,
          snapshot: SessionSnapshot,
      ): PreparedSessionSeed = PreparedSessionSeed(owner, session, snapshot)
    }
  }

  private fun enforceMemoryBudget() {
    var removed = 0
    while (retainedBytes() > memoryBudgetBytes && states.size > 1) {
      removeFirstEntry()
      removed++
    }
    budgetEvictionCount += removed
  }

  private fun addLastEntry(entry: RewindEntry) {
    when (entry) {
      is MachineEntry -> machineRetention.add(entry.machine)
      is SessionEntry -> sessionRetention.add(entry.snapshot)
    }
    states.addLast(entry)
  }

  private fun removeFirstEntry(): RewindEntry {
    val entry = states.first()
    release(entry)
    return states.removeFirst()
  }

  private fun removeLastEntryOrNull(): RewindEntry? {
    val entry = states.lastOrNull() ?: return null
    release(entry)
    return states.removeLast()
  }

  private fun release(entry: RewindEntry) {
    when (entry) {
      is MachineEntry -> machineRetention.remove(entry.machine)
      is SessionEntry -> sessionRetention.remove(entry.snapshot)
    }
  }

  private fun retainedBytes(): Long =
      Math.addExact(machineRetention.modeledRetainedBytes, sessionRetention.retainedBytes)

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
  }
}
