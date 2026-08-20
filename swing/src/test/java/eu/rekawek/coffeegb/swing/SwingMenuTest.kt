package eu.rekawek.coffeegb.swing

import java.awt.event.ActionEvent
import java.nio.file.Paths
import javax.swing.AbstractAction
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JSeparator
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class SwingMenuTest {

  @Test
  fun `native recent menu prefers the exact-origin host route`() {
    val path = Paths.get("/games/archive.zip")
    val exact = mutableListOf<java.nio.file.Path>()
    val legacy = mutableListOf<Pair<java.nio.file.Path, RomOpenSource>>()

    openRecentRomPath(path, exact::add) { candidate, source -> legacy += candidate to source }

    assertEquals(listOf(path), exact)
    assertTrue(legacy.isEmpty())

    exact.clear()
    openRecentRomPath(path, null) { candidate, source -> legacy += candidate to source }
    assertTrue(exact.isEmpty())
    assertEquals(listOf(path to RomOpenSource.RECENT), legacy)
  }

  @Test
  fun `Proposal 3 menu insertion follows the feature flag`() {
    val openMenuAction =
        object : AbstractAction("On-screen Menu") {
          override fun actionPerformed(event: ActionEvent) = Unit
        }
    val disabledMenu = JMenu("Game")

    addProposal3MenuItem(disabledMenu, openMenuAction, enabled = false)

    assertEquals(0, disabledMenu.menuComponentCount)

    val enabledMenu = JMenu("Game")
    addProposal3MenuItem(enabledMenu, openMenuAction, enabled = true)

    assertEquals(2, enabledMenu.menuComponentCount)
    assertEquals(openMenuAction, (enabledMenu.getMenuComponent(0) as JMenuItem).action)
    assertEquals("On-screen Menu", (enabledMenu.getMenuComponent(0) as JMenuItem).text)
    assertTrue(enabledMenu.getMenuComponent(1) is JSeparator)
  }

  @Test
  fun `mobile adapter configuration action opens the retained window`() {
    var opens = 0

    SwingUtilities.invokeAndWait {
      val item = mobileAdapterConfigurationMenuItem { opens++ }

      assertEquals("Configure Mobile Adapter…", item.text)
      assertTrue(item.accessibleContext.accessibleDescription.contains("session permissions"))
      item.doClick()
    }

    assertEquals(1, opens)
  }

  @Test
  fun `desktop startup contract requires both Mobile Adapter entry points`() {
    val menuBar = JMenuBar()
    val peripherals = JMenu("Peripherals")
    val linkPort = JMenu("Link-port device")
    linkPort.add("Mobile Adapter GB")
    peripherals.add(linkPort)
    menuBar.add(peripherals)

    assertFalse(hasMobileAdapterDesktopControls(menuBar))

    val configuration = mobileAdapterConfigurationMenuItem {}
    peripherals.add(configuration)
    assertTrue(hasMobileAdapterDesktopControls(menuBar))

    configuration.isEnabled = false
    assertFalse(hasMobileAdapterDesktopControls(menuBar))
    configuration.isEnabled = true

    val owner = linkPort.getItem(0)
    owner.isVisible = false
    assertFalse(hasMobileAdapterDesktopControls(menuBar))
  }
}
