package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.swing.io.GamepadCatalog
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.text.ParseException
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JSeparator
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.Timer

internal fun interface GamepadSnapshotProvider {
  fun snapshot(): GamepadCatalog.Snapshot
}

internal data class GamepadPreferencesDraft(
    val selections: Map<Int, ApplicationSettings.GamepadSelection>,
    val tunings: Map<String, ApplicationSettings.GamepadTuning>,
)

/** Draft-only gamepad assignment and tuning editor. It never calls SDL or mutates the runtime. */
internal class GamepadPreferencesEditor private constructor(
    initial: ApplicationSettings.Input,
    private val defaults: ApplicationSettings.Input,
    private val snapshots: GamepadSnapshotProvider,
    @Suppress("UNUSED_PARAMETER") edtGuard: Unit,
) : JPanel(BorderLayout(0, 8)) {
  constructor(
      initial: ApplicationSettings.Input,
      defaults: ApplicationSettings.Input = ApplicationSettings.Input.defaults(),
      snapshots: GamepadSnapshotProvider,
  ) : this(initial, defaults, snapshots, requireEdt())

  internal data class SelectionOption(
      val selection: ApplicationSettings.GamepadSelection,
      val label: String,
  ) {
    override fun toString(): String = label
  }

  internal data class DeviceOption(
      val stableId: String,
      val label: String,
  ) {
    override fun toString(): String = label
  }

  private val selections =
      MutableList(PLAYER_COUNT) { player ->
        initial.gamepads[player] ?: ApplicationSettings.GamepadSelection.Disabled
      }
  private val tunings = initial.gamepadTunings.toMutableMap()
  private val explicitlyStoredProfiles = initial.gamepadTunings.keys.toMutableSet()
  private var snapshot =
      GamepadCatalog.Snapshot(GamepadCatalog.Status.STARTING, emptyList(), "")
  private var optionsInitialized = false
  private var updatingControls = false
  private var selectedTuningId: String? =
      initial.gamepadTunings.keys.sorted().firstOrNull()

  internal val playerSelectors: List<JComboBox<SelectionOption>> =
      List(PLAYER_COUNT) { player ->
        JComboBox<SelectionOption>().apply {
          accessibleContext.accessibleName = "Player ${player + 1} gamepad"
          accessibleContext.accessibleDescription =
              "Choose Disabled, automatic assignment, or one specific game controller."
          addActionListener {
            if (!updatingControls) {
              selectedItem?.let {
                selections[player] = (it as SelectionOption).selection
                clearAssignmentErrors()
                refreshOptions()
              }
            }
          }
        }
      }
  internal val playerErrors: List<JLabel> =
      List(PLAYER_COUNT) { player ->
        JLabel(" ").apply {
          foreground = ERROR_COLOR
          accessibleContext.accessibleName = "Player ${player + 1} gamepad error"
        }
      }
  internal val catalogStatus =
      JLabel("Detecting game controllers…").apply {
        accessibleContext.accessibleName = "Gamepad discovery status"
      }
  internal val tuningDevice =
      JComboBox<DeviceOption>().apply {
        accessibleContext.accessibleName = "Gamepad tuning device"
        accessibleContext.accessibleDescription =
            "Choose a stable game controller whose dead zones and axes should be adjusted."
        addActionListener {
          if (!updatingControls) {
            selectedTuningId = (selectedItem as? DeviceOption)?.stableId
            loadTuningControls()
          }
        }
      }
  internal val movementDeadZone =
      JSpinner(
          SpinnerNumberModel(
              ApplicationSettings.DEFAULT_GAMEPAD_MOVEMENT_DEAD_ZONE,
              ApplicationSettings.MIN_GAMEPAD_DEAD_ZONE,
              ApplicationSettings.MAX_GAMEPAD_DEAD_ZONE,
              256,
          ))
  internal val tiltDeadZone =
      JSpinner(
          SpinnerNumberModel(
              ApplicationSettings.DEFAULT_GAMEPAD_TILT_DEAD_ZONE,
              ApplicationSettings.MIN_GAMEPAD_DEAD_ZONE,
              ApplicationSettings.MAX_GAMEPAD_DEAD_ZONE,
              256,
          ))
  internal val invertMovementX = JCheckBox("Invert movement X axis")
  internal val invertMovementY = JCheckBox("Invert movement Y axis")
  internal val invertTiltX = JCheckBox("Invert tilt X axis")
  internal val invertTiltY = JCheckBox("Invert tilt Y axis")
  internal val tuningError =
      JLabel(" ").apply {
        foreground = ERROR_COLOR
        accessibleContext.accessibleName = "Gamepad tuning error"
      }

  private val catalogTimer =
      Timer(CATALOG_REFRESH_MILLIS) { refreshCatalog() }.apply {
        isRepeats = true
        isCoalesce = true
      }

  init {
    getAccessibleContext().accessibleName = "Gamepad preferences"
    getAccessibleContext().accessibleDescription =
        "Assign up to four players and configure per-device dead zones and axis inversion."
    border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    add(createContent(), BorderLayout.CENTER)
    add(catalogStatus, BorderLayout.SOUTH)
    refreshCatalog()
  }

  internal fun validatedDraft(): GamepadPreferencesDraft {
    requireEdt()
    clearAssignmentErrors()
    commitTuningEditors()

    val assignments =
        selections.withIndex().filter {
          it.value != ApplicationSettings.GamepadSelection.Disabled
        }
    val duplicates =
        assignments
            .groupBy { it.value }
            .values
            .filter { matching -> matching.size > 1 }
    if (duplicates.isNotEmpty()) {
      duplicates.forEach { duplicate ->
        val players = duplicate.map { it.index }
        players.forEach { player ->
          val other = players.first { it != player }
          playerErrors[player].text =
              "${selectionLabel(duplicate.first().value)} is already selected for Player ${other + 1}."
        }
      }
      val firstDuplicate = duplicates.first()
      val message =
          "${selectionLabel(firstDuplicate.first().value)} cannot be assigned to multiple players."
      throw PreferenceEditorValidationException(
          message,
          playerSelectors[firstDuplicate.first().index],
      )
    }

    val encodedSelections =
        buildMap {
          selections.forEachIndexed { player, selection ->
            if (player == 0 || selection != ApplicationSettings.GamepadSelection.Disabled) {
              put(player, selection)
            }
          }
        }
    try {
      ApplicationSettings.Input(emptyMap(), encodedSelections, tunings).toPlayerMapping()
    } catch (failure: IllegalArgumentException) {
      tuningError.text = failure.message ?: "The gamepad tuning settings are invalid."
      throw PreferenceEditorValidationException(tuningError.text, tuningDevice)
    }
    return GamepadPreferencesDraft(encodedSelections, tunings.toSortedMap())
  }

  internal fun restoreDefaults() {
    requireEdt()
    selections.indices.forEach { player ->
      selections[player] =
          defaults.gamepads[player] ?: ApplicationSettings.GamepadSelection.Disabled
    }
    tunings.clear()
    tunings.putAll(defaults.gamepadTunings)
    explicitlyStoredProfiles.clear()
    explicitlyStoredProfiles.addAll(defaults.gamepadTunings.keys)
    selectedTuningId = defaults.gamepadTunings.keys.sorted().firstOrNull()
    clearAssignmentErrors()
    tuningError.text = " "
    refreshOptions()
  }

  internal fun refreshCatalog() {
    requireEdt()
    val nextSnapshot = snapshots.snapshot()
    val deviceOptionsChanged =
        !optionsInitialized ||
            snapshot.devices().map { it.stableId() to it.name() } !=
                nextSnapshot.devices().map { it.stableId() to it.name() }
    snapshot = nextSnapshot
    catalogStatus.text =
        when (snapshot.status()) {
          GamepadCatalog.Status.STARTING -> "Detecting game controllers…"
          GamepadCatalog.Status.AVAILABLE ->
              when (snapshot.devices().size) {
                0 -> "No game controllers are currently connected."
                1 -> "1 game controller is connected."
                else -> "${snapshot.devices().size} game controllers are connected."
              }
          GamepadCatalog.Status.UNAVAILABLE ->
              snapshot.message().ifBlank {
                "Game controllers are unavailable. Keyboard input remains available."
              }
          GamepadCatalog.Status.STOPPED -> "Game controller discovery has stopped."
        }
    catalogStatus.accessibleContext.accessibleDescription = catalogStatus.text
    if (deviceOptionsChanged) {
      refreshOptions()
      optionsInitialized = true
    }
  }

  internal fun stopCatalogUpdates() {
    requireEdt()
    catalogTimer.stop()
  }

  internal fun isCatalogTimerRunning(): Boolean = catalogTimer.isRunning

  override fun addNotify() {
    super.addNotify()
    refreshCatalog()
    catalogTimer.start()
  }

  override fun removeNotify() {
    catalogTimer.stop()
    super.removeNotify()
  }

  private fun createContent(): JPanel {
    val panel = JPanel(GridBagLayout())
    val constraints =
        GridBagConstraints().apply {
          anchor = GridBagConstraints.LINE_START
          fill = GridBagConstraints.HORIZONTAL
          insets = Insets(4, 4, 4, 4)
        }

    playerSelectors.forEachIndexed { player, selector ->
      val label = JLabel("Player ${player + 1}:")
      label.labelFor = selector
      label.displayedMnemonic = KeyEventForPlayer[player]
      addRow(panel, constraints, player * 2, label, selector)
      constraints.gridx = 1
      constraints.gridy = player * 2 + 1
      constraints.weightx = 1.0
      panel.add(playerErrors[player], constraints)
    }

    constraints.gridx = 0
    constraints.gridy = PLAYER_COUNT * 2
    constraints.gridwidth = 2
    constraints.weightx = 1.0
    panel.add(JSeparator(), constraints)

    val tuningLabel = JLabel("Tune controller:")
    tuningLabel.labelFor = tuningDevice
    tuningLabel.displayedMnemonic = java.awt.event.KeyEvent.VK_T
    addRow(panel, constraints, PLAYER_COUNT * 2 + 1, tuningLabel, tuningDevice)

    movementDeadZone.accessibleContext.accessibleName = "Movement dead zone"
    movementDeadZone.toolTipText =
        "Raw SDL threshold from ${ApplicationSettings.MIN_GAMEPAD_DEAD_ZONE} to " +
            "${ApplicationSettings.MAX_GAMEPAD_DEAD_ZONE}."
    val movementLabel = JLabel("Movement dead zone:")
    movementLabel.labelFor = movementDeadZone
    movementLabel.displayedMnemonic = java.awt.event.KeyEvent.VK_M
    addRow(panel, constraints, PLAYER_COUNT * 2 + 2, movementLabel, movementDeadZone)

    tiltDeadZone.accessibleContext.accessibleName = "Tilt dead zone"
    tiltDeadZone.toolTipText =
        "Raw SDL threshold from ${ApplicationSettings.MIN_GAMEPAD_DEAD_ZONE} to " +
            "${ApplicationSettings.MAX_GAMEPAD_DEAD_ZONE}."
    val tiltLabel = JLabel("Tilt dead zone:")
    tiltLabel.labelFor = tiltDeadZone
    tiltLabel.displayedMnemonic = java.awt.event.KeyEvent.VK_D
    addRow(panel, constraints, PLAYER_COUNT * 2 + 3, tiltLabel, tiltDeadZone)

    listOf(invertMovementX, invertMovementY, invertTiltX, invertTiltY).forEach {
      it.accessibleContext.accessibleName = it.text
      it.addActionListener { storeTuningFromControls() }
    }
    movementDeadZone.addChangeListener { storeTuningFromControls() }
    tiltDeadZone.addChangeListener { storeTuningFromControls() }

    val inversionPanel =
        JPanel(GridBagLayout()).apply {
          val checkboxConstraints =
              GridBagConstraints().apply {
                anchor = GridBagConstraints.LINE_START
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
              }
          listOf(invertMovementX, invertMovementY, invertTiltX, invertTiltY)
              .forEachIndexed { index, checkbox ->
                checkboxConstraints.gridx = index % 2
                checkboxConstraints.gridy = index / 2
                add(checkbox, checkboxConstraints)
              }
        }
    val inversionLabel = JLabel("Axis inversion:")
    inversionLabel.labelFor = invertMovementX
    addRow(panel, constraints, PLAYER_COUNT * 2 + 4, inversionLabel, inversionPanel)

    constraints.gridx = 1
    constraints.gridy = PLAYER_COUNT * 2 + 5
    constraints.gridwidth = 1
    constraints.weightx = 1.0
    panel.add(tuningError, constraints)

    constraints.gridx = 0
    constraints.gridy = PLAYER_COUNT * 2 + 6
    constraints.gridwidth = 2
    constraints.weighty = 1.0
    constraints.fill = GridBagConstraints.BOTH
    panel.add(JPanel(), constraints)
    return panel
  }

  private fun refreshOptions() {
    val available =
        snapshot.devices().associateBy(GamepadCatalog.Device::stableId)
    val stableIds =
        buildSet {
          addAll(available.keys)
          selections.forEach { selection ->
            if (selection is ApplicationSettings.GamepadSelection.Device) {
              add(selection.stableId)
            }
          }
          addAll(tunings.keys)
          selectedTuningId?.let(::add)
        }.sorted()

    updatingControls = true
    try {
      playerSelectors.forEachIndexed { player, selector ->
        val options =
            buildList {
              add(
                  SelectionOption(
                      ApplicationSettings.GamepadSelection.Disabled,
                      "Disabled",
                  ))
              add(
                  SelectionOption(
                      ApplicationSettings.GamepadSelection.Auto,
                      "Automatic",
                  ))
              stableIds.forEach { stableId ->
                add(
                    SelectionOption(
                        ApplicationSettings.GamepadSelection.Device(stableId),
                        deviceLabel(stableId, available[stableId]),
                    ))
              }
            }
        selector.model = DefaultComboBoxModel(options.toTypedArray())
        selector.selectedItem = options.first { it.selection == selections[player] }
      }

      val deviceOptions =
          stableIds.map { stableId ->
            DeviceOption(stableId, deviceLabel(stableId, available[stableId]))
          }
      tuningDevice.model = DefaultComboBoxModel(deviceOptions.toTypedArray())
      val selected =
          deviceOptions.firstOrNull { it.stableId == selectedTuningId }
              ?: deviceOptions.firstOrNull()
      selectedTuningId = selected?.stableId
      tuningDevice.selectedItem = selected
    } finally {
      updatingControls = false
    }
    loadTuningControls()
  }

  private fun loadTuningControls() {
    val stableId = selectedTuningId
    val enabled = stableId != null
    val tuning =
        stableId?.let(tunings::get) ?: ApplicationSettings.GamepadTuning()
    updatingControls = true
    try {
      movementDeadZone.value = tuning.movementDeadZone
      tiltDeadZone.value = tuning.tiltDeadZone
      invertMovementX.isSelected = tuning.invertMovementX
      invertMovementY.isSelected = tuning.invertMovementY
      invertTiltX.isSelected = tuning.invertTiltX
      invertTiltY.isSelected = tuning.invertTiltY
      listOf(
              movementDeadZone,
              tiltDeadZone,
              invertMovementX,
              invertMovementY,
              invertTiltX,
              invertTiltY,
          )
          .forEach { it.isEnabled = enabled }
      tuningDevice.isEnabled = tuningDevice.itemCount > 0
    } finally {
      updatingControls = false
    }
  }

  private fun storeTuningFromControls() {
    if (updatingControls) return
    val stableId = selectedTuningId ?: return
    tuningError.text = " "
    val tuning =
        ApplicationSettings.GamepadTuning(
            movementDeadZone = (movementDeadZone.value as Number).toInt(),
            tiltDeadZone = (tiltDeadZone.value as Number).toInt(),
            invertMovementX = invertMovementX.isSelected,
            invertMovementY = invertMovementY.isSelected,
            invertTiltX = invertTiltX.isSelected,
            invertTiltY = invertTiltY.isSelected,
        )
    if (stableId in explicitlyStoredProfiles || tuning != ApplicationSettings.GamepadTuning()) {
      tunings[stableId] = tuning
    } else {
      tunings.remove(stableId)
    }
  }

  private fun commitTuningEditors() {
    try {
      movementDeadZone.commitEdit()
    } catch (_: ParseException) {
      tuningError.text =
          "Enter dead zones from ${ApplicationSettings.MIN_GAMEPAD_DEAD_ZONE} to " +
              "${ApplicationSettings.MAX_GAMEPAD_DEAD_ZONE}."
      throw PreferenceEditorValidationException(
          tuningError.text,
          (movementDeadZone.editor as JSpinner.DefaultEditor).textField,
      )
    }
    try {
      tiltDeadZone.commitEdit()
    } catch (_: ParseException) {
      tuningError.text =
          "Enter dead zones from ${ApplicationSettings.MIN_GAMEPAD_DEAD_ZONE} to " +
              "${ApplicationSettings.MAX_GAMEPAD_DEAD_ZONE}."
      throw PreferenceEditorValidationException(
          tuningError.text,
          (tiltDeadZone.editor as JSpinner.DefaultEditor).textField,
      )
    }
    storeTuningFromControls()
  }

  private fun clearAssignmentErrors() {
    playerErrors.forEach { it.text = " " }
  }

  private fun selectionLabel(selection: ApplicationSettings.GamepadSelection): String =
      when (selection) {
        ApplicationSettings.GamepadSelection.Disabled -> "Disabled"
        ApplicationSettings.GamepadSelection.Auto -> "Automatic assignment"
        is ApplicationSettings.GamepadSelection.Device ->
            snapshot
                .devices()
                .firstOrNull { it.stableId() == selection.stableId }
                ?.name()
                ?: "The configured controller"
      }

  private fun deviceLabel(
      stableId: String,
      device: GamepadCatalog.Device?,
  ): String =
      if (device == null) {
        "Unavailable configured controller (${abbreviate(stableId)})"
      } else {
        "${device.name()} (${abbreviate(stableId)})"
      }

  private fun abbreviate(stableId: String): String =
      stableId.take(STABLE_ID_LABEL_PREFIX) + "…" + stableId.takeLast(STABLE_ID_LABEL_SUFFIX)

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

  private companion object {
    const val PLAYER_COUNT = 4
    const val CATALOG_REFRESH_MILLIS = 1_000
    const val STABLE_ID_LABEL_PREFIX = 12
    const val STABLE_ID_LABEL_SUFFIX = 6
    val ERROR_COLOR = java.awt.Color(0xB0, 0x00, 0x20)
    val KeyEventForPlayer =
        listOf(
            java.awt.event.KeyEvent.VK_1,
            java.awt.event.KeyEvent.VK_2,
            java.awt.event.KeyEvent.VK_3,
            java.awt.event.KeyEvent.VK_4,
        )

    fun requireEdt() {
      check(SwingUtilities.isEventDispatchThread()) {
        "Gamepad preferences must be constructed and accessed on the EDT"
      }
    }
  }
}

internal class PreferenceEditorValidationException(
    message: String,
    val invalidComponent: Component,
) : IllegalArgumentException(message)
