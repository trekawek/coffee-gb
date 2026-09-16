package eu.rekawek.coffeegb.swing

import java.awt.Component
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * Leaves the first translation press to Swing's normal action routing, then suppresses repeats
 * until its physical key is released. Holding the shortcut cannot cancel and submit API requests
 * repeatedly. Mouse/menu actions remain independent, and native focus transitions release the latch.
 */
internal class DesktopTranslationShortcutDispatcher private constructor(
    private val registry: KeyEventDispatcherRegistry,
    private val lifecycle: EscapeSequenceLifecycleRegistry,
    private val belongsToMainWindow: (Component?) -> Boolean,
    private val shortcut: () -> KeyStroke?,
) : KeyEventDispatcher, AutoCloseable {
  private val lock = Any()
  private var installed = false
  private var capturedKey: Int? = null
  private val reset: () -> Unit = { synchronized(lock) { capturedKey = null } }

  constructor(
      mainWindow: Window,
      shortcut: () -> KeyStroke?,
      focusManager: KeyboardFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager(),
  ) : this(
      KeyboardFocusManagerDispatcherRegistry(focusManager),
      WindowEscapeSequenceLifecycleRegistry(mainWindow),
      belongsToMainWindow = { component ->
        component === mainWindow ||
            (component != null && SwingUtilities.getWindowAncestor(component) === mainWindow)
      },
      shortcut,
  )

  internal constructor(
      registry: KeyEventDispatcherRegistry,
      shortcut: () -> KeyStroke?,
      lifecycle: EscapeSequenceLifecycleRegistry = EscapeSequenceLifecycleRegistry.NOOP,
      belongsToMainWindow: (Component?) -> Boolean = { true },
      @Suppress("UNUSED_PARAMETER") testSeam: Unit = Unit,
  ) : this(registry, lifecycle, belongsToMainWindow, shortcut)

  fun install() {
    synchronized(lock) {
      if (installed) return
      registry.add(this)
      try {
        lifecycle.add(reset)
        installed = true
      } catch (failure: RuntimeException) {
        registry.remove(this)
        throw failure
      }
    }
  }

  override fun close() {
    synchronized(lock) {
      if (!installed) return
      try {
        lifecycle.remove(reset)
      } finally {
        registry.remove(this)
        installed = false
        capturedKey = null
      }
    }
  }

  override fun dispatchKeyEvent(event: KeyEvent): Boolean =
      synchronized(lock) {
        if (event.id == KeyEvent.KEY_TYPED) return@synchronized false
        val keyCode = event.keyCode
        if (!belongsToMainWindow(event.component)) {
          if (event.id == KeyEvent.KEY_RELEASED && capturedKey == keyCode) capturedKey = null
          return@synchronized false
        }
        if (capturedKey == keyCode) {
          when (event.id) {
            KeyEvent.KEY_PRESSED -> return@synchronized true
            KeyEvent.KEY_RELEASED -> {
              capturedKey = null
              return@synchronized true
            }
          }
        }
        if (event.id == KeyEvent.KEY_PRESSED &&
            KeyStroke.getKeyStrokeForEvent(event) == shortcut()) {
          capturedKey = keyCode
        }
        false
      }
}
