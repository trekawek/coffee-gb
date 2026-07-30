package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.debug.trace.ApuTrace
import eu.rekawek.coffeegb.core.debug.trace.CpuInstructionTrace
import eu.rekawek.coffeegb.core.debug.trace.DmaTrace
import eu.rekawek.coffeegb.core.debug.trace.InputTrace
import eu.rekawek.coffeegb.core.debug.trace.InterruptTrace
import eu.rekawek.coffeegb.core.debug.trace.MapperRtcTrace
import eu.rekawek.coffeegb.core.debug.trace.MemoryAccessTrace
import eu.rekawek.coffeegb.core.debug.trace.PpuTrace
import eu.rekawek.coffeegb.core.debug.trace.SerialIrTrace
import eu.rekawek.coffeegb.core.debug.trace.TimerTrace
import eu.rekawek.coffeegb.core.debug.trace.TraceEntry
import eu.rekawek.coffeegb.core.debug.trace.TraceEvent
import eu.rekawek.coffeegb.core.debug.trace.TraceReadResult
import javax.swing.table.AbstractTableModel

/** Snapshot identity attached to every trace page captured by coherent inspection. */
internal data class DebuggerTimelineIdentity(
    val sessionGeneration: Long,
    val snapshotSequence: Long,
) {
  val label: String
    get() = "S$sessionGeneration/#$snapshotSequence"
}

internal data class DebuggerTimelineRow(
    val identity: DebuggerTimelineIdentity,
    val entry: TraceEntry,
    val eventText: String = renderTraceEvent(entry.event()),
)

internal data class DebuggerTimelineUpdate(
    val acceptedRows: Int,
    val discardedRows: Int,
    val warning: String?,
)

/**
 * Strictly sequence-ordered, session-scoped timeline storage for the desktop table.
 *
 * The emulator ring and this UI have separate bounds. A page can therefore report emulator-side
 * loss while this model also evicts old rendered rows to stay responsive.
 */
internal class DebuggerTimelineTableModel(
    retentionLimit: Int = MAX_RETAINED_ROWS,
) : AbstractTableModel() {
  private val rows = ArrayList<DebuggerTimelineRow>()
  private var retentionLimit = retentionLimit
  private var generation: Long? = null
  private var lastSequence = -1L

  init {
    require(retentionLimit in 1..MAX_RETAINED_ROWS) {
      "Timeline retention must be between 1 and $MAX_RETAINED_ROWS rows"
    }
  }

  override fun getRowCount(): Int = rows.size

  override fun getColumnCount(): Int = COLUMNS.size

  override fun getColumnName(column: Int): String = COLUMNS[column]

  override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
    val row = rows[rowIndex]
    return when (columnIndex) {
      0 -> row.entry.sequence().toString()
      1 -> row.entry.masterTick().toString()
      2 -> row.identity.label
      3 -> row.entry.category().name
      4 -> row.entry.source().name
      5 -> row.eventText
      else -> error("Unknown timeline column: $columnIndex")
    }
  }

  fun append(
      identity: DebuggerTimelineIdentity,
      page: TraceReadResult,
  ): DebuggerTimelineUpdate {
    if (generation != null && generation != identity.sessionGeneration) clear()
    generation = identity.sessionGeneration

    var discarded = 0
    page.entries().forEach { entry ->
      if (entry.sequence() <= lastSequence) {
        discarded++
      } else {
        rows += DebuggerTimelineRow(identity, entry)
        lastSequence = entry.sequence()
      }
    }

    val evicted = (rows.size - retentionLimit).coerceAtLeast(0)
    if (evicted > 0) rows.subList(0, evicted).clear()
    if (page.entries().isNotEmpty() || evicted > 0) fireTableDataChanged()

    val warnings = ArrayList<String>(3)
    if (page.missedEventCount() > 0) {
      warnings += "missed ${page.missedEventCount()} events before this page"
    }
    if (page.droppedEventCount() > 0) {
      warnings += "emulator trace buffer dropped ${page.droppedEventCount()} events total"
    }
    if (evicted > 0) warnings += "desktop view evicted $evicted oldest rows"
    if (discarded > 0) warnings += "ignored $discarded duplicate or out-of-order rows"
    return DebuggerTimelineUpdate(
        acceptedRows = page.entries().size - discarded,
        discardedRows = discarded,
        warning = if (warnings.isEmpty()) null else warnings.joinToString("; "),
    )
  }

  fun clear() {
    if (rows.isNotEmpty()) {
      rows.clear()
      fireTableDataChanged()
    }
    generation = null
    lastSequence = -1L
  }

  fun setRetentionLimit(value: Int) {
    require(value in 1..MAX_RETAINED_ROWS) {
      "Timeline retention must be between 1 and $MAX_RETAINED_ROWS rows"
    }
    retentionLimit = value
    val evicted = (rows.size - retentionLimit).coerceAtLeast(0)
    if (evicted > 0) {
      rows.subList(0, evicted).clear()
      fireTableDataChanged()
    }
  }

  fun rowAt(index: Int): DebuggerTimelineRow = rows[index]

  fun copyText(selectedRows: IntArray): String {
    val indexes =
        selectedRows
            .asSequence()
            .filter { it in rows.indices }
            .distinct()
            .sorted()
            .toList()
            .ifEmpty { rows.indices.toList() }
    return buildString {
          append(COLUMNS.joinToString("\t"))
          indexes.forEach { index ->
            val row = rows[index]
            append('\n')
            append(row.entry.sequence())
            append('\t')
            append(row.entry.masterTick())
            append('\t')
            append(row.identity.label)
            append('\t')
            append(row.entry.category())
            append('\t')
            append(row.entry.source())
            append('\t')
            append(row.eventText)
          }
        }
        .trimEnd()
  }

  companion object {
    const val MAX_RETAINED_ROWS = 2_000
    private val COLUMNS =
        arrayOf("Sequence", "Tick", "Snapshot", "Category", "Source", "Event")
  }
}

private fun renderTraceEvent(event: TraceEvent): String =
    when (event) {
      is CpuInstructionTrace ->
          if (event.prefixedOpcode() >= 0) {
            "PC=${word(event.programCounter())} opcode=CB ${byte(event.prefixedOpcode())}"
          } else {
            "PC=${word(event.programCounter())} opcode=${byte(event.opcode())}"
          }
      is MemoryAccessTrace ->
          "${event.access()} ${event.addressSpace()} " +
              "${word(event.address())}=${byte(event.value())}"
      is InterruptTrace -> "${event.kind()} ${event.interrupt()}"
      is PpuTrace ->
          "${event.kind()} frame=${event.ppuFrame()} line=${event.line()} " +
              "dot=${event.dot()} mode=${event.mode()}"
      is DmaTrace ->
          "${event.engine()} ${event.kind()} ${word(event.sourceAddress())}->" +
              "${word(event.destinationAddress())} ${event.bytesTransferred()}/${event.length()}"
      is TimerTrace ->
          "${event.kind()} DIV=${word(event.divider())} TIMA=${byte(event.counter())} " +
              "TMA=${byte(event.modulo())} TAC=${byte(event.control())}"
      is SerialIrTrace -> "${event.endpoint()} ${event.kind()} value=${byte(event.value())}"
      is InputTrace ->
          "${event.kind()} buttons=${byte(event.buttonMask())} changed=${byte(event.changedMask())}"
      is MapperRtcTrace ->
          "${event.kind()} register=${optionalByte(event.register())} value=${event.value()}"
      is ApuTrace ->
          "${event.kind()} channel=${optionalDecimal(event.channel())} " +
              "register=${optionalWord(event.register())} value=${optionalByte(event.value())}"
      else -> event.toString()
    }

private fun byte(value: Int): String = "\$%02X".format(value)

private fun word(value: Int): String = "\$%04X".format(value)

private fun optionalByte(value: Int): String = if (value < 0) "—" else byte(value)

private fun optionalWord(value: Int): String = if (value < 0) "—" else word(value)

private fun optionalDecimal(value: Int): String = if (value < 0) "—" else value.toString()
