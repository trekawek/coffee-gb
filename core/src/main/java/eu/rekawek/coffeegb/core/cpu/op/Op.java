package eu.rekawek.coffeegb.core.cpu.op;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.Registers;
import eu.rekawek.coffeegb.core.gpu.SpriteBug;

import java.io.Serializable;

public interface Op extends Serializable {

    default boolean readsMemory() {
        return false;
    }

    default boolean writesMemory() {
        return false;
    }

    /**
     * Resolves the effective address used by this operation without touching the
     * address space. A {@code null} result denotes an internal/wait cycle whose
     * {@link #readsMemory()} or {@link #writesMemory()} flag exists only for CPU
     * timing purposes.
     */
    default Integer resolveMemoryAddress(Registers registers, int[] args, int context) {
        return null;
    }

    /** Returns the byte driven by a resolved write, without performing it. */
    default Integer resolveMemoryWriteValue(int context) {
        return null;
    }

    /**
     * Previews a side-effect-free context-producing operation. This is kept
     * deliberately opt-in so speculative CPU timing checks never execute ALU,
     * register-write, interrupt, or memory operations.
     */
    default Integer previewContext(Registers registers, int[] args, int context) {
        return null;
    }

    default int operandLength() {
        return 0;
    }

    default int execute(Registers registers, AddressSpace addressSpace, int[] args, int context) {
        return context;
    }

    default void switchInterrupts(InterruptManager interruptManager) {
    }

    default boolean proceed(Registers registers) {
        return true;
    }

    default boolean forceFinishCycle() {
        return false;
    }

    default SpriteBug.CorruptionType causesOemBug(Registers registers, int context) {
        return null;
    }

    String toString();
}
