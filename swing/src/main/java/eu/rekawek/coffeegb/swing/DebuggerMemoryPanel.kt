package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.debug.DebugAddressSpace
import eu.rekawek.coffeegb.core.debug.DebugAnchoredMemoryRequest
import eu.rekawek.coffeegb.core.debug.DebugInspectionAnchor
import eu.rekawek.coffeegb.core.debug.DebugInspectionRequest
import eu.rekawek.coffeegb.core.debug.DebugMemoryBlock
import eu.rekawek.coffeegb.core.debug.DebugMemoryRequest
import eu.rekawek.coffeegb.core.debug.DebugMemoryWrite
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Point
import java.awt.event.MouseEvent
import java.util.EventObject
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.DefaultCellEditor
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/** The memory capture currently requested by [DebuggerMemoryPanel]. */
internal sealed interface DebuggerMemoryInterest {
  data class Absolute(val request: DebugMemoryRequest) : DebuggerMemoryInterest

  data class Anchored(val request: DebugAnchoredMemoryRequest) : DebuggerMemoryInterest
}

/** Register-relative modes exposed by the memory inspector's Follow control. */
internal enum class DebuggerMemoryFollow(private val displayName: String) {
  NONE("None"),
  PROGRAM_COUNTER("PC"),
  STACK_POINTER("SP");

  override fun toString(): String = displayName
}

internal data class DebuggerMemoryPanelCallbacks(
    val onInterestChanged: (DebuggerMemoryInterest) -> Unit = {},
    val onWriteByte: (DebugMemoryWrite) -> Unit = {},
)

/**
 * Live memory inspector with bounded native Swing controls and a hexadecimal grid.
 *
 * Rendering and edit requests are EDT-only. There is deliberately no refresh action: the owner
 * uses [currentInterest] (or the callback) to include this pane in its continuous inspection
 * stream. A negotiated, paused owner may edit the safe RAM views one byte at a time.
 */
internal class DebuggerMemoryPanel(
    private val callbacks: DebuggerMemoryPanelCallbacks = DebuggerMemoryPanelCallbacks(),
) : JPanel(BorderLayout(6, 6)) {
  internal val addressSpaceCombo = JComboBox(SAFE_ADDRESS_SPACES.toTypedArray())
  internal val startSpinner =
      DebuggerHexSpinner(DEFAULT_START, rangesFor(DebugAddressSpace.WORK_RAM), digits = 4)
  internal val lengthSpinner =
      DebuggerHexSpinner(DEFAULT_LENGTH, listOf(1..DEFAULT_MAX_SAMPLE_LENGTH), digits = 4)
  internal val followCombo =
      JComboBox(
          arrayOf(
              DebuggerMemoryFollow.NONE,
              DebuggerMemoryFollow.PROGRAM_COUNTER,
              DebuggerMemoryFollow.STACK_POINTER,
          )
      )
  internal val statusLabel = JLabel()
  private val tableModel = DebuggerMemoryTableModel(::commitByteEdit)
  internal val memoryTable: JTable = DebuggerMemoryTable(tableModel)
  internal val memoryScrollPane = JScrollPane(memoryTable)

  private var maximumSampleLength = DEFAULT_MAX_SAMPLE_LENGTH
  private var suppressControlEvents = true
  private var interest: DebuggerMemoryInterest
  private var lastAppliedIdentity: DebuggerSnapshotIdentity? = null
  private var baseline: RenderedMemorySample? = null
  private var memoryWritesEnabled = false
  private var activeAddressSpace = DebugAddressSpace.WORK_RAM
  private val positionsByAddressSpace = mutableMapOf<DebugAddressSpace, MemorySpacePosition>()
  private var pendingPositionRestore: MemorySpacePosition? = null

  init {
    requireMemoryPanelEdt("Memory debugger panel construction")
    border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
    getAccessibleContext().accessibleName = "Live memory inspector"
    getAccessibleContext().accessibleDescription = EMPTY_ACCESSIBLE_DESCRIPTION

    addressSpaceCombo.selectedItem = DebugAddressSpace.WORK_RAM
    addressSpaceCombo.renderer = AddressSpaceRenderer()
    addressSpaceCombo.accessibleContext.accessibleName = "Memory address space"
    addressSpaceCombo.accessibleContext.accessibleDescription =
        "Side-effect-free memory view; unavailable hardware address spaces are omitted"
    startSpinner.accessibleContext.accessibleName = "Memory start address"
    startSpinner.accessibleContext.accessibleDescription =
        "Bounded hexadecimal address; arrow controls skip unavailable address ranges"
    lengthSpinner.accessibleContext.accessibleName = "Memory sample length"
    lengthSpinner.accessibleContext.accessibleDescription =
        "Bounded hexadecimal count of bytes captured continuously"
    followCombo.accessibleContext.accessibleName = "Follow register"
    followCombo.accessibleContext.accessibleDescription =
        "Keep the memory range anchored to no register, the program counter, or the stack pointer"

    val addressLabel = controlLabel("Space", addressSpaceCombo)
    val startLabel = controlLabel("Start", startSpinner)
    val lengthLabel = controlLabel("Length", lengthSpinner)
    val followLabel = controlLabel("Follow", followCombo)
    val controls =
        JPanel(FlowLayout(FlowLayout.LEADING, 8, 3)).apply {
          border = BorderFactory.createTitledBorder("Live range")
          add(addressLabel)
          add(addressSpaceCombo)
          add(startLabel)
          add(startSpinner)
          add(lengthLabel)
          add(lengthSpinner)
          add(followLabel)
          add(followCombo)
        }

    configureTable()
    memoryScrollPane.accessibleContext.accessibleName = "Live memory grid"
    statusLabel.border = BorderFactory.createEmptyBorder(1, 4, 1, 4)
    statusLabel.accessibleContext.accessibleName = "Memory snapshot status"

    add(controls, BorderLayout.NORTH)
    add(memoryScrollPane, BorderLayout.CENTER)
    add(statusLabel, BorderLayout.SOUTH)

    reconcileBounds()
    interest = interestFromControls()
    suppressControlEvents = false
    installListeners()
    setEmptyState("Waiting for the first live memory sample", resetIdentity = true)
  }

  val currentInterest: DebuggerMemoryInterest
    get() = interest

  val currentRequest: DebugMemoryRequest?
    get() = (interest as? DebuggerMemoryInterest.Absolute)?.request

  val currentAnchoredRequest: DebugAnchoredMemoryRequest?
    get() = (interest as? DebuggerMemoryInterest.Anchored)?.request

  /** Applies a block only when it belongs to the current interest and is not older than the UI. */
  fun render(
      identity: DebuggerSnapshotIdentity,
      sampledInterest: DebuggerMemoryInterest,
      block: DebugMemoryBlock,
  ): Boolean {
    requireMemoryPanelEdt("Memory debugger rendering")
    if (sampledInterest != interest || isOlderThanLastApplied(identity)) return false
    requireBlockMatches(sampledInterest, block)

    val selectedCell = pendingPositionRestore?.selectedCell ?: selectedCell()
    val viewPosition = pendingPositionRestore?.viewPosition ?: Point(memoryScrollPane.viewport.viewPosition)
    val previous =
        baseline?.takeIf { sample ->
          sample.identity.sessionGeneration == identity.sessionGeneration &&
              sample.block.addressSpace() == block.addressSpace()
        }
    tableModel.render(block, previous?.block)
    restoreSelection(selectedCell)
    restoreViewPosition(viewPosition)

    baseline = RenderedMemorySample(identity, block)
    updateByteEditing()
    pendingPositionRestore = null
    lastAppliedIdentity = identity
    val rangeEnd = block.endExclusive() - 1
    statusLabel.text =
        "${identity.label} · ${addressSpaceName(block.addressSpace())} " +
            "${formatAddress(block.startAddress())}–${formatAddress(rangeEnd)} · " +
            "${block.length()} bytes · live"
    updateAccessibleDescription(statusLabel.text)
    return true
  }

  fun render(identity: DebuggerSnapshotIdentity, block: DebugMemoryBlock): Boolean =
      render(identity, interest, block)

  /** Displays an explicit current-snapshot absence without leaving old bytes on screen. */
  fun showNotSampled(
      identity: DebuggerSnapshotIdentity,
      sampledInterest: DebuggerMemoryInterest,
      reason: String,
  ): Boolean {
    requireMemoryPanelEdt("Memory debugger not-sampled transition")
    require(reason.isNotBlank()) { "Not-sampled reason must not be blank" }
    if (sampledInterest != interest || isOlderThanLastApplied(identity)) return false

    tableModel.clear()
    memoryTable.clearSelection()
    baseline = null
    updateByteEditing()
    lastAppliedIdentity = identity
    statusLabel.text = "${identity.label} · not sampled: ${reason.trim()}"
    updateAccessibleDescription(statusLabel.text)
    return true
  }

  fun showNotSampled(identity: DebuggerSnapshotIdentity, reason: String): Boolean =
      showNotSampled(identity, interest, reason)

  /** Clears rendered state while retaining the user's range controls. */
  fun clear() {
    requireMemoryPanelEdt("Memory debugger clearing")
    setEmptyState("No memory sample loaded", resetIdentity = true)
  }

  /** Restricts length to the aggregate inspection budget and updates interest atomically. */
  fun setMaximumSampleLength(maximum: Int) {
    requireMemoryPanelEdt("Memory debugger sample-length configuration")
    require(maximum in 1..DebugInspectionRequest.MAX_TOTAL_BYTES) {
      "Maximum sample length must be within the inspection byte budget"
    }
    if (maximum == maximumSampleLength) return
    maximumSampleLength = maximum
    reconcileBoundsAndPublish()
  }

  /** Updates which register-relative choices the active debug session can provide. */
  fun setFollowCapabilities(programCounter: Boolean, stackPointer: Boolean) {
    requireMemoryPanelEdt("Memory debugger follow-capability configuration")
    val previous = followCombo.selectedItem as? DebuggerMemoryFollow ?: DebuggerMemoryFollow.NONE
    val choices =
        buildList {
          add(DebuggerMemoryFollow.NONE)
          if (programCounter) add(DebuggerMemoryFollow.PROGRAM_COUNTER)
          if (stackPointer) add(DebuggerMemoryFollow.STACK_POINTER)
        }
    if ((0 until followCombo.itemCount).map(followCombo::getItemAt) == choices) return

    suppressControlEvents = true
    followCombo.model = DefaultComboBoxModel(choices.toTypedArray())
    followCombo.selectedItem = previous.takeIf(choices::contains) ?: DebuggerMemoryFollow.NONE
    suppressControlEvents = false
    updateControlEnablement()
    publishInterestIfChanged()
  }

  /** Enables byte editing only while the owner has negotiated paused memory-write support. */
  fun setMemoryWritesEnabled(enabled: Boolean) {
    requireMemoryPanelEdt("Memory debugger write-capability configuration")
    if (memoryWritesEnabled == enabled) return
    memoryWritesEnabled = enabled
    updateByteEditing()
  }

  private fun installListeners() {
    addressSpaceCombo.addActionListener {
      if (!suppressControlEvents) switchAddressSpace()
    }
    startSpinner.addChangeListener {
      if (!suppressControlEvents) {
        pendingPositionRestore = null
        rememberPosition(activeAddressSpace)
        publishInterestIfChanged()
      }
    }
    lengthSpinner.addChangeListener {
      if (!suppressControlEvents) reconcileBoundsAndPublish()
    }
    followCombo.addActionListener {
      if (!suppressControlEvents) {
        updateControlEnablement()
        publishInterestIfChanged()
      }
    }
  }

  private fun switchAddressSpace() {
    rememberPosition(activeAddressSpace)
    activeAddressSpace = selectedAddressSpace()
    val restored = positionsByAddressSpace[activeAddressSpace]
    pendingPositionRestore = restored
    reconcileBoundsAndPublish(
        preferredStart = restored?.startAddress ?: defaultStartFor(activeAddressSpace),
        restoringAddressSpace = true,
    )
  }

  private fun reconcileBoundsAndPublish(
      preferredStart: Int = startSpinner.intValue,
      restoringAddressSpace: Boolean = false,
  ) {
    if (!restoringAddressSpace) rememberPosition(activeAddressSpace)
    suppressControlEvents = true
    reconcileBounds(preferredStart)
    suppressControlEvents = false
    updateStoredStartPosition()
    publishInterestIfChanged()
  }

  private fun reconcileBounds(preferredStart: Int = startSpinner.intValue) {
    val space = selectedAddressSpace()
    val longestSegment = rangesFor(space).maxOf { range -> range.last - range.first + 1 }
    val lengthLimit = minOf(maximumSampleLength, longestSegment)
    lengthSpinner.setAllowedRanges(listOf(1..lengthLimit), lengthSpinner.intValue)
    val length = lengthSpinner.intValue
    val starts =
        rangesFor(space).mapNotNull { range ->
          val lastStart = range.last - length + 1
          if (lastStart < range.first) null else range.first..lastStart
        }
    startSpinner.setAllowedRanges(starts, preferredStart)
    updateControlEnablement()
  }

  private fun updateControlEnablement() {
    val absolute = selectedFollow() == DebuggerMemoryFollow.NONE
    addressSpaceCombo.isEnabled = absolute
    startSpinner.isEnabled = absolute
    lengthSpinner.isEnabled = true
  }

  private fun publishInterestIfChanged() {
    if (suppressControlEvents) return
    val next = interestFromControls()
    if (next == interest) return
    interest = next
    tableModel.clear()
    memoryTable.clearSelection()
    baseline = null
    updateByteEditing()
    statusLabel.text = "Waiting for a live sample of ${interestDescription(next)}"
    updateAccessibleDescription(statusLabel.text)
    callbacks.onInterestChanged(next)
  }

  private fun interestFromControls(): DebuggerMemoryInterest {
    val length = lengthSpinner.intValue
    return when (selectedFollow()) {
      DebuggerMemoryFollow.NONE ->
          DebuggerMemoryInterest.Absolute(
              DebugMemoryRequest(selectedAddressSpace(), startSpinner.intValue, length)
          )
      DebuggerMemoryFollow.PROGRAM_COUNTER ->
          DebuggerMemoryInterest.Anchored(
              DebugAnchoredMemoryRequest(DebugInspectionAnchor.PROGRAM_COUNTER, 0, length)
          )
      DebuggerMemoryFollow.STACK_POINTER ->
          DebuggerMemoryInterest.Anchored(
              DebugAnchoredMemoryRequest(DebugInspectionAnchor.STACK_POINTER, 0, length)
          )
    }
  }

  private fun requireBlockMatches(
      sampledInterest: DebuggerMemoryInterest,
      block: DebugMemoryBlock,
  ) {
    when (sampledInterest) {
      is DebuggerMemoryInterest.Absolute -> {
        val request = sampledInterest.request
        require(block.addressSpace() == request.addressSpace()) {
          "Memory block address space does not match the requested range"
        }
        require(block.startAddress() == request.address() && block.length() == request.length()) {
          "Memory block boundaries do not match the requested range"
        }
      }
      is DebuggerMemoryInterest.Anchored -> {
        require(block.length() == sampledInterest.request.length()) {
          "Memory block length does not match the anchored request"
        }
        val expectedSpaces =
            when (sampledInterest.request.anchor()) {
              DebugInspectionAnchor.PROGRAM_COUNTER ->
                  setOf(DebugAddressSpace.ROM, DebugAddressSpace.SYSTEM_BUS)
              DebugInspectionAnchor.STACK_POINTER -> setOf(DebugAddressSpace.SYSTEM_BUS)
            }
        require(block.addressSpace() in expectedSpaces) {
          "Memory block address space does not match the anchored request"
        }
      }
    }
    require(isSafeBlock(block)) { "Memory block is outside a side-effect-free address range" }
  }

  private fun selectedAddressSpace(): DebugAddressSpace =
      addressSpaceCombo.selectedItem as? DebugAddressSpace
          ?: error("Memory address space selection is missing")

  private fun selectedFollow(): DebuggerMemoryFollow =
      followCombo.selectedItem as? DebuggerMemoryFollow ?: DebuggerMemoryFollow.NONE

  private fun isOlderThanLastApplied(identity: DebuggerSnapshotIdentity): Boolean {
    val last = lastAppliedIdentity ?: return false
    return when {
      identity.sessionGeneration != last.sessionGeneration ->
          identity.sessionGeneration < last.sessionGeneration
      identity.sequence != last.sequence -> identity.sequence < last.sequence
      else -> identity.masterTick < last.masterTick
    }
  }

  private fun selectedCell(): SelectedMemoryCell? {
    val row = memoryTable.selectedRow
    val column = memoryTable.selectedColumn
    if (row !in 0 until tableModel.rowCount || column !in 0 until tableModel.columnCount) return null
    val rowStart = tableModel.rowStartAddress(row)
    val address = if (column in BYTE_COLUMN_FIRST..BYTE_COLUMN_LAST) rowStart + column - 1 else rowStart
    return SelectedMemoryCell(address, column)
  }

  private fun restoreSelection(selected: SelectedMemoryCell?) {
    if (selected == null) return
    val row = tableModel.rowContaining(selected.address) ?: return
    val column = selected.column.coerceIn(0, tableModel.columnCount - 1)
    memoryTable.selectionModel.setSelectionInterval(row, row)
    memoryTable.columnModel.selectionModel.setSelectionInterval(column, column)
  }

  private fun restoreViewPosition(position: Point) {
    val viewport = memoryScrollPane.viewport
    val maximumX = (memoryTable.width - viewport.extentSize.width).coerceAtLeast(0)
    val maximumY = (memoryTable.height - viewport.extentSize.height).coerceAtLeast(0)
    viewport.viewPosition = Point(position.x.coerceIn(0, maximumX), position.y.coerceIn(0, maximumY))
  }

  private fun configureTable() {
    memoryTable.autoResizeMode = JTable.AUTO_RESIZE_OFF
    memoryTable.fillsViewportHeight = true
    memoryTable.cellSelectionEnabled = true
    memoryTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
    memoryTable.columnModel.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
    memoryTable.rowHeight = memoryTable.getFontMetrics(memoryTable.font).height + 6
    memoryTable.accessibleContext.accessibleName = "Hexadecimal memory bytes"
    memoryTable.accessibleContext.accessibleDescription =
        "Rows contain an address, sixteen hexadecimal byte cells, and an ASCII rendering. " +
            "Double-click an editable RAM byte to change it; changed bytes begin with a delta marker"
    memoryTable.setDefaultRenderer(Any::class.java, MemoryCellRenderer())
    memoryTable.columnModel.getColumn(ADDRESS_COLUMN).preferredWidth = 64
    for (column in BYTE_COLUMN_FIRST..BYTE_COLUMN_LAST) {
      memoryTable.columnModel.getColumn(column).preferredWidth = 38
    }
    memoryTable.columnModel.getColumn(ASCII_COLUMN).preferredWidth = 120
  }

  private fun updateByteEditing() {
    val editable =
        memoryWritesEnabled &&
            baseline?.block?.addressSpace() in
                setOf(
                    DebugAddressSpace.SYSTEM_BUS,
                    DebugAddressSpace.WORK_RAM,
                    DebugAddressSpace.HIGH_RAM,
                )
    tableModel.setByteEditingEnabled(editable)
  }

  private fun commitByteEdit(cell: DebuggerMemoryByteCell, rawValue: String) {
    val value = parseCellByte(rawValue)
    if (value == null) {
      statusLabel.text = "Enter a hexadecimal byte from 00 to FF"
      updateAccessibleDescription(statusLabel.text)
      return
    }
    val block = baseline?.block
    if (!memoryWritesEnabled || block == null || !isWritableAddressSpace(block.addressSpace())) {
      statusLabel.text = "Memory editing is available only while the debugger is paused"
      updateAccessibleDescription(statusLabel.text)
      return
    }
    val write = DebugMemoryWrite(block.addressSpace(), cell.address, value)
    statusLabel.text = "Writing ${formatCellByte(value)} to ${formatCellAddress(cell.address)}…"
    updateAccessibleDescription(statusLabel.text)
    callbacks.onWriteByte(write)
  }

  fun showWriteFailure(message: String) {
    requireMemoryPanelEdt("Memory debugger write failure")
    statusLabel.text = message
    updateAccessibleDescription(message)
  }

  private fun setEmptyState(message: String, resetIdentity: Boolean) {
    tableModel.clear()
    memoryTable.clearSelection()
    baseline = null
    updateByteEditing()
    if (resetIdentity) lastAppliedIdentity = null
    statusLabel.text = message
    updateAccessibleDescription(message)
  }

  private fun updateAccessibleDescription(message: String) {
    statusLabel.accessibleContext.accessibleDescription = message
    getAccessibleContext().accessibleDescription =
        "Live, side-effect-free hexadecimal memory grid. $message"
  }

  private fun controlLabel(text: String, component: Component): JLabel =
      JLabel("$text:").apply {
        labelFor = component
        getAccessibleContext().accessibleName = "$text control label"
      }

  private data class RenderedMemorySample(
      val identity: DebuggerSnapshotIdentity,
      val block: DebugMemoryBlock,
  )

  private data class SelectedMemoryCell(val address: Int, val column: Int)

  private data class MemorySpacePosition(
      val startAddress: Int,
      val selectedCell: SelectedMemoryCell?,
      val viewPosition: Point,
  )

  private fun rememberPosition(addressSpace: DebugAddressSpace) {
    positionsByAddressSpace[addressSpace] =
        MemorySpacePosition(
            startSpinner.intValue,
            selectedCell(),
            Point(memoryScrollPane.viewport.viewPosition),
        )
  }

  private fun updateStoredStartPosition() {
    val existing = positionsByAddressSpace[activeAddressSpace]
    positionsByAddressSpace[activeAddressSpace] =
        if (existing == null) {
          MemorySpacePosition(startSpinner.intValue, null, Point())
        } else {
          existing.copy(startAddress = startSpinner.intValue)
        }
  }

  private class AddressSpaceRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component =
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).also {
          text = (value as? DebugAddressSpace)?.let(::addressSpaceName).orEmpty()
        }
  }

  companion object {
    private const val DEFAULT_START = 0xc000
    private const val DEFAULT_LENGTH = 0x80
    private const val DEFAULT_MAX_SAMPLE_LENGTH = DebugInspectionRequest.MAX_TOTAL_BYTES
    private const val EMPTY_ACCESSIBLE_DESCRIPTION =
        "Live, side-effect-free hexadecimal memory grid waiting for a sample"

    private val SAFE_ADDRESS_SPACES =
        listOf(
            DebugAddressSpace.SYSTEM_BUS,
            DebugAddressSpace.ROM,
            DebugAddressSpace.WORK_RAM,
            DebugAddressSpace.HIGH_RAM,
        )

    private fun rangesFor(space: DebugAddressSpace): List<IntRange> =
        when (space) {
          DebugAddressSpace.SYSTEM_BUS -> listOf(0xc000..0xfdff, 0xff80..0xfffe)
          DebugAddressSpace.ROM -> listOf(0x0000..0xffff)
          DebugAddressSpace.WORK_RAM -> listOf(0xc000..0xfdff)
          DebugAddressSpace.HIGH_RAM -> listOf(0xff80..0xfffe)
          else -> error("Unsafe memory address space is not selectable: $space")
        }

    private fun defaultStartFor(space: DebugAddressSpace): Int = rangesFor(space).first().first

    private fun isSafeBlock(block: DebugMemoryBlock): Boolean {
      if (block.length() <= 0) return false
      val start = block.startAddress()
      val end = block.endExclusive() - 1
      return rangesFor(block.addressSpace()).any { range -> start >= range.first && end <= range.last }
    }

    private fun isWritableAddressSpace(addressSpace: DebugAddressSpace): Boolean =
        addressSpace == DebugAddressSpace.SYSTEM_BUS ||
            addressSpace == DebugAddressSpace.WORK_RAM ||
            addressSpace == DebugAddressSpace.HIGH_RAM

    private fun interestDescription(interest: DebuggerMemoryInterest): String =
        when (interest) {
          is DebuggerMemoryInterest.Absolute ->
              "${addressSpaceName(interest.request.addressSpace())} at " +
                  formatAddress(interest.request.address())
          is DebuggerMemoryInterest.Anchored ->
              when (interest.request.anchor()) {
                DebugInspectionAnchor.PROGRAM_COUNTER -> "memory following PC"
                DebugInspectionAnchor.STACK_POINTER -> "memory following SP"
              }
        }

    private fun addressSpaceName(space: DebugAddressSpace): String =
        when (space) {
          DebugAddressSpace.SYSTEM_BUS -> "System bus"
          DebugAddressSpace.ROM -> "ROM image"
          DebugAddressSpace.WORK_RAM -> "Work RAM"
          DebugAddressSpace.HIGH_RAM -> "High RAM"
          else -> space.name.lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase)
        }

    private fun formatAddress(address: Int): String =
        "$" + String.format(Locale.ROOT, "%04X", address)
  }
}

/** A byte-cell value with a textual change indicator that does not rely on color. */
internal data class DebuggerMemoryByteCell(
    val address: Int,
    val value: Int,
    val previousValue: Int?,
) {
  val changed: Boolean
    get() = previousValue != null && previousValue != value

  val accessibleText: String
    get() =
        if (changed) {
          "${formatCellAddress(address)} changed from ${formatCellByte(previousValue!!)} to " +
              formatCellByte(value)
        } else {
          "${formatCellAddress(address)} contains ${formatCellByte(value)}"
        }

  override fun toString(): String =
      if (changed) "Δ ${formatCellByte(value)}" else "  ${formatCellByte(value)}"
}

private class DebuggerMemoryTable(
    model: DebuggerMemoryTableModel,
) : JTable(model) {
  init {
    val editor =
        object : DefaultCellEditor(JTextField()) {
          init {
            clickCountToStart = 2
          }

          override fun getTableCellEditorComponent(
              table: JTable,
              value: Any?,
              isSelected: Boolean,
              row: Int,
              column: Int,
          ): Component {
            val component = super.getTableCellEditorComponent(table, value, isSelected, row, column)
            if (value is DebuggerMemoryByteCell) {
              (component as JTextField).apply {
                text = formatCellByte(value.value)
                selectAll()
              }
            }
            return component
          }
        }
    setDefaultEditor(DebuggerMemoryByteCell::class.java, editor)
  }

  override fun editCellAt(row: Int, column: Int, event: EventObject?): Boolean {
    if (event is MouseEvent && event.clickCount < 2) return false
    return super.editCellAt(row, column, event)
  }

  override fun getToolTipText(event: MouseEvent): String? {
    val row = rowAtPoint(event.point)
    val column = columnAtPoint(event.point)
    if (row < 0 || column < 0) return null
    return (getValueAt(row, column) as? DebuggerMemoryByteCell)?.accessibleText
  }
}

private class DebuggerMemoryTableModel(
    private val onByteEdit: (DebuggerMemoryByteCell, String) -> Unit,
) : AbstractTableModel() {
  private var rows = emptyList<MemoryRow>()
  private var byteEditingEnabled = false

  override fun getRowCount(): Int = rows.size

  override fun getColumnCount(): Int = TOTAL_COLUMNS

  override fun getColumnName(column: Int): String =
      when (column) {
        ADDRESS_COLUMN -> "Address"
        ASCII_COLUMN -> "ASCII"
        else -> Integer.toHexString(column - 1).uppercase(Locale.ROOT)
      }

  override fun getColumnClass(columnIndex: Int): Class<*> =
      if (columnIndex in BYTE_COLUMN_FIRST..BYTE_COLUMN_LAST) {
        DebuggerMemoryByteCell::class.java
      } else {
        String::class.java
      }

  override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
    val row = rows[rowIndex]
    return when (columnIndex) {
      ADDRESS_COLUMN -> formatCellAddress(row.startAddress)
      ASCII_COLUMN -> row.ascii
      else -> row.bytes[columnIndex - BYTE_COLUMN_FIRST]
    }
  }

  override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean =
      byteEditingEnabled &&
          columnIndex in BYTE_COLUMN_FIRST..BYTE_COLUMN_LAST &&
          rows.getOrNull(rowIndex)?.bytes?.get(columnIndex - BYTE_COLUMN_FIRST) != null

  override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
    if (!isCellEditable(rowIndex, columnIndex)) return
    val cell = rows[rowIndex].bytes[columnIndex - BYTE_COLUMN_FIRST] ?: return
    onByteEdit(cell, value?.toString().orEmpty())
  }

  fun setByteEditingEnabled(enabled: Boolean) {
    if (byteEditingEnabled == enabled) return
    byteEditingEnabled = enabled
    fireTableDataChanged()
  }

  fun render(block: DebugMemoryBlock, previous: DebugMemoryBlock?) {
    val previousValues =
        previous?.let { old ->
          (0 until old.length()).associate { index ->
            old.startAddress() + index to old.unsignedByteAt(index)
          }
        }.orEmpty()
    val alignedStart = block.startAddress() and -0x10
    val alignedEnd = (block.endExclusive() + 0x0f) and -0x10
    rows =
        (alignedStart until alignedEnd step BYTES_PER_ROW).map { rowStart ->
          val byteCells =
              (0 until BYTES_PER_ROW).map { offset ->
                val address = rowStart + offset
                if (address !in block.startAddress() until block.endExclusive()) {
                  null
                } else {
                  val value = block.unsignedByteAt(address - block.startAddress())
                  DebuggerMemoryByteCell(address, value, previousValues[address])
                }
              }
          val ascii =
              byteCells.joinToString(separator = "") { cell ->
                when {
                  cell == null -> " "
                  cell.value in 0x20..0x7e -> cell.value.toChar().toString()
                  else -> "."
                }
              }
          MemoryRow(rowStart, byteCells, ascii)
        }
    fireTableDataChanged()
  }

  fun clear() {
    if (rows.isEmpty()) return
    rows = emptyList()
    fireTableDataChanged()
  }

  fun rowStartAddress(row: Int): Int = rows[row].startAddress

  fun rowContaining(address: Int): Int? =
      rows.indexOfFirst { row -> address in row.startAddress until row.startAddress + BYTES_PER_ROW }
          .takeIf { it >= 0 }

  private data class MemoryRow(
      val startAddress: Int,
      val bytes: List<DebuggerMemoryByteCell?>,
      val ascii: String,
  )
}

private class MemoryCellRenderer : DefaultTableCellRenderer() {
  private val plainFont = Font(Font.MONOSPACED, Font.PLAIN, font.size)
  private val changedFont = plainFont.deriveFont(Font.BOLD)

  init {
    horizontalAlignment = SwingConstants.CENTER
    border = BorderFactory.createEmptyBorder(0, 4, 0, 4)
  }

  override fun getTableCellRendererComponent(
      table: JTable,
      value: Any?,
      isSelected: Boolean,
      hasFocus: Boolean,
      row: Int,
      column: Int,
  ): Component {
    super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
    val cell = value as? DebuggerMemoryByteCell
    font = if (cell?.changed == true) changedFont else plainFont
    horizontalAlignment = if (column == ASCII_COLUMN) SwingConstants.LEFT else SwingConstants.CENTER
    toolTipText = cell?.accessibleText
    if (!isSelected) {
      foreground =
          if (cell?.changed == true) {
            UIManager.getColor("Component.accentColor") ?: Color(0xb0, 0x5a, 0x00)
          } else {
            UIManager.getColor("Table.foreground") ?: Color.BLACK
          }
      background = UIManager.getColor("Table.background") ?: Color.WHITE
    }
    return this
  }
}

private const val ADDRESS_COLUMN = 0
private const val BYTE_COLUMN_FIRST = 1
private const val BYTES_PER_ROW = 16
private const val BYTE_COLUMN_LAST = BYTE_COLUMN_FIRST + BYTES_PER_ROW - 1
private const val ASCII_COLUMN = BYTE_COLUMN_LAST + 1
private const val TOTAL_COLUMNS = ASCII_COLUMN + 1

private fun formatCellAddress(address: Int): String =
    "$" + String.format(Locale.ROOT, "%04X", address)

private fun formatCellByte(value: Int): String = String.format(Locale.ROOT, "%02X", value)

private fun parseCellByte(value: String): Int? {
  val normalized =
      value.trim().removePrefix("$").removePrefix("0x").removePrefix("0X")
  return normalized.takeIf { it.length in 1..2 && it.all(Char::isDigitOrHex) }?.toIntOrNull(16)
}

private fun Char.isDigitOrHex(): Boolean = isDigit() || lowercaseChar() in 'a'..'f'

private fun requireMemoryPanelEdt(operation: String) {
  check(SwingUtilities.isEventDispatchThread()) { "$operation must run on the EDT" }
}
