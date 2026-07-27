package eu.rekawek.coffeegb.swing

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FullscreenControllerTest {

  @Test
  fun `enter and exit use the required dispose decoration bounds show order`() {
    val primary = screen("primary", bounds(0, 0, 1920, 1080), bounds(0, 0, 1920, 1040))
    val provider = MutableScreenProvider(ScreenLayout(listOf(primary), "primary"))
    val initial = bounds(120, 80, 800, 600)
    val window = FakeWindow(FullscreenWindowSnapshot(initial, "primary", false))
    val controller = controller(window, provider)

    controller.enterFullscreen()

    assertTrue(controller.isFullscreen())
    assertEquals("primary", controller.activeScreenId())
    assertEquals(
        listOf(
            WindowOperation.Dispose,
            WindowOperation.Decoration(true),
            WindowOperation.Bounds(primary.fullBounds),
            WindowOperation.Show,
        ),
        window.operations,
    )

    controller.exitFullscreen()

    assertFalse(controller.isFullscreen())
    assertEquals(null, controller.activeScreenId())
    assertEquals(
        listOf(
            WindowOperation.Dispose,
            WindowOperation.Decoration(true),
            WindowOperation.Bounds(primary.fullBounds),
            WindowOperation.Show,
            WindowOperation.Dispose,
            WindowOperation.Decoration(false),
            WindowOperation.Bounds(initial),
            WindowOperation.Show,
        ),
        window.operations,
    )
  }

  @Test
  fun `exit restores the previous decoration state`() {
    val primary = screen("primary", bounds(0, 0, 1280, 720))
    val provider = MutableScreenProvider(ScreenLayout(listOf(primary), "primary"))
    val window =
        FakeWindow(FullscreenWindowSnapshot(bounds(30, 40, 640, 480), "primary", true))
    val controller = controller(window, provider)

    controller.enterFullscreen()
    controller.exitFullscreen()

    assertEquals(WindowOperation.Decoration(true), window.operations[5])
  }

  @Test
  fun `explicit transitions are idempotent while toggle alternates`() {
    val primary = screen("primary", bounds(0, 0, 1280, 720))
    val provider = MutableScreenProvider(ScreenLayout(listOf(primary), "primary"))
    val window =
        FakeWindow(FullscreenWindowSnapshot(bounds(30, 40, 640, 480), "primary", false))
    val controller = controller(window, provider)

    controller.enterFullscreen()
    controller.enterFullscreen()
    assertEquals(4, window.operations.size)

    controller.exitFullscreen()
    controller.exitFullscreen()
    assertEquals(8, window.operations.size)

    controller.toggleFullscreen()
    assertTrue(controller.isFullscreen())
    controller.toggleFullscreen()
    assertFalse(controller.isFullscreen())
    assertEquals(16, window.operations.size)
  }

  @Test
  fun `stable ID distinguishes monitors with identical geometry and scale`() {
    val first = screen("display-a", bounds(0, 0, 1920, 1080))
    val second = screen("display-b", bounds(0, 0, 1920, 1080))
    val provider = MutableScreenProvider(ScreenLayout(listOf(first, second), "display-a"))
    val window =
        FakeWindow(FullscreenWindowSnapshot(bounds(100, 100, 700, 500), "display-b", false))
    val controller = controller(window, provider)

    controller.enterFullscreen()

    assertEquals("display-b", controller.activeScreenId())
  }

  @Test
  fun `disconnected stable ID recovers even when replacement geometry is identical`() {
    val first = screen("display-a", bounds(0, 0, 1920, 1080))
    val removed = screen("display-b", bounds(0, 0, 1920, 1080))
    val provider = MutableScreenProvider(ScreenLayout(listOf(first, removed), "display-a"))
    val initial = bounds(100, 100, 700, 500)
    val window = FakeWindow(FullscreenWindowSnapshot(initial, "display-b", false))
    val controller = controller(window, provider)
    controller.enterFullscreen()
    window.operations.clear()

    provider.layout = ScreenLayout(listOf(first), "display-a")
    controller.refreshScreens()

    assertEquals("display-a", controller.activeScreenId())
    assertTrue(window.operations.isEmpty())

    controller.exitFullscreen()

    assertEquals(WindowOperation.Bounds(initial), window.operations[2])
  }

  @Test
  fun `disconnected monitor moves fullscreen and restored window to nearest current screen`() {
    val primary = screen("primary", bounds(0, 0, 1000, 800))
    val removed =
        screen(
            "removed",
            bounds(5000, 0, 1000, 800),
            bounds(5000, 0, 1000, 760),
        )
    val nearest =
        screen(
            "nearest",
            bounds(6300, 0, 1000, 800),
            bounds(6300, 0, 1000, 760),
        )
    val provider =
        MutableScreenProvider(ScreenLayout(listOf(primary, removed, nearest), "primary"))
    val window =
        FakeWindow(FullscreenWindowSnapshot(bounds(5100, 70, 700, 500), "removed", false))
    val controller = controller(window, provider)
    controller.enterFullscreen()
    window.operations.clear()

    provider.layout = ScreenLayout(listOf(primary, nearest), "primary")
    controller.refreshScreens()

    assertEquals("nearest", controller.activeScreenId())
    assertEquals(
        listOf<WindowOperation>(WindowOperation.Bounds(nearest.fullBounds)),
        window.operations,
    )

    controller.exitFullscreen()

    assertEquals(
        WindowOperation.Bounds(bounds(6400, 70, 700, 500)),
        window.operations[window.operations.lastIndex - 1],
    )
  }

  @Test
  fun `equal-distance disconnected monitor fallback prefers primary then stable ID`() {
    val removed = screen("removed", bounds(1000, 0, 1000, 800))
    val primary = screen("primary", bounds(0, 0, 1000, 800))
    val other = screen("other", bounds(2000, 0, 1000, 800))
    val provider =
        MutableScreenProvider(ScreenLayout(listOf(other, primary, removed), "primary"))
    val window =
        FakeWindow(FullscreenWindowSnapshot(bounds(1100, 50, 500, 400), "removed", false))
    val controller = controller(window, provider)
    controller.enterFullscreen()

    provider.layout = ScreenLayout(listOf(other, primary), "primary")
    controller.refreshScreens()

    assertEquals("primary", controller.activeScreenId())
  }

  @Test
  fun `negative monitor coordinates remain negative after restore`() {
    val left =
        screen(
            "left",
            bounds(-1920, -120, 1920, 1080),
            bounds(-1920, -80, 1920, 1040),
        )
    val primary = screen("primary", bounds(0, 0, 1920, 1080))
    val provider = MutableScreenProvider(ScreenLayout(listOf(primary, left), "primary"))
    val initial = bounds(-1800, -40, 900, 600)
    val window = FakeWindow(FullscreenWindowSnapshot(initial, "left", false))
    val controller = controller(window, provider)

    controller.enterFullscreen()
    controller.exitFullscreen()

    assertEquals(WindowOperation.Bounds(initial), window.operations[6])
  }

  @Test
  fun `per-monitor scale change preserves device placement and exact fullscreen bounds`() {
    val initialScreen =
        screen(
            "primary",
            bounds(0, 0, 1536, 864),
            bounds(0, 0, 1536, 824),
            scaleX = 1.25,
            scaleY = 1.25,
        )
    val provider = MutableScreenProvider(ScreenLayout(listOf(initialScreen), "primary"))
    val window =
        FakeWindow(FullscreenWindowSnapshot(bounds(100, 80, 800, 600), "primary", false))
    val controller = controller(window, provider, DesktopSize(160, 144))
    controller.enterFullscreen()
    window.operations.clear()

    val changedScreen =
        screen(
            "primary",
            bounds(0, 0, 960, 540),
            bounds(0, 0, 960, 500),
            scaleX = 2.0,
            scaleY = 2.0,
        )
    provider.layout = ScreenLayout(listOf(changedScreen), "primary")
    controller.refreshScreens()
    controller.refreshScreens()

    // Refresh sets the exact GraphicsConfiguration rectangle once, without scale arithmetic.
    assertEquals(
        listOf<WindowOperation>(WindowOperation.Bounds(changedScreen.fullBounds)),
        window.operations,
    )

    controller.exitFullscreen()

    // 100*1.25/2 rounds to 63; 80*1.25/2 is 50. Physical 1000x750 becomes 500x375.
    val restored = bounds(63, 50, 500, 375)
    assertEquals(
        WindowOperation.Bounds(restored),
        window.operations[window.operations.lastIndex - 1],
    )
    assertTrue(restored.rightExclusive <= changedScreen.usableBounds.rightExclusive)
    assertTrue(restored.bottomExclusive <= changedScreen.usableBounds.bottomExclusive)
  }

  @Test
  fun `tiny remembered bounds grow to minimum size`() {
    val primary =
        screen("primary", bounds(0, 0, 1200, 900), bounds(0, 0, 1200, 860))
    val provider = MutableScreenProvider(ScreenLayout(listOf(primary), "primary"))
    val window =
        FakeWindow(FullscreenWindowSnapshot(bounds(400, 300, 10, 8), "primary", false))
    val controller = controller(window, provider, DesktopSize(320, 288))

    controller.enterFullscreen()
    controller.exitFullscreen()

    assertEquals(WindowOperation.Bounds(bounds(400, 300, 320, 288)), window.operations[6])
  }

  @Test
  fun `oversized remembered bounds shrink to current usable screen`() {
    val primary =
        screen("primary", bounds(0, 0, 1920, 1080), bounds(0, 0, 1920, 1040))
    val provider = MutableScreenProvider(ScreenLayout(listOf(primary), "primary"))
    val window =
        FakeWindow(FullscreenWindowSnapshot(bounds(100, 100, 4000, 3000), "primary", false))
    val controller = controller(window, provider)

    controller.enterFullscreen()
    controller.exitFullscreen()

    assertEquals(WindowOperation.Bounds(primary.usableBounds), window.operations[6])
  }

  @Test
  fun `off-screen remembered bounds clamp without forcing legitimate coordinates positive`() {
    val primary =
        screen("primary", bounds(0, 0, 1920, 1080), bounds(0, 0, 1920, 1040))
    val provider = MutableScreenProvider(ScreenLayout(listOf(primary), "primary"))
    val window =
        FakeWindow(FullscreenWindowSnapshot(bounds(5000, -3000, 800, 600), "primary", false))
    val controller = controller(window, provider)

    controller.enterFullscreen()
    controller.exitFullscreen()

    assertEquals(WindowOperation.Bounds(bounds(1120, 0, 800, 600)), window.operations[6])
  }

  @Test
  fun `screen smaller than minimum uses the complete usable screen`() {
    val tiny = screen("tiny", bounds(-100, -100, 120, 90), bounds(-95, -95, 100, 70))
    val provider = MutableScreenProvider(ScreenLayout(listOf(tiny), "tiny"))
    val window =
        FakeWindow(FullscreenWindowSnapshot(bounds(-90, -90, 5, 5), "tiny", false))
    val controller = controller(window, provider, DesktopSize(320, 288))

    controller.enterFullscreen()
    controller.exitFullscreen()

    assertEquals(WindowOperation.Bounds(tiny.usableBounds), window.operations[6])
  }

  @Test
  fun `missing screen ID uses overlap then nearest geometry deterministically`() {
    val primary = screen("primary", bounds(0, 0, 1000, 800))
    val right = screen("right", bounds(1000, 0, 1000, 800))
    val layout = ScreenLayout(listOf(right, primary), "primary")

    assertEquals(
        "right",
        layout.resolve("disconnected", bounds(1500, 100, 300, 300)).screenId,
    )
    assertEquals(
        "right",
        layout.resolve("disconnected", bounds(2400, 100, 200, 200)).screenId,
    )
    // The reference is equidistant/touching both screens, so the declared primary wins.
    assertEquals(
        "primary",
        layout.resolve("disconnected", bounds(999, 900, 2, 10)).screenId,
    )
  }

  @Test
  fun `all transition entry points reject non-EDT access before observing dependencies`() {
    val primary = screen("primary", bounds(0, 0, 1000, 800))
    val provider = MutableScreenProvider(ScreenLayout(listOf(primary), "primary"))
    val window =
        FakeWindow(FullscreenWindowSnapshot(bounds(100, 100, 500, 400), "primary", false))
    val controller =
        FullscreenController(
            window,
            provider,
            DesktopSize(320, 288),
            EdtOwnership { false },
        )

    assertFailsWith<IllegalStateException> { controller.enterFullscreen() }
    assertFailsWith<IllegalStateException> { controller.exitFullscreen() }
    assertFailsWith<IllegalStateException> { controller.toggleFullscreen() }
    assertFailsWith<IllegalStateException> { controller.refreshScreens() }

    assertFalse(controller.isFullscreen())
    assertTrue(window.operations.isEmpty())
    assertEquals(0, provider.snapshotCalls)
  }

  @Test
  fun `screen snapshots validate transforms IDs and usable geometry`() {
    assertFailsWith<IllegalArgumentException> {
      screen("", bounds(0, 0, 100, 100))
    }
    assertFailsWith<IllegalArgumentException> {
      screen("screen", bounds(0, 0, 100, 100), bounds(-1, 0, 100, 100))
    }
    assertFailsWith<IllegalArgumentException> {
      screen("screen", bounds(0, 0, 100, 100), scaleX = Double.NaN)
    }
    assertFailsWith<IllegalArgumentException> {
      ScreenLayout(
          listOf(
              screen("duplicate", bounds(0, 0, 100, 100)),
              screen("duplicate", bounds(100, 0, 100, 100)),
          ))
    }

    val mutable = mutableListOf(screen("stable", bounds(0, 0, 100, 100)))
    val layout = ScreenLayout(mutable, "stable")
    mutable.clear()
    assertEquals(listOf("stable"), layout.screens.map(ScreenSnapshot::screenId))
  }

  private fun controller(
      window: FakeWindow,
      provider: MutableScreenProvider,
      minimum: DesktopSize = DesktopSize(320, 288),
  ): FullscreenController =
      FullscreenController(window, provider, minimum, EdtOwnership { true })

  private fun screen(
      id: String,
      full: DesktopBounds,
      usable: DesktopBounds = full,
      scaleX: Double = 1.0,
      scaleY: Double = 1.0,
  ): ScreenSnapshot = ScreenSnapshot(id, full, usable, scaleX, scaleY)

  private fun bounds(x: Int, y: Int, width: Int, height: Int): DesktopBounds =
      DesktopBounds(x, y, width, height)

  private class MutableScreenProvider(var layout: ScreenLayout) : ScreenLayoutProvider {
    var snapshotCalls = 0

    override fun snapshot(): ScreenLayout {
      snapshotCalls++
      return layout
    }
  }

  private class FakeWindow(initial: FullscreenWindowSnapshot) : FullscreenWindow {
    var current = initial
    val operations = mutableListOf<WindowOperation>()

    override fun snapshot(): FullscreenWindowSnapshot = current

    override fun dispose() {
      operations += WindowOperation.Dispose
    }

    override fun setUndecorated(undecorated: Boolean) {
      operations += WindowOperation.Decoration(undecorated)
      current = current.copy(undecorated = undecorated)
    }

    override fun setBounds(bounds: DesktopBounds) {
      operations += WindowOperation.Bounds(bounds)
      current = current.copy(bounds = bounds)
    }

    override fun showWindow() {
      operations += WindowOperation.Show
    }
  }

  private sealed interface WindowOperation {
    data object Dispose : WindowOperation

    data class Decoration(val undecorated: Boolean) : WindowOperation

    data class Bounds(val bounds: DesktopBounds) : WindowOperation

    data object Show : WindowOperation
  }
}
