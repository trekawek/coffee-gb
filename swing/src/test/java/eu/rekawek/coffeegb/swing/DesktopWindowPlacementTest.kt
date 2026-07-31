package eu.rekawek.coffeegb.swing

import java.awt.Dimension
import java.awt.Rectangle
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class DesktopWindowPlacementTest {
  @Test
  fun `material intersection requires eighty by sixty logical pixels on one screen`() {
    val layout = ScreenLayout(listOf(screen("primary", bounds(0, 0, 1_920, 1_080))), "primary")

    assertTrue(
        DesktopWindowPlacement.materiallyIntersectsScreen(
            bounds(1_840, 1_020, 320, 240),
            layout,
        ))
    assertFalse(
        DesktopWindowPlacement.materiallyIntersectsScreen(
            bounds(1_841, 1_020, 320, 240),
            layout,
        ))
    assertFalse(
        DesktopWindowPlacement.materiallyIntersectsScreen(
            bounds(1_840, 1_021, 320, 240),
            layout,
        ))
  }

  @Test
  fun `valid intersecting bounds restore into the selected usable work area`() {
    val left =
        screen(
            "left",
            bounds(-1_920, -80, 1_920, 1_080),
            bounds(-1_920, -40, 1_920, 1_040),
        )
    val primary = screen("primary", bounds(0, 0, 1_920, 1_080), bounds(0, 0, 1_920, 1_040))
    val layout = ScreenLayout(listOf(left, primary), "primary")

    assertEquals(
        bounds(-1_920, -40, 1_920, 1_040),
        DesktopWindowPlacement.restorableBounds(
            bounds(-1_900, -70, 2_500, 1_200),
            layout,
            DesktopSize(320, 288),
        ),
        "largest screen overlap selects the left monitor before clamping",
    )
  }

  @Test
  fun `off screen tiny and unbounded persisted rectangles are rejected`() {
    val layout = ScreenLayout(listOf(screen("primary", bounds(0, 0, 1_920, 1_080))), "primary")
    val minimum = DesktopSize(320, 288)

    assertNull(
        DesktopWindowPlacement.restorableBounds(
            bounds(5_000, -3_000, 800, 600),
            layout,
            minimum,
        ))
    assertNull(
        DesktopWindowPlacement.restorableBounds(
            bounds(20, 30, 319, 600),
            layout,
            minimum,
        ))
    assertNull(
        DesktopWindowPlacement.restorableBounds(
            bounds(100_001, 30, 800, 600),
            layout,
            minimum,
        ))
    assertNull(
        DesktopWindowPlacement.restorableBounds(
            bounds(20, 30, 10_001, 600),
            layout,
            minimum,
        ))
  }

  @Test
  fun `clamping supports negative coordinates and a work area below the normal minimum`() {
    val tinyUsable = bounds(-95, -95, 100, 70)

    assertEquals(
        tinyUsable,
        DesktopWindowPlacement.clampToUsable(
            bounds(-90, -90, 5, 5),
            tinyUsable,
            DesktopSize(320, 288),
        ),
    )
    assertEquals(
        bounds(1_120, 0, 800, 600),
        DesktopWindowPlacement.clampToUsable(
            bounds(5_000, -3_000, 800, 600),
            bounds(0, 0, 1_920, 1_040),
            DesktopSize(320, 288),
        ),
    )
  }

  @Test
  fun `centering is overflow safe and constrains desired outer size`() {
    val usable = bounds(Int.MAX_VALUE - 99, -200, 100, 80)

    assertEquals(
        usable,
        DesktopWindowPlacement.centeredBounds(
            DesktopSize(Int.MAX_VALUE, Int.MAX_VALUE),
            usable,
            DesktopSize(320, 288),
        ),
    )
    assertEquals(
        bounds(-1_350, 200, 700, 500),
        DesktopWindowPlacement.centeredBounds(
            DesktopSize(700, 500),
            bounds(-1_900, -50, 1_800, 1_000),
            DesktopSize(320, 288),
        ),
    )
  }

  @Test
  fun `awt overload gives debugger dialogs the same restore policy`() {
    val layout = ScreenLayout(listOf(screen("primary", bounds(0, 0, 1_000, 760))), "primary")

    assertEquals(
        Rectangle(0, 0, 1_000, 760),
        DesktopWindowPlacement.restorableBounds(
            Rectangle(100, 60, 1_200, 900),
            layout,
            Dimension(420, 320),
        ),
    )
    assertNull(
        DesktopWindowPlacement.restorableBounds(
            Rectangle(2_000, 2_000, 900, 700),
            layout,
            Dimension(420, 320),
        ))
  }

  private fun screen(
      id: String,
      full: DesktopBounds,
      usable: DesktopBounds = full,
  ): ScreenSnapshot = ScreenSnapshot(id, full, usable, 1.0, 1.0)

  private fun bounds(x: Int, y: Int, width: Int, height: Int): DesktopBounds =
      DesktopBounds(x, y, width, height)
}
