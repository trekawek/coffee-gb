package eu.rekawek.coffeegb.swing

import java.awt.Dialog
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.KeyboardFocusManager
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JFrame
import javax.swing.JMenuBar
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
  private val chromeVisibility = FullscreenChromeVisibility()
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
        chromeVisibility.hide(frame.jMenuBar)
        try {
          controller.enterFullscreen()
        } catch (failure: RuntimeException) {
          chromeVisibility.restore()
          throw failure
        }
        refreshTimer.start()
      } else {
        refreshTimer.stop()
        controller.exitFullscreen()
        chromeVisibility.restore()
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

/** Retains the exact menu-bar visibility across the native-peer fullscreen transition. */
internal class FullscreenChromeVisibility {
  private var retained: RetainedChrome? = null

  fun hide(menuBar: JMenuBar?) {
    if (retained != null) return
    retained = RetainedChrome(menuBar, menuBar?.isVisible == true)
    menuBar?.isVisible = false
  }

  fun restore() {
    val previous = retained ?: return
    retained = null
    previous.menuBar?.isVisible = previous.visible
  }

  private data class RetainedChrome(
      val menuBar: JMenuBar?,
      val visible: Boolean,
  )
}

private class JFrameFullscreenWindow(
    private val frame: JFrame,
) : FullscreenWindow {
  private var peerTransitionActive = false
  private var retainedOwnedWindows = emptyList<RetainedOwnedWindow>()

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

  override fun beginPeerTransition() {
    check(!peerTransitionActive) { "A fullscreen peer transition is already active" }
    peerTransitionActive = true
    val activeWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
    retainedOwnedWindows =
        ownedWindowTree(frame)
            .filter { (owned, _) ->
              owned.isVisible && isRetainableModelessWindow(owned)
            }
            .map { (owned, depth) ->
              RetainedOwnedWindow(
                  window = owned,
                  bounds = Rectangle(owned.bounds),
                  active = owned === activeWindow,
                  depth = depth,
              )
            }
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

  override fun endPeerTransition() {
    val retained = retainedOwnedWindows
    retainedOwnedWindows = emptyList()
    peerTransitionActive = false
    // Disposing an owner makes every descendant undisplayable. Recreate each retained peer in
    // ownership order so nested tools never race a still-undisplayable parent.
    retained.sortedBy(RetainedOwnedWindow::depth).forEach { entry ->
      entry.window.bounds = entry.bounds
      entry.window.isVisible = true
    }
    retained.firstOrNull { it.active }?.window?.let { active ->
      active.toFront()
      active.requestFocus()
    }
  }

  private data class RetainedOwnedWindow(
      val window: Window,
      val bounds: Rectangle,
      val active: Boolean,
      val depth: Int,
  )

  private fun ownedWindowTree(root: Window): List<Pair<Window, Int>> {
    val result = mutableListOf<Pair<Window, Int>>()
    fun visit(owner: Window, depth: Int) {
      owner.ownedWindows.forEach { owned ->
        result += owned to depth
        visit(owned, depth + 1)
      }
    }
    visit(root, 0)
    return result
  }

  private fun isRetainableModelessWindow(window: Window): Boolean =
      window !is Dialog || window.modalityType == Dialog.ModalityType.MODELESS
}

internal class AwtScreenLayoutProvider(
    private val window: Window,
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
    val currentId = window.graphicsConfiguration?.device?.iDstring
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
