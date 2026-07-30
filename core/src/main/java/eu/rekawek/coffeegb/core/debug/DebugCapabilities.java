package eu.rekawek.coffeegb.core.debug;

import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind;
import eu.rekawek.coffeegb.core.debug.history.DebugHistoryCapabilities;
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory;
import eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration;
import eu.rekawek.coffeegb.core.debug.trace.TraceReadRequest;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Immutable feature negotiation for one session generation. */
public record DebugCapabilities(
        boolean pauseResume,
        boolean snapshot,
        boolean instructionStep,
        boolean machineCycleStep,
        boolean frameStep,
        boolean memoryRead,
        boolean buttonInput,
        int maxMemoryReadLength,
        Set<DebugBreakpointKind> breakpointKinds,
        int maxBreakpoints,
        Set<TraceCategory> traceCategories,
        int maxTraceCapacity,
        int maxTraceReadEntries,
        DebugHistoryCapabilities history) {

    public DebugCapabilities {
        if (maxMemoryReadLength < 0 || maxMemoryReadLength > DebugMemoryRequest.MAX_LENGTH) {
            throw new IllegalArgumentException("Invalid maximum memory-read length: "
                    + maxMemoryReadLength);
        }
        if (memoryRead != (maxMemoryReadLength > 0)) {
            throw new IllegalArgumentException(
                    "Memory-read capability and maximum length must agree");
        }
        Objects.requireNonNull(breakpointKinds, "breakpointKinds");
        EnumSet<DebugBreakpointKind> breakpointCopy = breakpointKinds.isEmpty()
                ? EnumSet.noneOf(DebugBreakpointKind.class)
                : EnumSet.copyOf(breakpointKinds);
        breakpointKinds = Collections.unmodifiableSet(breakpointCopy);
        if (maxBreakpoints < 0 || maxBreakpoints > 4096) {
            throw new IllegalArgumentException("Invalid maximum breakpoint count: "
                    + maxBreakpoints);
        }
        if (breakpointKinds.isEmpty() != (maxBreakpoints == 0)) {
            throw new IllegalArgumentException(
                    "Breakpoint kinds and maximum count must agree");
        }

        Objects.requireNonNull(traceCategories, "traceCategories");
        EnumSet<TraceCategory> traceCopy = traceCategories.isEmpty()
                ? EnumSet.noneOf(TraceCategory.class)
                : EnumSet.copyOf(traceCategories);
        traceCategories = Collections.unmodifiableSet(traceCopy);
        if (maxTraceCapacity < 0 || maxTraceCapacity > TraceConfiguration.MAX_CAPACITY) {
            throw new IllegalArgumentException("Invalid maximum trace capacity: "
                    + maxTraceCapacity);
        }
        if (maxTraceReadEntries < 0 || maxTraceReadEntries > TraceReadRequest.MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid maximum trace read length: "
                    + maxTraceReadEntries);
        }
        if (traceCategories.isEmpty()
                ? maxTraceCapacity != 0 || maxTraceReadEntries != 0
                : maxTraceCapacity == 0 || maxTraceReadEntries == 0) {
            throw new IllegalArgumentException(
                    "Trace categories and limits must agree");
        }
        Objects.requireNonNull(history, "history");
    }

    /** Compatibility constructor for transports that predate reverse history. */
    public DebugCapabilities(
            boolean pauseResume,
            boolean snapshot,
            boolean instructionStep,
            boolean machineCycleStep,
            boolean frameStep,
            boolean memoryRead,
            boolean buttonInput,
            int maxMemoryReadLength,
            Set<DebugBreakpointKind> breakpointKinds,
            int maxBreakpoints,
            Set<TraceCategory> traceCategories,
            int maxTraceCapacity,
            int maxTraceReadEntries) {
        this(pauseResume, snapshot, instructionStep, machineCycleStep, frameStep,
                memoryRead, buttonInput, maxMemoryReadLength,
                breakpointKinds, maxBreakpoints, traceCategories, maxTraceCapacity,
                maxTraceReadEntries, DebugHistoryCapabilities.disabled());
    }

    /** Compatibility constructor for transports that expose only the phase-one operations. */
    public DebugCapabilities(
            boolean pauseResume,
            boolean snapshot,
            boolean instructionStep,
            boolean machineCycleStep,
            boolean frameStep,
            boolean memoryRead,
            boolean buttonInput,
            int maxMemoryReadLength) {
        this(pauseResume, snapshot, instructionStep, machineCycleStep, frameStep,
                memoryRead, buttonInput, maxMemoryReadLength,
                Set.of(), 0, Set.of(), 0, 0, DebugHistoryCapabilities.disabled());
    }

    public boolean supports(DebugStepKind kind) {
        return switch (Objects.requireNonNull(kind, "kind")) {
            case INSTRUCTION -> instructionStep;
            case MACHINE_CYCLE -> machineCycleStep;
            case FRAME -> frameStep;
        };
    }

    public boolean breakpoints() {
        return !breakpointKinds.isEmpty();
    }

    public boolean supports(DebugBreakpointKind kind) {
        return breakpointKinds.contains(Objects.requireNonNull(kind, "kind"));
    }

    public boolean trace() {
        return !traceCategories.isEmpty();
    }

    public boolean supports(TraceCategory category) {
        return traceCategories.contains(Objects.requireNonNull(category, "category"));
    }
}
