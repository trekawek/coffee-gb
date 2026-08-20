package eu.rekawek.coffeegb.controller.replay

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.ExecutionMode
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource
import eu.rekawek.coffeegb.core.serial.ByteReceivingSerialEndpoint
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayRuntimeTest {

  @Test
  fun sessionInstallsTheOptionalIsolatedSerialEndpoint() {
    val endpoint = ByteReceivingSerialEndpoint {}

    ReplayRuntime.session(
            configuration(),
            restoreImmediately = true,
            serialEndpoint = endpoint,
        )
        .use { session ->
          assertSame(endpoint, session.serialEndpoint)
        }
  }

  @Test
  fun sessionDefaultsToAnEmptyLinkPort() {
    ReplayRuntime.session(configuration(), restoreImmediately = false).use { session ->
      assertSame(SerialEndpoint.NULL_ENDPOINT, session.serialEndpoint)
    }
  }

  @Test
  fun `runtime preserves a selected mode and can force accuracy for replay`() {
    val performance = configuration().setExecutionMode(ExecutionMode.PERFORMANCE)
    val preserved =
        ReplayRuntime.configuration(performance, VirtualTimeSource(), ReplayInputSource())
    assertEquals(ExecutionMode.PERFORMANCE, preserved.executionMode)

    val reference =
        ReplayRuntime.configuration(
            performance,
            VirtualTimeSource(),
            ReplayInputSource(),
            executionMode = ExecutionMode.ACCURACY,
        )
    assertEquals(ExecutionMode.ACCURACY, reference.executionMode)
  }

  private fun configuration(): Gameboy.GameboyConfiguration =
      Gameboy.GameboyConfiguration(Rom(ReplayRecorderPlayerTest.syntheticRom()))
          .setSupportBatterySave(false)
}
