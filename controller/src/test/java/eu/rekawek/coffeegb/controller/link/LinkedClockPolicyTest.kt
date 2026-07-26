package eu.rekawek.coffeegb.controller.link

import eu.rekawek.coffeegb.controller.state.StateApplyException
import eu.rekawek.coffeegb.core.hardware.ClockSpec
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class LinkedClockPolicyTest {

  @Test
  fun `matching custom session clocks are admitted`() {
    val custom = ClockSpec(1_000, 10, 1)
    assertEquals(
        custom,
        requireCompatibleLinkedClockIdentities(
            listOf(LinkedClockIdentity("first", custom), LinkedClockIdentity("second", custom))))
  }

  @Test
  fun `incompatible session clock is rejected before linked execution`() {
    val first = ClockSpec(1_000, 10, 1)
    val incompatible = ClockSpec(2_000, 10, 1)

    val failure =
        assertFailsWith<StateApplyException> {
          requireCompatibleLinkedClockIdentities(
              listOf(
                  LinkedClockIdentity("first", first),
                  LinkedClockIdentity("future", incompatible),
              ))
        }

    assertTrue(failure.message!!.contains("future"))
    assertTrue(failure.message!!.contains("first"))
  }

  @Test
  fun `complete rational identity admits sgb2 peers but rejects sgb mixed group`() {
    assertEquals(
        ClockSpec.SGB2,
        requireCompatibleLinkedClockIdentities(
            listOf(
                LinkedClockIdentity("sgb2-a", ClockSpec.SGB2),
                LinkedClockIdentity("sgb2-b", ClockSpec.SGB2),
            )),
    )
    assertFailsWith<StateApplyException> {
      requireCompatibleLinkedClockIdentities(
          listOf(
              LinkedClockIdentity(HardwareProfileRegistry.SGB.id(), ClockSpec.SGB),
              LinkedClockIdentity(HardwareProfileRegistry.SGB2.id(), ClockSpec.SGB2),
          ))
    }

    // Same integer frame budget is insufficient: exact master/cadence identity is required.
    val lookalike = ClockSpec(4_194_304, 1, 4_194_304, 70_224)
    val rationallyDifferent = ClockSpec(46_137_345, 11, 4_194_304, 70_224)
    assertEquals(lookalike.controllerTicksPerFrame(), rationallyDifferent.controllerTicksPerFrame())
    assertFailsWith<StateApplyException> {
      requireCompatibleLinkedClockIdentities(
          listOf(LinkedClockIdentity("a", lookalike), LinkedClockIdentity("b", rationallyDifferent)))
    }
  }
}
