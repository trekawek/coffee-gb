package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.gpu.Gpu;
import eu.rekawek.coffeegb.core.gpu.Mode;
import eu.rekawek.coffeegb.core.gpu.StatRegister;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.performance.PerformanceDiagnostics;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static org.junit.Assert.*;

/** Composition tests for the already-derived STAT checkpoint proof and strict detailed CPU bus. */
public class GameboyDetailedStatCheckpointTest {
    private static final HardwareProfile[] PROFILES = {
            HardwareProfileRegistry.CGB, HardwareProfileRegistry.CGB0};
    private static final Method DETAILED;
    static {
        try {
            DETAILED = Gameboy.class.getDeclaredMethod(
                    "tryPerformanceNativeCgbDetailedPpuEpoch", long.class);
            DETAILED.setAccessible(true);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    @Test
    public void ownedMode2CheckpointAggregationPreservesEveryRestoredPrefix() throws Exception {
        for (HardwareProfile profile : PROFILES) {
            for (int mask : new int[]{0, 0x40}) {
                for (int dot : new int[]{0, 1, 6, 12}) {
                    try (Gameboy scalar = session(profile); Gameboy bulk = session(profile)) {
                        prepare(scalar, dot, true, mask, 72, false);
                        prepare(bulk, dot, true, mask, 72, false);
                        List<ComponentState<Gameboy>> ss = captures(scalar, 54);
                        List<ComponentState<Gameboy>> bs = captures(bulk, 54);
                        for (int prefix = 1; prefix <= 54; prefix++) {
                            restoreBeforeEntry(scalar, bulk, ss.get(prefix - 1), bs.get(prefix - 1));
                            String label = profile.id() + " mode2 dot=" + dot
                                    + " mask=" + mask + " prefix=" + prefix;
                            Gpu gpu = bulk.getGpu();
                            gpu.setPerformanceScanlineEnabled(true);
                            assertEquals(label, Mode.OamSearch, gpu.getMode());
                            assertEquals(label + " previous owner rejected STAT", 0,
                                    stat(bulk).performanceSettledHaltSpanLimit(54));
                            assertEquals(label + " existing aggregate proof", 54,
                                    stat(bulk).performanceNativeCgbCheckpointAggregateSpanLimit(54));
                            assertEquals(label + " quiet owned PPU proof", 54,
                                    gpu.performanceNativeCgbOamReplayQuietSpanLimit(54));
                            PerformanceDiagnostics d = new PerformanceDiagnostics(70_224);
                            bulk.setPerformanceDiagnostics(d);
                            int elapsed = compareDetailedPrefix(label, scalar, bulk, prefix);
                            if (prefix >= 8) assertTrue(label + " no newly admitted epoch", elapsed > 0);
                            assertEquals(label + " DMA stride count", (long) elapsed,
                                    (long) d.snapshot().subsystemTicks().get(
                                            PerformanceDiagnostics.Subsystem.DMA_BATCHED_COPY));
                            assertEquals(label + " exact evaluator unexpectedly replayed", 0L,
                                    (long) d.snapshot().subsystemTicks().get(
                                            PerformanceDiagnostics.Subsystem.STAT_REPLAY));
                            assertSameProduction(label + " ownership release", scalar, bulk, 350);
                            assertFalse(label, dma(bulk).isTransferInProgress());
                        }
                    }
                }
            }
        }
    }

    @Test
    public void ownedHblankCheckpointsRetainCanonicalReplayAndTailLatchAfterRestore() throws Exception {
        for (HardwareProfile profile : PROFILES) {
            for (int window : new int[]{-1, -2, 447}) {
                try (Gameboy scalar = session(profile); Gameboy bulk = session(profile)) {
                    int dot = prepare(scalar, window, true, 0x40, 72, false);
                    assertEquals(dot, prepare(bulk, window, true, 0x40, 72, false));
                    List<ComponentState<Gameboy>> ss = captures(scalar, 54);
                    List<ComponentState<Gameboy>> bs = captures(bulk, 54);
                    int maxPrefix = window == 447 ? 8 : 16;
                    for (int prefix = 1; prefix <= maxPrefix; prefix++) {
                        restoreBeforeEntry(scalar, bulk, ss.get(prefix - 1), bs.get(prefix - 1));
                        String label = profile.id() + " owned HBlank dot=" + dot + " prefix=" + prefix;
                        bulk.getGpu().setPerformanceScanlineEnabled(true);
                        assertEquals(label, Mode.HBlank, bulk.getGpu().getMode());
                        assertEquals(label + " old STAT veto", 0,
                                stat(bulk).performanceSettledHaltSpanLimit(maxPrefix));
                        assertEquals(label + " broad replay proof", maxPrefix,
                                stat(bulk).performanceNativeCgbCheckpointReplaySpanLimit(maxPrefix));
                        if (window < 0) assertEquals(label + " mid-line has no aggregate", 0,
                                stat(bulk).performanceNativeCgbCheckpointAggregateSpanLimit(maxPrefix));
                        PerformanceDiagnostics d = new PerformanceDiagnostics(70_224);
                        bulk.setPerformanceDiagnostics(d);
                        int elapsed = compareDetailedPrefix(label, scalar, bulk, prefix);
                        if (prefix >= 8) assertTrue(label + " no checkpoint batch", elapsed > 0);
                        assertEquals(label + " checkpoint dots must retain actual replay", (long) elapsed,
                                (long) d.snapshot().subsystemTicks().get(
                                        PerformanceDiagnostics.Subsystem.STAT_REPLAY));
                        assertEquals(label + " DMA must preserve per-dot ordering", (long) elapsed,
                                (long) d.snapshot().subsystemTicks().get(
                                        PerformanceDiagnostics.Subsystem.DMA_REPLAY));
                        // Cross the registered-LY tail latch, next line and actual DMA release.
                        assertSameProduction(label + " line and DMA continuation", scalar, bulk, 600);
                        assertFalse(dma(bulk).isTransferInProgress());
                    }
                }
            }
        }
    }

    @Test
    public void unownedTailReplayStopsBeforeLineRolloverAcrossEveryPrefix() throws Exception {
        for (HardwareProfile profile : PROFILES) {
            try (Gameboy scalar = session(profile); Gameboy bulk = session(profile)) {
                prepare(scalar, 447, false, 0x40, 72, false);
                prepare(bulk, 447, false, 0x40, 72, false);
                List<ComponentState<Gameboy>> ss = captures(scalar, 54);
                List<ComponentState<Gameboy>> bs = captures(bulk, 54);
                for (int prefix = 1; prefix <= 8; prefix++) {
                    restoreBeforeEntry(scalar, bulk, ss.get(prefix - 1), bs.get(prefix - 1));
                    String label = profile.id() + " unowned tail prefix=" + prefix;
                    assertEquals(0, stat(bulk).performanceSettledHaltSpanLimit(54));
                    assertEquals(8, stat(bulk).performanceNativeCgbCheckpointReplaySpanLimit(54));
                    int elapsed = compareDetailedPrefix(label, scalar, bulk, prefix);
                    if (prefix == 8) assertEquals(label + " tail checkpoint batch", 8, elapsed);
                    assertEquals(10, bulk.getGpu().getLine());
                    assertEquals(447 + prefix, bulk.getGpu().getTicksInLine());
                    assertSameProduction(label + " rollover", scalar, bulk, 70);
                }
            }
        }
    }

    @Test
    public void realStickyMode2RequestSurvivesCheckpointRestoreAndCpuLifecycleRecovery() throws Exception {
        for (HardwareProfile profile : PROFILES) {
            for (int dot : new int[]{0, 1, 447}) {
                try (Gameboy scalar = session(profile); Gameboy bulk = session(profile)) {
                    prepare(scalar, dot, true, 0, 72, true);
                    prepare(bulk, dot, true, 0, 72, true);
                    List<ComponentState<Gameboy>> ss = captures(scalar, 54);
                    List<ComponentState<Gameboy>> bs = captures(bulk, 54);
                    for (int prefix : new int[]{1, 7, 8}) {
                        restoreBeforeEntry(scalar, bulk, ss.get(prefix - 1), bs.get(prefix - 1));
                        String label = profile.id() + " sticky IF dot=" + dot + " prefix=" + prefix;
                        assertEquals(label + " actual enabled STAT IF", 2,
                                bulk.getAddressSpace().getByte(0xff0f) & 2);
                        assertTrue(label + " actual durable phased request",
                                interrupts(bulk).isPhasedMode2InterruptRequested());
                        assertEquals(label + " old STAT veto", 0,
                                stat(bulk).performanceSettledHaltSpanLimit(8));
                        assertEquals(label + " sticky IF still has no new edge", 8,
                                stat(bulk).performanceNativeCgbCheckpointReplaySpanLimit(8));
                        int elapsed = compareDetailedPrefix(label, scalar, bulk, prefix);
                        if (prefix == 8) assertTrue(label + " sticky checkpoint batch", elapsed > 0);
                        assertSameProduction(label + " finish DMA", scalar, bulk, 600);
                        scalar.getAddressSpace().setByte(0xff91, 1);
                        bulk.getAddressSpace().setByte(0xff91, 1);
                        // The exact checkpoint prefix and DMA-release continuation above still
                        // use the scalar oracle. CPU-written STAT recovery uses the original
                        // PERFORMANCE packet owner below: its established deferred write journal
                        // can retain a one-dot historical timestamp versus forced scalar ticks.
                        // Keep that journal on both machines while disabling only this stage's
                        // new detailed checkpoint admission on the reference. No state is reset,
                        // normalized or excluded from the comparisons.
                        // The authored program executes EI, the real STAT handler, IF clear,
                        // a later mode-2 HALT wake, and returns to the strict HRAM data loop.
                        for (int ticks : new int[]{1, 2, 7, 13, 31, 127, 509, 1021}) {
                            assertSameOriginalPackets(label + " lifecycle ticks=" + ticks,
                                    scalar, bulk, ticks);
                        }
                        assertTrue(label + " EI must acknowledge the old STAT request",
                                bulk.getAddressSpace().getByte(0xff92) > 0);
                        assertEquals(label + " HALT must wake into useful work", 1,
                                bulk.getAddressSpace().getByte(0xff93));
                        assertEquals(label + " CPU must clear IF after recovery", 0,
                                bulk.getAddressSpace().getByte(0xff0f) & 2);
                    }
                }
            }
        }
    }

    @Test
    public void enabledSourcesEqualityAndPendingCapturesStayOnTheScalarOwner() throws Exception {
        int[][] rejected = {{8, 72}, {0x10, 72}, {0x20, 72}, {0x78, 72}, {0x40, 10}, {0x40, 11}};
        for (HardwareProfile profile : PROFILES) {
            for (int[] setting : rejected) {
                try (Gameboy scalar = session(profile); Gameboy bulk = session(profile)) {
                    prepare(scalar, 6, true, setting[0], setting[1], false);
                    prepare(bulk, 6, true, setting[0], setting[1], false);
                    scalar.tick(); bulk.tick();
                    String label = profile.id() + " negative mask=" + setting[0] + " LYC=" + setting[1];
                    assertEquals(label, 0,
                            stat(bulk).performanceNativeCgbCheckpointReplaySpanLimit(54));
                    assertEquals(label + " detailed owner must reject", 0,
                            compareDetailedPrefix(label, scalar, bulk, 54));
                }
            }
            try (Gameboy scalar = session(profile); Gameboy bulk = session(profile)) {
                prepare(scalar, 6, true, 0, 72, false);
                prepare(bulk, 6, true, 0, 72, false);
                // A real native register write schedules the existing mode capture copies.
                scalar.getAddressSpace().setByte(0xff41, 0x40);
                bulk.getAddressSpace().setByte(0xff41, 0x40);
                scalar.tick(); bulk.tick();
                assertTrue("fixture must retain a scheduled native register capture",
                        stat(bulk).hasPendingModeRegisterCapture());
                assertEquals(0, stat(bulk).performanceNativeCgbCheckpointReplaySpanLimit(54));
                assertEquals(0, compareDetailedPrefix(profile.id() + " pending capture",
                        scalar, bulk, 54));
            }
        }
    }

    /** Returns target dot; leaves the machine exactly one canonical dot before it. */
    private static int prepare(Gameboy gb, int requestedDot, boolean owned,
            int mask, int lyc, boolean sticky) throws Exception {
        gb.setPerformanceBatchingEnabled(false);
        var bus = gb.getAddressSpace();
        bus.setByte(0xff26, 0);
        bus.setByte(0xff45, lyc);
        bus.setByte(0xff41, sticky ? 0x20 : mask);
        bus.setByte(0xffff, sticky ? 2 : 0);
        bus.setByte(0xff0f, 0);
        for (int i = 0; i < 160; i++) bus.setByte(0xc000 + i, (i * 37 + 0x12) & 255);
        gb.getSpeedMode().setByte(0xff4d, 1);
        Method onStop = gb.getSpeedMode().getClass().getDeclaredMethod("onStop");
        onStop.setAccessible(true);
        assertEquals(true, onStop.invoke(gb.getSpeedMode()));
        advanceTo(gb, 3, 40);
        if (sticky) {
            assertEquals("real mode-2 request fixture", 2, bus.getByte(0xff0f) & 2);
            assertTrue("real phased request fixture", interrupts(gb).isPhasedMode2InterruptRequested());
            bus.setByte(0xff41, mask);
        }
        if (requestedDot >= 0 && requestedDot <= 12) {
            advanceTo(gb, 9, 400);
            if (owned) bus.setByte(0xff46, 0xc0);
        } else {
            advanceTo(gb, 10, requestedDot < 0 ? 180 : 400);
            if (owned) bus.setByte(0xff46, 0xc0);
        }
        int target = requestedDot;
        if (target < 0) {
            for (int guard = 0; gb.getGpu().getMode() != Mode.HBlank; guard++) {
                assertTrue("HBlank fixture bound", guard < 200);
                gb.tick();
            }
            target = mode0InterruptTick(gb.getGpu()) + (requestedDot == -2 ? 2 : 0);
            assertTrue("mode-0 edge must still lie ahead", target > gb.getGpu().getTicksInLine());
        }
        if (target == 0) advanceTo(gb, 9, 455);
        else advanceTo(gb, 10, target - 1);
        return target;
    }

    private static void restoreBeforeEntry(Gameboy scalar, Gameboy bulk,
            ComponentState<Gameboy> ss, ComponentState<Gameboy> bs) {
        scalar.restoreStateSilently(ss);
        bulk.restoreStateSilently(bs);
        // Restore invalidates derived STAT evaluation; settle the immediately preceding dot.
        scalar.tick(); bulk.tick();
        scalar.setPerformanceBatchingEnabled(false);
        bulk.setPerformanceBatchingEnabled(true);
    }

    private static int compareDetailedPrefix(String label, Gameboy scalar, Gameboy bulk,
            int ticks) throws Exception {
        assertEquals(label + " scalar frame count", 0, scalar.runTicks(ticks));
        bulk.getGpu().setPerformanceScanlineEnabled(true);
        int elapsed;
        try {
            elapsed = (int) DETAILED.invoke(bulk, (long) ticks);
            assertTrue(label + " bounded packet", elapsed >= 0 && elapsed <= ticks);
            for (int i = elapsed; i < ticks; i++) bulk.tick();
        } finally {
            bulk.getSound().materializePendingPerformanceTicks();
            bulk.getGpu().setPerformanceScanlineEnabled(false);
        }
        assertStateEquals(label, scalar.captureStateWithoutTimeSource(), bulk.captureStateWithoutTimeSource());
        return elapsed;
    }

    private static void assertSameProduction(String label, Gameboy scalar, Gameboy bulk, int ticks) {
        scalar.setPerformanceBatchingEnabled(false);
        bulk.setPerformanceBatchingEnabled(true);
        assertEquals(label, scalar.runTicks(ticks), bulk.runTicks(ticks));
        assertStateEquals(label, scalar.captureStateWithoutTimeSource(), bulk.captureStateWithoutTimeSource());
    }

    private static void assertSameOriginalPackets(String label, Gameboy reference,
            Gameboy candidate, int ticks) throws Exception {
        reference.setPerformanceBatchingEnabled(true);
        candidate.setPerformanceBatchingEnabled(true);
        assertEquals(label, runOriginalNativePackets(reference, ticks), candidate.runTicks(ticks));
        assertStateEquals(label, reference.captureStateWithoutTimeSource(),
                candidate.captureStateWithoutTimeSource());
    }

    /**
     * Test-local original runNativeCgbPerformanceTicks contract. Every existing packet,
     * scalar STAT lease and HALT method is reused unchanged; only the staged detailed
     * checkpoint extension is excluded by its former settled-STAT precondition.
     */
    private static int runOriginalNativePackets(Gameboy gb, int ticks) throws Exception {
        int frames = 0;
        int remaining = ticks;
        Field scalarOwner = Gameboy.class.getDeclaredField("nativeCgbScalarOwner");
        scalarOwner.setAccessible(true);
        Field marker = Gameboy.class.getDeclaredField("PERFORMANCE_EPOCH_NEEDS_PPU_REPLAY");
        marker.setAccessible(true);
        int replayMarker = marker.getInt(null);
        gb.getGpu().setPerformanceScanlineEnabled(true);
        try {
            while (remaining > 0) {
                assertTrue("fixture must retain native x2 topology",
                        invokeBoolean(gb, "isNativeCgbPerformanceEpochTopology"));
                int committed = invokePacket(gb, "tryPerformanceNativeCgbDmaOwnedDataSpan", remaining);
                if (committed > 0) { remaining -= committed; continue; }
                committed = invokePacket(gb, "tryPerformanceEpoch", remaining);
                if (committed > 0) { remaining -= committed; continue; }
                if (committed == replayMarker || dma(gb).isTransferInProgress()) {
                    // The original detailed method intersects the GPU/DMA span with this
                    // same settled horizon and cannot proceed when it is zero. A smaller
                    // GPU/DMA horizon cannot make a rejected one-dot STAT prefix positive.
                    committed = stat(gb).performanceSettledHaltSpanLimit(
                            Math.min(remaining, Cpu.PERFORMANCE_EPOCH_MAX_TICKS)) > 0
                            ? (int) DETAILED.invoke(gb, (long) remaining) : 0;
                    if (committed > 0) { remaining -= committed; continue; }
                }
                if (committed < 0) {
                    int deadline = -committed;
                    do {
                        // This fixture has no debug/reset/STOP mutation. Match the original
                        // native scalar owner's STAT prologue and per-dot clock ordering.
                        scalarOwner.setBoolean(gb, true);
                        if (gb.tick()) frames++;
                        remaining--;
                        deadline--;
                    } while (deadline > 0 && remaining > 0
                            && invokeBoolean(gb, "canContinueNativeCgbNegativeStatLease"));
                    continue;
                }
                if (gb.getCpu().getState() == Cpu.State.HALTED) {
                    committed = invokePacket(gb, "tryPerformanceSettledNativeCgbHaltSpan", remaining);
                    if (committed > 0) { remaining -= committed; continue; }
                }
                scalarOwner.setBoolean(gb, true);
                if (gb.tick()) frames++;
                remaining--;
            }
        } finally {
            scalarOwner.setBoolean(gb, false);
            gb.getSound().materializePendingPerformanceTicks();
            gb.getGpu().setPerformanceScanlineEnabled(false);
        }
        return frames;
    }

    private static int invokePacket(Gameboy gb, String name, int remaining) throws Exception {
        Method method = Gameboy.class.getDeclaredMethod(name, long.class);
        method.setAccessible(true);
        return (int) method.invoke(gb, (long) remaining);
    }

    private static boolean invokeBoolean(Gameboy gb, String name) throws Exception {
        Method method = Gameboy.class.getDeclaredMethod(name);
        method.setAccessible(true);
        return (boolean) method.invoke(gb);
    }

    private static void advanceTo(Gameboy gb, int line, int dot) {
        for (int budget = 2 * 70_224; budget > 0; budget--) {
            if (gb.getGpu().getLine() == line && gb.getGpu().getTicksInLine() == dot) return;
            gb.tick();
        }
        fail("fixture failed to reach line=" + line + " dot=" + dot);
    }

    private static Gameboy session(HardwareProfile profile) throws Exception {
        byte[] image = new byte[0x8000];
        image[0x100] = (byte) 0xc3; image[0x101] = 0x50; image[0x102] = 1;
        image[0x143] = (byte) 0x80;
        int[] handler = {0xf5, 0xf0, 0x92, 0x3c, 0xe0, 0x92, 0xf1, 0xd9};
        for (int i = 0; i < handler.length; i++) image[0x48 + i] = (byte) handler[i];
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        emit(p, 0xf3, 0x31, 0xfe, 0xff); // DI, SP=FFFE.
        int loop = 0x150 + p.size();
        emit(p, 0xf0, 0x90, 0x3c, 0xe0, 0x90, 0xf0, 0x91, 0xb7, 0xca, loop & 255, loop >> 8);
        emit(p, 0xaf, 0xe0, 0x91, 0xfb, 0x00, 0x00, 0xf3); // command clear; EI/dispatch; DI.
        emit(p, 0x3e, 0x20, 0xe0, 0x41, 0xaf, 0xe0, 0x0f, 0x76, 0x00, 0xf3);
        emit(p, 0x3e, 1, 0xe0, 0x93, 0xaf, 0xe0, 0x41, 0xe0, 0x0f);
        emit(p, 0xc3, loop & 255, loop >> 8);
        byte[] program = p.toByteArray();
        System.arraycopy(program, 0, image, 0x150, program.length);
        return new Gameboy.GameboyConfiguration(new Rom(image)).setHardwareProfile(profile)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP).setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false).setRtcTimeSource(() -> 0L).build();
    }

    private static int mode0InterruptTick(Gpu gpu) throws Exception {
        Method method = Gpu.class.getDeclaredMethod("getMode0InterruptTick");
        method.setAccessible(true);
        return (int) method.invoke(gpu);
    }

    private static List<ComponentState<Gameboy>> captures(Gameboy gb, int count) {
        // Some restored array members become live: give every matrix row its own capture.
        List<ComponentState<Gameboy>> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(gb.captureStateWithoutTimeSource());
        return result;
    }

    private static void emit(ByteArrayOutputStream out, int... bytes) {
        for (int b : bytes) out.write(b);
    }
    private static Object field(Gameboy gb, String name) throws Exception {
        Field field = Gameboy.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(gb);
    }
    private static StatRegister stat(Gameboy gb) throws Exception { return (StatRegister) field(gb, "statRegister"); }
    private static Dma dma(Gameboy gb) throws Exception { return (Dma) field(gb, "dma"); }
    private static InterruptManager interrupts(Gameboy gb) throws Exception {
        return (InterruptManager) field(gb, "interruptManager");
    }
}
