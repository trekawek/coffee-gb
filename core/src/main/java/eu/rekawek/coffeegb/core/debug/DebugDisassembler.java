package eu.rekawek.coffeegb.core.debug;

import eu.rekawek.coffeegb.core.cpu.Opcodes;
import eu.rekawek.coffeegb.core.cpu.opcode.Opcode;

import java.util.ArrayList;
import java.util.List;

/** Stateless, platform-neutral best-effort formatter over one detached debug memory block. */
public final class DebugDisassembler {

    private DebugDisassembler() {
    }

    /** Formats the first instruction in {@code memory}, whose start address is its displayed PC. */
    public static String disassemble(DebugMemoryBlock memory) {
        if (memory == null) {
            throw new NullPointerException("memory");
        }
        if (memory.length() == 0) {
            throw new IllegalArgumentException("At least one opcode byte is required");
        }
        int address = memory.startAddress();
        int opcode1 = memory.unsignedByteAt(0);
        if (opcode1 == 0xcb) {
            if (memory.length() < 2) {
                throw new IllegalArgumentException("Extended opcode is truncated");
            }
            int opcode2 = memory.unsignedByteAt(1);
            Opcode opcode = Opcodes.EXT_COMMANDS.get(opcode2);
            String label = opcode == null ? "UNKNOWN" : opcode.getLabel();
            return labelView(String.format("%04X: CB %02X %s", address, opcode2, label), memory);
        }

        Opcode opcode = Opcodes.COMMANDS.get(opcode1);
        String label = opcode == null ? "UNKNOWN" : opcode.getLabel();
        int operandLength = opcode == null ? 0 : opcode.getOperandLength();
        if (memory.length() < operandLength + 1) {
            throw new IllegalArgumentException("Instruction operands are truncated");
        }
        List<String> bytes = new ArrayList<>(operandLength + 1);
        bytes.add(String.format("%02X", opcode1));
        if (operandLength >= 1) {
            int value = memory.unsignedByteAt(1);
            bytes.add(String.format("%02X", value));
            label = label.replace("d8", String.format("%02X", value))
                    .replace("r8", String.format("%02X", value))
                    .replace("a8", String.format("%02X", value));
        }
        if (operandLength >= 2) {
            int low = memory.unsignedByteAt(1);
            int high = memory.unsignedByteAt(2);
            int value = high << 8 | low;
            bytes.add(String.format("%02X", high));
            label = label.replace("d16", String.format("%04X", value))
                    .replace("a16", String.format("%04X", value));
        }
        return labelView(
                String.format("%04X: %-11s %s", address, String.join(" ", bytes), label), memory);
    }

    private static String labelView(String disassembly, DebugMemoryBlock memory) {
        String view = memory.addressSpace() == DebugAddressSpace.ROM
                ? "parser-corrected ROM image, not the mapped CPU window"
                : memory.addressSpace().name();
        return disassembly + " [best-effort: " + view + "]";
    }
}
