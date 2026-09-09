package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.Gameboy

/** Bounded owner-thread sample state, allocated only for an explicitly enabled soak. */
internal class PerformanceSoakSampler(private val nanoTime: () -> Long = System::nanoTime) {
  private var generation = Long.MIN_VALUE
  private var published = 0L
  private var masterTicks = 0L
  private var nativeBase = 0L
  private var renderedBase = 0L
  private var suppressedBase = 0L
  private val work = LongArray(120)
  private var workIndex = 0
  private var workCount = 0

  fun beginFrame(sessionGeneration: Long, gameboy: Gameboy) {
    if (sessionGeneration == generation) return
    generation = sessionGeneration
    published = nanoTime()
    masterTicks = 0
    nativeBase = gameboy.performanceNativeFrames
    renderedBase = gameboy.performanceRenderedFrames
    suppressedBase = gameboy.performanceSuppressedFrames
    workIndex = 0
    workCount = 0
  }

  fun record(
      gameboy: Gameboy,
      ticks: Int,
      workNanos: Long,
      pacingDebt: Boolean,
  ): Controller.PerformanceSoakSampleEvent? {
    masterTicks = Math.addExact(masterTicks, ticks.toLong())
    if (ticks > 0) {
      work[workIndex] = workNanos
      workIndex = (workIndex + 1) % work.size
      workCount = minOf(workCount + 1, work.size)
    }
    val now = nanoTime()
    if (now - published < 1_000_000_000L) return null
    published = now
    val sorted = work.copyOf(workCount).also { it.sort() }
    val clock = gameboy.clockSpec
    return Controller.PerformanceSoakSampleEvent(
        generation,
        now,
        masterTicks,
        gameboy.performanceNativeFrames - nativeBase,
        gameboy.performanceRenderedFrames - renderedBase,
        gameboy.performanceSuppressedFrames - suppressedBase,
        if (workCount == 0) 0 else sorted[(workCount * 95 + 99) / 100 - 1],
        sorted.lastOrNull() ?: 0,
        pacingDebt,
        clock.ticksPerSecondNumerator(),
        clock.ticksPerSecondDenominator(),
        gameboy.hardwareProfile.id(),
        gameboy.speedMode.speedMode,
        gameboy.executionMode.name,
        gameboy.speedMode.isDmgCompat,
    )
  }
}
