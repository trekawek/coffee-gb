package eu.rekawek.coffeegb.controller.link

import eu.rekawek.coffeegb.controller.state.StateApplyException
import eu.rekawek.coffeegb.core.hardware.ClockSpec
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
}
