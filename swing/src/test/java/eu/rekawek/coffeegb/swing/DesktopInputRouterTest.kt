package eu.rekawek.coffeegb.swing

import java.awt.Component
import java.awt.KeyEventDispatcher
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JMenu
import javax.swing.JPanel
import javax.swing.JScrollBar
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.JTree
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class DesktopInputRouterTest {

  @Test
  fun `one physical key sequence is routed to exactly one owner`() {
    val fixture = Fixture(joypadKeys = setOf(KeyEvent.VK_J), tiltKeys = setOf(KeyEvent.VK_J))
    fixture.router.install()

    assertTrue(fixture.dispatch(press(KeyEvent.VK_J)))
    assertTrue(fixture.dispatch(press(KeyEvent.VK_J)))
    assertTrue(fixture.dispatch(release(KeyEvent.VK_J)))

    assertEquals(listOf("joypad-press", "joypad-press", "joypad-release"), fixture.events)
    assertFalse(fixture.dispatch(release(KeyEvent.VK_J)))
    fixture.router.close()
  }

  @Test
  fun `platform command modifiers and text entry yield after releasing gameplay`() {
    val game = JPanel()
    val text = JTextField()
    val fixture =
        Fixture(
            joypadKeys = setOf(KeyEvent.VK_Z),
            belongsToMainWindow = { it === game || it === text },
            yieldsToComponent = { component, _ -> component === text },
        )
    fixture.router.install()

    assertTrue(fixture.dispatch(press(KeyEvent.VK_Z, component = game)))
    assertFalse(
        fixture.dispatch(
            press(
                KeyEvent.VK_Z,
                component = game,
                modifiers = InputEvent.CTRL_DOWN_MASK,
            )))
    assertEquals(1, fixture.releaseAllCount)
    assertFalse(fixture.dispatch(release(KeyEvent.VK_Z, component = game)))

    assertFalse(fixture.dispatch(press(KeyEvent.VK_Z, component = text)))
    assertEquals(2, fixture.releaseAllCount)
    fixture.router.close()
  }

  @Test
  fun `menus and other windows withdraw main gameplay scope`() {
    val game = JPanel()
    val otherWindow = JPanel()
    var menuActive = false
    val fixture =
        Fixture(
            joypadKeys = setOf(KeyEvent.VK_A),
            belongsToMainWindow = { it === game },
            isMenuActive = { menuActive },
        )
    fixture.router.install()

    assertTrue(fixture.dispatch(press(KeyEvent.VK_A, component = game)))
    menuActive = true
    assertFalse(fixture.dispatch(press(KeyEvent.VK_A, component = game)))
    assertFalse(fixture.dispatch(release(KeyEvent.VK_A, component = game)))
    assertEquals(2, fixture.releaseAllCount)

    menuActive = false
    assertFalse(fixture.dispatch(press(KeyEvent.VK_A, component = otherWindow)))
    assertEquals(3, fixture.releaseAllCount)
    fixture.router.close()
  }

  @Test
  fun `lifecycle transition mapping change and close release every latch`() {
    val lifecycle = RecordingLifecycle()
    val fixture = Fixture(joypadKeys = setOf(KeyEvent.VK_B), lifecycle = lifecycle)

    fixture.router.install()
    fixture.router.install()
    assertEquals(1, fixture.registry.addCount)
    assertTrue(fixture.dispatch(press(KeyEvent.VK_B)))

    lifecycle.transition()
    assertEquals(1, fixture.releaseAllCount)
    assertFalse(fixture.dispatch(release(KeyEvent.VK_B)))

    fixture.router.releaseForOwnershipChange()
    assertEquals(2, fixture.releaseAllCount)

    fixture.router.close()
    fixture.router.close()
    assertEquals(3, fixture.releaseAllCount)
    assertEquals(1, fixture.registry.removeCount)
  }

  @Test
  fun `mapped gameplay keys win over ordinary focused controls`() {
    val controls =
        listOf(
            JButton("Pause"),
            JComboBox(arrayOf("Slot 0", "Slot 1")),
            JList(arrayOf("General", "Display")),
            JTable(2, 2),
            JSpinner(),
            JSlider(),
            JScrollBar(),
            JTabbedPane(),
            JTree(),
        )
    val fixture =
        Fixture(
            joypadKeys =
                setOf(
                    KeyEvent.VK_SPACE,
                    KeyEvent.VK_UP,
                    KeyEvent.VK_ENTER,
                    KeyEvent.VK_ESCAPE,
                ),
            yieldsToComponent = ::componentOwnsDesktopKey,
        )
    fixture.router.install()

    controls.forEach { control ->
      assertTrue(fixture.dispatch(press(KeyEvent.VK_SPACE, component = control)))
      assertTrue(fixture.dispatch(release(KeyEvent.VK_SPACE, component = control)))
      assertTrue(fixture.dispatch(press(KeyEvent.VK_UP, component = control)))
      assertTrue(fixture.dispatch(release(KeyEvent.VK_UP, component = control)))
      assertFalse(fixture.dispatch(press(KeyEvent.VK_DOWN, component = control)))
      assertFalse(fixture.dispatch(press(KeyEvent.VK_ENTER, component = control)))
      assertFalse(fixture.dispatch(press(KeyEvent.VK_ESCAPE, component = control)))
    }
    assertEquals(controls.size * 4, fixture.events.size)
    fixture.router.close()
  }

  @Test
  fun `text menu open selector and explicit capture contexts keep mapped gameplay keys`() {
    val captureOwner =
        JPanel().apply {
          putClientProperty(DesktopInputRouter.INPUT_CAPTURE_PROPERTY, true)
        }
    val captureChild = JButton("Capture")
    captureOwner.add(captureChild)
    val openSelector =
        object : JComboBox<String>(arrayOf("Slot 0", "Slot 1")) {
          override fun isPopupVisible(): Boolean = true
        }
    val controls: List<JComponent> =
        listOf(JTextField(), JMenu("Game"), openSelector, captureChild)
    val fixture =
        Fixture(
            joypadKeys = setOf(KeyEvent.VK_SPACE, KeyEvent.VK_UP),
            yieldsToComponent = ::componentOwnsDesktopKey,
        )
    fixture.router.install()

    controls.forEach { control ->
      assertFalse(fixture.dispatch(press(KeyEvent.VK_SPACE, component = control)))
      assertFalse(fixture.dispatch(press(KeyEvent.VK_UP, component = control)))
    }
    assertTrue(fixture.events.isEmpty())
    fixture.router.close()
  }

  private class Fixture(
      joypadKeys: Set<Int> = emptySet(),
      tiltKeys: Set<Int> = emptySet(),
      val registry: RecordingRegistry = RecordingRegistry(),
      lifecycle: DesktopInputLifecycleRegistry = DesktopInputLifecycleRegistry.NOOP,
      belongsToMainWindow: (Component?) -> Boolean = { true },
      yieldsToComponent: (Component?, Int) -> Boolean = { _, _ -> false },
      isMenuActive: () -> Boolean = { false },
  ) {
    val events = mutableListOf<String>()
    var releaseAllCount = 0
    val router =
        DesktopInputRouter(
            registry = registry,
            lifecycle = lifecycle,
            belongsToMainWindow = belongsToMainWindow,
            yieldsToComponent = yieldsToComponent,
            isMenuActive = isMenuActive,
            joypadHandles = { it in joypadKeys },
            joypadPressed = { events += "joypad-press" },
            joypadReleased = { events += "joypad-release" },
            tiltHandles = { it in tiltKeys },
            tiltPressed = { events += "tilt-press" },
            tiltReleased = { events += "tilt-release" },
            releaseAll = { releaseAllCount++ },
        )

    fun dispatch(event: KeyEvent): Boolean = registry.dispatch(event)
  }

  private class RecordingRegistry : KeyEventDispatcherRegistry {
    private val dispatchers = linkedSetOf<KeyEventDispatcher>()
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

  private class RecordingLifecycle : DesktopInputLifecycleRegistry {
    private var release: (() -> Unit)? = null

    override fun add(release: () -> Unit) {
      this.release = release
    }

    override fun remove(release: () -> Unit) {
      if (this.release === release) this.release = null
    }

    fun transition() = checkNotNull(release).invoke()
  }

  private fun press(
      keyCode: Int,
      component: Component = JPanel(),
      modifiers: Int = 0,
  ) = key(component, KeyEvent.KEY_PRESSED, keyCode, modifiers)

  private fun release(
      keyCode: Int,
      component: Component = JPanel(),
      modifiers: Int = 0,
  ) = key(component, KeyEvent.KEY_RELEASED, keyCode, modifiers)

  private fun key(
      component: Component,
      id: Int,
      keyCode: Int,
      modifiers: Int,
  ) =
      KeyEvent(
          component,
          id,
          1L,
          modifiers,
          keyCode,
          KeyEvent.CHAR_UNDEFINED,
      )
}
