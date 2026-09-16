package eu.rekawek.coffeegb.swing

import java.awt.Component
import java.awt.KeyEventDispatcher
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JPanel
import javax.swing.KeyStroke
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class DesktopTranslationShortcutDispatcherTest {
  @Test
  fun `holding the shortcut reaches its Swing action only once until physical release`() {
    listOf(InputEvent.CTRL_DOWN_MASK, InputEvent.META_DOWN_MASK).forEach { menuMask ->
      val fixture = Fixture(menuMask)
      fixture.dispatcher.install()

      assertFalse(fixture.press())
      repeat(12) { assertTrue(fixture.press()) }
      assertEquals(1, fixture.actionCalls)

      // Releasing a modifier before T must not turn repeats into a new action or gameplay input.
      assertTrue(fixture.press(modifiers = 0))
      assertTrue(fixture.release(modifiers = 0))
      assertFalse(fixture.press())
      repeat(12) { assertTrue(fixture.press()) }
      assertEquals(2, fixture.actionCalls)
      assertTrue(fixture.release())
      fixture.dispatcher.close()
    }
  }

  @Test
  fun `unrelated keys and owned dialog input keep their normal routing`() {
    val fixture = Fixture()
    fixture.dispatcher.install()

    assertFalse(fixture.press(modifiers = 0))
    assertFalse(fixture.press(keyCode = KeyEvent.VK_R))
    assertFalse(fixture.press(component = fixture.dialog))
    assertEquals(0, fixture.actionCalls)

    assertFalse(fixture.press())
    assertFalse(fixture.release(component = fixture.dialog))
    assertFalse(fixture.press())
    assertEquals(2, fixture.actionCalls)
    fixture.dispatcher.close()
  }

  @Test
  fun `focus loss and disposal clear captures whose release was lost`() {
    val fixture = Fixture()
    fixture.dispatcher.install()
    fixture.dispatcher.install()
    assertEquals(1, fixture.registry.dispatchers.size)

    assertFalse(fixture.press())
    fixture.lifecycle.reset?.invoke()
    assertFalse(fixture.press())
    assertEquals(2, fixture.actionCalls)

    fixture.dispatcher.close()
    fixture.dispatcher.close()
    assertTrue(fixture.registry.dispatchers.isEmpty())
    assertEquals(null, fixture.lifecycle.reset)
    fixture.dispatcher.install()
    assertFalse(fixture.press())
    assertEquals(3, fixture.actionCalls)
    fixture.dispatcher.close()
  }

  private class Fixture(menuMask: Int = InputEvent.CTRL_DOWN_MASK) {
    val main = JPanel()
    val dialog = JPanel()
    val registry = Registry()
    val lifecycle = Lifecycle()
    val modifiers = menuMask or InputEvent.SHIFT_DOWN_MASK
    val shortcut = KeyStroke.getKeyStroke(KeyEvent.VK_T, modifiers)
    var actionCalls = 0
    val dispatcher =
        DesktopTranslationShortcutDispatcher(
            registry,
            shortcut = { shortcut },
            lifecycle = lifecycle,
            belongsToMainWindow = { it === main },
        )

    fun press(
        modifiers: Int = this.modifiers,
        keyCode: Int = KeyEvent.VK_T,
        component: Component = main,
    ): Boolean {
      val event = event(KeyEvent.KEY_PRESSED, modifiers, keyCode, component)
      val consumed = registry.dispatch(event)
      // Swing sees only events which the earlier dispatcher did not consume.
      if (!consumed && component === main && KeyStroke.getKeyStrokeForEvent(event) == shortcut) {
        actionCalls++
      }
      return consumed
    }

    fun release(modifiers: Int = this.modifiers, component: Component = main): Boolean =
        registry.dispatch(event(KeyEvent.KEY_RELEASED, modifiers, KeyEvent.VK_T, component))

    private fun event(id: Int, modifiers: Int, keyCode: Int, component: Component): KeyEvent =
        KeyEvent(component, id, 1L, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED)
  }

  private class Registry : KeyEventDispatcherRegistry {
    val dispatchers = mutableListOf<KeyEventDispatcher>()

    override fun add(dispatcher: KeyEventDispatcher) {
      dispatchers += dispatcher
    }

    override fun remove(dispatcher: KeyEventDispatcher) {
      dispatchers -= dispatcher
    }

    fun dispatch(event: KeyEvent): Boolean = dispatchers.toList().any { it.dispatchKeyEvent(event) }
  }

  private class Lifecycle : EscapeSequenceLifecycleRegistry {
    var reset: (() -> Unit)? = null

    override fun add(reset: () -> Unit) {
      this.reset = reset
    }

    override fun remove(reset: () -> Unit) {
      if (this.reset === reset) this.reset = null
    }
  }
}
