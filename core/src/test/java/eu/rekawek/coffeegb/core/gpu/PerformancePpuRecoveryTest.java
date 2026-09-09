package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.performance.PerformanceDiagnostics;
import org.junit.Test;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Differential recovery tests use the real dot machines before admitting idle PPU clocks. */
public class PerformancePpuRecoveryTest {
    private static final HardwareProfile[] PROFILES = {
            HardwareProfileRegistry.DMG, HardwareProfileRegistry.MGB,
            HardwareProfileRegistry.CGB, HardwareProfileRegistry.CGB0,
            HardwareProfileRegistry.SGB, HardwareProfileRegistry.SGB2
    };

    @Test
    public void repeatedMode3EntryWritesRecoverHblankAndPreserveWindowCheckpoints()
            throws Exception {
        for (HardwareProfile profile : PROFILES) {
            try (Gameboy scalar = session(profile, true); Gameboy bulk = session(profile, true)) {
                Gpu expected = scalar.getGpu();
                Gpu actual = bulk.getGpu();
                var diagnostics = new PerformanceDiagnostics(70_224);
                actual.setPerformanceDiagnostics(diagnostics);
                configureWindow(expected);
                configureWindow(actual);
                for (int line = 1; line <= 5; line++) {
                    while (actual.getLine() != line || actual.getTicksInLine() != 79) {
                        expected.tick();
                        actual.tick();
                    }
                    // A WX strobe in the final OAM clock rejects direct composition on every
                    // line. Keep the window enabled so the legacy BG-only cursor cannot arm.
                    expected.setPerformanceScanlineEnabled(true);
                    actual.setPerformanceScanlineEnabled(true);
                    expected.setByteFromCpu(0xff4b, 7);
                    actual.setByteFromCpu(0xff4b, 7);
                    expected.tick();
                    actual.tick();
                    assertTrue("entry write unexpectedly armed " + profile.id(),
                            !actual.isPerformanceScanlineCursorActive());
                    while (actual.getMode() != Mode.HBlank) {
                        expected.tick();
                        actual.tick();
                    }
                    int recovered = 0;
                    while (actual.getLine() == line) {
                        int span = Math.min(54, actual.performanceQuietSpanLimit());
                        if (span > 0) {
                            for (int i = 0; i < span; i++) {
                                expected.tick();
                            }
                            assertTrue(actual.advancePerformanceQuietSpan(span));
                            recovered += span;
                        } else {
                            expected.tick();
                            actual.tick();
                        }
                        assertState(profile.id() + " scalar-line HBlank " + line,
                                expected.captureState(), actual.captureState());
                    }
                    assertTrue(profile.id() + " scalar line never recovered useful HBlank",
                            recovered > 100);
                }
                assertTrue("rejected arming guards were not diagnosed",
                        diagnostics.snapshot().rejectedLines() >= 5);
                assertTrue("write-strobe rejection has no latch reason",
                        diagnostics.snapshot().blockers().getOrDefault(
                                PerformanceDiagnostics.Blocker.PPU_LATCH, 0L) >= 5);
            }
        }
    }

    @Test
    public void cgb0NormalSpeedMode2SpanMatchesScalarForNativeAndCompatibility()
            throws Exception {
        for (boolean nativeColor : new boolean[] {false, true}) {
            try (Gameboy scalar = session(HardwareProfileRegistry.CGB0, nativeColor);
                    Gameboy bulk = session(HardwareProfileRegistry.CGB0, nativeColor)) {
                Gpu expected = scalar.getGpu();
                Gpu actual = bulk.getGpu();
                while (actual.getLine() != 1 || actual.getTicksInLine() != 20) {
                    // The GPU proof also requires HDMA's published mode-2 request clock.
                    scalar.tick();
                    bulk.tick();
                }
                actual.setPerformanceScanlineEnabled(true);
                int span = actual.performanceCgbNormalSpeedMode2PhaseSpanLimit(54);
                assertEquals("CGB0 mode2 native=" + nativeColor, 54, span);
                for (int i = 0; i < span; i++) {
                    expected.tick();
                }
                actual.advancePerformanceCgbNormalSpeedMode2PhaseSpanTrusted(span);
                assertState("CGB0 mode2 native=" + nativeColor,
                        expected.captureState(), actual.captureState());
            }
        }
    }

    @Test
    public void mode2HeightProofRetainsEveryOtherLcdcHistoryBit() throws Exception {
        for (int base : new int[]{0x91, 0x95}) {
            for (int age = 0; age < 9; age++) {
                for (int ticks : new int[]{1, 3, 8, 54}) {
                    Lcdc expected = new Lcdc();
                    Lcdc actual = new Lcdc();
                    for (Lcdc lcdc : new Lcdc[]{expected, actual}) {
                        lcdc.setGbc(true);
                        lcdc.set(base);
                        for (int i = 0; i < 9; i++) lcdc.tickConflicts();
                        lcdc.set(base ^ 0x20);
                        for (int i = 0; i < age; i++) lcdc.tickConflicts();
                    }
                    assertTrue(!actual.isPerformanceMode2FixedPoint());
                    assertTrue(actual.isPerformanceMode2HeightStable());
                    for (int i = 0; i < ticks; i++) expected.tickConflicts();
                    actual.advancePerformanceMode2FixedPointSpanTrusted(ticks);
                    assertState("mode2 window history " + base + ':' + age + ':' + ticks,
                            expected.captureState(), actual.captureState());
                    actual.set(base ^ 4);
                    assertTrue("a size transition must retain scalar delayed sampling",
                            !actual.isPerformanceMode2HeightStable());
                }
            }
        }
    }

    @Test
    public void nativeDoubleSpeedMode2RecoversImmediatelyAfterWindowBitWrite() throws Exception {
        for (HardwareProfile profile : new HardwareProfile[]{
                HardwareProfileRegistry.CGB, HardwareProfileRegistry.CGB0}) {
            for (int ticks : new int[]{1, 2, 3, 7, 53}) {
                try (Gameboy scalar = session(profile, true); Gameboy bulk = session(profile, true)) {
                    for (Gameboy gameboy : new Gameboy[]{scalar, bulk}) {
                        gameboy.getSpeedMode().setByte(0xff4d, 1);
                        var onStop = gameboy.getSpeedMode().getClass().getDeclaredMethod("onStop");
                        onStop.setAccessible(true);
                        onStop.invoke(gameboy.getSpeedMode());
                        while (gameboy.getGpu().getLine() != 1
                                || gameboy.getGpu().getTicksInLine() != 20) gameboy.tick();
                        gameboy.getGpu().setPerformanceScanlineEnabled(true);
                        gameboy.getGpu().setByte(0xff40, 0xb1);
                    }
                    Gpu expected = scalar.getGpu(), actual = bulk.getGpu();
                    assertEquals(ticks, actual.performanceEpochMode2BulkSpanLimit(ticks));
                    for (int i = 0; i < ticks; i++) expected.tick();
                    actual.advancePerformanceMode2QuietSpanTrusted(ticks);
                    assertState(profile.id() + " mode2 window write prefix " + ticks,
                            expected.captureState(), actual.captureState());
                }
            }
        }
    }

    @Test
    public void doubleSpeedLcdOffSpanPreservesFrozenGpuState() throws Exception {
        try (Gameboy scalar = session(HardwareProfileRegistry.CGB, true);
                Gameboy bulk = session(HardwareProfileRegistry.CGB, true)) {
            for (Gameboy gameboy : new Gameboy[] {scalar, bulk}) {
                gameboy.getSpeedMode().setByte(0xff4d, 1);
                var onStop = gameboy.getSpeedMode().getClass().getDeclaredMethod("onStop");
                onStop.setAccessible(true);
                onStop.invoke(gameboy.getSpeedMode());
                gameboy.getGpu().prepareForTick();
                gameboy.getGpu().setByte(0xff40, 0);
                gameboy.getGpu().setPerformanceScanlineEnabled(true);
            }
            Gpu expected = scalar.getGpu();
            Gpu actual = bulk.getGpu();
            assertEquals(2, bulk.getSpeedMode().getSpeedMode());
            assertEquals(54, actual.performanceNativeCgbDoubleSpeedLcdOffSpanLimit(54));
            long generation = actual.getTimingGeneration();
            for (int i = 0; i < 54; i++) {
                expected.tick();
            }
            actual.advancePerformanceNativeCgbDoubleSpeedLcdOffSpanTrusted(54);
            assertEquals(generation + 54, actual.getTimingGeneration());
            assertState("native x2 LCD-off", expected.captureState(), actual.captureState());
        }
    }

    @Test
    public void unobservedLcdcHistoryDrainPreservesCanonicalRingsAndRestores() throws Exception {
        for (boolean color : new boolean[]{false, true}) {
            for (boolean restore : new boolean[]{false, true}) {
                for (int age = 0; age <= 9; age++) {
                    for (int ticks : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 54}) {
                        Lcdc expected = new Lcdc();
                        Lcdc actual = new Lcdc();
                        for (Lcdc lcdc : new Lcdc[]{expected, actual}) {
                            lcdc.setGbc(color);
                            lcdc.set(0x95);
                            for (int i = 0; i < 3; i++) lcdc.tickConflicts();
                            lcdc.set(0xb1);
                            lcdc.triggerTileSelectGlitch();
                            // Settle the active strobe/mix first; retain the eight history cells.
                            for (int i = 0; i < 2 + age; i++) lcdc.tickConflicts();
                        }
                        if (restore) actual.restoreState(expected.captureState());
                        for (int i = 0; i < ticks; i++) expected.tickConflicts();
                        actual.advancePerformanceUnobservedHistorySpanTrusted(ticks);
                        assertState("LCDC history color=" + color + " restore=" + restore
                                        + " age=" + age + " ticks=" + ticks,
                                expected.captureState(), actual.captureState());
                        for (int delay = 0; delay < 8; delay++) {
                            assertEquals(expected.getOamSpriteHeight(delay), actual.getOamSpriteHeight(delay));
                            assertEquals(expected.isTileSelectGlitch(delay), actual.isTileSelectGlitch(delay));
                        }
                        // A later strobe observes the retained logical order, including bulk
                        // prefixes that wrapped the physical ring or filled every cell.
                        for (Lcdc lcdc : new Lcdc[]{expected, actual}) {
                            lcdc.set(0x94);
                            lcdc.triggerTileSelectGlitch();
                        }
                        for (int i = 0; i < 10; i++) {
                            expected.tickConflicts();
                            actual.tickConflicts();
                            assertState("LCDC history continuation " + i,
                                    expected.captureState(), actual.captureState());
                        }
                    }
                }
            }
        }
    }

    @Test
    public void nativeUnobservedHistoryPreservesOneDotBackgroundLatch() throws Exception {
        // CGB writes have no active mix, so a history span may start before the first
        // background-enable sample. Both otherwise dormant blob latches remain serialized.
        for (int ticks : new int[]{0, 1, 2, 7, 8, 9, 54}) {
            Lcdc expected = new Lcdc(true);
            Lcdc actual = new Lcdc(true);
            for (Lcdc lcdc : new Lcdc[]{expected, actual}) {
                lcdc.setGbc(true);
                lcdc.set(0x90);
            }
            for (int i = 0; i < ticks; i++) expected.tickConflicts();
            actual.advancePerformanceUnobservedHistorySpanTrusted(ticks);
            assertState("native background history " + ticks,
                    expected.captureState(), actual.captureState());
        }
    }

    @Test
    public void directLineAndHblankAdmitFreshLcdcHistoryWithoutSkippingItsCells()
            throws Exception {
        for (boolean color : new boolean[]{false, true}) {
            HardwareProfile profile = color ? HardwareProfileRegistry.CGB : HardwareProfileRegistry.DMG;
            for (int path = 0; path < 3; path++) {
                try (Gameboy scalar = session(profile, color); Gameboy bulk = session(profile, color)) {
                    Gpu expected = scalar.getGpu();
                    Gpu actual = bulk.getGpu();
                    expected.setPerformanceScanlineEnabled(true);
                    actual.setPerformanceScanlineEnabled(true);
                    while (actual.getLine() != 1 || actual.getTicksInLine() != 76) {
                        expected.tick(); actual.tick();
                    }
                    expected.setByteFromCpu(0xff40, 0x95);
                    actual.setByteFromCpu(0xff40, 0x95);
                    for (int i = 0; i < 4; i++) { expected.tick(); actual.tick(); }
                    assertTrue("fixture must already have composed its direct line",
                            actual.isPerformanceScanlineCursorActive());
                    Lcdc history = lcdcForTest(actual);
                    assertTrue("fixture must retain nonuniform mode-2 LCDC history",
                            !history.isPerformanceQuietSpanFixedPoint());
                    assertTrue("direct line should admit exact history draining",
                            actual.performanceScanlineQuietSpanLimit() >= 3);
                    advanceHistoryTestSpan(actual, 3, path, color, true);
                    for (int i = 0; i < 3; i++) expected.tick();
                    assertState("direct LCDC history path=" + path + " color=" + color,
                            expected.captureState(), actual.captureState());

                    // The retained direct cursor must reach its real predicted HBlank handoff
                    // before this loop. An on->on LCDC write leaves that composed line intact;
                    // issuing the writes while mode 3 is still live would consume their history
                    // while the cursor is finishing and falsely expect nonuniform history later.
                    long directLinesBeforeHblank = actual.getPerformanceScanlineLines();
                    int naturalHblankTicks = 0;
                    while (actual.getMode() != Mode.HBlank && naturalHblankTicks++ < 512) {
                        expected.tick();
                        actual.tick();
                    }
                    assertTrue("direct line failed to reach natural HBlank",
                            naturalHblankTicks < 512);
                    assertEquals("natural HBlank mode", expected.getMode(), actual.getMode());
                    assertTrue("direct cursor must publish its predicted HBlank handoff",
                            !actual.isPerformanceScanlineCursorActive());
                    assertEquals("direct line handoff count", directLinesBeforeHblank + 1,
                            actual.getPerformanceScanlineLines());
                    assertState("natural HBlank after direct LCDC history path=" + path
                                    + " color=" + color,
                            expected.captureState(), actual.captureState());

                    // Fresh writes now begin in real HBlank, after the direct line's output is
                    // empty. Each new history remains batchable through all three commit paths.
                    int hblankLine = actual.getLine();
                    for (int write = 0; write < 12; write++) {
                        int value = (write & 1) == 0 ? 0xb1 : 0x95;
                        expected.setByteFromCpu(0xff40, value);
                        actual.setByteFromCpu(0xff40, value);
                        int freshHistoryTicks = 0;
                        while ((actual.getMode() != Mode.HBlank
                                || actual.performanceQuietSpanLimit() < 3)
                                && freshHistoryTicks++ < 256) {
                            expected.tick(); actual.tick();
                        }
                        assertTrue("fresh HBlank history failed to settle within its line",
                                freshHistoryTicks < 256);
                        assertEquals("fresh HBlank write crossed a line", hblankLine,
                                actual.getLine());
                        assertEquals("fresh HBlank mode", Mode.HBlank, actual.getMode());
                        assertTrue("fresh HBlank history should be admitted before its fixed point",
                                !history.isPerformanceQuietSpanFixedPoint());
                        advanceHistoryTestSpan(actual, 3, path, color, false);
                        for (int i = 0; i < 3; i++) expected.tick();
                        assertState("HBlank LCDC history path=" + path + " write=" + write,
                                expected.captureState(), actual.captureState());
                    }
                }
            }
        }
    }

    private static Lcdc lcdcForTest(Gpu gpu) throws Exception {
        var field = Gpu.class.getDeclaredField("lcdc");
        field.setAccessible(true);
        return (Lcdc) field.get(gpu);
    }

    private static void advanceHistoryTestSpan(Gpu gpu, int ticks, int path,
                                               boolean color, boolean direct) {
        if (path == 0) {
            assertTrue(gpu.advancePerformanceQuietSpan(ticks));
        } else if (path == 1) {
            gpu.advancePerformanceQuietSpanTrusted(ticks, direct, false);
        } else if (color) {
            assertTrue(gpu.performanceEpochSpanLimit(ticks) >= ticks);
            gpu.advancePerformanceEpochQuietSpanTrusted(ticks, direct, false);
        } else {
            assertTrue(gpu.performancePhysicalDmgEpochSpanLimit(ticks) >= ticks);
            gpu.advancePhysicalDmgPerformanceEpochQuietSpanTrusted(ticks, direct, false);
        }
    }

    private static void configureWindow(Gpu gpu) {
        gpu.setByte(0xff40, 0xf3);
        gpu.setByte(0xff4a, 4);
        gpu.setByte(0xff4b, 7);
        for (int address = 0x8000; address < 0xa000; address++) {
            gpu.writeVideoRam0ForCore(address, (address * 37 ^ address >>> 3) & 0xff);
        }
    }

    private static Gameboy session(HardwareProfile profile, boolean nativeColor)
            throws Exception {
        byte[] image = new byte[0x8000];
        image[0x100] = (byte) 0xc3;
        image[0x101] = 0;
        image[0x102] = 1;
        image[0x143] = (byte) (nativeColor ? 0x80 : 0);
        return new Gameboy.GameboyConfiguration(new Rom(image))
                .setHardwareProfile(profile)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build();
    }

    private static void assertState(String path, Object expected, Object actual) throws Exception {
        if (expected == null || actual == null) {
            assertEquals(path, expected, actual);
        } else if (expected.getClass().isArray()) {
            assertEquals(path + " length", Array.getLength(expected), Array.getLength(actual));
            for (int i = 0; i < Array.getLength(expected); i++) {
                assertState(path + '[' + i + ']', Array.get(expected, i), Array.get(actual, i));
            }
        } else if (expected instanceof List<?> list) {
            List<?> other = (List<?>) actual;
            assertEquals(path + " size", list.size(), other.size());
            for (int i = 0; i < list.size(); i++) {
                assertState(path + '[' + i + ']', list.get(i), other.get(i));
            }
        } else if (expected.getClass().isRecord()) {
            assertEquals(path + " type", expected.getClass(), actual.getClass());
            for (RecordComponent component : expected.getClass().getRecordComponents()) {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                assertState(path + '.' + component.getName(),
                        accessor.invoke(expected), accessor.invoke(actual));
            }
        } else {
            assertEquals(path, expected, actual);
        }
    }
}
