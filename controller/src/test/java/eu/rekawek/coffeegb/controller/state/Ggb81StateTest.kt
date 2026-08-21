package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.core.GameboyType
import kotlin.test.assertEquals
import org.junit.Test

class Ggb81StateTest {

  @Test
  fun ordinaryRewindAndPortableSnapshotsRestoreTheProtectedMapper() {
    val configuration = StateCodecTestSupport.configuration(ggb81Rom(), GameboyType.CGB)
    StateCodecTestSupport.session(configuration).use { session ->
      val gameboy = session.gameboy
      gameboy.addressSpace.setByte(0x2001, 0x03)
      assertEquals(0x06, gameboy.addressSpace.getByte(0x4000))

      val rewind = MachineSnapshot.capture(gameboy)
      val portable = StateCodec.encode(StateCodec.capture(configuration, gameboy))

      gameboy.addressSpace.setByte(0x2001, 0x00)
      assertEquals(0x00, gameboy.addressSpace.getByte(0x4000))
      rewind.restore(gameboy)
      assertEquals(0x06, gameboy.addressSpace.getByte(0x4000))

      gameboy.addressSpace.setByte(0x2001, 0x00)
      StateCodec.decodeAndApply(portable, configuration, gameboy)
      assertEquals(0x06, gameboy.addressSpace.getByte(0x4000))
    }
  }

  private fun ggb81Rom(): ByteArray = ByteArray(0x80000).also { data ->
    data[0x100] = 0x18
    data[0x101] = 0xfe.toByte()
    NINTENDO_LOGO.copyInto(data, 0x104)
    "DIGIMON".forEachIndexed { index, character ->
      data[0x134 + index] = character.code.toByte()
    }
    data[0x143] = 0x80.toByte()
    data[0x144] = 'A'.code.toByte()
    data[0x145] = '7'.code.toByte()
    data[0x147] = 0x19
    data[0x148] = 0x06
    data[0x149] = 0x01
    data[3 * 0x4000] = 0x22
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
