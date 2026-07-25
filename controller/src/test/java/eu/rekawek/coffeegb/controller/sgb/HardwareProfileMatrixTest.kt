package eu.rekawek.coffeegb.controller.sgb

import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class HardwareProfileMatrixTest {

  @Test
  fun checkedInMatrixExactlyMatchesTheAuthoritativeRegistry() {
    val rows = matrix()
    assertEquals(HardwareProfileRegistry.supportedIds(), rows.map { it.getValue("id") })

    rows.forEach { row ->
      val profile = HardwareProfileRegistry.resolve(row.getValue("id"))
      val capabilities = profile.capabilities()
      val clock = profile.clockSpec()
      val boot = profile.bootSpec()

      assertEquals(row.getValue("display_name"), profile.displayName())
      assertEquals(row.getValue("family"), profile.family().name)
      assertEquals(row.getValue("revision"), profile.revision())
      assertEquals(row.boolean("color_display"), capabilities.colorDisplay())
      assertEquals(row.boolean("cgb_mode"), capabilities.cgbMode())
      assertEquals(row.boolean("double_speed"), capabilities.doubleSpeed())
      assertEquals(row.boolean("infrared"), capabilities.infrared())
      assertEquals(row.boolean("sgb_commands"), capabilities.superGameboyCommands())
      assertEquals(row.boolean("sgb_border"), capabilities.superGameboyBorder())
      assertEquals(row.boolean("serial_link"), capabilities.serialLink())
      assertEquals(row.long("clock_ticks_per_second"), clock.ticksPerSecond())
      assertEquals(row.long("cadence_numerator"), clock.controllerFramesPerSecondNumerator())
      assertEquals(row.long("cadence_denominator"), clock.controllerFramesPerSecondDenominator())
      assertEquals(row.int("ticks_per_frame"), clock.controllerTicksPerFrame())
      assertEquals(row.getValue("boot_rom"), boot.bootRomId())
      assertEquals(row.int("authentic_div"), boot.authenticDivPreset())
      assertEquals(row.int("post_boot_div"), boot.postBootDivPreset())
      assertEquals(row.int("post_boot_af"), boot.postBootAf())
      assertEquals(row.int("post_boot_bc"), boot.postBootBc())
      assertEquals(row.int("post_boot_de"), boot.postBootDe())
      assertEquals(row.int("post_boot_hl"), boot.postBootHl())
      assertEquals(row.int("cgb_handoff_ticks"), boot.cgbBootHandoffTicks())
      assertFalse("Evidence is required for ${profile.id()}", row.getValue("evidence").isBlank())
      assertFalse("Uncertainty is required for ${profile.id()}", row.getValue("uncertainty").isBlank())
    }
  }

  private fun matrix(): List<Map<String, String>> {
    val stream = javaClass.getResourceAsStream("/sgb-baselines/hardware-profile-matrix.tsv")
    assertNotNull(stream)
    val lines = stream!!.bufferedReader(StandardCharsets.UTF_8).readLines().filter { it.isNotBlank() }
    val header = lines.first().split('\t')
    return lines.drop(1).map { line ->
      val values = line.split('\t')
      assertEquals(header.size, values.size)
      header.zip(values).toMap()
    }
  }

  private fun Map<String, String>.boolean(field: String) = getValue(field).toBooleanStrict()

  private fun Map<String, String>.int(field: String) = Integer.decode(getValue(field))

  private fun Map<String, String>.long(field: String) = java.lang.Long.decode(getValue(field))
}
