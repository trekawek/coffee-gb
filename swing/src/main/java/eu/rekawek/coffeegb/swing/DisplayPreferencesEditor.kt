package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.KeyEvent
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Draft-only display editor. It performs no persistence, device discovery, or runtime mutation. */
internal class DisplayPreferencesEditor private constructor(
    initial: ApplicationSettings.Display,
    private val defaults: ApplicationSettings.Display,
    @Suppress("UNUSED_PARAMETER") edtGuard: Unit,
) : JPanel(GridBagLayout()) {
  constructor(
      initial: ApplicationSettings.Display,
      defaults: ApplicationSettings.Display = ApplicationSettings.Display(),
  ) : this(initial, defaults, requireEdt())

  internal data class ScalingModeOption(
      val mode: ApplicationSettings.DisplayScalingMode,
      val label: String,
  ) {
    override fun toString(): String = label
  }

  internal data class ScaleOption(
      val scale: Int,
  ) {
    override fun toString(): String = "${scale}×"
  }

  internal data class RotationOption(
      val rotation: ApplicationSettings.Rotation,
  ) {
    override fun toString(): String = "${rotation.degrees}°"
  }

  internal val scalingMode =
      JComboBox(SCALING_MODES.toTypedArray()).apply {
        selectedItem = SCALING_MODES.first { it.mode == initial.scalingMode }
        accessibleContext.accessibleName = "Display scaling mode"
        accessibleContext.accessibleDescription =
            "Choose integer fit, aspect fit, or a fixed explicit scale."
      }
  internal val explicitScale =
      JComboBox(SCALES.toTypedArray()).apply {
        selectedItem = SCALES.first { it.scale == initial.explicitScale }
        accessibleContext.accessibleName = "Explicit display scale"
        accessibleContext.accessibleDescription =
            "Choose a fixed display scale from one times through four times."
      }
  internal val letterboxColor =
      JTextField(formatColor(initial.letterboxColor), 8).apply {
        accessibleContext.accessibleName = "Letterbox color"
        accessibleContext.accessibleDescription =
            "Enter a six-digit RGB color in #RRGGBB form."
      }
  internal val letterboxPreview =
      JPanel().apply {
        background = Color(initial.letterboxColor)
        isOpaque = true
        preferredSize = Dimension(48, letterboxColor.preferredSize.height)
        minimumSize = Dimension(32, letterboxColor.minimumSize.height)
        border = BorderFactory.createLineBorder(Color.GRAY)
        accessibleContext.accessibleName = "Letterbox color preview"
        accessibleContext.accessibleDescription = formatColor(initial.letterboxColor)
      }
  internal val letterboxColorError =
      JLabel(" ").apply {
        foreground = ERROR_COLOR
        accessibleContext.accessibleName = "Letterbox color error"
      }
  internal val fullscreen =
      JCheckBox("Fullscreen", initial.fullscreen).apply {
        mnemonic = KeyEvent.VK_F
        accessibleContext.accessibleName = "Fullscreen"
      }
  internal val rotation =
      JComboBox(ROTATIONS.toTypedArray()).apply {
        selectedItem = ROTATIONS.first { it.rotation == initial.rotation }
        accessibleContext.accessibleName = "Display rotation"
      }
  internal val grayscale =
      JCheckBox("Use grayscale palette", initial.grayscale).apply {
        mnemonic = KeyEvent.VK_G
        accessibleContext.accessibleName = "Use grayscale palette"
      }
  internal val blending =
      JCheckBox("Blend adjacent frames", initial.blending).apply {
        mnemonic = KeyEvent.VK_B
        accessibleContext.accessibleName = "Blend adjacent frames"
      }
  internal val colorCorrection =
      JCheckBox("Apply CGB color correction", initial.colorCorrection).apply {
        mnemonic = KeyEvent.VK_C
        accessibleContext.accessibleName = "Apply CGB color correction"
      }
  internal val showSgbBorder =
      JCheckBox("Show Super Game Boy border", initial.showSgbBorder).apply {
        mnemonic = KeyEvent.VK_O
        accessibleContext.accessibleName = "Show Super Game Boy border"
      }

  init {
    getAccessibleContext().accessibleName = "Display preferences"
    getAccessibleContext().accessibleDescription =
        "Configure scaling, letterboxing, fullscreen, rotation, and display filters."
    border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    createRows()

    scalingMode.addActionListener {
      requireEdt()
      updateExplicitScaleEnabled()
    }
    letterboxColor.document.addDocumentListener(
        object : DocumentListener {
          override fun insertUpdate(event: DocumentEvent) = updateColorPreview()

          override fun removeUpdate(event: DocumentEvent) = updateColorPreview()

          override fun changedUpdate(event: DocumentEvent) = updateColorPreview()
        })
    updateExplicitScaleEnabled()
  }

  internal fun validatedDisplay(): ApplicationSettings.Display {
    requireEdt()
    val parsedColor =
        parseColor(letterboxColor.text)
            ?: run {
              letterboxColorError.text = COLOR_ERROR
              throw PreferenceEditorValidationException(COLOR_ERROR, letterboxColor)
            }
    letterboxColorError.text = " "
    return ApplicationSettings.Display(
        scalingMode = (scalingMode.selectedItem as ScalingModeOption).mode,
        explicitScale = (explicitScale.selectedItem as ScaleOption).scale,
        letterboxColor = parsedColor,
        fullscreen = fullscreen.isSelected,
        grayscale = grayscale.isSelected,
        blending = blending.isSelected,
        colorCorrection = colorCorrection.isSelected,
        rotation = (rotation.selectedItem as RotationOption).rotation,
        showSgbBorder = showSgbBorder.isSelected,
    )
  }

  internal fun restoreDefaults() {
    requireEdt()
    scalingMode.selectedItem = SCALING_MODES.first { it.mode == defaults.scalingMode }
    explicitScale.selectedItem = SCALES.first { it.scale == defaults.explicitScale }
    letterboxColor.text = formatColor(defaults.letterboxColor)
    fullscreen.isSelected = defaults.fullscreen
    rotation.selectedItem = ROTATIONS.first { it.rotation == defaults.rotation }
    grayscale.isSelected = defaults.grayscale
    blending.isSelected = defaults.blending
    colorCorrection.isSelected = defaults.colorCorrection
    showSgbBorder.isSelected = defaults.showSgbBorder
    letterboxColorError.text = " "
    updateExplicitScaleEnabled()
    updateColorPreview()
  }

  private fun createRows() {
    val constraints =
        GridBagConstraints().apply {
          anchor = GridBagConstraints.LINE_START
          fill = GridBagConstraints.HORIZONTAL
          insets = Insets(4, 4, 4, 4)
        }

    val scalingLabel = JLabel("Scaling mode:")
    scalingLabel.displayedMnemonic = KeyEvent.VK_S
    scalingLabel.labelFor = scalingMode
    addRow(constraints, 0, scalingLabel, scalingMode)

    val explicitScaleLabel = JLabel("Explicit scale:")
    explicitScaleLabel.displayedMnemonic = KeyEvent.VK_X
    explicitScaleLabel.labelFor = explicitScale
    addRow(constraints, 1, explicitScaleLabel, explicitScale)

    val letterboxLabel = JLabel("Letterbox color:")
    letterboxLabel.displayedMnemonic = KeyEvent.VK_L
    letterboxLabel.labelFor = letterboxColor
    val colorRow = JPanel(BorderLayout(8, 0))
    colorRow.add(letterboxColor, BorderLayout.CENTER)
    colorRow.add(letterboxPreview, BorderLayout.LINE_END)
    addRow(constraints, 2, letterboxLabel, colorRow)

    constraints.gridx = 1
    constraints.gridy = 3
    constraints.weightx = 1.0
    add(letterboxColorError, constraints)

    constraints.gridx = 1
    constraints.gridy = 4
    add(fullscreen, constraints)

    val rotationLabel = JLabel("Rotation:")
    rotationLabel.displayedMnemonic = KeyEvent.VK_R
    rotationLabel.labelFor = rotation
    addRow(constraints, 5, rotationLabel, rotation)

    constraints.gridx = 1
    constraints.gridy = 6
    add(grayscale, constraints)
    constraints.gridy = 7
    add(blending, constraints)
    constraints.gridy = 8
    add(colorCorrection, constraints)
    constraints.gridy = 9
    add(showSgbBorder, constraints)

    constraints.gridx = 0
    constraints.gridy = 10
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

  private fun updateExplicitScaleEnabled() {
    explicitScale.isEnabled =
        (scalingMode.selectedItem as? ScalingModeOption)?.mode ==
            ApplicationSettings.DisplayScalingMode.EXPLICIT
  }

  private fun updateColorPreview() {
    requireEdt()
    val parsed = parseColor(letterboxColor.text)
    if (parsed == null) {
      return
    }
    letterboxColorError.text = " "
    letterboxPreview.background = Color(parsed)
    letterboxPreview.accessibleContext.accessibleDescription = formatColor(parsed)
  }

  private companion object {
    const val COLOR_ERROR = "Enter a color in #RRGGBB form."
    val ERROR_COLOR = Color(0xB0, 0x00, 0x20)
    val SCALING_MODES =
        listOf(
            ScalingModeOption(
                ApplicationSettings.DisplayScalingMode.INTEGER_FIT,
                "Integer fit",
            ),
            ScalingModeOption(
                ApplicationSettings.DisplayScalingMode.ASPECT_FIT,
                "Fit preserving aspect ratio",
            ),
            ScalingModeOption(
                ApplicationSettings.DisplayScalingMode.EXPLICIT,
                "Explicit scale",
            ),
        )
    val SCALES =
        (ApplicationSettings.MIN_EXPLICIT_DISPLAY_SCALE..
                ApplicationSettings.MAX_EXPLICIT_DISPLAY_SCALE)
            .map(::ScaleOption)
    val ROTATIONS = ApplicationSettings.Rotation.entries.map(::RotationOption)

    fun formatColor(rgb: Int): String = "#%06X".format(Locale.ROOT, rgb)

    fun parseColor(value: String): Int? {
      val trimmed = value.trim()
      if (!trimmed.matches(Regex("#[0-9A-Fa-f]{6}"))) {
        return null
      }
      return trimmed.substring(1).toInt(16)
    }

    fun requireEdt() {
      check(SwingUtilities.isEventDispatchThread()) {
        "Display preferences must be constructed and accessed on the EDT"
      }
    }
  }
}
