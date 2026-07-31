package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.debug.DebugCapabilities
import eu.rekawek.coffeegb.core.debug.DebugDisassembler
import eu.rekawek.coffeegb.core.debug.DebugMemoryBlock
import eu.rekawek.coffeegb.core.debug.DebugSnapshot
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

    configureTable(instructionTable, "Current instruction")
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
      border = BorderFactory.createTitledBorder("Instruction at PC")
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

    instructionModel.render(snapshot, instruction)
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
    instructionModel.message("Capturing the instruction at the new PC…")
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
    private var row = arrayOf("—", "Waiting for a coherent snapshot", "")

    override fun getRowCount(): Int = 1

    override fun getColumnCount(): Int = COLUMNS.size

    override fun getColumnName(column: Int): String = COLUMNS[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = row[columnIndex]

    fun render(snapshot: DebugSnapshot, memory: DebugMemoryBlock?) {
      if (memory == null) {
        message("Live instruction bytes are outside the safe memory views")
        row[0] = DebuggerPresentation.formatWord(snapshot.registers().pc())
        return
      }
      val disassembly =
          runCatching { DebugDisassembler.disassemble(memory) }
              .getOrElse { "Disassembly unavailable: ${it.message.orEmpty()}" }
      val sourceMarker = " [best-effort: "
      val sourceAt = disassembly.indexOf(sourceMarker)
      val body = if (sourceAt >= 0) disassembly.substring(0, sourceAt) else disassembly
      val detail = body.substringAfter(':', body).trim()
      val source =
          if (sourceAt >= 0) disassembly.substring(sourceAt + sourceMarker.length).removeSuffix("]")
          else memory.addressSpace().name
      row = arrayOf(DebuggerPresentation.formatWord(memory.startAddress()), detail, source)
      fireTableRowsUpdated(0, 0)
    }

    fun message(value: String) {
      row = arrayOf("—", value, "")
      fireTableRowsUpdated(0, 0)
    }

    fun copyText(): String = row.joinToString("  ").trim()

    private companion object {
      val COLUMNS = arrayOf("PC", "Bytes and instruction", "Memory view")
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
