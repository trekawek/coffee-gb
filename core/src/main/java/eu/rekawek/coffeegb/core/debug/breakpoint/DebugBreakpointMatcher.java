package eu.rekawek.coffeegb.core.debug.breakpoint;

import eu.rekawek.coffeegb.core.debug.DebugInterruptType;
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess;
import eu.rekawek.coffeegb.core.debug.DebugPpuMode;

import java.util.Objects;

/**
 * Allocation-free predicate matching over primitive emulation observations.
 *
 * <p>Each method handles only conditions meaningful for that event and returns {@code false}
 * for every other condition kind. Definitions and observations remain detached data; this
 * class does not evaluate code, expressions, or callbacks.
 */
public final class DebugBreakpointMatcher {

    private DebugBreakpointMatcher() {
    }

    /** Matches PC and opcode conditions at an instruction boundary. */
    public static boolean matchesInstruction(
            DebugBreakpoint breakpoint,
            int programCounter,
            boolean cbPrefixed,
            int opcode) {
        Objects.requireNonNull(breakpoint, "breakpoint");
        if (!breakpoint.enabled()) {
            return false;
        }
        DebugBreakpointCondition condition = breakpoint.condition();
        if (condition instanceof DebugPcCondition pc) {
            DebugBreakpointChecks.address("programCounter", programCounter);
            return programCounter >= pc.startAddress()
                    && programCounter <= pc.endAddress();
        }
        if (condition instanceof DebugOpcodeCondition instruction) {
            DebugBreakpointChecks.unsignedByte("opcode", opcode);
            return instruction.cbPrefixed() == cbPrefixed
                    && instruction.opcode() == opcode;
        }
        return false;
    }

    /** Matches read, write, or execute watchpoints against one observed bus byte. */
    public static boolean matchesMemory(
            DebugBreakpoint breakpoint,
            DebugMemoryAccess access,
            int address,
            int value) {
        Objects.requireNonNull(breakpoint, "breakpoint");
        if (!breakpoint.enabled()
                || !(breakpoint.condition() instanceof DebugMemoryCondition memory)) {
            return false;
        }
        Objects.requireNonNull(access, "access");
        DebugBreakpointChecks.address("address", address);
        DebugBreakpointChecks.unsignedByte("value", value);
        if (memory.access() != access
                || address < memory.startAddress()
                || address > memory.endAddress()) {
            return false;
        }
        return !memory.hasValueConstraint()
                || (value & memory.valueMask()) == (memory.value() & memory.valueMask());
    }

    /** Matches an interrupt edge or acceptance reported by the emulator. */
    public static boolean matchesInterrupt(
            DebugBreakpoint breakpoint,
            DebugInterruptType interrupt) {
        Objects.requireNonNull(breakpoint, "breakpoint");
        if (!breakpoint.enabled()
                || !(breakpoint.condition() instanceof DebugInterruptCondition condition)) {
            return false;
        }
        return condition.interrupt() == Objects.requireNonNull(interrupt, "interrupt");
    }

    /** Matches the coherent PPU state observed at an emulator safe point. */
    public static boolean matchesPpu(
            DebugBreakpoint breakpoint,
            long frame,
            int ly,
            DebugPpuMode mode) {
        Objects.requireNonNull(breakpoint, "breakpoint");
        if (!breakpoint.enabled()
                || !(breakpoint.condition() instanceof DebugPpuCondition condition)) {
            return false;
        }
        DebugBreakpointChecks.nonNegative("frame", frame);
        DebugBreakpointChecks.range("ly", ly, 0, 153);
        Objects.requireNonNull(mode, "mode");
        return (!condition.constrainsFrame() || condition.frame() == frame)
                && (!condition.constrainsLy() || condition.ly() == ly)
                && (!condition.constrainsMode() || condition.mode() == mode);
    }

    /** Matches exact monotonic master-tick or frame-count conditions. */
    public static boolean matchesCounters(
            DebugBreakpoint breakpoint,
            long masterTick,
            long frame) {
        Objects.requireNonNull(breakpoint, "breakpoint");
        if (!breakpoint.enabled()
                || !(breakpoint.condition() instanceof DebugCounterCondition condition)) {
            return false;
        }
        DebugBreakpointChecks.nonNegative("masterTick", masterTick);
        DebugBreakpointChecks.nonNegative("frame", frame);
        return condition.counter() == DebugCounterType.MASTER_TICK
                ? condition.value() == masterTick
                : condition.value() == frame;
    }
}
