package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings.GamepadSelection
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings.RomChangeConfirmationPolicy
import eu.rekawek.coffeegb.controller.properties.ControllerProperties
import eu.rekawek.coffeegb.core.joypad.Button
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.FutureTask
import javax.swing.JButton
import javax.swing.AbstractButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JRootPane
import javax.swing.JSpinner
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class PreferencesDialogTest {

  @Test
  fun `edit applies exposed fields to latest settings and preserves hidden settings`() {
    val defaultInput = ApplicationSettings.Input.defaults()
    val currentInput =
        defaultInput.copy(
            gamepads =
                mapOf(
                    0 to GamepadSelection.Auto,
                    1 to GamepadSelection.Device("sdl-" + "a".repeat(64)),
                ),
            gamepadTunings =
                mapOf(
                    "sdl-" + "a".repeat(64) to
                        ApplicationSettings.GamepadTuning(invertMovementX = true)))
    val recent = (1..4).map { Paths.get("rom-$it.gb") }
    val current =
        ApplicationSettings(
            general =
                ApplicationSettings.General(
                    romDirectory = Paths.get("old"),
                    recentRoms = recent,
                    recentFileCapacity = 4,
                    romChangeConfirmationPolicy = RomChangeConfirmationPolicy.ALWAYS,
                ),
            display =
                ApplicationSettings.Display(
                    explicitScale = 4,
                    grayscale = true,
                ),
            audio = ApplicationSettings.Audio(enabled = false),
            input = currentInput,
            saves = ApplicationSettings.Saves(batterySavesEnabled = false),
        )
    val edit =
        PreferencesEdit(
            romDirectory = Paths.get("new"),
            recentFileCapacity = 2,
            confirmationPolicy = RomChangeConfirmationPolicy.NEVER,
            display =
                ApplicationSettings.Display(
                    scalingMode = ApplicationSettings.DisplayScalingMode.ASPECT_FIT,
                    letterboxColor = 0x202020,
                    fullscreen = true,
                ),
            keyboard = emptyMap(),
            gamepads = mapOf(0 to GamepadSelection.Disabled),
            gamepadTunings =
                mapOf(
                    "sdl-" + "b".repeat(64) to
                        ApplicationSettings.GamepadTuning(tiltDeadZone = 1_024)),
            audio =
                ApplicationSettings.Audio(
                    enabled = true,
                    volume = 25,
                    latency = ApplicationSettings.AudioLatency.SAFE,
                ),
        )

    val updated = edit.applyTo(current)

    assertEquals(Paths.get("new"), updated.general.romDirectory)
    assertEquals(recent.take(2), updated.general.recentRoms)
    assertEquals(2, updated.general.recentFileCapacity)
    assertEquals(RomChangeConfirmationPolicy.NEVER, updated.general.romChangeConfirmationPolicy)
    assertTrue(updated.input.keyboard.isEmpty())
    assertEquals(edit.gamepads, updated.input.gamepads)
    assertEquals(edit.gamepadTunings, updated.input.gamepadTunings)
    assertEquals(edit.display, updated.display)
    assertEquals(edit.audio, updated.audio)
    assertEquals(current.saves, updated.saves)
    assertEquals(current.advanced, updated.advanced)
  }

  @Test
  fun `apply invokes one edit callback before closing`() =
      onEdt {
        val events = mutableListOf<String>()
        var received: PreferencesEdit? = null
        val panel = PreferencesPanel(ApplicationSettings())
        val actions =
            PreferencesDialogActions(
                panel,
                applyEdit = {
                  events += "apply"
                  received = it
                },
                close = { events += "close" },
            )

        actions.apply()

        assertEquals(listOf("apply", "close"), events)
        assertEquals(ApplicationSettings.DEFAULT_RECENT_FILE_CAPACITY, received?.recentFileCapacity)
        assertEquals(ApplicationSettings.Input.defaults().keyboard, received?.keyboard)
        assertEquals(ApplicationSettings.Input.defaults().gamepads, received?.gamepads)
        assertEquals(ApplicationSettings.Display(), received?.display)
        assertEquals(ApplicationSettings.Audio(), received?.audio)
      }

  @Test
  fun `restore defaults and cancel never invoke apply callback`() =
      onEdt {
        val defaults = ApplicationSettings()
        val changedKeyboard =
            defaults.input.keyboard +
                (ControllerProperties.PlayerButton(0, Button.A) to
                    ApplicationSettings.KeyboardKey.fromKeyCode(KeyEvent.VK_A))
        val initial =
            defaults.copy(
                general =
                    ApplicationSettings.General(
                        romDirectory = Paths.get("changed"),
                        recentFileCapacity = 3,
                        romChangeConfirmationPolicy = RomChangeConfirmationPolicy.NEVER,
                    ),
                audio =
                    ApplicationSettings.Audio(
                        enabled = false,
                        volume = 13,
                        latency = ApplicationSettings.AudioLatency.LOW,
                    ),
                display =
                    ApplicationSettings.Display(
                        scalingMode = ApplicationSettings.DisplayScalingMode.INTEGER_FIT,
                        letterboxColor = 0x303030,
                        fullscreen = true,
                        rotation = ApplicationSettings.Rotation.DEG_90,
                    ),
                input =
                    defaults.input.copy(
                        keyboard = changedKeyboard,
                        gamepads = mapOf(0 to GamepadSelection.Disabled),
                        gamepadTunings =
                            mapOf(
                                "sdl-" + "c".repeat(64) to
                                    ApplicationSettings.GamepadTuning(
                                        invertMovementY = true)),
                    ),
            )
        var applyCount = 0
        var closeCount = 0
        val panel = PreferencesPanel(initial, defaults)
        val actions =
            PreferencesDialogActions(
                panel,
                applyEdit = { applyCount++ },
                close = { closeCount++ },
            )

        actions.restoreDefaults()
        val restored = panel.validatedEdit()
        actions.cancel()

        assertEquals(defaults.general.romDirectory, restored.romDirectory)
        assertEquals(defaults.general.recentFileCapacity, restored.recentFileCapacity)
        assertEquals(
            defaults.general.romChangeConfirmationPolicy,
            restored.confirmationPolicy,
        )
        assertEquals(defaults.input.keyboard, restored.keyboard)
        assertEquals(defaults.input.gamepads, restored.gamepads)
        assertEquals(defaults.input.gamepadTunings, restored.gamepadTunings)
        assertEquals(defaults.display, restored.display)
        assertEquals(defaults.audio, restored.audio)
        assertEquals(0, applyCount)
        assertEquals(1, closeCount)
      }

  @Test
  fun `validation and apply failures keep dialog open and surface an error`() =
      onEdt {
        var applyCount = 0
        var closeCount = 0
        val panel = PreferencesPanel(ApplicationSettings())
        val actions =
            PreferencesDialogActions(
                panel,
                applyEdit = {
                  applyCount++
                  throw IllegalStateException("settings storage unavailable")
                },
                close = { closeCount++ },
            )

        panel.directoryField.text = "bad\u0000path"
        actions.apply()
        assertEquals(0, applyCount)
        assertEquals(0, closeCount)
        assertEquals("Enter a valid directory path.", panel.directoryError.text)
        assertSame(panel.directoryField, panel.focusOwnerOrInvalidComponent())

        panel.directoryField.text = ""
        actions.apply()
        assertEquals(1, applyCount)
        assertEquals(0, closeCount)
        assertTrue(panel.validationSummary.text.contains("settings storage unavailable"))
      }

  @Test
  fun `invalid typed recent capacity stays open with a field error`() =
      onEdt {
        var applyCount = 0
        var closeCount = 0
        val panel = PreferencesPanel(ApplicationSettings())
        val actions =
            PreferencesDialogActions(
                panel,
                applyEdit = { applyCount++ },
                close = { closeCount++ },
            )
        val editor = (panel.recentCapacity.editor as JSpinner.DefaultEditor).textField

        editor.text = "99"
        actions.apply()

        assertEquals(0, applyCount)
        assertEquals(0, closeCount)
        assertTrue(panel.recentCapacityError.text.contains("0 to 50"))
        assertSame(editor, panel.focusOwnerOrInvalidComponent())
      }

  @Test
  fun `invalid display color selects Display and keeps the dialog open`() =
      onEdt {
        var applyCount = 0
        var closeCount = 0
        val panel = PreferencesPanel(ApplicationSettings())
        val actions =
            PreferencesDialogActions(
                panel,
                applyEdit = { applyCount++ },
                close = { closeCount++ },
            )

        panel.tabs.selectedIndex = 0
        panel.displayEditor.letterboxColor.text = "not-a-color"
        actions.apply()

        assertEquals(0, applyCount)
        assertEquals(0, closeCount)
        assertEquals("Display", panel.tabs.getTitleAt(panel.tabs.selectedIndex))
        assertEquals(
            "Enter a color in #RRGGBB form.",
            panel.displayEditor.letterboxColorError.text,
        )
        assertSame(panel.displayEditor.letterboxColor, panel.focusOwnerOrInvalidComponent())
      }

  @Test
  fun `general fields have label associations and accessible names`() =
      onEdt {
        val panel = PreferencesPanel(ApplicationSettings())
        val labels = descendants(panel).filterIsInstance<JLabel>().toList()

        assertSame(
            panel.directoryField,
            labels.single { it.text == "Default ROM directory:" }.labelFor,
        )
        assertSame(
            panel.recentCapacity,
            labels.single { it.text == "Recent files to keep:" }.labelFor,
        )
        assertSame(
            panel.confirmationPolicy,
            labels.single { it.text == "Replacing or closing a game:" }.labelFor,
        )
        assertEquals("Default ROM directory", panel.directoryField.accessibleContext.accessibleName)
        assertEquals("Recent files to keep", panel.recentCapacity.accessibleContext.accessibleName)
        assertFalse(panel.tabs.accessibleContext.accessibleName.isNullOrBlank())
        assertEquals(
            listOf("General", "Display", "Input", "Gamepads", "Audio"),
            (0 until panel.tabs.tabCount).map(panel.tabs::getTitleAt),
        )
      }

  @Test
  fun `configured ROM directory disables synchronous shell folder resolution`() =
      onEdt {
        val chooser = RomFileChooser()
        val configured = Paths.get("stale/network/roms")

        chooser.useConfiguredDirectory(configured)

        assertEquals(false, chooser.getClientProperty("FileChooser.useShellFolder"))
        assertEquals(
            configured.toAbsolutePath().toString(),
            chooser.currentDirectory.canonicalPath,
        )
        assertEquals("roms", chooser.getName(chooser.currentDirectory))
        assertEquals(
            UIManager.getIcon("FileView.directoryIcon"),
            chooser.getIcon(chooser.currentDirectory),
        )
        generateSequence(chooser.currentDirectory, File::getParentFile).forEach { directory ->
          assertFalse(directory.canWrite())
          assertFalse(chooser.getName(directory).isBlank())
          assertEquals(
              UIManager.getIcon("FileView.directoryIcon"),
              chooser.getIcon(directory),
          )
        }
      }

  @Test
  fun `root pane uses Apply as default and Escape as cancel`() =
      onEdt {
        var cancelCount = 0
        val rootPane = JRootPane()
        val apply = JButton("Apply")
        configurePreferencesRootPane(rootPane, apply) { cancelCount++ }

        assertSame(apply, rootPane.defaultButton)
        val key = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)
        val actionKey =
            rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(key)
        rootPane.actionMap.get(actionKey).actionPerformed(ActionEvent(rootPane, 0, "escape"))
        assertEquals(1, cancelCount)
      }

  @Test
  fun `leaving the Input tab cancels keyboard capture`() =
      onEdt {
        val panel = PreferencesPanel(ApplicationSettings())
        panel.tabs.selectedIndex = 2
        val capture =
            descendants(panel.keyboardEditor)
                .filterIsInstance<AbstractButton>()
                .single {
                  it.accessibleContext.accessibleName ==
                      "Capture Player 2 A keyboard binding"
                }

        capture.doClick()
        assertTrue(panel.keyboardEditor.isCaptureActive())
        panel.tabs.selectedIndex = 0

        assertFalse(panel.keyboardEditor.isCaptureActive())
        assertFalse(
            panel.keyboardEditor.handleCaptureKey(
                java.awt.event.KeyEvent(
                    capture,
                    java.awt.event.KeyEvent.KEY_PRESSED,
                    0,
                    0,
                    java.awt.event.KeyEvent.VK_Q,
                    java.awt.event.KeyEvent.CHAR_UNDEFINED,
                )))
        assertEquals(null, panel.keyboardEditor.currentBinding(1, Button.A))
      }

  @Test
  fun `panel rejects construction outside the Event Dispatch Thread`() {
    assertFailsWith<IllegalStateException> { PreferencesPanel(ApplicationSettings()) }
  }

  private fun PreferencesPanel.focusOwnerOrInvalidComponent(): Component {
    return try {
      validatedEdit()
      this
    } catch (failure: PreferencesValidationException) {
      failure.invalidComponent
    }
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
