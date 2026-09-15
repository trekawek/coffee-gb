package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.memory.cart.Rom
import eu.rekawek.coffeegb.core.memory.cart.type.SonarScene
import kotlin.test.assertEquals
import org.junit.Test

class PocketSonarStateTest {
  @Test fun portableStateRetainsScenePowerAndPartialColumn() {
    val bytes = ByteArray(0x8000)
    "POCKETSONAR".toByteArray().copyInto(bytes, 0x134)
    bytes[0x147] = 1
    val config = Gameboy.GameboyConfiguration(Rom(bytes))
        .setBootstrapMode(Gameboy.BootstrapMode.SKIP).setSupportBatterySave(false)
    Session(config, EventBusImpl(null, null, false), null).use { session ->
      val memory = session.gameboy.addressSpace
      val samples = SonarScene.openWater().copySamples()
      samples[160] = 3
      session.gameboy.configurePocketSonar(SonarScene(samples), true)
      memory.setByte(0x6000, 1)
      memory.setByte(0x4000, 1); memory.setByte(0x4000, 0)
      assertEquals(7, memory.getByte(0xa000))
      val state = StateCodec.encode(StateCodec.capture(session))
      session.gameboy.configurePocketSonar(SonarScene.demo(), false)
      StateCodec.decodeAndApply(state, session)
      assertEquals(3, memory.getByte(0xa000))
      assertEquals(7, memory.getByte(0xa000))
    }
  }
}
