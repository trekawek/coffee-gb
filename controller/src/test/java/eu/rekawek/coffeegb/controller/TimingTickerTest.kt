package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.controller.state.StateCodec
import eu.rekawek.coffeegb.controller.state.StateCodecTestSupport
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.hardware.ClockSpec
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertContentEquals
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

  @Test
  fun `whole frame pacing shares deadlines with tick pacing`() {
    fun parks(useWholeFrameCall: Boolean): List<Long> {
      val now = AtomicLong(0)
      val parked = mutableListOf<Long>()
      val ticker =
          TimingTicker(
              { now.addAndGet(100_000L) },
              { duration ->
                parked += duration
                now.addAndGet(duration)
              },
          )
      val clock = ClockSpec(6_000, 60, 1)
      repeat(4) {
        if (useWholeFrameCall) {
          ticker.runFrame(clock)
        } else {
          repeat(clock.controllerTicksPerFrame()) { ticker.run(clock) }
        }
      }
      assertEquals(4, ticker.completedFrames)
      return parked
    }

    assertEquals(parks(false), parks(true))
  }

  @Test
  fun `twenty millisecond pacing debt is repaid before pacing resumes`() {
    val now = AtomicLong(0)
    val parked = mutableListOf<Long>()
    val ticker =
        TimingTicker(
            { now.addAndGet(1_000_000L) },
            { duration ->
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
    assertEquals(1, parksBeforeDelay)

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
            { now.addAndGet(1_000_000L) },
            { duration ->
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
    assertEquals(1, parksBeforeDelay)

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
        val ticker = TimingTicker({ now.addAndGet(hostStep) }, {})
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
}
