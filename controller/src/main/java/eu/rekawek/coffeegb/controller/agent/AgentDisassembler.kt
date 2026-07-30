package eu.rekawek.coffeegb.controller.agent

import eu.rekawek.coffeegb.core.debug.DebugDisassembler
import eu.rekawek.coffeegb.core.debug.DebugMemoryBlock

/** Stateless formatter over an immutable debug memory block. */
internal object AgentDisassembler {

  fun disassemble(address: Int, memory: DebugMemoryBlock): String {
    require(memory.startAddress() == address) { "Memory block does not start at the instruction" }
    return DebugDisassembler.disassemble(memory)
  }
}
