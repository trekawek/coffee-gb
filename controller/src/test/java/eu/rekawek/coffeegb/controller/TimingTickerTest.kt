package eu.rekawek.coffeegb.controller

import com.sun.management.ThreadMXBean
import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.hardware.ClockSpec
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongConsumer
import java.util.function.LongSupplier
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class TimingTickerTest {

  @Test
  fun `frame entry accounts for one complete cadence`() {
    val now = AtomicLong(0)
    val ticker = TimingTicker(
        LongSupplier { now.addAndGet(1_000_000_000L) },
        LongConsumer {},
    )
    val clock = ClockSpec(1_000, 10, 1)

    repeat(3) { ticker.runFrame(clock) }

    assertEquals(3, ticker.completedFrames)
    kotlin.test.assertTrue(ticker.hasPacingDebt)
  }

  @Test
  fun `frame entry resets an incomplete tick cadence`() {
    val now = AtomicLong(0)
    val ticker = TimingTicker(
        LongSupplier { now.addAndGet(1_000_000_000L) },
        LongConsumer {},
    )
    val clock = ClockSpec(1_000, 10, 1)

    repeat(5) { ticker.run(clock) }
    ticker.runFrame(clock)
    repeat(clock.controllerTicksPerFrame() - 1) { ticker.run(clock) }
    assertEquals(1, ticker.completedFrames)
    ticker.run(clock)
    assertEquals(2, ticker.completedFrames)
  }

  @Test
  fun `controller pacing boundary uses supplied session clock`() {
    val now = AtomicLong(0)
    val parked = mutableListOf<Long>()
    val ticker = TimingTicker(
        LongSupplier { now.addAndGet(1_000_000_000L) },
        LongConsumer { parked.add(it) },
    )
    val custom = ClockSpec(1_000, 10, 1)

    repeat(custom.controllerTicksPerFrame() - 1) { ticker.run(custom) }
    assertEquals(0, ticker.completedFrames)
    kotlin.test.assertFalse(ticker.hasPacingDebt)
    ticker.run(custom)

    assertEquals(1, ticker.completedFrames)
    kotlin.test.assertTrue(ticker.hasPacingDebt)
    assertEquals(emptyList(), parked)
  }

  @Test
  fun `changing clock discards an incomplete prior frame`() {
    val now = AtomicLong(0)
    val ticker = TimingTicker(
        LongSupplier { now.addAndGet(1_000_000_000L) },
        LongConsumer {},
    )
    val first = ClockSpec(1_000, 10, 1)
    val second = ClockSpec(2_000, 10, 1)

    repeat(99) { ticker.run(first) }
    repeat(199) { ticker.run(second) }
    assertEquals(0, ticker.completedFrames)
    kotlin.test.assertFalse(ticker.hasPacingDebt)
    ticker.run(second)
    assertEquals(1, ticker.completedFrames)
  }

  @Test
  fun `benchmark reset clears warmup debt and starts a fresh cadence`() {
    val now = AtomicLong(0)
    val ticker = TimingTicker(
        LongSupplier { now.addAndGet(1_000_000_000L) },
        LongConsumer {},
    )
    val clock = ClockSpec(1_000, 10, 1)

    ticker.runFrame(clock)
    assertEquals(1, ticker.completedFrames)
    assertTrue(ticker.hasPacingDebt)

    ticker.resetForBenchmark()

    assertEquals(0, ticker.completedFrames)
    kotlin.test.assertFalse(ticker.hasPacingDebt)
    repeat(clock.controllerTicksPerFrame() - 1) { ticker.run(clock) }
    assertEquals(0, ticker.completedFrames)
    ticker.run(clock)
    assertEquals(1, ticker.completedFrames)
  }

  @Test
  fun `twenty millisecond pacing debt is repaid before pacing resumes`() {
    val now = AtomicLong(0)
    val parked = mutableListOf<Long>()
    val ticker =
        TimingTicker(
            LongSupplier { now.addAndGet(1_000_000L) },
            LongConsumer { duration ->
              parked += duration
              now.addAndGet(duration)
            },
        )
    val clock = ClockSpec(6_000, 60, 1)

    fun runFrame() {
      repeat(clock.controllerTicksPerFrame()) { ticker.run(clock) }
    }

    runFrame()
    val parksBeforeDelay = parked.size
    assertEquals(2, parksBeforeDelay)

    // One frame interval plus host delay leaves roughly 20 ms of debt after the next
    // frame deadline is advanced. The old one-frame re-anchor discarded this debt.
    now.addAndGet(37_000_000L)
    repeat(2) { runFrame() }
    assertEquals(
        parksBeforeDelay,
        parked.size,
        "recoverable pacing debt must run both catch-up frames without being discarded",
    )

    repeat(3) { runFrame() }
    kotlin.test.assertTrue(
        parked.size > parksBeforeDelay,
        "repaid short debt must return to ordinary pacing",
    )
  }

  @Test
  fun `hundred millisecond host delay retains only bounded catch-up debt`() {
    val now = AtomicLong(0)
    val parked = mutableListOf<Long>()
    val ticker =
        TimingTicker(
            LongSupplier { now.addAndGet(1_000_000L) },
            LongConsumer { duration ->
              parked += duration
              now.addAndGet(duration)
            },
        )
    val clock = ClockSpec(6_000, 60, 1)

    fun runFrame() {
      repeat(clock.controllerTicksPerFrame()) { ticker.run(clock) }
    }

    runFrame()
    val parksBeforeDelay = parked.size
    assertEquals(2, parksBeforeDelay)

    now.addAndGet(100_000_000L)
    repeat(3) { runFrame() }
    assertEquals(
        parksBeforeDelay,
        parked.size,
        "the clamped debt must allow immediate catch-up",
    )

    repeat(3) { runFrame() }
    kotlin.test.assertTrue(
        parked.size > parksBeforeDelay,
        "the 50 ms cap must return to pacing within a bounded number of frames",
    )
  }

  @Test
  fun `host pacing and pause reanchoring never advance emulated sgb2 state`() {
    fun runWithHostStep(hostStep: Long): ByteArray {
      val configuration =
          StateCodecTestSupport.configuration(
                  StateCodecTestSupport.rom(sgb = true),
                  GameboyType.SGB,
              )
              .setHardwareProfile(HardwareProfileRegistry.SGB2)
      return StateCodecTestSupport.session(configuration).use { session ->
        val now = AtomicLong(0)
        val ticker = TimingTicker(
            LongSupplier { now.addAndGet(hostStep) },
            LongConsumer {},
        )
        val clock = session.gameboy.clockSpec
        repeat(clock.controllerTicksPerFrame()) {
          session.gameboy.tick()
          ticker.run(clock)
        }
        val atFrame = StateCodec.encode(StateCodec.capture(session))

        // A long fake host pause invokes pacing/re-anchor logic only. No emulator tick is hidden
        // inside TimingTicker, so the complete detached machine remains byte-identical.
        now.addAndGet(60_000_000_000L)
        repeat(clock.controllerTicksPerFrame()) { ticker.run(clock) }
        assertContentEquals(atFrame, StateCodec.encode(StateCodec.capture(session)))
        atFrame
      }
    }

    assertContentEquals(runWithHostStep(17_000_000L), runWithHostStep(91_000_000L))
  }

  @Test
  fun `primitive timing seams do not allocate inside repeated fine waits`() {
    val allocation = threadAllocation() ?: return
    val seam = FineWaitSeam()
    val ticker = TimingTicker(seam, seam)
    val clock = ClockSpec(1, 1, 1)

    repeat(2_000) { ticker.run(clock) }
    assertEquals(4_000, seam.parkCalls)
    assertTrue(
        seam.nanoTimeCalls <= seam.parkCalls * 3 + 4,
        "the deterministic clock must not force a scheduler-polling storm",
    )

    var minimumAllocated = Long.MAX_VALUE
    repeat(5) {
      val before = allocation.current()
      repeat(2_000) { ticker.run(clock) }
      minimumAllocated = minOf(minimumAllocated, allocation.current() - before)
    }

    assertEquals(0L, minimumAllocated, "primitive fine-wait seam allocated")
  }

  private fun threadAllocation(): AllocationCounter? {
    val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return null
    if (!bean.isThreadAllocatedMemorySupported) return null
    if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
    return AllocationCounter(bean, Thread.currentThread().id)
  }

  private data class AllocationCounter(
      val bean: ThreadMXBean,
      val threadId: Long,
  ) {
    fun current(): Long = bean.getThreadAllocatedBytes(threadId)
  }

  private class FineWaitSeam : LongSupplier, LongConsumer {
    private var now = 0L
    var nanoTimeCalls = 0
      private set
    var parkCalls = 0
      private set
    var lastParkNanos = 0L
      private set

    override fun getAsLong(): Long {
      nanoTimeCalls++
      now += 100_000L
      return now
    }

    override fun accept(duration: Long) {
      parkCalls++
      lastParkNanos = duration
      now += duration
    }
  }

}
