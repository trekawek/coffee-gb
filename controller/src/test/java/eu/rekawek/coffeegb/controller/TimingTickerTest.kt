package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.hardware.ClockSpec
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import org.junit.Test

class TimingTickerTest {

  @Test
  fun `controller pacing boundary uses supplied session clock`() {
    val now = AtomicLong(0)
    val parked = mutableListOf<Long>()
    val ticker = TimingTicker({ now.addAndGet(1_000_000_000L) }, parked::add)
    val custom = ClockSpec(1_000, 10, 1)

    repeat(custom.controllerTicksPerFrame() - 1) { ticker.run(custom) }
    assertEquals(0, ticker.completedFrames)
    ticker.run(custom)

    assertEquals(1, ticker.completedFrames)
    assertEquals(emptyList(), parked)
  }

  @Test
  fun `changing clock discards an incomplete prior frame`() {
    val now = AtomicLong(0)
    val ticker = TimingTicker({ now.addAndGet(1_000_000_000L) }, {})
    val first = ClockSpec(1_000, 10, 1)
    val second = ClockSpec(2_000, 10, 1)

    repeat(99) { ticker.run(first) }
    repeat(199) { ticker.run(second) }
    assertEquals(0, ticker.completedFrames)
    ticker.run(second)
    assertEquals(1, ticker.completedFrames)
  }
}
