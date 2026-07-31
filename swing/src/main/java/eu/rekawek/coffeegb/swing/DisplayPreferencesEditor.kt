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
) : JPanel(GridBagLayout()), DesktopThemeRefreshHook {
  constructor(
      initial: ApplicationSettings.Display,
      defaults: ApplicationSettings.Display = ApplicationSettings.Display(),
  ) : this(initial, defaults, requireEdt())

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

  private val initialScalingMode = initial.scalingMode
  private val initialExplicitScale = initial.explicitScale

  internal val explicitScale =
      JComboBox(SCALES.toTypedArray()).apply {
        selectedItem = scaleOption(initial.explicitScale)
        getAccessibleContext().accessibleName = "Window scale"
        getAccessibleContext().accessibleDescription =
            "Resize the window to one, two, or four times. The picture still fits the window when resized."
      }
  internal var windowScaleCommandRequested = false
    private set

  internal val letterboxColor =
      JTextField(formatColor(initial.letterboxColor), 8).apply {
        getAccessibleContext().accessibleName = "Letterbox color"
        getAccessibleContext().accessibleDescription =
            "Enter a six-digit RGB color in #RRGGBB form."
      }
  internal val letterboxPreview =
      JPanel().apply {
        background = Color(initial.letterboxColor)
        isOpaque = true
        preferredSize = Dimension(48, letterboxColor.preferredSize.height)
        minimumSize = Dimension(32, letterboxColor.minimumSize.height)
        border = BorderFactory.createLineBorder(Color.GRAY)
        getAccessibleContext().accessibleName = "Letterbox color preview"
        getAccessibleContext().accessibleDescription = formatColor(initial.letterboxColor)
      }
  internal val letterboxColorError =
      JLabel(" ").apply {
        foreground = desktopValidationErrorColor()
        getAccessibleContext().accessibleName = "Letterbox color error"
      }

  override fun desktopThemeChanged(tokens: DesktopThemeTokens) {
    letterboxColorError.foreground = tokens.danger
  }
  internal val fullscreen =
      JCheckBox("Fullscreen", initial.fullscreen).apply {
        mnemonic = KeyEvent.VK_F
        getAccessibleContext().accessibleName = "Fullscreen"
      }
  internal val rotation =
      JComboBox(ROTATIONS.toTypedArray()).apply {
        selectedItem = ROTATIONS.first { it.rotation == initial.rotation }
        getAccessibleContext().accessibleName = "Display rotation"
      }
  internal val grayscale =
      JCheckBox("Use grayscale palette", initial.grayscale).apply {
        mnemonic = KeyEvent.VK_G
        getAccessibleContext().accessibleName = "Use grayscale palette"
      }
  internal val blending =
      JCheckBox("Blend adjacent frames", initial.blending).apply {
        mnemonic = KeyEvent.VK_B
        getAccessibleContext().accessibleName = "Blend adjacent frames"
      }
  internal val colorCorrection =
      JCheckBox("Apply CGB color correction", initial.colorCorrection).apply {
        mnemonic = KeyEvent.VK_C
        getAccessibleContext().accessibleName = "Apply CGB color correction"
      }
  internal val showSgbBorder =
      JCheckBox("Show Super Game Boy border", initial.showSgbBorder).apply {
        mnemonic = KeyEvent.VK_O
        getAccessibleContext().accessibleName = "Show Super Game Boy border"
      }

  init {
    getAccessibleContext().accessibleName = "Display preferences"
    getAccessibleContext().accessibleDescription =
        "Configure window size, letterboxing, fullscreen, rotation, and display filters."
    border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    explicitScale.addActionListener { windowScaleCommandRequested = true }
    createRows()

    letterboxColor.document.addDocumentListener(
        object : DocumentListener {
          override fun insertUpdate(event: DocumentEvent) = updateColorPreview()

          override fun removeUpdate(event: DocumentEvent) = updateColorPreview()

          override fun changedUpdate(event: DocumentEvent) = updateColorPreview()
        })
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
    val scaleCommand = windowScaleCommandRequested
    return ApplicationSettings.Display(
        // The scale is a window-size command, not a rendering cap. The viewport always preserves
        // aspect ratio and fits whatever size the user gives the resizable window. Preserve legacy
        // persisted values until the user explicitly chooses one of the available commands.
        scalingMode =
            if (scaleCommand) ApplicationSettings.DisplayScalingMode.EXPLICIT
            else initialScalingMode,
        explicitScale =
            if (scaleCommand) (explicitScale.selectedItem as ScaleOption).scale
            else initialExplicitScale,
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
    explicitScale.selectedItem = scaleOption(defaults.explicitScale)
    windowScaleCommandRequested = true
    letterboxColor.text = formatColor(defaults.letterboxColor)
    fullscreen.isSelected = defaults.fullscreen
    rotation.selectedItem = ROTATIONS.first { it.rotation == defaults.rotation }
    grayscale.isSelected = defaults.grayscale
    blending.isSelected = defaults.blending
    colorCorrection.isSelected = defaults.colorCorrection
    showSgbBorder.isSelected = defaults.showSgbBorder
    letterboxColorError.text = " "
    updateColorPreview()
  }

  private fun createRows() {
    val constraints =
        GridBagConstraints().apply {
          anchor = GridBagConstraints.LINE_START
          fill = GridBagConstraints.HORIZONTAL
          insets = Insets(4, 4, 4, 4)
        }

    val explicitScaleLabel = JLabel("Window scale:")
    explicitScaleLabel.displayedMnemonic = KeyEvent.VK_W
    explicitScaleLabel.labelFor = explicitScale
    addRow(constraints, 0, explicitScaleLabel, explicitScale)

    val letterboxLabel = JLabel("Letterbox color:")
    letterboxLabel.displayedMnemonic = KeyEvent.VK_L
    letterboxLabel.labelFor = letterboxColor
    val colorRow = JPanel(BorderLayout(8, 0))
    colorRow.add(letterboxColor, BorderLayout.CENTER)
    colorRow.add(letterboxPreview, BorderLayout.LINE_END)
    addRow(constraints, 1, letterboxLabel, colorRow)

    constraints.gridx = 1
    constraints.gridy = 2
    constraints.weightx = 1.0
    add(letterboxColorError, constraints)

    constraints.gridx = 1
    constraints.gridy = 3
    add(fullscreen, constraints)

    val rotationLabel = JLabel("Rotation:")
    rotationLabel.displayedMnemonic = KeyEvent.VK_R
    rotationLabel.labelFor = rotation
    addRow(constraints, 4, rotationLabel, rotation)

    constraints.gridx = 1
    constraints.gridy = 5
    add(grayscale, constraints)
    constraints.gridy = 6
    add(blending, constraints)
    constraints.gridy = 7
    add(colorCorrection, constraints)
    constraints.gridy = 8
    add(showSgbBorder, constraints)

    constraints.gridx = 0
    constraints.gridy = 9
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
    val SCALES = listOf(1, 2, 4).map(::ScaleOption)
    val ROTATIONS = ApplicationSettings.Rotation.entries.map(::RotationOption)

    fun scaleOption(scale: Int): ScaleOption =
        SCALES.minBy { kotlin.math.abs(it.scale - scale) }

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
