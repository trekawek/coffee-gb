package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.controller.properties.ApplicationSettings.RomChangeConfirmationPolicy
import eu.rekawek.coffeegb.controller.properties.ControllerProperties
import eu.rekawek.coffeegb.swing.io.AudioDeviceSnapshot
import eu.rekawek.coffeegb.swing.io.GamepadCatalog
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
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
import javax.swing.AbstractButton
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent

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
    val autofireKeyboard:
        Map<
            ControllerProperties.PlayerAutofireButton,
            ApplicationSettings.KeyboardKey,
        > = emptyMap(),
    val gamepads: Map<Int, ApplicationSettings.GamepadSelection>,
    val gamepadTunings: Map<String, ApplicationSettings.GamepadTuning>,
    val cameraDeviceIndex: Int,
    val audio: ApplicationSettings.Audio,
    val desktop: ApplicationSettings.Desktop? = null,
    val saves: ApplicationSettings.Saves? = null,
    val advanced: ApplicationSettings.Advanced? = null,
    val forceWindowSize: Boolean = false,
) {
  init {
    require(
        recentFileCapacity in
            ApplicationSettings.MIN_RECENT_FILE_CAPACITY..
                ApplicationSettings.MAX_RECENT_FILE_CAPACITY)
    ApplicationSettings.Input(
            keyboard,
            gamepads,
            gamepadTunings,
            autofireKeyboard,
        )
        .toPlayerMapping()
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
                    executionMode = edited.executionMode,
                )
              } ?: current.advanced,
          input =
              current.input.copy(
                  keyboard = keyboard,
                  autofireKeyboard = autofireKeyboard,
                  gamepads = gamepads,
                  gamepadTunings = gamepadTunings,
              ),
          peripherals = current.peripherals.copy(cameraDeviceIndex = cameraDeviceIndex),
          desktop =
              desktop?.let { edited ->
                current.desktop.copy(
                    appearance = edited.appearance,
                    commandBarVisible = edited.commandBarVisible,
                )
              } ?: current.desktop,
      )
}

internal enum class PreferencesCategory(val displayName: String) {
  GENERAL("General"),
  DISPLAY("Display"),
  AUDIO("Audio"),
  CONTROLS("Controls"),
  SAVES_AND_REWIND("Saves & Rewind"),
  SYSTEM("System"),
  PERIPHERALS("Peripherals"),
}

/** Presentation supplied by the settings owner without coupling the dialog to its store. */
internal data class PreferencesPersistencePresentation(
    val sessionOnly: Boolean = false,
    val message: String? = null,
) {
  val primaryActionLabel: String
    get() = if (sessionOnly) "Apply for this session" else "Save changes"

  companion object {
    val PERSISTENT = PreferencesPersistencePresentation()

    fun sessionOnly(message: String = SESSION_ONLY_MESSAGE) =
        PreferencesPersistencePresentation(sessionOnly = true, message = message)

    private const val SESSION_ONLY_MESSAGE =
        "Settings are read-only. Changes apply to this session and will not survive restart."
  }
}

/** A native, keyboard-accessible category list backed by a card page. */
internal class PreferencesCategoryNavigation(
    pages: Map<PreferencesCategory, Component>,
    initialCategory: PreferencesCategory,
    private val categoryChanged: (PreferencesCategory) -> Unit,
) : JPanel(BorderLayout(12, 0)) {
  private val categories = PreferencesCategory.entries
  internal val categoryList =
      JList(categories.toTypedArray()).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        fixedCellWidth = 168
        cellRenderer =
            object : DefaultListCellRenderer() {
              override fun getListCellRendererComponent(
                  list: JList<*>?,
                  value: Any?,
                  index: Int,
                  isSelected: Boolean,
                  cellHasFocus: Boolean,
              ): Component =
                  super.getListCellRendererComponent(
                          list,
                          (value as? PreferencesCategory)?.displayName ?: value,
                          index,
                          isSelected,
                          cellHasFocus,
                      )
                      .also { component ->
                        (component as? JComponent)?.accessibleContext?.accessibleName =
                            (value as? PreferencesCategory)?.displayName ?: value?.toString()
                      }
            }
        getAccessibleContext().accessibleName = "Preference categories"
        getAccessibleContext().accessibleDescription =
            "Choose a category to show its settings page."
      }
  private val cardLayout = CardLayout()
  private val pageCards = JPanel(cardLayout)

  init {
    getAccessibleContext().accessibleName = "Preference categories and pages"
    categories.forEach { category ->
      pageCards.add(checkNotNull(pages[category]), category.name)
    }
    categoryList.addListSelectionListener { event ->
      if (!event.valueIsAdjusting) {
        val selected = categoryList.selectedValue ?: return@addListSelectionListener
        cardLayout.show(pageCards, selected.name)
        categoryChanged(selected)
      }
    }
    add(categoryList, BorderLayout.LINE_START)
    add(pageCards, BorderLayout.CENTER)
    categoryList.setSelectedValue(initialCategory, true)
    cardLayout.show(pageCards, initialCategory.name)
  }

  var selectedCategory: PreferencesCategory
    get() = categoryList.selectedValue ?: PreferencesCategory.GENERAL
    set(value) {
      categoryList.setSelectedValue(value, true)
      cardLayout.show(pageCards, value.name)
    }

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
    private val persistence: PreferencesPersistencePresentation =
        PreferencesPersistencePresentation.PERSISTENT,
    initialCategory: PreferencesCategory = PreferencesCategory.GENERAL,
    categoryChanged: (PreferencesCategory) -> Unit = {},
    private val draftChanged: (Boolean) -> Unit = {},
    mobileAdapterSummary: String = "Offline · networking blocked for this session",
    configureMobileAdapter: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") edtGuard: Unit,
) : JPanel(BorderLayout(12, 8)), DesktopThemeRefreshHook {
  constructor(
      initial: ApplicationSettings,
      defaults: ApplicationSettings = ApplicationSettings(),
      directoryChooser: RomDirectoryChooser = SYSTEM_DIRECTORY_CHOOSER,
      gamepadSnapshots: GamepadSnapshotProvider = EMPTY_GAMEPAD_SNAPSHOTS,
      audioDevices: AudioDeviceProvider = SYSTEM_AUDIO_DEVICES,
      saveDirectoryChooser: SaveDirectoryChooser = SYSTEM_SAVE_DIRECTORY_CHOOSER,
      persistence: PreferencesPersistencePresentation =
          PreferencesPersistencePresentation.PERSISTENT,
      initialCategory: PreferencesCategory = PreferencesCategory.GENERAL,
      categoryChanged: (PreferencesCategory) -> Unit = {},
      draftChanged: (Boolean) -> Unit = {},
      mobileAdapterSummary: String = "Offline · networking blocked for this session",
      configureMobileAdapter: () -> Unit = {},
  ) : this(
      initial,
      defaults,
      directoryChooser,
      gamepadSnapshots,
      audioDevices,
      saveDirectoryChooser,
      persistence,
      initialCategory,
      categoryChanged,
      draftChanged,
      mobileAdapterSummary,
      configureMobileAdapter,
      requireEdt(),
  )

  private val initialSettings = initial
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
  internal val appearance =
      JComboBox(APPEARANCE_OPTIONS.toTypedArray()).apply {
        selectedItem = APPEARANCE_OPTIONS.first { it.appearance == initial.desktop.appearance }
        getAccessibleContext().accessibleName = "Application appearance"
        getAccessibleContext().accessibleDescription =
            "Choose a light, dark, or operating-system appearance for every Coffee GB window."
      }
  internal val commandBarVisible =
      JCheckBox("Show command bar while playing", initial.desktop.commandBarVisible).apply {
        getAccessibleContext().accessibleName = "Show command bar while playing"
        getAccessibleContext().accessibleDescription =
            "Show the compact gameplay command bar below the emulator display."
      }
  internal val displayEditor =
      DisplayPreferencesEditor(initial.display, defaults.display)
  internal val systemEditor =
      SystemPreferencesEditor(initial.advanced, defaults.advanced)
  internal val keyboardEditor = KeyboardMappingEditor(initial.input, defaults.input)
  internal val gamepadEditor =
      GamepadPreferencesEditor(initial.input, defaults.input, gamepadSnapshots)
  internal val controlsPlayerSelector =
      JComboBox(CONTROL_PLAYER_OPTIONS.toTypedArray()).apply {
        getAccessibleContext().accessibleName = "Controls player"
        getAccessibleContext().accessibleDescription =
            "Choose which player's keyboard and game controller settings are shown."
        addActionListener {
          val player = (selectedItem as? ControlPlayerOption)?.player ?: return@addActionListener
          keyboardEditor.selectPlayer(player)
          gamepadEditor.selectPlayer(player)
        }
      }
  internal val controlsSubpages =
      JTabbedPane().apply {
        getAccessibleContext().accessibleName = "Control type"
        getAccessibleContext().accessibleDescription =
            "Choose Keyboard or Gamepad settings for the selected player."
      }
  internal val controlsRuntimeGuidance =
      JTextArea(
              "Autofire uses separate A/B keyboard bindings or gamepad L1/R1. " +
                  "Fixed runtime controls: Backspace rewinds; I/J/K/L tilt supported cartridges. " +
                  "Application shortcuts are listed in Help > Keyboard Shortcuts and withdraw " +
                  "when an unmodified key is assigned to gameplay.")
          .apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            rows = 2
            putClientProperty("html.disable", true)
            getAccessibleContext().accessibleName = "Fixed runtime controls"
            getAccessibleContext().accessibleDescription = text
          }
  internal val peripheralsEditor =
      PeripheralsPreferencesEditor(
          initial.peripherals,
          defaults.peripherals,
          mobileAdapterSummary,
          configureMobileAdapter,
      )
  internal val audioEditor =
      AudioPreferencesEditor(initial.audio, defaults.audio, audioDevices)
  internal val savesEditor =
      SavesPreferencesEditor(initial.saves, defaults.saves, saveDirectoryChooser)
  internal val validationSummary = JLabel(" ")
  internal val persistenceBanner =
      JLabel(persistence.message.orEmpty()).apply {
        isVisible = !persistence.message.isNullOrBlank()
        getAccessibleContext().accessibleName = "Preferences persistence notice"
        getAccessibleContext().accessibleDescription = text
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(
                UIManager.getColor("Separator.foreground")
                    ?: UIManager.getColor("Label.disabledForeground")
                    ?: foreground,
            ),
            BorderFactory.createEmptyBorder(8, 10, 8, 10),
        )
      }
  private val styledSectionLabels = mutableListOf<Pair<JLabel, Boolean>>()
  private var trackedGamepadTunings = initial.input.gamepadTunings.toMutableMap()
  private var explicitlyTrackedGamepadTunings = initial.input.gamepadTunings.keys.toMutableSet()
  private var suppressDirtyNotifications = false
  private lateinit var openingFingerprint: PreferencesDraftFingerprint
  private var lastPublishedDirty = false

  internal val categories =
      PreferencesCategoryNavigation(
          pages =
              mapOf(
                  PreferencesCategory.GENERAL to categoryPage("General", createGeneralPanel()),
                  PreferencesCategory.DISPLAY to categoryPage("Display", displayEditor),
                  PreferencesCategory.AUDIO to categoryPage("Audio", audioEditor),
                  PreferencesCategory.CONTROLS to categoryPage("Controls", createControlsPanel()),
                  PreferencesCategory.SAVES_AND_REWIND to
                      categoryPage("Saves & Rewind", savesEditor),
                  PreferencesCategory.SYSTEM to categoryPage("System", systemEditor),
                  PreferencesCategory.PERIPHERALS to
                      categoryPage("Peripherals", peripheralsEditor),
              ),
          initialCategory = initialCategory,
          categoryChanged = { category ->
            if (category != PreferencesCategory.CONTROLS) {
              keyboardEditor.cancelCapture()
            }
            categoryChanged(category)
          },
      )

  init {
    border = BorderFactory.createEmptyBorder(12, 12, 0, 12)
    getAccessibleContext().accessibleName = "Coffee GB preferences"

    add(persistenceBanner, BorderLayout.NORTH)
    add(categories, BorderLayout.CENTER)

    validationSummary.foreground = desktopValidationErrorColor()
    validationSummary.accessibleContext.accessibleName = "Preferences validation error"
    add(validationSummary, BorderLayout.SOUTH)

    installDirtyTracking(this)
    installGamepadTuningTracking()
    openingFingerprint = draftFingerprint()
    publishDirtyState()
  }

  override fun desktopThemeChanged(tokens: DesktopThemeTokens) {
    validationSummary.foreground = tokens.danger
    directoryError.foreground = tokens.danger
    recentCapacityError.foreground = tokens.danger
    controlsRuntimeGuidance.background = tokens.surface
    controlsRuntimeGuidance.foreground = tokens.secondaryText
    persistenceBanner.border =
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(tokens.border),
            BorderFactory.createEmptyBorder(8, 10, 8, 10),
        )
    val labelFont = UIManager.getFont("Label.font")
    if (labelFont != null) {
      styledSectionLabels.forEach { (label, pageTitle) ->
        label.font =
            labelFont.deriveFont(
                labelFont.style or java.awt.Font.BOLD,
                labelFont.size2D + if (pageTitle) 3f else 1f,
            )
      }
    }
    revalidate()
    repaint()
  }

  internal fun restoreSelectedPageDefaults() {
    requireEdt()
    withSuppressedDirtyNotifications {
      when (categories.selectedCategory) {
        PreferencesCategory.GENERAL -> restoreGeneralDefaults()
        PreferencesCategory.DISPLAY -> displayEditor.restoreDefaults()
        PreferencesCategory.AUDIO -> audioEditor.restoreDefaults()
        PreferencesCategory.CONTROLS -> restoreControlsDefaults()
        PreferencesCategory.SAVES_AND_REWIND -> savesEditor.restoreDefaults()
        PreferencesCategory.SYSTEM -> systemEditor.restoreDefaults()
        PreferencesCategory.PERIPHERALS -> peripheralsEditor.restoreDefaults()
      }
      clearErrors()
    }
    publishDirtyState()
  }

  internal fun restoreAllDefaults() {
    requireEdt()
    withSuppressedDirtyNotifications {
      restoreGeneralDefaults()
      displayEditor.restoreDefaults()
      audioEditor.restoreDefaults()
      restoreControlsDefaults()
      savesEditor.restoreDefaults()
      systemEditor.restoreDefaults()
      peripheralsEditor.restoreDefaults()
      clearErrors()
    }
    publishDirtyState()
  }

  internal fun isDirty(): Boolean {
    requireEdt()
    return draftFingerprint() != openingFingerprint
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
          categories.selectedCategory = PreferencesCategory.DISPLAY
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
          categories.selectedCategory = PreferencesCategory.AUDIO
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
          categories.selectedCategory = PreferencesCategory.CONTROLS
          controlsSubpages.selectedIndex = KEYBOARD_CONTROLS_PAGE
          throw PreferencesValidationException(validationSummary.text, keyboardEditor)
        }
    val gamepad =
        try {
          gamepadEditor.validatedDraft()
        } catch (failure: PreferenceEditorValidationException) {
          validationSummary.text = failure.message ?: "Resolve the gamepad settings error."
          categories.selectedCategory = PreferencesCategory.CONTROLS
          controlsPlayerSelector.selectedIndex = gamepadEditor.selectedPlayer
          controlsSubpages.selectedIndex = GAMEPAD_CONTROLS_PAGE
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
          categories.selectedCategory = PreferencesCategory.SAVES_AND_REWIND
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
        autofireKeyboard = input.autofireKeyboard,
        gamepads = gamepad.selections,
        gamepadTunings = gamepad.tunings,
        cameraDeviceIndex = peripheralsEditor.validatedPeripherals().cameraDeviceIndex,
        audio = audio,
        desktop =
            initialSettings.desktop.copy(
                appearance = (appearance.selectedItem as AppearanceOption).appearance,
                commandBarVisible = commandBarVisible.isSelected,
            ),
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
    categories.selectedCategory = PreferencesCategory.SAVES_AND_REWIND
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

    val appearanceLabel = JLabel("Appearance:")
    appearanceLabel.displayedMnemonic = KeyEvent.VK_A
    appearanceLabel.labelFor = appearance
    addRow(panel, constraints, 0, appearanceLabel, appearance)

    constraints.gridx = 1
    constraints.gridy = 1
    constraints.gridwidth = 1
    constraints.weightx = 1.0
    constraints.fill = GridBagConstraints.HORIZONTAL
    panel.add(commandBarVisible, constraints)

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
    addRow(panel, constraints, 2, directoryLabel, directoryRow)

    directoryError.foreground = desktopValidationErrorColor()
    directoryError.accessibleContext.accessibleName = "Default ROM directory error"
    constraints.gridx = 1
    constraints.gridy = 3
    constraints.weightx = 1.0
    panel.add(directoryError, constraints)

    val recentLabel = JLabel("Recent files to keep:")
    recentLabel.displayedMnemonic = KeyEvent.VK_R
    recentLabel.labelFor = recentCapacity
    recentCapacity.accessibleContext.accessibleName = "Recent files to keep"
    recentCapacity.toolTipText =
        "Choose between ${ApplicationSettings.MIN_RECENT_FILE_CAPACITY} and " +
            "${ApplicationSettings.MAX_RECENT_FILE_CAPACITY}."
    addRow(panel, constraints, 4, recentLabel, recentCapacity)

    recentCapacityError.foreground = desktopValidationErrorColor()
    recentCapacityError.accessibleContext.accessibleName = "Recent-file capacity error"
    constraints.gridx = 1
    constraints.gridy = 5
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
    addRow(panel, constraints, 6, confirmationLabel, confirmationPolicy)

    constraints.gridx = 0
    constraints.gridy = 7
    constraints.gridwidth = 2
    constraints.weighty = 1.0
    constraints.fill = GridBagConstraints.BOTH
    panel.add(JPanel(), constraints)
    return panel
  }

  private fun createControlsPanel(): JPanel {
    val panel = JPanel(BorderLayout(0, 10))
    panel.accessibleContext.accessibleName = "Keyboard and gamepad controls"
    val playerLabel = JLabel("Player:")
    playerLabel.labelFor = controlsPlayerSelector
    playerLabel.displayedMnemonic = KeyEvent.VK_P
    val playerRow =
        JPanel(GridBagLayout()).apply {
          val constraints =
              GridBagConstraints().apply {
                anchor = GridBagConstraints.LINE_START
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(4, 4, 4, 8)
              }
          add(playerLabel, constraints)
          constraints.gridx = 1
          constraints.weightx = 1.0
          add(controlsPlayerSelector, constraints)
        }
    playerRow.accessibleContext.accessibleName = "Selected controls player"

    controlsSubpages.addTab("Keyboard", keyboardEditor)
    controlsSubpages.setToolTipTextAt(
        KEYBOARD_CONTROLS_PAGE,
        "Capture keyboard bindings for the selected player.",
    )
    controlsSubpages.addTab("Gamepad", gamepadEditor)
    controlsSubpages.setToolTipTextAt(
        GAMEPAD_CONTROLS_PAGE,
        "Assign a controller and optionally tune connected devices.",
    )
    controlsSubpages.addChangeListener {
      if (controlsSubpages.selectedIndex != KEYBOARD_CONTROLS_PAGE) {
        keyboardEditor.cancelCapture()
      }
    }

    panel.add(playerRow, BorderLayout.NORTH)
    panel.add(controlsSubpages, BorderLayout.CENTER)
    panel.add(controlsRuntimeGuidance, BorderLayout.SOUTH)
    return panel
  }

  private fun categoryPage(title: String, content: Component): JScrollPane {
    val page =
        JPanel(BorderLayout(0, 8)).apply {
          border = BorderFactory.createEmptyBorder(4, 8, 8, 8)
          add(sectionLabel(title, pageTitle = true), BorderLayout.NORTH)
          add(content, BorderLayout.CENTER)
        }
    return JScrollPane(page).apply {
      border = null
      horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
      verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
      verticalScrollBar.unitIncrement = 16
      getAccessibleContext().accessibleName = "$title preferences page"
    }
  }

  private fun sectionLabel(text: String, pageTitle: Boolean = false): JLabel =
      JLabel(text).apply {
        font =
            font.deriveFont(
                font.style or java.awt.Font.BOLD,
                font.size2D + if (pageTitle) 3f else 1f,
            )
        styledSectionLabels += this to pageTitle
      }

  private fun restoreGeneralDefaults() {
    directoryField.text = defaults.general.romDirectory?.toString().orEmpty()
    recentCapacity.value = defaults.general.recentFileCapacity
    confirmationPolicy.selectedItem =
        CONFIRMATION_OPTIONS.first {
          it.policy == defaults.general.romChangeConfirmationPolicy
        }
    appearance.selectedItem =
        APPEARANCE_OPTIONS.first { it.appearance == defaults.desktop.appearance }
    commandBarVisible.isSelected = defaults.desktop.commandBarVisible
  }

  private fun restoreControlsDefaults() {
    keyboardEditor.resetToDefaults()
    gamepadEditor.restoreDefaults()
    trackedGamepadTunings = defaults.input.gamepadTunings.toMutableMap()
    explicitlyTrackedGamepadTunings = defaults.input.gamepadTunings.keys.toMutableSet()
  }

  private fun installDirtyTracking(component: Component) {
    when (component) {
      is JTextComponent ->
          component.document.addDocumentListener(
              object : DocumentListener {
                override fun insertUpdate(event: DocumentEvent) = publishDirtyState()

                override fun removeUpdate(event: DocumentEvent) = publishDirtyState()

                override fun changedUpdate(event: DocumentEvent) = publishDirtyState()
              })
      is JComboBox<*> -> {
        component.addItemListener { publishDirtyState() }
        component.addActionListener { queueDirtyStatePublication() }
      }
      is JSpinner -> component.addChangeListener { publishDirtyState() }
      is JSlider -> component.addChangeListener { publishDirtyState() }
      is AbstractButton -> {
        component.addItemListener { publishDirtyState() }
        component.addActionListener { queueDirtyStatePublication() }
      }
      is JLabel -> {
        if (component.accessibleContext.accessibleName.orEmpty()
            .startsWith("Current binding for ")) {
          component.addPropertyChangeListener("text") { publishDirtyState() }
        }
      }
    }
    if (component is Container) {
      component.components.forEach(::installDirtyTracking)
    }
  }

  private fun installGamepadTuningTracking() {
    val update = {
      if (!suppressDirtyNotifications) {
        captureTrackedGamepadTuning()
        publishDirtyState()
      }
    }
    gamepadEditor.movementDeadZone.addChangeListener { update() }
    gamepadEditor.tiltDeadZone.addChangeListener { update() }
    listOf(
            gamepadEditor.invertMovementX,
            gamepadEditor.invertMovementY,
            gamepadEditor.invertTiltX,
            gamepadEditor.invertTiltY,
        )
        .forEach { it.addItemListener { update() } }
  }

  private fun captureTrackedGamepadTuning() {
    val stableId =
        (gamepadEditor.tuningDevice.selectedItem as? GamepadPreferencesEditor.DeviceOption)
            ?.stableId ?: return
    val tuning =
        ApplicationSettings.GamepadTuning(
            movementDeadZone = (gamepadEditor.movementDeadZone.value as Number).toInt(),
            tiltDeadZone = (gamepadEditor.tiltDeadZone.value as Number).toInt(),
            invertMovementX = gamepadEditor.invertMovementX.isSelected,
            invertMovementY = gamepadEditor.invertMovementY.isSelected,
            invertTiltX = gamepadEditor.invertTiltX.isSelected,
            invertTiltY = gamepadEditor.invertTiltY.isSelected,
        )
    if (stableId in explicitlyTrackedGamepadTunings ||
        tuning != ApplicationSettings.GamepadTuning()) {
      trackedGamepadTunings[stableId] = tuning
    } else {
      trackedGamepadTunings.remove(stableId)
    }
  }

  private fun draftFingerprint(): PreferencesDraftFingerprint {
    val keyboard =
        buildMap {
          repeat(4) { player ->
            eu.rekawek.coffeegb.core.joypad.Button.values().forEach { button ->
              put(
                  ControllerProperties.PlayerButton(player, button),
                  keyboardEditor.currentBinding(player, button),
              )
            }
          }
        }
    val autofireKeyboard =
        buildMap {
          repeat(4) { player ->
            listOf(
                    eu.rekawek.coffeegb.core.joypad.Button.A,
                    eu.rekawek.coffeegb.core.joypad.Button.B,
                )
                .forEach { button ->
                  put(
                      ControllerProperties.PlayerAutofireButton(player, button),
                      keyboardEditor.currentAutofireBinding(player, button),
                  )
                }
          }
        }
    return PreferencesDraftFingerprint(
        listOf(
            directoryField.text,
            spinnerText(recentCapacity),
            (confirmationPolicy.selectedItem as? ConfirmationOption)?.policy,
            (appearance.selectedItem as? AppearanceOption)?.appearance,
            commandBarVisible.isSelected,
            displayEditor.explicitScale.selectedItem,
            displayEditor.windowScaleCommandRequested,
            displayEditor.letterboxColor.text,
            displayEditor.fullscreen.isSelected,
            displayEditor.rotation.selectedItem,
            displayEditor.grayscale.isSelected,
            displayEditor.blending.isSelected,
            displayEditor.colorCorrection.isSelected,
            displayEditor.showSgbBorder.isSelected,
            (audioEditor.output.selectedItem as? AudioPreferencesEditor.OutputOption)?.stableId,
            audioEditor.muted.isSelected,
            audioEditor.volume.value,
            audioEditor.latency.selectedItem,
            keyboard,
            autofireKeyboard,
            (0 until CONTROL_PLAYER_COUNT).map(gamepadEditor::selectionForPlayer),
            trackedGamepadTunings.toMap(),
            spinnerText(gamepadEditor.movementDeadZone),
            spinnerText(gamepadEditor.tiltDeadZone),
            savesEditor.directoryField.text,
            savesEditor.batterySaves.isSelected,
            savesEditor.rewindEnabled.isSelected,
            spinnerText(savesEditor.rewindSeconds),
            spinnerText(savesEditor.rewindMemory),
            savesEditor.resume.selectedItem,
            systemEditor.dmgGamesProfile.selectedItem,
            systemEditor.cgbGamesProfile.selectedItem,
            systemEditor.bootstrapMode.selectedItem,
            peripheralsEditor.cameraDevice.selectedItem,
        ))
  }

  private fun spinnerText(spinner: JSpinner): String =
      (spinner.editor as? JSpinner.DefaultEditor)?.textField?.text ?: spinner.value.toString()

  private fun publishDirtyState() {
    if (suppressDirtyNotifications || !::openingFingerprint.isInitialized) return
    val dirty = isDirty()
    if (dirty != lastPublishedDirty) {
      lastPublishedDirty = dirty
      draftChanged(dirty)
    }
  }

  private fun queueDirtyStatePublication() {
    SwingUtilities.invokeLater(::publishDirtyState)
  }

  private fun withSuppressedDirtyNotifications(action: () -> Unit) {
    suppressDirtyNotifications = true
    try {
      action()
    } finally {
      suppressDirtyNotifications = false
    }
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
    categories.selectedCategory = PreferencesCategory.GENERAL
    throw PreferencesValidationException(message, directoryField)
  }

  private fun failRecentCapacity(message: String): Nothing {
    recentCapacityError.text = message
    validationSummary.text = message
    categories.selectedCategory = PreferencesCategory.GENERAL
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

  internal data class AppearanceOption(
      val appearance: ApplicationSettings.Appearance,
      val label: String,
  ) {
    override fun toString(): String = label
  }

  internal data class ControlPlayerOption(val player: Int) {
    init {
      require(player in 0 until CONTROL_PLAYER_COUNT)
    }

    override fun toString(): String = "Player ${player + 1}"
  }

  private companion object {
    const val CONTROL_PLAYER_COUNT = 4
    const val KEYBOARD_CONTROLS_PAGE = 0
    const val GAMEPAD_CONTROLS_PAGE = 1
    val CONTROL_PLAYER_OPTIONS = List(CONTROL_PLAYER_COUNT, ::ControlPlayerOption)
    val APPEARANCE_OPTIONS =
        listOf(
            AppearanceOption(ApplicationSettings.Appearance.LIGHT, "Light"),
            AppearanceOption(ApplicationSettings.Appearance.DARK, "Dark"),
            AppearanceOption(ApplicationSettings.Appearance.SYSTEM, "System"),
        )
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

private data class PreferencesDraftFingerprint(val values: List<Any?>)

private enum class PreferencesConfirmation {
  PROCEED,
  CANCEL,
}

internal class PreferencesValidationException(
    message: String,
    val invalidComponent: Component,
) : IllegalArgumentException(message)

internal fun requestMobileAdapterConfigurationHandoff(
    isDirty: Boolean,
    confirmDiscard: () -> Boolean,
    stopBackgroundWork: () -> Unit,
    closePreferences: () -> Unit,
    defer: ((() -> Unit) -> Unit) = { SwingUtilities.invokeLater(it) },
    configureMobileAdapter: () -> Unit,
): Boolean {
  if (isDirty && !confirmDiscard()) return false
  stopBackgroundWork()
  closePreferences()
  defer(configureMobileAdapter)
  return true
}

/** Owns only the modal window lifecycle. Persistence and runtime changes belong to [SwingGui]. */
internal object PreferencesDialog {
  fun show(
      owner: Window,
      initial: ApplicationSettings,
      defaults: ApplicationSettings = ApplicationSettings(),
      gamepadCatalog: GamepadCatalog = GamepadCatalog(),
      audioDevices: AudioDeviceProvider =
          AudioDeviceProvider { listOf(AudioDeviceSnapshot.systemDefaultDevice()) },
      persistence: PreferencesPersistencePresentation =
          PreferencesPersistencePresentation.PERSISTENT,
      initialCategory: PreferencesCategory = PreferencesCategory.GENERAL,
      initialBounds: Rectangle? = null,
      mobileAdapterSummary: String = "Offline · networking blocked for this session",
      configureMobileAdapter: () -> Unit = {},
      onCategoryChanged: (PreferencesCategory) -> Unit = {},
      onBoundsChanged: (Rectangle) -> Unit = {},
      onClosed: () -> Unit = {},
      afterCommit: (PreferencesEdit) -> Unit = {},
      applyEdit: (PreferencesEdit) -> Unit,
  ) {
    check(SwingUtilities.isEventDispatchThread()) {
      "Preferences dialog must be opened on the EDT"
    }

    val dialog =
        JDialog(owner, "Preferences — Coffee GB", Dialog.ModalityType.APPLICATION_MODAL)
    val saveButton = JButton(persistence.primaryActionLabel).apply { isEnabled = false }
    val cancelButton = JButton("Cancel")
    val restorePageButton = JButton("Restore page defaults")
    val restoreAllButton = JButton("Restore all Preferences defaults…")
    val draftStatus = JLabel("No unsaved changes")
    var applyingInProgress = false
    lateinit var refreshFooter: () -> Unit
    lateinit var panel: PreferencesPanel
    panel =
        PreferencesPanel(
            initial,
            defaults,
            gamepadSnapshots = GamepadSnapshotProvider(gamepadCatalog::snapshot),
            audioDevices = audioDevices,
            persistence = persistence,
            initialCategory = initialCategory,
            categoryChanged = onCategoryChanged,
            draftChanged = { refreshFooter() },
            mobileAdapterSummary = mobileAdapterSummary,
            configureMobileAdapter = {
              requestMobileAdapterConfigurationHandoff(
                  isDirty = panel.isDirty(),
                  confirmDiscard = { confirmDiscardChanges(dialog) },
                  stopBackgroundWork = panel::stopBackgroundWork,
                  closePreferences = dialog::dispose,
                  configureMobileAdapter = configureMobileAdapter,
              )
            },
        )

    refreshFooter = {
      val dirty = panel.isDirty()
      saveButton.isEnabled = dirty && !applyingInProgress
      restorePageButton.isEnabled = !applyingInProgress
      restoreAllButton.isEnabled = !applyingInProgress
      cancelButton.isEnabled = !applyingInProgress
      draftStatus.text = if (dirty) "Unsaved changes" else "No unsaved changes"
      draftStatus.accessibleContext.accessibleDescription = draftStatus.text
    }

    val actions =
        PreferencesDialogActions(
            panel,
            applyEdit,
            dialog::dispose,
            afterCommit = afterCommit,
            confirmDiscard = { confirmDiscardChanges(dialog) },
            confirmRestoreAll = { confirmRestoreAllDefaults(dialog) },
            applyingChanged = { isApplying ->
              applyingInProgress = isApplying
              refreshFooter()
            },
        )

    saveButton.accessibleContext.accessibleName = persistence.primaryActionLabel
    cancelButton.accessibleContext.accessibleName = "Cancel preferences"
    restorePageButton.accessibleContext.accessibleName = "Restore current page defaults"
    restoreAllButton.accessibleContext.accessibleName = "Restore all Preferences defaults"
    draftStatus.accessibleContext.accessibleName = "Preferences draft status"
    saveButton.addActionListener { actions.apply() }
    cancelButton.addActionListener { actions.cancel() }
    restorePageButton.addActionListener { actions.restorePageDefaults() }
    restoreAllButton.addActionListener { actions.restoreAllDefaults() }

    val restoreButtons =
        JPanel(FlowLayout(FlowLayout.LEADING, 8, 0)).apply {
          add(restorePageButton)
          add(restoreAllButton)
        }
    val outcomeButtons =
        JPanel(FlowLayout(FlowLayout.TRAILING, 8, 0)).apply {
          add(cancelButton)
          add(saveButton)
        }
    val footer =
        JPanel(BorderLayout(12, 0)).apply {
          border = BorderFactory.createEmptyBorder(8, 12, 12, 12)
          add(restoreButtons, BorderLayout.LINE_START)
          add(draftStatus, BorderLayout.CENTER)
          add(outcomeButtons, BorderLayout.LINE_END)
        }

    dialog.contentPane.layout = BorderLayout()
    dialog.contentPane.add(panel, BorderLayout.CENTER)
    dialog.contentPane.add(footer, BorderLayout.SOUTH)
    dialog.defaultCloseOperation = JDialog.DO_NOTHING_ON_CLOSE
    dialog.addWindowListener(
        object : WindowAdapter() {
          override fun windowClosing(event: WindowEvent) = actions.cancel()

          override fun windowClosed(event: WindowEvent) = onClosed()
        })
    configurePreferencesRootPane(dialog.rootPane, saveButton, actions::cancel)
    refreshFooter()
    dialog.pack()
    val packed = dialog.size
    dialog.minimumSize = Dimension(720, 500)
    dialog.size =
        Dimension(
            packed.width.coerceIn(dialog.minimumSize.width, 960),
            packed.height.coerceIn(dialog.minimumSize.height, 760),
        )
    if (initialBounds == null) {
      dialog.setLocationRelativeTo(owner)
    } else {
      dialog.bounds = Rectangle(initialBounds)
    }
    dialog.addComponentListener(
        object : ComponentAdapter() {
          override fun componentMoved(event: ComponentEvent) = publishBounds()

          override fun componentResized(event: ComponentEvent) = publishBounds()

          private fun publishBounds() = onBoundsChanged(Rectangle(dialog.bounds))
        })
    dialog.isVisible = true
  }

  private fun confirmDiscardChanges(parent: Window): Boolean =
      DesktopDialogFactory().showDecision(
          parent,
          DesktopDecisionSpec(
              title = "Discard Preferences changes",
              heading = "Discard your unsaved Preferences changes?",
              message = "Every change made since Preferences opened will be lost.",
              buttons =
                  DesktopDialogButtons(
                      primary =
                          DesktopDialogAction(
                              "Discard changes",
                              PreferencesConfirmation.PROCEED,
                              destructive = true,
                          ),
                      cancel =
                          DesktopDialogAction(
                              "Keep editing",
                              PreferencesConfirmation.CANCEL,
                          ),
                      defaultButton = DesktopDialogDefaultButton.CANCEL,
                  ),
              modality = DesktopOwnedDialogModality.DOCUMENT,
          )) == PreferencesConfirmation.PROCEED

  private fun confirmRestoreAllDefaults(parent: Window): Boolean =
      DesktopDialogFactory().showDecision(
          parent,
          DesktopDecisionSpec(
              title = "Restore all Preferences defaults",
              heading = "Restore defaults on every Preferences page?",
              message = "The draft changes now; nothing is saved until you choose Save changes.",
              buttons =
                  DesktopDialogButtons(
                      primary =
                          DesktopDialogAction(
                              "Restore all defaults",
                              PreferencesConfirmation.PROCEED,
                          ),
                      cancel =
                          DesktopDialogAction("Cancel", PreferencesConfirmation.CANCEL),
                      defaultButton = DesktopDialogDefaultButton.CANCEL,
                  ),
              modality = DesktopOwnedDialogModality.DOCUMENT,
          )) == PreferencesConfirmation.PROCEED
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
    private val afterCommit: (PreferencesEdit) -> Unit = {},
    private val confirmDiscard: () -> Boolean = { true },
    private val confirmRestoreAll: () -> Boolean = { true },
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
    afterCommit(edit)
  }

  fun cancel() {
    if (closed) return
    if (panel.isDirty() && !confirmDiscard()) return
    closed = true
    validationGeneration++
    validationPending = false
    applyingChanged(false)
    panel.stopBackgroundWork()
    closeValidationExecutor()
    close()
  }

  fun restorePageDefaults() {
    if (!validationPending && !closed) {
      panel.restoreSelectedPageDefaults()
    }
  }

  fun restoreAllDefaults() {
    if (!validationPending && !closed && confirmRestoreAll()) {
      panel.restoreAllDefaults()
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
