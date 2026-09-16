package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import java.nio.file.Paths
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class TranslationPreferencesEditorTest {

  @Test
  fun `key is masked until explicitly shown and masked again after theme refresh`() =
      onEdt {
        val editor = TranslationPreferencesEditor(ApplicationSettings.Translation(SAVED_KEY))

        assertTrue(editor.apiKeyField.echoChar != '\u0000')
        assertFalse(editor.showApiKey.isSelected)
        assertEquals(SAVED_KEY, String(editor.apiKeyField.password))
        editor.showApiKey.doClick()
        assertEquals('\u0000', editor.apiKeyField.echoChar)

        editor.desktopThemeChanged(DesktopThemeTokens.capture(DesktopAppearance.SYSTEM))

        assertTrue(editor.apiKeyField.echoChar != '\u0000')
        assertFalse(editor.showApiKey.isSelected)
        assertEquals(SAVED_KEY, editor.validatedTranslation().apiKey)
      }

  @Test
  fun `clear removes only the draft key and leaves environment fallback empty in the field`() =
      onEdt {
        val initial = ApplicationSettings.Translation(SAVED_KEY)
        val editor = TranslationPreferencesEditor(initial)
        editor.showApiKey.doClick()

        editor.clearApiKey.doClick()

        assertEquals("", String(editor.apiKeyField.password))
        assertEquals(ApplicationSettings.Translation(), editor.validatedTranslation())
        assertEquals(SAVED_KEY, initial.apiKey)
        assertTrue(editor.apiKeyField.echoChar != '\u0000')
        assertFalse(editor.clearApiKey.isEnabled)
        assertTrue(editor.guidance.text.contains("OPENAI_API_KEY"))
        assertTrue(editor.guidance.text.contains("without encryption"))
      }

  @Test
  fun `validation trims pasted key and rejects malformed keys without disclosing them`() =
      onEdt {
        val editor = TranslationPreferencesEditor(ApplicationSettings.Translation())
        editor.apiKeyField.text = "  $NEW_KEY  "
        assertEquals(NEW_KEY, editor.validatedTranslation().apiKey)

        for (invalid in listOf("sk-invalid key", "sk-漢字", "s".repeat(4097))) {
          editor.apiKeyField.text = invalid

          val failure =
              assertFailsWith<PreferenceEditorValidationException> {
                editor.validatedTranslation()
              }

          assertSame(editor.apiKeyField, failure.invalidComponent)
          assertFalse(failure.message.orEmpty().contains(invalid))
          assertFalse(editor.draftFingerprint().toString().contains(invalid))
          assertTrue(editor.keyError.text.contains("API key"))
        }
      }

  @Test
  fun `draft fingerprints distinguish edits and redact valid and invalid credentials`() =
      onEdt {
        val editor = TranslationPreferencesEditor(ApplicationSettings.Translation(SAVED_KEY))
        val opening = editor.draftFingerprint()
        editor.apiKeyField.text = NEW_KEY
        val edited = editor.draftFingerprint()
        assertNotEquals(opening, edited)
        assertFalse(opening.toString().contains(SAVED_KEY))
        assertFalse(edited.toString().contains(NEW_KEY))
        editor.apiKeyField.text = SAVED_KEY
        assertEquals(opening, editor.draftFingerprint())
      }

  @Test
  fun `key draft drives dirty state while revealing key does not`() =
      onEdt {
        val dirty = mutableListOf<Boolean>()
        val panel =
            PreferencesPanel(
                ApplicationSettings(translation = ApplicationSettings.Translation(SAVED_KEY)),
                draftChanged = { dirty += it },
            )
        panel.categories.selectedCategory = PreferencesCategory.TRANSLATION
        panel.translationEditor.showApiKey.doClick()
        assertFalse(panel.isDirty())

        panel.translationEditor.apiKeyField.text = NEW_KEY
        assertTrue(panel.isDirty())
        assertEquals(true, dirty.last())

        panel.translationEditor.apiKeyField.text = SAVED_KEY
        assertFalse(panel.isDirty())
        assertEquals(false, dirty.last())

        panel.translationEditor.clearApiKey.doClick()
        assertTrue(panel.isDirty())
        assertEquals("", panel.validatedEdit().translation?.apiKey)
        panel.stopBackgroundWork()
      }

  @Test
  fun `Save applies translation to latest settings while old callers preserve the key`() =
      onEdt {
        val initial = ApplicationSettings(translation = ApplicationSettings.Translation(SAVED_KEY))
        val latest = initial.copy(advanced = initial.advanced.copy(fullChangerCharacter = "LATEST"))
        val panel = PreferencesPanel(initial)
        var saved: ApplicationSettings? = null
        var closed = false
        panel.translationEditor.apiKeyField.text = NEW_KEY
        panel.translationEditor.showApiKey.doClick()
        val edit = panel.validatedEdit()
        assertFalse(edit.toString().contains(NEW_KEY))
        assertEquals(latest.translation, edit.copy(translation = null).applyTo(latest).translation)

        PreferencesDialogActions(
                panel,
                applyEdit = { saved = it.applyTo(latest) },
                close = { closed = true },
            )
            .apply()

        assertEquals(NEW_KEY, saved?.translation?.apiKey)
        assertEquals(latest.advanced.fullChangerCharacter, saved?.advanced?.fullChangerCharacter)
        assertEquals(SAVED_KEY, initial.translation.apiKey)
        assertTrue(closed)
        assertTrue(panel.translationEditor.apiKeyField.echoChar != '\u0000')
      }

  @Test
  fun `Cancel discards key edits and remasks the field`() =
      onEdt {
        val initial = ApplicationSettings(translation = ApplicationSettings.Translation(SAVED_KEY))
        val panel = PreferencesPanel(initial)
        var applyCount = 0
        var closed = false
        panel.translationEditor.apiKeyField.text = NEW_KEY
        panel.translationEditor.showApiKey.doClick()

        PreferencesDialogActions(
                panel,
                applyEdit = { applyCount++ },
                close = { closed = true },
            )
            .cancel()

        assertEquals(0, applyCount)
        assertEquals(SAVED_KEY, initial.translation.apiKey)
        assertTrue(closed)
        assertTrue(panel.translationEditor.apiKeyField.echoChar != '\u0000')
      }

  @Test
  fun `page and all defaults clear the key and navigating away hides it`() =
      onEdt {
        val initial = ApplicationSettings(translation = ApplicationSettings.Translation(SAVED_KEY))
        val panel = PreferencesPanel(initial)
        panel.categories.selectedCategory = PreferencesCategory.TRANSLATION
        panel.translationEditor.showApiKey.doClick()
        panel.categories.selectedCategory = PreferencesCategory.GENERAL
        assertTrue(panel.translationEditor.apiKeyField.echoChar != '\u0000')
        panel.categories.selectedCategory = PreferencesCategory.TRANSLATION

        panel.restoreSelectedPageDefaults()

        assertEquals("", panel.validatedEdit().translation?.apiKey)
        assertEquals(SAVED_KEY, initial.translation.apiKey)
        assertTrue(panel.isDirty())

        panel.translationEditor.apiKeyField.text = NEW_KEY
        panel.restoreAllDefaults()
        assertEquals("", panel.validatedEdit().translation?.apiKey)
        panel.stopBackgroundWork()
      }

  @Test
  fun `invalid key keeps preferences open and selects Translation`() =
      onEdt {
        val panel = PreferencesPanel(ApplicationSettings())
        var applyCount = 0
        var closed = false
        val actions =
            PreferencesDialogActions(
                panel,
                applyEdit = { applyCount++ },
                close = { closed = true },
            )
        panel.translationEditor.apiKeyField.text = "sk-invalid key"

        actions.apply()

        assertEquals(0, applyCount)
        assertFalse(closed)
        assertEquals(PreferencesCategory.TRANSLATION, panel.categories.selectedCategory)
        assertTrue(panel.validationSummary.text.contains("API key"))
        assertFalse(panel.validationSummary.text.contains("sk-invalid key"))
        actions.cancel()
      }

  @Test
  fun `key editing is frozen during background save validation and restored on failure`() {
    var backgroundValidation: Runnable? = null
    var finishValidation: (() -> Unit)? = null
    lateinit var panel: PreferencesPanel
    lateinit var actions: PreferencesDialogActions
    onEdt {
      panel =
          PreferencesPanel(
              ApplicationSettings(
                  saves = ApplicationSettings.Saves(directory = Paths.get("save-data")),
                  translation = ApplicationSettings.Translation(SAVED_KEY),
              ))
      actions =
          PreferencesDialogActions(
              panel,
              applyEdit = { throw AssertionError("Invalid save directory must prevent saving") },
              close = {},
              saveDirectoryValidator = SaveDirectoryValidator { "The directory is not writable." },
              validationExecutor = Executor { backgroundValidation = it },
              uiExecutor = { finishValidation = it },
          )
      panel.translationEditor.showApiKey.doClick()

      actions.apply()

      assertFalse(panel.translationEditor.apiKeyField.isEnabled)
      assertFalse(panel.translationEditor.apiKeyField.isEditable)
      assertFalse(panel.translationEditor.showApiKey.isEnabled)
      assertFalse(panel.translationEditor.clearApiKey.isEnabled)
      assertTrue(panel.translationEditor.apiKeyField.echoChar != '\u0000')
    }

    backgroundValidation!!.run()

    onEdt {
      finishValidation!!.invoke()
      assertTrue(panel.translationEditor.apiKeyField.isEnabled)
      assertTrue(panel.translationEditor.apiKeyField.isEditable)
      assertTrue(panel.translationEditor.showApiKey.isEnabled)
      assertTrue(panel.translationEditor.clearApiKey.isEnabled)
      assertEquals(SAVED_KEY, panel.translationEditor.validatedTranslation().apiKey)
      actions.cancel()
    }
  }

  private fun <T> onEdt(action: () -> T): T {
    val task = FutureTask(action)
    SwingUtilities.invokeAndWait(task)
    return task.get()
  }

  private companion object {
    const val SAVED_KEY = "sk-test-saved-key"
    const val NEW_KEY = "sk-test-new-key"
  }
}
