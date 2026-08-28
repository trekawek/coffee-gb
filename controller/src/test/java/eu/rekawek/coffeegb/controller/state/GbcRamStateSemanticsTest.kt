package eu.rekawek.coffeegb.controller.state

import eu.rekawek.coffeegb.core.memory.GbcRam
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class GbcRamStateSemanticsTest {

  @Test
  fun `legacy full-byte bank state validates and restores canonically`() {
    val legacy = GbcRam.GbcRamState(IntArray(7 * 0x1000), 0xf8)

    StateSemantics.validate(legacy)
    val ram = GbcRam()
    ram.restoreState(legacy)

    assertEquals(0, (ram.captureState() as GbcRam.GbcRamState).svbk())
  }

  @Test
  fun `bank state outside a byte remains invalid`() {
    val invalid = GbcRam.GbcRamState(IntArray(7 * 0x1000), 0x100)

    assertFailsWith<StateApplyException> { StateSemantics.validate(invalid) }
  }
}
