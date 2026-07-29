package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings.GamepadSelection
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings.RomChangeConfirmationPolicy
import eu.rekawek.coffeegb.controller.properties.ControllerProperties
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.joypad.Button
import java.awt.Component
import java.awt.Container
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
            peripherals = ApplicationSettings.Peripherals(cameraDeviceIndex = 3),
            saves = ApplicationSettings.Saves(batterySavesEnabled = false),
            advanced =
                ApplicationSettings.Advanced(
                    bootstrapMode = BootstrapMode.FAST_FORWARD,
                    datelSlotRom = Paths.get("datel-hidden.gb"),
                    fullChangerCharacter = "7",
                ),
            desktop =
                ApplicationSettings.Desktop(
                    windowSize = ApplicationSettings.WindowSize(913, 617)),
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
            cameraDeviceIndex = 6,
            audio =
                ApplicationSettings.Audio(
                    enabled = true,
                    volume = 25,
                    latency = ApplicationSettings.AudioLatency.SAFE,
                ),
            advanced =
                current.advanced.copy(
                    bootstrapMode = BootstrapMode.NORMAL,
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
    assertEquals(6, updated.peripherals.cameraDeviceIndex)
    assertEquals(edit.display, updated.display)
    assertEquals(edit.audio, updated.audio)
    assertEquals(current.saves, updated.saves)
    assertEquals(edit.advanced, updated.advanced)
    assertEquals(current.desktop, updated.desktop)
  }

  @Test
  fun `system edit preserves hidden advanced settings changed after dialog opened`() {
    val dialogSnapshot =
        ApplicationSettings.Advanced(
            bootstrapMode = BootstrapMode.SKIP,
            datelSlotRom = Paths.get("stale-datel-slot.gb"),
            fullChangerCharacter = "STALE",
        )
    val latest =
        ApplicationSettings(
            advanced =
                dialogSnapshot.copy(
                    datelSlotRom = Paths.get("latest-datel-slot.gb"),
                    fullChangerCharacter = "LATEST",
                ))
    val edit =
        PreferencesEdit(
            romDirectory = null,
            recentFileCapacity = ApplicationSettings.DEFAULT_RECENT_FILE_CAPACITY,
            confirmationPolicy = RomChangeConfirmationPolicy.WHEN_RUNNING,
            display = latest.display,
            keyboard = latest.input.keyboard,
            gamepads = latest.input.gamepads,
            gamepadTunings = latest.input.gamepadTunings,
            cameraDeviceIndex = latest.peripherals.cameraDeviceIndex,
            audio = latest.audio,
            advanced = dialogSnapshot.copy(bootstrapMode = BootstrapMode.NORMAL),
        )

    val updated = edit.applyTo(latest)

    assertEquals(BootstrapMode.NORMAL, updated.advanced.bootstrapMode)
    assertEquals(latest.advanced.datelSlotRom, updated.advanced.datelSlotRom)
    assertEquals(
        latest.advanced.fullChangerCharacter,
        updated.advanced.fullChangerCharacter,
    )
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
        assertEquals(
            ApplicationSettings.DEFAULT_CAMERA_DEVICE_INDEX,
            received?.cameraDeviceIndex,
        )
        assertEquals(ApplicationSettings.Display(), received?.display)
        assertEquals(ApplicationSettings.Audio(), received?.audio)
        assertEquals(ApplicationSettings.Saves(), received?.saves)
        assertEquals(ApplicationSettings.Advanced(), received?.advanced)
        assertFalse(checkNotNull(received).forceWindowSize)
      }

  @Test
  fun `choosing a display scale propagates one window-size command through the edit`() =
      onEdt {
        val panel = PreferencesPanel(ApplicationSettings())
        panel.displayEditor.explicitScale.selectedIndex = 2

        val edit = panel.validatedEdit()

        assertEquals(4, edit.display.explicitScale)
        assertTrue(edit.forceWindowSize)
      }

  @Test
  fun `no-op apply preserves legacy display settings without requesting window sizing`() =
      onEdt {
        for (mode in
            listOf(
                ApplicationSettings.DisplayScalingMode.INTEGER_FIT,
                ApplicationSettings.DisplayScalingMode.ASPECT_FIT,
            )) {
          val initialDisplay =
              ApplicationSettings.Display(
                  scalingMode = mode,
                  explicitScale = 3,
              )
          var received: PreferencesEdit? = null
          val panel = PreferencesPanel(ApplicationSettings(display = initialDisplay))
          val actions =
              PreferencesDialogActions(
                  panel,
                  applyEdit = { received = it },
                  close = {},
              )

          actions.apply()

          assertEquals(initialDisplay, checkNotNull(received).display)
          assertFalse(checkNotNull(received).forceWindowSize)
        }
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
                advanced =
                    defaults.advanced.copy(
                        bootstrapMode = BootstrapMode.NORMAL,
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
        assertEquals(defaults.saves, restored.saves)
        assertEquals(defaults.advanced, restored.advanced)
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
            listOf(
                "General",
                "System",
                "Display",
                "Input",
                "Gamepads",
                "Peripherals",
                "Audio",
                "Saves",
            ),
            (0 until panel.tabs.tabCount).map(panel.tabs::getTitleAt),
        )
      }

  @Test
  fun `battery save checkbox explicitly describes its next-game scope`() =
      onEdt {
        val checkbox = PreferencesPanel(ApplicationSettings()).savesEditor.batterySaves

        assertEquals(
            "Enable battery saves for the next opened game",
            checkbox.text,
        )
        assertEquals(
            "Enable battery saves for the next opened game",
            checkbox.accessibleContext.accessibleName,
        )
        assertTrue(checkbox.toolTipText.contains("current game"))
        assertEquals(
            checkbox.toolTipText,
            checkbox.accessibleContext.accessibleDescription,
        )
      }

  @Test
  fun `historical filesystem roots are not retained by the saves editor`() {
    val filesystemRoot =
        Paths.get("").toAbsolutePath().root
            ?: throw AssertionError("Default filesystem has no root")
    val safeHistory = Paths.get("/safe/history")

    assertEquals(
        listOf(safeHistory),
        retainedPreviousSaveDirectories(
            directory = Paths.get("/new/root"),
            initialDirectory = filesystemRoot,
            initialPreviousDirectories = listOf(filesystemRoot, safeHistory),
        ),
    )
  }

  @Test
  fun `saves editor retains prior roots and validates the complete state policy`() =
      onEdt {
        val initialSaves =
            ApplicationSettings.Saves(
                directory = Paths.get("/old/root"),
                previousDirectories = listOf(Paths.get("/older/root")),
                batterySavesEnabled = false,
                rewindEnabled = false,
                rewindSeconds = 45,
                autosavePolicy =
                    ApplicationSettings.AutosavePolicy.ON_CLOSE_AND_ROM_SWITCH,
                resumePolicy = ApplicationSettings.ResumePolicy.ALWAYS,
                rewindMemoryMiB = 96,
            )
        val panel =
            PreferencesPanel(
                ApplicationSettings(saves = initialSaves),
                saveDirectoryChooser = SaveDirectoryChooser { _, _ -> Paths.get("/new/root") },
            )
        panel.savesEditor.directoryField.text = "/new/root"
        panel.savesEditor.batterySaves.isSelected = true
        panel.savesEditor.rewindEnabled.isSelected = true
        panel.savesEditor.rewindSeconds.value = 60
        panel.savesEditor.rewindMemory.value = 128

        val saves = panel.validatedEdit().saves!!

        assertEquals(Paths.get("/new/root"), saves.directory)
        assertEquals(
            listOf(Paths.get("/old/root"), Paths.get("/older/root")),
            saves.previousDirectories,
        )
        assertTrue(saves.batterySavesEnabled)
        assertTrue(saves.rewindEnabled)
        assertEquals(60, saves.rewindSeconds)
        assertEquals(128, saves.rewindMemoryMiB)
        assertEquals(initialSaves.autosavePolicy, saves.autosavePolicy)
        assertEquals(initialSaves.resumePolicy, saves.resumePolicy)
      }

  @Test
  fun `invalid typed rewind budget selects Saves and keeps settings unapplied`() =
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
        val editor = (panel.savesEditor.rewindMemory.editor as JSpinner.DefaultEditor).textField
        panel.tabs.selectedIndex = 0
        editor.text = "999"

        actions.apply()

        assertEquals(0, applyCount)
        assertEquals(0, closeCount)
        assertEquals("Saves", panel.tabs.getTitleAt(panel.tabs.selectedIndex))
        assertTrue(panel.validationSummary.text.contains("8 to 512"))
        assertSame(editor, panel.focusOwnerOrInvalidComponent())
      }

  @Test
  fun `filesystem root save directory is rejected before background validation`() =
      onEdt {
        val filesystemRoot =
            Paths.get("").toAbsolutePath().root
                ?: throw AssertionError("Default filesystem has no root")
        var validationCount = 0
        var applyCount = 0
        val panel = PreferencesPanel(ApplicationSettings())
        val actions =
            PreferencesDialogActions(
                panel,
                applyEdit = { applyCount++ },
                close = {},
                saveDirectoryValidator =
                    SaveDirectoryValidator {
                      validationCount++
                      null
                    },
            )
        panel.tabs.selectedIndex = 0
        panel.savesEditor.directoryField.text = filesystemRoot.toString()

        actions.apply()

        assertEquals(0, applyCount)
        assertEquals(0, validationCount)
        assertEquals("Saves", panel.tabs.getTitleAt(panel.tabs.selectedIndex))
        assertTrue(panel.savesEditor.directoryError.text.contains("below the filesystem root"))
        assertSame(
            panel.savesEditor.directoryField,
            panel.focusOwnerOrInvalidComponent(),
        )
      }

  @Test
  fun `configured save directory is checked off EDT before Apply mutates settings`() {
    val directory = Files.createTempDirectory("preferences-saves")
    val validationOffEdt = AtomicBoolean()
    val completed = CountDownLatch(1)
    val events = mutableListOf<String>()
    val validationExecutor = Executors.newSingleThreadExecutor()

    onEdt {
      val panel =
          PreferencesPanel(
              ApplicationSettings(
                  saves = ApplicationSettings.Saves(directory = directory),
              ))
      val actions =
          PreferencesDialogActions(
              panel,
              applyEdit = {
                assertTrue(SwingUtilities.isEventDispatchThread())
                events += "apply"
              },
              close = {
                events += "close"
                completed.countDown()
              },
              saveDirectoryValidator =
                  SaveDirectoryValidator {
                    validationOffEdt.set(!SwingUtilities.isEventDispatchThread())
                    null
                  },
              validationExecutor = validationExecutor,
          )

      actions.apply()

      assertTrue(events.isEmpty())
    }

    assertTrue(completed.await(5, TimeUnit.SECONDS))
    assertTrue(validationOffEdt.get())
    assertEquals(listOf("apply", "close"), events)
    assertTrue(validationExecutor.isShutdown)
  }

  @Test
  fun `failed background save validation stays open and selects actionable field error`() {
    val directory = Files.createTempDirectory("preferences-saves-invalid")
    val completed = CountDownLatch(1)
    var applyCount = 0
    var closeCount = 0
    lateinit var panel: PreferencesPanel

    onEdt {
      panel =
          PreferencesPanel(
              ApplicationSettings(
                  saves = ApplicationSettings.Saves(directory = directory),
              ))
      val actions =
          PreferencesDialogActions(
              panel,
              applyEdit = { applyCount++ },
              close = { closeCount++ },
              saveDirectoryValidator =
                  SaveDirectoryValidator {
                    assertFalse(SwingUtilities.isEventDispatchThread())
                    "The selected save data directory is not writable."
                  },
              validationExecutor =
                  Executor { command ->
                    Thread(command, "preferences-save-validation-failure-test").start()
                  },
              applyingChanged = { applying ->
                if (!applying) completed.countDown()
              },
          )
      panel.tabs.selectedIndex = 0

      actions.apply()
    }

    assertTrue(completed.await(5, TimeUnit.SECONDS))
    onEdt {
      assertEquals(0, applyCount)
      assertEquals(0, closeCount)
      assertEquals("Saves", panel.tabs.getTitleAt(panel.tabs.selectedIndex))
      assertTrue(panel.savesEditor.directoryError.text.contains("not writable"))
      assertTrue(panel.validationSummary.text.contains("not writable"))
    }
  }

  @Test
  fun `system save validator rejects symbolic link components`() {
    val directory = Files.createTempDirectory("preferences-saves-symlink")
    val real = Files.createDirectory(directory.resolve("real"))
    val linked = directory.resolve("linked")
    try {
      Files.createSymbolicLink(linked, real)
    } catch (_: Exception) {
      return
    }

    val error = SYSTEM_SAVE_DIRECTORY_VALIDATOR.validate(linked)

    assertTrue(error.orEmpty().contains("symbolic links"))
  }

  @Test
  fun `system save validator rejects filesystem root but accepts a named writable child`() {
    val root = Paths.get("/").toAbsolutePath().normalize()
    val child = Files.createTempDirectory("preferences-named-save-root")

    val rootError = SYSTEM_SAVE_DIRECTORY_VALIDATOR.validate(root)
    val childError = SYSTEM_SAVE_DIRECTORY_VALIDATOR.validate(child)

    assertTrue(rootError.orEmpty().contains("named directory"))
    assertEquals(null, childError)
  }

  @Test
  fun `validation rejection restores Apply state and owned executor closes with dialog`() {
    val directory = Files.createTempDirectory("preferences-saves-rejected")
    var applying = false
    var applyCount = 0
    var closeCount = 0

    onEdt {
      val panel =
          PreferencesPanel(
              ApplicationSettings(
                  saves = ApplicationSettings.Saves(directory = directory),
              ))
      val rejected =
          PreferencesDialogActions(
              panel,
              applyEdit = { applyCount++ },
              close = { closeCount++ },
              validationExecutor =
                  Executor {
                    throw RejectedExecutionException("injected saturation")
                  },
              applyingChanged = { applying = it },
          )

      rejected.apply()

      assertFalse(applying)
      assertEquals(0, applyCount)
      assertEquals(0, closeCount)
      assertTrue(panel.savesEditor.directoryError.text.contains("validation is busy"))
      rejected.cancel()
    }

    val ownedExecutor = Executors.newSingleThreadExecutor()
    onEdt {
      val panel = PreferencesPanel(ApplicationSettings())
      PreferencesDialogActions(
              panel,
              applyEdit = {},
              close = {},
              validationExecutor = ownedExecutor,
          )
          .cancel()
    }
    assertTrue(ownedExecutor.isShutdown)
  }

  @Test
  fun `configured ROM directory disables synchronous shell folder resolution`() =
      onEdt {
        val chooser = RomFileChooser()
        val configured = Paths.get("stale/network/roms")

        // macOS asks JFileChooser about its initially null directory during construction.
        assertFalse(chooser.isTraversable(null))
        assertEquals(null, chooser.getName(null))
        assertEquals(null, chooser.getIcon(null))
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
          assertFalse(chooser.getName(directory).orEmpty().isBlank())
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
        panel.tabs.selectedIndex = 3
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
