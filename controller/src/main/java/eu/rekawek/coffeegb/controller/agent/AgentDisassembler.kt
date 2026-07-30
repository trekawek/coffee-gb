package eu.rekawek.coffeegb.controller.agent

import eu.rekawek.coffeegb.core.cpu.Opcodes
import eu.rekawek.coffeegb.core.debug.DebugAddressSpace
import eu.rekawek.coffeegb.core.debug.DebugMemoryBlock

/** Stateless formatter over an immutable debug memory block. */
internal object AgentDisassembler {

  fun disassemble(address: Int, memory: DebugMemoryBlock): String {
    require(memory.startAddress() == address) { "Memory block does not start at the instruction" }
    require(memory.length() > 0) { "At least one opcode byte is required" }
    val opcode1 = memory.unsignedByteAt(0)
    if (opcode1 == 0xcb) {
      require(memory.length() >= 2) { "Extended opcode is truncated" }
      val opcode2 = memory.unsignedByteAt(1)
      val opcode = Opcodes.EXT_COMMANDS[opcode2]
      return labelView(
          String.format("%04X: CB %02X %s", address, opcode2, opcode?.label ?: "UNKNOWN"),
          memory,
      )
    }

    val opcode = Opcodes.COMMANDS[opcode1]
    var label = opcode?.label ?: "UNKNOWN"
    val length = opcode?.operandLength ?: 0
    require(memory.length() >= length + 1) { "Instruction operands are truncated" }
    val bytes = mutableListOf(String.format("%02X", opcode1))
    if (length >= 1) {
      val value = memory.unsignedByteAt(1)
      bytes.add(String.format("%02X", value))
      label = label.replace("d8", String.format("%02X", value))
      label = label.replace("r8", String.format("%02X", value))
      label = label.replace("a8", String.format("%02X", value))
    }
    if (length >= 2) {
      val low = memory.unsignedByteAt(1)
      val high = memory.unsignedByteAt(2)
      val value = high shl 8 or low
      bytes.add(String.format("%02X", high))
      label = label.replace("d16", String.format("%04X", value))
      label = label.replace("a16", String.format("%04X", value))
    }
    return labelView(
        String.format("%04X: %-11s %s", address, bytes.joinToString(" "), label),
        memory,
    )
  }

  private fun labelView(disassembly: String, memory: DebugMemoryBlock): String {
    val view =
        when (memory.addressSpace()) {
          DebugAddressSpace.ROM ->
              "parser-corrected ROM image, not the mapped CPU window"
          else -> memory.addressSpace().name
        }
    return "$disassembly [best-effort: $view]"
  }
}
