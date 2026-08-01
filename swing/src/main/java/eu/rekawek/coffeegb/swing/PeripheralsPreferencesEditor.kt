package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/** Draft-only host-peripheral editor. Opening Preferences never probes or opens camera hardware. */
internal class PeripheralsPreferencesEditor private constructor(
    initial: ApplicationSettings.Peripherals,
    private val defaults: ApplicationSettings.Peripherals,
    mobileAdapterSummary: String,
    private val configureMobileAdapter: () -> Unit,
    @Suppress("UNUSED_PARAMETER") edtGuard: Unit,
) : JPanel(GridBagLayout()) {
  constructor(
      initial: ApplicationSettings.Peripherals,
      defaults: ApplicationSettings.Peripherals = ApplicationSettings.Peripherals(),
      mobileAdapterSummary: String = "Offline · networking blocked for this session",
      configureMobileAdapter: () -> Unit = {},
  ) : this(
      initial,
      defaults,
      mobileAdapterSummary,
      configureMobileAdapter,
      requireEdt(),
  )

  internal data class CameraOption(
      val deviceIndex: Int,
      val label: String,
  ) {
    override fun toString(): String = label
  }

  internal val cameraDevice =
      JComboBox(CAMERA_OPTIONS.toTypedArray()).apply {
        getAccessibleContext().accessibleName = "Game Boy Camera device"
        getAccessibleContext().accessibleDescription = CAMERA_ORDER_EXPLANATION
        toolTipText = CAMERA_ORDER_EXPLANATION
        selectedItem = CAMERA_OPTIONS.first { it.deviceIndex == initial.cameraDeviceIndex }
      }
  internal val mobileAdapterStatus =
      JTextArea(mobileAdapterSummary, 3, 36).apply {
        isEditable = false
        isFocusable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        border = null
        putClientProperty("html.disable", true)
        getAccessibleContext().accessibleName = "Mobile Adapter configuration summary"
        getAccessibleContext().accessibleDescription = text
      }
  internal val configureMobileAdapterButton =
      JButton("Configure Mobile Adapter…").apply {
        mnemonic = KeyEvent.VK_M
        getAccessibleContext().accessibleName = "Configure Mobile Adapter"
        getAccessibleContext().accessibleDescription =
            "Open the retained Mobile Adapter policy and current-session window"
        addActionListener { configureMobileAdapter() }
      }

  init {
    getAccessibleContext().accessibleName = "Peripheral preferences"
    getAccessibleContext().accessibleDescription =
        "Choose host devices used by emulated Game Boy peripherals."
    border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

    val constraints =
        GridBagConstraints().apply {
          anchor = GridBagConstraints.LINE_START
          fill = GridBagConstraints.HORIZONTAL
          insets = Insets(4, 4, 4, 4)
        }
    val cameraLabel = JLabel("Game Boy Camera:")
    cameraLabel.displayedMnemonic = KeyEvent.VK_C
    cameraLabel.labelFor = cameraDevice
    constraints.gridx = 0
    constraints.gridy = 0
    constraints.weightx = 0.0
    add(cameraLabel, constraints)

    constraints.gridx = 1
    constraints.weightx = 1.0
    add(cameraDevice, constraints)

    val explanation = JLabel("<html>$CAMERA_ORDER_EXPLANATION</html>")
    explanation.accessibleContext.accessibleName = "Camera device ordering note"
    constraints.gridx = 0
    constraints.gridy = 1
    constraints.gridwidth = 2
    constraints.weightx = 1.0
    add(explanation, constraints)

    val mobileHeading = JLabel("Mobile Adapter GB:")
    constraints.gridx = 0
    constraints.gridy = 2
    constraints.gridwidth = 2
    constraints.weightx = 1.0
    constraints.weighty = 0.0
    constraints.fill = GridBagConstraints.HORIZONTAL
    constraints.insets = Insets(16, 4, 4, 4)
    add(mobileHeading, constraints)

    constraints.gridy = 3
    constraints.insets = Insets(4, 4, 4, 4)
    add(mobileAdapterStatus, constraints)

    constraints.gridy = 4
    constraints.fill = GridBagConstraints.NONE
    add(configureMobileAdapterButton, constraints)

    constraints.gridy = 5
    constraints.weighty = 1.0
    constraints.fill = GridBagConstraints.BOTH
    add(JPanel(), constraints)
  }

  internal fun validatedPeripherals(): ApplicationSettings.Peripherals {
    requireEdt()
    val selected = cameraDevice.selectedItem as CameraOption
    return ApplicationSettings.Peripherals(cameraDeviceIndex = selected.deviceIndex)
  }

  internal fun restoreDefaults() {
    requireEdt()
    cameraDevice.selectedItem =
        CAMERA_OPTIONS.first { it.deviceIndex == defaults.cameraDeviceIndex }
  }

  private companion object {
    const val CAMERA_ORDER_EXPLANATION =
        "Camera order is supplied by the operating system and can change after reconnecting devices."
    val CAMERA_OPTIONS =
        (ApplicationSettings.MIN_CAMERA_DEVICE_INDEX..
                ApplicationSettings.MAX_CAMERA_DEVICE_INDEX)
            .map { deviceIndex ->
              val number = deviceIndex + 1
              CameraOption(
                  deviceIndex,
                  if (deviceIndex == ApplicationSettings.DEFAULT_CAMERA_DEVICE_INDEX) {
                    "Camera $number (Coffee GB default)"
                  } else {
                    "Camera $number"
                  },
              )
            }

    fun requireEdt() {
      check(SwingUtilities.isEventDispatchThread()) {
        "Peripheral preferences must be constructed and accessed on the EDT"
      }
    }
  }
}
