package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.memento.Memento

/**
 * Rolling history of emulation states for the rewind feature. A state is recorded every
 * [RECORD_INTERVAL] frames and the buffer holds [CAPACITY] entries, giving about
 * CAPACITY * RECORD_INTERVAL / 60 seconds of history. Rewinding restores one recorded
 * state per rendered frame, so it plays backwards at RECORD_INTERVAL times the game speed.
 */
class RewindManager {

  private val states = ArrayDeque<State>()

  private var frameCounter = 0

  fun record(gameboy: Gameboy, achievementProgress: () -> ByteArray? = { null }) {
    if (frameCounter++ % RECORD_INTERVAL != 0) {
      return
    }
    if (states.size == CAPACITY) {
      states.removeFirst()
    }
    states.addLast(State(gameboy.saveToMemento(), achievementProgress()))
  }

  /** Restores the most recent recorded state; returns false when the history is empty. */
  fun rewindOneStep(
      gameboy: Gameboy,
      restoreAchievementProgress: (ByteArray?) -> Unit = {},
  ): Boolean {
    val state = states.removeLastOrNull() ?: return false
    gameboy.restoreFromMemento(state.gameboy)
    restoreAchievementProgress(state.achievementProgress)
    return true
  }

  fun clear() {
    states.clear()
    frameCounter = 0
  }

  private data class State(
      val gameboy: Memento<Gameboy>,
      val achievementProgress: ByteArray?,
  )

  companion object {
    const val RECORD_INTERVAL = 6

    const val CAPACITY = 300
  }
}
