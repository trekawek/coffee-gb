package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.debug.DebugCapabilities
import eu.rekawek.coffeegb.core.debug.DebugDisassembler
import eu.rekawek.coffeegb.core.debug.DebugMemoryBlock
import eu.rekawek.coffeegb.core.debug.DebugSnapshot
import eu.rekawek.coffeegb.core.cpu.Opcodes
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/** Structured, read-only execution view used by the realtime debugger workspace. */
internal class DebuggerExecutionPanel(
    private val copyToClipboard: (String) -> Unit,
) : JPanel(BorderLayout(8, 8)) {
  private val registerValues =
      listOf("AF", "BC", "DE", "HL", "SP", "PC").associateWith { name -> registerCell(name) }
  private val flagValues =
      listOf("Z", "N", "H", "C").associateWith { name -> flagCell(name) }
  private val cpuState = valueLabel("CPU state")
  private val opcode = valueLabel("Opcode")
  private val machineCycle = valueLabel("Machine cycle")
  private val speed = valueLabel("Speed")
  private val retired = valueLabel("Retired instructions")
  private val timing = valueLabel("Frame timing")
  private val instructionModel = InstructionTableModel()
  private val stackModel = StackTableModel()
  internal val instructionTable = JTable(instructionModel)
  internal val stackTable = JTable(stackModel)
  internal val coherenceLabel = JLabel("Waiting for a coherent snapshot")
  private var lastSnapshot: DebugSnapshot? = null

  init {
    border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    getAccessibleContext().accessibleName = "Execution inspector"
    getAccessibleContext().accessibleDescription =
        "Live CPU registers, flags, current instruction, execution state, and stack"

    val registers = JPanel(GridLayout(2, 3, 6, 6)).apply {
      border = BorderFactory.createTitledBorder("Registers")
      registerValues.values.forEach { cell -> add(cell.panel) }
    }
    val flags = JPanel(GridLayout(1, 4, 6, 0)).apply {
      border = BorderFactory.createTitledBorder("Flags")
      flagValues.values.forEach(::add)
    }
    val scalar = JPanel(BorderLayout(6, 6)).apply {
      add(registers, BorderLayout.CENTER)
      add(flags, BorderLayout.SOUTH)
    }

    val machine = JPanel(GridLayout(3, 2, 8, 4)).apply {
      border = BorderFactory.createTitledBorder("Execution")
      add(labeledValue("State", cpuState))
      add(labeledValue("Opcode", opcode))
      add(labeledValue("M-cycle", machineCycle))
      add(labeledValue("Clock", speed))
      add(labeledValue("Retired", retired))
      add(labeledValue("Position", timing))
    }

    val summary = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scalar, machine).apply {
      resizeWeight = 0.58
      isContinuousLayout = true
      border = null
    }

    configureTable(instructionTable, "Instructions around the program counter")
    instructionTable.accessibleContext.accessibleDescription =
        "Best-effort instruction context before and after the current program counter"
    instructionTable.columnModel.getColumn(0).preferredWidth = 90
    instructionTable.columnModel.getColumn(1).preferredWidth = 420
    instructionTable.columnModel.getColumn(2).preferredWidth = 260
    instructionTable.minimumSize = Dimension(320, 92)

    configureTable(stackTable, "Stack bytes")
    stackTable.columnModel.getColumn(0).preferredWidth = 70
    stackTable.columnModel.getColumn(1).preferredWidth = 100
    stackTable.columnModel.getColumn(2).preferredWidth = 80
    stackTable.columnModel.getColumn(3).preferredWidth = 260

    val instruction = JPanel(BorderLayout()).apply {
      border = BorderFactory.createTitledBorder("Instructions around PC")
      add(JScrollPane(instructionTable), BorderLayout.CENTER)
    }
    val stack = JPanel(BorderLayout()).apply {
      border = BorderFactory.createTitledBorder("Stack from SP")
      add(JScrollPane(stackTable), BorderLayout.CENTER)
    }
    val lower = JSplitPane(JSplitPane.VERTICAL_SPLIT, instruction, stack).apply {
      resizeWeight = 0.28
      isContinuousLayout = true
      border = null
    }

    coherenceLabel.border = BorderFactory.createEmptyBorder(2, 4, 1, 4)
    coherenceLabel.accessibleContext.accessibleName = "Execution snapshot status"

    add(summary, BorderLayout.NORTH)
    add(lower, BorderLayout.CENTER)
    add(coherenceLabel, BorderLayout.SOUTH)
    clear()
  }

  fun render(
      snapshot: DebugSnapshot,
      instruction: DebugMemoryBlock?,
      stack: DebugMemoryBlock?,
      capabilities: DebugCapabilities,
  ) {
    val registers = snapshot.registers()
    registerValues.getValue("AF").value.text = DebuggerPresentation.formatWord(registers.af())
    registerValues.getValue("BC").value.text = DebuggerPresentation.formatWord(registers.bc())
    registerValues.getValue("DE").value.text = DebuggerPresentation.formatWord(registers.de())
    registerValues.getValue("HL").value.text = DebuggerPresentation.formatWord(registers.hl())
    registerValues.getValue("SP").value.text = DebuggerPresentation.formatWord(registers.sp())
    registerValues.getValue("PC").value.text = DebuggerPresentation.formatWord(registers.pc())
    flagValues.forEach { (name, cell) ->
      val bit = when (name) {
        "Z" -> 7
        "N" -> 6
        "H" -> 5
        else -> 4
      }
      cell.setActive(registers.f() and (1 shl bit) != 0)
    }

    val execution = snapshot.execution()
    cpuState.text = execution.cpuState().name.replace('_', ' ')
    opcode.text =
        when {
          execution.extendedOpcode() >= 0 ->
              "CB ${DebuggerPresentation.formatByte(execution.extendedOpcode())}"
          execution.opcode() >= 0 -> DebuggerPresentation.formatByte(execution.opcode())
          else -> "—"
        }
    machineCycle.text = execution.machineCycle().toString()
    speed.text = if (execution.doubleSpeed()) "Double" else "Normal"
    retired.text = "%,d".format(execution.retiredInstructions())
    timing.text = "F${snapshot.frame()} · ${snapshot.framePosition()}"

    instructionModel.render(snapshot, instruction)?.let { currentRow ->
      instructionTable.selectionModel.setSelectionInterval(currentRow, currentRow)
      instructionTable.scrollRectToVisible(instructionTable.getCellRect(currentRow, 0, true))
    } ?: instructionTable.clearSelection()
    val stackView =
        DebuggerPresentation.stack(
            snapshot,
            capabilities,
            stack?.let { DebuggerMemoryCapture(DebuggerSnapshotIdentity.from(snapshot), it) },
            STACK_BYTES,
        )
    stackModel.render(stackView)
    lastSnapshot = snapshot
    coherenceLabel.text =
        "Session ${snapshot.sessionGeneration()}, snapshot ${snapshot.sequence()} · " +
            "tick ${snapshot.masterTick()} · ${if (snapshot.paused()) "PAUSED" else "LIVE"}"
  }

  /** Keeps command completions visually current while the next coherent memory capture arrives. */
  fun renderSnapshot(snapshot: DebugSnapshot, capabilities: DebugCapabilities) {
    render(snapshot, null, null, capabilities)
    instructionModel.message("Capturing instruction context at the new PC…")
    instructionTable.clearSelection()
    stackModel.message("Capturing stack bytes at the new SP…")
  }

  fun clear(message: String = "No emulation session") {
    registerValues.values.forEach { it.value.text = "—" }
    flagValues.values.forEach { it.setActive(false) }
    cpuState.text = "—"
    opcode.text = "—"
    machineCycle.text = "—"
    speed.text = "—"
    retired.text = "—"
    timing.text = "—"
    instructionModel.message(message)
    instructionTable.clearSelection()
    stackModel.message(message)
    coherenceLabel.text = message
    lastSnapshot = null
  }

  fun copyText(): String {
    val snapshot = lastSnapshot ?: return coherenceLabel.text
    val registers = snapshot.registers()
    return buildString {
      appendLine(coherenceLabel.text)
      appendLine(
          "AF=${DebuggerPresentation.formatWord(registers.af())} " +
              "BC=${DebuggerPresentation.formatWord(registers.bc())} " +
              "DE=${DebuggerPresentation.formatWord(registers.de())} " +
              "HL=${DebuggerPresentation.formatWord(registers.hl())} " +
              "SP=${DebuggerPresentation.formatWord(registers.sp())} " +
              "PC=${DebuggerPresentation.formatWord(registers.pc())}"
      )
      appendLine("Flags ${DebuggerPresentation.formatFlags(registers.f())}")
      appendLine(instructionModel.copyText())
      append(stackModel.copyText())
    }.trimEnd()
  }

  fun copyToClipboard() {
    copyToClipboard(copyText())
  }

  private fun configureTable(table: JTable, name: String) {
    table.fillsViewportHeight = true
    table.autoCreateRowSorter = false
    table.selectionModel.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
    table.accessibleContext.accessibleName = name
    (table.getDefaultRenderer(Any::class.java) as? DefaultTableCellRenderer)?.border =
        BorderFactory.createEmptyBorder(0, 5, 0, 5)
  }

  private data class RegisterCell(val panel: JPanel, val value: JLabel)

  private fun registerCell(name: String): RegisterCell {
    val value = JLabel("—", SwingConstants.RIGHT).apply {
      font = Font(Font.MONOSPACED, Font.BOLD, font.size + 2)
      getAccessibleContext().accessibleName = "$name register value"
    }
    val panel = JPanel(BorderLayout(8, 0)).apply {
      border = BorderFactory.createCompoundBorder(
          BorderFactory.createEtchedBorder(),
          BorderFactory.createEmptyBorder(5, 8, 5, 8),
      )
      add(JLabel(name), BorderLayout.WEST)
      add(value, BorderLayout.CENTER)
    }
    return RegisterCell(panel, value)
  }

  private fun flagCell(name: String): FlagCell = FlagCell(name)

  private class FlagCell(private val name: String) : JPanel(BorderLayout()) {
    private val label = JLabel(name, SwingConstants.CENTER)

    init {
      border = BorderFactory.createEmptyBorder(5, 8, 5, 8)
      add(label)
      getAccessibleContext().accessibleName = "$name CPU flag"
    }

    fun setActive(active: Boolean) {
      isOpaque = true
      background =
          if (active) UIManager.getColor("Table.selectionBackground") ?: Color(0x3B, 0x78, 0xE7)
          else UIManager.getColor("Panel.background")
      label.foreground =
          if (active) UIManager.getColor("Table.selectionForeground") ?: Color.WHITE
          else UIManager.getColor("Label.foreground")
      label.text = "$name ${if (active) "1" else "0"}"
      getAccessibleContext().accessibleDescription =
          "$name flag is ${if (active) "set" else "clear"}"
    }
  }

  private fun valueLabel(name: String): JLabel = JLabel("—").apply {
    font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
    getAccessibleContext().accessibleName = name
  }

  private fun labeledValue(name: String, value: JLabel): JPanel =
      JPanel(FlowLayout(FlowLayout.LEADING, 6, 2)).apply {
        add(JLabel("$name:"))
        add(value)
      }

  private class InstructionTableModel : AbstractTableModel() {
    private data class Row(
        val address: String,
        val instruction: String,
        val source: String,
        val offset: Int = -1,
    )

    private var rows = listOf(Row("—", "Waiting for a coherent snapshot", ""))

    override fun getRowCount(): Int = rows.size

    override fun getColumnCount(): Int = COLUMNS.size

    override fun getColumnName(column: Int): String = COLUMNS[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
        when (columnIndex) {
          0 -> rows[rowIndex].address
          1 -> rows[rowIndex].instruction
          else -> rows[rowIndex].source
        }

    fun render(snapshot: DebugSnapshot, memory: DebugMemoryBlock?): Int? {
      val pc = snapshot.registers().pc()
      if (memory == null) {
        rows = listOf(Row(DebuggerPresentation.formatWord(pc), "Live instruction bytes are outside the safe memory views", ""))
        fireTableDataChanged()
        return null
      }
      val currentOffset = pc - memory.startAddress()
      if (currentOffset !in 0 until memory.length()) {
        rows =
            listOf(
                Row(
                    DebuggerPresentation.formatWord(pc),
                    "Current PC is outside the captured instruction context",
                    "",
                ))
        fireTableDataChanged()
        return null
      }

      val current = rowAt(memory, currentOffset, inferredPredecessor = false)
      if (current == null) {
        rows =
            listOf(
                Row(
                    DebuggerPresentation.formatWord(pc),
                    "Instruction bytes are truncated in this capture",
                    memory.addressSpace().name,
                ))
        fireTableDataChanged()
        return null
      }
      val previous = precedingRows(memory, currentOffset)
      val following = followingRows(memory, currentOffset)
      rows = previous + following
      val selected = rows.indexOfFirst { it.offset == currentOffset }
      fireTableDataChanged()
      return selected.takeIf { it >= 0 }
    }

    fun message(value: String) {
      rows = listOf(Row("—", value, ""))
      fireTableDataChanged()
    }

    fun copyText(): String =
        rows.joinToString("\n") { row ->
          listOf(row.address, row.instruction, row.source).filter(String::isNotBlank).joinToString("  ")
        }

    private fun precedingRows(memory: DebugMemoryBlock, currentOffset: Int): List<Row> {
      val reverse = mutableListOf<Row>()
      var endOffset = currentOffset
      repeat(PREVIOUS_CONTEXT_ROWS) {
        val previous =
            (MAX_INSTRUCTION_BYTES downTo 1).firstNotNullOfOrNull { length ->
              val startOffset = endOffset - length
              rowAt(memory, startOffset, inferredPredecessor = true)
                  ?.takeIf { instructionLength(memory, startOffset) == length }
            } ?: return reverse.asReversed()
        reverse += previous
        endOffset = previous.offset
      }
      return reverse.asReversed()
    }

    private fun followingRows(memory: DebugMemoryBlock, currentOffset: Int): List<Row> {
      val result = mutableListOf<Row>()
      var offset = currentOffset
      repeat(FOLLOWING_CONTEXT_ROWS + 1) {
        val row = rowAt(memory, offset, inferredPredecessor = false) ?: return result
        result += row
        offset += instructionLength(memory, offset)
      }
      return result
    }

    private fun rowAt(
        memory: DebugMemoryBlock,
        offset: Int,
        inferredPredecessor: Boolean,
    ): Row? {
      if (offset !in 0 until memory.length()) return null
      val length = instructionLength(memory, offset)
      if (offset + length > memory.length()) return null
      val instruction =
          DebugMemoryBlock(
              memory.addressSpace(),
              memory.startAddress() + offset,
              ByteArray(length) { index -> memory.byteAt(offset + index) },
          )
      val disassembly =
          runCatching { DebugDisassembler.disassemble(instruction) }.getOrNull() ?: return null
      val sourceMarker = " [best-effort: "
      val sourceAt = disassembly.indexOf(sourceMarker)
      val body = if (sourceAt >= 0) disassembly.substring(0, sourceAt) else disassembly
      val source =
          if (sourceAt >= 0) disassembly.substring(sourceAt + sourceMarker.length).removeSuffix("]")
          else memory.addressSpace().name
      return Row(
          DebuggerPresentation.formatWord(memory.startAddress() + offset),
          body.substringAfter(':', body).trim(),
          if (inferredPredecessor) "$source; inferred pre-PC boundary" else source,
          offset,
      )
    }

    private fun instructionLength(memory: DebugMemoryBlock, offset: Int): Int {
      val opcode = memory.unsignedByteAt(offset)
      return if (opcode == 0xcb) 2 else (Opcodes.COMMANDS[opcode]?.operandLength ?: 0) + 1
    }

    private companion object {
      val COLUMNS = arrayOf("PC", "Bytes and instruction", "Memory view")
      const val MAX_INSTRUCTION_BYTES = 3
      const val PREVIOUS_CONTEXT_ROWS = 4
      const val FOLLOWING_CONTEXT_ROWS = 6
    }
  }

  private class StackTableModel : AbstractTableModel() {
    private var rows: List<Array<String>> = emptyList()
    private var unavailable = "Waiting for a coherent snapshot"

    override fun getRowCount(): Int = if (rows.isEmpty()) 1 else rows.size

    override fun getColumnCount(): Int = COLUMNS.size

    override fun getColumnName(column: Int): String = COLUMNS[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
        if (rows.isEmpty()) {
          if (columnIndex == 3) unavailable else ""
        } else {
          rows[rowIndex][columnIndex]
        }

    fun render(view: DebuggerStackView) {
      unavailable = view.explanation ?: "Stack is unavailable"
      rows =
          view.entries.map { entry ->
            arrayOf(
                if (entry.offset == 0) "SP →" else "+${entry.offset}",
                entry.addressText,
                entry.valueText,
                if (entry.value in 0x20..0x7e) entry.value.toChar().toString() else "·",
            )
          }
      fireTableDataChanged()
    }

    fun message(value: String) {
      rows = emptyList()
      unavailable = value
      fireTableDataChanged()
    }

    fun copyText(): String =
        if (rows.isEmpty()) unavailable
        else rows.joinToString("\n") { row -> row.joinToString("  ").trim() }

    private companion object {
      val COLUMNS = arrayOf("", "Address", "Value", "ASCII")
    }
  }

  private companion object {
    const val STACK_BYTES = 16
  }
}
