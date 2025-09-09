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
