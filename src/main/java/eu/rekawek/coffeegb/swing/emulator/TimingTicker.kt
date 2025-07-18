package eu.rekawek.coffeegb.swing.emulator

import eu.rekawek.coffeegb.Gameboy.TICKS_PER_FRAME
import kotlin.concurrent.Volatile

class TimingTicker : Runnable {
  private var lastSleep = System.nanoTime()
  private var ticks: Long = 0

  @Volatile private var delayEnabled = true

  override fun run() {
    if (++ticks < TICKS_PER_FRAME) {
      return
    }
    ticks = 0
    if (delayEnabled) {
      while (System.nanoTime() - lastSleep < FRAME_DURATION_NANOS) {}
    }
    lastSleep = System.nanoTime()
  }

  fun setDelayEnabled(delayEnabled: Boolean) {
    this.delayEnabled = delayEnabled
  }

  private companion object {
    const val FRAME_DURATION_NANOS = 1000000000 / 60
  }
}
