package eu.rekawek.coffeegb.controller

import com.google.common.annotations.VisibleForTesting
import eu.rekawek.coffeegb.core.Gameboy

class TimingTicker : Runnable {
  private var lastSleep = System.nanoTime()
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
    while (System.nanoTime() - lastSleep < FRAME_DURATION_NANOS) {}
    lastSleep = System.nanoTime()
  }

  private companion object {
    const val FRAME_DURATION_NANOS = 1000000000 / 60
  }
}
