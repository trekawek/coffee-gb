package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.EmulatorProperties
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowStateListener
import javax.swing.JFrame
import javax.swing.Timer

internal const val DESKTOP_WINDOW_SIZE_COMMIT_DELAY_MILLIS = 250

/** Persisted desktop geometry access kept separate from editable display preferences. */
internal interface DesktopWindowSizeSettings {
  fun current(): ApplicationSettings.WindowSize?

  fun canPersist(): Boolean

  fun replace(size: ApplicationSettings.WindowSize)
}

/** Headless seam for the small set of JFrame operations needed by size persistence. */
internal interface DesktopWindowSizeHost {
  fun currentSize(): DesktopSize

  fun minimumSize(): DesktopSize

  fun maximumSize(): DesktopSize

  fun resize(size: DesktopSize)

  fun isNormalWindow(): Boolean

  fun listen(listener: () -> Unit): DesktopWindowResizeSubscription
}

internal fun interface DesktopWindowResizeSubscription {
  fun remove()
}

/** Coalesces live-resize events while retaining an explicit close-time flush boundary. */
internal interface DesktopWindowSizeCommitScheduler {
  fun restart(action: () -> Unit)

  fun cancel()
}

/**
 * EDT-owned launch-to-launch persistence for the last normal, windowed outer-frame size.
 *
 * Fullscreen and maximized bounds are transient host state and must never replace the user's
 * windowed size. The listener is removed before settings closure so a late dispose/resize event
 * cannot update a closed store.
 */
internal class DesktopWindowSizeController(
    private val settings: DesktopWindowSizeSettings,
    private val host: DesktopWindowSizeHost,
    private val commitScheduler: DesktopWindowSizeCommitScheduler =
        SwingDesktopWindowSizeCommitScheduler(),
    private val edtOwnership: EdtOwnership = EdtOwnership.SWING,
) {
  private var subscription: DesktopWindowResizeSubscription? = null
  private var observedSize: ApplicationSettings.WindowSize? = null
  private var installed = false
  private var closed = false

  constructor(
      properties: EmulatorProperties,
      frame: JFrame,
      isFullscreen: () -> Boolean,
  ) : this(
      EmulatorDesktopWindowSizeSettings(properties),
      JFrameDesktopWindowSizeHost(frame, isFullscreen),
  )

  /** Applies a saved size after the frame has been packed and its minimum has been established. */
  fun restore() {
    requireEdt()
    check(subscription == null) { "Desktop window size must be restored before listening" }
    check(!closed) { "Desktop window size controller is closed" }
    val saved = settings.current() ?: return
    val restored =
        clampRestoredWindowSize(
            saved,
            minimum = host.minimumSize(),
            maximum = host.maximumSize(),
        )
    host.resize(restored)
    // AWT and the host window manager may adjust setSize(), so canonicalize what was accepted.
    val canonical = host.currentSize().toApplicationWindowSize()
    observedSize = canonical
    if (canonical != saved) {
      persist(canonical)
    }
  }

  /** Starts observing after startup layout/fullscreen application has reached a stable state. */
  fun install() {
    requireEdt()
    check(!closed) { "Desktop window size controller is closed" }
    if (installed) return
    attach()
  }

  /** Captures the exact last size and detaches while a recoverable settings close is attempted. */
  fun suspend() {
    requireEdt()
    check(!closed) { "Desktop window size controller is closed" }
    if (!installed) return
    commitScheduler.cancel()
    captureSettledNormalWindowSize()
    subscription?.remove()
    subscription = null
    installed = false
  }

  /** Reattaches after a recoverable settings-close failure retained the desktop window. */
  fun resume() {
    requireEdt()
    check(!closed) { "Desktop window size controller is closed" }
    if (installed) return
    attach()
  }

  /** Captures any final normal size and permanently prevents later settings access. */
  fun close() {
    requireEdt()
    if (closed) return
    suspend()
    commitScheduler.cancel()
    closed = true
  }

  private fun attach() {
    subscription = host.listen(::scheduleSettledCapture)
    installed = true

    val current = host.currentSize().toApplicationWindowSize()
    val restoredBaseline = observedSize
    if (restoredBaseline == null) {
      // Establish a baseline without turning the initial pack into a persisted user resize.
      observedSize = current
    } else if (host.isNormalWindow() && current != restoredBaseline) {
      // Showing, restoring, or rearming a frame may reveal a final host geometry adjustment.
      scheduleSettledCapture()
    }
  }

  private fun scheduleSettledCapture() {
    requireEdt()
    if (closed || !installed) return
    // Resize and window-state events can arrive in either order. Sample only after both settle.
    commitScheduler.restart(::captureSettledNormalWindowSize)
  }

  private fun captureSettledNormalWindowSize() {
    requireEdt()
    if (closed || !installed || !host.isNormalWindow()) return
    val current = host.currentSize().toApplicationWindowSize()
    if (current == observedSize) return
    observedSize = current
    persist(current)
  }

  private fun persist(size: ApplicationSettings.WindowSize) {
    if (settings.canPersist()) {
      settings.replace(size)
    }
  }

  private fun requireEdt() {
    check(edtOwnership.isEventDispatchThread()) {
      "Desktop window size persistence must run on the Event Dispatch Thread"
    }
  }
}

/** Clamps an independently saved width and height to the current host's usable range. */
internal fun clampRestoredWindowSize(
    saved: ApplicationSettings.WindowSize,
    minimum: DesktopSize,
    maximum: DesktopSize,
): DesktopSize {
  val minimumWidth = minOf(minimum.width, maximum.width)
  val minimumHeight = minOf(minimum.height, maximum.height)
  return DesktopSize(
      saved.width.coerceIn(minimumWidth, maximum.width),
      saved.height.coerceIn(minimumHeight, maximum.height),
  )
}

private fun DesktopSize.toApplicationWindowSize(): ApplicationSettings.WindowSize =
    ApplicationSettings.WindowSize(width, height)

private class EmulatorDesktopWindowSizeSettings(
    private val properties: EmulatorProperties,
) : DesktopWindowSizeSettings {
  override fun current(): ApplicationSettings.WindowSize? =
      properties.applicationSettings.desktop.windowSize

  override fun canPersist(): Boolean = !properties.isReadOnly()

  override fun replace(size: ApplicationSettings.WindowSize) {
    properties.updateApplicationSettings { current ->
      current.copy(desktop = current.desktop.copy(windowSize = size))
    }
  }
}

private class SwingDesktopWindowSizeCommitScheduler : DesktopWindowSizeCommitScheduler {
  private var pending: (() -> Unit)? = null
  private val timer =
      Timer(DESKTOP_WINDOW_SIZE_COMMIT_DELAY_MILLIS) {
        val action = pending
        pending = null
        action?.invoke()
      }.apply { isRepeats = false }

  override fun restart(action: () -> Unit) {
    pending = action
    timer.restart()
  }

  override fun cancel() {
    timer.stop()
    pending = null
  }
}

private class JFrameDesktopWindowSizeHost(
    private val frame: JFrame,
    private val isFullscreen: () -> Boolean,
) : DesktopWindowSizeHost {
  override fun currentSize(): DesktopSize =
      DesktopSize(frame.width.coerceAtLeast(1), frame.height.coerceAtLeast(1))

  override fun minimumSize(): DesktopSize =
      DesktopSize(frame.minimumSize.width.coerceAtLeast(1), frame.minimumSize.height.coerceAtLeast(1))

  override fun maximumSize(): DesktopSize {
    val usable = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    return DesktopSize(usable.width.coerceAtLeast(1), usable.height.coerceAtLeast(1))
  }

  override fun resize(size: DesktopSize) {
    frame.setSize(size.width, size.height)
  }

  override fun isNormalWindow(): Boolean {
    val transientStates = Frame.ICONIFIED or Frame.MAXIMIZED_BOTH
    return !isFullscreen() && frame.extendedState and transientStates == 0
  }

  override fun listen(listener: () -> Unit): DesktopWindowResizeSubscription {
    val componentAdapter =
        object : ComponentAdapter() {
          override fun componentResized(event: ComponentEvent) = listener()
        }
    val windowStateListener = WindowStateListener { listener() }
    frame.addComponentListener(componentAdapter)
    frame.addWindowStateListener(windowStateListener)
    return DesktopWindowResizeSubscription {
      frame.removeWindowStateListener(windowStateListener)
      frame.removeComponentListener(componentAdapter)
    }
  }
}
