package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.KeyEvent
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.text.ParseException
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities

internal fun interface SaveDirectoryChooser {
  fun choose(parent: Component, initial: Path?): Path?
}

/** Draft-only editor for portable state, rewind, autosave, and resume preferences. */
internal class SavesPreferencesEditor private constructor(
    private val initial: ApplicationSettings.Saves,
    private val defaults: ApplicationSettings.Saves,
    private val directoryChooser: SaveDirectoryChooser,
    @Suppress("UNUSED_PARAMETER") edtGuard: Unit,
) : JPanel(GridBagLayout()) {
  constructor(
      initial: ApplicationSettings.Saves,
      defaults: ApplicationSettings.Saves = ApplicationSettings.Saves(),
      directoryChooser: SaveDirectoryChooser = SYSTEM_SAVE_DIRECTORY_CHOOSER,
  ) : this(initial, defaults, directoryChooser, requireSavesEdt())

  internal val directoryField = JTextField(initial.directory?.toString().orEmpty(), 32)
  internal val batterySaves = JCheckBox("Enable battery saves", initial.batterySavesEnabled)
  internal val rewindEnabled = JCheckBox("Enable rewind", initial.rewindEnabled)
  internal val rewindSeconds =
      JSpinner(
          SpinnerNumberModel(
              initial.rewindSeconds,
              ApplicationSettings.MIN_REWIND_SECONDS,
              ApplicationSettings.MAX_REWIND_SECONDS,
              5,
          ))
  internal val rewindMemory =
      JSpinner(
          SpinnerNumberModel(
              initial.rewindMemoryMiB,
              ApplicationSettings.MIN_REWIND_MEMORY_MIB,
              ApplicationSettings.MAX_REWIND_MEMORY_MIB,
              8,
          ))
  internal val autosave =
      JComboBox(AUTOSAVE_OPTIONS.toTypedArray()).apply {
        selectedItem = AUTOSAVE_OPTIONS.first { it.policy == initial.autosavePolicy }
      }
  internal val resume =
      JComboBox(RESUME_OPTIONS.toTypedArray()).apply {
        selectedItem = RESUME_OPTIONS.first { it.policy == initial.resumePolicy }
      }
  internal val directoryError = JLabel(" ")

  init {
    border = BorderFactory.createEmptyBorder(8, 4, 8, 4)
    accessibleContext?.accessibleName = "Saves preferences"
    val constraints =
        GridBagConstraints().apply {
          anchor = GridBagConstraints.LINE_START
          fill = GridBagConstraints.HORIZONTAL
          insets = Insets(4, 4, 4, 4)
        }

    val directoryLabel = JLabel("State and screenshot directory:")
    directoryLabel.displayedMnemonic = KeyEvent.VK_D
    directoryLabel.labelFor = directoryField
    directoryField.accessibleContext.accessibleName = "State and screenshot directory"
    directoryField.toolTipText =
        "Leave blank to keep each ROM's state data in a hidden .coffee-gb directory beside it."
    val browse = JButton("Browse…")
    browse.mnemonic = KeyEvent.VK_B
    browse.accessibleContext.accessibleName = "Browse for state and screenshot directory"
    browse.addActionListener {
      val current = runCatching { parseDirectory(directoryField.text) }.getOrNull()
      directoryChooser.choose(this, current)?.let { directoryField.text = it.toString() }
    }
    val directoryRow = JPanel(BorderLayout(6, 0))
    directoryRow.add(directoryField, BorderLayout.CENTER)
    directoryRow.add(browse, BorderLayout.LINE_END)
    addRow(constraints, 0, directoryLabel, directoryRow)

    directoryError.foreground = java.awt.Color(0xB0, 0x00, 0x20)
    directoryError.accessibleContext.accessibleName = "Save directory error"
    constraints.gridx = 1
    constraints.gridy = 1
    constraints.weightx = 1.0
    add(directoryError, constraints)

    batterySaves.mnemonic = KeyEvent.VK_A
    batterySaves.accessibleContext.accessibleName = "Enable battery saves"
    addFullRow(constraints, 2, batterySaves)

    rewindEnabled.mnemonic = KeyEvent.VK_W
    rewindEnabled.accessibleContext.accessibleName = "Enable rewind"
    rewindEnabled.addActionListener { updateRewindAvailability() }
    addFullRow(constraints, 3, rewindEnabled)

    val durationLabel = JLabel("Rewind duration (seconds):")
    durationLabel.displayedMnemonic = KeyEvent.VK_T
    durationLabel.labelFor = rewindSeconds
    rewindSeconds.accessibleContext.accessibleName = "Rewind duration in seconds"
    addRow(constraints, 4, durationLabel, rewindSeconds)

    val memoryLabel = JLabel("Rewind memory budget (MiB):")
    memoryLabel.displayedMnemonic = KeyEvent.VK_M
    memoryLabel.labelFor = rewindMemory
    rewindMemory.accessibleContext.accessibleName = "Rewind memory budget in MiB"
    addRow(constraints, 5, memoryLabel, rewindMemory)

    val autosaveLabel = JLabel("Autosave:")
    autosaveLabel.displayedMnemonic = KeyEvent.VK_U
    autosaveLabel.labelFor = autosave
    autosave.accessibleContext.accessibleName = "Autosave policy"
    addRow(constraints, 6, autosaveLabel, autosave)

    val resumeLabel = JLabel("Resume autosave:")
    resumeLabel.displayedMnemonic = KeyEvent.VK_R
    resumeLabel.labelFor = resume
    resume.accessibleContext.accessibleName = "Resume autosave policy"
    addRow(constraints, 7, resumeLabel, resume)

    constraints.gridx = 0
    constraints.gridy = 8
    constraints.gridwidth = 2
    constraints.weighty = 1.0
    constraints.fill = GridBagConstraints.BOTH
    add(JPanel(), constraints)
    updateRewindAvailability()
  }

  fun validatedSaves(): ApplicationSettings.Saves {
    requireSavesEdt()
    directoryError.text = " "
    val directory =
        try {
          parseDirectory(directoryField.text)
        } catch (_: InvalidPathException) {
          directoryError.text = "Enter a valid directory path."
          throw PreferenceEditorValidationException(directoryError.text, directoryField)
        }
    val seconds =
        commitInteger(
            rewindSeconds,
            ApplicationSettings.MIN_REWIND_SECONDS,
            ApplicationSettings.MAX_REWIND_SECONDS,
            "Enter a rewind duration from ${ApplicationSettings.MIN_REWIND_SECONDS} to " +
                "${ApplicationSettings.MAX_REWIND_SECONDS} seconds.",
        )
    val memory =
        commitInteger(
            rewindMemory,
            ApplicationSettings.MIN_REWIND_MEMORY_MIB,
            ApplicationSettings.MAX_REWIND_MEMORY_MIB,
            "Enter a rewind memory budget from ${ApplicationSettings.MIN_REWIND_MEMORY_MIB} to " +
                "${ApplicationSettings.MAX_REWIND_MEMORY_MIB} MiB.",
        )
    return ApplicationSettings.Saves(
        directory = directory,
        previousDirectories = previousDirectories(directory),
        batterySavesEnabled = batterySaves.isSelected,
        rewindEnabled = rewindEnabled.isSelected,
        rewindSeconds = seconds,
        autosavePolicy = (autosave.selectedItem as AutosaveOption).policy,
        resumePolicy = (resume.selectedItem as ResumeOption).policy,
        rewindMemoryMiB = memory,
    )
  }

  fun restoreDefaults() {
    requireSavesEdt()
    directoryField.text = defaults.directory?.toString().orEmpty()
    batterySaves.isSelected = defaults.batterySavesEnabled
    rewindEnabled.isSelected = defaults.rewindEnabled
    rewindSeconds.value = defaults.rewindSeconds
    rewindMemory.value = defaults.rewindMemoryMiB
    autosave.selectedItem = AUTOSAVE_OPTIONS.first { it.policy == defaults.autosavePolicy }
    resume.selectedItem = RESUME_OPTIONS.first { it.policy == defaults.resumePolicy }
    directoryError.text = " "
    updateRewindAvailability()
  }

  private fun previousDirectories(directory: Path?): List<Path> {
    if (samePath(directory, initial.directory)) return initial.previousDirectories
    return (listOfNotNull(initial.directory) + initial.previousDirectories)
        .filterNot { samePath(it, directory) }
        .distinctBy { it.toAbsolutePath().normalize() }
        .take(ApplicationSettings.MAX_PREVIOUS_SAVE_DIRECTORIES)
  }

  private fun samePath(first: Path?, second: Path?): Boolean =
      first?.toAbsolutePath()?.normalize() == second?.toAbsolutePath()?.normalize()

  private fun commitInteger(
      spinner: JSpinner,
      minimum: Int,
      maximum: Int,
      message: String,
  ): Int {
    val field = (spinner.editor as JSpinner.DefaultEditor).textField
    try {
      spinner.commitEdit()
    } catch (_: ParseException) {
      throw PreferenceEditorValidationException(message, field)
    } catch (_: IllegalArgumentException) {
      throw PreferenceEditorValidationException(message, field)
    }
    return (spinner.value as Number).toInt().also {
      if (it !in minimum..maximum) {
        throw PreferenceEditorValidationException(message, field)
      }
    }
  }

  private fun updateRewindAvailability() {
    rewindSeconds.isEnabled = rewindEnabled.isSelected
    rewindMemory.isEnabled = rewindEnabled.isSelected
  }

  private fun addRow(
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
    add(label, constraints)
    constraints.gridx = 1
    constraints.weightx = 1.0
    constraints.fill = GridBagConstraints.HORIZONTAL
    add(field, constraints)
  }

  private fun addFullRow(
      constraints: GridBagConstraints,
      row: Int,
      field: Component,
  ) {
    constraints.gridx = 0
    constraints.gridy = row
    constraints.gridwidth = 2
    constraints.weightx = 1.0
    constraints.fill = GridBagConstraints.HORIZONTAL
    add(field, constraints)
  }

  internal data class AutosaveOption(
      val policy: ApplicationSettings.AutosavePolicy,
      val label: String,
  ) {
    override fun toString(): String = label
  }

  internal data class ResumeOption(
      val policy: ApplicationSettings.ResumePolicy,
      val label: String,
  ) {
    override fun toString(): String = label
  }

  private companion object {
    val AUTOSAVE_OPTIONS =
        listOf(
            AutosaveOption(ApplicationSettings.AutosavePolicy.DISABLED, "Disabled"),
            AutosaveOption(
                ApplicationSettings.AutosavePolicy.ON_CLOSE_AND_ROM_SWITCH,
                "On close and ROM switch",
            ),
        )
    val RESUME_OPTIONS =
        listOf(
            ResumeOption(ApplicationSettings.ResumePolicy.NEVER, "Never"),
            ResumeOption(ApplicationSettings.ResumePolicy.ASK, "Ask"),
            ResumeOption(ApplicationSettings.ResumePolicy.ALWAYS, "Always"),
        )

    fun parseDirectory(value: String): Path? =
        value.trim().takeIf(String::isNotEmpty)?.let(Paths::get)
  }
}

internal val SYSTEM_SAVE_DIRECTORY_CHOOSER =
    SaveDirectoryChooser { parent, initial ->
      val chooser =
          RomFileChooser().apply {
            dialogTitle = "Choose state and screenshot directory"
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

private fun requireSavesEdt() {
  check(SwingUtilities.isEventDispatchThread()) {
    "Saves preferences components must be constructed and accessed on the EDT"
  }
}
