package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.debug.DebugInstrumentation;
import eu.rekawek.coffeegb.core.debug.breakpoint.DebugBreakpointKind;
import eu.rekawek.coffeegb.core.debug.trace.MapperRtcTrace;
import eu.rekawek.coffeegb.core.debug.trace.TraceCategory;
import eu.rekawek.coffeegb.core.debug.trace.TraceConfiguration;
import eu.rekawek.coffeegb.core.debug.trace.TraceEntry;
import eu.rekawek.coffeegb.core.debug.trace.TraceReadRequest;
import eu.rekawek.coffeegb.core.debug.trace.TraceSource;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource;
import org.junit.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MapperDebugInstrumentationTest {

    @Test
    public void mbc1ReportsOnlyEffectiveTransitionsAndAttachOrRestoreIsSilent()
            throws Exception {
        Mbc1 mapper = new Mbc1(new Rom(mbc1Rom()), Battery.NULL_BATTERY);
        DebugInstrumentation instrumentation = instrumentation();
        var state = mapper.captureState();

        mapper.setDebugHooks(instrumentation);
        mapper.restoreState(state);
        assertTrue(trace(instrumentation).isEmpty());

        mapper.setByte(0x2000, 0);
        mapper.setByte(0x2000, 1);
        mapper.setByte(0x2000, 2);
        mapper.setByte(0x2000, 2);
        mapper.setByte(0x0000, 0x0a);
        mapper.setByte(0x0000, 0x1a);

        List<TraceEntry> entries = trace(instrumentation);
        assertEquals(2, entries.size());
        assertMapper(entries.get(0), MapperRtcTrace.Kind.ROM_BANK_CHANGED, -1, 2);
        assertMapper(entries.get(1), MapperRtcTrace.Kind.RAM_ENABLE_CHANGED, -1, 1);
    }

    @Test
    public void mbc3DistinguishesRamBanksFromExplicitRtcSelectionAndDeselect()
            throws Exception {
        Mbc3 mapper = new Mbc3(
                new Rom(mbc3Rom()), Battery.NULL_BATTERY, new VirtualTimeSource());
        DebugInstrumentation instrumentation = instrumentation();
        var state = mapper.captureState();
        mapper.setDebugHooks(instrumentation);
        mapper.restoreState(state);
        assertTrue(trace(instrumentation).isEmpty());

        mapper.setByte(0x0000, 0x0a);
        mapper.setByte(0x4000, 2);
        mapper.setByte(0x4000, 2);
        mapper.setByte(0x4000, 0x08);
        mapper.setByte(0xa000, 0xfe);
        int readValue = mapper.getByte(0xa000);
        mapper.setByte(0x6000, 0x7f);
        mapper.setByte(0x4000, 1);

        List<TraceEntry> entries = trace(instrumentation);
        assertEquals(8, entries.size());
        assertMapper(entries.get(0), MapperRtcTrace.Kind.RAM_ENABLE_CHANGED, -1, 1);
        assertMapper(entries.get(1), MapperRtcTrace.Kind.RAM_BANK_CHANGED, -1, 2);
        assertRtc(entries.get(2), MapperRtcTrace.Kind.RTC_REGISTER_SELECTED, 0x08, 1);
        assertRtc(entries.get(3), MapperRtcTrace.Kind.RTC_REGISTER_WRITTEN, 0x08, 0xfe);
        assertRtc(entries.get(4), MapperRtcTrace.Kind.RTC_REGISTER_READ, 0x08, readValue);
        assertRtc(entries.get(5), MapperRtcTrace.Kind.RTC_LATCHED, -1, 0x7f);
        assertRtc(entries.get(6), MapperRtcTrace.Kind.RTC_REGISTER_SELECTED, -1, 0);
        assertMapper(entries.get(7), MapperRtcTrace.Kind.RAM_BANK_CHANGED, -1, 1);
    }

    @Test
    public void mbc5MasksBankRegistersAndSuppressesEffectiveNoOps() throws Exception {
        Mbc5 mapper = new Mbc5(new Rom(mbc5Rom()), Battery.NULL_BATTERY);
        DebugInstrumentation instrumentation = instrumentation();
        mapper.setDebugHooks(instrumentation);

        mapper.setByte(0x2000, 0x41);
        mapper.setByte(0x3000, 1);
        mapper.setByte(0x2000, 2);
        mapper.setByte(0x2000, 0x42);
        mapper.setByte(0x4000, 2);
        mapper.setByte(0x4000, 0x12);
        mapper.setByte(0x4000, 7);
        mapper.setByte(0x0000, 2);
        mapper.setByte(0x0000, 0x0a);
        mapper.setByte(0x0000, 0);

        List<TraceEntry> entries = trace(instrumentation);
        assertEquals(4, entries.size());
        assertMapper(entries.get(0), MapperRtcTrace.Kind.ROM_BANK_CHANGED, -1, 2);
        assertMapper(entries.get(1), MapperRtcTrace.Kind.RAM_BANK_CHANGED, -1, 2);
        assertMapper(entries.get(2), MapperRtcTrace.Kind.RAM_ENABLE_CHANGED, -1, 1);
        assertMapper(entries.get(3), MapperRtcTrace.Kind.RAM_ENABLE_CHANGED, -1, 0);
    }

    private static DebugInstrumentation instrumentation() {
        DebugInstrumentation instrumentation = new DebugInstrumentation(
                1,
                32,
                8,
                EnumSet.of(DebugBreakpointKind.COUNTER),
                EnumSet.of(TraceCategory.MAPPER_RTC));
        instrumentation.configureTrace(new TraceConfiguration(
                32, EnumSet.of(TraceCategory.MAPPER_RTC)));
        return instrumentation;
    }

    private static List<TraceEntry> trace(DebugInstrumentation instrumentation) {
        return instrumentation.readTrace(TraceReadRequest.initial(32)).entries();
    }

    private static void assertMapper(
            TraceEntry entry, MapperRtcTrace.Kind kind, int register, long value) {
        assertEquals(TraceSource.MAPPER, entry.source());
        assertEvent(entry, kind, register, value);
    }

    private static void assertRtc(
            TraceEntry entry, MapperRtcTrace.Kind kind, int register, long value) {
        assertEquals(TraceSource.RTC, entry.source());
        assertEvent(entry, kind, register, value);
    }

    private static void assertEvent(
            TraceEntry entry, MapperRtcTrace.Kind kind, int register, long value) {
        MapperRtcTrace event = (MapperRtcTrace) entry.event();
        assertEquals(kind, event.kind());
        assertEquals(register, event.register());
        assertEquals(value, event.value());
    }

    private static byte[] mbc1Rom() {
        byte[] rom = new byte[64 * 0x4000];
        rom[0x147] = 0x03;
        rom[0x148] = 0x05;
        rom[0x149] = 0x03;
        return rom;
    }

    private static byte[] mbc3Rom() {
        byte[] rom = new byte[128 * 0x4000];
        rom[0x147] = 0x13;
        rom[0x148] = 0x06;
        rom[0x149] = 0x03;
        return rom;
    }

    private static byte[] mbc5Rom() {
        byte[] rom = new byte[64 * 0x4000];
        rom[0x147] = 0x1b;
        rom[0x148] = 0x05;
        rom[0x149] = 0x03;
        return rom;
    }
}
