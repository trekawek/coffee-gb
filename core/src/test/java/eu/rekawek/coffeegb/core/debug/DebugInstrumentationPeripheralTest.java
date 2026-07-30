package eu.rekawek.coffeegb.core.debug;

import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpoint;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointId;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugPpuCondition;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugSerialCondition;
import eu.rekawek.coffeegb.core.debug.trace.ApuTrace;
import eu.rekawek.coffeegb.core.debug.trace.DmaTrace;
import eu.rekawek.coffeegb.core.debug.trace.InputTrace;
import eu.rekawek.coffeegb.core.debug.trace.MapperRtcTrace;
import eu.rekawek.coffeegb.core.debug.trace.PpuTrace;
import eu.rekawek.coffeegb.core.debug.trace.SerialIrTrace;
import eu.rekawek.coffeegb.core.debug.trace.TimerTrace;
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory;
import eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration;
import eu.rekawek.coffeegb.core.debug.trace.TraceEntry;
import eu.rekawek.coffeegb.core.debug.trace.TraceReadRequest;
import eu.rekawek.coffeegb.core.debug.trace.TraceSource;
import org.junit.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DebugInstrumentationPeripheralTest {

    @Test
    public void disabledInstrumentationRequestsNoPeripheralHooksAndRecordsNothing() {
        DebugInstrumentation instrumentation = instrumentation();

        assertFalse(instrumentation.isActive());
        assertFalse(instrumentation.requiresCpuHooks());
        assertFalse(instrumentation.requiresMemoryAccessHooks());
        assertFalse(instrumentation.requiresPpuHooks());
        assertFalse(instrumentation.requiresPpuMemoryAccessHooks());
        assertFalse(instrumentation.requiresInterruptHooks());
        assertFalse(instrumentation.requiresDmaHooks());
        assertFalse(instrumentation.requiresTimerHooks());
        assertFalse(instrumentation.requiresSerialIrHooks());
        assertFalse(instrumentation.requiresInputHooks());
        assertFalse(instrumentation.requiresMapperRtcHooks());
        assertFalse(instrumentation.requiresApuHooks());

        instrumentation.alignMasterTick(10);
        instrumentation.alignOwnerFrame(2);
        instrumentation.alignPpuState(3, DebugPpuMode.OAM_SEARCH);
        instrumentation.onMasterTickStarted();
        instrumentation.onPpuEvent(
                PpuTrace.Kind.MODE_CHANGED, 0, 3, 80, DebugPpuMode.PIXEL_TRANSFER);
        instrumentation.onDmaEvent(
                DmaTrace.Engine.OAM, DmaTrace.Kind.STARTED,
                0xc000, 0xfe00, 0xa0, 0);
        instrumentation.onTimerEvent(
                TimerTrace.Kind.COUNTER_INCREMENTED, 1, 2, 3, 4);
        instrumentation.onSerialIrEvent(
                SerialIrTrace.Endpoint.SERIAL,
                SerialIrTrace.Kind.TRANSFER_STARTED,
                0x5a);
        instrumentation.onInputEvent(InputTrace.Kind.PRESSED, 1, 1);
        instrumentation.onMapperRtcEvent(
                MapperRtcTrace.Kind.ROM_BANK_CHANGED, -1, 2);
        instrumentation.onApuEvent(
                ApuTrace.Kind.REGISTER_WRITTEN, 1, 0xff12, 0xf3);

        var read = instrumentation.readTrace(TraceReadRequest.initial(32));
        assertTrue(read.entries().isEmpty());
        assertEquals(0, read.nextSequence());
        assertNull(instrumentation.pollBreakpointMatch());
    }

    @Test
    public void typedPeripheralEventsShareOneTickAndPreserveCallbackOrder() {
        DebugInstrumentation instrumentation = instrumentation();
        instrumentation.configureTrace(new TraceConfiguration(
                32,
                EnumSet.of(
                        TraceCategory.PPU,
                        TraceCategory.DMA,
                        TraceCategory.TIMER,
                        TraceCategory.SERIAL_IR,
                        TraceCategory.INPUT,
                        TraceCategory.MAPPER_RTC,
                        TraceCategory.APU)));
        instrumentation.alignMasterTick(40);
        instrumentation.onMasterTickStarted();

        instrumentation.onPpuEvent(
                PpuTrace.Kind.MODE_CHANGED, 7, 12, 80, DebugPpuMode.PIXEL_TRANSFER);
        instrumentation.onDmaEvent(
                DmaTrace.Engine.VRAM_HBLANK, DmaTrace.Kind.BYTE_TRANSFERRED,
                0x1230, 0x8010, 32, 17);
        instrumentation.onTimerEvent(
                TimerTrace.Kind.COUNTER_RELOADED, 0xabcd, 0x44, 0x44, 5);
        instrumentation.onSerialIrEvent(
                SerialIrTrace.Endpoint.INFRARED,
                SerialIrTrace.Kind.SIGNAL_CHANGED,
                1);
        instrumentation.onInputEvent(InputTrace.Kind.RELEASED, 0x10, 0x20);
        instrumentation.onMapperRtcEvent(
                MapperRtcTrace.Kind.RTC_REGISTER_READ, 0x08, 59);
        instrumentation.onApuEvent(
                ApuTrace.Kind.CHANNEL_TRIGGERED, 2, 0xff19, 0x80);

        List<TraceEntry> entries = instrumentation.readTrace(
                TraceReadRequest.initial(32)).entries();
        assertEquals(7, entries.size());
        assertEquals(List.of(
                        TraceSource.PPU,
                        TraceSource.DMA,
                        TraceSource.TIMER,
                        TraceSource.INFRARED,
                        TraceSource.INPUT,
                        TraceSource.RTC,
                        TraceSource.APU),
                entries.stream().map(TraceEntry::source).toList());
        assertEquals(List.of(
                        PpuTrace.class,
                        DmaTrace.class,
                        TimerTrace.class,
                        SerialIrTrace.class,
                        InputTrace.class,
                        MapperRtcTrace.class,
                        ApuTrace.class),
                entries.stream().map(entry -> entry.event().getClass()).toList());
        for (int i = 0; i < entries.size(); i++) {
            assertEquals(i, entries.get(i).sequence());
            assertEquals(41, entries.get(i).masterTick());
        }
    }

    @Test
    public void ppuAlignmentIsSilentAndCombinedOwnerFrameConditionReevaluatesAtBoundary() {
        DebugInstrumentation instrumentation = instrumentation();
        instrumentation.configureTrace(new TraceConfiguration(
                8, EnumSet.of(TraceCategory.PPU)));
        DebugBreakpointId id = new DebugBreakpointId(9);
        instrumentation.setBreakpoint(new DebugBreakpoint(
                id,
                true,
                new DebugPpuCondition(5, 10, DebugPpuMode.HBLANK)));

        instrumentation.alignMasterTick(100);
        instrumentation.alignOwnerFrame(4);
        instrumentation.alignPpuState(10, DebugPpuMode.HBLANK);
        assertNull(instrumentation.pollBreakpointMatch());
        assertTrue(instrumentation.readTrace(TraceReadRequest.initial(8)).entries().isEmpty());

        instrumentation.onMasterTickStarted();
        assertNull(instrumentation.pollBreakpointMatch());
        instrumentation.onFrameBoundary(5);

        DebugInstrumentation.BreakpointMatch match = instrumentation.pollBreakpointMatch();
        assertEquals(id, match.breakpointId());
        assertEquals(101, match.matchMasterTick());
        assertTrue(instrumentation.readTrace(TraceReadRequest.initial(8)).entries().isEmpty());
    }

    @Test
    public void clearingTimelineRequiresAFreshPpuObservationBeforeFrameMatching() {
        DebugInstrumentation instrumentation = instrumentation();
        DebugBreakpointId id = new DebugBreakpointId(4);
        instrumentation.setBreakpoint(new DebugBreakpoint(
                id, true, DebugPpuCondition.atFrame(5)));
        instrumentation.alignOwnerFrame(4);
        instrumentation.alignPpuState(10, DebugPpuMode.HBLANK);

        instrumentation.clearTimelineCorrelation();
        instrumentation.onFrameBoundary(5);
        assertNull(instrumentation.pollBreakpointMatch());

        instrumentation.onPpuEvent(
                PpuTrace.Kind.MODE_CHANGED, 1, 10, 200, DebugPpuMode.OAM_SEARCH);
        assertEquals(id, instrumentation.pollBreakpointMatch().breakpointId());
    }

    @Test
    public void serialBreakpointsChooseLowestIdOnOneEdgeButFirstObservedEdgeWins() {
        DebugInstrumentation instrumentation = instrumentation();
        instrumentation.configureTrace(new TraceConfiguration(
                8, EnumSet.of(TraceCategory.SERIAL_IR)));
        DebugBreakpointId broadId = new DebugBreakpointId(9);
        DebugBreakpointId maskedId = new DebugBreakpointId(2);
        instrumentation.setBreakpoint(new DebugBreakpoint(
                broadId,
                true,
                new DebugSerialCondition(
                        DebugSerialCondition.Event.TRANSFER_STARTED, 0xa5)));
        instrumentation.setBreakpoint(new DebugBreakpoint(
                maskedId,
                true,
                new DebugSerialCondition(
                        DebugSerialCondition.Event.TRANSFER_STARTED, 0xa0, 0xf0)));
        instrumentation.onMasterTickStarted();

        instrumentation.onSerialIrEvent(
                SerialIrTrace.Endpoint.SERIAL,
                SerialIrTrace.Kind.TRANSFER_STARTED,
                0xa5);
        assertEquals(maskedId, instrumentation.pollBreakpointMatch().breakpointId());

        instrumentation.setBreakpoint(new DebugBreakpoint(
                maskedId,
                false,
                new DebugSerialCondition(
                        DebugSerialCondition.Event.TRANSFER_STARTED, 0xa0, 0xf0)));
        DebugBreakpointId completionId = new DebugBreakpointId(1);
        instrumentation.setBreakpoint(new DebugBreakpoint(
                completionId,
                true,
                new DebugSerialCondition(
                        DebugSerialCondition.Event.BYTE_TRANSFERRED, 0x42)));
        instrumentation.onSerialIrEvent(
                SerialIrTrace.Endpoint.SERIAL,
                SerialIrTrace.Kind.TRANSFER_STARTED,
                0xa5);
        instrumentation.onSerialIrEvent(
                SerialIrTrace.Endpoint.SERIAL,
                SerialIrTrace.Kind.BYTE_TRANSFERRED,
                0x42);

        assertEquals(broadId, instrumentation.pollBreakpointMatch().breakpointId());
        List<TraceEntry> entries = instrumentation.readTrace(
                TraceReadRequest.initial(8)).entries();
        assertEquals(3, entries.size());
        assertTrue(entries.stream().allMatch(entry -> entry.source() == TraceSource.SERIAL));
        assertEquals(List.of(
                        SerialIrTrace.Kind.TRANSFER_STARTED,
                        SerialIrTrace.Kind.TRANSFER_STARTED,
                        SerialIrTrace.Kind.BYTE_TRANSFERRED),
                entries.stream()
                        .map(entry -> ((SerialIrTrace) entry.event()).kind())
                        .toList());
    }

    private static DebugInstrumentation instrumentation() {
        return new DebugInstrumentation(
                16,
                64,
                8,
                EnumSet.allOf(DebugBreakpointKind.class),
                EnumSet.allOf(TraceCategory.class));
    }
}
