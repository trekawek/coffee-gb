package eu.rekawek.coffeegb.swing

import javax.swing.JMenuBar
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class FullscreenChromeVisibilityTest {

  @Test
  fun `visible menu bar is hidden once and restored once`() {
    val menuBar = JMenuBar().apply { isVisible = true }
    val visibility = FullscreenChromeVisibility()

    visibility.hide(menuBar)
    visibility.hide(menuBar)
    assertFalse(menuBar.isVisible)

    visibility.restore()
    visibility.restore()
    assertTrue(menuBar.isVisible)
  }

  @Test
  fun `a previously hidden menu bar remains hidden`() {
    val menuBar = JMenuBar().apply { isVisible = false }
    val visibility = FullscreenChromeVisibility()

    visibility.hide(menuBar)
    visibility.restore()

    assertFalse(menuBar.isVisible)
  }
}
