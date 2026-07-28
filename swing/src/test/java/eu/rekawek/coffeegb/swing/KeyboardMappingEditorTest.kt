package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.ControllerProperties
import eu.rekawek.coffeegb.core.joypad.Button
import java.awt.Component
import java.awt.Container
import java.awt.event.KeyEvent
import javax.swing.AbstractButton
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class KeyboardMappingEditorTest {

  @Test
  fun presentsFourKeyboardNavigablePlayersAndNamedActions() =
      onEventThread {
        val editor = KeyboardMappingEditor(ApplicationSettings.Input.defaults())
        val tabs = descendants(editor).filterIsInstance<JTabbedPane>().single()

        assertEquals(4, tabs.tabCount)
        assertEquals(
            listOf("Player 1", "Player 2", "Player 3", "Player 4"),
            (0 until tabs.tabCount).map(tabs::getTitleAt),
        )
        assertEquals("Keyboard mappings", editor.accessibleContext.accessibleName)
        assertEquals("Keyboard mappings by player", tabs.accessibleContext.accessibleName)

        val actions = descendants(editor).filterIsInstance<AbstractButton>()
        assertTrue(actions.all { !it.accessibleContext.accessibleName.isNullOrBlank() })
        assertTrue(actions.all(Component::isFocusable))
        assertTrue(
            actions.any {
              it.accessibleContext.accessibleName ==
                  "Capture dialog key for Player 4 Start keyboard binding"
            })
        assertEquals(
            32,
            descendants(editor)
                .mapNotNull { it.accessibleContext.accessibleName }
                .count { it.startsWith("Current binding for Player ") },
        )
      }

  @Test
  fun rejectsConflictsImmediatelyWithoutChangingTheDraft() =
      onEventThread {
        val editor = KeyboardMappingEditor(ApplicationSettings.Input.defaults())

        val result = editor.editBinding(1, Button.A, KeyEvent.VK_Z)

        val conflict = assertIs<KeyboardMappingEditor.EditResult.Conflict>(result)
        assertEquals(KeyboardMappingEditor.Binding(0, Button.A), conflict.existingBinding)
        assertNull(editor.currentBinding(1, Button.A))
        assertNull(
            editor.validatedDraft().keyboard[
                ControllerProperties.PlayerButton(1, Button.A)])
        assertTrue(
            findByAccessibleName(editor, "Keyboard mapping status")
                .accessibleContext.accessibleDescription
                .contains("already assigned"))
      }

  @Test
  fun editsClearAndResetRemainADraftAndPreserveGamepadSettings() =
      onEventThread {
        val stableId = "sdl-${"a".repeat(64)}"
        val gamepads =
            mapOf(
                0 to ApplicationSettings.GamepadSelection.Auto,
                1 to
                    ApplicationSettings.GamepadSelection.Device(
                        stableId),
            )
        val tunings =
            mapOf(
                stableId to
                    ApplicationSettings.GamepadTuning(
                        movementDeadZone = 1_024,
                        invertTiltY = true,
                    ))
        val initial =
            ApplicationSettings.Input(
                ApplicationSettings.Input.defaults().keyboard,
                gamepads,
                tunings,
            )
        val editor = KeyboardMappingEditor(initial)

        assertIs<KeyboardMappingEditor.EditResult.Applied>(
            editor.editBinding(1, Button.A, KeyEvent.VK_Q))
        assertEquals(KeyEvent.VK_Q, editor.currentBinding(1, Button.A)?.code)
        assertEquals(KeyEvent.VK_Q, editor.validatedDraft().keyboard[playerButton(1, Button.A)]?.code)
        assertEquals(gamepads, editor.validatedDraft().gamepads)
        assertEquals(tunings, editor.validatedDraft().gamepadTunings)
        assertNull(initial.keyboard[playerButton(1, Button.A)], "the initial value is immutable")

        editor.clearBinding(1, Button.A)
        assertNull(editor.currentBinding(1, Button.A))
        editor.clearBinding(0, Button.A)
        assertNull(editor.currentBinding(0, Button.A))
        assertIs<KeyboardMappingEditor.EditResult.Applied>(editor.resetBinding(0, Button.A))
        assertEquals(KeyEvent.VK_Z, editor.currentBinding(0, Button.A)?.code)

        editor.editBinding(1, Button.B, KeyEvent.VK_Q)
        editor.resetToDefaults()
        assertEquals(
            ApplicationSettings.Input(
                ApplicationSettings.Input.defaults().keyboard,
                gamepads,
                tunings,
            ),
            editor.validatedDraft(),
        )
      }

  @Test
  fun reservedDialogKeysRequireTheExplicitSafePath() =
      onEventThread {
        val editor = KeyboardMappingEditor(ApplicationSettings.Input.defaults())

        assertIs<KeyboardMappingEditor.EditResult.Reserved>(
            editor.editBinding(1, Button.A, KeyEvent.VK_TAB))
        assertNull(editor.currentBinding(1, Button.A))

        assertIs<KeyboardMappingEditor.EditResult.Reserved>(
            editor.editBinding(1, Button.A, KeyEvent.VK_TAB, allowReserved = true))
        assertIs<KeyboardMappingEditor.EditResult.Reserved>(
            editor.editBinding(1, Button.A, KeyEvent.VK_BACK_SPACE, allowReserved = true))
        assertNull(editor.currentBinding(1, Button.A))

        assertIs<KeyboardMappingEditor.EditResult.Applied>(
            editor.editBinding(1, Button.A, KeyEvent.VK_ESCAPE, allowReserved = true))
        assertEquals(KeyEvent.VK_ESCAPE, editor.currentBinding(1, Button.A)?.code)

        assertIs<KeyboardMappingEditor.EditResult.Unsupported>(
            editor.editBinding(1, Button.B, Int.MAX_VALUE))
        assertNull(editor.currentBinding(1, Button.B))
      }

  @Test
  fun regularCaptureLeavesTabAndShiftTabForNavigationAndEscapeCancels() =
      onEventThread {
        val editor = KeyboardMappingEditor(ApplicationSettings.Input.defaults())
        val capture = button(editor, "Capture Player 2 A keyboard binding")

        capture.doClick()
        assertTrue(editor.handleCaptureKey(key(capture, KeyEvent.KEY_PRESSED, KeyEvent.VK_SHIFT)))
        assertFalse(
            editor.handleCaptureKey(
                key(
                    capture,
                    KeyEvent.KEY_PRESSED,
                    KeyEvent.VK_TAB,
                    KeyEvent.SHIFT_DOWN_MASK,
                )))
        assertFalse(editor.handleCaptureKey(key(capture, KeyEvent.KEY_RELEASED, KeyEvent.VK_SHIFT)))
        assertNull(editor.currentBinding(1, Button.A))

        capture.doClick()
        assertTrue(editor.handleCaptureKey(key(capture, KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE)))
        assertTrue(editor.handleCaptureKey(key(capture, KeyEvent.KEY_RELEASED, KeyEvent.VK_ESCAPE)))
        assertNull(editor.currentBinding(1, Button.A))

        capture.doClick()
        assertTrue(editor.handleCaptureKey(key(capture, KeyEvent.KEY_PRESSED, KeyEvent.VK_ENTER)))
        assertTrue(editor.handleCaptureKey(key(capture, KeyEvent.KEY_RELEASED, KeyEvent.VK_ENTER)))
        assertNull(editor.currentBinding(1, Button.A))
      }

  @Test
  fun captureAppliesARegularKeyAndReportsConflictsWithoutReassignment() =
      onEventThread {
        val editor = KeyboardMappingEditor(ApplicationSettings.Input.defaults())
        val capture = button(editor, "Capture Player 2 A keyboard binding")

        capture.doClick()
        assertTrue(editor.handleCaptureKey(key(capture, KeyEvent.KEY_PRESSED, KeyEvent.VK_Z)))
        assertTrue(editor.handleCaptureKey(key(capture, KeyEvent.KEY_RELEASED, KeyEvent.VK_Z)))
        assertNull(editor.currentBinding(1, Button.A))
        assertEquals(KeyEvent.VK_Z, editor.currentBinding(0, Button.A)?.code)

        capture.doClick()
        assertTrue(editor.handleCaptureKey(key(capture, KeyEvent.KEY_PRESSED, KeyEvent.VK_Q)))
        assertTrue(editor.handleCaptureKey(key(capture, KeyEvent.KEY_RELEASED, KeyEvent.VK_Q)))
        assertEquals(KeyEvent.VK_Q, editor.currentBinding(1, Button.A)?.code)
      }

  @Test
  fun explicitDialogCaptureConsumesTheWholeReservedKeySequence() =
      onEventThread {
        val editor = KeyboardMappingEditor(ApplicationSettings.Input.defaults())
        val capture =
            button(editor, "Capture dialog key for Player 3 B keyboard binding")

        capture.doClick()
        assertTrue(editor.handleCaptureKey(key(capture, KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE)))
        assertTrue(editor.handleCaptureKey(key(capture, KeyEvent.KEY_RELEASED, KeyEvent.VK_ESCAPE)))
        assertEquals(KeyEvent.VK_ESCAPE, editor.currentBinding(2, Button.B)?.code)

        editor.clearBinding(0, Button.START)
        val enter =
            button(editor, "Capture dialog key for Player 4 A keyboard binding")
        enter.doClick()
        assertTrue(editor.handleCaptureKey(key(enter, KeyEvent.KEY_PRESSED, KeyEvent.VK_ENTER)))
        assertTrue(editor.handleCaptureKey(key(enter, KeyEvent.KEY_RELEASED, KeyEvent.VK_ENTER)))
        assertEquals(KeyEvent.VK_ENTER, editor.currentBinding(3, Button.A)?.code)
      }

  @Test
  fun resetAndClearCancelAnActiveCapture() =
      onEventThread {
        val editor = KeyboardMappingEditor(ApplicationSettings.Input.defaults())
        val capture = button(editor, "Capture Player 1 A keyboard binding")

        capture.doClick()
        assertTrue(editor.isCaptureActive())
        button(editor, "Reset Player 1 A keyboard binding").doClick()
        assertFalse(editor.isCaptureActive())
        assertFalse(editor.handleCaptureKey(key(capture, KeyEvent.KEY_PRESSED, KeyEvent.VK_Q)))
        assertEquals(KeyEvent.VK_Z, editor.currentBinding(0, Button.A)?.code)

        capture.doClick()
        button(editor, "Clear Player 1 B keyboard binding").doClick()
        assertFalse(editor.isCaptureActive())
        assertFalse(editor.handleCaptureKey(key(capture, KeyEvent.KEY_PRESSED, KeyEvent.VK_Q)))
        assertNull(editor.currentBinding(0, Button.B))
      }

  @Test
  fun switchingPlayerTabsCancelsTheHiddenRowsCapture() =
      onEventThread {
        val editor = KeyboardMappingEditor(ApplicationSettings.Input.defaults())
        val tabs = descendants(editor).filterIsInstance<JTabbedPane>().single()
        val capture = button(editor, "Capture Player 1 A keyboard binding")

        capture.doClick()
        assertTrue(editor.isCaptureActive())
        tabs.selectedIndex = 1

        assertFalse(editor.isCaptureActive())
        assertFalse(editor.handleCaptureKey(key(capture, KeyEvent.KEY_PRESSED, KeyEvent.VK_Q)))
        assertEquals(KeyEvent.VK_Z, editor.currentBinding(0, Button.A)?.code)
      }

  @Test
  fun displayableCaptureInstallsAndEveryExitRemovesTheDispatcher() =
      onEventThread {
        val editor = KeyboardMappingEditor(ApplicationSettings.Input.defaults())
        val capture = button(editor, "Capture Player 2 A keyboard binding")
        editor.addNotify()
        try {
          capture.doClick()
          assertTrue(editor.isCaptureDispatcherInstalled())
          editor.cancelCapture()
          assertFalse(editor.isCaptureDispatcherInstalled())

          capture.doClick()
          assertTrue(editor.isCaptureDispatcherInstalled())
        } finally {
          editor.removeNotify()
        }
        assertFalse(editor.isCaptureDispatcherInstalled())
        assertFalse(editor.isCaptureActive())
      }

  @Test
  fun aModifierIsCapturedOnlyAfterReleaseSoItCannotStealShiftTab() =
      onEventThread {
        val editor = KeyboardMappingEditor(ApplicationSettings.Input.defaults())
        val capture = button(editor, "Capture Player 3 A keyboard binding")

        capture.doClick()
        assertTrue(editor.handleCaptureKey(key(capture, KeyEvent.KEY_PRESSED, KeyEvent.VK_CONTROL)))
        assertNull(editor.currentBinding(2, Button.A))
        assertTrue(editor.handleCaptureKey(key(capture, KeyEvent.KEY_RELEASED, KeyEvent.VK_CONTROL)))
        assertEquals(KeyEvent.VK_CONTROL, editor.currentBinding(2, Button.A)?.code)
      }

  @Test
  fun constructionAndMutationAreRejectedOffTheEventDispatchThread() {
    val failure =
        kotlin.runCatching {
              KeyboardMappingEditor(ApplicationSettings.Input.defaults())
            }
            .exceptionOrNull()

    assertIs<IllegalStateException>(failure)

    lateinit var editor: KeyboardMappingEditor
    onEventThread { editor = KeyboardMappingEditor(ApplicationSettings.Input.defaults()) }
    assertIs<IllegalStateException>(
        kotlin.runCatching { editor.editBinding(1, Button.A, KeyEvent.VK_Q) }.exceptionOrNull())
  }

  private fun button(editor: KeyboardMappingEditor, name: String): AbstractButton =
      assertIs(findByAccessibleName(editor, name))

  private fun findByAccessibleName(root: Container, name: String): Component =
      descendants(root).single { it.accessibleContext?.accessibleName == name }

  private fun descendants(root: Container): List<Component> =
      buildList {
        fun visit(component: Component) {
          add(component)
          if (component is Container) {
            component.components.forEach(::visit)
          }
        }
        visit(root)
      }

  private fun key(
      source: Component,
      id: Int,
      code: Int,
      modifiers: Int = 0,
  ) =
      KeyEvent(
          source,
          id,
          System.currentTimeMillis(),
          modifiers,
          code,
          KeyEvent.CHAR_UNDEFINED,
      )

  private fun playerButton(player: Int, button: Button) =
      ControllerProperties.PlayerButton(player, button)

  private fun onEventThread(block: () -> Unit) {
    SwingUtilities.invokeAndWait(block)
  }
}
