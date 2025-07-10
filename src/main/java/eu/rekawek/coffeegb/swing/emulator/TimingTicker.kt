package eu.rekawek.coffeegb.swing.emulator

import eu.rekawek.coffeegb.Gameboy
import kotlin.concurrent.Volatile

class TimingTicker : Runnable {
  private var lastSleep = System.nanoTime()
  private var ticks: Long = 0

  @Volatile private var delayEnabled = true

  override fun run() {
    if (++ticks < TICKS_PER_PERIOD) {
      return
    }
    ticks = 0
    if (delayEnabled) {
      while (System.nanoTime() - lastSleep < PERIOD_IN_NANOS) {}
    }
    lastSleep = System.nanoTime()
  }

  fun setDelayEnabled(delayEnabled: Boolean) {
    this.delayEnabled = delayEnabled
  }

  private companion object {
    const val PERIODS_PER_SECOND: Long = 65536
    const val TICKS_PER_PERIOD = Gameboy.TICKS_PER_SEC / PERIODS_PER_SECOND
    const val PERIOD_IN_NANOS = 1000000000 / PERIODS_PER_SECOND
  }
}
