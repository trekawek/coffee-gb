package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.events.register
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.core.events.EventBusImpl
import eu.rekawek.coffeegb.swing.io.DisplayScaleMode
import eu.rekawek.coffeegb.swing.io.SwingDisplay
import java.awt.event.KeyEvent
import java.util.concurrent.FutureTask
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JRadioButtonMenuItem
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ScreenMenuTest {

  @Test
  fun `scale and rotation choices are exclusive radios whose clicks update the coordinator`() {
    Fixture(
            ApplicationSettings.Display(
                scalingMode = ApplicationSettings.DisplayScalingMode.ASPECT_FIT,
                explicitScale = 4,
                rotation = ApplicationSettings.Rotation.DEG_90,
            ))
        .use { fixture ->
          val scale = onScreenMenuEdt { fixture.menu.submenu("Scale") }
          val rotate = onScreenMenuEdt { fixture.menu.submenu("Rotate") }
          onScreenMenuEdt {
            val scaleItems = scale.items()
            val rotateItems = rotate.items()
            assertEquals(
                listOf("Integer fit", "Fit to window", "1x", "2x", "3x", "4x"),
                scaleItems.map { it.text },
            )
            assertTrue(scaleItems.all { it is JRadioButtonMenuItem })
            assertEquals(listOf("Fit to window"), scaleItems.selectedLabels())
            assertEquals(
                listOf("None", "90°", "180°", "270°"),
                rotateItems.map { it.text },
            )
            assertTrue(rotateItems.all { it is JRadioButtonMenuItem })
            assertEquals(listOf("90°"), rotateItems.selectedLabels())
          }

          var runtimeScale: DisplayScaleMode? = null
          fixture.eventBus.register<SwingDisplay.SetScaleModeEvent> {
            runtimeScale = it.mode()
          }
          onScreenMenuEdt { scale.item("3x").doClick() }

          assertEquals(
              ApplicationSettings.DisplayScalingMode.EXPLICIT,
              fixture.settings.display.scalingMode,
          )
          assertEquals(3, fixture.settings.display.explicitScale)
          assertEquals(DisplayScaleMode.EXPLICIT_3X, runtimeScale)
          onScreenMenuEdt {
            assertEquals(listOf("3x"), scale.items().selectedLabels())
          }

          onScreenMenuEdt { rotate.item("270°").doClick() }

          assertEquals(ApplicationSettings.Rotation.DEG_270, fixture.settings.display.rotation)
          onScreenMenuEdt {
            assertEquals(listOf("270°"), rotate.items().selectedLabels())
          }
          assertEquals(2, fixture.settings.replacements)
        }
  }

  @Test
  fun `off-EDT display event synchronizes every control without listener feedback`() {
    Fixture(ApplicationSettings.Display()).use { fixture ->
      val synchronized =
          ApplicationSettings.Display(
              scalingMode = ApplicationSettings.DisplayScalingMode.EXPLICIT,
              explicitScale = 4,
              fullscreen = true,
              grayscale = true,
              blending = false,
              colorCorrection = false,
              rotation = ApplicationSettings.Rotation.DEG_180,
              showSgbBorder = true,
          )
      fixture.settings.display = synchronized

      val publisher =
          Thread(
              { fixture.eventBus.post(DisplaySettingsChangedEvent(synchronized)) },
              "screen-menu-test-publisher",
          )
      publisher.start()
      publisher.join()
      flushScreenMenuEdt()

      onScreenMenuEdt {
        assertEquals(listOf("4x"), fixture.menu.submenu("Scale").items().selectedLabels())
        assertEquals(listOf("180°"), fixture.menu.submenu("Rotate").items().selectedLabels())
        assertTrue(fixture.menu.checkItem("Fullscreen").state)
        assertTrue(fixture.menu.checkItem("DMG grayscale").state)
        assertFalse(fixture.menu.checkItem("LCD ghosting (frame blend)").state)
        assertFalse(fixture.menu.checkItem("CGB color correction").state)
        assertTrue(fixture.menu.checkItem("Show SGB border").state)
      }
      assertEquals(0, fixture.settings.replacements)
    }
  }

  @Test
  fun `F11 toggles fullscreen only while it is free from emulator bindings`() {
    Fixture(ApplicationSettings.Display()).use { fixture ->
      val fullscreen = onScreenMenuEdt { fixture.menu.checkItem("Fullscreen") }
      val f11 = KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0)

      onScreenMenuEdt { assertEquals(f11, fullscreen.accelerator) }
      onScreenMenuEdt { fullscreen.doClick() }
      assertTrue(fixture.settings.display.fullscreen)
      assertTrue(fixture.fullscreen.fullscreenState)
      assertEquals(1, fixture.settings.replacements)

      fixture.keyboardBindings +=
          ApplicationSettings.KeyboardKey.fromKeyCode(KeyEvent.VK_F11)
      onScreenMenuEdt {
        fixture.eventBus.post(DisplaySettingsChangedEvent(fixture.settings.display))
      }
      onScreenMenuEdt { assertNull(fullscreen.accelerator) }

      fixture.keyboardBindings.clear()
      onScreenMenuEdt {
        fixture.eventBus.post(DisplaySettingsChangedEvent(fixture.settings.display))
      }
      onScreenMenuEdt { assertEquals(f11, fullscreen.accelerator) }

      onScreenMenuEdt { fullscreen.doClick() }
      assertFalse(fixture.settings.display.fullscreen)
      assertFalse(fixture.fullscreen.fullscreenState)
      assertEquals(2, fixture.settings.replacements)
    }
  }

  private class Fixture(
      initial: ApplicationSettings.Display,
  ) : AutoCloseable {
    val eventBus = EventBusImpl()
    val settings = FakeDisplaySettings(initial)
    val fullscreen = FakeFullscreenRuntime()
    val keyboardBindings = mutableListOf<ApplicationSettings.KeyboardKey>()
    val menu =
        onScreenMenuEdt {
          createScreenMenu(
              DesktopDisplayController(settings, eventBus, fullscreen),
              eventBus,
              keyboardBindings = { keyboardBindings.toList() },
          )
        }

    override fun close() {
      eventBus.close()
    }
  }

  private class FakeDisplaySettings(
      var display: ApplicationSettings.Display,
  ) : DisplaySettingsAccess {
    var replacements = 0

    override fun current(): ApplicationSettings.Display = display

    override fun replace(display: ApplicationSettings.Display) {
      this.display = display
      replacements++
    }
  }

  private class FakeFullscreenRuntime : FullscreenRuntime {
    var fullscreenState = false

    override fun isFullscreen(): Boolean = fullscreenState

    override fun setFullscreen(fullscreen: Boolean) {
      fullscreenState = fullscreen
    }

    override fun refreshScreens() = Unit

    override fun close() = Unit
  }

  private fun JMenu.submenu(text: String): JMenu =
      items().filterIsInstance<JMenu>().single { it.text == text }

  private fun JMenu.checkItem(text: String): JCheckBoxMenuItem =
      items().filterIsInstance<JCheckBoxMenuItem>().single { it.text == text }

  private fun JMenu.item(text: String): JMenuItem =
      items().single { it.text == text }

  private fun JMenu.items(): List<JMenuItem> =
      (0 until itemCount).mapNotNull(::getItem)

  private fun List<JMenuItem>.selectedLabels(): List<String> =
      filter { it.isSelected }.map { it.text }

}

private fun flushScreenMenuEdt() {
  onScreenMenuEdt {}
}

private fun <T> onScreenMenuEdt(action: () -> T): T {
  val task = FutureTask(action)
  SwingUtilities.invokeAndWait(task)
  return task.get()
}
