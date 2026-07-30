package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.debug.DebugAddressSpace
import eu.rekawek.coffeegb.core.debug.DebugCapabilities
import eu.rekawek.coffeegb.core.debug.DebugInspectionAnchor
import eu.rekawek.coffeegb.core.debug.DebugInspectionResult
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess
import eu.rekawek.coffeegb.core.debug.DebugMemoryBlock
import eu.rekawek.coffeegb.core.debug.DebugMemoryRequest
import eu.rekawek.coffeegb.core.debug.DebugResult
import eu.rekawek.coffeegb.core.debug.DebugSnapshot
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugCounterCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugInterruptCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugMemoryCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugOpcodeCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPcCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPpuCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugSerialCondition
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryStatus
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryTruncationReason
import eu.rekawek.coffeegb.core.debug.history.DebugReverseStepResult
import java.util.Collections
import java.util.Locale

/**
 * Pure formatting and validation for the desktop debugger.
 *
 * This layer deliberately owns no Swing component and never waits for a debug command. The EDT
 * can therefore transform completed debug DTOs into immutable values and unit tests can exercise
 * the same code in a headless JVM.
 */
internal object DebuggerPresentation {

  private val addressToken = "(?:0[xX]|\\${'$'})?[0-9A-Fa-f]{1,4}"
  private val addressPattern = Regex("^($addressToken)$")
  private val rangePattern = Regex("^($addressToken)(?:\\s*(?:-|\\.\\.|:)\\s*($addressToken))?$")
  private val bytePattern = Regex("^(?:0[xX]|\\${'$'})?([0-9A-Fa-f]{1,2})$")

  fun parseAddress(text: String): DebuggerParsedValue<Int> {
    val input = text.trim()
    val match = addressPattern.matchEntire(input)
        ?: return DebuggerParsedValue.invalid(addressError())
    return DebuggerParsedValue.valid(parseHexToken(match.groupValues[1]))
  }

  fun parseAddressRange(text: String): DebuggerParsedValue<DebuggerAddressRange> {
    val input = text.trim()
    val match = rangePattern.matchEntire(input)
        ?: return DebuggerParsedValue.invalid(
            "Use one 16-bit hexadecimal address or an inclusive range, for example " +
                "${'$'}C000-${'$'}C0FF."
        )
    val start = parseHexToken(match.groupValues[1])
    val end = match.groupValues[2].takeIf(String::isNotEmpty)?.let(::parseHexToken) ?: start
    if (end < start) {
      return DebuggerParsedValue.invalid(
          "Range end ${formatWord(end)} must not precede ${formatWord(start)}."
      )
    }
    return DebuggerParsedValue.valid(DebuggerAddressRange(start, end))
  }

  fun parseByte(text: String, fieldName: String = "Value"): DebuggerParsedValue<Int> {
    val input = text.trim()
    val match = bytePattern.matchEntire(input)
        ?: return DebuggerParsedValue.invalid(
            "$fieldName must be an 8-bit hexadecimal value, for example ${'$'}7F."
        )
    return DebuggerParsedValue.valid(parseHexToken(match.groupValues[1]))
  }

  fun parseProgramCounterCondition(text: String): DebuggerParsedValue<DebugPcCondition> =
      parseAddressRange(text).map { DebugPcCondition(it.startAddress, it.endAddress) }

  fun parseMemoryCondition(
      rangeText: String,
      access: DebugMemoryAccess,
      valueText: String = "",
      maskText: String = "",
  ): DebuggerParsedValue<DebugMemoryCondition> {
    val range = parseAddressRange(rangeText)
    if (!range.isValid) return DebuggerParsedValue.invalid(range.error!!)
    if (valueText.isBlank()) {
      if (maskText.isNotBlank()) {
        return DebuggerParsedValue.invalid("A value is required when a value mask is set.")
      }
      return DebuggerParsedValue.valid(
          DebugMemoryCondition(access, range.value!!.startAddress, range.value.endAddress)
      )
    }
    val value = parseByte(valueText)
    if (!value.isValid) return DebuggerParsedValue.invalid(value.error!!)
    if (maskText.isBlank()) {
      return DebuggerParsedValue.valid(
          DebugMemoryCondition(
              access,
              range.value!!.startAddress,
              range.value.endAddress,
              value.value!!,
          )
      )
    }
    val mask = parseByte(maskText, "Mask")
    if (!mask.isValid) return DebuggerParsedValue.invalid(mask.error!!)
    if (mask.value == 0) {
      return DebuggerParsedValue.invalid("Mask must contain at least one set bit.")
    }
    return DebuggerParsedValue.valid(
        DebugMemoryCondition(
            access,
            range.value!!.startAddress,
            range.value.endAddress,
            value.value!!,
            mask.value!!,
        )
    )
  }

  fun formatByte(value: Int): String {
    require(value in 0..0xff) { "Byte value is outside 0..255: $value" }
    return "${'$'}${value.toString(16).padStart(2, '0').uppercase(Locale.ROOT)}"
  }

  fun formatWord(value: Int): String {
    require(value in 0..0xffff) { "Word value is outside 0..65535: $value" }
    return "${'$'}${value.toString(16).padStart(4, '0').uppercase(Locale.ROOT)}"
  }

  /** Explicit form is suitable for labels and screen readers. */
  fun formatFlags(value: Int): String {
    require(value in 0..0xff && value and 0x0f == 0) { "Invalid Game Boy F register: $value" }
    return listOf(7 to "Z", 6 to "N", 5 to "H", 4 to "C")
        .joinToString(" ") { (bit, name) -> "$name=${if (value and (1 shl bit) != 0) 1 else 0}" }
  }

  /** Compact four-character form, with a dash for each clear flag. */
  fun formatCompactFlags(value: Int): String {
    require(value in 0..0xff && value and 0x0f == 0) { "Invalid Game Boy F register: $value" }
    return listOf(7 to 'Z', 6 to 'N', 5 to 'H', 4 to 'C')
        .joinToString("") { (bit, name) -> if (value and (1 shl bit) != 0) "$name" else "-" }
  }

  fun snapshot(snapshot: DebugSnapshot): DebuggerSnapshotView {
    val registers = snapshot.registers
    return DebuggerSnapshotView(
        identity = DebuggerSnapshotIdentity.from(snapshot),
        paused = snapshot.paused,
        frame = snapshot.frame,
        framePosition = snapshot.framePosition,
        timingText =
            "Frame ${snapshot.frame}, position ${snapshot.framePosition}, " +
                "tick ${snapshot.masterTick}",
        registers =
            DebuggerRegisterView(
                a = formatByte(registers.a),
                f = formatByte(registers.f),
                b = formatByte(registers.b),
                c = formatByte(registers.c),
                d = formatByte(registers.d),
                e = formatByte(registers.e),
                h = formatByte(registers.h),
                l = formatByte(registers.l),
                af = formatWord(registers.af()),
                bc = formatWord(registers.bc()),
                de = formatWord(registers.de()),
                hl = formatWord(registers.hl()),
                sp = formatWord(registers.sp),
                pc = formatWord(registers.pc),
                flags = formatFlags(registers.f),
                compactFlags = formatCompactFlags(registers.f),
            ),
        cpuState = humanize(snapshot.execution.cpuState.name),
        opcode =
            when {
              snapshot.execution.extendedOpcode >= 0 ->
                  "${formatByte(0xcb)} ${formatByte(snapshot.execution.extendedOpcode)}"
              snapshot.execution.opcode >= 0 -> formatByte(snapshot.execution.opcode)
              else -> "Unavailable"
            },
        mapper = snapshot.mapper.mapperId,
    )
  }

  fun capabilities(capabilities: DebugCapabilities): DebuggerCapabilityView =
      DebuggerCapabilityView(
          pauseResume = capabilities.pauseResume,
          snapshots = capabilities.snapshot,
          instructionStep = capabilities.instructionStep,
          machineCycleStep = capabilities.machineCycleStep,
          frameStep = capabilities.frameStep,
          memoryRead = capabilities.memoryRead,
          maxMemoryReadLength = capabilities.maxMemoryReadLength,
          coherentInspection = capabilities.coherentInspection(),
          maxInspectionBlocks = capabilities.maxInspectionBlocks(),
          maxInspectionBytes = capabilities.maxInspectionBytes(),
          breakpointKinds =
              immutableCopy(capabilities.breakpointKinds.map { humanize(it.name) }.sorted()),
          maxBreakpoints = capabilities.maxBreakpoints,
          reverseHistory = capabilities.history.checkpointHistory,
          reverseFrame = capabilities.history.reverseFrame,
          reverseInstruction = capabilities.history.reverseInstruction,
      )

  /** Presents a block whose coherence is guaranteed by its containing inspection result. */
  fun memory(
      inspection: DebugInspectionResult,
      block: DebugMemoryBlock,
      bytesPerRow: Int = 16,
  ): DebuggerMemoryView {
    require(
        inspection.anchoredBlocks.any { it === block } ||
            inspection.memoryBlocks.any { it === block }
    ) { "Memory block is not owned by this inspection result" }
    val identity = DebuggerSnapshotIdentity.from(inspection.snapshot)
    return memory(identity, DebuggerMemoryCapture(identity, block), bytesPerRow)
  }

  fun breakpointRows(
      breakpoints: Collection<DebugBreakpoint>,
      capabilities: DebugCapabilities? = null,
  ): List<DebuggerBreakpointRow> =
      immutableCopy(
          breakpoints.map { breakpoint ->
            val kind = breakpoint.condition.kind()
            val supported = capabilities?.supports(kind) ?: true
            val kindText = humanize(kind.name)
            val conditionText = formatCondition(breakpoint.condition)
            DebuggerBreakpointRow(
                id = breakpoint.id.value,
                enabled = breakpoint.enabled,
                supported = supported,
                kind = kindText,
                condition = conditionText,
                accessibilityText =
                    "Breakpoint ${breakpoint.id.value}, $kindText, $conditionText, " +
                        when {
                          !supported -> "unsupported in this session"
                          breakpoint.enabled -> "enabled"
                          else -> "disabled"
                        },
            )
          }
      )

  fun memory(
      expectedIdentity: DebuggerSnapshotIdentity,
      capture: DebuggerMemoryCapture,
      bytesPerRow: Int = 16,
  ): DebuggerMemoryView {
    require(bytesPerRow in 1..32) { "Memory row width must be between 1 and 32" }
    val coherence = capture.identity.coherenceWith(expectedIdentity)
    val rows = ArrayList<DebuggerMemoryRow>()
    var index = 0
    while (index < capture.block.length()) {
      val count = minOf(bytesPerRow, capture.block.length() - index)
      val bytes = (0 until count).map { capture.block.unsignedByteAt(index + it) }
      rows +=
          DebuggerMemoryRow(
              address = capture.block.startAddress() + index,
              addressText = formatWord(capture.block.startAddress() + index),
              bytes = immutableCopy(bytes),
              hexText = bytes.joinToString(" ") { formatByte(it).removePrefix("${'$'}") },
              asciiText = bytes.joinToString("") { byte ->
                if (byte in 0x20..0x7e) byte.toChar().toString() else "."
              },
          )
      index += count
    }
    return DebuggerMemoryView(
        identity = capture.identity,
        addressSpace = capture.block.addressSpace(),
        startAddress = capture.block.startAddress(),
        length = capture.block.length(),
        coherence = coherence,
        coherenceExplanation = coherence.explanation,
        rows = immutableCopy(rows),
    )
  }

  fun stack(
      snapshot: DebugSnapshot,
      capabilities: DebugCapabilities,
      capture: DebuggerMemoryCapture?,
      requestedBytes: Int = 16,
  ): DebuggerStackView {
    require(requestedBytes in 1..256) { "Stack length must be between 1 and 256" }
    val identity = DebuggerSnapshotIdentity.from(snapshot)
    if (!capabilities.memoryRead) {
      return DebuggerStackView.unavailable(
          identity,
          requestedBytes,
          "Stack is unavailable because this session does not support memory reads.",
      )
    }
    if (capture == null) {
      return DebuggerStackView.unavailable(
          identity,
          requestedBytes,
          "Stack is unavailable until memory at SP ${formatWord(snapshot.registers.sp)} is read.",
      )
    }
    val coherence = capture.identity.coherenceWith(identity)
    if (coherence != DebuggerMemoryCoherence.COHERENT) {
      return DebuggerStackView.unavailable(
          identity,
          requestedBytes,
          "Stack is unavailable because ${coherence.explanation!!.replaceFirstChar { it.lowercase() }}",
      )
    }
    if (capture.block.addressSpace() != DebugAddressSpace.SYSTEM_BUS) {
      return DebuggerStackView.unavailable(
          identity,
          requestedBytes,
          "Stack requires a system-bus memory read at SP ${formatWord(snapshot.registers.sp)}.",
      )
    }
    val sp = snapshot.registers.sp
    if (sp < capture.block.startAddress() || sp >= capture.block.endExclusive()) {
      return DebuggerStackView.unavailable(
          identity,
          requestedBytes,
          "Stack memory does not include SP ${formatWord(sp)}.",
      )
    }
    val addressSpaceBytes = 0x10000 - sp
    val blockBytes = capture.block.endExclusive() - sp
    val count = minOf(requestedBytes, addressSpaceBytes, blockBytes)
    val startIndex = sp - capture.block.startAddress()
    val entries =
        (0 until count).map { offset ->
          val value = capture.block.unsignedByteAt(startIndex + offset)
          DebuggerStackEntry(
              offset = offset,
              address = sp + offset,
              addressText = formatWord(sp + offset),
              value = value,
              valueText = formatByte(value),
          )
        }
    val clipped = count < requestedBytes
    val explanation =
        when {
          !clipped -> null
          addressSpaceBytes < requestedBytes ->
              "Stack view is clipped at the end of the 16-bit address space."
          else -> "Stack view is clipped to the returned memory block."
        }
    return DebuggerStackView(
        identity = identity,
        available = true,
        requestedBytes = requestedBytes,
        entries = immutableCopy(entries),
        clipped = clipped,
        explanation = explanation,
    )
  }

  /** Uses the stack-pointer-relative block captured with the inspection's coherent snapshot. */
  fun stack(
      inspection: DebugInspectionResult,
      capabilities: DebugCapabilities,
      requestedBytes: Int = 16,
  ): DebuggerStackView {
    val stackBlock =
        inspection.request.anchoredRequests
            .withIndex()
            .firstOrNull { (index, request) ->
              request.anchor == DebugInspectionAnchor.STACK_POINTER &&
                  inspection.anchoredBlocks[index].let { block ->
                    inspection.snapshot.registers.sp in
                        block.startAddress() until block.endExclusive()
                  }
            }
            ?.let { inspection.anchoredBlocks[it.index] }
    val identity = DebuggerSnapshotIdentity.from(inspection.snapshot)
    return stack(
        inspection.snapshot,
        capabilities,
        stackBlock?.let { DebuggerMemoryCapture(identity, it) },
        requestedBytes,
    )
  }

  fun history(
      capabilities: DebugCapabilities,
      status: DebugHistoryStatus?,
  ): DebuggerHistoryView {
    if (!capabilities.history.checkpointHistory) {
      val unsupported = DebuggerActionState.unavailable("Reverse history is unsupported.")
      return DebuggerHistoryView(
          enabled = false,
          checkpointCount = 0,
          retainedBytes = 0,
          cursor = "Unavailable",
          retainedRange = "Unavailable",
          futureCheckpointCount = 0,
          truncation = "None",
          reverseFrame = unsupported,
          reverseInstruction = unsupported,
      )
    }
    if (status == null) {
      val waiting = DebuggerActionState.unavailable("Waiting for reverse-history status.")
      return DebuggerHistoryView(
          enabled = false,
          checkpointCount = 0,
          retainedBytes = 0,
          cursor = "Waiting for status",
          retainedRange = "Waiting for status",
          futureCheckpointCount = 0,
          truncation = "None",
          reverseFrame = waiting,
          reverseInstruction = waiting,
      )
    }
    val enabled = status.configuration.enabled
    val cursor = status.cursor
    val oldest = status.oldest
    val newest = status.newest
    val commonUnavailable =
        when {
          !enabled -> "Enable reverse history to step backward."
          status.checkpointCount == 0 -> "Waiting for the first frame checkpoint."
          else -> null
        }
    val frameReason =
        commonUnavailable
            ?: when {
              !capabilities.history.reverseFrame -> "Reverse-frame stepping is unsupported."
              cursor!!.framePosition == 0 && cursor.masterTick <= oldest!!.masterTick ->
                  "The oldest retained frame is selected."
              else -> null
            }
    val instructionReason =
        commonUnavailable
            ?: when {
              !capabilities.history.reverseInstruction ->
                  "Reverse-instruction stepping is unsupported."
              cursor!!.masterTick <= oldest!!.masterTick ->
                  "The oldest retained instruction range is selected."
              else -> null
            }
    return DebuggerHistoryView(
        enabled = enabled,
        checkpointCount = status.checkpointCount,
        retainedBytes = status.retainedBytes,
        cursor =
            cursor?.let {
              "Frame ${it.frame}, position ${it.framePosition}, tick ${it.masterTick}"
            } ?: "No checkpoint",
        retainedRange =
            if (oldest == null || newest == null) {
              "No checkpoints retained"
            } else {
              "Frame ${oldest.frame} / tick ${oldest.masterTick} to " +
                  "frame ${newest.frame} / tick ${newest.masterTick}"
            },
        futureCheckpointCount = status.futureCheckpointCount,
        truncation = truncationText(status.lastTruncationReason),
        reverseFrame = action(frameReason),
        reverseInstruction = action(instructionReason),
    )
  }

  fun reverseOutcome(
      result: DebugResult<DebugReverseStepResult>,
  ): DebuggerReverseOutcome {
    if (result.isFailure) {
      val error = result.error()
      return DebuggerReverseOutcome(
          completed = false,
          step = null,
          errorCode = error.code().name,
          message = error.message(),
      )
    }
    val value = result.value()
    val step =
        DebuggerReverseStepView(
            kind = humanize(value.kind.name),
            identity = DebuggerSnapshotIdentity.from(value.snapshot),
            restoredPosition =
                "Frame ${value.restoredPosition.frame}, " +
                    "position ${value.restoredPosition.framePosition}, " +
                    "tick ${value.restoredPosition.masterTick}",
            replayAnchor =
                "Checkpoint ${value.replayAnchor.checkpointId} at " +
                    "frame ${value.replayAnchor.frame}, tick ${value.replayAnchor.masterTick}",
            futureCheckpointCount = value.history.futureCheckpointCount,
        )
    return DebuggerReverseOutcome(
        completed = true,
        step = step,
        errorCode = null,
        message =
            "Restored ${step.kind.lowercase(Locale.ROOT)} at ${step.restoredPosition.lowercaseFirst()}.",
    )
  }

  private fun formatCondition(condition: DebugBreakpointCondition): String =
      when (condition) {
        is DebugPcCondition ->
            if (condition.isExact) formatWord(condition.startAddress)
            else "${formatWord(condition.startAddress)}-${formatWord(condition.endAddress)}"
        is DebugMemoryCondition ->
            buildString {
              append(humanize(condition.access().name))
              append(' ')
              append(formatRange(condition.startAddress(), condition.endAddress()))
              if (condition.hasValueConstraint()) {
                append(", value ")
                append(formatByte(condition.value()))
                if (condition.valueMask() != 0xff) {
                  append(", mask ")
                  append(formatByte(condition.valueMask()))
                }
              }
            }
        is DebugOpcodeCondition ->
            if (condition.cbPrefixed) "CB ${formatByte(condition.opcode)}"
            else formatByte(condition.opcode)
        is DebugInterruptCondition -> humanize(condition.interrupt.name)
        is DebugPpuCondition ->
            buildList {
                  if (condition.constrainsFrame()) add("frame ${condition.frame}")
                  if (condition.constrainsLy()) add("LY ${condition.ly}")
                  if (condition.constrainsMode()) add("mode ${humanize(condition.mode!!.name)}")
                }
                .joinToString(", ")
        is DebugSerialCondition ->
            buildString {
              append(humanize(condition.event().name))
              if (condition.hasValueConstraint()) {
                append(", value ")
                append(formatByte(condition.value()))
                if (condition.valueMask() != 0xff) {
                  append(", mask ")
                  append(formatByte(condition.valueMask()))
                }
              }
            }
        is DebugCounterCondition -> "${humanize(condition.counter.name)} = ${condition.value}"
        else -> condition.toString()
      }

  private fun formatRange(start: Int, end: Int): String =
      if (start == end) formatWord(start) else "${formatWord(start)}-${formatWord(end)}"

  private fun truncationText(reason: DebugHistoryTruncationReason): String =
      when (reason) {
        DebugHistoryTruncationReason.NONE -> "None"
        else -> humanize(reason.name)
      }

  private fun action(reason: String?): DebuggerActionState =
      reason?.let(DebuggerActionState::unavailable) ?: DebuggerActionState.available()

  private fun parseHexToken(token: String): Int {
    val digits =
        when {
          token.startsWith("0x", ignoreCase = true) -> token.substring(2)
          token.startsWith("${'$'}") -> token.substring(1)
          else -> token
        }
    return digits.toInt(16)
  }

  private fun addressError(): String =
      "Address must be a 16-bit hexadecimal value, for example ${'$'}C000 or 0xC000."

  private fun humanize(value: String): String =
      value.lowercase(Locale.ROOT).replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }

  private fun String.lowercaseFirst(): String = replaceFirstChar { it.lowercase(Locale.ROOT) }

  private fun <T> immutableCopy(values: Collection<T>): List<T> =
      Collections.unmodifiableList(ArrayList(values))
}

internal class DebuggerParsedValue<T> private constructor(val value: T?, val error: String?) {
  init {
    require((value == null) != (error == null)) { "A parsed value is either valid or invalid" }
    require(error == null || error.isNotBlank()) { "A parse error must not be blank" }
  }

  val isValid: Boolean
    get() = error == null

  fun <R> map(transform: (T) -> R): DebuggerParsedValue<R> =
      if (isValid) valid(transform(value!!)) else invalid(error!!)

  companion object {
    fun <T> valid(value: T): DebuggerParsedValue<T> = DebuggerParsedValue(value, null)

    fun <T> invalid(error: String): DebuggerParsedValue<T> = DebuggerParsedValue(null, error)
  }
}

internal data class DebuggerAddressRange(val startAddress: Int, val endAddress: Int) {
  init {
    require(startAddress in 0..0xffff) { "Start address is outside 16 bits" }
    require(endAddress in startAddress..0xffff) { "End address precedes start or exceeds 16 bits" }
  }

  val length: Int
    get() = endAddress - startAddress + 1

  val isExact: Boolean
    get() = startAddress == endAddress

  fun memoryRequest(addressSpace: DebugAddressSpace): DebugMemoryRequest =
      DebugMemoryRequest(addressSpace, startAddress, length)
}

internal data class DebuggerSnapshotIdentity(
    val sessionGeneration: Long,
    val sequence: Long,
    val masterTick: Long,
) {
  init {
    require(sessionGeneration >= 0 && sequence >= 0 && masterTick >= 0) {
      "Snapshot identity coordinates must be non-negative"
    }
  }

  val label: String
    get() = "Session $sessionGeneration, snapshot $sequence, tick $masterTick"

  fun coherenceWith(expected: DebuggerSnapshotIdentity): DebuggerMemoryCoherence =
      when {
        sessionGeneration != expected.sessionGeneration -> DebuggerMemoryCoherence.DIFFERENT_SESSION
        this != expected -> DebuggerMemoryCoherence.STALE_SNAPSHOT
        else -> DebuggerMemoryCoherence.COHERENT
      }

  companion object {
    fun from(snapshot: DebugSnapshot): DebuggerSnapshotIdentity =
        DebuggerSnapshotIdentity(
            snapshot.sessionGeneration,
            snapshot.sequence,
            snapshot.masterTick,
        )
  }
}

internal data class DebuggerSnapshotView(
    val identity: DebuggerSnapshotIdentity,
    val paused: Boolean,
    val frame: Long,
    val framePosition: Int,
    val timingText: String,
    val registers: DebuggerRegisterView,
    val cpuState: String,
    val opcode: String,
    val mapper: String,
)

internal data class DebuggerRegisterView(
    val a: String,
    val f: String,
    val b: String,
    val c: String,
    val d: String,
    val e: String,
    val h: String,
    val l: String,
    val af: String,
    val bc: String,
    val de: String,
    val hl: String,
    val sp: String,
    val pc: String,
    val flags: String,
    val compactFlags: String,
)

internal data class DebuggerCapabilityView(
    val pauseResume: Boolean,
    val snapshots: Boolean,
    val instructionStep: Boolean,
    val machineCycleStep: Boolean,
    val frameStep: Boolean,
    val memoryRead: Boolean,
    val maxMemoryReadLength: Int,
    val coherentInspection: Boolean,
    val maxInspectionBlocks: Int,
    val maxInspectionBytes: Int,
    val breakpointKinds: List<String>,
    val maxBreakpoints: Int,
    val reverseHistory: Boolean,
    val reverseFrame: Boolean,
    val reverseInstruction: Boolean,
)

internal data class DebuggerBreakpointRow(
    val id: Long,
    val enabled: Boolean,
    val supported: Boolean,
    val kind: String,
    val condition: String,
    val accessibilityText: String,
)

/** The identity is supplied by the caller because DebugMemoryBlock itself has no coordinates. */
internal data class DebuggerMemoryCapture(
    val identity: DebuggerSnapshotIdentity,
    val block: DebugMemoryBlock,
)

internal enum class DebuggerMemoryCoherence(val explanation: String?) {
  COHERENT(null),
  STALE_SNAPSHOT("Memory belongs to a different snapshot in this session."),
  DIFFERENT_SESSION("Memory belongs to a different emulation session."),
}

internal data class DebuggerMemoryView(
    val identity: DebuggerSnapshotIdentity,
    val addressSpace: DebugAddressSpace,
    val startAddress: Int,
    val length: Int,
    val coherence: DebuggerMemoryCoherence,
    val coherenceExplanation: String?,
    val rows: List<DebuggerMemoryRow>,
)

internal data class DebuggerMemoryRow(
    val address: Int,
    val addressText: String,
    val bytes: List<Int>,
    val hexText: String,
    val asciiText: String,
)

internal data class DebuggerStackEntry(
    val offset: Int,
    val address: Int,
    val addressText: String,
    val value: Int,
    val valueText: String,
)

internal data class DebuggerStackView(
    val identity: DebuggerSnapshotIdentity,
    val available: Boolean,
    val requestedBytes: Int,
    val entries: List<DebuggerStackEntry>,
    val clipped: Boolean,
    val explanation: String?,
) {
  init {
    require(available == entries.isNotEmpty()) {
      "An available stack contains entries; an unavailable stack does not"
    }
    require(!clipped || available) { "An unavailable stack cannot be clipped" }
    require(available || !explanation.isNullOrBlank()) {
      "An unavailable stack requires an explanation"
    }
  }

  companion object {
    fun unavailable(
        identity: DebuggerSnapshotIdentity,
        requestedBytes: Int,
        explanation: String,
    ): DebuggerStackView =
        DebuggerStackView(identity, false, requestedBytes, emptyList(), false, explanation)
  }
}

internal data class DebuggerActionState(val enabled: Boolean, val explanation: String?) {
  init {
    require(enabled == (explanation == null)) {
      "An enabled action has no explanation; a disabled action requires one"
    }
  }

  companion object {
    fun available(): DebuggerActionState = DebuggerActionState(true, null)

    fun unavailable(explanation: String): DebuggerActionState =
        DebuggerActionState(false, explanation)
  }
}

internal data class DebuggerHistoryView(
    val enabled: Boolean,
    val checkpointCount: Int,
    val retainedBytes: Long,
    val cursor: String,
    val retainedRange: String,
    val futureCheckpointCount: Int,
    val truncation: String,
    val reverseFrame: DebuggerActionState,
    val reverseInstruction: DebuggerActionState,
)

internal data class DebuggerReverseStepView(
    val kind: String,
    val identity: DebuggerSnapshotIdentity,
    val restoredPosition: String,
    val replayAnchor: String,
    val futureCheckpointCount: Int,
)

internal data class DebuggerReverseOutcome(
    val completed: Boolean,
    val step: DebuggerReverseStepView?,
    val errorCode: String?,
    val message: String,
) {
  init {
    require(completed == (step != null)) { "Only completed outcomes contain a reverse step" }
    require(completed == (errorCode == null)) { "Only failed outcomes contain an error code" }
    require(message.isNotBlank()) { "A reverse outcome requires display text" }
  }
}
