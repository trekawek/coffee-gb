package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.memory.cart.Rom
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class MainTest {

  @Test
  fun `canonical profile option selects the constructed session profile`() {
    HardwareProfileRegistry.supportedProfiles().forEach { profile ->
      val parsed = ParsedArgs.parse(arrayOf("--profile=${profile.id()}"))
      assertEquals(profile, parsed.hardwareProfileOverride())

      val properties = EmulatorProperties(parsed.hardwareProfileOverride())
      val rom = Rom(testRom())
      val config = Controller.createGameboyConfig(properties, rom).setSupportBatterySave(false)
      assertEquals(profile, config.hardwareProfile)
      config.build().use { gameboy -> assertEquals(profile, gameboy.hardwareProfile) }
    }
  }

  @Test
  fun `legacy force flags map to stable profiles and remain optional`() {
    assertEquals(
        HardwareProfileRegistry.DMG,
        ParsedArgs.parse(arrayOf("--force-dmg")).hardwareProfileOverride(),
    )
    assertEquals(
        HardwareProfileRegistry.CGB,
        ParsedArgs.parse(arrayOf("-c")).hardwareProfileOverride(),
    )
    assertNull(ParsedArgs.parse(emptyArray()).hardwareProfileOverride())
  }

  @Test
  fun `missing malformed conflicting and unknown profile options fail actionably`() {
    listOf(
            arrayOf("--profile"),
            arrayOf("--profile="),
            arrayOf("--profile=cgb=extra"),
            arrayOf("--profile=cgb", "--force-cgb"),
            arrayOf("--force-dmg", "--force-cgb"),
        )
        .forEach { args -> assertFailsWith<IllegalArgumentException> { ParsedArgs.parse(args).hardwareProfileOverride() } }

    val unknown =
        assertFailsWith<IllegalArgumentException> {
          ParsedArgs.parse(arrayOf("--profile=CGB")).hardwareProfileOverride()
        }
    assertTrue(unknown.message!!.contains("[dmg, cgb, cgb0, sgb, sgb2]"))
  }

  private fun testRom(): ByteArray =
      ByteArray(0x8000).also {
        it[0x100] = 0x18
        it[0x101] = 0xfe.toByte()
        it[0x143] = 0x80.toByte()
      }
}
