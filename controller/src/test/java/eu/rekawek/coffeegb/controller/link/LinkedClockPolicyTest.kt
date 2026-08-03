package eu.rekawek.coffeegb.controller.link

import eu.rekawek.coffeegb.controller.state.StateApplyException
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration
import eu.rekawek.coffeegb.core.hardware.ClockSpec
import eu.rekawek.coffeegb.core.hardware.HardwareProfile
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import eu.rekawek.coffeegb.core.memory.cart.Rom
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class LinkedClockPolicyTest {

  @Test
  fun `matching registered session clocks are admitted`() {
    assertEquals(
        ClockSpec.LEGACY,
        requireCompatibleLinkedClock(
            listOf(
                null,
                configuration(HardwareProfileRegistry.DMG),
                configuration(HardwareProfileRegistry.CGB),
            )),
    )
  }

  @Test
  fun `complete rational identity admits sgb2 peers but rejects sgb mixed group`() {
    assertEquals(
        ClockSpec.SGB2,
        requireCompatibleLinkedClock(
            listOf(
                configuration(HardwareProfileRegistry.SGB2),
                configuration(HardwareProfileRegistry.SGB2),
            )),
    )
    val failure =
        assertFailsWith<StateApplyException> {
          requireCompatibleLinkedClock(
          listOf(
              configuration(HardwareProfileRegistry.SGB),
              configuration(HardwareProfileRegistry.SGB2),
          ))
        }
    assertTrue(failure.message!!.contains("sgb2"))
    assertTrue(failure.message!!.contains("sgb"))
  }

  private fun configuration(profile: HardwareProfile): GameboyConfiguration =
      GameboyConfiguration(Rom(Path.of("src/test/resources/roms/cpu_instrs.gb").toFile()))
          .setHardwareProfile(profile)
}
