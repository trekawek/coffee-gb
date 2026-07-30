package eu.rekawek.coffeegb.core.debug;

import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointCondition;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointMatcher;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugCounterCondition;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugCounterType;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugInterruptCondition;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugMemoryCondition;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugOpcodeCondition;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPcCondition;
import eu.rekawek.coffeegb.core.debug.trace.CpuInstructionTrace;
import eu.rekawek.coffeegb.core.debug.trace.InterruptTrace;
import eu.rekawek.coffeegb.core.debug.trace.MemoryAccessTrace;
import eu.rekawek.coffeegb.core.debug.trace.TraceBuffer;
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory;
import eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration;
import eu.rekawek.coffeegb.core.debug.trace.TraceReadRequest;
import eu.rekawek.coffeegb.core.debug.trace.TraceReadResult;
import eu.rekawek.coffeegb.core.debug.trace.TraceSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Owner-thread breakpoint table and bounded trace attachment for one debug-port generation.
 *
 * <p>Definitions are rebuilt into an array only when a client edits the table. Hook matching uses
 * indexed loops, primitive observations, and pre-existing ids, so a non-hit does not allocate.
 */
public final class DebugInstrumentation implements DebugHooks {

    private final int maxBreakpoints;

    private final int maxTraceCapacity;

    private final Set<DebugBreakpointKind> supportedBreakpointKinds;

    private final Set<TraceCategory> supportedTraceCategories;

    private final Map<DebugBreakpointId, DebugBreakpoint> breakpoints = new LinkedHashMap<>();

    private DebugBreakpoint[] enabledBreakpoints = new DebugBreakpoint[0];

    private TraceBuffer traceBuffer;

    private long masterTick;

    private DebugBreakpointId pendingBreakpointId;

    private long pendingMatchTick;

    private BreakpointMatch readyMatch;

    public DebugInstrumentation(
            int maxBreakpoints,
            int maxTraceCapacity,
            int initialTraceCapacity,
            Set<DebugBreakpointKind> supportedBreakpointKinds,
            Set<TraceCategory> supportedTraceCategories) {
        if (maxBreakpoints < 1) {
            throw new IllegalArgumentException("Maximum breakpoint count must be positive");
        }
        if (maxTraceCapacity < 1
                || maxTraceCapacity > TraceConfiguration.MAX_CAPACITY) {
            throw new IllegalArgumentException("Invalid maximum trace capacity: "
                    + maxTraceCapacity);
        }
        if (initialTraceCapacity < 1 || initialTraceCapacity > maxTraceCapacity) {
            throw new IllegalArgumentException("Invalid initial trace capacity: "
                    + initialTraceCapacity);
        }
        this.maxBreakpoints = maxBreakpoints;
        this.maxTraceCapacity = maxTraceCapacity;
        this.supportedBreakpointKinds = immutableEnumSet(
                Objects.requireNonNull(supportedBreakpointKinds, "supportedBreakpointKinds"),
                DebugBreakpointKind.class);
        this.supportedTraceCategories = immutableEnumSet(
                Objects.requireNonNull(supportedTraceCategories, "supportedTraceCategories"),
                TraceCategory.class);
        if (this.supportedBreakpointKinds.isEmpty()) {
            throw new IllegalArgumentException("At least one breakpoint kind must be supported");
        }
        if (this.supportedTraceCategories.isEmpty()) {
            throw new IllegalArgumentException("At least one trace category must be supported");
        }
        traceBuffer = new TraceBuffer(TraceConfiguration.disabled(initialTraceCapacity));
    }

    private static <E extends Enum<E>> Set<E> immutableEnumSet(
            Set<E> values, Class<E> enumType) {
        return Set.copyOf(values.isEmpty()
                ? EnumSet.noneOf(enumType)
                : EnumSet.copyOf(values));
    }

    public int maxBreakpoints() {
        return maxBreakpoints;
    }

    public DebugBreakpoint setBreakpoint(DebugBreakpoint breakpoint) {
        Objects.requireNonNull(breakpoint, "breakpoint");
        if (!supportedBreakpointKinds.contains(breakpoint.condition().kind())) {
            throw new UnsupportedOperationException(
                    "Unsupported breakpoint kind: " + breakpoint.condition().kind());
        }
        if (!breakpoints.containsKey(breakpoint.id())
                && breakpoints.size() >= maxBreakpoints) {
            throw new IllegalStateException("Breakpoint capacity is exhausted");
        }
        DebugBreakpoint previous = breakpoints.put(breakpoint.id(), breakpoint);
        if (previous != null
                && !previous.equals(breakpoint)
                && hasPendingOrReadyMatch(breakpoint.id())) {
            clearPendingMatch();
        }
        rebuildEnabledBreakpoints();
        return breakpoint;
    }

    public boolean removeBreakpoint(DebugBreakpointId breakpointId) {
        Objects.requireNonNull(breakpointId, "breakpointId");
        DebugBreakpoint removed = breakpoints.remove(breakpointId);
        if (removed == null) {
            return false;
        }
        if (hasPendingOrReadyMatch(breakpointId)) {
            clearPendingMatch();
        }
        rebuildEnabledBreakpoints();
        return true;
    }

    public DebugBreakpointList listBreakpoints() {
        return new DebugBreakpointList(new ArrayList<>(breakpoints.values()));
    }

    private void rebuildEnabledBreakpoints() {
        ArrayList<DebugBreakpoint> enabled = new ArrayList<>(breakpoints.size());
        for (DebugBreakpoint breakpoint : breakpoints.values()) {
            if (breakpoint.enabled()) {
                enabled.add(breakpoint);
            }
        }
        enabled.sort((left, right) ->
                Long.compare(left.id().value(), right.id().value()));
        enabledBreakpoints = enabled.toArray(DebugBreakpoint[]::new);
    }

    public TraceConfiguration configureTrace(TraceConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        if (configuration.capacity() > maxTraceCapacity) {
            throw new IllegalArgumentException(
                    "Trace capacity exceeds the negotiated limit: "
                            + configuration.capacity());
        }
        if (!supportedTraceCategories.containsAll(configuration.categories())) {
            throw new UnsupportedOperationException(
                    "Trace configuration contains an unsupported category");
        }
        traceBuffer = traceBuffer.reconfigured(configuration);
        return configuration;
    }

    public TraceConfiguration traceConfiguration() {
        return traceBuffer.configuration();
    }

    public TraceReadResult readTrace(TraceReadRequest request) {
        return traceBuffer.read(Objects.requireNonNull(request, "request"));
    }

    public boolean hasEnabledBreakpoints() {
        return enabledBreakpoints.length != 0;
    }

    public boolean isActive() {
        return hasEnabledBreakpoints() || traceBuffer.isEnabled();
    }

    public boolean requiresCpuHooks() {
        if (traceBuffer.isEnabled(TraceCategory.CPU)
                || traceBuffer.isEnabled(TraceCategory.MEMORY)
                || traceBuffer.isEnabled(TraceCategory.INTERRUPT)) {
            return true;
        }
        for (int i = 0; i < enabledBreakpoints.length; i++) {
            DebugBreakpointKind kind = enabledBreakpoints[i].condition().kind();
            if (kind == DebugBreakpointKind.PROGRAM_COUNTER
                    || kind == DebugBreakpointKind.MEMORY
                    || kind == DebugBreakpointKind.OPCODE
                    || kind == DebugBreakpointKind.INTERRUPT) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean requiresMemoryAccessHooks() {
        if (traceBuffer.isEnabled(TraceCategory.MEMORY)) {
            return true;
        }
        for (int i = 0; i < enabledBreakpoints.length; i++) {
            if (enabledBreakpoints[i].condition().kind() == DebugBreakpointKind.MEMORY) {
                return true;
            }
        }
        return false;
    }

    /** Aligns the attachment before its next emulated tick. */
    public void alignMasterTick(long masterTick) {
        if (masterTick < 0) {
            throw new IllegalArgumentException("Master tick must not be negative");
        }
        if (masterTick < this.masterTick) {
            throw new IllegalArgumentException(
                    "Debug instrumentation tick cannot move backwards");
        }
        this.masterTick = masterTick;
    }

    /** Called exactly once immediately before each attached {@code Gameboy.tick()}. */
    public void onMasterTickStarted() {
        masterTick = Math.addExact(masterTick, 1L);
        for (int i = 0; i < enabledBreakpoints.length; i++) {
            DebugBreakpoint breakpoint = enabledBreakpoints[i];
            DebugBreakpointCondition condition = breakpoint.condition();
            if (condition instanceof DebugCounterCondition counter
                    && counter.counter() == DebugCounterType.MASTER_TICK
                    && counter.value() == masterTick) {
                offerImmediateMatch(breakpoint.id());
                return;
            }
        }
    }

    /** Matches an owner-defined completed frame counter at its boundary. */
    public void onFrameBoundary(long frame) {
        if (frame < 0) {
            throw new IllegalArgumentException("Frame must not be negative");
        }
        for (int i = 0; i < enabledBreakpoints.length; i++) {
            DebugBreakpoint breakpoint = enabledBreakpoints[i];
            DebugBreakpointCondition condition = breakpoint.condition();
            if (condition instanceof DebugCounterCondition counter
                    && counter.counter() == DebugCounterType.FRAME
                    && counter.value() == frame) {
                offerImmediateMatch(breakpoint.id());
                return;
            }
        }
    }

    public BreakpointMatch pollBreakpointMatch() {
        BreakpointMatch match = readyMatch;
        readyMatch = null;
        return match;
    }

    public void clearPendingMatch() {
        pendingBreakpointId = null;
        pendingMatchTick = 0;
        readyMatch = null;
    }

    private boolean hasPendingOrReadyMatch(DebugBreakpointId breakpointId) {
        return breakpointId.equals(pendingBreakpointId)
                || readyMatch != null && breakpointId.equals(readyMatch.breakpointId());
    }

    @Override
    public void onInstructionFetch(int programCounter) {
        for (int i = 0; i < enabledBreakpoints.length; i++) {
            DebugBreakpoint breakpoint = enabledBreakpoints[i];
            if (breakpoint.condition() instanceof DebugPcCondition
                    && DebugBreakpointMatcher.matchesInstruction(
                            breakpoint, programCounter, false, 0)) {
                offerRetirementMatch(breakpoint.id());
                return;
            }
        }
    }

    @Override
    public void onOpcodeFetched(
            int programCounter, boolean cbPrefixed, int opcode) {
        for (int i = 0; i < enabledBreakpoints.length; i++) {
            DebugBreakpoint breakpoint = enabledBreakpoints[i];
            if (breakpoint.condition() instanceof DebugOpcodeCondition
                    && DebugBreakpointMatcher.matchesInstruction(
                            breakpoint, programCounter, cbPrefixed, opcode)) {
                offerRetirementMatch(breakpoint.id());
                return;
            }
        }
    }

    @Override
    public void onInstructionRetired(
            boolean instructionKnown,
            int programCounter,
            int opcode,
            int prefixedOpcode) {
        if (instructionKnown && traceBuffer.isEnabled(TraceCategory.CPU)) {
            if (traceBuffer.configuration().filter().acceptsCpu(programCounter)) {
                traceBuffer.append(
                        masterTick,
                        TraceSource.CPU,
                        new CpuInstructionTrace(programCounter, opcode, prefixedOpcode));
            }
        }
        if (pendingBreakpointId != null && readyMatch == null) {
            readyMatch = new BreakpointMatch(pendingBreakpointId, pendingMatchTick);
            pendingBreakpointId = null;
            pendingMatchTick = 0;
        }
    }

    @Override
    public void onMemoryAccess(DebugMemoryAccess access, int address, int value) {
        Objects.requireNonNull(access, "access");
        for (int i = 0; i < enabledBreakpoints.length; i++) {
            DebugBreakpoint breakpoint = enabledBreakpoints[i];
            if (breakpoint.condition() instanceof DebugMemoryCondition
                    && DebugBreakpointMatcher.matchesMemory(
                            breakpoint, access, address, value)) {
                // Bus accesses are themselves complete observations. Owners poll only after the
                // enclosing Gameboy.tick(), so expose the match at that safe point even when the
                // CPU is idle and no instruction retirement will follow (for example STOP polling).
                offerImmediateMatch(breakpoint.id());
                break;
            }
        }
        if (traceBuffer.isEnabled(TraceCategory.MEMORY)
                && traceBuffer.configuration().filter().acceptsMemory(access, address)) {
            traceBuffer.append(
                    masterTick,
                    TraceSource.MEMORY_BUS,
                    new MemoryAccessTrace(access, address, value));
        }
    }

    @Override
    public void onInterruptRequested(DebugInterruptType interrupt) {
        Objects.requireNonNull(interrupt, "interrupt");
        if (traceBuffer.isEnabled(TraceCategory.INTERRUPT)
                && traceBuffer.configuration().filter().acceptsInterrupt(interrupt)) {
            traceBuffer.append(
                    masterTick,
                    TraceSource.INTERRUPT_CONTROLLER,
                    new InterruptTrace(InterruptTrace.Kind.REQUESTED, interrupt));
        }
    }

    @Override
    public void onInterruptAccepted(DebugInterruptType interrupt) {
        Objects.requireNonNull(interrupt, "interrupt");
        for (int i = 0; i < enabledBreakpoints.length; i++) {
            DebugBreakpoint breakpoint = enabledBreakpoints[i];
            if (breakpoint.condition() instanceof DebugInterruptCondition
                    && DebugBreakpointMatcher.matchesInterrupt(breakpoint, interrupt)) {
                offerRetirementMatch(breakpoint.id());
                break;
            }
        }
        if (traceBuffer.isEnabled(TraceCategory.INTERRUPT)
                && traceBuffer.configuration().filter().acceptsInterrupt(interrupt)) {
            traceBuffer.append(
                    masterTick,
                    TraceSource.CPU,
                    new InterruptTrace(InterruptTrace.Kind.ACCEPTED, interrupt));
        }
    }

    private void offerRetirementMatch(DebugBreakpointId breakpointId) {
        if (pendingBreakpointId == null && readyMatch == null) {
            pendingBreakpointId = breakpointId;
            pendingMatchTick = masterTick;
        }
    }

    private void offerImmediateMatch(DebugBreakpointId breakpointId) {
        if (pendingBreakpointId == null && readyMatch == null) {
            readyMatch = new BreakpointMatch(breakpointId, masterTick);
        }
    }

    /** Lightweight owner-side match completed into a public hit after snapshot capture. */
    public record BreakpointMatch(DebugBreakpointId breakpointId, long matchMasterTick) {

        public BreakpointMatch {
            Objects.requireNonNull(breakpointId, "breakpointId");
            if (matchMasterTick < 0) {
                throw new IllegalArgumentException("Match tick must not be negative");
            }
        }
    }
}
