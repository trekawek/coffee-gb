package eu.rekawek.coffeegb.cpu.opcode;

import eu.rekawek.coffeegb.cpu.op.Op;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Opcode {

    private final int opcode;

    private final String label;

    private final List<Op> ops;

    Opcode(OpcodeBuilder builder) {
        this.opcode = builder.getOpcode();
        this.label = builder.getLabel();
        this.ops = Collections.unmodifiableList(new ArrayList<>(builder.getOps()));
    }

    public int getOperandLength() {
        return ops.stream().mapToInt(o -> o.operandLength()).max().orElse(0);
    }

    @Override
    public String toString() {
        return String.format("%02x %s", opcode, label);
    }

    public List<Op> getOps() {
        return ops;
    }
}
