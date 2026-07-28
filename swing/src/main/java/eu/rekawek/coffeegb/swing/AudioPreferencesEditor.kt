package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import eu.rekawek.coffeegb.swing.io.AudioDeviceSnapshot
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.SwingUtilities
import javax.swing.SwingWorker

internal fun interface AudioDeviceProvider {
  fun load(): List<AudioDeviceSnapshot>
}

/** Draft-only audio editor. Host audio discovery is delegated to a cancellable background worker. */
internal class AudioPreferencesEditor private constructor(
    initial: ApplicationSettings.Audio,
    private val defaults: ApplicationSettings.Audio,
    private val devices: AudioDeviceProvider,
    @Suppress("UNUSED_PARAMETER") edtGuard: Unit,
) : JPanel(GridBagLayout()) {
  constructor(
      initial: ApplicationSettings.Audio,
      defaults: ApplicationSettings.Audio = ApplicationSettings.Audio(),
      devices: AudioDeviceProvider,
  ) : this(initial, defaults, devices, requireEdt())

  internal data class OutputOption(
      val stableId: String,
      val label: String,
      val available: Boolean,
  ) {
    override fun toString(): String = label
  }

  private var selectedOutputId = initial.output.stableId()
  private var knownDevices: List<AudioDeviceSnapshot> = emptyList()
  private var updatingControls = false
  private var generation = 0L
  private var worker: SwingWorker<List<AudioDeviceSnapshot>, Unit>? = null

  internal val output =
      JComboBox<OutputOption>().apply {
        accessibleContext.accessibleName = "Audio output device"
        accessibleContext.accessibleDescription =
            "Choose the system default or one explicit Java Sound output."
        addActionListener {
          if (!updatingControls) {
            selectedOutputId = (selectedItem as? OutputOption)?.stableId
                ?: AudioDeviceSnapshot.SYSTEM_DEFAULT_ID
            updateUnavailableStatus()
          }
        }
      }
  internal val muted =
      JCheckBox("Mute audio", !initial.enabled).apply {
        accessibleContext.accessibleName = "Mute audio"
      }
  internal val volume =
      JSlider(
            ApplicationSettings.MIN_AUDIO_VOLUME,
            ApplicationSettings.MAX_AUDIO_VOLUME,
            initial.volume,
          )
          .apply {
            majorTickSpacing = 25
            minorTickSpacing = 5
            paintTicks = true
            paintLabels = true
            accessibleContext.accessibleName = "Master volume"
            accessibleContext.accessibleDescription =
                "Choose the master volume from ${ApplicationSettings.MIN_AUDIO_VOLUME} to " +
                    "${ApplicationSettings.MAX_AUDIO_VOLUME} percent."
            toolTipText = accessibleContext.accessibleDescription
          }
  internal val latency =
      JComboBox(ApplicationSettings.AudioLatency.entries.toTypedArray()).apply {
        selectedItem = initial.latency
        accessibleContext.accessibleName = "Audio latency preset"
      }
  internal val status =
      JLabel("Audio outputs have not been checked yet.").apply {
        accessibleContext.accessibleName = "Audio output discovery status"
      }
  init {
    getAccessibleContext().accessibleName = "Audio preferences"
    getAccessibleContext().accessibleDescription =
        "Choose an output, mute state, master volume, and latency preset."
    border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    refreshOutputOptions()
    createRows()
  }

  internal fun validatedAudio(): ApplicationSettings.Audio {
    requireEdt()
    val outputSelection =
        if (selectedOutputId == AudioDeviceSnapshot.SYSTEM_DEFAULT_ID) {
          ApplicationSettings.AudioOutputSelection.Default
        } else {
          ApplicationSettings.AudioOutputSelection.Device(selectedOutputId)
        }
    return ApplicationSettings.Audio(
        enabled = !muted.isSelected,
        output = outputSelection,
        volume = volume.value,
        latency = latency.selectedItem as ApplicationSettings.AudioLatency,
    )
  }

  internal fun restoreDefaults() {
    requireEdt()
    selectedOutputId = defaults.output.stableId()
    muted.isSelected = !defaults.enabled
    volume.value = defaults.volume
    latency.selectedItem = defaults.latency
    refreshOutputOptions()
  }

  internal fun startDeviceLoading() {
    requireEdt()
    cancelDeviceLoading()
    val requestGeneration = ++generation
    status.text = "Checking audio outputs…"
    status.accessibleContext.accessibleDescription = status.text
    val nextWorker =
        object : SwingWorker<List<AudioDeviceSnapshot>, Unit>() {
          override fun doInBackground(): List<AudioDeviceSnapshot> = devices.load()

          override fun done() {
            if (
                worker !== this ||
                    requestGeneration != generation ||
                    isCancelled) {
              return
            }
            worker = null
            try {
              knownDevices = get().distinctBy(AudioDeviceSnapshot::stableId)
              refreshOutputOptions()
              updateUnavailableStatus()
            } catch (_: CancellationException) {
              // Cancellation is normal when the dialog is disposed.
            } catch (failure: InterruptedException) {
              Thread.currentThread().interrupt()
              showEnumerationFailure()
            } catch (_: ExecutionException) {
              showEnumerationFailure()
            } catch (_: RuntimeException) {
              showEnumerationFailure()
            }
          }
        }
    worker = nextWorker
    nextWorker.execute()
  }

  internal fun cancelDeviceLoading() {
    requireEdt()
    generation++
    worker?.cancel(true)
    worker = null
  }

  internal fun isDeviceLoading(): Boolean = worker != null

  override fun addNotify() {
    super.addNotify()
    startDeviceLoading()
  }

  override fun removeNotify() {
    cancelDeviceLoading()
    super.removeNotify()
  }

  private fun createRows() {
    val constraints =
        GridBagConstraints().apply {
          anchor = GridBagConstraints.LINE_START
          fill = GridBagConstraints.HORIZONTAL
          insets = Insets(4, 4, 4, 4)
        }

    val outputLabel = JLabel("Output device:")
    outputLabel.labelFor = output
    outputLabel.displayedMnemonic = java.awt.event.KeyEvent.VK_O
    addRow(constraints, 0, outputLabel, output)

    constraints.gridx = 1
    constraints.gridy = 1
    constraints.weightx = 1.0
    add(status, constraints)

    constraints.gridx = 1
    constraints.gridy = 2
    add(muted, constraints)

    val volumeLabel = JLabel("Master volume:")
    volumeLabel.labelFor = volume
    volumeLabel.displayedMnemonic = java.awt.event.KeyEvent.VK_V
    addRow(constraints, 3, volumeLabel, volume)

    val latencyLabel = JLabel("Latency preset:")
    latencyLabel.labelFor = latency
    latencyLabel.displayedMnemonic = java.awt.event.KeyEvent.VK_L
    addRow(constraints, 4, latencyLabel, latency)

    constraints.gridx = 0
    constraints.gridy = 5
    constraints.gridwidth = 2
    constraints.weightx = 1.0
    constraints.weighty = 1.0
    constraints.fill = GridBagConstraints.BOTH
    add(JPanel(), constraints)
  }

  private fun refreshOutputOptions() {
    val byId =
        knownDevices.associateBy(AudioDeviceSnapshot::stableId).toMutableMap().apply {
          putIfAbsent(
              AudioDeviceSnapshot.SYSTEM_DEFAULT_ID,
              AudioDeviceSnapshot.systemDefaultDevice(),
          )
        }
    val options =
        buildList {
          val systemDefault = byId.remove(AudioDeviceSnapshot.SYSTEM_DEFAULT_ID)
          add(
              OutputOption(
                  AudioDeviceSnapshot.SYSTEM_DEFAULT_ID,
                  systemDefault?.displayName() ?: "System Default",
                  true,
              ))
          byId.values.sortedWith(
                  compareBy(
                      { it.displayName().lowercase() },
                      AudioDeviceSnapshot::stableId,
                  ))
              .forEach { device ->
                add(OutputOption(device.stableId(), device.displayName(), true))
              }
          if (
              selectedOutputId != AudioDeviceSnapshot.SYSTEM_DEFAULT_ID &&
                  none { it.stableId == selectedOutputId }) {
            add(
                OutputOption(
                    selectedOutputId,
                    "Unavailable configured output (${abbreviate(selectedOutputId)})",
                    false,
                ))
          }
        }

    updatingControls = true
    try {
      output.model = DefaultComboBoxModel(options.toTypedArray())
      output.selectedItem = options.first { it.stableId == selectedOutputId }
    } finally {
      updatingControls = false
    }
  }

  private fun updateUnavailableStatus() {
    val selected = output.selectedItem as? OutputOption
    status.text =
        when {
          selected != null && !selected.available ->
              "The configured output is unavailable; runtime playback will fall back safely."
          knownDevices.isEmpty() ->
              "No explicit audio outputs were found. System Default remains available."
          else ->
              "${knownDevices.count { !it.systemDefault() }} explicit audio outputs are available."
        }
    status.accessibleContext.accessibleDescription = status.text
  }

  private fun showEnumerationFailure() {
    knownDevices = emptyList()
    refreshOutputOptions()
    status.text = "Audio outputs could not be listed. System Default remains available."
    status.accessibleContext.accessibleDescription = status.text
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

  private fun abbreviate(stableId: String): String =
      stableId.take(STABLE_ID_LABEL_PREFIX) + "…" + stableId.takeLast(STABLE_ID_LABEL_SUFFIX)

  private fun ApplicationSettings.AudioOutputSelection.stableId(): String =
      when (this) {
        ApplicationSettings.AudioOutputSelection.Default ->
            AudioDeviceSnapshot.SYSTEM_DEFAULT_ID
        is ApplicationSettings.AudioOutputSelection.Device -> stableId
      }

  private companion object {
    const val STABLE_ID_LABEL_PREFIX = 18
    const val STABLE_ID_LABEL_SUFFIX = 6
    val ERROR_COLOR = java.awt.Color(0xB0, 0x00, 0x20)

    fun requireEdt() {
      check(SwingUtilities.isEventDispatchThread()) {
        "Audio preferences must be constructed and accessed on the EDT"
      }
    }
  }
}
