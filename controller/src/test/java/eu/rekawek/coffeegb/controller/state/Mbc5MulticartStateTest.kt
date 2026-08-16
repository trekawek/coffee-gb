package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test

class Mbc5MulticartStateTest {

  @Test
  fun portableAndInProcessSnapshotsRestoreAcrossMenuAndNativeChildMappers() {
    val configuration =
        StateCodecTestSupport.configuration(multicartRom(), GameboyType.CGB)
            .setRtcTimeSource(VirtualTimeSource())
    StateCodecTestSupport.session(configuration).use { session ->
      val gameboy = session.gameboy
      val menuSnapshot = MachineSnapshot.capture(gameboy)
      val menuState = StateCodec.encode(StateCodec.capture(configuration, gameboy))

      select(gameboy, 0x20, 0xe0, 0xa0)
      gameboy.addressSpace.setByte(0x2000, 3)
      assertEquals(0x43, gameboy.addressSpace.getByte(0x4000))
      assertNotNull(gameboy.captureRtcRuntimeState().primary())
      val mbc3Snapshot = MachineSnapshot.capture(gameboy, menuSnapshot)
      val mbc3State = StateCodec.encode(StateCodec.capture(configuration, gameboy))

      menuSnapshot.restore(gameboy)
      select(gameboy, 0x10, 0xf0, 0xe0)
      assertNull(gameboy.captureRtcRuntimeState().primary())
      mbc3Snapshot.restore(gameboy)
      assertEquals(0x43, gameboy.addressSpace.getByte(0x4000))
      assertNotNull(gameboy.captureRtcRuntimeState().primary())

      StateCodec.decodeAndApply(menuState, configuration, gameboy)
      assertNull(gameboy.captureRtcRuntimeState().primary())
      select(gameboy, 0x10, 0xf0, 0xe0)
      StateCodec.decodeAndApply(mbc3State, configuration, gameboy)
      assertEquals(0x43, gameboy.addressSpace.getByte(0x4000))
      assertNotNull(gameboy.captureRtcRuntimeState().primary())
    }
  }

  private fun select(gameboy: Gameboy, page: Int, invertedMask: Int, mapperMode: Int) {
    gameboy.addressSpace.setByte(0xb000, page)
    gameboy.addressSpace.setByte(0xb100, invertedMask)
    gameboy.addressSpace.setByte(0xb200, mapperMode)
  }

  private fun multicartRom(): ByteArray = ByteArray(256 * 0x4000).also { data ->
    repeat(256) { bank -> data[bank * 0x4000] = bank.toByte() }
    putHeader(data, 0, 0x19, 0x05)
    for (bank in 0x16..0x20 step 2) {
      putHeader(data, bank, 0x19, 0x05)
    }
    putHeader(data, 0x20, 0x01, 0x04)
    putHeader(data, 0x40, 0x10, 0x05)
  }

  private fun putHeader(data: ByteArray, bank: Int, type: Int, size: Int) {
    val base = bank * 0x4000
    NINTENDO_LOGO.forEachIndexed { index, byte -> data[base + 0x104 + index] = byte }
    data[base + 0x143] = 0x80.toByte()
    data[base + 0x147] = type.toByte()
    data[base + 0x148] = size.toByte()
  }

  private companion object {
    val NINTENDO_LOGO =
        byteArrayOf(
            0xce.toByte(), 0xed.toByte(), 0x66, 0x66, 0xcc.toByte(), 0x0d, 0, 0x0b,
            0x03, 0x73, 0, 0x83.toByte(), 0, 0x0c, 0, 0x0d,
            0, 0x08, 0x11, 0x1f, 0x88.toByte(), 0x89.toByte(), 0, 0x0e,
            0xdc.toByte(), 0xcc.toByte(), 0x6e, 0xe6.toByte(), 0xdd.toByte(), 0xdd.toByte(),
            0xd9.toByte(), 0x99.toByte(), 0xbb.toByte(), 0xbb.toByte(), 0x67, 0x63, 0x6e,
            0x0e, 0xec.toByte(), 0xcc.toByte(), 0xdd.toByte(), 0xdc.toByte(), 0x99.toByte(),
            0x9f.toByte(), 0xbb.toByte(), 0xb9.toByte(), 0x33, 0x3e,
        )
  }
}
