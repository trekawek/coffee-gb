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
