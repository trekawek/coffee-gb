package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.BardigunSerialEndpoint
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class BardigunStateTest {
  @Test
  fun portableStateRetainsPendingSwipeAndPartialSerialByte() {
    val endpoint = BardigunSerialEndpoint()
    val configuration = Gameboy.GameboyConfiguration(Rom(ByteArray(0x8000)))
        .setBootstrapMode(BootstrapMode.SKIP).setSupportBatterySave(false)
    Session(configuration, EventBusImpl(null, null, false), null, endpoint).use { session ->
      endpoint.scan("4902370501445")
      val pending = StateCodec.encode(StateCodec.capture(session))
      assertEquals(SerialPeripheralState.BARDIGUN, session.captureDetachedState().serialPeripheral)
      repeat(23) {
        endpoint.startSending()
        repeat(8) { endpoint.sendBit() }
      }
      endpoint.startSending()
      repeat(3) { endpoint.sendBit() }
      val partial = StateCodec.encode(StateCodec.capture(session))
      val expected = IntArray(100) { endpoint.sendBit() }
      StateCodec.decodeAndApply(partial, session)
      expected.forEach { assertEquals(it, endpoint.sendBit()) }
      endpoint.disconnect()
      StateCodec.decodeAndApply(pending, session)
      assertTrue(endpoint.isScanPending)
      endpoint.startSending()
      repeat(8) { assertEquals(1, endpoint.sendBit()) }
    }
  }
}
