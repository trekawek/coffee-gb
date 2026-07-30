package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.controller.Controller
import eu.rekawek.coffeegb.core.debug.DebugAddressSpace
import eu.rekawek.coffeegb.core.debug.DebugAnchoredMemoryRequest
import eu.rekawek.coffeegb.core.debug.DebugBreakpointList
import eu.rekawek.coffeegb.core.debug.DebugCapabilities
import eu.rekawek.coffeegb.core.debug.DebugDisassembler
import eu.rekawek.coffeegb.core.debug.DebugErrorCode
import eu.rekawek.coffeegb.core.debug.DebugInspectionAnchor
import eu.rekawek.coffeegb.core.debug.DebugInspectionRequest
import eu.rekawek.coffeegb.core.debug.DebugInspectionResult
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess
import eu.rekawek.coffeegb.core.debug.DebugMemoryRequest
import eu.rekawek.coffeegb.core.debug.DebugPort
import eu.rekawek.coffeegb.core.debug.DebugResult
import eu.rekawek.coffeegb.core.debug.DebugSnapshot
import eu.rekawek.coffeegb.core.debug.DebugStepKind
import eu.rekawek.coffeegb.core.debug.DebugStepResult
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryConfiguration
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryStatus
import eu.rekawek.coffeegb.core.debug.history.DebugReverseStepResult
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.table.AbstractTableModel
import org.slf4j.LoggerFactory

/** Modeless desktop debugger retained by [DesktopDebuggerController]. */
internal class DebuggerWindow(owner: JFrame) : DesktopDebuggerView {
  private val panel = DebuggerPanel()
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
            panel.setPollingActive(false)
          }

          override fun windowClosed(event: WindowEvent) {
            panel.setPollingActive(false)
          }
        })
    dialog.addComponentListener(
        object : ComponentAdapter() {
          override fun componentShown(event: ComponentEvent) {
            panel.setPollingActive(true)
          }

          override fun componentHidden(event: ComponentEvent) {
            panel.setPollingActive(false)
          }
        })
    dialog.pack()
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
    panel.close()
    dialog.dispose()
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

  fun historyStatus(): CompletionStage<DebugResult<DebugHistoryStatus>>

  fun listBreakpoints(): CompletionStage<DebugResult<DebugBreakpointList>>

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

  override fun listBreakpoints(): CompletionStage<DebugResult<DebugBreakpointList>> =
      port.listBreakpoints()

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
) : JPanel(BorderLayout(6, 6)), AutoCloseable {
  internal val sessionLabel = JLabel("No emulation session")
  internal val snapshotLabel = JLabel("Waiting for a debug snapshot")
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
  internal val breakpointKind = JComboBox(BreakpointEditorKind.entries.toTypedArray())
  internal val breakpointRange = JTextField("\$0100", 13)
  internal val breakpointValue = JTextField("", 4)
  internal val breakpointMask = JTextField("", 4)
  internal val breakpointAddButton = JButton("Add")
  internal val breakpointRemoveButton = JButton("Remove")
  internal val breakpointModel = DebuggerBreakpointTableModel(::toggleBreakpoint)
  internal val breakpointTable = JTable(breakpointModel)
  internal val historyLabel = JLabel("Reverse history status unavailable")

  private val pollingTimer =
      Timer(pollingIntervalMillis) { requestRefresh() }.apply { isRepeats = true }
  private var client: DebuggerClient? = null
  private var latestGeneration = NO_GENERATION
  private var snapshot: DebugSnapshot? = null
  private var historyStatus: DebugHistoryStatus? = null
  private var selectedMemory: DebugMemoryRequest? = null
  private var windowEpoch = 0L
  private var nextRequestId = 1L
  private var refreshInFlight = false
  private var refreshAgain = false
  private var snapshotOnlyRefreshPending = false
  private var metadataInFlight = false
  private var metadataAgain = false
  private var commandInFlight = false
  private var pollingActive = false
  private var updatingHistoryToggle = false
  private var closed = false
  private var lastAppliedStateRequestId = 0L

  init {
    requireDebuggerWindowEdt("Debugger panel construction")
    require(pollingIntervalMillis > 0) { "Debugger polling interval must be positive" }
    border = BorderFactory.createEmptyBorder(6, 6, 6, 6)

    sessionLabel.accessibleContext.accessibleName = "Debugger session"
    snapshotLabel.accessibleContext.accessibleName = "Debugger snapshot identity"
    statusLabel.accessibleContext.accessibleName = "Debugger status"
    historyLabel.accessibleContext.accessibleName = "Reverse history status"

    val header = JPanel(GridLayout(0, 1, 2, 2))
    header.add(sessionLabel)
    header.add(snapshotLabel)
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

    val tabs = JTabbedPane()
    tabs.accessibleContext.accessibleName = "Debugger panes"
    tabs.addTab("CPU", cpuPanel())
    tabs.addTab("Memory", memoryPanel())
    tabs.addTab("Breakpoints", breakpointPanel())
    add(tabs, BorderLayout.CENTER)

    statusLabel.border = BorderFactory.createEmptyBorder(3, 4, 2, 4)
    add(statusLabel, BorderLayout.SOUTH)

    runButton.accessibleContext.accessibleDescription =
        "Release the debugger-owned pause; an application-owned pause may remain"
    runButton.toolTipText =
        "Release the debugger-owned pause; an application-owned pause may remain"
    pauseButton.accessibleContext.accessibleDescription = "Pause at the next safe point"
    stepInstructionButton.accessibleContext.accessibleDescription = "Execute one CPU instruction"
    stepFrameButton.accessibleContext.accessibleDescription = "Run to the next frame boundary"
    backInstructionButton.accessibleContext.accessibleDescription =
        "Restore the previous recorded instruction boundary"
    backFrameButton.accessibleContext.accessibleDescription =
        "Restore the previous recorded frame boundary"
    refreshButton.accessibleContext.accessibleDescription = "Refresh the coherent debugger view"
    historyToggle.accessibleContext.accessibleDescription =
        "Enable or disable bounded reverse-execution history"

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
    memoryReadButton.addActionListener { selectMemoryRange() }
    breakpointAddButton.addActionListener { addBreakpoint() }
    breakpointRemoveButton.addActionListener { removeSelectedBreakpoint() }
    breakpointTable.selectionModel.addListSelectionListener { updateControlState() }
    breakpointKind.addActionListener { updateBreakpointEditor() }

    updateBreakpointEditor()
    clearSessionView()
    updateControlState()
  }

  private fun cpuPanel(): Component {
    val scalar = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll(registersArea), scroll(machineArea))
    scalar.resizeWeight = 0.34
    scalar.isContinuousLayout = true
    val codeAndStack =
        JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll(disassemblyArea), scroll(stackArea))
    codeAndStack.resizeWeight = 0.7
    codeAndStack.isContinuousLayout = true
    return JSplitPane(JSplitPane.VERTICAL_SPLIT, scalar, codeAndStack).apply {
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
    controls.add(JLabel("Space:"))
    controls.add(memorySpace)
    controls.add(JLabel("Range:"))
    controls.add(memoryRange)
    controls.add(memoryReadButton)
    return JPanel(BorderLayout(4, 4)).apply {
      add(controls, BorderLayout.NORTH)
      add(scroll(memoryArea), BorderLayout.CENTER)
    }
  }

  private fun breakpointPanel(): Component {
    breakpointTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
    breakpointTable.fillsViewportHeight = true
    breakpointTable.autoCreateRowSorter = false
    breakpointTable.accessibleContext.accessibleName = "Breakpoints and watchpoints"
    breakpointTable.columnModel.getColumn(0).preferredWidth = 65
    breakpointTable.columnModel.getColumn(1).preferredWidth = 70
    breakpointTable.columnModel.getColumn(2).preferredWidth = 140
    breakpointTable.columnModel.getColumn(3).preferredWidth = 420

    breakpointKind.accessibleContext.accessibleName = "Breakpoint type"
    breakpointRange.accessibleContext.accessibleName = "Breakpoint address or range"
    breakpointValue.accessibleContext.accessibleName = "Optional watchpoint byte value"
    breakpointMask.accessibleContext.accessibleName = "Optional watchpoint byte mask"
    val editor = JPanel(FlowLayout(FlowLayout.LEADING))
    editor.add(JLabel("Type:"))
    editor.add(breakpointKind)
    editor.add(JLabel("Address/range:"))
    editor.add(breakpointRange)
    editor.add(JLabel("Value:"))
    editor.add(breakpointValue)
    editor.add(JLabel("Mask:"))
    editor.add(breakpointMask)
    editor.add(breakpointAddButton)
    editor.add(breakpointRemoveButton)
    return JPanel(BorderLayout(4, 4)).apply {
      add(editor, BorderLayout.NORTH)
      add(JScrollPane(breakpointTable), BorderLayout.CENTER)
    }
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
    latestGeneration = generation
    client = nextClient
    windowEpoch++
    snapshot = null
    historyStatus = null
    selectedMemory = null
    refreshInFlight = false
    refreshAgain = false
    snapshotOnlyRefreshPending = false
    metadataInFlight = false
    metadataAgain = false
    commandInFlight = false
    lastAppliedStateRequestId = 0L
    breakpointModel.replace(emptyList(), null)
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
      requestRefresh()
      requestMetadata()
    } else {
      syncPollingTimer()
      refreshAgain = false
      metadataAgain = false
      releaseRetainedSessionState()
    }
    updateControlState()
  }

  internal fun requestRefresh() {
    requireDebuggerWindowEdt("Debugger refresh")
    val attached = client ?: return
    if (closed || !pollingActive || !attached.capabilities.coherentInspection()) return
    if (refreshInFlight) {
      refreshAgain = true
      return
    }
    val plan = inspectionPlan(attached)
    val token = token(attached)
    refreshInFlight = true
    updateControlState()
    val stage =
        try {
          attached.inspect(plan.request)
        } catch (failure: RuntimeException) {
          refreshInFlight = false
          showFailure("Refresh", failure)
          updateControlState()
          return
        }
    stage.whenComplete { result, failure ->
      dispatchSwingMutation {
        if (!sameSession(token)) return@dispatchSwingMutation
        refreshInFlight = false
        if (token.epoch == windowEpoch && pollingActive) {
          when {
            failure != null -> showFailure("Refresh", failure)
            result == null -> statusLabel.text = "Refresh returned no result"
            result.isFailure -> {
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
            else -> {
              val inspection = result.value()
              when {
                inspection.request() != plan.request ->
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
    }
  }

  private fun inspectionPlan(attached: DebuggerClient): InspectionPlan {
    if (snapshotOnlyRefreshPending) {
      snapshotOnlyRefreshPending = false
      return InspectionPlan(DebugInspectionRequest(emptyList(), emptyList()), null, null, false)
    }
    val anchored = ArrayList<DebugAnchoredMemoryRequest>(2)
    var codeIndex: Int? = null
    var stackIndex: Int? = null
    var remainingBytes = attached.capabilities.maxInspectionBytes()
    val previous = snapshot
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
        DebugInspectionRequest(anchored, explicit),
        codeIndex,
        stackIndex,
        explicit.isNotEmpty(),
    )
  }

  private fun applyInspection(plan: InspectionPlan, result: DebugInspectionResult) {
    requireDebuggerWindowEdt("Debugger inspection rendering")
    val wasRunning = snapshot?.paused() == false
    val view = DebuggerPresentation.snapshot(result.snapshot())
    snapshot = result.snapshot()
    snapshotLabel.text =
        "${view.identity.label} — ${if (view.paused) "PAUSED" else "RUNNING"} — ${view.timingText}"
    registersArea.text = view.registers.render()
    machineArea.text = renderMachine(result.snapshot())

    if (!view.paused) {
      disassemblyArea.text = "Paused-only view; instruction bytes are not sampled while running."
      stackArea.text = "Paused-only view; stack bytes are not sampled while running."
      memoryArea.text = "Paused-only view; memory is blank while the emulator is running."
    } else {
      val identity = view.identity
      val code = plan.codeIndex?.let { result.anchoredBlocks()[it] }
      disassemblyArea.text =
          code?.let {
            runCatching { DebugDisassembler.disassemble(it) }
                .getOrElse { failure -> "Disassembly unavailable: ${failure.message}" }
          } ?: "Current PC is outside the side-effect-free ROM/WRAM/HRAM inspection views."

      val stackCapture =
          plan.stackIndex?.let {
            DebuggerMemoryCapture(identity, result.anchoredBlocks()[it])
          }
      stackArea.text =
          DebuggerPresentation.stack(result.snapshot(), client!!.capabilities, stackCapture, STACK_BYTES)
              .render()

      memoryArea.text =
          if (plan.includesExplicitMemory) {
            val capture = DebuggerMemoryCapture(identity, result.memoryBlocks().single())
            DebuggerPresentation.memory(identity, capture).render()
          } else {
            "Choose a bounded range and select Read."
          }
    }
    statusLabel.text = "Snapshot refreshed"

    // A first paused snapshot deliberately carries no register-relative ranges. Queue exactly one
    // follow-up so those ranges are resolved against their own coherent snapshot.
    if (view.paused && plan.request.blockCount() == 0 && client?.capabilities?.memoryRead() == true) {
      refreshAgain = true
    }
    if (wasRunning && view.paused) requestMetadata()
    updateControlState()
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
    val breakpoints =
        if (attached.capabilities.breakpoints()) attached.listBreakpoints()
        else completedResult(DebugResult.success(DebugBreakpointList(emptyList())))
    val history =
        if (attached.capabilities.history().checkpointHistory()) attached.historyStatus()
        else completedResult<DebugResult<DebugHistoryStatus>?>(null)
    breakpoints.thenCombine(history) { breakpointResult, historyResult ->
          MetadataResult(breakpointResult, historyResult)
        }
        .whenComplete { result, failure ->
          dispatchSwingMutation {
            if (!sameSession(token)) return@dispatchSwingMutation
            metadataInFlight = false
            if (token.epoch == windowEpoch && pollingActive) {
              when {
                failure != null -> showFailure("Metadata refresh", failure)
                result == null -> statusLabel.text = "Metadata refresh returned no result"
                result.breakpoints.isFailure -> showFailure("Breakpoint refresh", result.breakpoints)
                else -> {
                  breakpointModel.replace(
                      result.breakpoints.value().breakpoints(),
                      attached.capabilities,
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

  private fun addBreakpoint() {
    val attached = client ?: return
    val editorKind = breakpointKind.selectedItem as BreakpointEditorKind
    val parsed: DebuggerParsedValue<out DebugBreakpointCondition> =
        if (editorKind == BreakpointEditorKind.PROGRAM_COUNTER) {
          DebuggerPresentation.parseProgramCounterCondition(breakpointRange.text)
        } else {
          DebuggerPresentation.parseMemoryCondition(
              breakpointRange.text,
              editorKind.access!!,
              breakpointValue.text,
              breakpointMask.text,
          )
        }
    if (!parsed.isValid) {
      statusLabel.text = parsed.error
      return
    }
    val condition = parsed.value!!
    if (!attached.capabilities.supports(condition.kind())) {
      statusLabel.text = "${condition.kind()} breakpoints are unsupported in this session."
      return
    }
    if (breakpointModel.rowCount >= attached.capabilities.maxBreakpoints()) {
      statusLabel.text = "The session breakpoint limit has been reached."
      return
    }
    val id = breakpointModel.nextId()
    val breakpoint = DebugBreakpoint(DebugBreakpointId(id), true, condition)
    executeCommand("Add breakpoint $id", { attached.setBreakpoint(breakpoint) }) {}
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

  private fun removeSelectedBreakpoint() {
    val attached = client ?: return
    val row = breakpointTable.selectedRow
    if (row < 0) return
    val modelRow = breakpointTable.convertRowIndexToModel(row)
    val breakpoint = breakpointModel.breakpointAt(modelRow)
    executeCommand(
        "Remove breakpoint ${breakpoint.id().value()}",
        { attached.removeBreakpoint(breakpoint.id()) },
    ) {}
  }

  private fun updateBreakpointEditor() {
    val memory = (breakpointKind.selectedItem as? BreakpointEditorKind)?.access != null
    breakpointValue.isEnabled = memory
    breakpointMask.isEnabled = memory
    updateControlState()
  }

  private fun applyCommandSnapshot(value: DebugSnapshot) {
    snapshot = value
    val view = DebuggerPresentation.snapshot(value)
    snapshotLabel.text =
        "${view.identity.label} — ${if (view.paused) "PAUSED" else "RUNNING"} — ${view.timingText}"
    registersArea.text = view.registers.render()
    machineArea.text = renderMachine(value)
  }

  private fun <T> executeCommand(
      label: String,
      operation: () -> CompletionStage<DebugResult<T>>,
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
          updateControlState()
          return
        }
    stage.whenComplete { result, failure ->
      dispatchSwingMutation {
        if (!sameSession(token)) return@dispatchSwingMutation
        commandInFlight = false
        if (token.epoch == windowEpoch && pollingActive) {
          when {
            failure != null -> showFailure(label, failure)
            result == null -> statusLabel.text = "$label returned no result"
            result.isFailure -> showFailure(label, result)
            else -> {
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
            capabilities!!.coherentInspection() &&
            pollingActive &&
            !refreshInFlight
    historyToggle.isEnabled =
        usable && capabilities?.history()?.checkpointHistory() == true
    memorySpace.isEnabled = usable && paused && capabilities?.coherentInspection() == true
    memoryRange.isEnabled = usable && paused && capabilities?.coherentInspection() == true
    memoryReadButton.isEnabled = usable && paused && capabilities?.coherentInspection() == true

    val editorKind = breakpointKind.selectedItem as? BreakpointEditorKind
    val editorSupported =
        when {
          editorKind == null || capabilities == null -> false
          editorKind == BreakpointEditorKind.PROGRAM_COUNTER ->
              capabilities.supports(DebugBreakpointKind.PROGRAM_COUNTER)
          else -> capabilities.supports(DebugBreakpointKind.MEMORY)
        }
    breakpointKind.isEnabled = usable && capabilities?.breakpoints() == true
    breakpointRange.isEnabled = usable && editorSupported
    breakpointValue.isEnabled = usable && editorSupported && editorKind?.access != null
    breakpointMask.isEnabled = usable && editorSupported && editorKind?.access != null
    breakpointAddButton.isEnabled = usable && editorSupported
    breakpointRemoveButton.isEnabled =
        usable && capabilities?.breakpoints() == true && breakpointTable.selectedRow >= 0
    breakpointTable.isEnabled = usable && capabilities?.breakpoints() == true
  }

  private fun clearSessionView() {
    sessionLabel.text = "No emulation session"
    snapshotLabel.text = "Waiting for a debug snapshot"
    historyLabel.text = "Reverse history status unavailable"
    registersArea.text = "No CPU state"
    machineArea.text = "No machine state"
    disassemblyArea.text = "No disassembly"
    stackArea.text = "No stack"
    memoryArea.text = "No memory"
    updatingHistoryToggle = true
    try {
      historyToggle.isSelected = false
    } finally {
      updatingHistoryToggle = false
    }
  }

  private fun releaseRetainedSessionState() {
    snapshot = null
    historyStatus = null
    selectedMemory = null
    snapshotOnlyRefreshPending = false
    breakpointModel.replace(emptyList(), client?.capabilities)
    snapshotLabel.text = "Debugger hidden; retained snapshot released"
    historyLabel.text = "Debugger hidden; reverse-history state released"
    registersArea.text = "Debugger hidden; CPU state released"
    machineArea.text = "Debugger hidden; machine state released"
    disassemblyArea.text = "Debugger hidden; disassembly released"
    stackArea.text = "Debugger hidden; stack state released"
    memoryArea.text = "Debugger hidden; memory view released"
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
    if (pollingActive && client?.capabilities?.coherentInspection() == true) {
      pollingTimer.start()
    } else {
      pollingTimer.stop()
    }
  }

  override fun close() {
    requireDebuggerWindowEdt("Debugger panel disposal")
    if (closed) return
    closed = true
    pollingActive = false
    windowEpoch++
    pollingTimer.stop()
    client = null
    snapshot = null
    historyStatus = null
    selectedMemory = null
    snapshotOnlyRefreshPending = false
    breakpointModel.replace(emptyList(), null)
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

  private data class InspectionPlan(
      val request: DebugInspectionRequest,
      val codeIndex: Int?,
      val stackIndex: Int?,
      val includesExplicitMemory: Boolean,
  )

  private data class MetadataResult(
      val breakpoints: DebugResult<DebugBreakpointList>,
      val history: DebugResult<DebugHistoryStatus>?,
  )

  private companion object {
    val LOG = LoggerFactory.getLogger(DebuggerPanel::class.java)
    const val DEFAULT_POLLING_INTERVAL_MILLIS = 400
    const val MAX_INSTRUCTION_BYTES = 3
    const val STACK_BYTES = 16
    const val NO_GENERATION = -1L
    val SAFE_MEMORY_SPACES =
        arrayOf(
            DebugAddressSpace.SYSTEM_BUS,
            DebugAddressSpace.ROM,
            DebugAddressSpace.WORK_RAM,
            DebugAddressSpace.HIGH_RAM,
        )
  }
}

internal enum class BreakpointEditorKind(
    private val label: String,
    val access: DebugMemoryAccess?,
) {
  PROGRAM_COUNTER("Program counter", null),
  READ("Memory read", DebugMemoryAccess.READ),
  WRITE("Memory write", DebugMemoryAccess.WRITE),
  EXECUTE("Memory execute", DebugMemoryAccess.EXECUTE);

  override fun toString(): String = label
}

internal class DebuggerBreakpointTableModel(
    private val onToggle: (DebugBreakpoint, Boolean) -> Unit,
) : AbstractTableModel() {
  private var breakpoints: List<DebugBreakpoint> = emptyList()
  private var rows: List<DebuggerBreakpointRow> = emptyList()

  override fun getRowCount(): Int = rows.size

  override fun getColumnCount(): Int = COLUMNS.size

  override fun getColumnName(column: Int): String = COLUMNS[column]

  override fun getColumnClass(columnIndex: Int): Class<*> =
      if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java

  override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean =
      columnIndex == 0 && rows[rowIndex].supported

  override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
    val row = rows[rowIndex]
    return when (columnIndex) {
      0 -> row.enabled
      1 -> row.id.toString()
      2 -> row.kind
      3 -> row.condition
      else -> error("Unknown breakpoint column: $columnIndex")
    }
  }

  override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
    if (columnIndex != 0 || value !is Boolean) return
    onToggle(breakpoints[rowIndex], value)
  }

  fun breakpointAt(row: Int): DebugBreakpoint = breakpoints[row]

  fun nextId(): Long {
    val maximum = breakpoints.maxOfOrNull { it.id().value() } ?: -1L
    check(maximum < Long.MAX_VALUE) { "Breakpoint identifiers are exhausted" }
    return maximum + 1
  }

  fun replace(values: List<DebugBreakpoint>, capabilities: DebugCapabilities?) {
    breakpoints = values.toList()
    rows = DebuggerPresentation.breakpointRows(breakpoints, capabilities)
    fireTableDataChanged()
  }

  private companion object {
    val COLUMNS = arrayOf("Enabled", "ID", "Kind", "Condition")
  }
}

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
