package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.debug.DebugBreakpointHit
import eu.rekawek.coffeegb.core.debug.DebugCapabilities
import eu.rekawek.coffeegb.core.debug.DebugInterruptType
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess
import eu.rekawek.coffeegb.core.debug.DebugPpuMode
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugCounterType
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPcCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugSerialCondition
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.regex.Pattern
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.RowFilter
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableRowSorter

/** Immutable hand-off from the breakpoint editor to the asynchronous debugger owner. */
internal data class DebuggerBreakpointSaveRequest(
    val replacedId: DebugBreakpointId?,
    val enabled: Boolean,
    val draft: DebuggerBreakpointDraft,
    val condition: DebugBreakpointCondition,
)

/**
 * Commands emitted by [DebuggerBreakpointPanel].
 *
 * The panel never assumes that a command succeeded. The owning debugger applies the request and
 * later calls [DebuggerBreakpointPanel.replace] with the authoritative backend state.
 */
internal data class DebuggerBreakpointPanelCallbacks(
    val onSave: (DebuggerBreakpointSaveRequest) -> Unit = {},
    val onRemove: (DebugBreakpoint) -> Unit = {},
    val onToggle: (DebugBreakpoint, Boolean) -> Unit = { _, _ -> },
    val onToggleAtCurrentPc: (Int) -> Unit = {},
    val onStatus: (String) -> Unit = {},
)

/**
 * Searchable Mesen-style breakpoint workspace backed only by immutable debugger DTOs.
 *
 * Table updates are authoritative replacements. Editing, duplicating, removing, toggling, and F9
 * merely emit callbacks; no optimistic table mutation can make an asynchronous failure look like
 * success.
 */
internal class DebuggerBreakpointPanel(
    private val callbacks: DebuggerBreakpointPanelCallbacks = DebuggerBreakpointPanelCallbacks(),
) : JPanel(BorderLayout(4, 4)) {
  internal val hitLabel = JLabel(NO_HIT_TEXT)
  internal val filterField = JTextField(24)
  internal val clearFilterButton = JButton("Clear")
  internal val resultCountLabel = JLabel("0 breakpoints")
  internal val tableModel = DebuggerBreakpointWorkspaceTableModel(callbacks.onToggle)
  internal val table = JTable(tableModel)
  internal val editButton = JButton("Edit")
  internal val duplicateButton = JButton("Duplicate")
  internal val removeButton = JButton("Remove")
  internal val toggleCurrentPcButton = JButton("Toggle PC breakpoint (F9)")
  internal val editor = DebuggerBreakpointDraftEditor()
  internal val saveButton = JButton("Add breakpoint")
  internal val cancelEditButton = JButton("Clear editor")
  internal val editorStatusLabel = JLabel(DEFAULT_EDITOR_STATUS)

  private val sorter = TableRowSorter(tableModel)
  private var capabilities: DebugCapabilities? = null
  private var currentProgramCounter: Int? = null
  private var editingId: DebugBreakpointId? = null
  private var busy = false
  private var automaticEditorStatus = false

  init {
    requireBreakpointPanelEdt("Breakpoint panel construction")
    border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
    getAccessibleContext().accessibleName = "Breakpoint and watchpoint workspace"
    getAccessibleContext().accessibleDescription =
        "Search, sort, add, edit, duplicate, enable, disable, and remove debugger breakpoints"

    hitLabel.accessibleContext.accessibleName = "Last breakpoint hit"
    hitLabel.accessibleContext.accessibleDescription = hitLabel.text
    hitLabel.addPropertyChangeListener("text") { event ->
      hitLabel.accessibleContext.accessibleDescription = event.newValue?.toString()
    }

    configureFilter()
    configureTable()
    configureActions()
    configureEditor()

    val header = JPanel(BorderLayout(4, 3)).apply {
      add(hitLabel, BorderLayout.NORTH)
      add(filterBar(), BorderLayout.CENTER)
      add(tableActions(), BorderLayout.SOUTH)
    }
    val tablePane = JPanel(BorderLayout(3, 3)).apply {
      add(header, BorderLayout.NORTH)
      add(JScrollPane(table), BorderLayout.CENTER)
    }
    val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, tablePane, editorPane()).apply {
      resizeWeight = 0.62
      isContinuousLayout = true
      border = null
    }
    add(split, BorderLayout.CENTER)

    installKeyboardBindings()
    updateControlState()
  }

  /** Replaces every displayed row and all session-scoped marker state. */
  fun replace(
      values: List<DebugBreakpoint>,
      capabilities: DebugCapabilities?,
      lastHit: DebugBreakpointHit? = null,
      currentProgramCounter: Int? = null,
      currentStop: Boolean = false,
  ) {
    requireBreakpointPanelEdt("Breakpoint workspace replacement")
    require(currentProgramCounter == null || currentProgramCounter in 0..0xffff) {
      "Current program counter is outside 16 bits"
    }
    require(!currentStop || lastHit?.activePause() == true) {
      "A current stop requires an active breakpoint hit"
    }
    val selectedId = selectedBreakpoint()?.id()
    this.capabilities = capabilities
    tableModel.replace(values, capabilities, lastHit)
    editor.setCapabilities(capabilities)
    updateExecutionContext(lastHit, currentProgramCounter, currentStop)
    restoreSelection(selectedId)
    updateFilterCount()
    updateControlState()
  }

  /** Updates the frequently changing stop marker and F9 address without rebuilding table rows. */
  fun updateExecutionContext(
      lastHit: DebugBreakpointHit?,
      currentProgramCounter: Int?,
      currentStop: Boolean = false,
  ) {
    requireBreakpointPanelEdt("Breakpoint execution-context update")
    require(currentProgramCounter == null || currentProgramCounter in 0..0xffff) {
      "Current program counter is outside 16 bits"
    }
    require(!currentStop || lastHit?.activePause() == true) {
      "A current stop requires an active breakpoint hit"
    }
    this.currentProgramCounter = currentProgramCounter
    tableModel.updateLastHit(lastHit)
    updateHit(lastHit, currentStop)
    updateControlState()
  }

  /** Returns a tab-separated representation of selected visible rows, or all visible rows. */
  fun copyText(): String {
    requireBreakpointPanelEdt("Breakpoint workspace copying")
    val viewRows =
        table.selectedRows
            .asSequence()
            .filter { it in 0 until table.rowCount }
            .distinct()
            .sorted()
            .toList()
            .ifEmpty { (0 until table.rowCount).toList() }
    return buildString {
          append((0 until table.columnCount).joinToString("\t") { table.getColumnName(it) })
          viewRows.forEach { row ->
            append('\n')
            append(
                (0 until table.columnCount).joinToString("\t") { column ->
                  table.getValueAt(row, column)?.toString().orEmpty()
                })
          }
        }
        .trimEnd()
  }

  /** Finds every exact-PC definition suitable for the conventional F9 toggle action. */
  fun breakpointsAtProgramCounter(pc: Int): List<DebugBreakpoint> {
    requireBreakpointPanelEdt("Program-counter breakpoint lookup")
    require(pc in 0..0xffff) { "Program counter is outside 16 bits" }
    return tableModel
        .breakpoints()
        .filter { breakpoint ->
          val condition = breakpoint.condition()
          condition is DebugPcCondition && condition.isExact && condition.startAddress == pc
        }
        .sortedBy { it.id().value() }
  }

  /** Reports a completed async command and optionally clears its draft after confirmed success. */
  fun commandSucceeded(message: String, clearEditor: Boolean = false) {
    requireBreakpointPanelEdt("Breakpoint command success")
    require(message.isNotBlank()) { "Breakpoint command status must not be blank" }
    if (clearEditor) resetEditorState()
    automaticEditorStatus = false
    editorStatusLabel.text = message
    updateControlState()
  }

  /** Keeps the current draft editable after an authoritative async failure. */
  fun commandFailed(message: String) {
    requireBreakpointPanelEdt("Breakpoint command failure")
    require(message.isNotBlank()) { "Breakpoint command status must not be blank" }
    automaticEditorStatus = false
    editorStatusLabel.text = message
    updateControlState()
  }

  /** Gates command-producing controls while the owner has an asynchronous command in flight. */
  fun setBusy(value: Boolean) {
    requireBreakpointPanelEdt("Breakpoint workspace busy state")
    busy = value
    tableModel.interactionEnabled = !value
    updateControlState()
  }

  /** Releases all session-derived rows and editor state without persisting anything. */
  fun clear() {
    requireBreakpointPanelEdt("Breakpoint workspace clearing")
    capabilities = null
    currentProgramCounter = null
    editingId = null
    tableModel.replace(emptyList(), null, null)
    editor.reset()
    editor.setCapabilities(null)
    filterField.text = ""
    hitLabel.text = NO_HIT_TEXT
    automaticEditorStatus = false
    editorStatusLabel.text = DEFAULT_EDITOR_STATUS
    saveButton.text = "Add breakpoint"
    updateFilterCount()
    updateControlState()
  }

  private fun configureFilter() {
    filterField.accessibleContext.accessibleName = "Filter breakpoints"
    filterField.accessibleContext.accessibleDescription =
        "Filter the breakpoint table by identifier, type, or condition"
    clearFilterButton.accessibleContext.accessibleDescription = "Clear the breakpoint filter"
    resultCountLabel.accessibleContext.accessibleName = "Breakpoint filter result count"
    filterField.document.addDocumentListener(
        object : DocumentListener {
          override fun insertUpdate(event: DocumentEvent) = applyFilter()

          override fun removeUpdate(event: DocumentEvent) = applyFilter()

          override fun changedUpdate(event: DocumentEvent) = applyFilter()
        })
    clearFilterButton.addActionListener {
      filterField.text = ""
      filterField.requestFocusInWindow()
    }
  }

  private fun configureTable() {
    table.rowSorter = sorter
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
    table.fillsViewportHeight = true
    table.accessibleContext.accessibleName = "Breakpoints and watchpoints"
    table.accessibleContext.accessibleDescription =
        "Sortable authoritative breakpoint list; the Hit column marks the last breakpoint hit"
    table.columnModel.getColumn(0).preferredWidth = 58
    table.columnModel.getColumn(1).preferredWidth = 72
    table.columnModel.getColumn(2).preferredWidth = 70
    table.columnModel.getColumn(3).preferredWidth = 120
    table.columnModel.getColumn(4).preferredWidth = 440
    table.columnModel.getColumn(5).preferredWidth = 150
    table.selectionModel.addListSelectionListener { updateControlState() }
    table.addMouseListener(
        object : MouseAdapter() {
          override fun mouseClicked(event: MouseEvent) {
            if (event.clickCount == 2 && SwingUtilities.isLeftMouseButton(event)) editSelected()
          }
        })
  }

  private fun configureActions() {
    editButton.accessibleContext.accessibleDescription =
        "Load the selected breakpoint into the typed editor; Enter also edits"
    duplicateButton.accessibleContext.accessibleDescription =
        "Copy the selected condition into the editor as a new breakpoint"
    removeButton.accessibleContext.accessibleDescription =
        "Request removal of the selected breakpoint"
    toggleCurrentPcButton.accessibleContext.accessibleDescription =
        "Toggle a program-counter breakpoint at the current CPU address with F9"
    editButton.addActionListener { editSelected() }
    duplicateButton.addActionListener { duplicateSelected() }
    removeButton.addActionListener { selectedBreakpoint()?.let(callbacks.onRemove) }
    toggleCurrentPcButton.addActionListener { toggleAtCurrentPc() }
  }

  private fun configureEditor() {
    editor.accessibleContext.accessibleName = "Typed breakpoint editor"
    saveButton.accessibleContext.accessibleDescription =
        "Validate and request an authoritative breakpoint add or replacement"
    cancelEditButton.accessibleContext.accessibleDescription =
        "Cancel replacement and reset the typed breakpoint editor"
    editorStatusLabel.accessibleContext.accessibleName = "Breakpoint editor status"
    editorStatusLabel.accessibleContext.accessibleDescription = editorStatusLabel.text
    editorStatusLabel.addPropertyChangeListener("text") { event ->
      editorStatusLabel.accessibleContext.accessibleDescription = event.newValue?.toString()
    }
    editor.onChange = { updateControlState() }
    saveButton.addActionListener { saveEditor() }
    cancelEditButton.addActionListener { resetEditor("Breakpoint editor cleared") }
  }

  private fun filterBar(): Component =
      JPanel(FlowLayout(FlowLayout.LEADING, 5, 1)).apply {
        val label = JLabel("Filter:")
        label.labelFor = filterField
        add(label)
        add(filterField)
        add(clearFilterButton)
        add(resultCountLabel)
      }

  private fun tableActions(): Component =
      JPanel(FlowLayout(FlowLayout.LEADING, 5, 1)).apply {
        add(editButton)
        add(duplicateButton)
        add(removeButton)
        add(toggleCurrentPcButton)
      }

  private fun editorPane(): Component =
      JPanel(BorderLayout(4, 3)).apply {
        border = BorderFactory.createTitledBorder("Breakpoint editor")
        add(editor, BorderLayout.CENTER)
        add(
            JPanel(BorderLayout(4, 2)).apply {
              add(editorStatusLabel, BorderLayout.CENTER)
              add(
                  JPanel(FlowLayout(FlowLayout.TRAILING, 5, 1)).apply {
                    add(cancelEditButton)
                    add(saveButton)
                  },
                  BorderLayout.EAST,
              )
            },
            BorderLayout.SOUTH,
        )
      }

  private fun installKeyboardBindings() {
    val tableInput = table.getInputMap(JComponent.WHEN_FOCUSED)
    tableInput.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), ACTION_EDIT)
    table.actionMap.put(
        ACTION_EDIT,
        object : AbstractAction() {
          override fun actionPerformed(event: java.awt.event.ActionEvent?) = editSelected()
        },
    )

    val input = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
    input.put(KeyStroke.getKeyStroke(KeyEvent.VK_F9, 0), ACTION_TOGGLE_CURRENT_PC)
    actionMap.put(
        ACTION_TOGGLE_CURRENT_PC,
        object : AbstractAction() {
          override fun actionPerformed(event: java.awt.event.ActionEvent?) = toggleAtCurrentPc()
        },
    )
    input.put(
        KeyStroke.getKeyStroke(KeyEvent.VK_F, debuggerMenuShortcutMask()),
        ACTION_FOCUS_FILTER,
    )
    actionMap.put(
        ACTION_FOCUS_FILTER,
        object : AbstractAction() {
          override fun actionPerformed(event: java.awt.event.ActionEvent?) {
            filterField.requestFocusInWindow()
            filterField.selectAll()
          }
        },
    )
  }

  private fun applyFilter() {
    val query = filterField.text.trim()
    sorter.rowFilter =
        if (query.isEmpty()) null
        else RowFilter.regexFilter("(?i)${Pattern.quote(query)}")
    updateFilterCount()
    updateControlState()
  }

  private fun updateFilterCount() {
    val visible = table.rowCount
    val total = tableModel.rowCount
    resultCountLabel.text =
        when {
          total == 0 -> "0 breakpoints"
          visible == total -> "$total ${plural(total, "breakpoint", "breakpoints")}"
          else -> "$visible of $total breakpoints"
        }
  }

  private fun editSelected() {
    val breakpoint = selectedBreakpoint() ?: return
    editingId = breakpoint.id()
    editor.load(DebuggerBreakpointDraft.from(breakpoint.condition()))
    editor.enabledCheckBox.isSelected = breakpoint.enabled()
    saveButton.text = "Save breakpoint #${breakpoint.id().value()}"
    automaticEditorStatus = false
    editorStatusLabel.text = "Editing breakpoint #${breakpoint.id().value()}"
    updateControlState()
    editor.focusPrimaryEditor()
  }

  private fun duplicateSelected() {
    val breakpoint = selectedBreakpoint() ?: return
    editingId = null
    editor.load(DebuggerBreakpointDraft.from(breakpoint.condition()))
    editor.enabledCheckBox.isSelected = breakpoint.enabled()
    saveButton.text = "Add breakpoint"
    automaticEditorStatus = false
    editorStatusLabel.text = "Duplicating breakpoint #${breakpoint.id().value()} as a new definition"
    updateControlState()
    editor.focusPrimaryEditor()
  }

  private fun saveEditor() {
    if (!editor.selectedKindSupported || busy) return
    val draft = editor.draft()
    val parsed = draft.parse()
    if (!parsed.isValid) {
      showStatus(parsed.error!!)
      editor.focusPrimaryEditor()
      return
    }
    val pendingMessage =
        editingId?.let { "Saving breakpoint #${it.value()}…" } ?: "Adding breakpoint…"
    showStatus(pendingMessage)
    callbacks.onSave(
        DebuggerBreakpointSaveRequest(
            editingId,
            editor.enabledCheckBox.isSelected,
            draft,
            parsed.value!!,
        ))
  }

  private fun resetEditor(message: String) {
    resetEditorState()
    showStatus(message)
    updateControlState()
  }

  private fun resetEditorState() {
    editingId = null
    editor.reset()
    saveButton.text = "Add breakpoint"
  }

  private fun toggleAtCurrentPc() {
    val pc = currentProgramCounter
    if (pc == null) {
      showStatus("Pause and refresh before toggling a breakpoint at the current PC")
      return
    }
    if (busy || capabilities?.supports(editor.programCounterNegotiatedKind) != true) return
    showStatus("Toggling program-counter breakpoint at ${DebuggerPresentation.formatWord(pc)}…")
    callbacks.onToggleAtCurrentPc(pc)
  }

  private fun selectedBreakpoint(): DebugBreakpoint? {
    val viewRow = table.selectedRow
    if (viewRow < 0 || viewRow >= table.rowCount) return null
    return tableModel.breakpointAt(table.convertRowIndexToModel(viewRow))
  }

  private fun restoreSelection(id: DebugBreakpointId?) {
    if (id == null) {
      table.clearSelection()
      return
    }
    val modelRow = tableModel.indexOf(id)
    if (modelRow < 0) {
      table.clearSelection()
      return
    }
    val viewRow = table.convertRowIndexToView(modelRow)
    if (viewRow >= 0) table.setRowSelectionInterval(viewRow, viewRow) else table.clearSelection()
  }

  private fun updateHit(hit: DebugBreakpointHit?, currentStop: Boolean) {
    hitLabel.text =
        hit?.let {
          "${if (currentStop) "Current stop" else "Last hit"}: " +
              "breakpoint #${it.breakpointId().value()} matched at tick " +
              "${it.matchMasterTick()} and stopped at tick ${it.snapshot().masterTick()}"
        } ?: NO_HIT_TEXT
  }

  private fun updateControlState() {
    val selected = selectedBreakpoint()
    val supportsBreakpoints = capabilities?.breakpoints() == true
    val usable = supportsBreakpoints && !busy
    table.isEnabled = usable
    tableModel.interactionEnabled = usable
    editButton.isEnabled = usable && selected != null
    duplicateButton.isEnabled = usable && selected != null && selected.condition().let {
      capabilities?.supports(it.kind()) == true
    }
    removeButton.isEnabled = usable && selected != null
    toggleCurrentPcButton.isEnabled =
        usable &&
            currentProgramCounter != null &&
            capabilities?.supports(editor.programCounterNegotiatedKind) == true
    clearFilterButton.isEnabled = filterField.text.isNotEmpty()
    editor.setInteractionEnabled(usable)
    saveButton.isEnabled = usable && editor.selectedKindSupported
    cancelEditButton.isEnabled = !busy
    val unavailableMessage =
        when {
          !supportsBreakpoints -> "Breakpoint editing is unavailable in this session"
          !editor.selectedKindSupported ->
              "${editor.selectedKind.displayName} is unsupported in this session"
          else -> null
        }
    if (unavailableMessage != null &&
        (automaticEditorStatus || editorStatusLabel.text == DEFAULT_EDITOR_STATUS)) {
      automaticEditorStatus = true
      editorStatusLabel.text = unavailableMessage
    } else if (unavailableMessage == null && automaticEditorStatus) {
      automaticEditorStatus = false
      editorStatusLabel.text =
          editingId?.let { "Editing breakpoint #${it.value()}" } ?: DEFAULT_EDITOR_STATUS
    }
  }

  private fun showStatus(message: String) {
    automaticEditorStatus = false
    editorStatusLabel.text = message
    callbacks.onStatus(message)
  }

  private companion object {
    const val NO_HIT_TEXT = "No breakpoint hit recorded in this session"
    const val DEFAULT_EDITOR_STATUS = "Enter a breakpoint condition"
    const val ACTION_EDIT = "debugger-breakpoint-edit"
    const val ACTION_TOGGLE_CURRENT_PC = "debugger-breakpoint-toggle-current-pc"
    const val ACTION_FOCUS_FILTER = "debugger-breakpoint-focus-filter"
  }
}

/** Authoritative sortable rows for [DebuggerBreakpointPanel]. */
internal class DebuggerBreakpointWorkspaceTableModel(
    private val onToggle: (DebugBreakpoint, Boolean) -> Unit,
) : AbstractTableModel() {
  private var breakpoints = emptyList<DebugBreakpoint>()
  private var rows = emptyList<DebuggerBreakpointRow>()
  private var lastHitId: DebugBreakpointId? = null
  var interactionEnabled: Boolean = true
    set(value) {
      if (field == value) return
      field = value
      if (rowCount > 0) fireTableRowsUpdated(0, rowCount - 1)
    }

  override fun getRowCount(): Int = rows.size

  override fun getColumnCount(): Int = COLUMNS.size

  override fun getColumnName(column: Int): String = COLUMNS[column]

  override fun getColumnClass(columnIndex: Int): Class<*> =
      when (columnIndex) {
        1 -> Boolean::class.javaObjectType
        2 -> Long::class.javaObjectType
        else -> String::class.java
      }

  override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean =
      interactionEnabled && columnIndex == 1 && rows[rowIndex].supported

  override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
    val breakpoint = breakpoints[rowIndex]
    val row = rows[rowIndex]
    return when (columnIndex) {
      0 -> if (breakpoint.id() == lastHitId) "Last hit" else ""
      1 -> row.enabled
      2 -> row.id
      3 -> row.kind
      4 -> row.condition
      5 -> if (row.supported) "Supported" else "Unsupported in this session"
      else -> error("Unknown breakpoint workspace column: $columnIndex")
    }
  }

  override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
    if (!isCellEditable(rowIndex, columnIndex) || value !is Boolean) return
    val breakpoint = breakpoints[rowIndex]
    if (breakpoint.enabled() != value) onToggle(breakpoint, value)
  }

  fun replace(
      values: List<DebugBreakpoint>,
      capabilities: DebugCapabilities?,
      lastHit: DebugBreakpointHit?,
  ) {
    breakpoints = values.toList()
    rows = DebuggerPresentation.breakpointRows(breakpoints, capabilities)
    this.lastHitId = markerId(lastHit)
    fireTableDataChanged()
  }

  fun updateLastHit(hit: DebugBreakpointHit?) {
    val value = markerId(hit)
    if (lastHitId == value) return
    val previous = lastHitId
    lastHitId = value
    previous?.let(::indexOf)?.takeIf { it >= 0 }?.let { fireTableRowsUpdated(it, it) }
    value?.let(::indexOf)?.takeIf { it >= 0 }?.let { fireTableRowsUpdated(it, it) }
  }

  fun breakpointAt(row: Int): DebugBreakpoint = breakpoints[row]

  fun indexOf(id: DebugBreakpointId): Int = breakpoints.indexOfFirst { it.id() == id }

  fun breakpoints(): List<DebugBreakpoint> = breakpoints

  private fun markerId(hit: DebugBreakpointHit?): DebugBreakpointId? {
    val definition = hit?.breakpoint()?.orElse(null) ?: return null
    return definition.id().takeIf { id ->
      breakpoints.any { it.id() == id && it == definition }
    }
  }

  private companion object {
    val COLUMNS = arrayOf("Hit", "Enabled", "ID", "Kind", "Condition", "Session support")
  }
}

/** Card-based editor which maps each visible form directly to one immutable draft variant. */
internal class DebuggerBreakpointDraftEditor : JPanel(BorderLayout(4, 4)) {
  internal val kindCombo = JComboBox<DebuggerBreakpointEditorKind>()
  internal val enabledCheckBox = JCheckBox("Enabled", true)
  internal val addressField = JTextField("\$0100", 15)
  internal val memoryAddressField = JTextField("\$C000", 15)
  internal val valueField = JTextField("", 5)
  internal val maskField = JTextField("", 5)
  internal val opcodeField = JTextField("\$00", 5)
  internal val interruptCombo = JComboBox(DebugInterruptType.entries.toTypedArray())
  internal val ppuFrameField = JTextField("", 12)
  internal val ppuLyField = JTextField("", 5)
  internal val ppuModeCombo = JComboBox(PpuModeChoice.entries.toTypedArray())
  internal val serialValueField = JTextField("", 5)
  internal val serialMaskField = JTextField("", 5)
  internal val counterField = JTextField("0", 18)

  private val cardLayout = CardLayout()
  private val cards = JPanel(cardLayout)
  private var capabilities: DebugCapabilities? = null
  private var interactionEnabled = false
  internal var onChange: () -> Unit = {}
  val programCounterNegotiatedKind =
      DebuggerBreakpointEditorKind.PROGRAM_COUNTER.negotiatedKind

  val selectedKind: DebuggerBreakpointEditorKind
    get() = kindCombo.selectedItem as DebuggerBreakpointEditorKind

  val selectedKindSupported: Boolean
    get() = capabilities?.supports(selectedKind.negotiatedKind) == true

  init {
    requireBreakpointPanelEdt("Breakpoint editor construction")
    kindCombo.model = DefaultComboBoxModel(DebuggerBreakpointEditorKind.entries.toTypedArray())
    kindCombo.renderer = BreakpointKindRenderer { capabilities }
    kindCombo.accessibleContext.accessibleName = "Breakpoint condition type"
    kindCombo.accessibleContext.accessibleDescription =
        "Select one of the breakpoint types negotiated with the current session"
    enabledCheckBox.accessibleContext.accessibleDescription =
        "Create or save the breakpoint as enabled or disabled"

    configureField(addressField, "Breakpoint address or inclusive range")
    configureField(memoryAddressField, "Watchpoint address or inclusive range")
    configureField(valueField, "Optional observed memory byte value")
    configureField(maskField, "Optional observed memory byte mask")
    configureField(opcodeField, "Opcode byte")
    configureField(ppuFrameField, "Optional PPU owner frame")
    configureField(ppuLyField, "Optional PPU scanline from 0 through 153")
    configureField(serialValueField, "Optional serial byte value")
    configureField(serialMaskField, "Optional serial byte mask")
    configureField(counterField, "Non-negative counter value")
    interruptCombo.accessibleContext.accessibleName = "Accepted interrupt"
    ppuModeCombo.accessibleContext.accessibleName = "Optional PPU mode"

    cards.add(addressCard(addressField, memory = false), CARD_PROGRAM_COUNTER)
    cards.add(addressCard(memoryAddressField, memory = true), CARD_MEMORY)
    cards.add(singleFieldCard("Opcode:", opcodeField), CARD_OPCODE)
    cards.add(comboCard("Interrupt:", interruptCombo), CARD_INTERRUPT)
    cards.add(ppuCard(), CARD_PPU)
    cards.add(serialCard(), CARD_SERIAL)
    cards.add(singleFieldCard("Value:", counterField), CARD_COUNTER)

    val selector = JPanel(FlowLayout(FlowLayout.LEADING, 5, 1)).apply {
      add(labelFor("Type:", kindCombo))
      add(kindCombo)
      add(enabledCheckBox)
    }
    add(selector, BorderLayout.NORTH)
    add(cards, BorderLayout.CENTER)

    kindCombo.addActionListener {
      showSelectedCard()
      onChange()
    }
    enabledCheckBox.addActionListener { onChange() }
    showSelectedCard()
  }

  fun setCapabilities(value: DebugCapabilities?) {
    requireBreakpointPanelEdt("Breakpoint editor capability update")
    capabilities = value
    kindCombo.repaint()
    updateEnabledState()
  }

  fun setInteractionEnabled(value: Boolean) {
    requireBreakpointPanelEdt("Breakpoint editor interaction update")
    interactionEnabled = value
    updateEnabledState()
  }

  fun draft(): DebuggerBreakpointDraft =
      when (selectedKind) {
        DebuggerBreakpointEditorKind.PROGRAM_COUNTER ->
            DebuggerBreakpointDraft.ProgramCounter(addressField.text)
        DebuggerBreakpointEditorKind.MEMORY_READ ->
            memoryDraft(DebugMemoryAccess.READ)
        DebuggerBreakpointEditorKind.MEMORY_WRITE ->
            memoryDraft(DebugMemoryAccess.WRITE)
        DebuggerBreakpointEditorKind.MEMORY_EXECUTE ->
            memoryDraft(DebugMemoryAccess.EXECUTE)
        DebuggerBreakpointEditorKind.BASE_OPCODE ->
            DebuggerBreakpointDraft.Opcode(false, opcodeField.text)
        DebuggerBreakpointEditorKind.CB_OPCODE ->
            DebuggerBreakpointDraft.Opcode(true, opcodeField.text)
        DebuggerBreakpointEditorKind.INTERRUPT ->
            DebuggerBreakpointDraft.Interrupt(interruptCombo.selectedItem as? DebugInterruptType)
        DebuggerBreakpointEditorKind.PPU_STATE ->
            DebuggerBreakpointDraft.Ppu(
                ppuFrameField.text,
                ppuLyField.text,
                (ppuModeCombo.selectedItem as PpuModeChoice).mode,
            )
        DebuggerBreakpointEditorKind.SERIAL_START ->
            serialDraft(DebugSerialCondition.Event.TRANSFER_STARTED)
        DebuggerBreakpointEditorKind.SERIAL_COMPLETION ->
            serialDraft(DebugSerialCondition.Event.BYTE_TRANSFERRED)
        DebuggerBreakpointEditorKind.MASTER_TICK ->
            DebuggerBreakpointDraft.Counter(DebugCounterType.MASTER_TICK, counterField.text)
        DebuggerBreakpointEditorKind.FRAME_COUNTER ->
            DebuggerBreakpointDraft.Counter(DebugCounterType.FRAME, counterField.text)
      }

  fun load(draft: DebuggerBreakpointDraft) {
    requireBreakpointPanelEdt("Breakpoint draft loading")
    kindCombo.selectedItem = draft.editorKind
    when (draft) {
      is DebuggerBreakpointDraft.ProgramCounter -> addressField.text = draft.addressText
      is DebuggerBreakpointDraft.Memory -> {
        memoryAddressField.text = draft.addressText
        valueField.text = draft.valueText
        maskField.text = draft.maskText
      }
      is DebuggerBreakpointDraft.Opcode -> opcodeField.text = draft.opcodeText
      is DebuggerBreakpointDraft.Interrupt -> interruptCombo.selectedItem = draft.interrupt
      is DebuggerBreakpointDraft.Ppu -> {
        ppuFrameField.text = draft.frameText
        ppuLyField.text = draft.lyText
        ppuModeCombo.selectedItem = PpuModeChoice.from(draft.mode)
      }
      is DebuggerBreakpointDraft.Serial -> {
        serialValueField.text = draft.valueText
        serialMaskField.text = draft.maskText
      }
      is DebuggerBreakpointDraft.Counter -> counterField.text = draft.valueText
    }
    showSelectedCard()
    onChange()
  }

  fun reset() {
    requireBreakpointPanelEdt("Breakpoint editor reset")
    kindCombo.selectedItem = DebuggerBreakpointEditorKind.PROGRAM_COUNTER
    enabledCheckBox.isSelected = true
    addressField.text = "\$0100"
    memoryAddressField.text = "\$C000"
    valueField.text = ""
    maskField.text = ""
    opcodeField.text = "\$00"
    interruptCombo.selectedIndex = 0
    ppuFrameField.text = ""
    ppuLyField.text = ""
    ppuModeCombo.selectedItem = PpuModeChoice.ANY
    serialValueField.text = ""
    serialMaskField.text = ""
    counterField.text = "0"
    showSelectedCard()
    onChange()
  }

  fun focusPrimaryEditor() {
    when (selectedKind) {
      DebuggerBreakpointEditorKind.PROGRAM_COUNTER -> addressField
      DebuggerBreakpointEditorKind.MEMORY_READ,
      DebuggerBreakpointEditorKind.MEMORY_WRITE,
      DebuggerBreakpointEditorKind.MEMORY_EXECUTE -> memoryAddressField
      DebuggerBreakpointEditorKind.BASE_OPCODE,
      DebuggerBreakpointEditorKind.CB_OPCODE -> opcodeField
      DebuggerBreakpointEditorKind.INTERRUPT -> interruptCombo
      DebuggerBreakpointEditorKind.PPU_STATE -> ppuFrameField
      DebuggerBreakpointEditorKind.SERIAL_START,
      DebuggerBreakpointEditorKind.SERIAL_COMPLETION -> serialValueField
      DebuggerBreakpointEditorKind.MASTER_TICK,
      DebuggerBreakpointEditorKind.FRAME_COUNTER -> counterField
    }.requestFocusInWindow()
  }

  private fun memoryDraft(access: DebugMemoryAccess): DebuggerBreakpointDraft.Memory =
      DebuggerBreakpointDraft.Memory(
          access,
          memoryAddressField.text,
          valueField.text,
          maskField.text,
      )

  private fun serialDraft(event: DebugSerialCondition.Event): DebuggerBreakpointDraft.Serial =
      DebuggerBreakpointDraft.Serial(event, serialValueField.text, serialMaskField.text)

  private fun showSelectedCard() {
    cardLayout.show(cards, cardName(selectedKind))
    updateEnabledState()
  }

  private fun updateEnabledState() {
    val enabled = interactionEnabled && selectedKindSupported
    kindCombo.isEnabled = interactionEnabled && capabilities?.breakpoints() == true
    enabledCheckBox.isEnabled = enabled
    listOf(
            addressField,
            memoryAddressField,
            valueField,
            maskField,
            opcodeField,
            interruptCombo,
            ppuFrameField,
            ppuLyField,
            ppuModeCombo,
            serialValueField,
            serialMaskField,
            counterField,
        )
        .forEach { it.isEnabled = enabled }
  }

  private fun addressCard(address: JTextField, memory: Boolean): JPanel =
      formPanel().apply {
        addFormRow(0, "Address/range:", address)
        if (memory) {
          addFormRow(1, "Value:", valueField)
          addFormRow(2, "Mask:", maskField)
        }
      }

  private fun ppuCard(): JPanel =
      formPanel().apply {
        addFormRow(0, "Frame:", ppuFrameField)
        addFormRow(1, "LY:", ppuLyField)
        addFormRow(2, "Mode:", ppuModeCombo)
      }

  private fun serialCard(): JPanel =
      formPanel().apply {
        addFormRow(0, "Value:", serialValueField)
        addFormRow(1, "Mask:", serialMaskField)
      }

  private fun singleFieldCard(label: String, field: Component): JPanel =
      formPanel().apply { addFormRow(0, label, field) }

  private fun comboCard(label: String, combo: Component): JPanel =
      singleFieldCard(label, combo)

  private fun formPanel(): JPanel = JPanel(GridBagLayout())

  private fun JPanel.addFormRow(row: Int, text: String, field: Component) {
    add(
        labelFor(text, field),
        GridBagConstraints().apply {
          gridx = 0
          gridy = row
          anchor = GridBagConstraints.LINE_END
          insets = Insets(2, 3, 2, 5)
        },
    )
    add(
        field,
        GridBagConstraints().apply {
          gridx = 1
          gridy = row
          weightx = 1.0
          fill = GridBagConstraints.HORIZONTAL
          anchor = GridBagConstraints.LINE_START
          insets = Insets(2, 0, 2, 3)
        },
    )
  }

  private fun configureField(field: JTextField, accessibleName: String) {
    field.accessibleContext.accessibleName = accessibleName
    field.document.addDocumentListener(
        object : DocumentListener {
          override fun insertUpdate(event: DocumentEvent) = onChange()

          override fun removeUpdate(event: DocumentEvent) = onChange()

          override fun changedUpdate(event: DocumentEvent) = onChange()
        })
  }

  private fun cardName(kind: DebuggerBreakpointEditorKind): String =
      when (kind) {
        DebuggerBreakpointEditorKind.PROGRAM_COUNTER -> CARD_PROGRAM_COUNTER
        DebuggerBreakpointEditorKind.MEMORY_READ,
        DebuggerBreakpointEditorKind.MEMORY_WRITE,
        DebuggerBreakpointEditorKind.MEMORY_EXECUTE -> CARD_MEMORY
        DebuggerBreakpointEditorKind.BASE_OPCODE,
        DebuggerBreakpointEditorKind.CB_OPCODE -> CARD_OPCODE
        DebuggerBreakpointEditorKind.INTERRUPT -> CARD_INTERRUPT
        DebuggerBreakpointEditorKind.PPU_STATE -> CARD_PPU
        DebuggerBreakpointEditorKind.SERIAL_START,
        DebuggerBreakpointEditorKind.SERIAL_COMPLETION -> CARD_SERIAL
        DebuggerBreakpointEditorKind.MASTER_TICK,
        DebuggerBreakpointEditorKind.FRAME_COUNTER -> CARD_COUNTER
      }

  private companion object {
    const val CARD_PROGRAM_COUNTER = "program-counter"
    const val CARD_MEMORY = "memory"
    const val CARD_OPCODE = "opcode"
    const val CARD_INTERRUPT = "interrupt"
    const val CARD_PPU = "ppu"
    const val CARD_SERIAL = "serial"
    const val CARD_COUNTER = "counter"
  }
}

internal enum class PpuModeChoice(val mode: DebugPpuMode?, private val label: String) {
  ANY(null, "Any mode"),
  DISABLED(DebugPpuMode.DISABLED, "Disabled"),
  HBLANK(DebugPpuMode.HBLANK, "HBlank"),
  VBLANK(DebugPpuMode.VBLANK, "VBlank"),
  OAM_SEARCH(DebugPpuMode.OAM_SEARCH, "OAM search"),
  PIXEL_TRANSFER(DebugPpuMode.PIXEL_TRANSFER, "Pixel transfer");

  override fun toString(): String = label

  companion object {
    fun from(mode: DebugPpuMode?): PpuModeChoice = entries.first { it.mode == mode }
  }
}

private class BreakpointKindRenderer(
    private val capabilities: () -> DebugCapabilities?,
) : DefaultListCellRenderer() {
  override fun getListCellRendererComponent(
      list: JList<*>?,
      value: Any?,
      index: Int,
      isSelected: Boolean,
      cellHasFocus: Boolean,
  ): Component {
    val component =
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
    val kind = value as? DebuggerBreakpointEditorKind
    if (kind != null) {
      val supported = capabilities()?.supports(kind.negotiatedKind) == true
      component.text = kind.displayName + if (supported || capabilities() == null) "" else " — unsupported"
      if (!supported && capabilities() != null && !isSelected) {
        component.foreground = disabledForeground(component.foreground)
      }
    }
    return component
  }

  private fun disabledForeground(fallback: Color): Color =
      UIManager.getColor("Label.disabledForeground") ?: fallback
}

private fun labelFor(text: String, target: Component): JLabel =
    JLabel(text).apply {
      labelFor = target
    }

private fun plural(count: Int, singular: String, plural: String): String =
    if (count == 1) singular else plural

private fun requireBreakpointPanelEdt(operation: String) {
  check(SwingUtilities.isEventDispatchThread()) { "$operation must run on the Event Dispatch Thread" }
}
