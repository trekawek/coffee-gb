package eu.rekawek.coffeegb.swing

import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.Toolkit
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.Timer

/** Small runtime surface used by the display-settings coordinator and its headless tests. */
internal interface FullscreenRuntime {
  fun isFullscreen(): Boolean

  fun setFullscreen(fullscreen: Boolean)

  fun refreshScreens()

  fun close()
}

/**
 * Production AWT adapter for [FullscreenController].
 *
 * Monitor snapshots are rebuilt for every transition/refresh, so stale
 * [java.awt.GraphicsDevice] instances and per-monitor transforms are never retained. The refresh
 * timer runs only while fullscreen and covers monitor removal even when AWT emits no frame move.
 */
internal class DesktopFullscreenRuntime(
    private val frame: JFrame,
    minimumContentSize: DesktopSize,
) : FullscreenRuntime {
  private var transitioning = false
  private val controller =
      FullscreenController(
          JFrameFullscreenWindow(frame),
          AwtScreenLayoutProvider(frame),
          minimumContentSize,
      )
  private val refreshTimer =
      Timer(SCREEN_REFRESH_MILLIS) { controller.refreshScreens() }.apply {
        isRepeats = true
        isCoalesce = true
      }
  private val componentListener =
      object : ComponentAdapter() {
        override fun componentMoved(event: ComponentEvent) = refreshScreens()

        override fun componentResized(event: ComponentEvent) = refreshScreens()

        override fun componentShown(event: ComponentEvent) = refreshScreens()
      }

  init {
    requireEdt()
    frame.addComponentListener(componentListener)
  }

  override fun isFullscreen(): Boolean = controller.isFullscreen()

  override fun setFullscreen(fullscreen: Boolean) {
    requireEdt()
    if (fullscreen == controller.isFullscreen()) return
    transitioning = true
    try {
      if (fullscreen) {
        controller.enterFullscreen()
        refreshTimer.start()
      } else {
        refreshTimer.stop()
        controller.exitFullscreen()
      }
    } finally {
      transitioning = false
    }
  }

  override fun refreshScreens() {
    requireEdt()
    if (transitioning) return
    controller.refreshScreens()
  }

  override fun close() {
    requireEdt()
    refreshTimer.stop()
    frame.removeComponentListener(componentListener)
  }

  private fun requireEdt() {
    check(SwingUtilities.isEventDispatchThread()) {
      "Fullscreen runtime must be accessed on the Event Dispatch Thread"
    }
  }

  private companion object {
    const val SCREEN_REFRESH_MILLIS = 1_000
  }
}

private class JFrameFullscreenWindow(
    private val frame: JFrame,
) : FullscreenWindow {
  override fun snapshot(): FullscreenWindowSnapshot {
    val bounds = frame.bounds
    return FullscreenWindowSnapshot(
        DesktopBounds(
            bounds.x,
            bounds.y,
            bounds.width.coerceAtLeast(1),
            bounds.height.coerceAtLeast(1),
        ),
        frame.graphicsConfiguration?.device?.iDstring,
        frame.isUndecorated,
    )
  }

  override fun dispose() = frame.dispose()

  override fun setUndecorated(undecorated: Boolean) {
    frame.isUndecorated = undecorated
  }

  override fun setBounds(bounds: DesktopBounds) {
    frame.setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
  }

  override fun showWindow() {
    frame.isVisible = true
  }
}

private class AwtScreenLayoutProvider(
    private val frame: JFrame,
) : ScreenLayoutProvider {
  override fun snapshot(): ScreenLayout {
    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val defaultDevice = environment.defaultScreenDevice
    val screens =
        environment.screenDevices.map { device ->
          val configuration = device.defaultConfiguration
          ScreenSnapshot(
              screenId = device.iDstring,
              fullBounds = configuration.bounds.toDesktopBounds(),
              usableBounds = configuration.usableBounds(),
              scaleX = configuration.defaultTransform.scaleX,
              scaleY = configuration.defaultTransform.scaleY,
          )
        }
    val currentId = frame.graphicsConfiguration?.device?.iDstring
    return ScreenLayout(
        screens,
        primaryScreenId =
            defaultDevice.iDstring.takeIf { primary ->
              screens.any { it.screenId == primary }
            } ?: currentId,
    )
  }
}

private fun GraphicsConfiguration.usableBounds(): DesktopBounds {
  val full = bounds
  val insets =
      try {
        Toolkit.getDefaultToolkit().getScreenInsets(this)
      } catch (_: RuntimeException) {
        Insets(0, 0, 0, 0)
      }
  val x = full.x.toLong() + insets.left
  val y = full.y.toLong() + insets.top
  val width = full.width.toLong() - insets.left - insets.right
  val height = full.height.toLong() - insets.top - insets.bottom
  if (
      width <= 0 ||
          height <= 0 ||
          x < Int.MIN_VALUE ||
          x > Int.MAX_VALUE ||
          y < Int.MIN_VALUE ||
          y > Int.MAX_VALUE
  ) {
    return full.toDesktopBounds()
  }
  return DesktopBounds(x.toInt(), y.toInt(), width.toInt(), height.toInt())
}

private fun java.awt.Rectangle.toDesktopBounds(): DesktopBounds =
    DesktopBounds(x, y, width, height)
