package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.core.Gameboy
import eu.rekawek.coffeegb.core.GameboyType
import kotlin.test.assertEquals
import org.junit.Test

class Unlicensed256MStateTest {

  @Test
  fun portableAndInProcessSnapshotsRestoreAcrossTheMenuAndSelectedGame() {
    val configuration = StateCodecTestSupport.configuration(multicartRom(), GameboyType.CGB)
    StateCodecTestSupport.session(configuration).use { session ->
      val gameboy = session.gameboy
      val menuSnapshot = MachineSnapshot.capture(gameboy)
      val menuState = StateCodec.encode(StateCodec.capture(configuration, gameboy))

      select(gameboy, 0x91)
      gameboy.addressSpace.setByte(0x2000, 0x03)
      gameboy.addressSpace.setByte(0x0000, 0x0a)
      gameboy.addressSpace.setByte(0x4000, 0x02)
      gameboy.addressSpace.setByte(0xa123, 0x5a)
      assertEquals(0xc3, gameboy.addressSpace.getByte(0x4000))
      assertEquals(0x5a, gameboy.addressSpace.getByte(0xa123))
      val selectedSnapshot = MachineSnapshot.capture(gameboy, menuSnapshot)
      val selectedState = StateCodec.encode(StateCodec.capture(configuration, gameboy))

      menuSnapshot.restore(gameboy)
      assertEquals(0x01, gameboy.addressSpace.getByte(0x4000))
      selectedSnapshot.restore(gameboy)
      assertEquals(0xc3, gameboy.addressSpace.getByte(0x4000))
      assertEquals(0x5a, gameboy.addressSpace.getByte(0xa123))

      StateCodec.decodeAndApply(menuState, configuration, gameboy)
      assertEquals(0x01, gameboy.addressSpace.getByte(0x4000))
      select(gameboy, 0x91)
      gameboy.addressSpace.setByte(0x0000, 0x0a)
      gameboy.addressSpace.setByte(0x4000, 0x02)
      gameboy.addressSpace.setByte(0xa123, 0x2a)
      StateCodec.decodeAndApply(selectedState, configuration, gameboy)
      assertEquals(0xc3, gameboy.addressSpace.getByte(0x4000))
      assertEquals(0x5a, gameboy.addressSpace.getByte(0xa123))
    }
  }

  private fun select(gameboy: Gameboy, configuration: Int) {
    gameboy.addressSpace.setByte(0x7000, 0x60)
    gameboy.addressSpace.setByte(0x7001, 0xe0)
    gameboy.addressSpace.setByte(0x7002, configuration)
  }

  private fun multicartRom(): ByteArray = ByteArray(0x400000).also { data ->
    repeat(data.size / 0x4000) { bank -> data[bank * 0x4000] = bank.toByte() }
    putHeader(data, 0, "GB HiCol", 0x03, 0x01, 0x00)
    data[0x100] = 0x00
    data[0x101] = 0xc3.toByte()
    data[0x102] = 0x00
    data[0x103] = 0x40
    putHeader(data, 0xc0, "SELECTED", 0x1b, 0x05, 0x03)
  }

  private fun putHeader(
      data: ByteArray,
      bank: Int,
      title: String,
      type: Int,
      romSize: Int,
      ramSize: Int,
  ) {
    val base = bank * 0x4000
    NINTENDO_LOGO.copyInto(data, base + 0x104)
    title.forEachIndexed { index, character ->
      data[base + 0x134 + index] = character.code.toByte()
    }
    data[base + 0x143] = 0x80.toByte()
    data[base + 0x147] = type.toByte()
    data[base + 0x148] = romSize.toByte()
    data[base + 0x149] = ramSize.toByte()
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
