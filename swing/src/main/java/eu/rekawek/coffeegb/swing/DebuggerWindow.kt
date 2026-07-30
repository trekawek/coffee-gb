package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.core.debug.DebugAddressSpace
import eu.rekawek.coffeegb.core.debug.DebugAnchoredMemoryRequest
import eu.rekawek.coffeegb.core.debug.DebugBreakpointList
import eu.rekawek.coffeegb.core.debug.DebugBreakpointHit
import eu.rekawek.coffeegb.core.debug.DebugCapabilities
import eu.rekawek.coffeegb.core.debug.DebugDisassembler
import eu.rekawek.coffeegb.core.debug.DebugErrorCode
import eu.rekawek.coffeegb.core.debug.DebugInspectionAnchor
import eu.rekawek.coffeegb.core.debug.DebugInspectionRequest
import eu.rekawek.coffeegb.core.debug.DebugInspectionResult
import eu.rekawek.coffeegb.core.debug.DebugInspectionSection
import eu.rekawek.coffeegb.core.debug.DebugMemoryBlock
import eu.rekawek.coffeegb.core.debug.DebugMemoryRequest
import eu.rekawek.coffeegb.core.debug.DebugPort
import eu.rekawek.coffeegb.core.debug.DebugResult
import eu.rekawek.coffeegb.core.debug.DebugSnapshot
import eu.rekawek.coffeegb.core.debug.DebugStepKind
import eu.rekawek.coffeegb.core.debug.DebugStepResult
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPcCondition
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryConfiguration
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryStatus
import eu.rekawek.coffeegb.core.debug.history.DebugReverseStepResult
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory
import eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration
import eu.rekawek.coffeegb.core.debug.trace.TraceReadRequest
import eu.rekawek.coffeegb.core.debug.trace.TraceReadResult
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.EnumSet
import java.util.IdentityHashMap
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JSpinner
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import org.slf4j.LoggerFactory

/** Modeless desktop debugger retained by [DesktopDebuggerController]. */
internal class DebuggerWindow(owner: JFrame) : DesktopDebuggerView {
  private val preferencesStore = DebuggerPreferencesStore()
  private val initialPreferences = preferencesStore.load()
  private val panel = DebuggerPanel(initialPreferences = initialPreferences)
  private val dialog = JDialog(owner, "Coffee GB Debugger", Dialog.ModalityType.MODELESS)
  private var positioned = false
  private var closed = false

  init {
    requireDebuggerWindowEdt("Debugger window construction")
    dialog.defaultCloseOperation = JDialog.HIDE_ON_CLOSE
    dialog.minimumSize = Dimension(900, 620)
    dialog.preferredSize = Dimension(1120, 760)
    dialog.contentPane = panel
    dialog.accessibleContext.accessibleName = "Coffee GB debugger"
    dialog.addWindowListener(
        object : WindowAdapter() {
          override fun windowClosing(event: WindowEvent) {
            savePreferences()
            panel.setPollingActive(false)
          }

          override fun windowClosed(event: WindowEvent) {
            savePreferences()
            panel.setPollingActive(false)
          }
        })
    dialog.addComponentListener(
        object : ComponentAdapter() {
          override fun componentShown(event: ComponentEvent) {
            panel.setPollingActive(true)
          }

          override fun componentHidden(event: ComponentEvent) {
            savePreferences()
            panel.setPollingActive(false)
          }
        })
    dialog.pack()
    initialPreferences.bounds?.let { bounds ->
      dialog.setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
      positioned = true
    }
  }

  override fun updateSession(event: Controller.SessionDebugPortEvent) {
    requireDebuggerWindowEdt("Debugger session update")
    if (!closed) panel.updateSession(event)
  }

  override fun showWindow() {
    requireDebuggerWindowEdt("Debugger window opening")
    if (closed) return
    if (!positioned) {
      dialog.setLocationRelativeTo(dialog.owner)
      positioned = true
    }
    dialog.isVisible = true
    panel.setPollingActive(true)
    dialog.toFront()
    dialog.requestFocus()
  }

  override fun close() {
    requireDebuggerWindowEdt("Debugger window disposal")
    if (closed) return
    closed = true
    savePreferences()
    panel.close()
    dialog.dispose()
  }

  private fun savePreferences() {
    if (!dialog.isDisplayable) return
    val bounds = dialog.bounds
    preferencesStore.save(
        panel.preferences(
            DebuggerWindowBounds(bounds.x, bounds.y, bounds.width, bounds.height),
        ))
  }
}

/** Narrow adapter that keeps the panel independently testable from the full DebugPort surface. */
internal interface DebuggerClient {
  val generation: Long
  val capabilities: DebugCapabilities

  fun inspect(request: DebugInspectionRequest): CompletionStage<DebugResult<DebugInspectionResult>>

  fun pause(): CompletionStage<DebugResult<DebugSnapshot>>

  fun resume(): CompletionStage<DebugResult<DebugSnapshot>>

  fun step(kind: DebugStepKind): CompletionStage<DebugResult<DebugStepResult>>

  fun stepBackward(kind: DebugStepKind): CompletionStage<DebugResult<DebugReverseStepResult>>

  fun configureHistory(
      configuration: DebugHistoryConfiguration
  ): CompletionStage<DebugResult<DebugHistoryStatus>>

  fun configureTrace(
      configuration: TraceConfiguration
  ): CompletionStage<DebugResult<TraceConfiguration>>

  fun historyStatus(): CompletionStage<DebugResult<DebugHistoryStatus>>

  fun listBreakpoints(): CompletionStage<DebugResult<DebugBreakpointList>>

  fun lastBreakpointHit(): CompletionStage<DebugResult<DebugBreakpointHit>>

  fun setBreakpoint(
      breakpoint: DebugBreakpoint
  ): CompletionStage<DebugResult<DebugBreakpoint>>

  fun removeBreakpoint(
      breakpointId: DebugBreakpointId
  ): CompletionStage<DebugResult<Void>>
}

private class DebugPortDebuggerClient(private val port: DebugPort) : DebuggerClient {
  override val generation: Long
    get() = port.sessionGeneration()

  override val capabilities: DebugCapabilities
    get() = port.capabilities()

  override fun inspect(
      request: DebugInspectionRequest
  ): CompletionStage<DebugResult<DebugInspectionResult>> = port.inspect(request)

  override fun pause(): CompletionStage<DebugResult<DebugSnapshot>> = port.pause()

  override fun resume(): CompletionStage<DebugResult<DebugSnapshot>> = port.resume()

  override fun step(kind: DebugStepKind): CompletionStage<DebugResult<DebugStepResult>> =
      port.step(kind)

  override fun stepBackward(
      kind: DebugStepKind
  ): CompletionStage<DebugResult<DebugReverseStepResult>> = port.stepBackward(kind)

  override fun configureHistory(
      configuration: DebugHistoryConfiguration
  ): CompletionStage<DebugResult<DebugHistoryStatus>> = port.configureHistory(configuration)

  override fun historyStatus(): CompletionStage<DebugResult<DebugHistoryStatus>> =
      port.historyStatus()

  override fun configureTrace(
      configuration: TraceConfiguration
  ): CompletionStage<DebugResult<TraceConfiguration>> = port.configureTrace(configuration)

  override fun listBreakpoints(): CompletionStage<DebugResult<DebugBreakpointList>> =
      port.listBreakpoints()

  override fun lastBreakpointHit(): CompletionStage<DebugResult<DebugBreakpointHit>> =
      port.lastBreakpointHit()

  override fun setBreakpoint(
      breakpoint: DebugBreakpoint
  ): CompletionStage<DebugResult<DebugBreakpoint>> = port.setBreakpoint(breakpoint)

  override fun removeBreakpoint(
      breakpointId: DebugBreakpointId
  ): CompletionStage<DebugResult<Void>> = port.removeBreakpoint(breakpointId)
}

/**
 * EDT-owned, headless-constructible debugger content.
 *
 * Debug calls only enqueue work. Their continuations return to the EDT and are correlated by
 * window epoch, client identity, session generation, and request id before touching Swing state.
 */
internal class DebuggerPanel(
    private val clientFactory: (DebugPort) -> DebuggerClient = ::DebugPortDebuggerClient,
    pollingIntervalMillis: Int = DEFAULT_POLLING_INTERVAL_MILLIS,
    initialPreferences: DebuggerUiPreferences = DebuggerUiPreferences(),
    private val copyText: (String) -> Unit = ::copyDebuggerText,
    private val peripheralExecutor: ExecutorService = newDebuggerPeripheralExecutor(),
    private val ownsPeripheralExecutor: Boolean = true,
) : JPanel(BorderLayout(6, 6)), AutoCloseable {
  private var uiPreferences = initialPreferences.sanitized()
  internal val sessionLabel = JLabel("No emulation session")
  internal val snapshotLabel = JLabel("Waiting for a debug snapshot")
  internal val stopReasonLabel = JLabel("No breakpoint stop in this session")
  internal val statusLabel = JLabel("Debugger ready")
  internal val runButton = JButton("Release debug pause")
  internal val pauseButton = JButton("Pause")
  internal val stepInstructionButton = JButton("Step instruction")
  internal val stepFrameButton = JButton("Step frame")
  internal val backInstructionButton = JButton("Back instruction")
  internal val backFrameButton = JButton("Back frame")
  internal val refreshButton = JButton("Refresh")
  internal val historyToggle = JCheckBox("Record reverse history")
  internal val registersArea = debuggerTextArea("CPU registers", 8, 28)
  internal val machineArea = debuggerTextArea("Machine summary", 13, 42)
  internal val disassemblyArea = debuggerTextArea("Current instruction", 5, 70)
  internal val stackArea = debuggerTextArea("Stack bytes", 12, 28)
  internal val memoryArea = debuggerTextArea("Memory hex view", 22, 76)
  internal val memorySpace = JComboBox(SAFE_MEMORY_SPACES)
  internal val memoryRange = JTextField("\$C000-\$C07F", 15)
  internal val memoryReadButton = JButton("Read")
  internal val historyLabel = JLabel("Reverse history status unavailable")
  internal val timelineToggle = JCheckBox("Capture trace timeline")
  internal val timelineWarning = JLabel("Timeline capture is off")
  internal val timelineCapacity =
      JSpinner(
          SpinnerNumberModel(
              uiPreferences.timelineCapacity,
              DebuggerUiPreferences.MIN_TIMELINE_CAPACITY,
              DebuggerUiPreferences.MAX_TIMELINE_CAPACITY,
              64,
          ))
  internal val timelineCategoryToggles: Map<TraceCategory, JCheckBox> =
      TraceCategory.entries.associateWith { category ->
        JCheckBox(categoryLabel(category), category in uiPreferences.timelineCategories)
      }
  internal val timelineModel = DebuggerTimelineTableModel(uiPreferences.timelineCapacity)
  internal val timelineTable = JTable(timelineModel)
  internal val tabs = JTabbedPane()
  internal val cpuScalarSplit =
      JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll(registersArea), scroll(machineArea))
  internal val cpuCodeSplit =
      JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll(disassemblyArea), scroll(stackArea))
  internal val cpuVerticalSplit =
      JSplitPane(JSplitPane.VERTICAL_SPLIT, cpuScalarSplit, cpuCodeSplit)
  internal val cpuPane = cpuPanel()
  internal val memoryPane = memoryPanel()
  internal val breakpointPane =
      DebuggerBreakpointPanel(
          DebuggerBreakpointPanelCallbacks(
              onSave = ::saveBreakpoint,
              onRemove = ::removeBreakpoint,
              onToggle = ::toggleBreakpoint,
              onToggleAtCurrentPc = ::toggleBreakpointAtCurrentPc,
              onStatus = { message -> statusLabel.text = message },
          ))
  internal val graphicsPane = DebuggerGraphicsPanel(copyText)
  internal val audioPane = DebuggerAudioPanel(copyText)
  internal val timelinePane = timelinePanel()

  private val pollingTimer =
      Timer(pollingIntervalMillis) { requestRefresh() }.apply { isRepeats = true }
  private val fontScaler: DebuggerFontScaler
  private var client: DebuggerClient? = null
  private var latestGeneration = NO_GENERATION
  private var snapshot: DebugSnapshot? = null
  private var lastBreakpointHit: DebugBreakpointHit? = null
  private var breakpointRows: List<DebugBreakpoint> = emptyList()
  private var nextBreakpointId = 0L
  private var historyStatus: DebugHistoryStatus? = null
  private var selectedMemory: DebugMemoryRequest? = null
  @Volatile private var windowEpoch = 0L
  private var nextRequestId = 1L
  private var refreshInFlight = false
  @Volatile private var activeRefreshRequestId = 0L
  private var refreshAgain = false
  private var snapshotOnlyRefreshPending = false
  private var metadataInFlight = false
  private var metadataAgain = false
  private var commandInFlight = false
  private var pollingActive = false
  private var updatingHistoryToggle = false
  private var updatingTimelineToggle = false
  private var traceOwner: DebuggerClient? = null
  private var pendingTraceEnable: DebuggerClient? = null
  private var traceOwnedCapacity = uiPreferences.timelineCapacity
  private var traceCursor = -1L
  private var traceConfigurationInFlight = false
  private var traceDisableAfterEnable = false
  private var traceDisableRequested = false
  private var traceDisableRetries = 0
  @Volatile private var peripheralPreparationTask: Future<*>? = null
  @Volatile private var peripheralPreparationRequestId = 0L
  private var closed = false
  private var lastAppliedStateRequestId = 0L
  private var lastAppliedCommandRequestId = 0L

  init {
    requireDebuggerWindowEdt("Debugger panel construction")
    require(pollingIntervalMillis > 0) { "Debugger polling interval must be positive" }
    border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
    getAccessibleContext().accessibleName = "Desktop debugger"
    getAccessibleContext().accessibleDescription =
        "F5 refreshes, F6 toggles run control, F7 and F8 step forward and back, F9 toggles " +
            "a program-counter breakpoint, " +
            "the menu shortcut plus or minus changes font size, and the menu shortcut C copies"

    sessionLabel.accessibleContext.accessibleName = "Debugger session"
    snapshotLabel.accessibleContext.accessibleName = "Debugger snapshot identity"
    stopReasonLabel.accessibleContext.accessibleName = "Debugger stop reason"
    statusLabel.accessibleContext.accessibleName = "Debugger status"
    statusLabel.accessibleContext.accessibleDescription = statusLabel.text
    statusLabel.addPropertyChangeListener("text") { event ->
      statusLabel.accessibleContext.accessibleDescription = event.newValue?.toString()
    }
    historyLabel.accessibleContext.accessibleName = "Reverse history status"
    timelineWarning.accessibleContext.accessibleName = "Trace timeline status"
    timelineWarning.accessibleContext.accessibleDescription = timelineWarning.text
    timelineWarning.addPropertyChangeListener("text") { event ->
      timelineWarning.accessibleContext.accessibleDescription = event.newValue?.toString()
    }

    val header = JPanel(GridLayout(0, 1, 2, 2))
    header.add(sessionLabel)
    header.add(snapshotLabel)
    header.add(stopReasonLabel)
    header.add(historyLabel)

    val toolbar = JPanel(FlowLayout(FlowLayout.LEADING, 5, 2))
    listOf(
            runButton,
            pauseButton,
            stepInstructionButton,
            stepFrameButton,
            backInstructionButton,
            backFrameButton,
            refreshButton,
        )
        .forEach(toolbar::add)
    toolbar.add(historyToggle)

    val north = JPanel(BorderLayout(4, 4))
    north.add(header, BorderLayout.NORTH)
    north.add(toolbar, BorderLayout.CENTER)
    add(north, BorderLayout.NORTH)

    tabs.accessibleContext.accessibleName = "Debugger panes"
    tabs.addTab("CPU", cpuPane)
    tabs.addTab("Memory", memoryPane)
    tabs.addTab("Breakpoints", breakpointPane)
    tabs.addTab("Graphics", graphicsPane)
    tabs.addTab("Audio", audioPane)
    tabs.addTab("Timeline", timelinePane)
    tabs.selectedIndex = uiPreferences.selectedPane.coerceAtMost(tabs.tabCount - 1)
    add(tabs, BorderLayout.CENTER)

    statusLabel.border = BorderFactory.createEmptyBorder(3, 4, 2, 4)
    add(statusLabel, BorderLayout.SOUTH)

    runButton.accessibleContext.accessibleDescription =
        "Release the debugger-owned pause with F6; an application-owned pause may remain"
    runButton.toolTipText =
        "Release the debugger-owned pause; an application-owned pause may remain"
    pauseButton.accessibleContext.accessibleDescription = "Pause at the next safe point with F6"
    stepInstructionButton.accessibleContext.accessibleDescription =
        "Execute one CPU instruction with F7"
    stepFrameButton.accessibleContext.accessibleDescription =
        "Run to the next frame boundary with Shift+F7"
    backInstructionButton.accessibleContext.accessibleDescription =
        "Restore the previous recorded instruction boundary with F8"
    backFrameButton.accessibleContext.accessibleDescription =
        "Restore the previous recorded frame boundary with Shift+F8"
    refreshButton.accessibleContext.accessibleDescription =
        "Refresh the coherent debugger view with F5"
    historyToggle.accessibleContext.accessibleDescription =
        "Enable or disable bounded reverse-execution history"
    timelineToggle.accessibleContext.accessibleDescription =
        "Explicitly enable or disable bounded typed trace capture for this debugger window"

    runButton.mnemonic = KeyEvent.VK_R
    pauseButton.mnemonic = KeyEvent.VK_P
    stepInstructionButton.mnemonic = KeyEvent.VK_I
    stepFrameButton.mnemonic = KeyEvent.VK_F
    backInstructionButton.mnemonic = KeyEvent.VK_B
    backFrameButton.mnemonic = KeyEvent.VK_K
    refreshButton.mnemonic = KeyEvent.VK_E
    historyToggle.mnemonic = KeyEvent.VK_H
    timelineToggle.mnemonic = KeyEvent.VK_T

    runButton.addActionListener { runCommand() }
    pauseButton.addActionListener { pauseCommand() }
    stepInstructionButton.addActionListener { stepCommand(DebugStepKind.INSTRUCTION) }
    stepFrameButton.addActionListener { stepCommand(DebugStepKind.FRAME) }
    backInstructionButton.addActionListener { reverseCommand(DebugStepKind.INSTRUCTION) }
    backFrameButton.addActionListener { reverseCommand(DebugStepKind.FRAME) }
    refreshButton.addActionListener {
      requestRefresh()
      requestMetadata()
    }
    historyToggle.addActionListener {
      if (!updatingHistoryToggle) {
        val requestedEnabled = historyToggle.isSelected
        // Swing changes the checkbox before this listener runs. Restore the authoritative state
        // until the asynchronous command succeeds so a typed failure cannot leave a false claim.
        applyHistory(historyStatus)
        configureHistory(requestedEnabled)
      }
    }
    timelineToggle.addActionListener {
      if (!updatingTimelineToggle) {
        val enable = timelineToggle.isSelected
        setTimelineSelected(traceOwner === client)
        configureTimeline(enable)
      }
    }
    timelineCategoryToggles.values.forEach { toggle ->
      toggle.addActionListener {
        uiPreferences =
            uiPreferences.copy(timelineCategories = selectedTimelineCategories()).sanitized()
        updateControlState()
      }
    }
    timelineCapacity.addChangeListener {
      uiPreferences =
          uiPreferences
              .copy(timelineCapacity = (timelineCapacity.value as Number).toInt())
              .sanitized()
      timelineModel.setRetentionLimit(uiPreferences.timelineCapacity)
    }
    memoryReadButton.addActionListener { selectMemoryRange() }
    tabs.addChangeListener {
      if (pollingActive) {
        syncPollingTimer()
        requestRefresh()
      }
    }

    installKeyboardBindings()
    clearSessionView()
    applyDivider(uiPreferences.cpuScalarDivider, cpuScalarSplit)
    applyDivider(uiPreferences.cpuCodeDivider, cpuCodeSplit)
    applyDivider(uiPreferences.cpuVerticalDivider, cpuVerticalSplit)
    fontScaler = DebuggerFontScaler(this)
    fontScaler.apply(uiPreferences.fontScalePercent)
    graphicsPane.applyFontScale(uiPreferences.fontScalePercent)
    audioPane.applyFontScale(uiPreferences.fontScalePercent)
    updateControlState()
  }

  private fun cpuPanel(): Component {
    cpuScalarSplit.resizeWeight = 0.34
    cpuScalarSplit.isContinuousLayout = true
    cpuCodeSplit.resizeWeight = 0.7
    cpuCodeSplit.isContinuousLayout = true
    return cpuVerticalSplit.apply {
      resizeWeight = 0.62
      isContinuousLayout = true
    }
  }

  private fun memoryPanel(): Component {
    memorySpace.accessibleContext.accessibleName = "Memory address space"
    memoryRange.accessibleContext.accessibleName = "Memory address or range"
    memoryReadButton.accessibleContext.accessibleDescription =
        "Read the selected bounded range at one coherent safe point"
    val controls = JPanel(FlowLayout(FlowLayout.LEADING))
    controls.add(debuggerLabel("Space:", memorySpace, KeyEvent.VK_S))
    controls.add(memorySpace)
    controls.add(debuggerLabel("Range:", memoryRange, KeyEvent.VK_G))
    controls.add(memoryRange)
    memoryReadButton.mnemonic = KeyEvent.VK_D
    controls.add(memoryReadButton)
    return JPanel(BorderLayout(4, 4)).apply {
      add(controls, BorderLayout.NORTH)
      add(scroll(memoryArea), BorderLayout.CENTER)
    }
  }

  private fun timelinePanel(): Component {
    timelineTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
    timelineTable.fillsViewportHeight = true
    timelineTable.autoCreateRowSorter = false
    timelineTable.accessibleContext.accessibleName = "Typed trace timeline"
    timelineTable.accessibleContext.accessibleDescription =
        "Sequence-ordered trace events correlated with the coherent debugger snapshot"
    timelineTable.columnModel.getColumn(0).preferredWidth = 80
    timelineTable.columnModel.getColumn(1).preferredWidth = 100
    timelineTable.columnModel.getColumn(2).preferredWidth = 100
    timelineTable.columnModel.getColumn(3).preferredWidth = 100
    timelineTable.columnModel.getColumn(4).preferredWidth = 120
    timelineTable.columnModel.getColumn(5).preferredWidth = 520

    timelineCapacity.accessibleContext.accessibleName = "Trace capacity"
    timelineCapacity.accessibleContext.accessibleDescription =
        "Maximum emulator trace entries and desktop rows, up to 2000"

    val capture = JPanel(FlowLayout(FlowLayout.LEADING, 5, 2))
    capture.add(timelineToggle)
    capture.add(debuggerLabel("Capacity:", timelineCapacity, KeyEvent.VK_C))
    capture.add(timelineCapacity)

    val categories = JPanel(GridLayout(0, 5, 4, 2))
    categories.border = BorderFactory.createTitledBorder("Event categories")
    categories.accessibleContext.accessibleName = "Trace event categories"
    timelineCategoryToggles.forEach { (category, toggle) ->
      toggle.accessibleContext.accessibleName = "${categoryLabel(category)} trace events"
      toggle.accessibleContext.accessibleDescription =
          "Include ${categoryLabel(category).lowercase()} events when trace capture starts"
      categories.add(toggle)
    }

    val controls = JPanel(BorderLayout(4, 2))
    controls.add(capture, BorderLayout.NORTH)
    controls.add(categories, BorderLayout.CENTER)
    controls.add(timelineWarning, BorderLayout.SOUTH)
    return JPanel(BorderLayout(4, 4)).apply {
      add(controls, BorderLayout.NORTH)
      add(JScrollPane(timelineTable), BorderLayout.CENTER)
    }
  }

  internal fun preferences(bounds: DebuggerWindowBounds? = null): DebuggerUiPreferences =
      uiPreferences
          .copy(
              bounds = bounds,
              cpuScalarDivider = cpuScalarSplit.dividerLocation,
              cpuCodeDivider = cpuCodeSplit.dividerLocation,
              cpuVerticalDivider = cpuVerticalSplit.dividerLocation,
              selectedPane = tabs.selectedIndex,
              fontScalePercent = fontScaler.scalePercent,
              timelineCategories = selectedTimelineCategories(),
              timelineCapacity = (timelineCapacity.value as Number).toInt(),
          )
          .sanitized()

  internal fun addPaneBeforeTimeline(title: String, component: Component) {
    requireDebuggerWindowEdt("Debugger pane installation")
    val timelineIndex = tabs.indexOfComponent(timelinePane).takeIf { it >= 0 } ?: tabs.tabCount
    tabs.insertTab(title, null, component, null, timelineIndex)
    tabs.selectedIndex = uiPreferences.selectedPane.coerceAtMost(tabs.tabCount - 1)
    fontScaler.capture(component)
    fontScaler.apply(fontScaler.scalePercent)
  }

  private fun installKeyboardBindings() {
    val input = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
    val shortcutMask = debuggerMenuShortcutMask()
    fun bind(key: KeyStroke, name: String, action: () -> Unit) {
      input.put(key, name)
      actionMap.put(
          name,
          object : AbstractAction() {
            override fun actionPerformed(event: java.awt.event.ActionEvent?) = action()
          },
      )
    }

    bind(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), ACTION_REFRESH) {
      if (refreshButton.isEnabled) refreshButton.doClick()
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0), ACTION_RUN_CONTROL) {
      when {
        pauseButton.isEnabled -> pauseButton.doClick()
        runButton.isEnabled -> runButton.doClick()
      }
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), ACTION_STEP_INSTRUCTION) {
      if (stepInstructionButton.isEnabled) stepInstructionButton.doClick()
    }
    bind(
        KeyStroke.getKeyStroke(KeyEvent.VK_F7, InputEvent.SHIFT_DOWN_MASK),
        ACTION_STEP_FRAME,
    ) {
      if (stepFrameButton.isEnabled) stepFrameButton.doClick()
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0), ACTION_BACK_INSTRUCTION) {
      if (backInstructionButton.isEnabled) backInstructionButton.doClick()
    }
    bind(
        KeyStroke.getKeyStroke(KeyEvent.VK_F8, InputEvent.SHIFT_DOWN_MASK),
        ACTION_BACK_FRAME,
    ) {
      if (backFrameButton.isEnabled) backFrameButton.doClick()
    }
    bind(KeyStroke.getKeyStroke(KeyEvent.VK_F9, 0), ACTION_TOGGLE_PC_BREAKPOINT) {
      if (breakpointPane.toggleCurrentPcButton.isEnabled) {
        breakpointPane.toggleCurrentPcButton.doClick()
      }
    }
    bind(
        KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, shortcutMask),
        ACTION_ZOOM_IN,
    ) {
      zoomFont(DebuggerUiPreferences.FONT_SCALE_STEP)
    }
    bind(
        KeyStroke.getKeyStroke(KeyEvent.VK_ADD, shortcutMask),
        "$ACTION_ZOOM_IN-numpad",
    ) {
      zoomFont(DebuggerUiPreferences.FONT_SCALE_STEP)
    }
    bind(
        KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, shortcutMask),
        ACTION_ZOOM_OUT,
    ) {
      zoomFont(-DebuggerUiPreferences.FONT_SCALE_STEP)
    }
    bind(
        KeyStroke.getKeyStroke(KeyEvent.VK_0, shortcutMask),
        ACTION_ZOOM_RESET,
    ) {
      setFontScale(DebuggerUiPreferences.DEFAULT_FONT_SCALE_PERCENT)
    }
    bind(
        KeyStroke.getKeyStroke(KeyEvent.VK_C, shortcutMask),
        ACTION_COPY,
    ) {
      copyCurrentPane()
    }

    refreshButton.toolTipText = "Refresh coherent view (F5)"
    pauseButton.toolTipText = "Pause at the next safe point (F6)"
    stepInstructionButton.toolTipText = "Execute one CPU instruction (F7)"
    stepFrameButton.toolTipText = "Run to the next frame boundary (Shift+F7)"
    backInstructionButton.toolTipText = "Restore the previous instruction boundary (F8)"
    backFrameButton.toolTipText = "Restore the previous frame boundary (Shift+F8)"
  }

  private fun zoomFont(delta: Int) {
    setFontScale(fontScaler.scalePercent + delta)
  }

  private fun setFontScale(requested: Int) {
    val scale =
        requested.coerceIn(
            DebuggerUiPreferences.MIN_FONT_SCALE_PERCENT,
            DebuggerUiPreferences.MAX_FONT_SCALE_PERCENT,
        )
    fontScaler.apply(scale)
    graphicsPane.applyFontScale(scale)
    audioPane.applyFontScale(scale)
    uiPreferences = uiPreferences.copy(fontScalePercent = scale)
    revalidate()
    repaint()
    statusLabel.text = "Debugger font scale: $scale%"
  }

  private fun copyCurrentPane() {
    val text =
        when (tabs.selectedComponent) {
          cpuPane ->
              listOf(registersArea.text, machineArea.text, disassemblyArea.text, stackArea.text)
                  .joinToString("\n\n")
          memoryPane -> memoryArea.text
          breakpointPane -> breakpointPane.copyText()
          graphicsPane -> graphicsPane.copyText()
          audioPane -> audioPane.copyText()
          timelinePane -> timelineModel.copyText(timelineTable.selectedRows)
          else -> debuggerComponentText(tabs.selectedComponent)
        }
    if (text.isBlank()) {
      statusLabel.text = "The selected debugger pane has no text to copy"
      return
    }
    try {
      copyText(text)
      statusLabel.text = "Copied the selected debugger pane"
    } catch (failure: RuntimeException) {
      LOG.warn("Debugger clipboard copy failed", failure)
      statusLabel.text = "Copy failed — the system clipboard is unavailable"
    }
  }

  private fun selectedTimelineCategories(): Set<TraceCategory> =
      timelineCategoryToggles
          .filterValues(JCheckBox::isSelected)
          .keys
          .let { values ->
            if (values.isEmpty()) emptySet() else EnumSet.copyOf(values)
          }

  fun updateSession(event: Controller.SessionDebugPortEvent) {
    requireDebuggerWindowEdt("Debugger session update")
    val port = event.debugPort
    val nextClient = port?.let(clientFactory)
    updateClient(event.generation, nextClient)
  }

  internal fun updateClient(generation: Long, nextClient: DebuggerClient?) {
    requireDebuggerWindowEdt("Debugger client update")
    if (closed || generation < latestGeneration) return
    if (nextClient != null && nextClient.generation != generation) return
    val previousClient = client
    if (previousClient != null) {
      // Mark the old owner abandoned before its best-effort trace disable. A terminal port may
      // complete the disable failure synchronously; it must not remain the owner of a replacement
      // session after the bounded retries are exhausted.
      client = null
      releaseTimelineOwnership(previousClient, "Session changed; timeline capture released")
    }
    cancelPeripheralPreparation()
    latestGeneration = generation
    client = nextClient
    windowEpoch++
    snapshot = null
    lastBreakpointHit = null
    breakpointRows = emptyList()
    nextBreakpointId = 0L
    historyStatus = null
    selectedMemory = null
    refreshInFlight = false
    activeRefreshRequestId = 0L
    refreshAgain = false
    snapshotOnlyRefreshPending = false
    metadataInFlight = false
    metadataAgain = false
    commandInFlight = false
    lastAppliedStateRequestId = 0L
    lastAppliedCommandRequestId = 0L
    traceCursor = -1L
    timelineModel.clear()
    graphicsPane.clear()
    audioPane.clear()
    setTimelineSelected(false)
    timelineWarning.text =
        when {
          nextClient == null -> "Timeline unavailable without an emulation session"
          nextClient.capabilities.coherentTraceInspection() -> "Timeline capture is off"
          else -> "Coherent trace timeline is unsupported in this session"
        }
    breakpointPane.clear()
    breakpointPane.replace(emptyList(), nextClient?.capabilities, null, null)
    syncPollingTimer()
    if (nextClient == null) {
      clearSessionView()
      sessionLabel.text = "Session $generation ended"
      statusLabel.text = "No active debug port"
    } else {
      val coherentInspection = nextClient.capabilities.coherentInspection()
      sessionLabel.text =
          "Session $generation — ${DebuggerPresentation.capabilities(nextClient.capabilities).summary()}"
      snapshotLabel.text =
          if (coherentInspection) "Waiting for a coherent snapshot"
          else "Coherent inspection is unavailable for this session"
      stopReasonLabel.text = "Waiting for breakpoint stop metadata"
      historyLabel.text = "Waiting for reverse history status"
      registersArea.text =
          if (coherentInspection) "Waiting for CPU state" else "Snapshot inspection unavailable"
      machineArea.text =
          if (coherentInspection) "Waiting for machine state" else "Snapshot inspection unavailable"
      disassemblyArea.text =
          if (coherentInspection) "Pause to inspect the current instruction"
          else "Disassembly unavailable"
      stackArea.text =
          if (coherentInspection) "Pause to inspect stack memory" else "Stack inspection unavailable"
      memoryArea.text =
          if (coherentInspection) "Choose a bounded range, pause, and select Read"
          else "Memory inspection unavailable"
      statusLabel.text =
          if (coherentInspection) "Session attached"
          else "Session attached; coherent inspection unsupported"
      if (pollingActive) {
        requestRefresh()
        requestMetadata()
      }
    }
    updateControlState()
  }

  fun setPollingActive(active: Boolean) {
    requireDebuggerWindowEdt("Debugger polling state change")
    if (closed || pollingActive == active) return
    pollingActive = active
    windowEpoch++
    if (active) {
      syncPollingTimer()
      if (traceOwner === client && traceDisableRequested && !traceConfigurationInFlight) {
        submitTraceDisable(checkNotNull(client))
      }
      requestRefresh()
      requestMetadata()
    } else {
      syncPollingTimer()
      cancelPeripheralPreparation()
      refreshInFlight = false
      activeRefreshRequestId = 0L
      refreshAgain = false
      metadataAgain = false
      releaseRetainedSessionState()
    }
    updateControlState()
  }

  internal fun requestRefresh() {
    requireDebuggerWindowEdt("Debugger refresh")
    val attached = client ?: return
    if (closed || !pollingActive || !canPollInspection(attached)) return
    if (refreshInFlight) {
      refreshAgain = true
      return
    }
    val plan = inspectionPlan(attached)
    val token = token(attached)
    refreshInFlight = true
    activeRefreshRequestId = token.requestId
    updateControlState()
    val stage =
        try {
          attached.inspect(plan.request)
        } catch (failure: RuntimeException) {
          refreshInFlight = false
          activeRefreshRequestId = 0L
          showFailure("Refresh", failure)
          updateControlState()
          return
        }
    stage.whenComplete { result, failure ->
      prepareRefreshCompletion(token, plan, result, failure)
    }
  }

  private fun prepareRefreshCompletion(
      token: RequestToken,
      plan: InspectionPlan,
      result: DebugResult<DebugInspectionResult>?,
      failure: Throwable?,
  ) {
    if (token.epoch != windowEpoch || token.requestId != activeRefreshRequestId) return
    when {
      failure != null -> dispatchRefreshCompletion(token, plan, RefreshCompletion.Unexpected(failure))
      result == null -> dispatchRefreshCompletion(token, plan, RefreshCompletion.Empty)
      result.isFailure ->
          dispatchRefreshCompletion(token, plan, RefreshCompletion.TypedFailure(result))
      else -> {
        val inspection = result.value()
        if (inspection.graphics().isEmpty && inspection.audio().isEmpty) {
          dispatchRefreshCompletion(
              token,
              plan,
              RefreshCompletion.Success(PreparedInspection.withoutPeripherals(inspection)),
          )
        } else {
          try {
            peripheralPreparationRequestId = token.requestId
            val task =
                peripheralExecutor.submit {
                  val prepared = runCatching { PreparedInspection.prepare(inspection) }
                  dispatchRefreshCompletion(
                      token,
                      plan,
                      prepared.fold(
                          onSuccess = { value -> RefreshCompletion.Success(value) },
                          onFailure = { value -> RefreshCompletion.Unexpected(value) },
                      ),
                  )
                }
            peripheralPreparationTask = task
            if (token.epoch != windowEpoch || token.requestId != activeRefreshRequestId) {
              task.cancel(true)
              if (peripheralPreparationTask === task) peripheralPreparationTask = null
              if (peripheralPreparationRequestId == token.requestId) {
                peripheralPreparationRequestId = 0L
              }
            }
          } catch (failure: RejectedExecutionException) {
            dispatchRefreshCompletion(token, plan, RefreshCompletion.Unexpected(failure))
          }
        }
      }
    }
  }

  private fun dispatchRefreshCompletion(
      token: RequestToken,
      plan: InspectionPlan,
      completion: RefreshCompletion,
  ) {
    dispatchSwingMutation { finishRefresh(token, plan, completion) }
  }

  private fun finishRefresh(
      token: RequestToken,
      plan: InspectionPlan,
      completion: RefreshCompletion,
  ) {
    if (!sameSession(token)) return
    if (activeRefreshRequestId != token.requestId) return
    refreshInFlight = false
    activeRefreshRequestId = 0L
    if (peripheralPreparationRequestId == token.requestId) {
      peripheralPreparationTask = null
      peripheralPreparationRequestId = 0L
    }
    if (token.epoch == windowEpoch && pollingActive) {
      when (completion) {
        is RefreshCompletion.Unexpected -> showFailure("Refresh", completion.failure)
        RefreshCompletion.Empty -> statusLabel.text = "Refresh returned no result"
        is RefreshCompletion.TypedFailure -> {
          val result = completion.result
          if (result.error().code() == DebugErrorCode.SIDE_EFFECTFUL_ADDRESS &&
              plan.request.anchoredRequests().isNotEmpty()) {
            // Register-relative ranges were planned from the previous snapshot but resolve
            // against the new safe point. If another pause owner moved PC/SP across a safe
            // boundary, first obtain a fresh scalar snapshot and then re-plan once from it.
            snapshotOnlyRefreshPending = true
            refreshAgain = true
            statusLabel.text =
                "Inspection registers moved; refreshing the snapshot before retrying"
          } else {
            showFailure("Refresh", result)
          }
        }
        is RefreshCompletion.Success -> {
          val inspection = completion.inspection
          when {
            inspection.request != plan.request ->
                statusLabel.text = "Refresh returned a mismatched inspection response"
            token.requestId < lastAppliedStateRequestId -> Unit
            else -> {
              lastAppliedStateRequestId = token.requestId
              applyInspection(plan, inspection)
            }
          }
        }
      }
    }
    val repeat = refreshAgain && pollingActive
    refreshAgain = false
    updateControlState()
    if (repeat) requestRefresh()
  }

  private fun inspectionPlan(attached: DebuggerClient): InspectionPlan {
    val traceRequest = traceInspectionRequest(attached)
    val sections = selectedInspectionSections(attached)
    if (snapshotOnlyRefreshPending) {
      snapshotOnlyRefreshPending = false
      return InspectionPlan(
          DebugInspectionRequest(
              emptyList(),
              emptyList(),
              sections,
              traceRequest,
          ),
          null,
          null,
          false,
          true,
      )
    }
    val anchored = ArrayList<DebugAnchoredMemoryRequest>(2)
    var codeIndex: Int? = null
    var stackIndex: Int? = null
    var remainingBytes = attached.capabilities.maxInspectionBytes()
    val previous = snapshot
    val replanAfterSnapshot = previous?.paused() != true
    if (attached.capabilities.memoryRead() && previous?.paused() == true) {
      pcAnchoredLength(
              previous.registers().pc(),
              minOf(MAX_INSTRUCTION_BYTES, remainingBytes),
          )
          ?.let { length ->
        codeIndex = anchored.size
        anchored +=
            DebugAnchoredMemoryRequest(DebugInspectionAnchor.PROGRAM_COUNTER, 0, length)
        remainingBytes -= length
      }
      stackAnchoredLength(previous.registers().sp(), minOf(STACK_BYTES, remainingBytes))?.let {
          length ->
        stackIndex = anchored.size
        anchored += DebugAnchoredMemoryRequest(DebugInspectionAnchor.STACK_POINTER, 0, length)
        remainingBytes -= length
      }
    }
    val explicit =
        selectedMemory
            ?.takeIf {
              previous?.paused() == true &&
                  attached.capabilities.memoryRead() &&
                  it.length() <= remainingBytes
            }
            ?.let(::listOf)
            ?: emptyList()
    return InspectionPlan(
        DebugInspectionRequest(anchored, explicit, sections, traceRequest),
        codeIndex,
        stackIndex,
        explicit.isNotEmpty(),
        replanAfterSnapshot,
    )
  }

  private fun selectedInspectionSections(
      attached: DebuggerClient
  ): Set<DebugInspectionSection> =
      when {
        tabs.selectedComponent === graphicsPane &&
            attached.capabilities.supportsInspection(DebugInspectionSection.GRAPHICS) ->
            EnumSet.of(DebugInspectionSection.GRAPHICS)
        tabs.selectedComponent === audioPane &&
            attached.capabilities.supportsInspection(DebugInspectionSection.AUDIO) ->
            EnumSet.of(DebugInspectionSection.AUDIO)
        else -> emptySet()
      }

  private fun traceInspectionRequest(attached: DebuggerClient): Optional<TraceReadRequest> {
    if (traceOwner !== attached ||
        traceDisableRequested ||
        !pollingActive ||
        !attached.capabilities.coherentTraceInspection()) {
      return Optional.empty()
    }
    val pageSize = minOf(TRACE_PAGE_SIZE, attached.capabilities.maxInspectionTraceEntries())
    return if (pageSize > 0) Optional.of(TraceReadRequest(traceCursor, pageSize))
    else Optional.empty()
  }

  private fun applyInspection(plan: InspectionPlan, result: PreparedInspection) {
    requireDebuggerWindowEdt("Debugger inspection rendering")
    val wasRunning = snapshot?.paused() == false
    val view = DebuggerPresentation.snapshot(result.snapshot)
    snapshot = result.snapshot
    renderBreakpointHit()
    snapshotLabel.text =
        "${view.identity.label} — ${if (view.paused) "PAUSED" else "RUNNING"} — ${view.timingText}"
    registersArea.text = view.registers.render()
    machineArea.text = renderMachine(result.snapshot)
    val graphics = result.graphics?.takeIf { it.identity == view.identity }
    if (tabs.selectedComponent === graphicsPane && graphics != null) {
      graphicsPane.render(graphics)
    } else {
      graphicsPane.showNotCaptured(view.identity)
    }
    val audio = result.audio?.takeIf { it.identity == view.identity }
    if (tabs.selectedComponent === audioPane && audio != null) {
      audioPane.render(audio)
    } else {
      audioPane.showNotCaptured(view.identity)
    }
    applyTimelineInspection(result)

    if (!view.paused) {
      disassemblyArea.text = "Paused-only view; instruction bytes are not sampled while running."
      stackArea.text = "Paused-only view; stack bytes are not sampled while running."
      memoryArea.text = "Paused-only view; memory is blank while the emulator is running."
    } else {
      val identity = view.identity
      val code = plan.codeIndex?.let { result.anchoredBlocks[it] }
      disassemblyArea.text =
          code?.let {
            runCatching { DebugDisassembler.disassemble(it) }
                .getOrElse { failure -> "Disassembly unavailable: ${failure.message}" }
          } ?: "Current PC is outside the side-effect-free ROM/WRAM/HRAM inspection views."

      val stackCapture =
          plan.stackIndex?.let {
            DebuggerMemoryCapture(identity, result.anchoredBlocks[it])
          }
      stackArea.text =
          DebuggerPresentation.stack(result.snapshot, client!!.capabilities, stackCapture, STACK_BYTES)
              .render()

      memoryArea.text =
          if (plan.includesExplicitMemory) {
            val capture = DebuggerMemoryCapture(identity, result.memoryBlocks.single())
            DebuggerPresentation.memory(identity, capture).render()
          } else {
            "Choose a bounded range and select Read."
          }
    }
    statusLabel.text = "Snapshot refreshed"

    // A plan made without a prior paused snapshot (or as an explicit scalar retry) deliberately
    // carries no register-relative ranges. Re-plan it exactly once against this coherent result.
    // A paused PC/SP outside the safe views may still produce zero blocks on that follow-up and
    // must not create an immediate refresh loop.
    if (view.paused && plan.replanAfterSnapshot && client?.capabilities?.memoryRead() == true) {
      refreshAgain = true
    }
    if (wasRunning && view.paused) requestMetadata()
    updateControlState()
  }

  private fun applyTimelineInspection(result: PreparedInspection) {
    val attached = client ?: return
    if (traceOwner !== attached ||
        traceDisableRequested ||
        result.request.traceRequest().isEmpty) return
    val trace = result.trace
    if (trace == null) {
      timelineWarning.text = "WARNING: coherent inspection omitted the requested trace page"
      return
    }
    val identity =
        DebuggerTimelineIdentity(
            result.snapshot.sessionGeneration(),
            result.snapshot.sequence(),
        )
    if (identity.sessionGeneration != attached.generation) {
      timelineWarning.text = "WARNING: ignored a trace page from a different session"
      return
    }
    val update = timelineModel.append(identity, trace)
    traceCursor = trace.nextAfterSequence()
    timelineWarning.text =
        if (update.warning == null) {
          "Timeline: ${timelineModel.rowCount} retained rows — cursor $traceCursor — ${identity.label}"
        } else {
          "WARNING: ${update.warning} — ${identity.label}"
        }
    if (timelineModel.rowCount > 0 && timelineTable.selectedRow < 0) {
      val last = timelineModel.rowCount - 1
      timelineTable.scrollRectToVisible(timelineTable.getCellRect(last, 0, true))
    }
  }

  private fun requestMetadata() {
    requireDebuggerWindowEdt("Debugger metadata refresh")
    val attached = client ?: return
    if (closed || !pollingActive) return
    if (metadataInFlight) {
      metadataAgain = true
      return
    }
    val token = token(attached)
    metadataInFlight = true
    updateControlState()
    val breakpoints =
        if (attached.capabilities.breakpoints()) attached.listBreakpoints()
        else completedResult(DebugResult.success(DebugBreakpointList(emptyList())))
    val history =
        if (attached.capabilities.history().checkpointHistory()) attached.historyStatus()
        else completedResult<DebugResult<DebugHistoryStatus>?>(null)
    val lastHit =
        if (attached.capabilities.breakpoints()) attached.lastBreakpointHit()
        else completedResult<DebugResult<DebugBreakpointHit>?>(null)
    breakpoints
        .thenCombine(history) { breakpointResult, historyResult ->
          MetadataResult(breakpointResult, historyResult, null)
        }
        .thenCombine(lastHit) { metadata, hitResult -> metadata.copy(lastHit = hitResult) }
        .whenComplete { result, failure ->
          dispatchSwingMutation {
            if (!sameSession(token)) return@dispatchSwingMutation
            metadataInFlight = false
            if (token.epoch == windowEpoch && pollingActive) {
              when {
                token.requestId < lastAppliedCommandRequestId -> metadataAgain = true
                failure != null -> showFailure("Metadata refresh", failure)
                result == null -> statusLabel.text = "Metadata refresh returned no result"
                result.breakpoints.isFailure -> showFailure("Breakpoint refresh", result.breakpoints)
                else -> {
                  breakpointRows = result.breakpoints.value().breakpoints()
                  advanceBreakpointId(breakpointRows)
                  applyBreakpointHit(result.lastHit)
                  breakpointPane.replace(
                      breakpointRows,
                      attached.capabilities,
                      lastBreakpointHit,
                      snapshot?.takeIf { it.paused() }?.registers()?.pc(),
                      isCurrentBreakpointStop(lastBreakpointHit),
                  )
                  val historyResult = result.history
                  if (historyResult != null) {
                    if (historyResult.isFailure) showFailure("History refresh", historyResult)
                    else applyHistory(historyResult.value())
                  } else {
                    applyHistory(null)
                  }
                }
              }
            }
            val repeat = metadataAgain && pollingActive
            metadataAgain = false
            updateControlState()
            if (repeat) requestMetadata()
          }
        }
  }

  private fun applyBreakpointHit(result: DebugResult<DebugBreakpointHit>?) {
    when {
      result == null -> {
        lastBreakpointHit = null
        renderBreakpointHit()
      }
      result.isFailure && result.error().code() == DebugErrorCode.NO_BREAKPOINT_HIT -> {
        lastBreakpointHit = null
        renderBreakpointHit()
      }
      result.isFailure -> {
        lastBreakpointHit = null
        stopReasonLabel.text =
            "Breakpoint stop reason unavailable — ${result.error().code()}: ${result.error().message()}"
      }
      else -> {
        lastBreakpointHit =
            result.value().takeIf {
              it.snapshot().sessionGeneration() == latestGeneration
            }
        renderBreakpointHit()
      }
    }
  }

  private fun renderBreakpointHit() {
    val hit =
        lastBreakpointHit?.takeIf { it.snapshot().sessionGeneration() == latestGeneration }
    breakpointPane.updateExecutionContext(
        hit,
        snapshot?.takeIf { it.paused() }?.registers()?.pc(),
        isCurrentBreakpointStop(hit),
    )
    if (hit == null) {
      stopReasonLabel.text = "No breakpoint stop in this session"
      return
    }
    val id = hit.breakpointId().value()
    val condition =
        hit.breakpoint()
            .map { DebuggerPresentation.breakpointRows(listOf(it)).single().condition }
            .orElse("condition unavailable")
    val current = isCurrentBreakpointStop(hit)
    val prefix = if (current) "Stopped by" else "Last stop:"
    stopReasonLabel.text =
        "$prefix breakpoint #$id — $condition — matched tick ${hit.matchMasterTick()}, " +
            "paused tick ${hit.snapshot().masterTick()}"
  }

  private fun isCurrentBreakpointStop(hit: DebugBreakpointHit?): Boolean {
    if (hit?.activePause() != true) return false
    val current = snapshot ?: return false
    return current.paused() &&
        current.sessionGeneration() == hit.snapshot().sessionGeneration() &&
        current.masterTick() == hit.snapshot().masterTick() &&
        current.frame() == hit.snapshot().frame() &&
        current.framePosition() == hit.snapshot().framePosition() &&
        current.execution().retiredInstructions() ==
            hit.snapshot().execution().retiredInstructions()
  }

  private fun applyHistory(status: DebugHistoryStatus?) {
    historyStatus = status
    val attached = client ?: return
    val view = DebuggerPresentation.history(attached.capabilities, status)
    historyLabel.text =
        if (!attached.capabilities.history().checkpointHistory()) {
          "Reverse history unsupported"
        } else {
          "History: ${if (view.enabled) "ON" else "OFF"}, ${view.checkpointCount} checkpoints, " +
              "${view.futureCheckpointCount} future — ${view.cursor}"
        }
    updatingHistoryToggle = true
    try {
      historyToggle.isSelected = view.enabled
    } finally {
      updatingHistoryToggle = false
    }
  }

  private fun pauseCommand() {
    val attached = client ?: return
    executeCommand("Pause", attached::pause) { value ->
      value?.let(::applyCommandSnapshot)
    }
  }

  private fun runCommand() {
    val attached = client ?: return
    executeCommand("Release debug pause", attached::resume) { value ->
      value?.let(::applyCommandSnapshot)
    }
  }

  private fun stepCommand(kind: DebugStepKind) {
    val attached = client ?: return
    executeCommand("Step ${kind.name.lowercase()}", { attached.step(kind) }) { value ->
      value?.let {
        applyCommandSnapshot(it.snapshot())
        statusLabel.text =
            "${it.kind()} step stopped at ${it.stopReason()} after ${it.ticksExecuted()} ticks"
      }
    }
  }

  private fun reverseCommand(kind: DebugStepKind) {
    val attached = client ?: return
    executeCommand("Reverse ${kind.name.lowercase()}", { attached.stepBackward(kind) }) { value ->
      value?.let {
        applyCommandSnapshot(it.snapshot())
        applyHistory(it.history())
        val outcome = DebuggerPresentation.reverseOutcome(DebugResult.success(it))
        statusLabel.text = outcome.message
      }
    }
  }

  private fun configureHistory(enabled: Boolean) {
    val attached = client ?: return
    val capabilities = attached.capabilities.history()
    val configuration =
        if (!enabled) {
          DebugHistoryConfiguration.disabled()
        } else {
          DebugHistoryConfiguration(
              true,
              minOf(DebugHistoryConfiguration.DEFAULT_MAX_FRAMES, capabilities.maxFrames()),
              minOf(
                  DebugHistoryConfiguration.DEFAULT_MEMORY_BUDGET_BYTES,
                  capabilities.maxMemoryBudgetBytes(),
              ),
          )
        }
    executeCommand(
        if (enabled) "Enable reverse history" else "Disable reverse history",
        { attached.configureHistory(configuration) },
    ) { value -> value?.let(::applyHistory) }
  }

  private fun configureTimeline(enabled: Boolean) {
    val attached = client ?: return
    if (!enabled) {
      releaseTimelineOwnership(attached, "Timeline capture disabled")
      updateControlState()
      return
    }
    if (closed || !pollingActive || traceConfigurationInFlight) return
    if (traceOwner != null) {
      statusLabel.text = "Wait for the previous trace configuration to be released"
      return
    }
    if (!attached.capabilities.coherentTraceInspection()) {
      statusLabel.text = "Coherent trace inspection is unsupported in this session"
      return
    }
    val categories =
        selectedTimelineCategories().filterTo(EnumSet.noneOf(TraceCategory::class.java)) {
          attached.capabilities.supports(it)
        }
    if (categories.isEmpty()) {
      statusLabel.text = "Select at least one supported trace category"
      return
    }
    val capacity =
        minOf(
            (timelineCapacity.value as Number).toInt(),
            attached.capabilities.maxTraceCapacity(),
            DebuggerTimelineTableModel.MAX_RETAINED_ROWS,
        )
    if (capacity < 1) {
      statusLabel.text = "This session does not expose a trace buffer"
      return
    }
    val configuration = TraceConfiguration(capacity, categories)
    timelineModel.setRetentionLimit(capacity)
    traceConfigurationInFlight = true
    pendingTraceEnable = attached
    traceDisableAfterEnable = false
    traceDisableRequested = false
    statusLabel.text = "Enable trace timeline…"
    timelineWarning.text = "Timeline capture is being enabled"
    updateControlState()
    val stage =
        try {
          attached.configureTrace(configuration)
        } catch (failure: RuntimeException) {
          pendingTraceEnable = null
          traceConfigurationInFlight = false
          showFailure("Enable trace timeline", failure)
          timelineWarning.text = "Timeline capture remains off"
          updateControlState()
          return
        }
    stage.whenComplete { result, failure ->
      dispatchSwingMutation {
        if (pendingTraceEnable === attached) pendingTraceEnable = null
        traceConfigurationInFlight = false
        when {
          failure != null -> {
            if (!closed && client === attached) showFailure("Enable trace timeline", failure)
            if (!closed && client === attached) timelineWarning.text = "Timeline capture remains off"
          }
          result == null -> {
            if (!closed && client === attached) {
              statusLabel.text = "Enable trace timeline returned no result"
              timelineWarning.text = "Timeline capture remains off"
            }
          }
          result.isFailure -> {
            if (!closed && client === attached) showFailure("Enable trace timeline", result)
            if (!closed && client === attached) timelineWarning.text = "Timeline capture remains off"
          }
          !result.value().isEnabled() -> {
            if (!closed && client === attached) {
              statusLabel.text = "Trace timeline was not enabled"
              timelineWarning.text = "Timeline capture remains off"
            }
          }
          else -> {
            traceOwner = attached
            traceOwnedCapacity = result.value().capacity()
            traceCursor = -1L
            timelineModel.clear()
            if (traceDisableAfterEnable || closed || client !== attached || !pollingActive) {
              traceDisableAfterEnable = false
              traceDisableRequested = true
              submitTraceDisable(attached)
            } else {
              traceDisableRequested = false
              traceDisableRetries = 0
              setTimelineSelected(true)
              timelineWarning.text = "Timeline capture is on; waiting for the first coherent page"
              statusLabel.text =
                  "Trace timeline enabled for ${result.value().categories().size} categories"
              syncPollingTimer()
              requestRefresh()
            }
          }
        }
        updateControlState()
      }
    }
  }

  private fun releaseTimelineOwnership(attached: DebuggerClient, message: String) {
    traceCursor = -1L
    timelineModel.clear()
    setTimelineSelected(false)
    timelineWarning.text = message
    if (pendingTraceEnable === attached) {
      traceDisableAfterEnable = true
      traceDisableRequested = true
      return
    }
    if (traceOwner === attached) {
      traceDisableRequested = true
      submitTraceDisable(attached)
    }
    syncPollingTimer()
  }

  private fun submitTraceDisable(attached: DebuggerClient) {
    if (traceOwner !== attached || traceConfigurationInFlight) return
    traceConfigurationInFlight = true
    val capacity = traceOwnedCapacity.coerceAtLeast(1)
    val stage =
        try {
          attached.configureTrace(TraceConfiguration.disabled(capacity))
        } catch (failure: RuntimeException) {
          traceConfigurationInFlight = false
          handleTraceDisableFailure(attached, failure)
          return
        }
    stage.whenComplete { result, failure ->
      dispatchSwingMutation {
        traceConfigurationInFlight = false
        when {
          failure != null -> handleTraceDisableFailure(attached, failure)
          result == null || result.isFailure || result.value().isEnabled() ->
              handleTraceDisableFailure(attached, null)
          else -> {
            if (traceOwner === attached) traceOwner = null
            traceDisableRequested = false
            traceDisableRetries = 0
            if (!closed && client === attached) {
              timelineWarning.text = "Timeline capture is off"
              statusLabel.text = "Trace timeline disabled"
            }
            syncPollingTimer()
          }
        }
        updateControlState()
      }
    }
  }

  private fun handleTraceDisableFailure(attached: DebuggerClient, failure: Throwable?) {
    if (failure != null) LOG.warn("Disabling debugger-owned trace capture failed", failure)
    if (traceOwner !== attached) return
    if (traceDisableRetries < MAX_TRACE_DISABLE_RETRIES) {
      traceDisableRetries++
      submitTraceDisable(attached)
      return
    }
    if (closed || client !== attached) {
      traceOwner = null
      traceDisableRequested = false
      traceDisableRetries = 0
      return
    }
    if (!closed && client === attached) {
      timelineWarning.text =
          "WARNING: debugger-owned trace capture could not be disabled; retry on next open"
      statusLabel.text = "Trace timeline disable failed"
    }
  }

  private fun setTimelineSelected(selected: Boolean) {
    updatingTimelineToggle = true
    try {
      timelineToggle.isSelected = selected
    } finally {
      updatingTimelineToggle = false
    }
  }

  private fun selectMemoryRange() {
    val attached = client ?: return
    val parsed = DebuggerPresentation.parseAddressRange(memoryRange.text)
    if (!parsed.isValid) {
      statusLabel.text = parsed.error
      return
    }
    val range = parsed.value!!
    val available =
        (attached.capabilities.maxInspectionBytes() - MAX_INSTRUCTION_BYTES - STACK_BYTES)
            .coerceAtLeast(0)
    if (range.length > available) {
      statusLabel.text = "Memory range exceeds the $available-byte debugger view limit."
      return
    }
    val request = range.memoryRequest(memorySpace.selectedItem as DebugAddressSpace)
    val validation = validateMemoryRequest(request)
    if (validation != null) {
      statusLabel.text = validation
      return
    }
    selectedMemory = request
    statusLabel.text = "Memory range selected; refreshing coherent view"
    requestRefresh()
  }

  private fun saveBreakpoint(request: DebuggerBreakpointSaveRequest) {
    val attached = client ?: return
    if (!attached.capabilities.supports(request.condition.kind())) {
      statusLabel.text = "${request.condition.kind()} breakpoints are unsupported in this session."
      breakpointPane.commandFailed(statusLabel.text)
      return
    }
    val replacing = request.replacedId
    if (replacing == null && breakpointRows.size >= attached.capabilities.maxBreakpoints()) {
      statusLabel.text = "The session breakpoint limit has been reached."
      breakpointPane.commandFailed(statusLabel.text)
      return
    }
    if (replacing == null && nextBreakpointId == Long.MAX_VALUE) {
      statusLabel.text = "No unused breakpoint identifier remains in this session."
      breakpointPane.commandFailed(statusLabel.text)
      return
    }
    if (replacing != null && breakpointRows.none { it.id() == replacing }) {
      statusLabel.text = "Breakpoint #${replacing.value()} no longer exists; refresh before saving."
      breakpointPane.commandFailed(statusLabel.text)
      return
    }
    val duplicate =
        breakpointRows.firstOrNull {
          it.id() != replacing && it.condition() == request.condition
        }
    if (duplicate != null) {
      statusLabel.text =
          "Breakpoint #${duplicate.id().value()} already uses this condition."
      breakpointPane.commandFailed(statusLabel.text)
      return
    }
    val id = replacing ?: allocateBreakpointId()
    val breakpoint = DebugBreakpoint(id, request.enabled, request.condition)
    val verb = if (replacing == null) "Add" else "Save"
    executeCommand(
        "$verb breakpoint ${id.value()}",
        { attached.setBreakpoint(breakpoint) },
        onFailure = breakpointPane::commandFailed,
    ) {
      breakpointPane.commandSucceeded(
          "${if (replacing == null) "Added" else "Saved"} breakpoint #${id.value()}",
          clearEditor = true,
      )
    }
  }

  private fun toggleBreakpoint(breakpoint: DebugBreakpoint, enabled: Boolean) {
    val attached = client ?: return
    if (commandInFlight || breakpoint.enabled() == enabled) return
    executeCommand(
        if (enabled) "Enable breakpoint ${breakpoint.id().value()}"
        else "Disable breakpoint ${breakpoint.id().value()}",
        { attached.setBreakpoint(breakpoint.withEnabled(enabled)) },
    ) {}
  }

  private fun advanceBreakpointId(values: List<DebugBreakpoint>) {
    val maximum = values.maxOfOrNull { it.id().value() } ?: return
    nextBreakpointId =
        if (maximum == Long.MAX_VALUE) Long.MAX_VALUE
        else maxOf(nextBreakpointId, maximum + 1)
  }

  private fun allocateBreakpointId(): DebugBreakpointId {
    check(nextBreakpointId < Long.MAX_VALUE) { "Breakpoint identifiers are exhausted" }
    return DebugBreakpointId(nextBreakpointId++)
  }

  private fun removeBreakpoint(breakpoint: DebugBreakpoint) {
    val attached = client ?: return
    executeCommand(
        "Remove breakpoint ${breakpoint.id().value()}",
        { attached.removeBreakpoint(breakpoint.id()) },
        onFailure = breakpointPane::commandFailed,
    ) {
      breakpointPane.commandSucceeded(
          "Removed breakpoint #${breakpoint.id().value()}",
          clearEditor = false,
      )
    }
  }

  private fun toggleBreakpointAtCurrentPc(programCounter: Int) {
    val existing = breakpointPane.breakpointsAtProgramCounter(programCounter)
    if (existing.isNotEmpty()) {
      removeBreakpoints(existing)
    } else {
      saveBreakpoint(
          DebuggerBreakpointSaveRequest(
              null,
              true,
              DebuggerBreakpointDraft.ProgramCounter(
                  DebuggerPresentation.formatWord(programCounter)
              ),
              DebugPcCondition.at(programCounter),
          ))
    }
  }

  private fun removeBreakpoints(breakpoints: List<DebugBreakpoint>) {
    val attached = client ?: return
    require(breakpoints.isNotEmpty()) { "At least one breakpoint is required" }
    val ordered = breakpoints.sortedBy { it.id().value() }
    val description =
        if (ordered.size == 1) {
          "breakpoint ${ordered.single().id().value()}"
        } else {
          "${ordered.size} duplicate PC breakpoints"
        }
    executeCommand(
        "Remove $description",
        { removeBreakpointsSequentially(attached, ordered, 0) },
        onFailure = { message ->
          breakpointPane.commandFailed(message)
          // A later removal can fail after an earlier duplicate was removed. Reload the backend
          // list so the authoritative table never conceals that partial progress.
          requestMetadata()
        },
    ) {
      breakpointPane.commandSucceeded(
          if (ordered.size == 1) {
            "Removed breakpoint #${ordered.single().id().value()}"
          } else {
            "Removed ${ordered.size} program-counter breakpoints"
          },
          clearEditor = false,
      )
    }
  }

  private fun removeBreakpointsSequentially(
      attached: DebuggerClient,
      breakpoints: List<DebugBreakpoint>,
      index: Int,
  ): CompletionStage<DebugResult<Void>> =
      attached.removeBreakpoint(breakpoints[index].id()).thenCompose { result ->
        if (result.isFailure || index == breakpoints.lastIndex) {
          CompletableFuture.completedFuture(result)
        } else {
          removeBreakpointsSequentially(attached, breakpoints, index + 1)
        }
      }

  private fun applyCommandSnapshot(value: DebugSnapshot) {
    snapshot = value
    val view = DebuggerPresentation.snapshot(value)
    snapshotLabel.text =
        "${view.identity.label} — ${if (view.paused) "PAUSED" else "RUNNING"} — ${view.timingText}"
    registersArea.text = view.registers.render()
    machineArea.text = renderMachine(value)
    graphicsPane.showNotCaptured(view.identity)
    audioPane.showNotCaptured(view.identity)
    renderBreakpointHit()
  }

  private fun <T> executeCommand(
      label: String,
      operation: () -> CompletionStage<DebugResult<T>>,
      onFailure: (String) -> Unit = {},
      onSuccess: (T?) -> Unit,
  ) {
    requireDebuggerWindowEdt("Debugger command")
    val attached = client ?: return
    if (closed || commandInFlight) return
    val token = token(attached)
    commandInFlight = true
    statusLabel.text = "$label…"
    updateControlState()
    val stage =
        try {
          operation()
        } catch (failure: RuntimeException) {
          commandInFlight = false
          showFailure(label, failure)
          onFailure(statusLabel.text)
          updateControlState()
          return
        }
    stage.whenComplete { result, failure ->
      dispatchSwingMutation {
        if (!sameSession(token)) return@dispatchSwingMutation
        commandInFlight = false
        if (token.epoch == windowEpoch && pollingActive) {
          when {
            failure != null -> {
              showFailure(label, failure)
              onFailure(statusLabel.text)
            }
            result == null -> {
              statusLabel.text = "$label returned no result"
              onFailure(statusLabel.text)
            }
            result.isFailure -> {
              showFailure(label, result)
              onFailure(statusLabel.text)
            }
            else -> {
              lastAppliedCommandRequestId =
                  maxOf(lastAppliedCommandRequestId, token.requestId)
              lastAppliedStateRequestId = maxOf(lastAppliedStateRequestId, token.requestId)
              onSuccess(result.value())
              if (statusLabel.text == "$label…") statusLabel.text = "$label completed"
              requestRefresh()
              requestMetadata()
            }
          }
        }
        updateControlState()
      }
    }
  }

  private fun updateControlState() {
    val attached = client
    val capabilities = attached?.capabilities
    val paused = snapshot?.paused() == true
    val running = snapshot?.paused() == false
    val usable = !closed && attached != null && !commandInFlight
    runButton.isEnabled = usable && capabilities!!.pauseResume() && paused
    pauseButton.isEnabled = usable && capabilities!!.pauseResume() && (running || snapshot == null)
    stepInstructionButton.isEnabled = usable && paused && capabilities!!.instructionStep()
    stepFrameButton.isEnabled = usable && paused && capabilities!!.frameStep()

    val historyView =
        capabilities?.let { DebuggerPresentation.history(it, historyStatus) }
    backInstructionButton.isEnabled =
        usable && paused && historyView?.reverseInstruction?.enabled == true
    backInstructionButton.toolTipText = historyView?.reverseInstruction?.explanation
    backFrameButton.isEnabled = usable && paused && historyView?.reverseFrame?.enabled == true
    backFrameButton.toolTipText = historyView?.reverseFrame?.explanation
    refreshButton.isEnabled =
        !closed &&
            attached != null &&
            canPollInspection(attached) &&
            pollingActive &&
            !refreshInFlight
    historyToggle.isEnabled =
        usable && capabilities?.history()?.checkpointHistory() == true
    memorySpace.isEnabled = usable && paused && capabilities?.coherentInspection() == true
    memoryRange.isEnabled = usable && paused && capabilities?.coherentInspection() == true
    memoryReadButton.isEnabled = usable && paused && capabilities?.coherentInspection() == true

    breakpointPane.setBusy(commandInFlight || metadataInFlight || closed)

    val traceSupported = capabilities?.coherentTraceInspection() == true
    val timelineOwned = traceOwner === attached && !traceDisableRequested
    timelineToggle.isEnabled =
        usable &&
            pollingActive &&
            traceSupported &&
            !traceConfigurationInFlight &&
            (traceOwner == null || traceOwner === attached && !traceDisableRequested)
    timelineCategoryToggles.forEach { (category, toggle) ->
      toggle.isEnabled =
          usable &&
              pollingActive &&
              traceSupported &&
              !timelineOwned &&
              !traceConfigurationInFlight &&
              capabilities.supports(category)
    }
    timelineCapacity.isEnabled =
        usable &&
            pollingActive &&
            traceSupported &&
            !timelineOwned &&
            !traceConfigurationInFlight
    timelineTable.isEnabled = usable && traceSupported
  }

  private fun clearSessionView() {
    sessionLabel.text = "No emulation session"
    snapshotLabel.text = "Waiting for a debug snapshot"
    stopReasonLabel.text = "No breakpoint stop in this session"
    historyLabel.text = "Reverse history status unavailable"
    registersArea.text = "No CPU state"
    machineArea.text = "No machine state"
    disassemblyArea.text = "No disassembly"
    stackArea.text = "No stack"
    memoryArea.text = "No memory"
    graphicsPane.clear()
    audioPane.clear()
    timelineModel.clear()
    traceCursor = -1L
    setTimelineSelected(false)
    timelineWarning.text = "Timeline unavailable without an emulation session"
    updatingHistoryToggle = true
    try {
      historyToggle.isSelected = false
    } finally {
      updatingHistoryToggle = false
    }
  }

  private fun releaseRetainedSessionState() {
    client?.let { releaseTimelineOwnership(it, "Debugger hidden; timeline state released") }
    snapshot = null
    lastBreakpointHit = null
    breakpointRows = emptyList()
    historyStatus = null
    selectedMemory = null
    snapshotOnlyRefreshPending = false
    breakpointPane.clear()
    snapshotLabel.text = "Debugger hidden; retained snapshot released"
    stopReasonLabel.text = "Debugger hidden; breakpoint stop state released"
    historyLabel.text = "Debugger hidden; reverse-history state released"
    registersArea.text = "Debugger hidden; CPU state released"
    machineArea.text = "Debugger hidden; machine state released"
    disassemblyArea.text = "Debugger hidden; disassembly released"
    stackArea.text = "Debugger hidden; stack state released"
    memoryArea.text = "Debugger hidden; memory view released"
    graphicsPane.clear()
    audioPane.clear()
    updatingHistoryToggle = true
    try {
      historyToggle.isSelected = false
    } finally {
      updatingHistoryToggle = false
    }
    statusLabel.text = "Debugger hidden; retained state released"
  }

  private fun showFailure(operation: String, result: DebugResult<*>) {
    val error = result.error()
    statusLabel.text = "$operation failed — ${error.code()}: ${error.message()}"
  }

  private fun showFailure(operation: String, failure: Throwable) {
    LOG.warn("Debugger operation failed unexpectedly: $operation", failure)
    statusLabel.text = "$operation failed — unexpected internal error"
  }

  private fun token(attached: DebuggerClient): RequestToken =
      RequestToken(windowEpoch, attached, attached.generation, nextRequestId++)

  private fun sameSession(token: RequestToken): Boolean =
      !closed && client === token.client && latestGeneration == token.generation

  private fun syncPollingTimer() {
    val attached = client
    if (pollingActive && attached != null && canPollInspection(attached)) {
      pollingTimer.start()
    } else {
      pollingTimer.stop()
    }
  }

  private fun cancelPeripheralPreparation() {
    peripheralPreparationTask?.cancel(true)
    peripheralPreparationTask = null
    peripheralPreparationRequestId = 0L
  }

  private fun canPollInspection(attached: DebuggerClient): Boolean =
      attached.capabilities.coherentInspection() ||
          selectedInspectionSections(attached).isNotEmpty() ||
          (traceOwner === attached &&
              !traceDisableRequested &&
              attached.capabilities.coherentTraceInspection())

  override fun close() {
    requireDebuggerWindowEdt("Debugger panel disposal")
    if (closed) return
    val attached = client
    closed = true
    client = null
    attached?.let { releaseTimelineOwnership(it, "Debugger closed; timeline state released") }
    pollingActive = false
    windowEpoch++
    pollingTimer.stop()
    cancelPeripheralPreparation()
    refreshInFlight = false
    activeRefreshRequestId = 0L
    if (ownsPeripheralExecutor) peripheralExecutor.shutdownNow()
    snapshot = null
    lastBreakpointHit = null
    breakpointRows = emptyList()
    historyStatus = null
    selectedMemory = null
    snapshotOnlyRefreshPending = false
    breakpointPane.clear()
    clearSessionView()
    statusLabel.text = "Debugger closed"
    updateControlState()
  }

  private data class RequestToken(
      val epoch: Long,
      val client: DebuggerClient,
      val generation: Long,
      val requestId: Long,
  )

  private sealed interface RefreshCompletion {
    data class Success(val inspection: PreparedInspection) : RefreshCompletion

    data class TypedFailure(val result: DebugResult<*>) : RefreshCompletion

    data class Unexpected(val failure: Throwable) : RefreshCompletion

    data object Empty : RefreshCompletion
  }

  private data class PreparedInspection(
      val snapshot: DebugSnapshot,
      val request: DebugInspectionRequest,
      val anchoredBlocks: List<DebugMemoryBlock>,
      val memoryBlocks: List<DebugMemoryBlock>,
      val trace: TraceReadResult?,
      val graphics: DebuggerGraphicsPaneView?,
      val audio: DebuggerAudioPaneView?,
  ) {
    companion object {
      fun withoutPeripherals(value: DebugInspectionResult): PreparedInspection {
        require(value.graphics().isEmpty && value.audio().isEmpty)
        return from(value, null, null)
      }

      fun prepare(value: DebugInspectionResult): PreparedInspection =
          DebuggerSnapshotIdentity.from(value.snapshot()).let { identity ->
            from(
                value,
                value.graphics().map { inspection ->
                  DebuggerPeripheralPanePreparation.graphics(identity, inspection)
                }.orElse(null),
                value.audio().map { inspection ->
                  DebuggerPeripheralPanePreparation.audio(identity, inspection)
                }.orElse(null),
            )
          }

      private fun from(
          value: DebugInspectionResult,
          graphics: DebuggerGraphicsPaneView?,
          audio: DebuggerAudioPaneView?,
      ): PreparedInspection =
          PreparedInspection(
              value.snapshot(),
              value.request(),
              value.anchoredBlocks().toList(),
              value.memoryBlocks().toList(),
              value.trace().orElse(null),
              graphics,
              audio,
          )
    }
  }

  private data class InspectionPlan(
      val request: DebugInspectionRequest,
      val codeIndex: Int?,
      val stackIndex: Int?,
      val includesExplicitMemory: Boolean,
      val replanAfterSnapshot: Boolean,
  )

  private data class MetadataResult(
      val breakpoints: DebugResult<DebugBreakpointList>,
      val history: DebugResult<DebugHistoryStatus>?,
      val lastHit: DebugResult<DebugBreakpointHit>?,
  )

  private companion object {
    val LOG = LoggerFactory.getLogger(DebuggerPanel::class.java)
    const val DEFAULT_POLLING_INTERVAL_MILLIS = 400
    const val MAX_INSTRUCTION_BYTES = 3
    const val STACK_BYTES = 16
    const val TRACE_PAGE_SIZE = 256
    const val MAX_TRACE_DISABLE_RETRIES = 2
    const val NO_GENERATION = -1L
    const val ACTION_REFRESH = "debugger-refresh"
    const val ACTION_RUN_CONTROL = "debugger-run-control"
    const val ACTION_STEP_INSTRUCTION = "debugger-step-instruction"
    const val ACTION_STEP_FRAME = "debugger-step-frame"
    const val ACTION_BACK_INSTRUCTION = "debugger-back-instruction"
    const val ACTION_BACK_FRAME = "debugger-back-frame"
    const val ACTION_TOGGLE_PC_BREAKPOINT = "debugger-toggle-pc-breakpoint"
    const val ACTION_ZOOM_IN = "debugger-zoom-in"
    const val ACTION_ZOOM_OUT = "debugger-zoom-out"
    const val ACTION_ZOOM_RESET = "debugger-zoom-reset"
    const val ACTION_COPY = "debugger-copy"
    val SAFE_MEMORY_SPACES =
        arrayOf(
            DebugAddressSpace.SYSTEM_BUS,
            DebugAddressSpace.ROM,
            DebugAddressSpace.WORK_RAM,
            DebugAddressSpace.HIGH_RAM,
        )
  }
}

private class DebuggerFontScaler(root: JComponent) {
  private data class FontBaseline(val font: Font, val rowHeight: Int?)

  private val baselines = IdentityHashMap<Component, FontBaseline>()
  var scalePercent: Int = DebuggerUiPreferences.DEFAULT_FONT_SCALE_PERCENT
    private set

  init {
    capture(root)
  }

  fun capture(component: Component) {
    component.font?.let { font ->
      baselines.putIfAbsent(component, FontBaseline(font, (component as? JTable)?.rowHeight))
    }
    (component as? Container)?.components?.forEach(::capture)
  }

  fun apply(requestedPercent: Int) {
    scalePercent =
        requestedPercent.coerceIn(
            DebuggerUiPreferences.MIN_FONT_SCALE_PERCENT,
            DebuggerUiPreferences.MAX_FONT_SCALE_PERCENT,
        )
    val factor = scalePercent / 100f
    baselines.forEach { (component, baseline) ->
      component.font = baseline.font.deriveFont((baseline.font.size2D * factor).coerceAtLeast(1f))
      if (component is JTable && baseline.rowHeight != null) {
        component.rowHeight = (baseline.rowHeight * factor).toInt().coerceAtLeast(1)
      }
    }
  }
}

private fun debuggerLabel(text: String, target: Component, mnemonic: Int): JLabel =
    JLabel(text).apply {
      labelFor = target
      displayedMnemonic = mnemonic
    }

private fun applyDivider(location: Int, split: JSplitPane) {
  if (location >= 0) split.dividerLocation = location
}

private fun categoryLabel(category: TraceCategory): String =
    category.name.lowercase().replace('_', ' ').replaceFirstChar { value -> value.titlecase() }

private fun copyDebuggerText(text: String) {
  val selection = StringSelection(text)
  Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
}

internal fun debuggerMenuShortcutMask(): Int =
    runCatching { Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx }
        .getOrDefault(InputEvent.CTRL_DOWN_MASK)

private val DEBUGGER_PERIPHERAL_WORKER_IDS = AtomicInteger()

private fun newDebuggerPeripheralExecutor(): ExecutorService =
    Executors.newSingleThreadExecutor(
        ThreadFactory { task ->
          Thread(
                  task,
                  "coffee-gb-debugger-peripheral-${DEBUGGER_PERIPHERAL_WORKER_IDS.incrementAndGet()}",
              )
              .apply { isDaemon = true }
        })

private fun debuggerComponentText(component: Component?): String =
    buildList {
          fun visit(value: Component) {
            when (value) {
              is JTextArea -> if (value.text.isNotBlank()) add(value.text)
              is JLabel -> if (value.text.isNotBlank()) add(value.text)
              is JTable -> {
                val model = value.model
                if (model.rowCount > 0) {
                  add(
                      buildString {
                        append((0 until model.columnCount).joinToString("\t") { model.getColumnName(it) })
                        for (row in 0 until model.rowCount) {
                          append('\n')
                          append(
                              (0 until model.columnCount).joinToString("\t") { column ->
                                model.getValueAt(row, column)?.toString().orEmpty()
                              })
                        }
                      })
                }
              }
            }
            (value as? Container)?.components?.forEach(::visit)
          }
          component?.let(::visit)
        }
        .distinct()
        .joinToString("\n\n")

private fun debuggerTextArea(name: String, rows: Int, columns: Int): JTextArea =
    JTextArea(rows, columns).apply {
      isEditable = false
      lineWrap = false
      font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
      accessibleContext.accessibleName = name
      caretPosition = 0
    }

private fun scroll(component: Component): JScrollPane = JScrollPane(component)

private fun DebuggerRegisterView.render(): String =
    """
    A  $a    F  $f    AF $af
    B  $b    C  $c    BC $bc
    D  $d    E  $e    DE $de
    H  $h    L  $l    HL $hl
    SP $sp
    PC $pc
    Flags $compactFlags  ($flags)
    """.trimIndent()

private fun renderMachine(snapshot: DebugSnapshot): String {
  val execution = snapshot.execution()
  val interrupts = snapshot.interrupts()
  val timer = snapshot.timer()
  val ppu = snapshot.ppu()
  val apu = snapshot.apu()
  val mapper = snapshot.mapper()
  return """
    CPU       ${execution.cpuState()}  opcode=${execution.opcode()} cb=${execution.extendedOpcode()} cycle=${execution.machineCycle()}
    Retired   ${execution.retiredInstructions()}  speed=${if (execution.doubleSpeed()) "double" else "normal"}  haltBug=${execution.haltBug()}
    Interrupt IME=${interrupts.ime()} pending=${DebuggerPresentation.formatByte(interrupts.pendingFlags())} IF=${DebuggerPresentation.formatByte(interrupts.requestFlags())} IE=${DebuggerPresentation.formatByte(interrupts.enableFlags())}
    Timer     DIV=${DebuggerPresentation.formatWord(timer.dividerCounter())} TIMA=${DebuggerPresentation.formatByte(timer.tima())} TMA=${DebuggerPresentation.formatByte(timer.tma())} TAC=${DebuggerPresentation.formatByte(timer.tac())}
    PPU       ${ppu.mode()} LY=${ppu.line()} dot=${ppu.dot()} LCD=${if (ppu.lcdEnabled()) "on" else "off"}
    APU       ${if (apu.enabled()) "on" else "off"} channels=${listOf(apu.channel1Enabled(), apu.channel2Enabled(), apu.channel3Enabled(), apu.channel4Enabled()).map { if (it) 1 else 0 }.joinToString("")}
    Mapper    ${mapper.mapperId()} ROM bank=${mapper.romBank()} RAM bank=${mapper.ramBank()} RAM=${mapper.ramEnabled()}
  """.trimIndent()
}

private fun DebuggerMemoryView.render(): String =
    buildString {
      append(identity.label)
      append(" — ")
      append(addressSpace)
      append(" — ")
      append(length)
      append(" bytes\n")
      coherenceExplanation?.let { append("WARNING: $it\n") }
      rows.forEach { row ->
        append(row.addressText)
        append(": ")
        append(row.hexText.padEnd(47))
        append("  ")
        append(row.asciiText)
        append('\n')
      }
    }.trimEnd()

private fun DebuggerStackView.render(): String =
    if (!available) {
      explanation!!
    } else {
      buildString {
        append(identity.label)
        append('\n')
        entries.forEach { entry ->
          append(if (entry.offset == 0) "SP -> " else "      ")
          append(entry.addressText)
          append("  ")
          append(entry.valueText)
          append('\n')
        }
        explanation?.let { append(it) }
      }.trimEnd()
    }

private fun DebuggerCapabilityView.summary(): String =
    buildList {
          if (pauseResume) add("run control")
          if (instructionStep) add("instruction step")
          if (frameStep) add("frame step")
          if (memoryRead) add("memory")
          if (maxBreakpoints > 0) add("breakpoints")
          if (reverseHistory) add("reverse history")
        }
        .ifEmpty { listOf("inspection unavailable") }
        .joinToString(", ")

private fun pcAnchoredLength(address: Int, maximum: Int): Int? {
  val endExclusive =
      when (address) {
        in 0x0000..0x7fff -> 0x8000
        in 0xc000..0xfdff -> 0xfe00
        in 0xff80..0xfffe -> 0xffff
        else -> return null
      }
  return minOf(maximum, endExclusive - address).takeIf { it > 0 }
}

private fun stackAnchoredLength(address: Int, maximum: Int): Int? {
  val endExclusive =
      when (address) {
        in 0xc000..0xfdff -> 0xfe00
        in 0xff80..0xfffe -> 0xffff
        else -> return null
      }
  return minOf(maximum, endExclusive - address).takeIf { it > 0 }
}

private fun validateMemoryRequest(request: DebugMemoryRequest): String? {
  val start = request.address()
  val end = request.endExclusive()
  val valid =
      when (request.addressSpace()) {
        DebugAddressSpace.ROM -> start >= 0 && end <= 0x10000
        DebugAddressSpace.WORK_RAM -> start >= 0xc000 && end <= 0xfe00
        DebugAddressSpace.HIGH_RAM -> start >= 0xff80 && end <= 0xffff
        DebugAddressSpace.SYSTEM_BUS ->
            start >= 0xc000 && end <= 0xfe00 || start >= 0xff80 && end <= 0xffff
        else -> false
      }
  return if (valid) null
  else "The selected range is outside this side-effect-free address-space view."
}

private fun <T> completedResult(value: T): CompletionStage<T> =
    CompletableFuture.completedFuture(value).minimalCompletionStage()

private fun requireDebuggerWindowEdt(operation: String) {
  check(SwingUtilities.isEventDispatchThread()) {
    "$operation must run on the Event Dispatch Thread"
  }
}
