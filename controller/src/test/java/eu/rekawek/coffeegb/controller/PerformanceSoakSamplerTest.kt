package eu.rekawek.coffeegb.controller

import eu.rekawek.coffeegb.core.ExecutionMode
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.memory.cart.Rom
import org.junit.Assert.*
import org.junit.Test

class PerformanceSoakSamplerTest {
  @Test
  fun samplesRealProducedAndSuppressedFramesWithoutChangingEmulation() {
    var now = 0L
    val sampler = PerformanceSoakSampler { now }
    session().use { gameboy ->
      sampler.beginFrame(7, gameboy)
      gameboy.requestFrameRenderSuppression(true)
      gameboy.runTicks(3 * 70224)
      now = 1_000_000_000L
      val sample = checkNotNull(sampler.record(gameboy, 3 * 70224, 1000, true))
      assertEquals(3 * 70224L, sample.masterTicks)
      assertEquals(gameboy.performanceNativeFrames, sample.nativeFrames)
      assertTrue(sample.suppressedFrames > 0)
      assertEquals(sample.nativeFrames, sample.renderedFrames + sample.suppressedFrames)
      assertTrue(sample.pacingDebt)
      assertEquals("PERFORMANCE", sample.executionMode)
      assertEquals(4_194_304L, sample.clockNumerator)
      assertEquals(1L, sample.clockDenominator)
    }
  }

  @Test
  fun rollingWorkPercentilesAndGenerationResetAreBounded() {
    var now = 0L
    val sampler = PerformanceSoakSampler { now }
    session().use { gameboy ->
      sampler.beginFrame(1, gameboy)
      for (work in 1L..240L) assertNull(sampler.record(gameboy, 4, work, false))
      now = 1_000_000_000L
      val sample = checkNotNull(sampler.record(gameboy, 0, 0, false))
      assertEquals(960L, sample.masterTicks)
      assertEquals(234L, sample.workP95Nanos)
      assertEquals(240L, sample.workMaxNanos)
      sampler.beginFrame(2, gameboy)
      now = 2_000_000_000L
      val reset = checkNotNull(sampler.record(gameboy, 4, 7, false))
      assertEquals(2L, reset.sessionGeneration)
      assertEquals(4L, reset.masterTicks)
      assertEquals(7L, reset.workP95Nanos)
      assertEquals(0L, reset.nativeFrames)
    }
  }

  private fun session(): Gameboy {
    val bytes = ByteArray(32768)
    bytes[0x100] = 0xc3.toByte()
    bytes[0x101] = 0
    bytes[0x102] = 1
    return Gameboy.GameboyConfiguration(Rom(bytes))
        .setHardwareProfile(HardwareProfileRegistry.DMG)
        .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
        .setExecutionMode(ExecutionMode.PERFORMANCE)
        .setSupportBatterySave(false)
        .build()
  }
}
