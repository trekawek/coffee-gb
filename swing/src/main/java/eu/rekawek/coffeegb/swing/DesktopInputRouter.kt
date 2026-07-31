package eu.rekawek.coffeegb.swing

import java.awt.Component
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.beans.PropertyChangeListener
import javax.swing.AbstractButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JMenu
import javax.swing.JScrollBar
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTree
import javax.swing.MenuSelectionManager
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

/**
 * Routes unmodified gameplay keys while the main window owns focus and yields every other desktop
 * context to normal Swing dispatch.
 *
 * A press captures its matching release for one input owner. Leaving game scope clears every
 * capture and releases all transient input first, so focus, menu, dialog, debugger, mapping, and
 * native-peer transitions cannot leave a Game Boy button, rewind, or tilt direction latched.
 */
internal class DesktopInputRouter private constructor(
    private val registry: KeyEventDispatcherRegistry,
    private val lifecycle: DesktopInputLifecycleRegistry,
    private val belongsToMainWindow: (Component?) -> Boolean,
    private val yieldsToComponent: (Component?) -> Boolean,
    private val isMenuActive: () -> Boolean,
    private val joypadHandles: (Int) -> Boolean,
    private val joypadPressed: (KeyEvent) -> Unit,
    private val joypadReleased: (KeyEvent) -> Unit,
    private val tiltHandles: (Int) -> Boolean,
    private val tiltPressed: (KeyEvent) -> Unit,
    private val tiltReleased: (KeyEvent) -> Unit,
    private val releaseAll: () -> Unit,
) : KeyEventDispatcher, AutoCloseable {
  private enum class Owner {
    JOYPAD,
    TILT,
  }

  private val lock = Any()
  private val captured = mutableMapOf<Int, Owner>()
  private var installed = false
  private val releaseScope: () -> Unit = ::releaseScope

  constructor(
      mainWindow: Window,
      joypadHandles: (Int) -> Boolean,
      joypadPressed: (KeyEvent) -> Unit,
      joypadReleased: (KeyEvent) -> Unit,
      tiltHandles: (Int) -> Boolean,
      tiltPressed: (KeyEvent) -> Unit,
      tiltReleased: (KeyEvent) -> Unit,
      releaseAll: () -> Unit,
      focusManager: KeyboardFocusManager =
          KeyboardFocusManager.getCurrentKeyboardFocusManager(),
  ) : this(
      DesktopKeyboardFocusManagerDispatcherRegistry(focusManager),
      WindowDesktopInputLifecycleRegistry(mainWindow, focusManager),
      belongsToMainWindow = { component -> component.belongsToInputWindow(mainWindow) },
      yieldsToComponent = ::componentOwnsDesktopKeys,
      isMenuActive = { MenuSelectionManager.defaultManager().selectedPath.isNotEmpty() },
      joypadHandles,
      joypadPressed,
      joypadReleased,
      tiltHandles,
      tiltPressed,
      tiltReleased,
      releaseAll,
  )

  /** Headless seam used by the scope and physical-key-sequence tests. */
  internal constructor(
      registry: KeyEventDispatcherRegistry,
      lifecycle: DesktopInputLifecycleRegistry = DesktopInputLifecycleRegistry.NOOP,
      belongsToMainWindow: (Component?) -> Boolean = { true },
      yieldsToComponent: (Component?) -> Boolean = { false },
      isMenuActive: () -> Boolean = { false },
      joypadHandles: (Int) -> Boolean,
      joypadPressed: (KeyEvent) -> Unit,
      joypadReleased: (KeyEvent) -> Unit,
      tiltHandles: (Int) -> Boolean,
      tiltPressed: (KeyEvent) -> Unit,
      tiltReleased: (KeyEvent) -> Unit,
      releaseAll: () -> Unit,
      @Suppress("UNUSED_PARAMETER") testSeam: Unit = Unit,
  ) : this(
      registry,
      lifecycle,
      belongsToMainWindow,
      yieldsToComponent,
      isMenuActive,
      joypadHandles,
      joypadPressed,
      joypadReleased,
      tiltHandles,
      tiltPressed,
      tiltReleased,
      releaseAll,
  )

  fun install() {
    synchronized(lock) {
      if (installed) return
      registry.add(this)
      try {
        lifecycle.add(releaseScope)
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
      lifecycle.remove(releaseScope)
      registry.remove(this)
      installed = false
    }
    releaseScope()
  }

  /** Releases current input before a mapping/controller/context owner changes. */
  fun releaseForOwnershipChange() = releaseScope()

  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    val keyCode = event.keyCode
    if (event.id == KeyEvent.KEY_TYPED) {
      return synchronized(lock) { captured.isNotEmpty() }
    }
    if (keyCode == KeyEvent.VK_UNDEFINED) return false

    if (!belongsToMainWindow(event.component) ||
        yieldsToComponent(event.component) ||
        isMenuActive() ||
        hasDesktopCommandModifier(event)) {
      releaseScope()
      return false
    }

    return when (event.id) {
      KeyEvent.KEY_PRESSED -> captureAndPress(event)
      KeyEvent.KEY_RELEASED -> releaseCaptured(event)
      else -> false
    }
  }

  private fun captureAndPress(event: KeyEvent): Boolean {
    val owner =
        synchronized(lock) {
          captured[event.keyCode]
              ?: when {
                joypadHandles(event.keyCode) -> Owner.JOYPAD
                tiltHandles(event.keyCode) -> Owner.TILT
                else -> null
              }?.also { captured[event.keyCode] = it }
        } ?: return false
    when (owner) {
      Owner.JOYPAD -> joypadPressed(event)
      Owner.TILT -> tiltPressed(event)
    }
    return true
  }

  private fun releaseCaptured(event: KeyEvent): Boolean {
    val owner = synchronized(lock) { captured.remove(event.keyCode) } ?: return false
    when (owner) {
      Owner.JOYPAD -> joypadReleased(event)
      Owner.TILT -> tiltReleased(event)
    }
    return true
  }

  private fun releaseScope() {
    synchronized(lock) { captured.clear() }
    // Scope transitions also have to clear producers whose native release was already lost. The
    // release operation is idempotent, so call it even without a router-owned key capture.
    releaseAll()
  }

  companion object {
    const val INPUT_CAPTURE_PROPERTY = "coffee-gb.input-capture"
  }
}

private fun hasDesktopCommandModifier(event: KeyEvent): Boolean =
    event.modifiersEx and
        (InputEvent.CTRL_DOWN_MASK or InputEvent.META_DOWN_MASK or InputEvent.ALT_DOWN_MASK) != 0

internal fun componentOwnsDesktopKeys(component: Component?): Boolean {
  var current = component
  while (current != null) {
    if (current is JTextComponent ||
        current is JMenu ||
        current is AbstractButton ||
        current is JComboBox<*> ||
        current is JList<*> ||
        current is JTable ||
        current is JTree ||
        current is JSpinner ||
        current is JSlider ||
        current is JScrollBar ||
        current is JTabbedPane) {
      return true
    }
    if ((current as? JComponent)?.getClientProperty(DesktopInputRouter.INPUT_CAPTURE_PROPERTY) ==
        true) {
      return true
    }
    current = current.parent
  }
  return false
}

private fun Component?.belongsToInputWindow(window: Window): Boolean =
    this === window || (this != null && SwingUtilities.getWindowAncestor(this) === window)

private class DesktopKeyboardFocusManagerDispatcherRegistry(
    private val focusManager: KeyboardFocusManager,
) : KeyEventDispatcherRegistry {
  override fun add(dispatcher: KeyEventDispatcher) {
    focusManager.addKeyEventDispatcher(dispatcher)
  }

  override fun remove(dispatcher: KeyEventDispatcher) {
    focusManager.removeKeyEventDispatcher(dispatcher)
  }
}

internal interface DesktopInputLifecycleRegistry {
  fun add(release: () -> Unit)

  fun remove(release: () -> Unit)

  companion object {
    val NOOP =
        object : DesktopInputLifecycleRegistry {
          override fun add(release: () -> Unit) = Unit

          override fun remove(release: () -> Unit) = Unit
        }
  }
}

private class WindowDesktopInputLifecycleRegistry(
    private val window: Window,
    private val focusManager: KeyboardFocusManager,
) : DesktopInputLifecycleRegistry {
  private var release: (() -> Unit)? = null
  private val focusListener = PropertyChangeListener { release?.invoke() }
  private val listener =
      object : WindowAdapter() {
        override fun windowLostFocus(event: WindowEvent) {
          releaseNow()
        }

        override fun windowDeactivated(event: WindowEvent) {
          releaseNow()
        }

        override fun windowClosed(event: WindowEvent) {
          releaseNow()
        }

        private fun releaseNow() {
          this@WindowDesktopInputLifecycleRegistry.release?.invoke()
        }
      }

  override fun add(release: () -> Unit) {
    check(this.release == null) { "Desktop input lifecycle listener is already installed" }
    this.release = release
    window.addWindowFocusListener(listener)
    window.addWindowListener(listener)
    focusManager.addPropertyChangeListener("permanentFocusOwner", focusListener)
  }

  override fun remove(release: () -> Unit) {
    if (this.release !== release) return
    window.removeWindowFocusListener(listener)
    window.removeWindowListener(listener)
    focusManager.removePropertyChangeListener("permanentFocusOwner", focusListener)
    this.release = null
  }
}
