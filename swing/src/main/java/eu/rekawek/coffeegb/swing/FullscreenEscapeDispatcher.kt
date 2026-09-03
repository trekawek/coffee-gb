package eu.rekawek.coffeegb.swing

import java.awt.Component
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.MenuSelectionManager
import javax.swing.SwingUtilities

/**
 * Reserves Escape for toggling the on-screen menu and F11 for leaving fullscreen without taking
 * F11 away from windowed emulator input or either key away from owned dialogs.
 *
 * One captured press owns the complete physical-key sequence. Either action may synchronously
 * change input ownership or recreate the fullscreen frame, but auto-repeat presses and the
 * matching release are still consumed until the key returns to neutral. A native-peer
 * focus/deactivation/close transition clears the capture because some window systems discard that
 * release during the transition.
 */
internal class FullscreenEscapeDispatcher private constructor(
    private val registry: KeyEventDispatcherRegistry,
    private val lifecycleRegistry: EscapeSequenceLifecycleRegistry,
    private val belongsToMainWindow: (Component?) -> Boolean,
    private val isMenuActive: () -> Boolean,
    private val isFullscreen: () -> Boolean,
    private val toggleMenu: () -> Unit,
    private val exitFullscreen: () -> Unit,
) : KeyEventDispatcher, AutoCloseable {
  private val lifecycleLock = Any()
  private var installed = false
  private var capturedSequenceKeyCode: Int? = null
  private val resetCapturedSequence: () -> Unit = {
    synchronized(lifecycleLock) { capturedSequenceKeyCode = null }
  }

  constructor(
      mainWindow: Window,
      isFullscreen: () -> Boolean,
      toggleMenu: () -> Unit,
      exitFullscreen: () -> Unit,
      focusManager: KeyboardFocusManager =
          KeyboardFocusManager.getCurrentKeyboardFocusManager(),
  ) : this(
      KeyboardFocusManagerDispatcherRegistry(focusManager),
      WindowEscapeSequenceLifecycleRegistry(mainWindow),
      belongsToMainWindow = { component -> component.belongsTo(mainWindow) },
      isMenuActive = { MenuSelectionManager.defaultManager().selectedPath.isNotEmpty() },
      isFullscreen,
      toggleMenu,
      exitFullscreen,
  )

  /** Test seam that avoids constructing a top-level AWT window in headless environments. */
  internal constructor(
      registry: KeyEventDispatcherRegistry,
      belongsToMainWindow: (Component?) -> Boolean,
      isMenuActive: () -> Boolean = { false },
      isFullscreen: () -> Boolean,
      toggleMenu: () -> Unit,
      exitFullscreen: () -> Unit,
      lifecycleRegistry: EscapeSequenceLifecycleRegistry = EscapeSequenceLifecycleRegistry.NOOP,
      @Suppress("UNUSED_PARAMETER") testSeam: Unit = Unit,
  ) : this(
      registry,
      lifecycleRegistry,
      belongsToMainWindow,
      isMenuActive,
      isFullscreen,
      toggleMenu,
      exitFullscreen,
  )

  fun install() {
    synchronized(lifecycleLock) {
      if (installed) return
      registry.add(this)
      try {
        lifecycleRegistry.add(resetCapturedSequence)
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
        lifecycleRegistry.remove(resetCapturedSequence)
      } finally {
        registry.remove(this)
        installed = false
        capturedSequenceKeyCode = null
      }
    }
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    var requestMenuToggle = false
    var requestExit = false
    val consume =
        synchronized(lifecycleLock) {
          // The macOS AWT backend can report a function key through its extended code while the
          // ordinary key code is undefined. Treat both representations as the same physical key.
          val keyCode = reservedKeyCode(event) ?: return@synchronized false
          if (keyCode !in RESERVED_KEYS) {
            return@synchronized false
          }
          if (!belongsToMainWindow(event.component)) {
            if (event.id == KeyEvent.KEY_RELEASED &&
                capturedSequenceKeyCode == keyCode) {
              capturedSequenceKeyCode = null
            }
            return@synchronized false
          }

          when (event.id) {
            KeyEvent.KEY_PRESSED -> {
              if (capturedSequenceKeyCode != null) {
                true
              } else if (keyCode == KeyEvent.VK_ESCAPE && !isMenuActive()) {
                capturedSequenceKeyCode = keyCode
                requestMenuToggle = true
                true
              } else if (keyCode == KeyEvent.VK_F11 && isFullscreen()) {
                capturedSequenceKeyCode = keyCode
                requestExit = true
                true
              } else {
                false
              }
            }
            KeyEvent.KEY_RELEASED -> {
              val captured = capturedSequenceKeyCode == keyCode
              if (captured) {
                capturedSequenceKeyCode = null
              }
              captured
            }
            else -> false
          }
        }
    if (requestMenuToggle) {
      toggleMenu()
    }
    if (requestExit) {
      exitFullscreen()
    }
    return consume
  }

  private fun reservedKeyCode(event: KeyEvent): Int? =
      event.keyCode.takeIf { it in RESERVED_KEYS }
          ?: event.extendedKeyCode.takeIf { it in RESERVED_KEYS }

  private companion object {
    val RESERVED_KEYS = setOf(KeyEvent.VK_ESCAPE, KeyEvent.VK_F11)
  }
}

/** Minimal registry seam around the process-wide AWT focus manager. */
internal interface KeyEventDispatcherRegistry {
  fun add(dispatcher: KeyEventDispatcher)

  fun remove(dispatcher: KeyEventDispatcher)
}

/** Clears a captured reserved-key sequence when a native peer transition can discard its release. */
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
    check(this.reset == null) { "Reserved-key lifecycle listener is already installed" }
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
