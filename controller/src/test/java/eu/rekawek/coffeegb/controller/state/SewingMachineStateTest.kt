package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.serial.SewingMachineSerialEndpoint
import org.junit.Test
import kotlin.test.*

class SewingMachineStateTest {
  @Test fun portableStatePreservesPartialTransferFabricAndControls() {
    val device = SewingMachineSerialEndpoint()
    val config = Gameboy.GameboyConfiguration(Rom(ByteArray(0x8000)))
        .setBootstrapMode(Gameboy.BootstrapMode.SKIP).setSupportBatterySave(false)
    Session(config, EventBusImpl(null, null, false), null, device).use { session ->
      device.setModel(2); device.setLargeHoop(true); device.setThreadColor(0x992233); device.setSpeed(240)
      device.setSb(0x80); device.startSending(); device.setExternalTransfer(true)
      repeat(200) { device.tick() }
      val encoded = StateCodec.encode(StateCodec.capture(session))
      device.setModel(0); device.setThreadColor(0); device.setSpeed(1)
      StateCodec.decodeAndApply(encoded, session)
      assertEquals(2, device.model); assertTrue(device.isArmAttached); assertTrue(device.isLargeHoop)
      assertEquals(0xff992233.toInt(), device.threadColor); assertEquals(240, device.speed)
      repeat(311) { device.tick() }; assertEquals(-1, device.recvBit())
      device.tick(); assertEquals(0, device.recvBit())
    }
  }
}
