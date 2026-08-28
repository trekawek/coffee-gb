package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.ControllerProperties
import eu.rekawek.coffeegb.core.joypad.Button
import java.awt.Component
import java.awt.Container
import java.awt.event.KeyEvent
import javax.swing.AbstractButton
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class KeyboardMappingEditorTest {

  @Test
  fun presentsOneReusableKeyboardNavigablePadForTheSelectedPlayer() =
      onEventThread {
        val editor = KeyboardMappingEditor(ApplicationSettings.Input.defaults())

        assertEquals("Keyboard mappings", editor.accessibleContext.accessibleName)
        assertEquals(0, editor.selectedPlayer)

        val actions = descendants(editor).filterIsInstance<AbstractButton>()
        assertTrue(actions.all { !it.accessibleContext.accessibleName.isNullOrBlank() })
        assertTrue(actions.all(Component::isFocusable))
        assertEquals(10, actions.count { it.text == "Capture" })
        assertEquals(1, actions.count { it.text == "Reset keyboard defaults" })
        assertTrue(actions.none { it.text in setOf("Dialog key…", "Clear", "Reset") })
        assertEquals(
            "Reset keyboard defaults",
            button(editor, "Restore all keyboard defaults").text,
        )
        assertEquals(
            10,
            descendants(editor)
                .mapNotNull { it.accessibleContext.accessibleName }
                .count { it.startsWith("Current binding for Player ") },
        )

        assertEquals(
            listOf(
                "Capture Player 1 Up keyboard binding",
                "Capture Player 1 Left keyboard binding",
                "Capture Player 1 Right keyboard binding",
                "Capture Player 1 Down keyboard binding",
                "Capture Player 1 Select keyboard binding",
                "Capture Player 1 Start keyboard binding",
                "Capture Player 1 B keyboard binding",
                "Capture Player 1 A keyboard binding",
                "Capture Player 1 B Autofire keyboard binding",
                "Capture Player 1 A Autofire keyboard binding",
            ),
            descendants(editor)
                .filterIsInstance<AbstractButton>()
                .filter { it.text == "Capture" }
                .map { it.accessibleContext.accessibleName },
        )
      }

  @Test
  fun arrangesEveryPlayerLikeAGameBoyPadAtStableGridCoordinates() =
      onEventThread {
        val editor = KeyboardMappingEditor(ApplicationSettings.Input.defaults())
        val expected =
            mapOf(
                Button.UP to KeyboardMappingEditor.PadPosition(1, 0),
                Button.LEFT to KeyboardMappingEditor.PadPosition(0, 1),
                Button.RIGHT to KeyboardMappingEditor.PadPosition(2, 1),
                Button.DOWN to KeyboardMappingEditor.PadPosition(1, 2),
                Button.SELECT to KeyboardMappingEditor.PadPosition(3, 3),
                Button.START to KeyboardMappingEditor.PadPosition(4, 3),
                Button.B to KeyboardMappingEditor.PadPosition(5, 2),
                Button.A to KeyboardMappingEditor.PadPosition(6, 1),
            )

        repeat(4) { player ->
          expected.forEach { (button, position) ->
            assertEquals(position, editor.padPosition(player, button))
          }
          assertEquals(
              KeyboardMappingEditor.PadPosition(5, 3),
              editor.autofirePadPosition(player, Button.B),
          )
          assertEquals(
              KeyboardMappingEditor.PadPosition(6, 3),
              editor.autofirePadPosition(player, Button.A),
          )
        }
      }

  @Test
  fun autofireBindingsCaptureSeparatelyAndSwapConflictsWithRegularBindings() =
      onEventThread {
        val editor = KeyboardMappingEditor(ApplicationSettings.Input.defaults())

        assertIs<KeyboardMappingEditor.EditResult.Applied>(
            editor.editAutofireBinding(0, Button.A, KeyEvent.VK_C))
        assertEquals(KeyEvent.VK_C, editor.currentAutofireBinding(0, Button.A)?.code)

        assertIs<KeyboardMappingEditor.EditResult.Applied>(
            editor.editAutofireBinding(1, Button.B, KeyEvent.VK_Q))
        assertIs<KeyboardMappingEditor.EditResult.Applied>(
            editor.editAutofireBinding(1, Button.B, KeyEvent.VK_Z))
        assertEquals(KeyEvent.VK_Z, editor.currentAutofireBinding(1, Button.B)?.code)
        assertEquals(KeyEvent.VK_Q, editor.currentBinding(0, Button.A)?.code)
        assertEquals(KeyEvent.VK_C, editor.currentAutofireBinding(0, Button.A)?.code)

        editor.selectPlayer(1)
        val capture = button(editor, "Capture Player 2 A Autofire keyboard binding")
        capture.doClick()
        assertTrue(editor.handleCaptureKey(key(capture, KeyEvent.KEY_PRESSED, KeyEvent.VK_Q)))
        assertTrue(editor.handleCaptureKey(key(capture, KeyEvent.KEY_RELEASED, KeyEvent.VK_Q)))
        assertEquals(KeyEvent.VK_Q, editor.currentAutofireBinding(1, Button.A)?.code)
        assertEquals(
            KeyEvent.VK_Q,
            editor.validatedDraft()
                .autofireKeyboard[ControllerProperties.PlayerAutofireButton(1, Button.A)]
                ?.code,
        )
      }

  @Test
  fun capturedConflictsSwapAnExistingTargetKeyAndMoveWhenTheTargetIsEmpty() =
      onEventThread {
        val editor = KeyboardMappingEditor(ApplicationSettings.Input.defaults())
        assertIs<KeyboardMappingEditor.EditResult.Applied>(
            editor.editBinding(1, Button.A, KeyEvent.VK_Q))

        editor.selectPlayer(1)
        val playerTwoCapture = button(editor, "Capture Player 2 A keyboard binding")
        playerTwoCapture.doClick()
        assertTrue(
            editor.handleCaptureKey(
                key(playerTwoCapture, KeyEvent.KEY_PRESSED, KeyEvent.VK_Z)))
        assertTrue(
            editor.handleCaptureKey(
                key(playerTwoCapture, KeyEvent.KEY_RELEASED, KeyEvent.VK_Z)))
        assertEquals(KeyEvent.VK_Z, editor.currentBinding(1, Button.A)?.code)
        assertEquals(KeyEvent.VK_Q, editor.currentBinding(0, Button.A)?.code)

        editor.selectPlayer(2)
        val playerThreeCapture = button(editor, "Capture Player 3 A keyboard binding")
        playerThreeCapture.doClick()
        assertTrue(
            editor.handleCaptureKey(
                key(playerThreeCapture, KeyEvent.KEY_PRESSED, KeyEvent.VK_Z)))
        assertTrue(
            editor.handleCaptureKey(
                key(playerThreeCapture, KeyEvent.KEY_RELEASED, KeyEvent.VK_Z)))
        assertEquals(KeyEvent.VK_Z, editor.currentBinding(2, Button.A)?.code)
        assertNull(editor.currentBinding(1, Button.A))
        assertEquals(KeyEvent.VK_Q, editor.currentBinding(0, Button.A)?.code)
        editor.validatedDraft().toPlayerMapping()
      }

  @Test
  fun editsRemainADraftAndResetDefaultsPreservesGamepadSettings() =
      onEventThread {
        val stableId = "sdl-${"a".repeat(64)}"
        val gamepads =
            mapOf(
                0 to ApplicationSettings.GamepadSelection.Auto,
                1 to ApplicationSettings.GamepadSelection.Device(stableId),
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
                mapOf(
                    ControllerProperties.PlayerAutofireButton(0, Button.A) to
                        DesktopKeyboardKeyAdapter.fromKeyCode(KeyEvent.VK_C)),
            )
        val editor = KeyboardMappingEditor(initial)

        assertIs<KeyboardMappingEditor.EditResult.Applied>(
            editor.editBinding(1, Button.A, KeyEvent.VK_Q))
        assertEquals(KeyEvent.VK_Q, editor.currentBinding(1, Button.A)?.code)
        assertEquals(KeyEvent.VK_Q, editor.validatedDraft().keyboard[playerButton(1, Button.A)]?.code)
        assertEquals(gamepads, editor.validatedDraft().gamepads)
        assertEquals(tunings, editor.validatedDraft().gamepadTunings)
        assertNull(initial.keyboard[playerButton(1, Button.A)], "the initial value is immutable")

        editor.editBinding(1, Button.B, KeyEvent.VK_W)
        editor.editAutofireBinding(2, Button.B, KeyEvent.VK_R)
        editor.resetToDefaults()
        assertEquals(
            ApplicationSettings.Input(
                ApplicationSettings.Input.defaults().keyboard,
                gamepads,
                tunings,
                ApplicationSettings.Input.defaults().autofireKeyboard,
            ),
            editor.validatedDraft(),
        )
      }

  @Test
  fun onlyTabAndBackspaceRemainReservedWhileEscapeAndEnterAreAssignable() =
      onEventThread {
        val editor = KeyboardMappingEditor(ApplicationSettings.Input.defaults())

        assertIs<KeyboardMappingEditor.EditResult.Reserved>(
            editor.editBinding(1, Button.A, KeyEvent.VK_TAB))
        assertIs<KeyboardMappingEditor.EditResult.Reserved>(
            editor.editBinding(1, Button.A, KeyEvent.VK_BACK_SPACE))
        assertNull(editor.currentBinding(1, Button.A))

        assertIs<KeyboardMappingEditor.EditResult.Applied>(
            editor.editBinding(1, Button.A, KeyEvent.VK_ESCAPE))
        assertEquals(KeyEvent.VK_ESCAPE, editor.currentBinding(1, Button.A)?.code)
        assertIs<KeyboardMappingEditor.EditResult.Applied>(
            editor.editBinding(1, Button.B, KeyEvent.VK_ENTER))
        assertEquals(KeyEvent.VK_ENTER, editor.currentBinding(1, Button.B)?.code)
        assertNull(editor.currentBinding(0, Button.START))

        assertIs<KeyboardMappingEditor.EditResult.Unsupported>(
            editor.editBinding(2, Button.B, Int.MAX_VALUE))
        assertNull(editor.currentBinding(2, Button.B))
      }

  @Test
  fun singleCaptureUsesDialogKeySemanticsAndConsumesTheWholeKeySequence() =
      onEventThread {
        val editor = KeyboardMappingEditor(ApplicationSettings.Input.defaults())
        editor.selectPlayer(1)
        val captureA = button(editor, "Capture Player 2 A keyboard binding")

        captureA.doClick()
        assertTrue(editor.handleCaptureKey(key(captureA, KeyEvent.KEY_PRESSED, KeyEvent.VK_TAB)))
        assertTrue(editor.handleCaptureKey(key(captureA, KeyEvent.KEY_RELEASED, KeyEvent.VK_TAB)))
        assertNull(editor.currentBinding(1, Button.A))

        captureA.doClick()
        assertTrue(
            editor.handleCaptureKey(key(captureA, KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE)))
        assertTrue(
            editor.handleCaptureKey(key(captureA, KeyEvent.KEY_RELEASED, KeyEvent.VK_ESCAPE)))
        assertEquals(KeyEvent.VK_ESCAPE, editor.currentBinding(1, Button.A)?.code)

        val captureB = button(editor, "Capture Player 2 B keyboard binding")
        captureB.doClick()
        assertTrue(
            editor.handleCaptureKey(key(captureB, KeyEvent.KEY_PRESSED, KeyEvent.VK_ENTER)))
        assertTrue(
            editor.handleCaptureKey(key(captureB, KeyEvent.KEY_RELEASED, KeyEvent.VK_ENTER)))
        assertEquals(KeyEvent.VK_ENTER, editor.currentBinding(1, Button.B)?.code)
      }

  @Test
  fun captureAppliesARegularKeyAndReportsTheUpdatedBinding() =
      onEventThread {
        val editor = KeyboardMappingEditor(ApplicationSettings.Input.defaults())
        editor.selectPlayer(1)
        val capture = button(editor, "Capture Player 2 A keyboard binding")

        capture.doClick()
        assertTrue(editor.handleCaptureKey(key(capture, KeyEvent.KEY_PRESSED, KeyEvent.VK_Q)))
        assertTrue(editor.handleCaptureKey(key(capture, KeyEvent.KEY_RELEASED, KeyEvent.VK_Q)))
        assertEquals(KeyEvent.VK_Q, editor.currentBinding(1, Button.A)?.code)
        assertTrue(
            findByAccessibleName(editor, "Keyboard mapping status")
                .accessibleContext.accessibleDescription
                .contains("Player 2 A is now Q"))
      }

  @Test
  fun resettingKeyboardDefaultsCancelsAnActiveCapture() =
      onEventThread {
        val editor = KeyboardMappingEditor(ApplicationSettings.Input.defaults())
        val capture = button(editor, "Capture Player 1 A keyboard binding")
        val reset = button(editor, "Restore all keyboard defaults")

        editor.editBinding(0, Button.A, KeyEvent.VK_Q)
        capture.doClick()
        assertTrue(editor.isCaptureActive())
        assertEquals("Reset keyboard defaults", reset.text)
        reset.doClick()

        assertFalse(editor.isCaptureActive())
        assertFalse(editor.handleCaptureKey(key(capture, KeyEvent.KEY_PRESSED, KeyEvent.VK_W)))
        assertEquals(KeyEvent.VK_Z, editor.currentBinding(0, Button.A)?.code)
      }

  @Test
  fun switchingTheSelectedPlayerCancelsCaptureAndReusesThePadControls() =
      onEventThread {
        val editor = KeyboardMappingEditor(ApplicationSettings.Input.defaults())
        val capture = button(editor, "Capture Player 1 A keyboard binding")

        capture.doClick()
        assertTrue(editor.isCaptureActive())
        editor.selectPlayer(1)

        assertFalse(editor.isCaptureActive())
        assertFalse(editor.handleCaptureKey(key(capture, KeyEvent.KEY_PRESSED, KeyEvent.VK_Q)))
        assertEquals(KeyEvent.VK_Z, editor.currentBinding(0, Button.A)?.code)
        assertEquals("Capture Player 2 A keyboard binding", capture.accessibleContext.accessibleName)
        assertEquals(1, editor.selectedPlayer)
      }

  @Test
  fun displayableCaptureInstallsAndEveryExitRemovesTheDispatcher() =
      onEventThread {
        val editor = KeyboardMappingEditor(ApplicationSettings.Input.defaults())
        editor.selectPlayer(1)
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
  fun aModifierIsCapturedOnlyAfterRelease() =
      onEventThread {
        val editor = KeyboardMappingEditor(ApplicationSettings.Input.defaults())
        editor.selectPlayer(2)
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
