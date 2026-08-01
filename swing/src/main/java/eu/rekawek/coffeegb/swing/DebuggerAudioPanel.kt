package eu.rekawek.coffeegb.swing

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.UIManager

/** EDT-only renderer for a payload-free [DebuggerAudioPaneView]. */
internal class DebuggerAudioPanel(
    private val copyToClipboard: (String) -> Unit,
    private val onSetChannelEnabled: (Int, Boolean) -> Unit = { _, _ -> },
) : JPanel(BorderLayout(4, 4)) {
  internal val overviewArea = peripheralTextArea("audio inspection summary")
  internal val tabs = JTabbedPane()
  internal val channelTable = JTable()
  internal val registerTable = JTable()
  internal val waveTable = JTable()
  internal val waveGraph = DebuggerWaveGraph()
  internal val channelToggles =
      (1..CHANNEL_COUNT).map { channel ->
        JCheckBox("CH $channel", true).apply {
          isEnabled = false
          accessibleContext.accessibleName = "Audio channel $channel output"
          accessibleContext.accessibleDescription =
              "Include channel $channel in emulator audio output. " +
                  "This does not change the game's APU registers."
        }
      }

  private val channelMixerEnabled = BooleanArray(CHANNEL_COUNT) { true }
  private var updatingChannelToggles = false

  private val channelModel =
      DebuggerPeripheralTableModel(
          listOf(
              "Channel",
              "Type",
              "Status",
              "DAC",
              "Output",
              "Length",
              "Routing",
              "Decoded details",
              "Description",
          )
      )
  private val registerModel =
      DebuggerPeripheralTableModel(
          listOf("Scope", "Register", "Address", "Raw value", "Decoded fields")
      )
  private val waveModel =
      DebuggerPeripheralTableModel(
          listOf("Sample", "Hex", "Decimal", "Level", "Description")
      )
  private val overviewPane = JScrollPane(overviewArea)
  private val channelPane: Component
  private val registerPane: Component
  private val wavePane: Component
  private val fontScaler: DebuggerPeripheralFontScaler

  init {
    requirePeripheralEdt("Audio debugger panel construction")
    border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
    getAccessibleContext().accessibleName = "Audio debugger pane"
    getAccessibleContext().accessibleDescription = EMPTY_DESCRIPTION
    tabs.accessibleContext.accessibleName = "Audio debugger sections"

    channelTable.model = channelModel
    registerTable.model = registerModel
    waveTable.model = waveModel
    configurePeripheralTable(
        channelTable,
        "Audio channels and routing",
        "Four APU channels with status, DAC, output, length, and textual left-right routing",
        { channelModel.copyText(channelTable.selectedRows) },
        copyToClipboard,
    )
    configurePeripheralTable(
        registerTable,
        "Audio registers",
        "Global and per-channel audio register values with decoded textual fields",
        { registerModel.copyText(registerTable.selectedRows) },
        copyToClipboard,
    )
    configurePeripheralTable(
        waveTable,
        "Wave RAM samples",
        "All 32 wave samples as hexadecimal, decimal, and textual levels",
        { waveModel.copyText(waveTable.selectedRows) },
        copyToClipboard,
    )
    setColumnWidths(channelTable, 60, 120, 90, 80, 125, 220, 105, 360, 700)
    setColumnWidths(registerTable, 90, 80, 85, 80, 700)
    setColumnWidths(waveTable, 65, 55, 65, 80, 300)

    overviewPane.accessibleContext.accessibleName = "Audio inspection overview"
    channelPane =
        JPanel(BorderLayout(2, 2)).apply {
          add(channelControls(), BorderLayout.NORTH)
          add(
              tablePane(
                  "Channel state and mixer routing are always stated in text",
                  channelTable,
              ),
              BorderLayout.CENTER,
          )
        }
    registerPane = tablePane("Raw audio registers with decoded fields", registerTable)
    wavePane =
        JPanel(BorderLayout(2, 4)).apply {
          val label = JLabel("Wave samples are textual below; the graph is supplemental")
          label.accessibleContext.accessibleName = "Waveform representation note"
          add(label, BorderLayout.NORTH)
          add(JScrollPane(waveTable), BorderLayout.CENTER)
          add(waveGraph, BorderLayout.SOUTH)
        }
    tabs.addTab("Overview", overviewPane)
    tabs.addTab("Channels", channelPane)
    tabs.addTab("Registers", registerPane)
    tabs.addTab("Wave RAM", wavePane)
    add(tabs, BorderLayout.CENTER)
    fontScaler = DebuggerPeripheralFontScaler(this)

    channelToggles.forEachIndexed { index, toggle ->
      toggle.addActionListener {
        if (!updatingChannelToggles) {
          channelMixerEnabled[index] = toggle.isSelected
          onSetChannelEnabled(index + 1, toggle.isSelected)
        }
      }
    }
  }

  fun render(view: DebuggerAudioPaneView) {
    requirePeripheralEdt("Audio debugger rendering")
    overviewArea.text = view.overviewText
    overviewArea.caretPosition = 0
    channelModel.replace(
        view.channelRows.map { row ->
          listOf<Any>(
              row.channel,
              row.kind,
              row.enabledText,
              row.dacText,
              row.outputText,
              row.lengthText,
              row.routingText,
              row.detailsText,
              row.accessibilityText,
          )
        }
    )
    registerModel.replace(
        view.registerRows.map { row ->
          listOf<Any>(
              row.scope,
              row.name,
              row.addressText,
              row.rawValueText,
              row.description,
          )
        }
    )
    waveModel.replace(
        view.waveRows.map { row ->
          listOf<Any>(
              row.index,
              row.hexadecimalValue,
              row.decimalValue,
              row.levelText,
              row.accessibilityText,
          )
        }
    )
    waveGraph.render(view.waveRows.map(DebuggerWaveSampleTableRow::decimalValue))
    getAccessibleContext().accessibleDescription = view.accessibilityText
  }

  fun showNotCaptured(identity: DebuggerSnapshotIdentity) {
    requirePeripheralEdt("Audio debugger snapshot transition")
    releaseRows()
    overviewArea.text =
        "Snapshot: ${identity.label}\n" +
            "Audio inspection was not captured for this snapshot; select Audio to refresh."
    overviewArea.caretPosition = 0
    getAccessibleContext().accessibleDescription =
        "${identity.label}. Audio inspection was not captured for this snapshot."
  }

  fun clear() {
    requirePeripheralEdt("Audio debugger clearing")
    overviewArea.text = "No audio inspection loaded"
    overviewArea.caretPosition = 0
    releaseRows()
    getAccessibleContext().accessibleDescription = EMPTY_DESCRIPTION
  }

  fun setChannelControlsEnabled(enabled: Boolean) {
    requirePeripheralEdt("Audio channel control state")
    channelToggles.forEach { it.isEnabled = enabled }
  }

  fun setChannelMixerEnabled(channel: Int, enabled: Boolean) {
    requirePeripheralEdt("Audio channel control update")
    val index = channel - 1
    require(index in channelMixerEnabled.indices) { "Audio channel must be between 1 and 4" }
    channelMixerEnabled[index] = enabled
    updatingChannelToggles = true
    try {
      channelToggles[index].isSelected = enabled
    } finally {
      updatingChannelToggles = false
    }
  }

  fun resetChannelMixer() {
    requirePeripheralEdt("Audio channel control reset")
    channelMixerEnabled.indices.forEach { setChannelMixerEnabled(it + 1, true) }
  }

  private fun releaseRows() {
    channelModel.clear()
    registerModel.clear()
    waveModel.clear()
    waveGraph.clear()
  }

  fun applyFontScale(scalePercent: Int) {
    requirePeripheralEdt("Audio debugger font scaling")
    fontScaler.apply(scalePercent)
    revalidate()
    repaint()
  }

  internal fun resetFontScaleForThemeChange() = fontScaler.resetToBaseline()

  internal fun recaptureFontScaleBaseline() = fontScaler.recapture(this)

  fun copyText(): String {
    requirePeripheralEdt("Audio debugger copying")
    return when (tabs.selectedComponent) {
      overviewPane -> overviewArea.text
      channelPane -> channelModel.copyText(channelTable.selectedRows)
      registerPane -> registerModel.copyText(registerTable.selectedRows)
      wavePane -> waveModel.copyText(waveTable.selectedRows)
      else -> ""
    }
  }

  private fun tablePane(description: String, table: JTable): Component =
      JPanel(BorderLayout(2, 2)).apply {
        val label = JLabel(description)
        label.accessibleContext.accessibleName = "$description description"
        add(label, BorderLayout.NORTH)
        add(JScrollPane(table), BorderLayout.CENTER)
      }

  private fun channelControls(): Component =
      JPanel(FlowLayout(FlowLayout.LEADING, 8, 2)).apply {
        val label = JLabel("Output mixer:")
        label.labelFor = channelToggles.first()
        label.accessibleContext.accessibleName = "Audio output mixer"
        add(label)
        channelToggles.forEach(::add)
      }

  private fun setColumnWidths(table: JTable, vararg widths: Int) {
    widths.forEachIndexed { index, width -> table.columnModel.getColumn(index).preferredWidth = width }
  }

  private companion object {
    const val EMPTY_DESCRIPTION = "Audio inspection is not retained"
    const val CHANNEL_COUNT = 4
  }
}

/** Supplemental bounded visualization; the adjacent table remains the authoritative text view. */
internal class DebuggerWaveGraph : JPanel() {
  private var samples = IntArray(0)

  init {
    preferredSize = Dimension(480, 120)
    minimumSize = Dimension(120, 60)
    getAccessibleContext().accessibleName = "Supplemental wave sample graph"
    getAccessibleContext().accessibleDescription =
        "No wave samples loaded; use the adjacent text table"
  }

  val sampleCount: Int
    get() = samples.size

  fun render(values: List<Int>) {
    requirePeripheralEdt("Wave graph rendering")
    require(values.size <= MAX_SAMPLES) { "Wave graph is limited to $MAX_SAMPLES samples" }
    require(values.all { it in 0..MAX_LEVEL }) { "Wave samples must be between 0 and $MAX_LEVEL" }
    samples = values.toIntArray()
    getAccessibleContext().accessibleDescription =
        if (samples.isEmpty()) {
          "No wave samples loaded; use the adjacent text table"
        } else {
          "Supplemental graph of ${samples.size} wave samples: " + samples.joinToString(", ")
        }
    repaint()
  }

  fun clear() {
    requirePeripheralEdt("Wave graph clearing")
    samples = IntArray(0)
    getAccessibleContext().accessibleDescription =
        "No wave samples loaded; use the adjacent text table"
    repaint()
  }

  override fun paintComponent(graphics: Graphics) {
    super.paintComponent(graphics)
    if (samples.isEmpty()) return
    val graphics2d = graphics.create() as Graphics2D
    try {
      graphics2d.setRenderingHint(
          RenderingHints.KEY_ANTIALIASING,
          RenderingHints.VALUE_ANTIALIAS_ON,
      )
      val left = INSET
      val top = INSET
      val plotWidth = (width - INSET * 2).coerceAtLeast(1)
      val plotHeight = (height - INSET * 2).coerceAtLeast(1)
      graphics2d.color = UIManager.getColor("Separator.foreground") ?: Color.GRAY
      graphics2d.drawRect(left, top, plotWidth, plotHeight)
      graphics2d.color = UIManager.getColor("Label.foreground") ?: Color.BLACK
      graphics2d.stroke = BasicStroke(2f)
      for (index in 1 until samples.size) {
        val previousX = left + (index - 1) * plotWidth / (samples.size - 1).coerceAtLeast(1)
        val currentX = left + index * plotWidth / (samples.size - 1).coerceAtLeast(1)
        val previousY = top + plotHeight - samples[index - 1] * plotHeight / MAX_LEVEL
        val currentY = top + plotHeight - samples[index] * plotHeight / MAX_LEVEL
        graphics2d.drawLine(previousX, previousY, currentX, currentY)
      }
    } finally {
      graphics2d.dispose()
    }
  }

  private companion object {
    const val MAX_SAMPLES = 32
    const val MAX_LEVEL = 15
    const val INSET = 8
  }
}
