package eu.rekawek.coffeegb.core.debug;

import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugCounterCondition;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugMemoryCondition;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPcCondition;
import eu.rekawek.coffeegb.core.debug.trace.CpuInstructionTrace;
import eu.rekawek.coffeegb.core.debug.trace.InterruptTrace;
import eu.rekawek.coffeegb.core.debug.trace.MemoryAccessTrace;
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory;
import eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration;
import eu.rekawek.coffeegb.core.debug.trace.TraceFilter;
import eu.rekawek.coffeegb.core.debug.trace.TraceReadRequest;
import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DebugInstrumentationTest {

    @Test
    public void instructionBreakpointStopsAtRetirementAndLowestIdWins() {
        DebugInstrumentation instrumentation = instrumentation(4);
        instrumentation.setBreakpoint(new DebugBreakpoint(
                new DebugBreakpointId(9), true, DebugPcCondition.at(0x100)));
        instrumentation.setBreakpoint(new DebugBreakpoint(
                new DebugBreakpointId(2), true, DebugPcCondition.at(0x100)));

        instrumentation.alignMasterTick(0);
        instrumentation.onMasterTickStarted();
        instrumentation.onInstructionFetch(0x100);
        instrumentation.onOpcodeFetched(0x100, false, 0x00);
        assertNull(instrumentation.pollBreakpointMatch());

        instrumentation.onInstructionRetired(true, 0x100, 0x00, -1);
        DebugInstrumentation.BreakpointMatch match = instrumentation.pollBreakpointMatch();
        assertEquals(2, match.breakpointId().value());
        assertEquals(1, match.matchMasterTick());
        assertNull(instrumentation.pollBreakpointMatch());
    }

    @Test
    public void memoryHitIsReadyForTheCompletedTickSafePoint() {
        DebugInstrumentation instrumentation = instrumentation(2);
        instrumentation.setBreakpoint(new DebugBreakpoint(
                new DebugBreakpointId(3), true,
                new DebugMemoryCondition(DebugMemoryAccess.WRITE, 0xc000, 0xc000, 0xa0, 0xf0)));

        instrumentation.onMasterTickStarted();
        instrumentation.onMemoryAccess(DebugMemoryAccess.WRITE, 0xc000, 0xa7);
        assertEquals(3, instrumentation.pollBreakpointMatch().breakpointId().value());
    }

    @Test
    public void exactCounterConditionsAreEdgeTriggered() {
        DebugInstrumentation instrumentation = instrumentation(2);
        instrumentation.setBreakpoint(new DebugBreakpoint(
                new DebugBreakpointId(1), true, DebugCounterCondition.atMasterTick(2)));

        instrumentation.onMasterTickStarted();
        assertNull(instrumentation.pollBreakpointMatch());
        instrumentation.onMasterTickStarted();
        assertEquals(1, instrumentation.pollBreakpointMatch().breakpointId().value());
        instrumentation.onMasterTickStarted();
        assertNull(instrumentation.pollBreakpointMatch());
    }

    @Test
    public void traceFiltersRunBeforeTypedEventConstructionAndPreserveOrder() {
        DebugInstrumentation instrumentation = instrumentation(2);
        TraceFilter filter = new TraceFilter(
                0x100, 0x1ff,
                0xc000, 0xc0ff,
                EnumSet.of(DebugMemoryAccess.WRITE),
                EnumSet.of(DebugInterruptType.TIMER));
        instrumentation.configureTrace(new TraceConfiguration(
                8,
                EnumSet.of(TraceCategory.CPU, TraceCategory.MEMORY, TraceCategory.INTERRUPT),
                filter));
        instrumentation.onMasterTickStarted();

        instrumentation.onMemoryAccess(DebugMemoryAccess.READ, 0xc000, 1);
        instrumentation.onMemoryAccess(DebugMemoryAccess.WRITE, 0xd000, 2);
        instrumentation.onMemoryAccess(DebugMemoryAccess.WRITE, 0xc001, 3);
        instrumentation.onInterruptRequested(DebugInterruptType.SERIAL);
        instrumentation.onInterruptRequested(DebugInterruptType.TIMER);
        instrumentation.onInstructionRetired(true, 0x80, 0, -1);
        instrumentation.onInstructionRetired(true, 0x101, 0, -1);

        var read = instrumentation.readTrace(TraceReadRequest.initial(8));
        assertEquals(3, read.entries().size());
        assertTrue(read.entries().get(0).event() instanceof MemoryAccessTrace);
        assertTrue(read.entries().get(1).event() instanceof InterruptTrace);
        assertTrue(read.entries().get(2).event() instanceof CpuInstructionTrace);
        assertEquals(0, read.entries().get(0).sequence());
        assertEquals(1, read.entries().get(1).sequence());
        assertEquals(2, read.entries().get(2).sequence());
    }

    @Test
    public void breakpointCapacityAndUnsupportedKindsAreEnforcedOnTheColdPath() {
        DebugInstrumentation instrumentation = instrumentation(1);
        instrumentation.setBreakpoint(new DebugBreakpoint(
                new DebugBreakpointId(1), true, DebugPcCondition.at(0x100)));
        assertThrows(IllegalStateException.class, () -> instrumentation.setBreakpoint(
                new DebugBreakpoint(
                        new DebugBreakpointId(2), true, DebugPcCondition.at(0x101))));
        assertThrows(UnsupportedOperationException.class, () -> instrumentation.setBreakpoint(
                new DebugBreakpoint(
                        new DebugBreakpointId(1), true,
                        new eu.rekawek.coffeegb.core.debug.breakpoint.DebugPpuCondition(
                                0, 0, DebugPpuMode.HBLANK))));
        assertFalse(instrumentation.traceConfiguration().isEnabled());
    }

    @Test
    public void editingOrRemovingADefinitionCancelsItsAlreadyObservedMatch() {
        DebugInstrumentation instrumentation = instrumentation(2);
        DebugBreakpointId id = new DebugBreakpointId(7);
        instrumentation.setBreakpoint(new DebugBreakpoint(id, true, DebugPcCondition.at(0x100)));

        instrumentation.onMasterTickStarted();
        instrumentation.onInstructionFetch(0x100);
        instrumentation.setBreakpoint(new DebugBreakpoint(id, true, DebugPcCondition.at(0x200)));
        instrumentation.onInstructionRetired(true, 0x100, 0x00, -1);
        assertNull(instrumentation.pollBreakpointMatch());

        instrumentation.setBreakpoint(new DebugBreakpoint(
                id, true, new DebugMemoryCondition(DebugMemoryAccess.READ, 0xc000, 0xc000)));
        instrumentation.onMemoryAccess(DebugMemoryAccess.READ, 0xc000, 0x12);
        assertTrue(instrumentation.removeBreakpoint(id));
        assertNull(instrumentation.pollBreakpointMatch());
    }

    @Test
    public void polledMatchRetainsTheExactDefinitionAfterIdReuse() {
        DebugInstrumentation instrumentation = instrumentation(2);
        DebugBreakpointId id = new DebugBreakpointId(8);
        DebugBreakpoint matched = new DebugBreakpoint(
                id, true,
                new DebugMemoryCondition(DebugMemoryAccess.READ, 0xc000, 0xc000));
        instrumentation.setBreakpoint(matched);

        instrumentation.onMemoryAccess(DebugMemoryAccess.READ, 0xc000, 0x12);
        DebugInstrumentation.BreakpointMatch match = instrumentation.pollBreakpointMatch();

        assertTrue(instrumentation.removeBreakpoint(id));
        instrumentation.setBreakpoint(
                new DebugBreakpoint(id, true, DebugPcCondition.at(0x200)));
        assertEquals(id, match.breakpointId());
        assertSame(matched, match.breakpoint());
        assertEquals(DebugMemoryAccess.READ,
                ((DebugMemoryCondition) match.breakpoint().condition()).access());
    }

    private static DebugInstrumentation instrumentation(int maxBreakpoints) {
        return new DebugInstrumentation(
                maxBreakpoints,
                32,
                8,
                EnumSet.of(
                        DebugBreakpointKind.PROGRAM_COUNTER,
                        DebugBreakpointKind.MEMORY,
                        DebugBreakpointKind.OPCODE,
                        DebugBreakpointKind.INTERRUPT,
                        DebugBreakpointKind.COUNTER),
                EnumSet.of(
                        TraceCategory.CPU,
                        TraceCategory.MEMORY,
                        TraceCategory.INTERRUPT));
    }
}
