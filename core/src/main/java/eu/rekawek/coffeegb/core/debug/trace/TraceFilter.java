package eu.rekawek.coffeegb.core.debug.trace;

import eu.rekawek.coffeegb.core.debug.DebugInterruptType;
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Data-only producer filter applied before constructing CPU, memory, or interrupt events.
 * Future categories are unaffected until they gain an explicit bounded filter here.
 */
public record TraceFilter(
        int cpuPcStart,
        int cpuPcEnd,
        int memoryStart,
        int memoryEnd,
        Set<DebugMemoryAccess> memoryAccesses,
        Set<DebugInterruptType> interrupts) {

    public TraceFilter {
        addressRange("CPU PC", cpuPcStart, cpuPcEnd);
        addressRange("memory", memoryStart, memoryEnd);
        Objects.requireNonNull(memoryAccesses, "memoryAccesses");
        EnumSet<DebugMemoryAccess> memoryCopy = memoryAccesses.isEmpty()
                ? EnumSet.noneOf(DebugMemoryAccess.class)
                : EnumSet.copyOf(memoryAccesses);
        memoryAccesses = Collections.unmodifiableSet(memoryCopy);
        Objects.requireNonNull(interrupts, "interrupts");
        EnumSet<DebugInterruptType> interruptCopy = interrupts.isEmpty()
                ? EnumSet.noneOf(DebugInterruptType.class)
                : EnumSet.copyOf(interrupts);
        interrupts = Collections.unmodifiableSet(interruptCopy);
    }

    public static TraceFilter all() {
        return new TraceFilter(
                0, 0xffff,
                0, 0xffff,
                EnumSet.allOf(DebugMemoryAccess.class),
                EnumSet.allOf(DebugInterruptType.class));
    }

    public boolean acceptsCpu(int programCounter) {
        return programCounter >= cpuPcStart && programCounter <= cpuPcEnd;
    }

    public boolean acceptsMemory(DebugMemoryAccess access, int address) {
        return memoryAccesses.contains(Objects.requireNonNull(access, "access"))
                && address >= memoryStart && address <= memoryEnd;
    }

    public boolean acceptsInterrupt(DebugInterruptType interrupt) {
        return interrupts.contains(Objects.requireNonNull(interrupt, "interrupt"));
    }

    private static void addressRange(String name, int start, int end) {
        TraceChecks.range(name + " start", start, 0, 0xffff);
        TraceChecks.range(name + " end", end, 0, 0xffff);
        if (start > end) {
            throw new IllegalArgumentException(name + " start must not exceed end");
        }
    }
}
