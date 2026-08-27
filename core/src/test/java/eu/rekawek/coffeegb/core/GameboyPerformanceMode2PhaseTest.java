package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.gpu.Mode;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import eu.rekawek.coffeegb.core.joypad.PlayerInputSnapshot;
import eu.rekawek.coffeegb.core.joypad.PlayerInputSource;
import eu.rekawek.coffeegb.core.memory.Hdma;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Differential coverage for the short DMG-clocked mode-2 phase packet. */
public final class GameboyPerformanceMode2PhaseTest {

    /** Deliberately non-identity source: PERFORMANCE's input horizon must reject this leg. */
    private static final PlayerInputSource SCALAR_INPUT = PlayerInputSnapshot::released;

    private record Mode2ExclusionCase(String label, HardwareProfile profile,
                                      ExecutionMode executionMode, boolean nativeColor,
                                      boolean scanlineCapable) {
    }

    @Test
    public void physicalDmgAndMgbMode2PacketsMatchScalarForBothSpriteHeights()
            throws Exception {
        assertMode2PacketsMatchScalarForBothSpriteHeights(new HardwareProfile[] {
                HardwareProfileRegistry.DMG, HardwareProfileRegistry.MGB});
    }

    @Test
    public void sgbAndSgb2Mode2PacketsMatchScalarForBothSpriteHeights()
            throws Exception {
        assertMode2PacketsMatchScalarForBothSpriteHeights(new HardwareProfile[] {
                HardwareProfileRegistry.SGB, HardwareProfileRegistry.SGB2});
    }

    @Test
    public void cgbCompatibilityMode2PacketsMatchScalarForBothSpriteHeights()
            throws Exception {
        assertMode2PacketsMatchScalarForBothSpriteHeights(new HardwareProfile[] {
                HardwareProfileRegistry.CGB});
    }

    private static void assertMode2PacketsMatchScalarForBothSpriteHeights(
            HardwareProfile[] profiles) throws Exception {
        for (HardwareProfile profile : profiles) {
            for (boolean tallSprites : new boolean[] {false, true}) {
                for (int start : new int[] {0, 1, 2, 3, 13, 20, 76, 77, 78}) {
                    int[] spans = {1, 2, 3};
                    for (int requested : spans) {
                        if (start + requested > 79) {
                            continue;
                        }
                        PlayerInputHub candidateHub = new PlayerInputHub();
                        try (PlayerInputHub.SourceHandle ignored = candidateHub.openSource(0);
                                Gameboy scalar = session(profile, SCALAR_INPUT);
                                Gameboy candidate = session(profile, candidateHub)) {
                            reachMode2Start(scalar, candidate, start, tallSprites);
                            scalar.getGpu().setPerformanceScanlineEnabled(true);
                            candidate.getGpu().setPerformanceScanlineEnabled(true);
                            assertEquals("mode-2 quiet span was unexpectedly admitted",
                                    0, candidate.getGpu().performanceQuietSpanLimit());
                            assertTrue("mode-2 phase preflight rejected profile="
                                            + profile.id() + " tall=" + tallSprites
                                            + " start=" + start + " span=" + requested,
                                    performanceMode2PhaseSpanLimit(
                                            candidate, profile, requested) > 0);
                            int cpuPhaseLimit = candidate.getCpu().performancePhaseOnlySpanLimit();
                            String caseDetails = "profile=" + profile.id()
                                    + " tall=" + tallSprites + " start=" + start
                                    + " requested=" + requested + " cpuPhaseLimit="
                                    + cpuPhaseLimit + " cpuState=" + candidate.getCpu().getState()
                                    + " gpuMode=" + candidate.getGpu().getMode()
                                    + " gpuDot=" + candidate.getGpu().getTicksInLine();
                            candidate.resetPerformanceBulkCounters();

                            for (int i = 0; i < requested; i++) {
                                scalar.tick();
                            }
                            assertEquals("candidate frame callback",
                                    0, candidate.runTicks(requested));
                            if (start >= 13 && requested <= cpuPhaseLimit) {
                                assertTrue("mode-2 packet was not selected (" + caseDetails
                                                + ", bulkTicks="
                                                + candidate.getPerformanceBulkTicks() + ")",
                                        candidate.getPerformanceBulkTicks() > 0);
                            } else if (start <= 3) {
                                assertEquals("early mode-2 STAT checkpoint took a bulk packet ("
                                                + caseDetails + ")", 0,
                                        candidate.getPerformanceBulkTicks());
                            }
                            assertDeepStateEquals("profile=" + profile.id()
                                            + " tall=" + tallSprites + " start=" + start
                                            + " span=" + requested,
                                    scalar.captureStateWithoutTimeSource(),
                                    candidate.captureStateWithoutTimeSource());
                        }
                    }
                }
            }
        }
    }

    @Test
    public void physicalDmgMode2LeavesDot79To80Scalar() throws Exception {
        assertMode2LeavesDot79To80Scalar(HardwareProfileRegistry.DMG);
    }

    @Test
    public void sgbAndSgb2Mode2LeaveDot79To80Scalar() throws Exception {
        for (HardwareProfile profile : new HardwareProfile[] {
                HardwareProfileRegistry.SGB, HardwareProfileRegistry.SGB2}) {
            assertMode2LeavesDot79To80Scalar(profile);
        }
    }

    @Test
    public void cgbCompatibilityMode2LeavesDot79To80Scalar() throws Exception {
        assertMode2LeavesDot79To80Scalar(HardwareProfileRegistry.CGB);
    }

    private static void assertMode2LeavesDot79To80Scalar(HardwareProfile profile)
            throws Exception {
        PlayerInputHub candidateHub = new PlayerInputHub();
        try (PlayerInputHub.SourceHandle ignored = candidateHub.openSource(0);
                Gameboy scalar = session(profile, SCALAR_INPUT);
                Gameboy candidate = session(profile, candidateHub)) {
            reachMode2Start(scalar, candidate, 76, false);
            scalar.getGpu().setPerformanceScanlineEnabled(true);
            candidate.getGpu().setPerformanceScanlineEnabled(true);
            candidate.resetPerformanceBulkCounters();

            for (int i = 0; i < 3; i++) {
                scalar.tick();
            }
            assertEquals(0, candidate.runTicks(3));
            assertEquals(79, candidate.getGpu().getTicksInLine());
            assertEquals(Mode.OamSearch, candidate.getGpu().getMode());
            long packetTicks = candidate.getPerformanceBulkTicks();
            assertTrue("mode-2 prefix was not bulk committed", packetTicks > 0);

            scalar.tick();
            assertEquals(0, candidate.runTicks(1));
            assertEquals(80, candidate.getGpu().getTicksInLine());
            assertEquals(Mode.PixelTransfer, candidate.getGpu().getMode());
            assertEquals("mode-3 handoff entered the mode-2 packet", packetTicks,
                    candidate.getPerformanceBulkTicks());
            if (profile == HardwareProfileRegistry.CGB) {
                assertTrue("CGB handoff left DMG compatibility mode",
                        candidate.getGpu().isDmgCompatMode());
                assertTrue("scalar CGB compatibility handoff did not arm the color renderer",
                        candidate.getGpu().isPerformanceScanlineCursorActive());
            }
            assertDeepStateEquals(profile.id() + " dot-79 handoff",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void physicalDmgMode2RejectsAnActiveDmaTransfer() throws Exception {
        assertMode2RejectsAnActiveDmaTransfer(HardwareProfileRegistry.DMG);
    }

    @Test
    public void sgbAndSgb2Mode2RejectActiveDmaTransfer() throws Exception {
        for (HardwareProfile profile : new HardwareProfile[] {
                HardwareProfileRegistry.SGB, HardwareProfileRegistry.SGB2}) {
            assertMode2RejectsAnActiveDmaTransfer(profile);
        }
    }

    @Test
    public void cgbCompatibilityMode2RejectsActiveOamDma() throws Exception {
        assertMode2RejectsAnActiveDmaTransfer(HardwareProfileRegistry.CGB);
    }

    private static void assertMode2RejectsAnActiveDmaTransfer(HardwareProfile profile)
            throws Exception {
        PlayerInputHub candidateHub = new PlayerInputHub();
        try (PlayerInputHub.SourceHandle ignored = candidateHub.openSource(0);
                Gameboy scalar = session(profile, SCALAR_INPUT);
                Gameboy candidate = session(profile, candidateHub)) {
            reachMode2Start(scalar, candidate, 20, false);
            scalar.getGpu().setPerformanceScanlineEnabled(true);
            candidate.getGpu().setPerformanceScanlineEnabled(true);
            scalar.getAddressSpace().setByte(0xff46, 0x80);
            candidate.getAddressSpace().setByte(0xff46, 0x80);
            assertEquals(0, performanceMode2PhaseSpanLimit(candidate, profile, 3));
            candidate.resetPerformanceBulkCounters();

            for (int i = 0; i < 3; i++) {
                scalar.tick();
            }
            assertEquals(0, candidate.runTicks(3));
            assertEquals("active OAM DMA entered a mode-2 packet", 0,
                    candidate.getPerformanceBulkTicks());
            assertDeepStateEquals(profile.id() + " active OAM DMA",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void physicalDmgMode2RestoreFailsClosedThenMatchesScalar() throws Exception {
        assertMode2RestoreFailsClosedThenMatchesScalar(HardwareProfileRegistry.DMG);
    }

    @Test
    public void cgbCompatibilityMode2RestoreFailsClosedThenMatchesScalar() throws Exception {
        assertMode2RestoreFailsClosedThenMatchesScalar(HardwareProfileRegistry.CGB);
    }

    private static void assertMode2RestoreFailsClosedThenMatchesScalar(
            HardwareProfile profile) throws Exception {
        PlayerInputHub candidateHub = new PlayerInputHub();
        try (PlayerInputHub.SourceHandle ignored = candidateHub.openSource(0);
                Gameboy scalar = session(profile, SCALAR_INPUT);
                Gameboy candidate = session(profile, candidateHub)) {
            reachMode2Start(scalar, candidate, 20, true);
            scalar.getGpu().setPerformanceScanlineEnabled(true);
            candidate.getGpu().setPerformanceScanlineEnabled(true);
            var checkpoint = candidate.captureState();
            candidate.restoreState(checkpoint);
            assertEquals("restored mode-2 state entered the fixed-point transaction", 0,
                    performanceMode2PhaseSpanLimit(candidate, profile, 3));
            candidate.resetPerformanceBulkCounters();

            // Restored LCDC history is intentionally conservative for nine dots. The scalar
            // fallback during that drain must remain observationally equivalent.
            for (int i = 0; i < 9; i++) {
                scalar.tick();
                candidate.runTicks(1);
            }
            assertEquals("restore drain entered a mode-2 packet", 0,
                    candidate.getPerformanceBulkTicks());
            assertDeepStateEquals(profile.id() + " restore mode-2 drain",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void cgbCompatibilityMode2RejectsActiveHdma() throws Exception {
        PlayerInputHub candidateHub = new PlayerInputHub();
        try (PlayerInputHub.SourceHandle ignored = candidateHub.openSource(0);
                Gameboy scalar = session(HardwareProfileRegistry.CGB, SCALAR_INPUT);
                Gameboy candidate = session(HardwareProfileRegistry.CGB, candidateHub)) {
            reachMode2Start(scalar, candidate, 20, false);
            scalar.getGpu().setPerformanceScanlineEnabled(true);
            candidate.getGpu().setPerformanceScanlineEnabled(true);
            startOneBlockGdma(scalar);
            startOneBlockGdma(candidate);
            assertEquals(0,
                    candidate.getGpu().performanceCgbCompatibilityMode2PhaseSpanLimit(3));
            candidate.resetPerformanceBulkCounters();

            for (int i = 0; i < 3; i++) {
                scalar.tick();
            }
            assertEquals(0, candidate.runTicks(3));
            assertEquals("active HDMA entered a mode-2 packet", 0,
                    candidate.getPerformanceBulkTicks());
            assertDeepStateEquals("CGB compatibility active HDMA",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());

            int guard = 0;
            while (candidate.getHdma().hasActiveOrPendingTransfer() && guard++ < 64) {
                scalar.tick();
                candidate.runTicks(1);
            }
            assertTrue("one-block GDMA did not complete inside mode 2", guard < 64);
            assertEquals(Mode.OamSearch, candidate.getGpu().getMode());
            assertTrue("GDMA completion crossed the mode-2 packet cap",
                    candidate.getGpu().getTicksInLine() < 79);
            Hdma.HdmaState completed = (Hdma.HdmaState) candidate.getHdma().captureState();
            assertEquals("fixture did not retain the post-GDMA request clock", 0,
                    completed.hblankRequestTicks());

            candidate.getGpu().setPerformanceScanlineEnabled(true);
            assertTrue("post-GDMA request clock rejected an exact mode-2 packet",
                    candidate.getGpu().performanceCgbCompatibilityMode2PhaseSpanLimit(3) > 0);
            candidate.resetPerformanceBulkCounters();
            int tail = Math.min(8, 79 - candidate.getGpu().getTicksInLine());
            for (int i = 0; i < tail; i++) {
                scalar.tick();
            }
            assertEquals(0, candidate.runTicks(tail));
            assertTrue("completed GDMA tail did not enter a mode-2 packet",
                    candidate.getPerformanceBulkTicks() > 0);
            assertDeepStateEquals("CGB compatibility completed-GDMA request clock",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void cgbCompatibilityMode2RejectsCgb0NativeAccuracyAndDebug() throws Exception {
        Mode2ExclusionCase[] cases = {
                new Mode2ExclusionCase("cgb0-compat", HardwareProfileRegistry.CGB0,
                        ExecutionMode.PERFORMANCE, false, true),
                new Mode2ExclusionCase("cgb-native", HardwareProfileRegistry.CGB,
                        ExecutionMode.PERFORMANCE, true, true),
                new Mode2ExclusionCase("accuracy-cgb-compat", HardwareProfileRegistry.CGB,
                        ExecutionMode.ACCURACY, false, false)
        };
        for (Mode2ExclusionCase exclusion : cases) {
            PlayerInputHub hub = new PlayerInputHub();
            try (PlayerInputHub.SourceHandle ignored = hub.openSource(0);
                    Gameboy gameboy = session(exclusion.profile(), hub,
                            exclusion.executionMode(), exclusion.nativeColor())) {
                reachMode2Start(gameboy, gameboy, 20, false);
                if (exclusion.scanlineCapable()) {
                    gameboy.getGpu().setPerformanceScanlineEnabled(true);
                }
                assertEquals(exclusion.label() + " entered CGB compatibility mode 2", 0,
                        gameboy.getGpu().performanceCgbCompatibilityMode2PhaseSpanLimit(3));
            }
        }

        PlayerInputHub debugHub = new PlayerInputHub();
        try (PlayerInputHub.SourceHandle ignored = debugHub.openSource(0);
                Gameboy debug = session(HardwareProfileRegistry.CGB, debugHub)) {
            reachMode2Start(debug, debug, 20, false);
            debug.getGpu().setPerformanceScanlineEnabled(true);
            assertTrue("ordinary CGB compatibility control was not eligible",
                    debug.getGpu().performanceCgbCompatibilityMode2PhaseSpanLimit(3) > 0);
            debug.getGpu().setDebugHooks(new TestDebugHooks());
            assertEquals("debug hooks entered CGB compatibility mode 2", 0,
                    debug.getGpu().performanceCgbCompatibilityMode2PhaseSpanLimit(3));
        }
    }

    @Test
    public void nativeCgbNormalSpeedMode2RemainsScalar() throws Exception {
        PlayerInputHub candidateHub = new PlayerInputHub();
        try (PlayerInputHub.SourceHandle ignored = candidateHub.openSource(0);
             Gameboy scalar = session(HardwareProfileRegistry.CGB, SCALAR_INPUT,
                     ExecutionMode.PERFORMANCE, true);
             Gameboy candidate = session(HardwareProfileRegistry.CGB, candidateHub,
                     ExecutionMode.PERFORMANCE, true)) {
            reachMode2Start(scalar, candidate, 20, false);
            scalar.resetPerformanceBulkCounters();
            candidate.resetPerformanceBulkCounters();

            for (int tick = 0; tick < 3; tick++) {
                scalar.tick();
            }
            assertEquals(0, candidate.runTicks(3));
            assertEquals("native CGB x1 mode 2 entered a phase packet", 0L,
                    candidate.getPerformanceBulkTicks());
            assertEquals("native CGB x1 mode 2 entered a CPU epoch", 0L,
                    candidate.getPerformanceEpochTicks());
            assertDeepStateEquals("native CGB x1 scalar mode 2",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    @Test
    public void physicalDmgMode2ExcludesOtherProfilesAtAnOtherwiseEligibleMode2Point()
            throws Exception {
        // The positive DMG control proves that this exact non-first-line mode-2 fixture has
        // settled LCDC/output/OAM state. Every row below reaches the same point; only the
        // construction-time profile or execution-mode permission is changed.
        PlayerInputHub controlHub = new PlayerInputHub();
        try (PlayerInputHub.SourceHandle ignored = controlHub.openSource(0);
                Gameboy control = session(HardwareProfileRegistry.DMG, controlHub)) {
            reachMode2Start(control, control, 20, false);
            advanceToPerformanceCpuPhase(control);
            control.getGpu().setPerformanceScanlineEnabled(true);
            assertTrue("DMG control fixture was not otherwise eligible",
                    control.getGpu().performancePhysicalDmgMode2PhaseSpanLimit(3) > 0);
        }

        Mode2ExclusionCase[] cases = {
                new Mode2ExclusionCase("cgb-native", HardwareProfileRegistry.CGB,
                        ExecutionMode.PERFORMANCE, true, true),
                new Mode2ExclusionCase("cgb0-native", HardwareProfileRegistry.CGB0,
                        ExecutionMode.PERFORMANCE, true, true),
                new Mode2ExclusionCase("cgb-compat", HardwareProfileRegistry.CGB,
                        ExecutionMode.PERFORMANCE, false, true),
                new Mode2ExclusionCase("accuracy-dmg", HardwareProfileRegistry.DMG,
                        ExecutionMode.ACCURACY, false, false),
                new Mode2ExclusionCase("accuracy-sgb", HardwareProfileRegistry.SGB,
                        ExecutionMode.ACCURACY, false, false),
                new Mode2ExclusionCase("accuracy-sgb2", HardwareProfileRegistry.SGB2,
                        ExecutionMode.ACCURACY, false, false)
        };
        for (Mode2ExclusionCase exclusion : cases) {
            PlayerInputHub hub = new PlayerInputHub();
            try (PlayerInputHub.SourceHandle ignored = hub.openSource(0);
                    Gameboy gameboy = session(exclusion.profile(), hub,
                            exclusion.executionMode(), exclusion.nativeColor())) {
                reachMode2Start(gameboy, gameboy, 20, false);
                assertEquals(exclusion.label() + " mode", Mode.OamSearch,
                        gameboy.getGpu().getMode());
                assertEquals(exclusion.label() + " line", 1, gameboy.getGpu().getLine());
                assertEquals(exclusion.label() + " dot", 20,
                        gameboy.getGpu().getTicksInLine());
                assertTrue(exclusion.label() + " LCD disabled", gameboy.getGpu().isLcdEnabled());
                assertEquals(exclusion.label() + " speed", 1,
                        gameboy.getSpeedMode().getSpeedMode());
                assertEquals(exclusion.label() + " mode-2 quiet path unexpectedly active", 0,
                        gameboy.getGpu().performanceQuietSpanLimit());
                if (exclusion.scanlineCapable()) {
                    advanceToPerformanceCpuPhase(gameboy);
                    gameboy.getGpu().setPerformanceScanlineEnabled(true);
                    assertTrue(exclusion.label() + " CPU phase is not available",
                            gameboy.getCpu().performancePhaseOnlySpanLimit() > 0);
                }
                assertEquals(exclusion.label() + " entered the physical-DMG mode-2 plan", 0,
                        gameboy.getGpu().performancePhysicalDmgMode2PhaseSpanLimit(3));
            }
        }
    }

    private static void advanceToPerformanceCpuPhase(Gameboy gameboy) {
        int guard = 0;
        while (gameboy.getCpu().performancePhaseOnlySpanLimit() == 0 && guard++ < 4) {
            gameboy.tick();
        }
        assertTrue("mode-2 exclusion fixture left the CPU on a boundary",
                gameboy.getCpu().performancePhaseOnlySpanLimit() > 0);
        assertEquals(Mode.OamSearch, gameboy.getGpu().getMode());
        assertTrue("mode-2 exclusion fixture crossed the OAM horizon",
                gameboy.getGpu().getTicksInLine() < 79);
    }

    private static int performanceMode2PhaseSpanLimit(
            Gameboy gameboy, HardwareProfile profile, int requested) {
        return profile == HardwareProfileRegistry.CGB
                ? gameboy.getGpu().performanceCgbCompatibilityMode2PhaseSpanLimit(requested)
                : gameboy.getGpu().performancePhysicalDmgMode2PhaseSpanLimit(requested);
    }

    @Test
    public void priorHblankHeightChangePreservesDmgYHalfStateAtNextLineDotZero()
            throws Exception {
        PlayerInputHub candidateHub = new PlayerInputHub();
        try (PlayerInputHub.SourceHandle ignored = candidateHub.openSource(0);
                Gameboy scalar = session(HardwareProfileRegistry.DMG, SCALAR_INPUT);
                Gameboy candidate = session(HardwareProfileRegistry.DMG, candidateHub)) {
            reachMode2Start(scalar, candidate, 0, false);
            int guard = 0;
            while (scalar.getGpu().getMode() != Mode.HBlank && guard++ < 456) {
                scalar.tick();
                candidate.tick();
            }
            assertTrue("did not reach line-1 HBlank", guard < 456);

            int oldLcdc = scalar.getGpu().getByte(0xff40);
            scalar.getGpu().setByte(0xff40, oldLcdc | 0x04);
            candidate.getGpu().setByte(0xff40, oldLcdc | 0x04);
            guard = 0;
            while ((scalar.getGpu().getLine() != 2
                            || scalar.getGpu().getTicksInLine() != 0)
                    && guard++ < 456) {
                scalar.tick();
                candidate.tick();
            }
            assertTrue("did not reach next-line dot zero", guard < 456);
            scalar.getGpu().setPerformanceScanlineEnabled(true);
            candidate.getGpu().setPerformanceScanlineEnabled(true);
            assertTrue(candidate.getGpu().performancePhysicalDmgMode2PhaseSpanLimit(1) > 0);

            scalar.tick();
            assertEquals(0, candidate.runTicks(1));
            assertDeepStateEquals("prior-HBlank LCDC.2 transition",
                    scalar.captureStateWithoutTimeSource(),
                    candidate.captureStateWithoutTimeSource());
        }
    }

    private static void reachMode2Start(Gameboy scalar, Gameboy candidate,
                                         int targetDot, boolean tallSprites) {
        int guard = 0;
        while (scalar.getGpu().getMode() != Mode.VBlank && guard++ < 456 * 155) {
            scalar.tick();
            if (candidate != scalar) {
                candidate.tick();
            }
        }
        assertTrue("mode-2 setup did not reach VBlank", guard < 456 * 155);

        int lcdc = scalar.getGpu().getByte(0xff40);
        int requestedLcdc = tallSprites ? lcdc | 0x04 : lcdc & ~0x04;
        scalar.getGpu().setByte(0xff40, requestedLcdc);
        if (candidate != scalar) {
            candidate.getGpu().setByte(0xff40, requestedLcdc);
        }
        // Two selected sprites exercise the same Y/X commit path without relying on an OAM
        // DMA burst.  Their Y covers line 1 for both 8- and 16-pixel modes.
        for (int index = 0; index < 2; index++) {
            int address = 0xfe00 + index * 4;
            scalar.getGpu().setByte(address, 17);
            scalar.getGpu().setByte(address + 1, 8 + index * 16);
            if (candidate != scalar) {
                candidate.getGpu().setByte(address, 17);
                candidate.getGpu().setByte(address + 1, 8 + index * 16);
            }
        }

        guard = 0;
        while ((scalar.getGpu().getLine() != 1
                        || scalar.getGpu().getTicksInLine() != targetDot)
                && guard++ < 456 * 12) {
            scalar.tick();
            if (candidate != scalar) {
                candidate.tick();
            }
        }
        assertTrue("mode-2 setup did not reach line 1 dot " + targetDot,
                guard < 456 * 12);
        assertEquals(Mode.OamSearch, scalar.getGpu().getMode());
        assertEquals(Mode.OamSearch, candidate.getGpu().getMode());
    }

    private static Gameboy session(HardwareProfile profile, PlayerInputSource inputSource)
            throws Exception {
        return session(profile, inputSource, ExecutionMode.PERFORMANCE, false);
    }

    private static Gameboy session(HardwareProfile profile, PlayerInputSource inputSource,
                                   ExecutionMode executionMode, boolean nativeColor)
            throws Exception {
        byte[] image = new byte[0x8000];
        image[0x100] = (byte) 0xc3;
        image[0x101] = 0;
        image[0x102] = 1;
        if (nativeColor) {
            image[0x143] = (byte) 0x80;
        }
        return new Gameboy.GameboyConfiguration(new Rom(image))
                .setHardwareProfile(profile)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(executionMode)
                .setPlayerInputSource(inputSource)
                .setSupportBatterySave(false)
                .build();
    }

    private static void startOneBlockGdma(Gameboy gameboy) {
        var bus = gameboy.getAddressSpace();
        bus.setByte(0xff51, 0);
        bus.setByte(0xff52, 0);
        bus.setByte(0xff53, 0);
        bus.setByte(0xff54, 0);
        bus.setByte(0xff55, 0);
    }

    private static void assertDeepStateEquals(String path, Object expected, Object actual) {
        if (expected == null || actual == null) {
            assertEquals(path, expected, actual);
            return;
        }
        assertEquals(path + " type", expected.getClass(), actual.getClass());
        Class<?> type = expected.getClass();
        if (type.isArray()) {
            int length = Array.getLength(expected);
            assertEquals(path + " length", length, Array.getLength(actual));
            for (int i = 0; i < length; i++) {
                assertDeepStateEquals(path + '[' + i + ']', Array.get(expected, i),
                        Array.get(actual, i));
            }
            return;
        }
        if (expected instanceof List<?> expectedList) {
            List<?> actualList = (List<?>) actual;
            assertEquals(path + " size", expectedList.size(), actualList.size());
            for (int i = 0; i < expectedList.size(); i++) {
                assertDeepStateEquals(path + '[' + i + ']', expectedList.get(i),
                        actualList.get(i));
            }
            return;
        }
        if (!type.isRecord()) {
            assertEquals(path, expected, actual);
            return;
        }
        try {
            for (RecordComponent component : type.getRecordComponents()) {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                assertDeepStateEquals(path + '.' + component.getName(),
                        accessor.invoke(expected), accessor.invoke(actual));
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot compare " + path, e);
        }
    }
}
