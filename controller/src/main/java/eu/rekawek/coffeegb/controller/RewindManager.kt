package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.controller.state.MachineSnapshot

/**
 * Rolling history of emulation states for the rewind feature. A state is recorded every
 * [RECORD_INTERVAL] frames and the buffer holds [CAPACITY] entries, giving about
 * CAPACITY entries at one capture per RECORD_INTERVAL controller frames (30 seconds with the
 * current 60-Hz profile cadence). Rewinding restores one recorded
 * state per rendered frame, so it plays backwards at RECORD_INTERVAL times the game speed.
 */
internal class RewindManager(
    val enabled: Boolean = true,
) {

  private val states = ArrayDeque<MachineSnapshot>()

  private var frameCounter = 0

  internal var captureCount = 0
    private set

  fun record(gameboy: Gameboy) {
    // This is intentionally the first operation. A disabled manager does not advance cadence,
    // inspect the live machine, allocate a capture DTO, or create a snapshot.
    if (!enabled) return
    if (frameCounter++ % RECORD_INTERVAL != 0) {
      return
    }
    if (states.size == CAPACITY) {
      states.removeFirst()
    }
    states.addLast(MachineSnapshot.capture(gameboy, states.lastOrNull()))
    captureCount++
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
  }

  internal val historySize: Int
    get() = states.size

  internal fun snapshotsForTesting(): List<MachineSnapshot> = states.toList()

  companion object {
    const val RECORD_INTERVAL = 6

    const val CAPACITY = 300
  }
}
