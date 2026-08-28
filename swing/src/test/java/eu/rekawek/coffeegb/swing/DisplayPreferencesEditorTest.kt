package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import java.awt.Component
import java.awt.Container
import java.awt.event.KeyEvent
import java.util.concurrent.FutureTask
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class DisplayPreferencesEditorTest {

  @Test
  fun `all display fields produce one validated immutable draft`() =
      onEdt {
        val editor = DisplayPreferencesEditor(ApplicationSettings.Display())

        selectScale(editor, 4)
        editor.letterboxColor.text = "#1a2B3c"
        editor.fullscreen.isSelected = true
        selectRotation(editor, ApplicationSettings.Rotation.DEG_270)
        editor.grayscale.isSelected = true
        editor.blending.isSelected = false
        editor.colorCorrection.isSelected = false
        editor.showSgbBorder.isSelected = true

        assertEquals(
            ApplicationSettings.Display(
                scalingMode = ApplicationSettings.DisplayScalingMode.EXPLICIT,
                explicitScale = 4,
                letterboxColor = 0x1A2B3C,
                fullscreen = true,
                grayscale = true,
                blending = false,
                colorCorrection = false,
                rotation = ApplicationSettings.Rotation.DEG_270,
                showSgbBorder = true,
            ),
            editor.validatedDisplay(),
        )
      }

  @Test
  fun `window scale exposes only supported resize commands and persists explicit mode`() =
      onEdt {
        val initial =
            ApplicationSettings.Display(
                scalingMode = ApplicationSettings.DisplayScalingMode.INTEGER_FIT,
                explicitScale = 3,
            )
        val editor = DisplayPreferencesEditor(initial)

        assertTrue(editor.explicitScale.isEnabled)
        assertFalse(editor.windowScaleCommandRequested)
        assertEquals(
            (1..5).toList(),
            (0 until editor.explicitScale.itemCount)
                .map(editor.explicitScale::getItemAt)
                .map { it.scale },
        )
        assertEquals(
            3,
            (editor.explicitScale.selectedItem as DisplayPreferencesEditor.ScaleOption).scale,
        )
        assertEquals(initial, editor.validatedDisplay())

        selectScale(editor, 5)
        assertTrue(editor.windowScaleCommandRequested)
        assertEquals(
            ApplicationSettings.DisplayScalingMode.EXPLICIT,
            editor.validatedDisplay().scalingMode,
        )
        assertEquals(5, editor.validatedDisplay().explicitScale)
      }

  @Test
  fun `untouched legacy fit modes preserve their exact persisted scale`() =
      onEdt {
        for (mode in
            listOf(
                ApplicationSettings.DisplayScalingMode.INTEGER_FIT,
                ApplicationSettings.DisplayScalingMode.ASPECT_FIT,
            )) {
          val initial =
              ApplicationSettings.Display(
                  scalingMode = mode,
                  explicitScale = 3,
              )
          val editor = DisplayPreferencesEditor(initial)

          assertEquals(initial, editor.validatedDisplay())
          assertFalse(editor.windowScaleCommandRequested)
        }
      }

  @Test
  fun `reselecting the current preference scale remains an explicit window-size command`() =
      onEdt {
        val editor = DisplayPreferencesEditor(ApplicationSettings.Display(explicitScale = 2))
        assertFalse(editor.windowScaleCommandRequested)

        selectScale(editor, 2)

        assertTrue(editor.windowScaleCommandRequested)
      }

  @Test
  fun `letterbox field updates its preview and reports malformed RGB at the field`() =
      onEdt {
        val editor =
            DisplayPreferencesEditor(
                ApplicationSettings.Display(letterboxColor = 0x112233))

        editor.letterboxColor.text = "#aBcDeF"
        assertEquals(0xABCDEF, editor.letterboxPreview.background.rgb and 0xFFFFFF)
        assertEquals("#ABCDEF", editor.letterboxPreview.accessibleContext.accessibleDescription)
        assertEquals(0xABCDEF, editor.validatedDisplay().letterboxColor)

        editor.letterboxColor.text = "ABCDEF"
        val failure =
            assertFailsWith<PreferenceEditorValidationException> {
              editor.validatedDisplay()
            }
        assertSame(editor.letterboxColor, failure.invalidComponent)
        assertEquals("Enter a color in #RRGGBB form.", editor.letterboxColorError.text)
        assertEquals(0xABCDEF, editor.letterboxPreview.background.rgb and 0xFFFFFF)
      }

  @Test
  fun `restore defaults changes the draft and canonicalizes the window scale mode`() =
      onEdt {
        val defaults =
            ApplicationSettings.Display(
                scalingMode = ApplicationSettings.DisplayScalingMode.ASPECT_FIT,
                explicitScale = 1,
                letterboxColor = 0x445566,
                fullscreen = true,
                grayscale = true,
                blending = false,
                colorCorrection = false,
                rotation = ApplicationSettings.Rotation.DEG_180,
                showSgbBorder = true,
            )
        val editor =
            DisplayPreferencesEditor(
                ApplicationSettings.Display(
                    scalingMode = ApplicationSettings.DisplayScalingMode.EXPLICIT,
                    explicitScale = 4,
                    letterboxColor = 0xFFFFFF,
                ),
                defaults,
            )

        editor.restoreDefaults()

        assertEquals(
            defaults.copy(scalingMode = ApplicationSettings.DisplayScalingMode.EXPLICIT),
            editor.validatedDisplay(),
        )
        assertEquals("#445566", editor.letterboxColor.text)
        assertTrue(editor.explicitScale.isEnabled)
        assertTrue(editor.windowScaleCommandRequested)
      }

  @Test
  fun `display controls have accessible names label associations and mnemonics`() =
      onEdt {
        val editor = DisplayPreferencesEditor(ApplicationSettings.Display())
        val labels = descendants(editor).filterIsInstance<JLabel>().toList()

        assertSame(
            editor.explicitScale,
            labels.single { it.text == "Window scale:" }.labelFor,
        )
        assertSame(
            editor.letterboxColor,
            labels.single { it.text == "Letterbox color:" }.labelFor,
        )
        assertSame(
            editor.rotation,
            labels.single { it.text == "Rotation:" }.labelFor,
        )
        assertEquals(
            KeyEvent.VK_W,
            labels.single { it.text == "Window scale:" }.displayedMnemonic,
        )
        assertEquals("Window scale", editor.explicitScale.accessibleContext.accessibleName)
        assertEquals(
            "Letterbox color preview",
            editor.letterboxPreview.accessibleContext.accessibleName,
        )
        assertEquals("Fullscreen", editor.fullscreen.accessibleContext.accessibleName)
      }

  @Test
  fun `display editor rejects construction outside the Event Dispatch Thread`() {
    assertFailsWith<IllegalStateException> {
      DisplayPreferencesEditor(ApplicationSettings.Display())
    }
  }

  private fun selectScale(editor: DisplayPreferencesEditor, scale: Int) {
    editor.explicitScale.selectedItem =
        (0 until editor.explicitScale.itemCount)
            .map(editor.explicitScale::getItemAt)
            .single { it.scale == scale }
  }

  private fun selectRotation(
      editor: DisplayPreferencesEditor,
      rotation: ApplicationSettings.Rotation,
  ) {
    editor.rotation.selectedItem =
        (0 until editor.rotation.itemCount)
            .map(editor.rotation::getItemAt)
            .single { it.rotation == rotation }
  }

  private fun descendants(component: Component): Sequence<Component> =
      sequence {
        yield(component)
        if (component is Container) {
          component.components.forEach { yieldAll(descendants(it)) }
        }
      }

  private fun <T> onEdt(action: () -> T): T {
    val task = FutureTask(action)
    SwingUtilities.invokeAndWait(task)
    return task.get()
  }
}
