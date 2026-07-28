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

        selectScalingMode(editor, ApplicationSettings.DisplayScalingMode.INTEGER_FIT)
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
                scalingMode = ApplicationSettings.DisplayScalingMode.INTEGER_FIT,
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
  fun `explicit scale is enabled only in explicit mode without losing its draft value`() =
      onEdt {
        val editor =
            DisplayPreferencesEditor(
                ApplicationSettings.Display(
                    scalingMode = ApplicationSettings.DisplayScalingMode.EXPLICIT,
                    explicitScale = 3,
                ))

        assertTrue(editor.explicitScale.isEnabled)
        selectScalingMode(editor, ApplicationSettings.DisplayScalingMode.ASPECT_FIT)
        assertFalse(editor.explicitScale.isEnabled)
        selectScalingMode(editor, ApplicationSettings.DisplayScalingMode.INTEGER_FIT)
        assertFalse(editor.explicitScale.isEnabled)
        selectScalingMode(editor, ApplicationSettings.DisplayScalingMode.EXPLICIT)
        assertTrue(editor.explicitScale.isEnabled)
        assertEquals(3, editor.validatedDisplay().explicitScale)
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
  fun `restore defaults changes the draft only and refreshes dependent controls`() =
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

        assertEquals(defaults, editor.validatedDisplay())
        assertEquals("#445566", editor.letterboxColor.text)
        assertFalse(editor.explicitScale.isEnabled)
      }

  @Test
  fun `display controls have accessible names label associations and mnemonics`() =
      onEdt {
        val editor = DisplayPreferencesEditor(ApplicationSettings.Display())
        val labels = descendants(editor).filterIsInstance<JLabel>().toList()

        assertSame(
            editor.scalingMode,
            labels.single { it.text == "Scaling mode:" }.labelFor,
        )
        assertSame(
            editor.explicitScale,
            labels.single { it.text == "Explicit scale:" }.labelFor,
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
            KeyEvent.VK_S,
            labels.single { it.text == "Scaling mode:" }.displayedMnemonic,
        )
        assertEquals("Display scaling mode", editor.scalingMode.accessibleContext.accessibleName)
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

  private fun selectScalingMode(
      editor: DisplayPreferencesEditor,
      mode: ApplicationSettings.DisplayScalingMode,
  ) {
    editor.scalingMode.selectedItem =
        (0 until editor.scalingMode.itemCount)
            .map(editor.scalingMode::getItemAt)
            .single { it.mode == mode }
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
