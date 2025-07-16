package eu.rekawek.coffeegb.cpu.opcode;

import eu.rekawek.coffeegb.cpu.op.Op;

import java.io.Serializable;
import java.util.List;

public class Opcode implements Serializable {

    private final int opcode;

    private final String label;

    private final List<Op> ops;

    private final int length;

    Opcode(OpcodeBuilder builder) {
        this.opcode = builder.getOpcode();
        this.label = builder.getLabel();
        this.ops = List.copyOf(builder.getOps());
        this.length = ops.stream().mapToInt(Op::operandLength).max().orElse(0);
    }

    public int getOperandLength() {
        return length;
    }

    @Override
    public String toString() {
        return String.format("%02x %s", opcode, label);
    }

    public List<Op> getOps() {
        return ops;
    }

    public String getLabel() {
        return label;
    }

    public int getOpcode() {
        return opcode;
    }
}
