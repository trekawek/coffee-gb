package eu.rekawek.coffeegb.controller.link

import eu.rekawek.coffeegb.controller.Session
import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.core.memory.cart.Rom
import kotlin.test.assertEquals
import org.junit.Test

class GbKissNetplayTest {
  @Test
  fun cartridgeInfraredUsesNetplayLinksAndSurvivesDetachedCheckpointRestore() {
    val links = StateHistory.createLinks(LinkMode.NORMAL)
    val sessions = listOf(0xff, 0xfe).mapIndexed { player, mapper ->
      val bytes = ByteArray(0x8000)
      bytes[0x147] = mapper.toByte()
      bytes[0x149] = 3
      val config = Gameboy.GameboyConfiguration(Rom(bytes))
          .setGameboyType(GameboyType.DMG).setSupportBatterySave(false)
      Session(config, EventBusImpl(null, null, false), null,
          links.serial[player], links.infrared[player])
    }
    try {
      sessions.forEach { it.gameboy.addressSpace.setByte(0, 0x0e) }
      sessions[0].gameboy.addressSpace.setByte(0xa000, 1)
      sessions[1].gameboy.addressSpace.setByte(0xa000, 1)
      val checkpoints = sessions.map { it.captureDetachedState() }
      sessions.forEach { it.gameboy.addressSpace.setByte(0xa000, 0) }
      sessions.forEach { assertEquals(0xc0, it.gameboy.addressSpace.getByte(0xa000)) }
      sessions.forEachIndexed { player, session -> session.restoreDetachedState(checkpoints[player]) }
      sessions.forEach { assertEquals(0xc1, it.gameboy.addressSpace.getByte(0xa000)) }
      sessions[0].gameboy.addressSpace.setByte(0xa000, 0)
      assertEquals(0xc0, sessions[1].gameboy.addressSpace.getByte(0xa000))
      assertEquals(0xc1, sessions[0].gameboy.addressSpace.getByte(0xa000))
    } finally {
      sessions.reversed().forEach(Session::close)
    }
  }
}
