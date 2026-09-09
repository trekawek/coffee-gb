package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.gpu.phase.OamSearch;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Focused proof for WRAM OAM DMA while an already-composed native CGB x2 line is active. */
public final class GameboyDirectCursorOamReplayTest {
    private static final HardwareProfile[] PROFILES = {
            HardwareProfileRegistry.CGB, HardwareProfileRegistry.CGB0
    };

    @Test
    public void realCpuFf46AfterDirectArmMatchesScalarForEveryPrefixAndRestores() throws Exception {
        for (HardwareProfile profile : PROFILES) {
            for (VisualCase visual : VisualCase.values()) {
                try (Gameboy scalar = session(profile, visual);
                        Gameboy candidate = session(profile, visual)) {
                    armDirectAndIssueRealFf46(scalar, visual);
                    armDirectAndIssueRealFf46(candidate, visual);
                    // The scalar ticks above make the direct-arm/FF46 seam deterministic; the
                    // owner under proof is restored to the normal PERFORMANCE scheduler mode.
                    candidate.setPerformanceBatchingEnabled(true);
                    assertEquals(profile.id() + " " + visual + " setup state",
                            scalar.getGpu().getTicksInLine(), candidate.getGpu().getTicksInLine());
                    assertTrue(profile.id() + " " + visual + " direct cursor",
                            candidate.getGpu().isPerformanceScanlineCursorActive());
                    assertTrue(profile.id() + " " + visual + " WRAM DMA",
                            dma(candidate).isTransferInProgress());
                    assertEquals(0xc000, ((Dma.DmaState) dma(candidate).captureState()).from());
                    assertTrue(profile.id() + " " + visual + " reader initialized",
                            oamReaderInitialized(candidate));
                    assertEquals(profile.id() + " " + visual + " active direct proof", 54,
                            candidate.getGpu().performanceNativeCgbOamReplayQuietSpanLimit(54));

                    ComponentState<Gameboy> scalarEntry =
                            scalar.captureStateWithoutTimeSource();
                    ComponentState<Gameboy> candidateEntry =
                            candidate.captureStateWithoutTimeSource();
                    for (int requested = 1; requested <= 54; requested++) {
                        String label = profile.id() + " " + visual + " prefix=" + requested;
                        scalar.restoreStateSilently(scalarEntry);
                        candidate.restoreStateSilently(candidateEntry);
                        // Restore reinitializes LCDC's transient nine-dot history drain. Settle
                        // that existing fixed-point fence on both machines before selecting the
                        // detailed owner; this is the same prerequisite as production admission.
                        settleRestoredFixedPoint(scalar, candidate);
                        assertTrue(label + " direct cursor after restore",
                                candidate.getGpu().isPerformanceScanlineCursorActive());
                        assertEquals(label + " admitted prefix", requested,
                                candidate.getGpu().performanceNativeCgbOamReplayQuietSpanLimit(requested));

                        int elapsed;
                        if (requested < 8) {
                            // The existing CPU lease has an eight-dot minimum. Keep the
                            // admission proof for every partial budget, while the short cases
                            // intentionally take the exact scalar owner on both machines.
                            elapsed = 0;
                            for (int i = 0; i < requested; i++) {
                                scalar.tick();
                                candidate.tick();
                            }
                        } else {
                            elapsed = invokeDetailedOwner(candidate, requested);
                            assertEquals(label + " detailed owner commits full prefix", requested,
                                    elapsed);
                            for (int i = 0; i < requested; i++) {
                                scalar.tick();
                            }
                        }
                        assertStateEquals(label + " full canonical state",
                                scalar.captureStateWithoutTimeSource(),
                                candidate.captureStateWithoutTimeSource());
                        assertEquals(label + " STAT",
                                scalar.getAddressSpace().getByte(0xff41),
                                candidate.getAddressSpace().getByte(0xff41));
                        assertEquals(label + " IF",
                                scalar.getAddressSpace().getByte(0xff0f),
                                candidate.getAddressSpace().getByte(0xff0f));
                    }

                    // Continue one restored maximum-prefix state through the direct handoff,
                    // mode-0 STAT edge, and real OAM ownership release.
                    scalar.restoreStateSilently(scalarEntry);
                    candidate.restoreStateSilently(candidateEntry);
                    settleRestoredFixedPoint(scalar, candidate);
                    int elapsed = invokeDetailedOwner(candidate, 54);
                    assertEquals(profile.id() + " " + visual + " release prefix", 54, elapsed);
                    for (int i = 0; i < 54; i++) scalar.tick();
                    for (int i = 0; i < 420; i++) {
                        scalar.tick();
                        candidate.tick();
                        assertStateEquals(profile.id() + " " + visual + " release tick=" + i,
                                scalar.captureStateWithoutTimeSource(),
                                candidate.captureStateWithoutTimeSource());
                        if (!dma(candidate).isTransferInProgress()
                                && candidate.getGpu().getMode() == Mode.HBlank
                                && candidate.getGpu().getTicksInLine() > 440) {
                            break;
                        }
                    }
                    assertFalse(profile.id() + " " + visual + " DMA release",
                            dma(candidate).isTransferInProgress());
                    assertEquals(profile.id() + " " + visual + " release state",
                            scalar.getGpu().getMode(), candidate.getGpu().getMode());
                }
            }
        }
    }

    @Test
    public void activeDirectCursorRejectsReaderEdgeAndUnsafeSourceGuards() throws Exception {
        for (HardwareProfile profile : PROFILES) {
            try (Gameboy edge = session(profile, VisualCase.OBJECTS)) {
                armDirect(edge, VisualCase.OBJECTS);
                issueRealFf46(edge, 0xc0);
                boolean acquired = false;
                for (int i = 0; i < 200 && !acquired; i++) {
                    edge.tick();
                    acquired = dma(edge).isTransferInProgress()
                            && dma(edge).hasPpuOamOwnershipTransitionThisTick()
                            && dma(edge).ownsOamForPpu();
                }
                assertTrue(profile.id() + " acquisition transition", acquired);
                assertEquals(profile.id() + " acquisition edge", 0,
                        edge.getGpu().performanceNativeCgbOamReplayQuietSpanLimit(54));

                boolean released = false;
                for (int i = 0; i < 400 && !released; i++) {
                    edge.tick();
                    released = dma(edge).hasPpuOamOwnershipTransitionThisTick()
                            && !dma(edge).ownsOamForPpu();
                }
                assertTrue(profile.id() + " release transition", released);
                assertEquals(profile.id() + " release edge", 0,
                        edge.getGpu().performanceNativeCgbOamReplayQuietSpanLimit(54));
            }

            try (Gameboy handoff = session(profile, VisualCase.BACKGROUND)) {
                armDirectAndIssueRealFf46(handoff, VisualCase.BACKGROUND);
                int end = performanceScanlineEndTick(handoff);
                while (handoff.getGpu().getTicksInLine() + 1 < end) {
                    handoff.tick();
                }
                assertTrue(profile.id() + " direct cursor before handoff",
                        handoff.getGpu().isPerformanceScanlineCursorActive());
                assertEquals(profile.id() + " handoff reserve dot", 0,
                        handoff.getGpu().performanceNativeCgbOamReplayQuietSpanLimit(54));
                handoff.tick();
                assertFalse(profile.id() + " direct cursor handoff",
                        handoff.getGpu().isPerformanceScanlineCursorActive());
                assertEquals(profile.id() + " HBlank handoff", Mode.HBlank,
                        handoff.getGpu().getMode());
            }

            try (Gameboy reader = session(profile, VisualCase.WINDOW)) {
                armDirectAndIssueRealFf46(reader, VisualCase.WINDOW);
                while (!dma(reader).isTransferInProgress()
                        || ((Dma.DmaState) dma(reader).captureState()).transferClocks() < 10) {
                    reader.tick();
                }
                setOamReaderInitialized(reader, false);
                assertEquals(profile.id() + " uninitialized reader", 0,
                        reader.getGpu().performanceNativeCgbOamReplayQuietSpanLimit(54));
            }

            try (Gameboy source = session(profile, VisualCase.BACKGROUND)) {
                armDirectAndIssueRealFf46(source, VisualCase.BACKGROUND, 0x80);
                while (!dma(source).isTransferInProgress()
                        || ((Dma.DmaState) dma(source).captureState()).transferClocks() < 10) {
                    source.tick();
                }
                assertEquals(profile.id() + " non-WRAM source", 0,
                        source.getGpu().performanceNativeCgbOamReplayQuietSpanLimit(54));
            }
        }
    }

    private static void armDirectAndIssueRealFf46(Gameboy gameboy, VisualCase visual)
            throws Exception {
        armDirectAndIssueRealFf46(gameboy, visual, 0xc0);
    }

    private static void armDirectAndIssueRealFf46(
            Gameboy gameboy, VisualCase visual, int sourcePage) throws Exception {
        armDirect(gameboy, visual);
        issueRealFf46(gameboy, sourcePage);
        int transferGuard = 0;
        while ((!dma(gameboy).isTransferInProgress()
                || ((Dma.DmaState) dma(gameboy).captureState()).transferClocks() < 10)
                && transferGuard++ < 200) {
            gameboy.tick();
        }
        assertTrue("real FF46 did not start WRAM DMA " + visual,
                dma(gameboy).isTransferInProgress());
        assertTrue("real FF46 did not reach owned interior " + visual,
                ((Dma.DmaState) dma(gameboy).captureState()).transferClocks() >= 10);
        assertTrue("FF46 must preserve an already active direct cursor " + visual,
                gameboy.getGpu().isPerformanceScanlineCursorActive());
    }

    private static void armDirect(Gameboy gameboy, VisualCase visual) throws Exception {
        gameboy.setPerformanceBatchingEnabled(false);
        gameboy.getGpu().setPerformanceScanlineEnabled(true);
        enableDoubleSpeed(gameboy);
        int guard = 0;
        while (!gameboy.getGpu().isPerformanceScanlineCursorActive() && guard++ < 2 * 70_224) {
            gameboy.tick();
        }
        assertTrue("direct cursor arm " + visual, guard < 2 * 70_224);
        assertTrue("mode-3 direct arm " + visual,
                gameboy.getGpu().getTicksInLine() >= 80);
    }

    private static void issueRealFf46(Gameboy gameboy, int sourcePage) {
        int[] program = {0x3e, sourcePage, 0xe0, 0x46, 0x18, 0xfe};
        for (int i = 0; i < program.length; i++) {
            gameboy.getAddressSpace().setByte(0xff80 + i, program[i]);
        }
        gameboy.getCpu().getRegisters().setPC(0xff80);
    }

    private static Gameboy session(HardwareProfile profile, VisualCase visual) throws Exception {
        byte[] image = new byte[0x8000];
        image[0x100] = (byte) 0xc3;
        image[0x101] = 0;
        image[0x102] = 1;
        image[0x143] = (byte) 0x80;
        Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(image))
                .setHardwareProfile(profile)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setRtcTimeSource(() -> 0L)
                .setSupportBatterySave(false)
                .build();
        prepareVisualFixture(gameboy, visual);
        return gameboy;
    }

    private static void prepareVisualFixture(Gameboy gameboy, VisualCase visual) {
        Gpu gpu = gameboy.getGpu();
        gpu.setByte(0xff40, 0);
        gpu.setByte(0xff41, 0x08); // HBlank STAT edge remains observable through the proof.
        gpu.setByte(0xff45, 0xff);
        for (int row = 0; row < 8; row++) {
            gpu.setByte(0x8020 + row * 2, 0x18 ^ row * 3);
            gpu.setByte(0x8021 + row * 2, 0x81 ^ row * 5);
        }
        for (int i = 0; i < 0x400; i++) {
            gpu.setByte(0x9800 + i, (i * 3 + 2) & 0xff);
            gpu.setByte(0x9c00 + i, (i * 5 + 1) & 0xff);
        }
        if (visual == VisualCase.OBJECTS) {
            for (int i = 0; i < 4; i++) {
                int address = 0xfe00 + i * 4;
                gpu.setByte(address, 16);
                gpu.setByte(address + 1, 8 + i * 24);
                gpu.setByte(address + 2, 1 + i);
                gpu.setByte(address + 3, i & 3);
            }
            gpu.setByte(0xff40, 0x93);
        } else if (visual == VisualCase.WINDOW) {
            gpu.setByte(0xff4a, 0);
            gpu.setByte(0xff4b, 7);
            gpu.setByte(0xff40, 0xb1);
        } else {
            gpu.setByte(0xff40, 0x91);
        }
    }

    private static void enableDoubleSpeed(Gameboy gameboy) throws Exception {
        gameboy.getSpeedMode().setByte(0xff4d, 1);
        Method onStop = gameboy.getSpeedMode().getClass().getDeclaredMethod("onStop");
        onStop.setAccessible(true);
        assertTrue((boolean) onStop.invoke(gameboy.getSpeedMode()));
        assertEquals(2, gameboy.getSpeedMode().getSpeedMode());
    }

    private static void settleRestoredFixedPoint(Gameboy scalar, Gameboy candidate) {
        for (int i = 0; i < 9; i++) {
            scalar.tick();
            candidate.tick();
        }
        scalar.getGpu().setPerformanceScanlineEnabled(true);
        candidate.getGpu().setPerformanceScanlineEnabled(true);
    }

    private static int invokeDetailedOwner(Gameboy gameboy, int requested) throws Exception {
        Method method = Gameboy.class.getDeclaredMethod(
                "tryPerformanceNativeCgbDetailedPpuEpoch", long.class);
        method.setAccessible(true);
        return (int) method.invoke(gameboy, (long) requested);
    }

    private static Dma dma(Gameboy gameboy) throws Exception {
        Field field = Gameboy.class.getDeclaredField("dma");
        field.setAccessible(true);
        return (Dma) field.get(gameboy);
    }

    private static int performanceScanlineEndTick(Gameboy gameboy) throws Exception {
        Field field = Gpu.class.getDeclaredField("performanceScanlineEndTick");
        field.setAccessible(true);
        return field.getInt(gameboy.getGpu());
    }

    private static boolean oamReaderInitialized(Gameboy gameboy) throws Exception {
        Field gpuField = Gpu.class.getDeclaredField("oamSearchPhase");
        gpuField.setAccessible(true);
        OamSearch search = (OamSearch) gpuField.get(gameboy.getGpu());
        return search.isOamReaderInitialized();
    }

    private static void setOamReaderInitialized(Gameboy gameboy, boolean value) throws Exception {
        Field gpuField = Gpu.class.getDeclaredField("oamSearchPhase");
        gpuField.setAccessible(true);
        OamSearch search = (OamSearch) gpuField.get(gameboy.getGpu());
        Field initialized = OamSearch.class.getDeclaredField("oamReaderInitialized");
        initialized.setAccessible(true);
        initialized.setBoolean(search, value);
    }

    private enum VisualCase {
        BACKGROUND,
        OBJECTS,
        WINDOW
    }
}
