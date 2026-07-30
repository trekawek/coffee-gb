package eu.rekawek.coffeegb.core.debug.trace;

import eu.rekawek.coffeegb.core.debug.DebugPpuMode;
import eu.rekawek.coffeegb.core.debug.DebugInterruptType;
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess;
import org.junit.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TraceEventModelTest {

    @Test
    public void everyRequiredSubsystemHasAClassifiedTypedPayload() {
        List<TraceEvent> events = List.of(
                new CpuInstructionTrace(0x100, 0xcb, 0x11),
                new MemoryAccessTrace(DebugMemoryAccess.WRITE, 0xff80, 0xaa),
                new InterruptTrace(InterruptTrace.Kind.ACCEPTED, DebugInterruptType.VBLANK),
                new PpuTrace(PpuTrace.Kind.FRAME_READY, 4, 144, 0, DebugPpuMode.VBLANK),
                new DmaTrace(DmaTrace.Engine.OAM, DmaTrace.Kind.COMPLETED,
                        0xc000, 0xfe00, 160, 160),
                new TimerTrace(TimerTrace.Kind.COUNTER_RELOADED, 0xabcd, 0x12, 0x12, 5),
                new SerialIrTrace(SerialIrTrace.Endpoint.INFRARED,
                        SerialIrTrace.Kind.SIGNAL_CHANGED, 1),
                new InputTrace(InputTrace.Kind.PRESSED, 0x10, 0x10),
                new MapperRtcTrace(MapperRtcTrace.Kind.RTC_LATCHED, -1, 1234),
                new ApuTrace(ApuTrace.Kind.CHANNEL_TRIGGERED, 1, 0xff14, 0x80));

        assertEquals(List.of(
                        TraceCategory.CPU,
                        TraceCategory.MEMORY,
                        TraceCategory.INTERRUPT,
                        TraceCategory.PPU,
                        TraceCategory.DMA,
                        TraceCategory.TIMER,
                        TraceCategory.SERIAL_IR,
                        TraceCategory.INPUT,
                        TraceCategory.MAPPER_RTC,
                        TraceCategory.APU),
                events.stream().map(TraceEvent::category).toList());
        for (TraceEvent event : events) {
            assertTrue(event.getClass().isRecord());
            for (RecordComponent component : event.getClass().getRecordComponents()) {
                assertFalse("Trace payload exposes an array: " + event.getClass(),
                        component.getType().isArray());
            }
        }
        assertTrue(List.of(TraceSource.values()).contains(TraceSource.SERIAL));
        assertTrue(List.of(TraceSource.values()).contains(TraceSource.INFRARED));
    }

    @Test
    public void entryRejectsMutableOrUnknownEventImplementations() {
        TraceEvent unknown = () -> TraceCategory.CPU;
        assertThrows(IllegalArgumentException.class,
                () -> new TraceEntry(0, 0, TraceSource.CPU, unknown));
        assertThrows(IllegalArgumentException.class,
                () -> new TraceEntry(-1, 0, TraceSource.CPU,
                        new CpuInstructionTrace(0, 0, -1)));
        assertThrows(IllegalArgumentException.class,
                () -> new TraceEntry(0, -1, TraceSource.CPU,
                        new CpuInstructionTrace(0, 0, -1)));
        assertThrows(NullPointerException.class,
                () -> new TraceEntry(0, 0, null, new CpuInstructionTrace(0, 0, -1)));

        TraceBuffer buffer = new TraceBuffer(1);
        assertThrows(IllegalArgumentException.class,
                () -> buffer.append(0, TraceSource.CPU, unknown));
        assertEquals(0, buffer.nextSequence());
    }

    @Test
    public void scalarPayloadsRejectOutOfRangeOrIncoherentValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new CpuInstructionTrace(0x10000, 0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new CpuInstructionTrace(0, 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new CpuInstructionTrace(0, 0xcb, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new MemoryAccessTrace(DebugMemoryAccess.READ, -1, 0));
        assertThrows(NullPointerException.class,
                () -> new InterruptTrace(null, DebugInterruptType.TIMER));
        assertThrows(IllegalArgumentException.class,
                () -> new PpuTrace(PpuTrace.Kind.MODE_CHANGED, 0, 154, 0,
                        DebugPpuMode.VBLANK));
        assertThrows(IllegalArgumentException.class,
                () -> new DmaTrace(DmaTrace.Engine.OAM, DmaTrace.Kind.COMPLETED,
                        0, 0xfe00, 160, 161));
        assertThrows(IllegalArgumentException.class,
                () -> new TimerTrace(TimerTrace.Kind.CONTROL_CHANGED, 0, 0, 0, 8));
        assertThrows(IllegalArgumentException.class,
                () -> new SerialIrTrace(SerialIrTrace.Endpoint.SERIAL,
                        SerialIrTrace.Kind.BIT_SHIFTED, 256));
        assertThrows(IllegalArgumentException.class,
                () -> new InputTrace(InputTrace.Kind.STATE_CHANGED, 0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new MapperRtcTrace(MapperRtcTrace.Kind.RTC_REGISTER_WRITTEN, 0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new ApuTrace(ApuTrace.Kind.REGISTER_WRITTEN, 5, 0xff10, 0));
    }

    @Test
    public void readResultsDefensivelyCopyAndExposeOnlyAnImmutablePage() {
        TraceEntry first = new TraceEntry(
                0, 10, TraceSource.CPU, new CpuInstructionTrace(0x100, 0, -1));
        TraceEntry second = new TraceEntry(
                1, 10, TraceSource.CPU, new CpuInstructionTrace(0x101, 0, -1));
        List<TraceEntry> source = new ArrayList<>();
        source.add(first);
        TraceReadResult result = new TraceReadResult(source, 0, 0, 0, 0, 1);
        source.clear();

        assertEquals(List.of(first), result.entries());
        assertThrows(UnsupportedOperationException.class, () -> result.entries().add(second));
        assertEquals(new TraceReadRequest(0, 7), result.nextRequest(7));
        assertThrows(IllegalArgumentException.class,
                () -> new TraceReadResult(List.of(second, first), 0, 0, 0, 0, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new TraceReadResult(List.of(first), 1, 0, 0, 0, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new TraceReadResult(List.of(), -1, 1, 0, 0, 0));
    }
}
