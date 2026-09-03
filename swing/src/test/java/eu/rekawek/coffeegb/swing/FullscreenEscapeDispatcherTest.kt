package eu.rekawek.coffeegb.swing

import java.awt.KeyEventDispatcher
import java.awt.event.KeyEvent
import java.util.concurrent.FutureTask
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class FullscreenEscapeDispatcherTest {

  @Test
  fun `install close and reinstall are idempotent`() =
      onEdt {
        val registry = RecordingRegistry()
        val lifecycle = RecordingLifecycleRegistry()
        val dispatcher = dispatcher(registry, lifecycleRegistry = lifecycle)

        dispatcher.install()
        dispatcher.install()
        assertEquals(1, registry.addCount)
        assertEquals(1, registry.dispatchers.size)
        assertEquals(1, lifecycle.addCount)

        dispatcher.close()
        dispatcher.close()
        assertEquals(1, registry.removeCount)
        assertTrue(registry.dispatchers.isEmpty())
        assertEquals(1, lifecycle.removeCount)

        dispatcher.install()
        assertEquals(2, registry.addCount)
        assertEquals(1, registry.dispatchers.size)
        assertEquals(2, lifecycle.addCount)
        dispatcher.close()
        assertEquals(2, registry.removeCount)
        assertEquals(2, lifecycle.removeCount)
      }

  @Test
  fun `escape opens the menu once and consumes repeats and release without exiting fullscreen`() =
      onEdt {
        val registry = RecordingRegistry()
        val mainComponent = JPanel()
        var fullscreen = true
        var menuVisible = false
        var openCount = 0
        var exitCount = 0
        val dispatcher =
            dispatcher(
                registry,
                belongsToMainWindow = { it === mainComponent },
                isFullscreen = { fullscreen },
                openMenu = {
                  if (!menuVisible) {
                    menuVisible = true
                    openCount++
                  }
                },
                exitFullscreen = {
                  exitCount++
                  fullscreen = false
                },
            )
        dispatcher.install()

        assertTrue(registry.dispatch(key(mainComponent, KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE)))
        assertEquals(1, openCount)
        assertEquals(0, exitCount)
        assertTrue(registry.dispatch(key(mainComponent, KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE)))
        assertTrue(registry.dispatch(key(mainComponent, KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE)))
        assertEquals(1, openCount)
        assertTrue(registry.dispatch(key(mainComponent, KeyEvent.KEY_RELEASED, KeyEvent.VK_ESCAPE)))
        assertEquals(0, exitCount)

        // Opening is idempotent while the overlay is visible, but Escape still owns its complete
        // sequence and cannot become route-local Back/Resume input.
        assertTrue(registry.dispatch(key(mainComponent, KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE)))
        assertTrue(registry.dispatch(key(mainComponent, KeyEvent.KEY_RELEASED, KeyEvent.VK_ESCAPE)))
        assertEquals(1, openCount)
        assertEquals(0, exitCount)
        dispatcher.close()
      }

  @Test
  fun `main-window fullscreen F11 exits once and consumes the complete key sequence`() =
      onEdt {
        val registry = RecordingRegistry()
        val mainComponent = JPanel()
        var fullscreen = true
        var exitCount = 0
        val dispatcher =
            dispatcher(
                registry,
                belongsToMainWindow = { it === mainComponent },
                isFullscreen = { fullscreen },
                exitFullscreen = {
                  exitCount++
                  fullscreen = false
                },
            )
        dispatcher.install()

        assertTrue(registry.dispatch(key(mainComponent, KeyEvent.KEY_PRESSED, KeyEvent.VK_F11)))
        assertEquals(1, exitCount)
        assertTrue(registry.dispatch(key(mainComponent, KeyEvent.KEY_PRESSED, KeyEvent.VK_F11)))
        assertTrue(registry.dispatch(key(mainComponent, KeyEvent.KEY_RELEASED, KeyEvent.VK_F11)))
        assertFalse(registry.dispatch(key(mainComponent, KeyEvent.KEY_PRESSED, KeyEvent.VK_F11)))
        assertEquals(1, exitCount)
        dispatcher.close()
      }

  @Test
  fun `main-window fullscreen extended F11 exits when the ordinary code is undefined`() =
      onEdt {
        val registry = RecordingRegistry()
        val mainComponent = JPanel()
        var fullscreen = true
        var exitCount = 0
        val dispatcher =
            dispatcher(
                registry,
                belongsToMainWindow = { it === mainComponent },
                isFullscreen = { fullscreen },
                exitFullscreen = {
                  exitCount++
                  fullscreen = false
                },
            )
        dispatcher.install()

        assertTrue(
            registry.dispatch(
                extendedKey(mainComponent, KeyEvent.KEY_PRESSED, KeyEvent.VK_F11)))
        assertEquals(1, exitCount)
        assertTrue(
            registry.dispatch(
                extendedKey(mainComponent, KeyEvent.KEY_RELEASED, KeyEvent.VK_F11)))
        assertFalse(
            registry.dispatch(
                extendedKey(mainComponent, KeyEvent.KEY_PRESSED, KeyEvent.VK_F11)))
        dispatcher.close()
      }

  @Test
  fun `lost release during fullscreen peer transition cannot latch the next F11`() =
      onEdt {
        val registry = RecordingRegistry()
        val lifecycle = RecordingLifecycleRegistry()
        val mainComponent = JPanel()
        var fullscreen = true
        var exitCount = 0
        val dispatcher =
            dispatcher(
                registry,
                belongsToMainWindow = { it === mainComponent },
                isFullscreen = { fullscreen },
                exitFullscreen = {
                  exitCount++
                  fullscreen = false
                },
                lifecycleRegistry = lifecycle,
            )
        dispatcher.install()

        assertTrue(registry.dispatch(key(mainComponent, KeyEvent.KEY_PRESSED, KeyEvent.VK_F11)))
        assertEquals(1, exitCount)

        // Disposing the fullscreen native peer can lose KEY_RELEASED on Windows. Window
        // deactivation/closure resets the capture before the replacement peer accepts input.
        lifecycle.transition()
        fullscreen = true
        assertTrue(registry.dispatch(key(mainComponent, KeyEvent.KEY_PRESSED, KeyEvent.VK_F11)))
        assertEquals(2, exitCount)
        assertTrue(registry.dispatch(key(mainComponent, KeyEvent.KEY_RELEASED, KeyEvent.VK_F11)))
        dispatcher.close()
      }

  @Test
  fun `windowed escape opens the menu while dialog and unrelated keys pass through`() =
      onEdt {
        val registry = RecordingRegistry()
        val mainComponent = JPanel()
        val dialogComponent = JPanel()
        var fullscreen = false
        var openCount = 0
        var exitCount = 0
        val dispatcher =
            dispatcher(
                registry,
                belongsToMainWindow = { it === mainComponent },
                isFullscreen = { fullscreen },
                openMenu = { openCount++ },
                exitFullscreen = { exitCount++ },
            )
        dispatcher.install()

        assertTrue(registry.dispatch(key(mainComponent, KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE)))
        assertTrue(registry.dispatch(key(mainComponent, KeyEvent.KEY_RELEASED, KeyEvent.VK_ESCAPE)))
        fullscreen = true
        assertFalse(registry.dispatch(key(dialogComponent, KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE)))
        assertFalse(registry.dispatch(key(dialogComponent, KeyEvent.KEY_RELEASED, KeyEvent.VK_ESCAPE)))
        assertFalse(registry.dispatch(key(mainComponent, KeyEvent.KEY_PRESSED, KeyEvent.VK_F10)))
        assertFalse(registry.dispatch(key(mainComponent, KeyEvent.KEY_RELEASED, KeyEvent.VK_F10)))
        assertEquals(1, openCount)
        assertEquals(0, exitCount)
        dispatcher.close()
      }

  @Test
  fun `dialog release clears a captured main-window sequence without consuming the dialog key`() =
      onEdt {
        val registry = RecordingRegistry()
        val mainComponent = JPanel()
        val dialogComponent = JPanel()
        var fullscreen = true
        var openCount = 0
        var exitCount = 0
        val dispatcher =
            dispatcher(
                registry,
                belongsToMainWindow = { it === mainComponent },
                isFullscreen = { fullscreen },
                openMenu = { openCount++ },
                exitFullscreen = {
                  exitCount++
                  fullscreen = false
                },
            )
        dispatcher.install()

        assertTrue(registry.dispatch(key(mainComponent, KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE)))
        assertFalse(registry.dispatch(key(dialogComponent, KeyEvent.KEY_RELEASED, KeyEvent.VK_ESCAPE)))
        assertTrue(registry.dispatch(key(mainComponent, KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE)))
        assertEquals(2, openCount)
        assertEquals(0, exitCount)
        assertTrue(registry.dispatch(key(mainComponent, KeyEvent.KEY_RELEASED, KeyEvent.VK_ESCAPE)))
        dispatcher.close()
      }

  @Test
  fun `active native menu retains escape ownership`() =
      onEdt {
        val registry = RecordingRegistry()
        val mainComponent = JPanel()
        var nativeMenuActive = true
        var openCount = 0
        val dispatcher =
            dispatcher(
                registry,
                belongsToMainWindow = { it === mainComponent },
                isMenuActive = { nativeMenuActive },
                openMenu = { openCount++ },
            )
        dispatcher.install()

        assertFalse(registry.dispatch(key(mainComponent, KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE)))
        nativeMenuActive = false
        assertFalse(registry.dispatch(key(mainComponent, KeyEvent.KEY_RELEASED, KeyEvent.VK_ESCAPE)))
        assertEquals(0, openCount)

        assertTrue(registry.dispatch(key(mainComponent, KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE)))
        assertTrue(registry.dispatch(key(mainComponent, KeyEvent.KEY_RELEASED, KeyEvent.VK_ESCAPE)))
        assertEquals(1, openCount)
        dispatcher.close()
      }

  @Test
  fun `closing dispatcher before settings teardown rejects later fullscreen escape`() =
      onEdt {
        val registry = RecordingRegistry()
        val mainComponent = JPanel()
        var exitCount = 0
        val dispatcher =
            dispatcher(
                registry,
                belongsToMainWindow = { it === mainComponent },
                isFullscreen = { true },
                exitFullscreen = { exitCount++ },
            )
        dispatcher.install()

        dispatcher.close()

        assertFalse(registry.dispatch(key(mainComponent, KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE)))
        assertEquals(0, exitCount)
      }

  private fun dispatcher(
      registry: RecordingRegistry,
      belongsToMainWindow: (java.awt.Component?) -> Boolean = { true },
      isMenuActive: () -> Boolean = { false },
      isFullscreen: () -> Boolean = { false },
      openMenu: () -> Unit = {},
      exitFullscreen: () -> Unit = {},
      lifecycleRegistry: EscapeSequenceLifecycleRegistry = EscapeSequenceLifecycleRegistry.NOOP,
  ) =
      FullscreenEscapeDispatcher(
          registry = registry,
          belongsToMainWindow = belongsToMainWindow,
          isMenuActive = isMenuActive,
          isFullscreen = isFullscreen,
          openMenu = openMenu,
          exitFullscreen = exitFullscreen,
          lifecycleRegistry = lifecycleRegistry,
      )

  private fun key(
      component: JPanel,
      id: Int,
      keyCode: Int,
  ): KeyEvent =
      KeyEvent(
          component,
          id,
          1L,
          0,
          keyCode,
          KeyEvent.CHAR_UNDEFINED,
      )

  private fun extendedKey(
      component: JPanel,
      id: Int,
      extendedKeyCode: Int,
  ): KeyEvent =
      object : KeyEvent(
          component,
          id,
          1L,
          0,
          KeyEvent.VK_UNDEFINED,
          KeyEvent.CHAR_UNDEFINED,
      ) {
        override fun getExtendedKeyCode(): Int = extendedKeyCode
      }

  private class RecordingRegistry : KeyEventDispatcherRegistry {
    val dispatchers = linkedSetOf<KeyEventDispatcher>()
    var addCount = 0
    var removeCount = 0

    override fun add(dispatcher: KeyEventDispatcher) {
      addCount++
      dispatchers += dispatcher
    }

    override fun remove(dispatcher: KeyEventDispatcher) {
      removeCount++
      dispatchers -= dispatcher
    }

    fun dispatch(event: KeyEvent): Boolean = dispatchers.any { it.dispatchKeyEvent(event) }
  }

  private class RecordingLifecycleRegistry : EscapeSequenceLifecycleRegistry {
    private var reset: (() -> Unit)? = null
    var addCount = 0
    var removeCount = 0

    override fun add(reset: () -> Unit) {
      addCount++
      this.reset = reset
    }

    override fun remove(reset: () -> Unit) {
      removeCount++
      if (this.reset === reset) this.reset = null
    }

    fun transition() = checkNotNull(reset).invoke()
  }

  private fun <T> onEdt(action: () -> T): T {
    val task = FutureTask(action)
    SwingUtilities.invokeAndWait(task)
    return task.get()
  }
}
