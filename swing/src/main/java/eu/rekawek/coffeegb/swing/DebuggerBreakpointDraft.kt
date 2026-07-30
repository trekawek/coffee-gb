package eu.rekawek.coffeegb.swing

import eu.rekawek.coffeegb.core.debug.DebugInterruptType
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess
import eu.rekawek.coffeegb.core.debug.DebugPpuMode
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugCounterCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugCounterType
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugInterruptCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugMemoryCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugOpcodeCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPcCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPpuCondition
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugSerialCondition
import java.math.BigInteger

/**
 * Stable editor choices kept separate from Swing components.
 *
 * Multiple editor choices can produce the same negotiated breakpoint kind. For example, read,
 * write, and execute watchpoints are all [DebugBreakpointKind.MEMORY] conditions.
 */
internal enum class DebuggerBreakpointEditorKind(
    val displayName: String,
    val negotiatedKind: DebugBreakpointKind,
) {
  PROGRAM_COUNTER("Program counter", DebugBreakpointKind.PROGRAM_COUNTER),
  MEMORY_READ("Memory read", DebugBreakpointKind.MEMORY),
  MEMORY_WRITE("Memory write", DebugBreakpointKind.MEMORY),
  MEMORY_EXECUTE("Memory execute", DebugBreakpointKind.MEMORY),
  BASE_OPCODE("Opcode", DebugBreakpointKind.OPCODE),
  CB_OPCODE("CB opcode", DebugBreakpointKind.OPCODE),
  INTERRUPT("Interrupt", DebugBreakpointKind.INTERRUPT),
  PPU_STATE("PPU state", DebugBreakpointKind.PPU_STATE),
  SERIAL_START("Serial transfer start", DebugBreakpointKind.SERIAL),
  SERIAL_COMPLETION("Serial transfer completion", DebugBreakpointKind.SERIAL),
  MASTER_TICK("Master tick", DebugBreakpointKind.COUNTER),
  FRAME_COUNTER("Frame counter", DebugBreakpointKind.COUNTER),
  ;

  override fun toString(): String = displayName
}

/** Immutable, UI-independent input for one breakpoint editor. */
internal sealed interface DebuggerBreakpointDraft {
  val editorKind: DebuggerBreakpointEditorKind

  /** Parses this draft into an existing immutable debug-port condition. */
  fun parse(): DebuggerParsedValue<DebugBreakpointCondition>

  data class ProgramCounter(val addressText: String) : DebuggerBreakpointDraft {
    override val editorKind: DebuggerBreakpointEditorKind =
        DebuggerBreakpointEditorKind.PROGRAM_COUNTER

    override fun parse(): DebuggerParsedValue<DebugBreakpointCondition> =
        DebuggerPresentation.parseProgramCounterCondition(addressText).map { it }
  }

  data class Memory(
      val access: DebugMemoryAccess,
      val addressText: String,
      val valueText: String = "",
      val maskText: String = "",
  ) : DebuggerBreakpointDraft {
    override val editorKind: DebuggerBreakpointEditorKind =
        when (access) {
          DebugMemoryAccess.READ -> DebuggerBreakpointEditorKind.MEMORY_READ
          DebugMemoryAccess.WRITE -> DebuggerBreakpointEditorKind.MEMORY_WRITE
          DebugMemoryAccess.EXECUTE -> DebuggerBreakpointEditorKind.MEMORY_EXECUTE
        }

    override fun parse(): DebuggerParsedValue<DebugBreakpointCondition> =
        DebuggerPresentation
            .parseMemoryCondition(addressText, access, valueText, maskText)
            .map { it }
  }

  data class Opcode(
      val cbPrefixed: Boolean,
      val opcodeText: String,
  ) : DebuggerBreakpointDraft {
    override val editorKind: DebuggerBreakpointEditorKind =
        if (cbPrefixed) DebuggerBreakpointEditorKind.CB_OPCODE
        else DebuggerBreakpointEditorKind.BASE_OPCODE

    override fun parse(): DebuggerParsedValue<DebugBreakpointCondition> {
      val opcode = DebuggerPresentation.parseByte(opcodeText, "Opcode")
      if (!opcode.isValid) return DebuggerParsedValue.invalid(opcode.error!!)
      return DebuggerParsedValue.valid(
          if (cbPrefixed) DebugOpcodeCondition.cb(opcode.value!!)
          else DebugOpcodeCondition.base(opcode.value!!)
      )
    }
  }

  data class Interrupt(val interrupt: DebugInterruptType?) : DebuggerBreakpointDraft {
    override val editorKind: DebuggerBreakpointEditorKind = DebuggerBreakpointEditorKind.INTERRUPT

    override fun parse(): DebuggerParsedValue<DebugBreakpointCondition> =
        interrupt?.let { DebuggerParsedValue.valid(DebugInterruptCondition(it)) }
            ?: DebuggerParsedValue.invalid("Choose an interrupt.")
  }

  data class Ppu(
      val frameText: String = "",
      val lyText: String = "",
      val mode: DebugPpuMode? = null,
  ) : DebuggerBreakpointDraft {
    override val editorKind: DebuggerBreakpointEditorKind = DebuggerBreakpointEditorKind.PPU_STATE

    override fun parse(): DebuggerParsedValue<DebugBreakpointCondition> {
      if (frameText.isBlank() && lyText.isBlank() && mode == null) {
        return DebuggerParsedValue.invalid(
            "Set at least one PPU constraint: frame, LY, or mode."
        )
      }
      val frame =
          if (frameText.isBlank()) {
            DebuggerParsedValue.valid(DebugPpuCondition.ANY_FRAME)
          } else {
            parseBoundedNumber(frameText, "Frame", Long.MAX_VALUE)
          }
      if (!frame.isValid) return DebuggerParsedValue.invalid(frame.error!!)

      val ly =
          if (lyText.isBlank()) {
            DebuggerParsedValue.valid(DebugPpuCondition.ANY_LY.toLong())
          } else {
            parseBoundedNumber(lyText, "LY", MAX_PPU_LY.toLong())
          }
      if (!ly.isValid) return DebuggerParsedValue.invalid(ly.error!!)

      return DebuggerParsedValue.valid(
          DebugPpuCondition(frame.value!!, ly.value!!.toInt(), mode)
      )
    }
  }

  data class Serial(
      val event: DebugSerialCondition.Event,
      val valueText: String = "",
      val maskText: String = "",
  ) : DebuggerBreakpointDraft {
    override val editorKind: DebuggerBreakpointEditorKind =
        when (event) {
          DebugSerialCondition.Event.TRANSFER_STARTED ->
              DebuggerBreakpointEditorKind.SERIAL_START
          DebugSerialCondition.Event.BYTE_TRANSFERRED ->
              DebuggerBreakpointEditorKind.SERIAL_COMPLETION
        }

    override fun parse(): DebuggerParsedValue<DebugBreakpointCondition> {
      if (valueText.isBlank()) {
        if (maskText.isNotBlank()) {
          return DebuggerParsedValue.invalid("A value is required when a value mask is set.")
        }
        return DebuggerParsedValue.valid(DebugSerialCondition(event))
      }

      val value = DebuggerPresentation.parseByte(valueText)
      if (!value.isValid) return DebuggerParsedValue.invalid(value.error!!)
      if (maskText.isBlank()) {
        return DebuggerParsedValue.valid(DebugSerialCondition(event, value.value!!))
      }

      val mask = DebuggerPresentation.parseByte(maskText, "Mask")
      if (!mask.isValid) return DebuggerParsedValue.invalid(mask.error!!)
      if (mask.value == 0) {
        return DebuggerParsedValue.invalid("Mask must contain at least one set bit.")
      }
      return DebuggerParsedValue.valid(
          DebugSerialCondition(event, value.value!!, mask.value!!)
      )
    }
  }

  data class Counter(
      val counter: DebugCounterType,
      val valueText: String,
  ) : DebuggerBreakpointDraft {
    override val editorKind: DebuggerBreakpointEditorKind =
        when (counter) {
          DebugCounterType.MASTER_TICK -> DebuggerBreakpointEditorKind.MASTER_TICK
          DebugCounterType.FRAME -> DebuggerBreakpointEditorKind.FRAME_COUNTER
        }

    override fun parse(): DebuggerParsedValue<DebugBreakpointCondition> {
      val value = parseBoundedNumber(valueText, counter.fieldName, Long.MAX_VALUE)
      if (!value.isValid) return DebuggerParsedValue.invalid(value.error!!)
      return DebuggerParsedValue.valid(DebugCounterCondition(counter, value.value!!))
    }
  }

  companion object {
    /** Creates an editable draft for every condition currently supported by the debug port. */
    fun from(condition: DebugBreakpointCondition): DebuggerBreakpointDraft =
        when (condition) {
          is DebugPcCondition ->
              ProgramCounter(formatRange(condition.startAddress, condition.endAddress))
          is DebugMemoryCondition ->
              Memory(
                  condition.access(),
                  formatRange(condition.startAddress(), condition.endAddress()),
                  if (condition.hasValueConstraint()) {
                    DebuggerPresentation.formatByte(condition.value())
                  } else {
                    ""
                  },
                  if (condition.hasValueConstraint() && condition.valueMask() != 0xff) {
                    DebuggerPresentation.formatByte(condition.valueMask())
                  } else {
                    ""
                  },
              )
          is DebugOpcodeCondition ->
              Opcode(condition.cbPrefixed, DebuggerPresentation.formatByte(condition.opcode))
          is DebugInterruptCondition -> Interrupt(condition.interrupt)
          is DebugPpuCondition ->
              Ppu(
                  if (condition.constrainsFrame()) condition.frame.toString() else "",
                  if (condition.constrainsLy()) condition.ly.toString() else "",
                  condition.mode,
              )
          is DebugSerialCondition ->
              Serial(
                  condition.event(),
                  if (condition.hasValueConstraint()) {
                    DebuggerPresentation.formatByte(condition.value())
                  } else {
                    ""
                  },
                  if (condition.hasValueConstraint() && condition.valueMask() != 0xff) {
                    DebuggerPresentation.formatByte(condition.valueMask())
                  } else {
                    ""
                  },
              )
          is DebugCounterCondition -> Counter(condition.counter, condition.value.toString())
          else ->
              throw IllegalArgumentException(
                  "Unsupported breakpoint condition: ${condition.javaClass.name}"
              )
        }
  }
}

private val DebugCounterType.fieldName: String
  get() =
      when (this) {
        DebugCounterType.MASTER_TICK -> "Master tick"
        DebugCounterType.FRAME -> "Frame counter"
      }

private fun parseBoundedNumber(
    text: String,
    fieldName: String,
    maximum: Long,
): DebuggerParsedValue<Long> {
  val input = text.trim()
  if (input.isEmpty()) return DebuggerParsedValue.invalid("$fieldName is required.")

  val (digits, radix) =
      when {
        input.firstOrNull() == '$' -> input.drop(1) to 16
        input.startsWith("0x", ignoreCase = true) -> input.drop(2) to 16
        else -> input to 10
      }
  val validDigits =
      digits.isNotEmpty() &&
          if (radix == 16) digits.all(::isAsciiHexDigit)
          else digits.all(::isAsciiDecimalDigit)
  if (!validDigits) {
    return DebuggerParsedValue.invalid(
        "$fieldName must be a non-negative decimal value or hexadecimal prefixed by ${'$'} or 0x."
    )
  }

  val value = BigInteger(digits, radix)
  val maximumValue = BigInteger.valueOf(maximum)
  if (value > maximumValue) {
    return DebuggerParsedValue.invalid("$fieldName must be between 0 and $maximum.")
  }
  return DebuggerParsedValue.valid(value.toLong())
}

private fun isAsciiHexDigit(value: Char): Boolean =
    value in '0'..'9' || value in 'a'..'f' || value in 'A'..'F'

private fun isAsciiDecimalDigit(value: Char): Boolean = value in '0'..'9'

private fun formatRange(startAddress: Int, endAddress: Int): String =
    if (startAddress == endAddress) {
      DebuggerPresentation.formatWord(startAddress)
    } else {
      "${DebuggerPresentation.formatWord(startAddress)}-" +
          DebuggerPresentation.formatWord(endAddress)
    }

private const val MAX_PPU_LY = 153
