package eu.rekawek.coffeegb.swing

import java.awt.Dimension
import java.awt.Rectangle

/**
 * Pure geometry policy shared by ordinary desktop windows and the retained debugger workspace.
 *
 * Persisted coordinates are allowed on monitors left of or above the primary screen, but they are
 * numerically bounded. Restoration additionally requires a useful part of the window to remain on
 * a current display, then clamps the complete outer frame into that display's usable work area.
 */
internal object DesktopWindowPlacement {
  internal const val MINIMUM_COORDINATE = -100_000
  internal const val MAXIMUM_COORDINATE = 100_000
  internal const val MAXIMUM_DIMENSION = 10_000
  internal const val MINIMUM_VISIBLE_WIDTH = 80
  internal const val MINIMUM_VISIBLE_HEIGHT = 60

  fun isPlausible(bounds: DesktopBounds, minimumSize: DesktopSize): Boolean =
      bounds.x in MINIMUM_COORDINATE..MAXIMUM_COORDINATE &&
          bounds.y in MINIMUM_COORDINATE..MAXIMUM_COORDINATE &&
          bounds.width in minimumSize.width..MAXIMUM_DIMENSION &&
          bounds.height in minimumSize.height..MAXIMUM_DIMENSION

  fun isPlausible(bounds: Rectangle, minimumSize: Dimension): Boolean =
      bounds.width > 0 &&
          bounds.height > 0 &&
          runCatching { DesktopBounds(bounds.x, bounds.y, bounds.width, bounds.height) }
              .getOrNull()
              ?.let {
                isPlausible(it, DesktopSize(minimumSize.width, minimumSize.height))
              } == true

  fun materiallyIntersectsScreen(bounds: DesktopBounds, layout: ScreenLayout): Boolean =
      layout.screens.any { screen ->
        intersectionWidth(bounds, screen.fullBounds) >= MINIMUM_VISIBLE_WIDTH &&
            intersectionHeight(bounds, screen.fullBounds) >= MINIMUM_VISIBLE_HEIGHT
      }

  /**
   * Validates saved bounds against current screens and returns usable-area-clamped outer bounds.
   * A caller should pack/center its normal default when this returns null.
   */
  fun restorableBounds(
      saved: DesktopBounds?,
      layout: ScreenLayout,
      minimumSize: DesktopSize,
  ): DesktopBounds? {
    if (saved == null || !isPlausible(saved, minimumSize)) return null
    if (!materiallyIntersectsScreen(saved, layout)) return null
    val screen = layout.resolve(null, saved)
    return clampToUsable(saved, screen.usableBounds, minimumSize)
  }

  /** Equivalent Swing/AWT seam for debugger and dialog migration. */
  fun restorableBounds(
      saved: Rectangle?,
      layout: ScreenLayout,
      minimumSize: Dimension,
  ): Rectangle? {
    if (saved == null || minimumSize.width <= 0 || minimumSize.height <= 0) return null
    val desktopBounds = saved.toDesktopBoundsOrNull() ?: return null
    return restorableBounds(
            desktopBounds,
            layout,
            DesktopSize(minimumSize.width, minimumSize.height),
        )
        ?.toRectangle()
  }

  /**
   * Clamps size and location independently, allowing a work area smaller than the normal minimum.
   */
  fun clampToUsable(
      bounds: DesktopBounds,
      usableBounds: DesktopBounds,
      minimumSize: DesktopSize,
  ): DesktopBounds {
    val minimumWidth = minOf(minimumSize.width, usableBounds.width)
    val minimumHeight = minOf(minimumSize.height, usableBounds.height)
    val width = bounds.width.coerceIn(minimumWidth, usableBounds.width)
    val height = bounds.height.coerceIn(minimumHeight, usableBounds.height)
    val maximumX = usableBounds.rightExclusive - width
    val maximumY = usableBounds.bottomExclusive - height
    val x = bounds.x.toLong().coerceIn(usableBounds.x.toLong(), maximumX).toInt()
    val y = bounds.y.toLong().coerceIn(usableBounds.y.toLong(), maximumY).toInt()
    return DesktopBounds(x, y, width, height)
  }

  /** Centers a desired outer-frame size after constraining it to the current usable work area. */
  fun centeredBounds(
      desiredSize: DesktopSize,
      usableBounds: DesktopBounds,
      minimumSize: DesktopSize,
  ): DesktopBounds {
    val minimumWidth = minOf(minimumSize.width, usableBounds.width)
    val minimumHeight = minOf(minimumSize.height, usableBounds.height)
    val width = desiredSize.width.coerceIn(minimumWidth, usableBounds.width)
    val height = desiredSize.height.coerceIn(minimumHeight, usableBounds.height)
    val x = usableBounds.x.toLong() + (usableBounds.width - width) / 2L
    val y = usableBounds.y.toLong() + (usableBounds.height - height) / 2L
    return DesktopBounds(x.toInt(), y.toInt(), width, height)
  }

  private fun intersectionWidth(first: DesktopBounds, second: DesktopBounds): Long =
      (minOf(first.rightExclusive, second.rightExclusive) -
              maxOf(first.x.toLong(), second.x.toLong()))
          .coerceAtLeast(0L)

  private fun intersectionHeight(first: DesktopBounds, second: DesktopBounds): Long =
      (minOf(first.bottomExclusive, second.bottomExclusive) -
              maxOf(first.y.toLong(), second.y.toLong()))
          .coerceAtLeast(0L)

  private fun Rectangle.toDesktopBoundsOrNull(): DesktopBounds? {
    if (width <= 0 || height <= 0) return null
    return runCatching { DesktopBounds(x, y, width, height) }.getOrNull()
  }

  private fun DesktopBounds.toRectangle(): Rectangle = Rectangle(x, y, width, height)
}
