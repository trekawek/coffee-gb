package eu.rekawek.coffeegb.swing

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class DesktopFeatureFlagsTest {
  @Test
  fun `Proposal 3 desktop menu is opt-in`() {
    assertFalse(DesktopFeatureFlags.proposal3MenuEnabled(null))
    assertFalse(DesktopFeatureFlags.proposal3MenuEnabled(""))
    assertFalse(DesktopFeatureFlags.proposal3MenuEnabled("false"))
    assertFalse(DesktopFeatureFlags.proposal3MenuEnabled("yes"))
    assertTrue(DesktopFeatureFlags.proposal3MenuEnabled("true"))
    assertTrue(DesktopFeatureFlags.proposal3MenuEnabled("TRUE"))
  }

  @Test
  fun `default desktop startup does not construct the Proposal 3 overlay`() {
    var installs = 0

    val hidden =
        installDesktopProposal3Menu(enabled = false) {
          installs++
          "hidden"
        }
    val enabled =
        installDesktopProposal3Menu(enabled = true) {
          installs++
          "installed"
        }

    assertNull(hidden)
    assertEquals("installed", enabled)
    assertEquals(1, installs)
  }
}
