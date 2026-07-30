package eu.rekawek.coffeegb.core.debug.trace;

import eu.rekawek.coffeegb.core.debug.DebugInterruptType;
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess;
import org.junit.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TraceBufferTest {

    @Test
    public void wrapsAtFixedCapacityAndReportsCursorLossAndCumulativeDrops() {
        TraceBuffer buffer = new TraceBuffer(3);
        for (int i = 0; i < 5; i++) {
            assertEquals(i, buffer.append(i, TraceSource.CPU, instruction(i)));
        }

        TraceReadResult result = buffer.read(TraceReadRequest.initial(10));

        assertEquals(List.of(2L, 3L, 4L), sequences(result));
        assertEquals(4, result.nextAfterSequence());
        assertEquals(2, result.missedEventCount());
        assertEquals(2, result.droppedEventCount());
        assertEquals(2, result.oldestAvailableSequence());
        assertEquals(5, result.nextSequence());
        assertEquals(3, buffer.size());
        assertEquals(2, buffer.droppedEventCount());
    }

    @Test
    public void exclusiveCursorPaginatesWithoutRegisteringOrHoldingAReader() {
        TraceBuffer buffer = new TraceBuffer(4);
        buffer.append(0, TraceSource.CPU, instruction(0));
        buffer.append(1, TraceSource.CPU, instruction(1));
        buffer.append(2, TraceSource.CPU, instruction(2));

        TraceReadResult first = buffer.read(TraceReadRequest.initial(2));
        assertEquals(List.of(0L, 1L), sequences(first));
        assertEquals(1, first.nextAfterSequence());

        buffer.append(3, TraceSource.CPU, instruction(3));
        buffer.append(4, TraceSource.CPU, instruction(4));

        TraceReadResult second = buffer.read(first.nextRequest(2));
        assertEquals(List.of(2L, 3L), sequences(second));
        assertEquals(0, second.missedEventCount());
        TraceReadResult third = buffer.read(second.nextRequest(2));
        assertEquals(List.of(4L), sequences(third));
        assertEquals(4, third.nextAfterSequence());

        TraceReadResult caughtUp = buffer.read(third.nextRequest(2));
        assertTrue(caughtUp.entries().isEmpty());
        assertEquals(4, caughtUp.nextAfterSequence());
    }

    @Test
    public void slowCursorDoesNotPreventOverwriteAndSeesItsOwnMissedCount() {
        TraceBuffer buffer = new TraceBuffer(2);
        buffer.append(10, TraceSource.CPU, instruction(0));
        TraceReadResult first = buffer.read(TraceReadRequest.initial(1));
        assertEquals(0, first.nextAfterSequence());

        buffer.append(11, TraceSource.CPU, instruction(1));
        buffer.append(12, TraceSource.CPU, instruction(2));
        buffer.append(13, TraceSource.CPU, instruction(3));

        TraceReadResult resumed = buffer.read(first.nextRequest(2));
        assertEquals(List.of(2L, 3L), sequences(resumed));
        assertEquals(1, resumed.missedEventCount());
        assertEquals(2, resumed.droppedEventCount());
    }

    @Test
    public void appendSequenceDefinesOrderWhenMasterTicksAreEqual() {
        TraceBuffer buffer = new TraceBuffer(4);
        CpuInstructionTrace cpu = instruction(0x100);
        MemoryAccessTrace memory = new MemoryAccessTrace(
                DebugMemoryAccess.READ, 0xc000, 0x42);
        InterruptTrace interrupt = new InterruptTrace(
                InterruptTrace.Kind.REQUESTED, DebugInterruptType.TIMER);

        buffer.append(77, TraceSource.CPU, cpu);
        buffer.append(77, TraceSource.MEMORY_BUS, memory);
        buffer.append(77, TraceSource.INTERRUPT_CONTROLLER, interrupt);

        List<TraceEntry> entries = buffer.read(TraceReadRequest.initial(4)).entries();
        assertEquals(List.of(0L, 1L, 2L),
                entries.stream().map(TraceEntry::sequence).collect(Collectors.toList()));
        assertEquals(List.of(77L, 77L, 77L),
                entries.stream().map(TraceEntry::masterTick).collect(Collectors.toList()));
        assertSame(cpu, entries.get(0).event());
        assertSame(memory, entries.get(1).event());
        assertSame(interrupt, entries.get(2).event());
    }

    @Test
    public void reconfigurationClearsHistoryWithoutResettingCursorSequenceSpace() {
        TraceBuffer original = new TraceBuffer(3);
        original.append(10, TraceSource.CPU, instruction(0));
        original.append(11, TraceSource.CPU, instruction(1));
        TraceReadResult cursorAfterFirst = original.read(TraceReadRequest.initial(1));

        TraceBuffer replacement = original.reconfigured(new TraceConfiguration(
                2, EnumSet.of(TraceCategory.CPU)));
        assertEquals(2, replacement.nextSequence());
        assertEquals(2, replacement.droppedEventCount());
        TraceReadResult beforeNewEvent = replacement.read(cursorAfterFirst.nextRequest(2));
        assertTrue(beforeNewEvent.entries().isEmpty());
        assertEquals(1, beforeNewEvent.missedEventCount());
        assertEquals(1, beforeNewEvent.nextAfterSequence());

        assertEquals(2, replacement.append(12, TraceSource.CPU, instruction(2)));
        TraceReadResult resumed = replacement.read(beforeNewEvent.nextRequest(2));
        assertEquals(List.of(2L), sequences(resumed));
        assertEquals(0, resumed.missedEventCount());
    }

    @Test
    public void categoryGuardLetsCallersSkipConstructionAndFilteredEventsUseNoSequences() {
        Set<TraceCategory> requested = EnumSet.of(TraceCategory.CPU);
        TraceConfiguration configuration = new TraceConfiguration(3, requested);
        requested.add(TraceCategory.MEMORY);
        TraceBuffer buffer = new TraceBuffer(configuration);
        AtomicInteger constructions = new AtomicInteger();

        if (buffer.isEnabled(TraceCategory.MEMORY)) {
            constructions.incrementAndGet();
            buffer.append(0, TraceSource.MEMORY_BUS,
                    new MemoryAccessTrace(DebugMemoryAccess.READ, 0, 0));
        }
        assertEquals(0, constructions.get());
        assertEquals(TraceBuffer.NOT_APPENDED, buffer.append(
                0,
                TraceSource.MEMORY_BUS,
                new MemoryAccessTrace(DebugMemoryAccess.READ, 0, 0)));

        long accepted = TraceBuffer.NOT_APPENDED;
        if (buffer.isEnabled(TraceCategory.CPU)) {
            constructions.incrementAndGet();
            accepted = buffer.append(0, TraceSource.CPU, instruction(0));
        }
        assertEquals(0, accepted);
        assertEquals(1, constructions.get());
        assertTrue(buffer.isEnabled());
        assertTrue(buffer.isEnabled(TraceCategory.CPU));
        assertFalse(buffer.isEnabled(TraceCategory.MEMORY));
        assertThrows(UnsupportedOperationException.class,
                () -> configuration.categories().add(TraceCategory.MEMORY));

        TraceBuffer disabled = new TraceBuffer(TraceConfiguration.disabled(1));
        assertFalse(disabled.isEnabled());
        if (disabled.isEnabled(TraceCategory.CPU)) {
            constructions.incrementAndGet();
            disabled.append(0, TraceSource.CPU, instruction(1));
        }
        assertEquals(1, constructions.get());
    }

    @Test
    public void configurationAndReadBoundsRejectUnboundedRequests() {
        assertThrows(IllegalArgumentException.class, () -> new TraceBuffer(0));
        assertThrows(IllegalArgumentException.class,
                () -> new TraceBuffer(TraceConfiguration.MAX_CAPACITY + 1));
        assertThrows(IllegalArgumentException.class, () -> new TraceReadRequest(-2, 1));
        assertThrows(IllegalArgumentException.class, () -> new TraceReadRequest(-1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TraceReadRequest(-1, TraceReadRequest.MAX_ENTRIES + 1));

        TraceBuffer buffer = new TraceBuffer(1);
        assertThrows(IllegalArgumentException.class,
                () -> buffer.append(-1, TraceSource.CPU, instruction(0)));
        assertThrows(NullPointerException.class, () -> buffer.read(null));
    }

    private static CpuInstructionTrace instruction(int programCounter) {
        return new CpuInstructionTrace(programCounter, 0x00, -1);
    }

    private static List<Long> sequences(TraceReadResult result) {
        return result.entries().stream()
                .map(TraceEntry::sequence)
                .collect(Collectors.toList());
    }
}
