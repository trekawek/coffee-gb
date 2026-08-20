package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.core.ExecutionMode
import eu.rekawek.coffeegb.core.Gameboy.BootstrapMode
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Draft-only system editor. It does not persist settings or mutate a running emulator. */
internal class SystemPreferencesEditor private constructor(
    initial: ApplicationSettings.Advanced,
    private val defaults: ApplicationSettings.Advanced,
    @Suppress("UNUSED_PARAMETER") edtGuard: Unit,
) : JPanel(GridBagLayout()) {
  constructor(
      initial: ApplicationSettings.Advanced,
      defaults: ApplicationSettings.Advanced = ApplicationSettings.Advanced(),
  ) : this(initial, defaults, requireEdt())

  internal data class ProfileOption(
      val selection: ApplicationSettings.ProfileSelection,
      val label: String,
  ) {
    override fun toString(): String = label
  }

  internal data class BootstrapOption(
      val mode: BootstrapMode,
      val label: String,
  ) {
    override fun toString(): String = label
  }

  internal data class ExecutionModeOption(
      val mode: ExecutionMode,
      val label: String,
  ) {
    override fun toString(): String = label
  }

  private val datelSlotRom = initial.datelSlotRom
  private val fullChangerCharacter = initial.fullChangerCharacter

  internal val dmgGamesProfile =
      JComboBox(PROFILE_OPTIONS.toTypedArray()).apply {
        selectedItem = profileOption(initial.dmgGamesProfile)
        getAccessibleContext().accessibleName = "DMG game hardware profile"
        getAccessibleContext().accessibleDescription =
            "Choose automatic detection or a hardware profile for DMG-compatible games."
      }

  internal val cgbGamesProfile =
      JComboBox(PROFILE_OPTIONS.toTypedArray()).apply {
        selectedItem = profileOption(initial.cgbGamesProfile)
        getAccessibleContext().accessibleName = "CGB game hardware profile"
        getAccessibleContext().accessibleDescription =
            "Choose automatic detection or a hardware profile for CGB-compatible games."
      }

  internal val bootstrapMode =
      JComboBox(BOOTSTRAP_OPTIONS.toTypedArray()).apply {
        selectedItem = bootstrapOption(initial.bootstrapMode)
        getAccessibleContext().accessibleName = "Bootstrap mode"
        getAccessibleContext().accessibleDescription =
            "Choose whether boot ROM startup is skipped, fast-forwarded, or shown in full."
      }

  internal val executionMode =
      JComboBox(EXECUTION_MODE_OPTIONS.toTypedArray()).apply {
        selectedItem = executionModeOption(initial.executionMode)
        getAccessibleContext().accessibleName = "Execution mode"
        getAccessibleContext().accessibleDescription =
            "Choose Accuracy for the cycle- and dot-accurate reference, or Performance for guarded " +
                "batching that falls back to Accuracy when needed. Changes reload the active session."
      }

  init {
    getAccessibleContext().accessibleName = "System preferences"
    getAccessibleContext().accessibleDescription =
        "Choose hardware profiles for DMG and CGB games and configure boot ROM startup."
    border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    createRows()
  }

  internal fun validatedAdvanced(): ApplicationSettings.Advanced {
    requireEdt()
    return ApplicationSettings.Advanced(
        dmgGamesProfile = (dmgGamesProfile.selectedItem as ProfileOption).selection,
        cgbGamesProfile = (cgbGamesProfile.selectedItem as ProfileOption).selection,
        bootstrapMode = (bootstrapMode.selectedItem as BootstrapOption).mode,
        executionMode = (executionMode.selectedItem as ExecutionModeOption).mode,
        datelSlotRom = datelSlotRom,
        fullChangerCharacter = fullChangerCharacter,
    )
  }

  internal fun restoreDefaults() {
    requireEdt()
    dmgGamesProfile.selectedItem = profileOption(defaults.dmgGamesProfile)
    cgbGamesProfile.selectedItem = profileOption(defaults.cgbGamesProfile)
    bootstrapMode.selectedItem = bootstrapOption(defaults.bootstrapMode)
    executionMode.selectedItem = executionModeOption(defaults.executionMode)
  }

  private fun createRows() {
    val constraints =
        GridBagConstraints().apply {
          anchor = GridBagConstraints.LINE_START
          fill = GridBagConstraints.HORIZONTAL
          insets = Insets(4, 4, 4, 4)
        }

    val dmgLabel = JLabel("DMG games:")
    dmgLabel.displayedMnemonic = KeyEvent.VK_D
    dmgLabel.labelFor = dmgGamesProfile
    addRow(constraints, 0, dmgLabel, dmgGamesProfile)

    val cgbLabel = JLabel("CGB games:")
    cgbLabel.displayedMnemonic = KeyEvent.VK_C
    cgbLabel.labelFor = cgbGamesProfile
    addRow(constraints, 1, cgbLabel, cgbGamesProfile)

    val bootstrapLabel = JLabel("Bootstrap:")
    bootstrapLabel.displayedMnemonic = KeyEvent.VK_B
    bootstrapLabel.labelFor = bootstrapMode
    addRow(constraints, 2, bootstrapLabel, bootstrapMode)

    val executionModeLabel = JLabel("Execution mode:")
    executionModeLabel.displayedMnemonic = KeyEvent.VK_E
    executionModeLabel.labelFor = executionMode
    addRow(constraints, 3, executionModeLabel, executionMode)

    constraints.gridx = 0
    constraints.gridy = 4
    constraints.gridwidth = 2
    constraints.weightx = 1.0
    constraints.weighty = 1.0
    constraints.fill = GridBagConstraints.BOTH
    add(JPanel(), constraints)
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

  private companion object {
    val PROFILE_OPTIONS =
        buildList {
          add(ProfileOption(ApplicationSettings.ProfileSelection.Auto, "Auto (default)"))
          HardwareProfileRegistry.supportedProfiles().forEach { profile ->
            add(
                ProfileOption(
                    ApplicationSettings.ProfileSelection.Explicit(profile),
                    profile.displayName(),
                ))
          }
        }

    val BOOTSTRAP_OPTIONS =
        listOf(
            BootstrapOption(BootstrapMode.SKIP, "Skip"),
            BootstrapOption(BootstrapMode.FAST_FORWARD, "Fast-forward"),
            BootstrapOption(BootstrapMode.NORMAL, "Full"),
        )

    val EXECUTION_MODE_OPTIONS =
        listOf(
            ExecutionModeOption(
                ExecutionMode.ACCURACY,
                "Accuracy (cycle/dot-accurate reference)",
            ),
            ExecutionModeOption(
                ExecutionMode.PERFORMANCE,
                "Performance (guarded batching)",
            ),
        )

    fun profileOption(selection: ApplicationSettings.ProfileSelection): ProfileOption =
        PROFILE_OPTIONS.single { it.selection == selection }

    fun bootstrapOption(mode: BootstrapMode): BootstrapOption =
        BOOTSTRAP_OPTIONS.single { it.mode == mode }

    fun executionModeOption(mode: ExecutionMode): ExecutionModeOption =
        EXECUTION_MODE_OPTIONS.single { it.mode == mode }

    fun requireEdt() {
      check(SwingUtilities.isEventDispatchThread()) {
        "System preferences must be constructed and accessed on the EDT"
      }
    }
  }
}
