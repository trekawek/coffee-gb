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
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPpuCondition;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugSerialCondition;
import eu.rekawek.coffeegb.core.debug.trace.*;

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

    private long ownerFrame;

    private boolean ppuStateKnown;

    private int ppuLy;

    private DebugPpuMode ppuMode = DebugPpuMode.DISABLED;

    private static final int MAX_TRACKED_INTERRUPT_DEPTH = 256;

    private final DebugInterruptType[] acceptedInterrupts =
            new DebugInterruptType[MAX_TRACKED_INTERRUPT_DEPTH];

    private int acceptedInterruptDepth;

    private int untrackedInterruptDepth;

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
        boolean interruptHooksWereRequired = requiresInterruptHooks();
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
        clearInterruptCorrelationIfDetached(interruptHooksWereRequired);
        return breakpoint;
    }

    public boolean removeBreakpoint(DebugBreakpointId breakpointId) {
        Objects.requireNonNull(breakpointId, "breakpointId");
        boolean interruptHooksWereRequired = requiresInterruptHooks();
        DebugBreakpoint removed = breakpoints.remove(breakpointId);
        if (removed == null) {
            return false;
        }
        if (hasPendingOrReadyMatch(breakpointId)) {
            clearPendingMatch();
        }
        rebuildEnabledBreakpoints();
        clearInterruptCorrelationIfDetached(interruptHooksWereRequired);
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
        boolean interruptHooksWereRequired = requiresInterruptHooks();
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
        clearInterruptCorrelationIfDetached(interruptHooksWereRequired);
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

    public boolean requiresPpuHooks() {
        return traceBuffer.isEnabled(TraceCategory.PPU)
                || traceBuffer.isEnabled(TraceCategory.MEMORY)
                || hasEnabledBreakpoint(DebugBreakpointKind.PPU_STATE);
    }

    @Override
    public boolean requiresPpuMemoryAccessHooks() {
        return traceBuffer.isEnabled(TraceCategory.MEMORY);
    }

    public boolean requiresInterruptHooks() {
        return traceBuffer.isEnabled(TraceCategory.INTERRUPT)
                || hasEnabledBreakpoint(DebugBreakpointKind.INTERRUPT);
    }

    public boolean requiresDmaHooks() {
        return traceBuffer.isEnabled(TraceCategory.DMA)
                || traceBuffer.isEnabled(TraceCategory.MEMORY);
    }

    public boolean requiresTimerHooks() {
        return traceBuffer.isEnabled(TraceCategory.TIMER);
    }

    public boolean requiresSerialIrHooks() {
        return traceBuffer.isEnabled(TraceCategory.SERIAL_IR)
                || hasEnabledBreakpoint(DebugBreakpointKind.SERIAL);
    }

    public boolean requiresInputHooks() {
        return traceBuffer.isEnabled(TraceCategory.INPUT);
    }

    public boolean requiresMapperRtcHooks() {
        return traceBuffer.isEnabled(TraceCategory.MAPPER_RTC);
    }

    public boolean requiresApuHooks() {
        return traceBuffer.isEnabled(TraceCategory.APU);
    }

    private boolean hasEnabledBreakpoint(DebugBreakpointKind kind) {
        for (int i = 0; i < enabledBreakpoints.length; i++) {
            if (enabledBreakpoints[i].condition().kind() == kind) {
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

    /** Aligns current PPU state without producing an event or matching a breakpoint. */
    public void alignPpuState(int ly, DebugPpuMode mode) {
        if (ly < 0 || ly > 153) {
            throw new IllegalArgumentException("PPU LY must be in 0..153: " + ly);
        }
        ppuLy = ly;
        ppuMode = Objects.requireNonNull(mode, "mode");
        ppuStateKnown = true;
    }

    /** Aligns the controller-owned frame counter without observing a boundary. */
    public void alignOwnerFrame(long frame) {
        if (frame < 0) {
            throw new IllegalArgumentException("Frame must not be negative");
        }
        ownerFrame = frame;
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
        ownerFrame = frame;
        for (int i = 0; i < enabledBreakpoints.length; i++) {
            DebugBreakpoint breakpoint = enabledBreakpoints[i];
            DebugBreakpointCondition condition = breakpoint.condition();
            if (condition instanceof DebugCounterCondition counter
                    && counter.counter() == DebugCounterType.FRAME
                    && counter.value() == frame) {
                offerImmediateMatch(breakpoint.id());
                return;
            }
            if (ppuStateKnown
                    && condition instanceof DebugPpuCondition
                    && DebugBreakpointMatcher.matchesPpu(
                            breakpoint, ownerFrame, ppuLy, ppuMode)) {
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

    /** Clears correlations that cannot cross a state restore or hidden replay discontinuity. */
    public void clearTimelineCorrelation() {
        clearPendingMatch();
        clearInterruptCorrelation();
        ppuStateKnown = false;
    }

    private void clearInterruptCorrelationIfDetached(boolean previouslyRequired) {
        if (previouslyRequired && !requiresInterruptHooks()) {
            clearInterruptCorrelation();
        }
    }

    private void clearInterruptCorrelation() {
        for (int i = 0; i < acceptedInterruptDepth; i++) {
            acceptedInterrupts[i] = null;
        }
        acceptedInterruptDepth = 0;
        untrackedInterruptDepth = 0;
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
        if (instructionKnown && opcode == 0xd9) {
            completeAcceptedInterrupt();
        }
        if (pendingBreakpointId != null && readyMatch == null) {
            readyMatch = new BreakpointMatch(pendingBreakpointId, pendingMatchTick);
            pendingBreakpointId = null;
            pendingMatchTick = 0;
        }
    }

    @Override
    public void onMemoryAccess(DebugMemoryAccess access, int address, int value) {
        onMemoryAccess(
                DebugAddressSpace.SYSTEM_BUS,
                TraceSource.CPU,
                access,
                address,
                value);
    }

    @Override
    public void onMemoryAccess(
            DebugAddressSpace addressSpace,
            TraceSource source,
            DebugMemoryAccess access,
            int address,
            int value) {
        Objects.requireNonNull(addressSpace, "addressSpace");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(access, "access");
        // The current watchpoint model has no producer dimension and therefore remains scoped
        // to the CPU's logical system-bus view. DMA/PPU accesses are still available in MEMORY
        // trace with explicit provenance and can gain source-aware predicates later.
        if (source == TraceSource.CPU) {
            for (int i = 0; i < enabledBreakpoints.length; i++) {
                DebugBreakpoint breakpoint = enabledBreakpoints[i];
                if (breakpoint.condition() instanceof DebugMemoryCondition
                        && DebugBreakpointMatcher.matchesMemory(
                                breakpoint, access, address, value)) {
                    // Bus accesses are themselves complete observations. Owners poll only after
                    // the enclosing Gameboy.tick(), so expose the match at that safe point even
                    // when the CPU is idle and no retirement follows (for example STOP polling).
                    offerImmediateMatch(breakpoint.id());
                    break;
                }
            }
        }
        if (traceBuffer.isEnabled(TraceCategory.MEMORY)
                && traceBuffer.configuration().filter().acceptsMemory(access, address)) {
            traceBuffer.append(
                    masterTick,
                    source,
                    new MemoryAccessTrace(addressSpace, access, address, value));
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
        if (acceptedInterruptDepth < acceptedInterrupts.length) {
            acceptedInterrupts[acceptedInterruptDepth++] = interrupt;
        } else {
            untrackedInterruptDepth++;
        }
    }

    @Override
    public void onInterruptCleared(DebugInterruptType interrupt) {
        appendInterrupt(InterruptTrace.Kind.CLEARED, interrupt, TraceSource.INTERRUPT_CONTROLLER);
    }

    private void completeAcceptedInterrupt() {
        if (untrackedInterruptDepth > 0) {
            untrackedInterruptDepth--;
            return;
        }
        if (acceptedInterruptDepth == 0) {
            return;
        }
        DebugInterruptType interrupt = acceptedInterrupts[--acceptedInterruptDepth];
        acceptedInterrupts[acceptedInterruptDepth] = null;
        appendInterrupt(InterruptTrace.Kind.COMPLETED, interrupt, TraceSource.CPU);
    }

    private void appendInterrupt(
            InterruptTrace.Kind kind, DebugInterruptType interrupt, TraceSource source) {
        Objects.requireNonNull(interrupt, "interrupt");
        if (traceBuffer.isEnabled(TraceCategory.INTERRUPT)
                && traceBuffer.configuration().filter().acceptsInterrupt(interrupt)) {
            traceBuffer.append(masterTick, source, new InterruptTrace(kind, interrupt));
        }
    }

    @Override
    public void onPpuEvent(
            PpuTrace.Kind kind,
            long ppuFrame,
            int line,
            int dot,
            DebugPpuMode mode) {
        Objects.requireNonNull(kind, "kind");
        ppuLy = line;
        ppuMode = Objects.requireNonNull(mode, "mode");
        ppuStateKnown = true;
        for (int i = 0; i < enabledBreakpoints.length; i++) {
            DebugBreakpoint breakpoint = enabledBreakpoints[i];
            if (breakpoint.condition() instanceof DebugPpuCondition
                    && DebugBreakpointMatcher.matchesPpu(
                            breakpoint, ownerFrame, line, mode)) {
                offerImmediateMatch(breakpoint.id());
                break;
            }
        }
        if (traceBuffer.isEnabled(TraceCategory.PPU)) {
            traceBuffer.append(
                    masterTick,
                    TraceSource.PPU,
                    new PpuTrace(kind, ppuFrame, line, dot, mode));
        }
    }

    @Override
    public void onDmaEvent(
            DmaTrace.Engine engine,
            DmaTrace.Kind kind,
            int sourceAddress,
            int destinationAddress,
            int length,
            int bytesTransferred) {
        if (traceBuffer.isEnabled(TraceCategory.DMA)) {
            traceBuffer.append(
                    masterTick,
                    TraceSource.DMA,
                    new DmaTrace(
                            engine, kind, sourceAddress, destinationAddress,
                            length, bytesTransferred));
        }
    }

    @Override
    public void onTimerEvent(
            TimerTrace.Kind kind, int divider, int counter, int modulo, int control) {
        if (traceBuffer.isEnabled(TraceCategory.TIMER)) {
            traceBuffer.append(
                    masterTick,
                    TraceSource.TIMER,
                    new TimerTrace(kind, divider, counter, modulo, control));
        }
    }

    @Override
    public void onSerialIrEvent(
            SerialIrTrace.Endpoint endpoint, SerialIrTrace.Kind kind, int value) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(kind, "kind");
        if (endpoint == SerialIrTrace.Endpoint.SERIAL) {
            DebugSerialCondition.Event event = switch (kind) {
                case TRANSFER_STARTED -> DebugSerialCondition.Event.TRANSFER_STARTED;
                case BYTE_TRANSFERRED -> DebugSerialCondition.Event.BYTE_TRANSFERRED;
                default -> null;
            };
            if (event != null) {
                for (int i = 0; i < enabledBreakpoints.length; i++) {
                    DebugBreakpoint breakpoint = enabledBreakpoints[i];
                    if (breakpoint.condition() instanceof DebugSerialCondition
                            && DebugBreakpointMatcher.matchesSerial(
                                    breakpoint, event, value)) {
                        offerImmediateMatch(breakpoint.id());
                        break;
                    }
                }
            }
        }
        if (traceBuffer.isEnabled(TraceCategory.SERIAL_IR)) {
            traceBuffer.append(
                    masterTick,
                    endpoint == SerialIrTrace.Endpoint.SERIAL
                            ? TraceSource.SERIAL : TraceSource.INFRARED,
                    new SerialIrTrace(endpoint, kind, value));
        }
    }

    @Override
    public void onInputEvent(InputTrace.Kind kind, int buttonMask, int changedMask) {
        if (traceBuffer.isEnabled(TraceCategory.INPUT)) {
            traceBuffer.append(
                    masterTick,
                    TraceSource.INPUT,
                    new InputTrace(kind, buttonMask, changedMask));
        }
    }

    @Override
    public void onMapperRtcEvent(MapperRtcTrace.Kind kind, int register, long value) {
        if (traceBuffer.isEnabled(TraceCategory.MAPPER_RTC)) {
            TraceSource source = switch (kind) {
                case RTC_LATCHED, RTC_REGISTER_SELECTED,
                        RTC_REGISTER_READ, RTC_REGISTER_WRITTEN -> TraceSource.RTC;
                default -> TraceSource.MAPPER;
            };
            traceBuffer.append(
                    masterTick, source, new MapperRtcTrace(kind, register, value));
        }
    }

    @Override
    public void onApuEvent(ApuTrace.Kind kind, int channel, int register, int value) {
        if (traceBuffer.isEnabled(TraceCategory.APU)) {
            traceBuffer.append(
                    masterTick,
                    TraceSource.APU,
                    new ApuTrace(kind, channel, register, value));
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
