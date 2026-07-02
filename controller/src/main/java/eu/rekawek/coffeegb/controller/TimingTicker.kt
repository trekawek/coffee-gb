package eu.rekawek.coffeegb.controller

import com.google.common.annotations.VisibleForTesting
import eu.rekawek.coffeegb.core.Gameboy
import java.util.concurrent.locks.LockSupport

class TimingTicker : Runnable {
  private var deadline = System.nanoTime()
  private var ticks: Long = 0
  @VisibleForTesting
  var disabled = false

  override fun run() {
    if (disabled) {
      return
    }
    if (++ticks < Gameboy.TICKS_PER_FRAME) {
      return
    }
    ticks = 0
    deadline += FRAME_DURATION_NANOS
    val now = System.nanoTime()
    if (deadline < now - FRAME_DURATION_NANOS) {
      // fell more than a frame behind (paused, breakpoint, slow host): don't try to
      // catch up by running ahead, just re-anchor
      deadline = now
      return
    }
    // sleep the bulk of the wait and busy-spin only the last stretch: parkNanos wakes
    // with millisecond-ish slack depending on the OS timer, the spin gives frame-exact
    // pacing without pegging a core for the whole frame
    while (true) {
      val remaining = deadline - System.nanoTime()
      if (remaining <= 0) {
        break
      }
      if (remaining > SPIN_THRESHOLD_NANOS) {
        LockSupport.parkNanos(remaining - SPIN_THRESHOLD_NANOS)
      } else {
        Thread.onSpinWait()
      }
    }
  }

  private companion object {
    const val FRAME_DURATION_NANOS = 1_000_000_000L / 60
    const val SPIN_THRESHOLD_NANOS = 1_500_000L
  }
}
