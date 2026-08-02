package eu.rekawek.coffeegb.core.cpu.opcode;

import eu.rekawek.coffeegb.core.cpu.op.Op;

import java.util.List;

public class Opcode  {

    private final int opcode;

    private final String label;

    private final List<Op> ops;

    /** Array-backed view used by the CPU execution hot path. */
    private final Op[] executionOps;

    /** Immutable per-operation memory metadata used during bus arbitration. */
    private final boolean[] accessesMemory;

    private final boolean[] writesMemory;

    private final int length;

    Opcode(OpcodeBuilder builder) {
        this.opcode = builder.getOpcode();
        this.label = builder.getLabel();
        this.ops = List.copyOf(builder.getOps());
        this.executionOps = ops.toArray(Op[]::new);
        this.accessesMemory = new boolean[executionOps.length];
        this.writesMemory = new boolean[executionOps.length];
        for (int i = 0; i < executionOps.length; i++) {
            Op op = executionOps[i];
            boolean writes = op.writesMemory();
            writesMemory[i] = writes;
            accessesMemory[i] = op.readsMemory() || writes;
        }
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

    public int getOpCount() {
        return executionOps.length;
    }

    public Op getOp(int index) {
        return executionOps[index];
    }

    public boolean opAccessesMemory(int index) {
        return accessesMemory[index];
    }

    public boolean opWritesMemory(int index) {
        return writesMemory[index];
    }

    public String getLabel() {
        return label;
    }

    public int getOpcode() {
        return opcode;
    }
}
