package eu.rekawek.coffeegb.swing

import java.awt.Component
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

/**
 * Reserves Escape for leaving fullscreen without taking it away from windowed emulator input or
 * from owned dialogs.
 *
 * One captured press owns the complete physical-key sequence. The exit action may synchronously
 * leave fullscreen, but auto-repeat presses and the matching release are still consumed until the
 * key returns to neutral. A native-peer focus/deactivation/close transition clears the capture
 * because some window systems discard that release while recreating the fullscreen frame.
 */
internal class FullscreenEscapeDispatcher private constructor(
    private val registry: KeyEventDispatcherRegistry,
    private val lifecycleRegistry: EscapeSequenceLifecycleRegistry,
    private val belongsToMainWindow: (Component?) -> Boolean,
    private val isFullscreen: () -> Boolean,
    private val exitFullscreen: () -> Unit,
) : KeyEventDispatcher, AutoCloseable {
  private val lifecycleLock = Any()
  private var installed = false
  private var escapeSequenceCaptured = false
  private val resetEscapeSequence: () -> Unit = {
    synchronized(lifecycleLock) { escapeSequenceCaptured = false }
  }

  constructor(
      mainWindow: Window,
      isFullscreen: () -> Boolean,
      exitFullscreen: () -> Unit,
      focusManager: KeyboardFocusManager =
          KeyboardFocusManager.getCurrentKeyboardFocusManager(),
  ) : this(
      KeyboardFocusManagerDispatcherRegistry(focusManager),
      WindowEscapeSequenceLifecycleRegistry(mainWindow),
      belongsToMainWindow = { component -> component.belongsTo(mainWindow) },
      isFullscreen,
      exitFullscreen,
  )

  /** Test seam that avoids constructing a top-level AWT window in headless environments. */
  internal constructor(
      registry: KeyEventDispatcherRegistry,
      belongsToMainWindow: (Component?) -> Boolean,
      isFullscreen: () -> Boolean,
      exitFullscreen: () -> Unit,
      lifecycleRegistry: EscapeSequenceLifecycleRegistry = EscapeSequenceLifecycleRegistry.NOOP,
      @Suppress("UNUSED_PARAMETER") testSeam: Unit = Unit,
  ) : this(registry, lifecycleRegistry, belongsToMainWindow, isFullscreen, exitFullscreen)

  fun install() {
    synchronized(lifecycleLock) {
      if (installed) return
      registry.add(this)
      try {
        lifecycleRegistry.add(resetEscapeSequence)
        installed = true
      } catch (failure: RuntimeException) {
        registry.remove(this)
        throw failure
      }
    }
  }

  override fun close() {
    synchronized(lifecycleLock) {
      if (!installed) return
      try {
        lifecycleRegistry.remove(resetEscapeSequence)
      } finally {
        registry.remove(this)
        installed = false
        escapeSequenceCaptured = false
      }
    }
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    var requestExit = false
    val consume =
        synchronized(lifecycleLock) {
          if (event.keyCode != KeyEvent.VK_ESCAPE) {
            return@synchronized false
          }
          if (!belongsToMainWindow(event.component)) {
            if (event.id == KeyEvent.KEY_RELEASED) {
              escapeSequenceCaptured = false
            }
            return@synchronized false
          }

          when (event.id) {
            KeyEvent.KEY_PRESSED -> {
              if (escapeSequenceCaptured) {
                true
              } else if (isFullscreen()) {
                escapeSequenceCaptured = true
                requestExit = true
                true
              } else {
                false
              }
            }
            KeyEvent.KEY_RELEASED -> {
              val captured = escapeSequenceCaptured
              escapeSequenceCaptured = false
              captured
            }
            else -> false
          }
        }
    if (requestExit) {
      exitFullscreen()
    }
    return consume
  }
}

/** Minimal registry seam around the process-wide AWT focus manager. */
internal interface KeyEventDispatcherRegistry {
  fun add(dispatcher: KeyEventDispatcher)

  fun remove(dispatcher: KeyEventDispatcher)
}

/** Clears a captured sequence when a fullscreen peer transition can discard its key release. */
internal interface EscapeSequenceLifecycleRegistry {
  fun add(reset: () -> Unit)

  fun remove(reset: () -> Unit)

  companion object {
    val NOOP =
        object : EscapeSequenceLifecycleRegistry {
          override fun add(reset: () -> Unit) = Unit

          override fun remove(reset: () -> Unit) = Unit
        }
  }
}

private class KeyboardFocusManagerDispatcherRegistry(
    private val focusManager: KeyboardFocusManager,
) : KeyEventDispatcherRegistry {
  override fun add(dispatcher: KeyEventDispatcher) {
    focusManager.addKeyEventDispatcher(dispatcher)
  }

  override fun remove(dispatcher: KeyEventDispatcher) {
    focusManager.removeKeyEventDispatcher(dispatcher)
  }
}

private class WindowEscapeSequenceLifecycleRegistry(
    private val window: Window,
) : EscapeSequenceLifecycleRegistry {
  private var reset: (() -> Unit)? = null
  private val listener =
      object : WindowAdapter() {
        override fun windowLostFocus(event: WindowEvent) = reset()

        override fun windowDeactivated(event: WindowEvent) = reset()

        override fun windowClosed(event: WindowEvent) = reset()

        private fun reset() {
          this@WindowEscapeSequenceLifecycleRegistry.reset?.invoke()
        }
      }

  override fun add(reset: () -> Unit) {
    check(this.reset == null) { "Fullscreen Escape lifecycle listener is already installed" }
    this.reset = reset
    window.addWindowFocusListener(listener)
    window.addWindowListener(listener)
  }

  override fun remove(reset: () -> Unit) {
    if (this.reset !== reset) return
    window.removeWindowFocusListener(listener)
    window.removeWindowListener(listener)
    this.reset = null
  }
}

private fun Component?.belongsTo(window: Window): Boolean =
    this === window || (this != null && SwingUtilities.getWindowAncestor(this) === window)
