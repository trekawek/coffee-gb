package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.TurboFileSerialEndpoint
import kotlin.test.*
import org.junit.Test

class TurboFileStateTest {
  @Test fun portableStateRetainsFlashSwitchesAndPartialClock() {
    for (advance in listOf(false, true)) {
      val device = TurboFileSerialEndpoint(advance)
      val config = Gameboy.GameboyConfiguration(Rom(ByteArray(0x8000)))
          .setBootstrapMode(Gameboy.BootstrapMode.SKIP).setSupportBatterySave(false)
      Session(config, EventBusImpl(null, null, false), null, device).use { session ->
        device.importImage(ByteArray(TurboFileSerialEndpoint.IMAGE_BYTES) { 0x35 }, true)
        device.setWriteProtected(true)
        device.setSb(0x6c); device.startSending(); device.setExternalTransfer(true)
        repeat(200) { device.tick() }
        val state = StateCodec.encode(StateCodec.capture(session))
        device.importImage(ByteArray(TurboFileSerialEndpoint.IMAGE_BYTES), true)
        device.setWriteProtected(false)
        StateCodec.decodeAndApply(state, session)
        assertTrue(device.isCardPresent); assertTrue(device.isWriteProtected)
        assertEquals(0x35, device.exportImage(true)[0].toInt())
        repeat(311) { device.tick() }
        assertEquals(-1, device.recvBit())
        device.tick(); assertEquals(1, device.recvBit())
      }
    }
  }
}
