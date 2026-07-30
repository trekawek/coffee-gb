package eu.rekawek.coffeegb.controller.replay

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.ByteReceivingSerialEndpoint
import eu.rekawek.coffeegb.core.serial.SerialEndpoint
import org.junit.Assert.assertSame
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

  private fun configuration(): Gameboy.GameboyConfiguration =
      Gameboy.GameboyConfiguration(Rom(ReplayRecorderPlayerTest.syntheticRom()))
          .setSupportBatterySave(false)
}
