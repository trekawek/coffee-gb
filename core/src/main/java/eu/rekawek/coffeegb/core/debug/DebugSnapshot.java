package eu.rekawek.coffeegb.core.debug;

import java.util.Objects;

/**
 * Coherent immutable view captured at one emulation-thread safe point.
 *
 * <p>Every component belongs to the same session generation, sequence, and master tick.
 */
public record DebugSnapshot(
        long sessionGeneration,
        long sequence,
        long masterTick,
        long frame,
        int framePosition,
        boolean paused,
        DebugRegisters registers,
        DebugInterruptState interrupts,
        DebugTimerState timer,
        DebugPpuState ppu,
        DebugApuState apu,
        DebugMapperState mapper,
        DebugExecutionState execution) {

    public DebugSnapshot {
        DebugValueChecks.nonNegative("sessionGeneration", sessionGeneration);
        DebugValueChecks.nonNegative("sequence", sequence);
        DebugValueChecks.nonNegative("masterTick", masterTick);
        DebugValueChecks.nonNegative("frame", frame);
        DebugValueChecks.nonNegative("framePosition", framePosition);
        Objects.requireNonNull(registers, "registers");
        Objects.requireNonNull(interrupts, "interrupts");
        Objects.requireNonNull(timer, "timer");
        Objects.requireNonNull(ppu, "ppu");
        Objects.requireNonNull(apu, "apu");
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(execution, "execution");
    }
}
