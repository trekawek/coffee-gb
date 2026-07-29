package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings.RomChangeConfirmationPolicy
import eu.rekawek.coffeegb.controller.properties.ControllerProperties
import eu.rekawek.coffeegb.swing.io.AudioDeviceSnapshot
import eu.rekawek.coffeegb.swing.io.GamepadCatalog
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dialog
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.text.ParseException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTabbedPane
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The validated portion of Preferences currently exposed by the desktop UI.
 *
 * Applying this object to the latest settings, instead of replacing the document captured when the
 * dialog opened, preserves every hidden section.
 */
internal data class PreferencesEdit(
    val romDirectory: Path?,
    val recentFileCapacity: Int,
    val confirmationPolicy: RomChangeConfirmationPolicy,
    val display: ApplicationSettings.Display,
    val keyboard:
        Map<
            ControllerProperties.PlayerButton,
            ApplicationSettings.KeyboardKey,
        >,
    val gamepads: Map<Int, ApplicationSettings.GamepadSelection>,
    val gamepadTunings: Map<String, ApplicationSettings.GamepadTuning>,
    val cameraDeviceIndex: Int,
    val audio: ApplicationSettings.Audio,
    val saves: ApplicationSettings.Saves? = null,
    val advanced: ApplicationSettings.Advanced? = null,
    val forceWindowSize: Boolean = false,
) {
  init {
    require(
        recentFileCapacity in
            ApplicationSettings.MIN_RECENT_FILE_CAPACITY..
                ApplicationSettings.MAX_RECENT_FILE_CAPACITY)
    ApplicationSettings.Input(keyboard, gamepads, gamepadTunings).toPlayerMapping()
  }

  fun applyTo(current: ApplicationSettings): ApplicationSettings =
      current.copy(
          general =
              current.general.copy(
                  romDirectory = romDirectory,
                  recentRoms = current.general.recentRoms.take(recentFileCapacity),
                  recentFileCapacity = recentFileCapacity,
                  romChangeConfirmationPolicy = confirmationPolicy,
              ),
          display = display,
          audio = audio,
          saves = saves ?: current.saves,
          advanced =
              advanced?.let { edited ->
                current.advanced.copy(
                    dmgGamesProfile = edited.dmgGamesProfile,
                    cgbGamesProfile = edited.cgbGamesProfile,
                    bootstrapMode = edited.bootstrapMode,
                )
              } ?: current.advanced,
          input =
              current.input.copy(
                  keyboard = keyboard,
                  gamepads = gamepads,
                  gamepadTunings = gamepadTunings,
              ),
          peripherals = current.peripherals.copy(cameraDeviceIndex = cameraDeviceIndex),
      )
}

internal fun interface RomDirectoryChooser {
  fun choose(parent: Component, initial: Path?): Path?
}

/**
 * Headless-testable Preferences content. All widgets represent a draft; this class never persists
 * settings or changes a live emulator.
 */
internal class PreferencesPanel private constructor(
    initial: ApplicationSettings,
    private val defaults: ApplicationSettings = ApplicationSettings(),
    private val directoryChooser: RomDirectoryChooser = SYSTEM_DIRECTORY_CHOOSER,
    gamepadSnapshots: GamepadSnapshotProvider = EMPTY_GAMEPAD_SNAPSHOTS,
    audioDevices: AudioDeviceProvider = SYSTEM_AUDIO_DEVICES,
    saveDirectoryChooser: SaveDirectoryChooser = SYSTEM_SAVE_DIRECTORY_CHOOSER,
    @Suppress("UNUSED_PARAMETER") edtGuard: Unit,
) : JPanel(BorderLayout(0, 8)) {
  constructor(
      initial: ApplicationSettings,
      defaults: ApplicationSettings = ApplicationSettings(),
      directoryChooser: RomDirectoryChooser = SYSTEM_DIRECTORY_CHOOSER,
      gamepadSnapshots: GamepadSnapshotProvider = EMPTY_GAMEPAD_SNAPSHOTS,
      audioDevices: AudioDeviceProvider = SYSTEM_AUDIO_DEVICES,
      saveDirectoryChooser: SaveDirectoryChooser = SYSTEM_SAVE_DIRECTORY_CHOOSER,
  ) : this(
      initial,
      defaults,
      directoryChooser,
      gamepadSnapshots,
      audioDevices,
      saveDirectoryChooser,
      requireEdt(),
  )

  internal val directoryField = JTextField(initial.general.romDirectory?.toString().orEmpty(), 32)
  internal val directoryError = JLabel(" ")
  internal val recentCapacity =
      JSpinner(
          SpinnerNumberModel(
              initial.general.recentFileCapacity,
              ApplicationSettings.MIN_RECENT_FILE_CAPACITY,
              ApplicationSettings.MAX_RECENT_FILE_CAPACITY,
              1,
          ))
  internal val recentCapacityError = JLabel(" ")
  internal val confirmationPolicy =
      JComboBox(CONFIRMATION_OPTIONS.toTypedArray()).apply {
        selectedItem =
            CONFIRMATION_OPTIONS.first {
              it.policy == initial.general.romChangeConfirmationPolicy
            }
      }
  internal val displayEditor =
      DisplayPreferencesEditor(initial.display, defaults.display)
  internal val systemEditor =
      SystemPreferencesEditor(initial.advanced, defaults.advanced)
  internal val keyboardEditor = KeyboardMappingEditor(initial.input, defaults.input)
  internal val gamepadEditor =
      GamepadPreferencesEditor(initial.input, defaults.input, gamepadSnapshots)
  internal val peripheralsEditor =
      PeripheralsPreferencesEditor(initial.peripherals, defaults.peripherals)
  internal val audioEditor =
      AudioPreferencesEditor(initial.audio, defaults.audio, audioDevices)
  internal val savesEditor =
      SavesPreferencesEditor(initial.saves, defaults.saves, saveDirectoryChooser)
  internal val validationSummary = JLabel(" ")
  internal val tabs = JTabbedPane()

  init {
    border = BorderFactory.createEmptyBorder(12, 12, 0, 12)
    getAccessibleContext().accessibleName = "Coffee GB preferences"

    tabs.accessibleContext.accessibleName = "Preference categories"
    tabs.addTab("General", createGeneralPanel())
    tabs.addTab("System", JScrollPane(systemEditor).apply { border = null })
    tabs.addTab("Display", JScrollPane(displayEditor).apply { border = null })
    tabs.addTab("Input", JScrollPane(keyboardEditor).apply { border = null })
    tabs.addTab("Gamepads", JScrollPane(gamepadEditor).apply { border = null })
    tabs.addTab("Peripherals", JScrollPane(peripheralsEditor).apply { border = null })
    tabs.addTab("Audio", JScrollPane(audioEditor).apply { border = null })
    tabs.addTab("Saves", JScrollPane(savesEditor).apply { border = null })
    tabs.addChangeListener {
      if (tabs.selectedIndex != INPUT_TAB) {
        keyboardEditor.cancelCapture()
      }
    }
    add(tabs, BorderLayout.CENTER)

    validationSummary.foreground = ERROR_COLOR
    validationSummary.accessibleContext.accessibleName = "Preferences validation error"
    add(validationSummary, BorderLayout.SOUTH)
  }

  internal fun restoreDefaults() {
    requireEdt()
    directoryField.text = defaults.general.romDirectory?.toString().orEmpty()
    recentCapacity.value = defaults.general.recentFileCapacity
    confirmationPolicy.selectedItem =
        CONFIRMATION_OPTIONS.first {
          it.policy == defaults.general.romChangeConfirmationPolicy
        }
    displayEditor.restoreDefaults()
    systemEditor.restoreDefaults()
    keyboardEditor.resetToDefaults()
    gamepadEditor.restoreDefaults()
    peripheralsEditor.restoreDefaults()
    audioEditor.restoreDefaults()
    savesEditor.restoreDefaults()
    clearErrors()
  }

  internal fun validatedEdit(): PreferencesEdit {
    requireEdt()
    clearErrors()
    val directory = validateDirectory()
    val capacity = validateRecentCapacity()
    val display =
        try {
          displayEditor.validatedDisplay()
        } catch (failure: PreferenceEditorValidationException) {
          validationSummary.text = failure.message ?: "Resolve the display settings error."
          tabs.selectedIndex = DISPLAY_TAB
          throw PreferencesValidationException(
              validationSummary.text,
              failure.invalidComponent,
          )
        }
    val input =
        try {
          keyboardEditor.validatedDraft()
        } catch (failure: IllegalArgumentException) {
          validationSummary.text = failure.message ?: "Resolve the keyboard binding conflict."
          tabs.selectedIndex = INPUT_TAB
          throw PreferencesValidationException(validationSummary.text, keyboardEditor)
        }
    val gamepad =
        try {
          gamepadEditor.validatedDraft()
        } catch (failure: PreferenceEditorValidationException) {
          validationSummary.text = failure.message ?: "Resolve the gamepad settings error."
          tabs.selectedIndex = GAMEPADS_TAB
          throw PreferencesValidationException(
              validationSummary.text,
              failure.invalidComponent,
          )
        }
    val audio =
        try {
          audioEditor.validatedAudio()
        } catch (failure: PreferenceEditorValidationException) {
          validationSummary.text = failure.message ?: "Resolve the audio settings error."
          tabs.selectedIndex = AUDIO_TAB
          throw PreferencesValidationException(
              validationSummary.text,
              failure.invalidComponent,
          )
        }
    val saves =
        try {
          savesEditor.validatedSaves()
        } catch (failure: PreferenceEditorValidationException) {
          validationSummary.text = failure.message ?: "Resolve the saves settings error."
          tabs.selectedIndex = SAVES_TAB
          throw PreferencesValidationException(
              validationSummary.text,
              failure.invalidComponent,
          )
        }
    return PreferencesEdit(
        romDirectory = directory,
        recentFileCapacity = capacity,
        confirmationPolicy = (confirmationPolicy.selectedItem as ConfirmationOption).policy,
        display = display,
        keyboard = input.keyboard,
        gamepads = gamepad.selections,
        gamepadTunings = gamepad.tunings,
        cameraDeviceIndex = peripheralsEditor.validatedPeripherals().cameraDeviceIndex,
        audio = audio,
        saves = saves,
        advanced = systemEditor.validatedAdvanced(),
        forceWindowSize = displayEditor.windowScaleCommandRequested,
    )
  }

  internal fun stopBackgroundWork() {
    requireEdt()
    keyboardEditor.cancelCapture()
    gamepadEditor.stopCatalogUpdates()
    audioEditor.cancelDeviceLoading()
  }

  override fun removeNotify() {
    stopBackgroundWork()
    super.removeNotify()
  }

  internal fun showApplyFailure(failure: RuntimeException) {
    requireEdt()
    val detail = failure.message?.trim()?.takeIf(String::isNotEmpty)
    validationSummary.text =
        if (detail == null) {
          "Preferences could not be applied."
        } else {
          "Preferences could not be applied: $detail"
        }
  }

  internal fun showSaveDirectoryFailure(message: String) {
    requireEdt()
    savesEditor.showDirectoryValidationError(message)
    validationSummary.text = message
    tabs.selectedIndex = SAVES_TAB
    savesEditor.directoryField.requestFocusInWindow()
  }

  private fun createGeneralPanel(): JPanel {
    val panel = JPanel(GridBagLayout())
    panel.border = BorderFactory.createEmptyBorder(8, 4, 8, 4)
    val constraints =
        GridBagConstraints().apply {
          anchor = GridBagConstraints.LINE_START
          fill = GridBagConstraints.HORIZONTAL
          insets = Insets(4, 4, 4, 4)
        }

    val directoryLabel = JLabel("Default ROM directory:")
    directoryLabel.displayedMnemonic = KeyEvent.VK_D
    directoryLabel.labelFor = directoryField
    directoryField.accessibleContext.accessibleName = "Default ROM directory"
    directoryField.toolTipText =
        "The directory shown first when choosing a ROM. Leave blank to use the system default."
    directoryField.document.addDocumentListener(
        object : DocumentListener {
          override fun insertUpdate(event: DocumentEvent) = clearDirectoryError()

          override fun removeUpdate(event: DocumentEvent) = clearDirectoryError()

          override fun changedUpdate(event: DocumentEvent) = clearDirectoryError()
        })

    val browse = JButton("Browse…")
    browse.mnemonic = KeyEvent.VK_B
    browse.accessibleContext.accessibleName = "Browse for default ROM directory"
    browse.addActionListener {
      val initialPath = runCatching { parseDirectory(directoryField.text) }.getOrNull()
      directoryChooser.choose(this, initialPath)?.let {
        directoryField.text = it.toString()
        clearDirectoryError()
      }
    }

    val directoryRow = JPanel(BorderLayout(6, 0))
    directoryRow.add(directoryField, BorderLayout.CENTER)
    directoryRow.add(browse, BorderLayout.LINE_END)
    addRow(panel, constraints, 0, directoryLabel, directoryRow)

    directoryError.foreground = ERROR_COLOR
    directoryError.accessibleContext.accessibleName = "Default ROM directory error"
    constraints.gridx = 1
    constraints.gridy = 1
    constraints.weightx = 1.0
    panel.add(directoryError, constraints)

    val recentLabel = JLabel("Recent files to keep:")
    recentLabel.displayedMnemonic = KeyEvent.VK_R
    recentLabel.labelFor = recentCapacity
    recentCapacity.accessibleContext.accessibleName = "Recent files to keep"
    recentCapacity.toolTipText =
        "Choose between ${ApplicationSettings.MIN_RECENT_FILE_CAPACITY} and " +
            "${ApplicationSettings.MAX_RECENT_FILE_CAPACITY}."
    addRow(panel, constraints, 2, recentLabel, recentCapacity)

    recentCapacityError.foreground = ERROR_COLOR
    recentCapacityError.accessibleContext.accessibleName = "Recent-file capacity error"
    constraints.gridx = 1
    constraints.gridy = 3
    constraints.weightx = 1.0
    panel.add(recentCapacityError, constraints)
    (recentCapacity.editor as JSpinner.DefaultEditor).textField.document.addDocumentListener(
        object : DocumentListener {
          override fun insertUpdate(event: DocumentEvent) = clearRecentCapacityError()

          override fun removeUpdate(event: DocumentEvent) = clearRecentCapacityError()

          override fun changedUpdate(event: DocumentEvent) = clearRecentCapacityError()
        })

    val confirmationLabel = JLabel("Replacing or closing a game:")
    confirmationLabel.displayedMnemonic = KeyEvent.VK_C
    confirmationLabel.labelFor = confirmationPolicy
    confirmationPolicy.accessibleContext.accessibleName =
        "Confirmation policy for replacing or closing a game"
    addRow(panel, constraints, 4, confirmationLabel, confirmationPolicy)

    constraints.gridx = 0
    constraints.gridy = 5
    constraints.gridwidth = 2
    constraints.weighty = 1.0
    constraints.fill = GridBagConstraints.BOTH
    panel.add(JPanel(), constraints)
    return panel
  }

  private fun validateDirectory(): Path? {
    val directory =
        try {
          parseDirectory(directoryField.text)
        } catch (_: InvalidPathException) {
          failDirectory("Enter a valid directory path.")
        }
    return directory
  }

  private fun validateRecentCapacity(): Int {
    try {
      recentCapacity.commitEdit()
    } catch (_: ParseException) {
      failRecentCapacity(
          "Enter a whole number from ${ApplicationSettings.MIN_RECENT_FILE_CAPACITY} to " +
              "${ApplicationSettings.MAX_RECENT_FILE_CAPACITY}.")
    }
    val value = (recentCapacity.value as Number).toInt()
    if (
        value !in
            ApplicationSettings.MIN_RECENT_FILE_CAPACITY..
                ApplicationSettings.MAX_RECENT_FILE_CAPACITY) {
      failRecentCapacity(
          "Enter a whole number from ${ApplicationSettings.MIN_RECENT_FILE_CAPACITY} to " +
              "${ApplicationSettings.MAX_RECENT_FILE_CAPACITY}.")
    }
    return value
  }

  private fun failDirectory(message: String): Nothing {
    directoryError.text = message
    validationSummary.text = message
    tabs.selectedIndex = GENERAL_TAB
    throw PreferencesValidationException(message, directoryField)
  }

  private fun failRecentCapacity(message: String): Nothing {
    recentCapacityError.text = message
    validationSummary.text = message
    tabs.selectedIndex = GENERAL_TAB
    throw PreferencesValidationException(
        message,
        (recentCapacity.editor as JSpinner.DefaultEditor).textField,
    )
  }

  private fun clearErrors() {
    clearDirectoryError()
    clearRecentCapacityError()
    validationSummary.text = " "
  }

  private fun clearDirectoryError() {
    directoryError.text = " "
    if (validationSummary.text != " ") {
      validationSummary.text = " "
    }
  }

  private fun clearRecentCapacityError() {
    recentCapacityError.text = " "
    if (validationSummary.text != " ") {
      validationSummary.text = " "
    }
  }

  private fun addRow(
      panel: JPanel,
      constraints: GridBagConstraints,
      row: Int,
      label: JLabel,
      field: Component,
  ) {
    constraints.gridx = 0
    constraints.gridy = row
    constraints.gridwidth = 1
    constraints.weightx = 0.0
    constraints.weighty = 0.0
    constraints.fill = GridBagConstraints.NONE
    panel.add(label, constraints)

    constraints.gridx = 1
    constraints.weightx = 1.0
    constraints.fill = GridBagConstraints.HORIZONTAL
    panel.add(field, constraints)
  }

  internal data class ConfirmationOption(
      val policy: RomChangeConfirmationPolicy,
      val label: String,
  ) {
    override fun toString(): String = label
  }

  private companion object {
    const val GENERAL_TAB = 0
    const val DISPLAY_TAB = 2
    const val INPUT_TAB = 3
    const val GAMEPADS_TAB = 4
    const val PERIPHERALS_TAB = 5
    const val AUDIO_TAB = 6
    const val SAVES_TAB = 7
    val ERROR_COLOR = Color(0xB0, 0x00, 0x20)
    val CONFIRMATION_OPTIONS =
        listOf(
            ConfirmationOption(RomChangeConfirmationPolicy.ALWAYS, "Always ask"),
            ConfirmationOption(
                RomChangeConfirmationPolicy.WHEN_RUNNING,
                "Ask while a game is running",
            ),
            ConfirmationOption(RomChangeConfirmationPolicy.NEVER, "Never ask"),
        )

    val SYSTEM_DIRECTORY_CHOOSER =
        RomDirectoryChooser { parent, initial ->
          val chooser =
              RomFileChooser().apply {
                dialogTitle = "Choose default ROM directory"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isAcceptAllFileFilterUsed = false
                initial?.let(::useConfiguredDirectory)
              }
          if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.toPath()
          } else {
            null
          }
        }

    val EMPTY_GAMEPAD_SNAPSHOTS =
        GamepadSnapshotProvider {
          GamepadCatalog.Snapshot(
              GamepadCatalog.Status.UNAVAILABLE,
              emptyList(),
              "Game controllers are unavailable. Keyboard input remains available.",
          )
        }

    val SYSTEM_AUDIO_DEVICES =
        AudioDeviceProvider { listOf(AudioDeviceSnapshot.systemDefaultDevice()) }

    fun parseDirectory(value: String): Path? =
        value.trim().takeIf(String::isNotEmpty)?.let(Paths::get)

    fun requireEdt() {
      check(SwingUtilities.isEventDispatchThread()) {
        "Preferences components must be constructed and accessed on the EDT"
      }
    }
  }
}

internal class PreferencesValidationException(
    message: String,
    val invalidComponent: Component,
) : IllegalArgumentException(message)

/** Owns only the modal window lifecycle. Persistence and runtime changes belong to [SwingGui]. */
internal object PreferencesDialog {
  fun show(
      owner: Window,
      initial: ApplicationSettings,
      defaults: ApplicationSettings = ApplicationSettings(),
      gamepadCatalog: GamepadCatalog = GamepadCatalog(),
      audioDevices: AudioDeviceProvider =
          AudioDeviceProvider { listOf(AudioDeviceSnapshot.systemDefaultDevice()) },
      applyEdit: (PreferencesEdit) -> Unit,
  ) {
    check(SwingUtilities.isEventDispatchThread()) {
      "Preferences dialog must be opened on the EDT"
    }

    val dialog = JDialog(owner, "Preferences", Dialog.ModalityType.APPLICATION_MODAL)
    val panel =
        PreferencesPanel(
            initial,
            defaults,
            gamepadSnapshots = GamepadSnapshotProvider(gamepadCatalog::snapshot),
            audioDevices = audioDevices,
        )
    val applyButton = JButton("Apply")
    val cancelButton = JButton("Cancel")
    val restoreButton = JButton("Restore Defaults")

    val actions =
        PreferencesDialogActions(
            panel,
            applyEdit,
            dialog::dispose,
            applyingChanged = { applying ->
              applyButton.isEnabled = !applying
              restoreButton.isEnabled = !applying
            },
        )

    applyButton.accessibleContext.accessibleName = "Apply preferences"
    cancelButton.accessibleContext.accessibleName = "Cancel preferences"
    restoreButton.accessibleContext.accessibleName = "Restore default preferences"
    applyButton.addActionListener { actions.apply() }
    cancelButton.addActionListener { actions.cancel() }
    restoreButton.addActionListener { actions.restoreDefaults() }

    val buttons = JPanel(FlowLayout(FlowLayout.TRAILING))
    buttons.border = BorderFactory.createEmptyBorder(0, 12, 12, 12)
    buttons.add(restoreButton)
    buttons.add(cancelButton)
    buttons.add(applyButton)

    dialog.contentPane.layout = BorderLayout()
    dialog.contentPane.add(panel, BorderLayout.CENTER)
    dialog.contentPane.add(buttons, BorderLayout.SOUTH)
    dialog.defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
    dialog.addWindowListener(
        object : WindowAdapter() {
          override fun windowClosing(event: WindowEvent) = actions.cancel()
        })
    configurePreferencesRootPane(dialog.rootPane, applyButton, actions::cancel)
    dialog.pack()
    dialog.minimumSize = dialog.size
    dialog.setLocationRelativeTo(owner)
    dialog.isVisible = true
  }
}

/** Testable orchestration for the dialog buttons; it deliberately owns no Swing window. */
internal class PreferencesDialogActions(
    private val panel: PreferencesPanel,
    private val applyEdit: (PreferencesEdit) -> Unit,
    private val close: () -> Unit,
    private val saveDirectoryValidator: SaveDirectoryValidator = SYSTEM_SAVE_DIRECTORY_VALIDATOR,
    private val validationExecutor: Executor = createSaveDirectoryValidationExecutor(),
    private val closeValidationExecutor: () -> Unit = {
      (validationExecutor as? ExecutorService)?.shutdownNow()
    },
    private val uiExecutor: ((() -> Unit) -> Unit) = { SwingUtilities.invokeLater(it) },
    private val applyingChanged: (Boolean) -> Unit = {},
) {
  private var validationPending = false
  private var closed = false
  private var validationGeneration = 0L

  fun apply() {
    if (validationPending || closed) return
    val edit =
        try {
          panel.validatedEdit()
        } catch (failure: PreferencesValidationException) {
          failure.invalidComponent.requestFocusInWindow()
          return
        }
    val directory = edit.saves?.directory
    if (directory == null) {
      finishApply(edit)
      return
    }

    validationPending = true
    applyingChanged(true)
    val generation = ++validationGeneration
    try {
      validationExecutor.execute {
        val error =
            try {
              saveDirectoryValidator.validate(directory)
            } catch (failure: RuntimeException) {
              "Coffee GB could not validate the save data directory " +
                  "(${failure.javaClass.simpleName})."
            }
        uiExecutor {
          if (closed || generation != validationGeneration) return@uiExecutor
          validationPending = false
          applyingChanged(false)
          if (error != null) {
            panel.showSaveDirectoryFailure(error)
          } else {
            finishApply(edit)
          }
        }
      }
    } catch (_: RejectedExecutionException) {
      validationPending = false
      applyingChanged(false)
      panel.showSaveDirectoryFailure(
          "Save directory validation is busy. Wait a moment, then apply again.")
    }
  }

  private fun finishApply(edit: PreferencesEdit) {
    try {
      applyEdit(edit)
    } catch (failure: RuntimeException) {
      panel.showApplyFailure(failure)
      return
    }
    panel.stopBackgroundWork()
    closeValidationExecutor()
    closed = true
    close()
  }

  fun cancel() {
    if (closed) return
    closed = true
    validationGeneration++
    validationPending = false
    applyingChanged(false)
    panel.stopBackgroundWork()
    closeValidationExecutor()
    close()
  }

  fun restoreDefaults() {
    if (!validationPending && !closed) {
      panel.restoreDefaults()
    }
  }
}

private val SAVE_DIRECTORY_VALIDATION_THREAD_ID = AtomicLong()

private fun createSaveDirectoryValidationExecutor(): Executor =
    ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { command ->
          Thread(
                  command,
                  "coffee-gb-save-directory-check-" +
                      SAVE_DIRECTORY_VALIDATION_THREAD_ID.incrementAndGet(),
              )
              .apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy(),
    )

internal fun configurePreferencesRootPane(
    rootPane: JRootPane,
    applyButton: JButton,
    cancel: () -> Unit,
) {
  rootPane.defaultButton = applyButton
  val cancelAction = "cancel-preferences"
  rootPane
      .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
      .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), cancelAction)
  rootPane.actionMap.put(
      cancelAction,
      object : AbstractAction() {
        override fun actionPerformed(event: ActionEvent) = cancel()
      },
  )
}
