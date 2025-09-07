package eu.rekawek.coffeegb.swing.session

import eu.rekawek.coffeegb.Gameboy

class TimingTicker : Runnable {
  private var lastSleep = System.nanoTime()
  private var ticks: Long = 0

  override fun run() {
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
