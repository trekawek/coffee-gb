package eu.rekawek.coffeegb.swing

import java.nio.file.Paths
import javax.swing.JMenu
import javax.swing.JMenuBar
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
  fun `mobile adapter configuration action opens the retained window`() {
    var opens = 0

    SwingUtilities.invokeAndWait {
      val item = mobileAdapterConfigurationMenuItem { opens++ }
      assertEquals("Configure…", item.text)
      assertEquals("Configure Mobile Adapter", item.accessibleContext.accessibleName)
      item.doClick()
    }

    assertEquals(1, opens)
  }

  @Test
  fun `desktop startup requires Mobile Adapter connection and controls in one submenu`() {
    val menuBar = JMenuBar()
    val peripherals = JMenu("Peripherals")
    val linkPort = JMenu("Link port")
    val mobileAdapter = JMenu("Mobile Adapter GB")
    val connect = mobileAdapter.add("Connect")
    linkPort.add(mobileAdapter)
    peripherals.add(linkPort)
    menuBar.add(peripherals)

    assertFalse(hasMobileAdapterDesktopControls(menuBar))
    val configuration = mobileAdapterConfigurationMenuItem {}
    mobileAdapter.add(configuration)
    assertTrue(hasMobileAdapterDesktopControls(menuBar))

    mobileAdapter.text = "Mobile Adapter GB (selected)"
    assertTrue(hasMobileAdapterDesktopControls(menuBar))

    linkPort.isEnabled = false
    assertFalse(hasMobileAdapterDesktopControls(menuBar))
    linkPort.isEnabled = true

    connect.isVisible = false
    assertFalse(hasMobileAdapterDesktopControls(menuBar))
    connect.isVisible = true
    configuration.isEnabled = false
    assertFalse(hasMobileAdapterDesktopControls(menuBar))
  }
}
