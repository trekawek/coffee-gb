package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.core.sgb.SgbDisplay;
import org.junit.Test;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static eu.rekawek.coffeegb.core.gpu.GpuRegister.WX;
import static eu.rekawek.coffeegb.core.gpu.GpuRegister.WY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Smoke coverage for the opt-in PERFORMANCE line compositor and its coarse raster seam. */
public final class PerformanceScanlineIntegrationTest {

    @Test
    public void performanceRunTicksActivatesDmgLineCompositor() throws Exception {
        try (Gameboy gameboy = session(false)) {
            gameboy.runTicks(100_000);
            assertTrue("DMG direct scanline path did not activate",
                    gameboy.getGpu().getPerformanceScanlineLines() > 0);
            assertTrue("DMG direct/quiet span did not skip raster work",
                    gameboy.getGpu().getPerformanceScanlineFastTicks() > 0);
        }
    }

    @Test
    public void performanceRunTicksActivatesNativeCgbLineCompositor() throws Exception {
        try (Gameboy gameboy = session(true)) {
            gameboy.runTicks(100_000);
            assertTrue("CGB direct scanline path did not activate",
                    gameboy.getGpu().getPerformanceScanlineLines() > 0);
            assertTrue("CGB direct/quiet span did not skip raster work",
                    gameboy.getGpu().getPerformanceScanlineFastTicks() > 0);
        }
    }

    @Test
    public void performanceRunTicksActivatesSgbScanlineAndPreservesVblankPayloads()
            throws Exception {
        for (HardwareProfile profile : new HardwareProfile[] {
                HardwareProfileRegistry.SGB, HardwareProfileRegistry.SGB2}) {
            try (SgbProbe accuracy = new SgbProbe(profile, ExecutionMode.ACCURACY);
                    SgbProbe performance = new SgbProbe(profile, ExecutionMode.PERFORMANCE)) {
                accuracy.runToTransfer();
                performance.runToTransfer();

                assertTrue(profile.id() + " direct scanline path did not activate",
                        performance.gameboy.getGpu().getPerformanceScanlineLines() > 0);
                assertTrue(profile.id() + " direct raster path did not skip work",
                        performance.gameboy.getGpu().getPerformanceScanlineFastTicks() > 0);
                assertTrue(profile.id() + " accuracy SGB frame was not emitted",
                        accuracy.sgbFrameCount > 0);
                assertEquals(profile.id() + " SGB frame count",
                        accuracy.sgbFrameCount, performance.sgbFrameCount);
                assertEquals(profile.id() + " SGB frame hash",
                        accuracy.sgbFrameHash, performance.sgbFrameHash);
                assertEquals(profile.id() + " VRAM transfer count",
                        accuracy.transferCount, performance.transferCount);
                assertEquals(profile.id() + " VRAM transfer hash",
                        accuracy.transferHash, performance.transferHash);
            }
        }
    }

    @Test
    public void runTicksMatchesScalarSchedulerForSmallRequests() throws Exception {
        for (int ticks = 0; ticks <= 8; ticks++) {
            try (Gameboy scalar = session(false); Gameboy batched = session(false)) {
                int scalarFrames = 0;
                for (int i = 0; i < ticks; i++) {
                    if (scalar.tick()) {
                        scalarFrames++;
                    }
                }
                assertEquals("frame count for " + ticks, scalarFrames, batched.runTicks(ticks));
                assertEquivalent(scalar, batched, "small request " + ticks);
            }
        }
    }

    @Test
    public void runTicksPreservesFrameTailBudget() throws Exception {
        // A physical LCD frame is 154 lines. Leave a short tail so the final quiet span is
        // necessarily clipped to the caller's exact request instead of consuming a whole
        // preflighted CPU/PPU window.
        int frameTicks = 456 * 154;
        int prefix = frameTicks - 8;
        try (Gameboy scalar = session(false); Gameboy batched = session(false)) {
            int scalarFrames = 0;
            for (int i = 0; i < prefix; i++) {
                if (scalar.tick()) {
                    scalarFrames++;
                }
            }
            assertEquals(scalarFrames, batched.runTicks(prefix));
            assertEquivalent(scalar, batched, "frame prefix");

            for (int i = 0; i < 8; i++) {
                boolean scalarFrame = scalar.tick();
                assertEquals("frame tail " + i, scalarFrame, batched.runTicks(1) != 0);
                assertEquivalent(scalar, batched, "frame tail " + i);
            }
        }
    }

    @Test
    public void measuredPerformanceRunExecutesNoTickAfterNativeFrameCallback() throws Exception {
        try (EventBus eventBus = new EventBusImpl(); Gameboy gameboy = session(false)) {
            AtomicBoolean stop = new AtomicBoolean();
            AtomicInteger callbacks = new AtomicInteger();
            AtomicInteger endpointLine = new AtomicInteger();
            AtomicInteger endpointDot = new AtomicInteger();
            AtomicLong endpointGpuGeneration = new AtomicLong();
            eventBus.register(event -> {
                callbacks.incrementAndGet();
                endpointLine.set(gameboy.getGpu().getLine());
                endpointDot.set(gameboy.getGpu().getTicksInLine());
                endpointGpuGeneration.set(gameboy.getGpu().getTimingGeneration());
                stop.set(true);
            }, Display.DmgFrameReadyEvent.class);
            gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);

            int executed = gameboy.runMeasuredTicksUntilStop(2 * 456 * 154, stop::get);

            assertTrue("native endpoint was not reached", stop.get());
            assertEquals("endpoint callback repeated", 1, callbacks.get());
            assertTrue("stop-aware run consumed the full two-frame budget",
                    executed > 0 && executed < 2 * 456 * 154);
            assertEquals("a post-endpoint tick changed LY",
                    endpointLine.get(), gameboy.getGpu().getLine());
            assertEquals("a post-endpoint tick changed the line dot",
                    endpointDot.get(), gameboy.getGpu().getTicksInLine());
            assertEquals("a post-endpoint tick changed GPU generation",
                    endpointGpuGeneration.get(), gameboy.getGpu().getTimingGeneration());
        }
    }

    @Test
    public void captureRestorePreservesDirectLineAndIsObservationallyPure() throws Exception {
        try (Gameboy source = session(false);
                Gameboy freshTarget = session(false)) {
            int guard = 0;
            while (!source.getGpu().isPerformanceScanlineCursorActive() && guard++ < 10_000) {
                source.runTicks(1);
            }
            assertTrue("test did not reach a direct mode-3 line",
                    source.getGpu().isPerformanceScanlineCursorActive());

            // Capturing a live direct line must not publish an early HBlank or otherwise change
            // the next dot. Compare a capture owner with an untouched peer from the same seam.
            try (Gameboy peer = session(false)) {
                var saved = source.captureState();
                peer.restoreState(saved);
                int line = source.getGpu().getLine();
                int lineTicks = source.getGpu().getTicksInLine();
                source.captureState();
                assertEquals("capture moved the direct line", line, source.getGpu().getLine());
                assertEquals("capture moved the direct dot", lineTicks,
                        source.getGpu().getTicksInLine());
                source.runTicks(1);
                peer.runTicks(1);
                assertEquivalent(source, peer, "capture purity");
            }

            var saved = source.captureState();
            // Restore into both the previous direct-path owner and a fresh instance. The
            // direct cursor and coarse marker are part of the state, so both targets must
            // resume at the same predicted handoff regardless of prior optimization history.
            source.restoreState(saved);
            freshTarget.restoreState(saved);
            for (int i = 0; i < 16; i++) {
                assertEquals("restore frame " + i,
                        source.runTicks(1), freshTarget.runTicks(1));
                assertEquivalent(source, freshTarget, "restore tick " + i);
            }
        }
    }

    @Test
    public void scalarTickAfterRunTicksFinishesButDoesNotRearmDirectLines() throws Exception {
        try (Gameboy gameboy = session(false)) {
            gameboy.runTicks(2_000);
            // runTicks leaves only a resumable cursor, if any. A mixed scalar caller may finish
            // that already rendered line, but once it hands off the PERFORMANCE flag is still
            // disabled and later scalar mode-3 entries must not arm new lines.
            int guard = 0;
            while (gameboy.getGpu().isPerformanceScanlineCursorActive() && guard++ < 1_000) {
                gameboy.tick();
            }
            assertTrue("scalar caller failed to finish the direct cursor",
                    !gameboy.getGpu().isPerformanceScanlineCursorActive());
            long lines = gameboy.getGpu().getPerformanceScanlineLines();
            for (int i = 0; i < 2_000; i++) {
                gameboy.tick();
            }
            assertEquals("scalar ticks rearmed a direct scanline", lines,
                    gameboy.getGpu().getPerformanceScanlineLines());
        }
    }

    @Test
    public void windowCounterSurvivesDirectScalarFallbackAndResetsAtFrameEdge() throws Exception {
        try (Gameboy gameboy = session(false)) {
            // Enable the window before the first eligible line. WX=7 makes every visible line
            // consume one direct window row, which makes stale-row reuse observable.
            gameboy.getGpu().setByte(WY.getAddress(), 0);
            gameboy.getGpu().setByte(WX.getAddress(), 7);
            gameboy.getGpu().setByte(0xff40, 0xb1);

            int guard = 0;
            while (!gameboy.getGpu().isPerformanceScanlineCursorActive() && guard++ < 100_000) {
                gameboy.runTicks(1);
            }
            assertTrue("window test did not reach a direct line", guard < 100_000);
            int first = gameboy.getGpu().getPerformanceWindowLineCounter();
            assertTrue("first direct window row was not published", first >= 0);

            // Finish the rendered line, then force the following complete visible line through
            // the scalar path. Direct arm publishes the row into both PixelTransfer machines;
            // the scalar line must therefore advance from `first`, not from an old -1.
            while (gameboy.getGpu().isPerformanceScanlineCursorActive()) {
                gameboy.runTicks(1);
            }
            gameboy.getGpu().setPerformanceObservationBlocked(true);
            int directLine = gameboy.getGpu().getLine();
            while (gameboy.getGpu().getLine() == directLine) {
                gameboy.runTicks(1);
            }
            int scalarLine = gameboy.getGpu().getLine();
            while (gameboy.getGpu().getLine() == scalarLine) {
                gameboy.runTicks(1);
            }
            int afterScalar = gameboy.getGpu().getPerformanceWindowLineCounter();
            assertTrue("scalar fallback lost the direct window row",
                    afterScalar > first);

            gameboy.getGpu().setPerformanceObservationBlocked(false);
            while (!gameboy.getGpu().isPerformanceScanlineCursorActive() && guard++ < 200_000) {
                gameboy.runTicks(1);
            }
            assertTrue("direct path did not resume after scalar fallback", guard < 200_000);
            assertEquals("direct row did not continue after scalar fallback",
                    afterScalar + 1, gameboy.getGpu().getPerformanceWindowLineCounter());

            boolean sawFrameReset = false;
            int frameGuard = 0;
            while (!sawFrameReset && frameGuard++ < 456 * 154 * 2) {
                gameboy.runTicks(1);
                sawFrameReset = gameboy.getGpu().getLine() == 0
                        && gameboy.getGpu().getTicksInLine() == 0
                        && gameboy.getGpu().getPerformanceWindowLineCounter() == -1;
            }
            assertTrue("window row did not reset at a physical frame edge", sawFrameReset);
        }
    }

    @Test
    public void hblankQuietBulkRequiresAProvenDirectLine() throws Exception {
        try (Gameboy scalar = session(false)) {
            scalar.getGpu().setPerformanceObservationBlocked(true);
            int guard = 0;
            while (scalar.getGpu().getMode() != Mode.HBlank && guard++ < 2_000) {
                scalar.runTicks(1);
            }
            assertTrue("scalar setup did not reach HBlank", guard < 2_000);
            scalar.getGpu().setPerformanceScanlineEnabled(true);
            assertEquals("observation guard classifier",
                    Gpu.PERFORMANCE_PHYSICAL_DMG_REJECT_COMMON_GUARD,
                    scalar.getGpu().performancePhysicalDmgEpochRejectionCode(1));
            scalar.getGpu().setPerformanceObservationBlocked(false);
            assertEquals("scalar PixelTransfer output tail was bulk-skipped", 0,
                    scalar.getGpu().performanceQuietSpanLimit());
            assertEquals("non-direct HBlank classifier",
                    Gpu.PERFORMANCE_PHYSICAL_DMG_REJECT_HBLANK_LINE,
                    scalar.getGpu().performancePhysicalDmgEpochRejectionCode(1));
        }

        try (Gameboy direct = session(false)) {
            int guard = 0;
            while (!direct.getGpu().isPerformanceScanlineCursorActive() && guard++ < 100_000) {
                direct.runTicks(1);
            }
            assertTrue("direct setup did not arm", guard < 100_000);
            while (direct.getGpu().isPerformanceScanlineCursorActive()) {
                direct.runTicks(1);
            }
            direct.getGpu().setPerformanceScanlineEnabled(true);
            direct.getGpu().tick();
            assertTrue("direct line did not expose a quiet HBlank tail",
                    direct.getGpu().performanceQuietSpanLimit() > 0);
        }
    }

    @Test
    public void nativeCgbVblankHorizonAdmitsOamReaderPrefixAfterTrustedReplay() throws Exception {
        try (Gameboy gameboy = nativeCgbSession()) {
            settleNativeDoubleSpeed(gameboy);
            gameboy.getGpu().setPerformanceScanlineEnabled(true);
            int guard = 0;
            while (gameboy.getGpu().getMode() != Mode.VBlank && guard++ < 456 * 155) {
                gameboy.tick();
            }
            assertTrue("native CGB setup did not reach VBlank", guard < 456 * 155);
            assertEquals(0, gameboy.getGpu().getTicksInLine());
            for (int dot = 0; dot < 79; dot++) {
                assertTrue("native epoch VBlank dot " + dot,
                        gameboy.getGpu().performanceEpochSpanLimit(1) > 0);
                assertTrue("generic VBlank dot " + dot,
                        gameboy.getGpu().performanceQuietSpanLimit() > 0);
                gameboy.tick();
            }
            assertEquals(79, gameboy.getGpu().getTicksInLine());
            assertTrue("native epoch VBlank dot 79->80 was not admitted",
                    gameboy.getGpu().performanceEpochSpanLimit(1) > 0);
            assertTrue("generic VBlank dot 79->80 was not admitted",
                    gameboy.getGpu().performanceQuietSpanLimit() > 0);
        }
    }

    @Test
    public void physicalDmgVblankHorizonAdmitsOamReaderPrefixAfterTrustedReplay() throws Exception {
        try (Gameboy gameboy = session(false)) {
            gameboy.getGpu().setPerformanceScanlineEnabled(true);
            int guard = 0;
            while (gameboy.getGpu().getMode() != Mode.VBlank && guard++ < 456 * 155) {
                gameboy.tick();
            }
            assertTrue("DMG setup did not reach VBlank", guard < 456 * 155);
            assertEquals(0, gameboy.getGpu().getTicksInLine());
            for (int dot = 0; dot < 79; dot++) {
                assertTrue("physical-DMG VBlank dot " + dot,
                        gameboy.getGpu().performancePhysicalDmgEpochSpanLimit(1) > 0);
                assertEquals("physical-DMG VBlank classifier dot " + dot,
                        Gpu.PERFORMANCE_PHYSICAL_DMG_REJECT_NONE,
                        gameboy.getGpu().performancePhysicalDmgEpochRejectionCode(1));
                assertTrue("generic VBlank dot " + dot,
                        gameboy.getGpu().performanceQuietSpanLimit() > 0);
                gameboy.tick();
            }
            assertEquals(79, gameboy.getGpu().getTicksInLine());
            assertTrue("physical-DMG VBlank dot 79->80 was not admitted",
                    gameboy.getGpu().performancePhysicalDmgEpochSpanLimit(1) > 0);
            assertTrue("generic VBlank dot 79->80 was not admitted",
                    gameboy.getGpu().performanceQuietSpanLimit() > 0);
        }
    }

    @Test
    public void physicalDmgLcdOffHorizonFreezesUnsettledConflictHistory() throws Exception {
        try (Gameboy scalar = session(false); Gameboy candidate = session(false)) {
            int lcdc = scalar.getGpu().getByte(0xff40);
            // Toggle real DMG LCDC conflict-mixed bits before disabling the LCD. The LCD-off
            // proof intentionally does not wait for those histories to drain; they are frozen.
            scalar.getGpu().setByte(0xff40, lcdc ^ 0x14);
            candidate.getGpu().setByte(0xff40, lcdc ^ 0x14);
            scalar.getGpu().setByte(0xff40, 0x00);
            candidate.getGpu().setByte(0xff40, 0x00);
            candidate.getGpu().setPerformanceScanlineEnabled(true);

            assertDeepStateEquals("LCD-off state", scalar.getGpu().captureState(),
                    candidate.getGpu().captureState());
            assertTrue("physical-DMG LCD-off horizon rejected frozen history",
                    candidate.getGpu().performancePhysicalDmgNormalSpeedLcdOffSpanLimit(54) > 0);
            long generation = candidate.getGpu().getTimingGeneration();
            candidate.getGpu().advancePerformancePhysicalDmgNormalSpeedLcdOffSpanTrusted(54);
            for (int tick = 0; tick < 54; tick++) {
                scalar.getGpu().tick();
            }

            assertEquals("LCD-off timing generation", generation + 54,
                    candidate.getGpu().getTimingGeneration());
            assertDeepStateEquals("LCD-off trusted advancement changed frozen PPU state",
                    scalar.getGpu().captureState(), candidate.getGpu().captureState());
        }
    }

    @Test
    public void cgbLcdcSizeAndTileHistoryFailClosedUntilNineDotsDrain() throws Exception {
        try (Gameboy gameboy = nativeCgbSession()) {
            settleNativeDoubleSpeed(gameboy);
            gameboy.getGpu().setPerformanceScanlineEnabled(true);
            int guard = 0;
            while (gameboy.getGpu().getMode() != Mode.VBlank && guard++ < 456 * 155) {
                gameboy.tick();
            }
            assertTrue("native CGB setup did not reach VBlank", guard < 456 * 155);
            for (int i = 0; i < 79; i++) {
                gameboy.tick();
            }
            assertTrue(gameboy.getGpu().performanceEpochSpanLimit(1) > 0);
            int lcdc = gameboy.getGpu().getByte(0xff40);
            gameboy.getGpu().setByte(0xff40, lcdc ^ 0x14);
            assertEquals("recent LCDC.2/.4 history was admitted",
                    0, gameboy.getGpu().performanceEpochSpanLimit(1));
            var checkpoint = gameboy.captureState();
            gameboy.restoreState(checkpoint);
            assertEquals("restored LCDC history was admitted",
                    0, gameboy.getGpu().performanceEpochSpanLimit(1));
            for (int i = 0; i < 9; i++) {
                gameboy.tick();
            }
            assertTrue("LCDC history did not re-admit after its drain",
                    gameboy.getGpu().performanceEpochSpanLimit(1) > 0);
        }
    }

    @Test
    public void trustedVblankReplayPreservesResidualOamSourceChangeAge() throws Exception {
        try (Gameboy scalar = session(false); Gameboy candidate = session(false)) {
            reachVblankDot(scalar, 0, false);
            reachVblankDot(candidate, 0, false);
            setOamReaderSourceChangeTicks(scalar, 3);
            setOamReaderSourceChangeTicks(candidate, 3);
            candidate.getGpu().setPerformanceScanlineEnabled(true);
            for (int i = 0; i < 3; i++) {
                scalar.getGpu().tick();
            }
            candidate.getGpu().advancePerformanceQuietSpanTrusted(3, false, false);
            assertDeepStateEquals("residual OAM source age",
                    scalar.getGpu().captureState(), candidate.getGpu().captureState());
        }
    }

    @Test
    public void line153WindowCheckpointRemainsScalarAndRestorable() throws Exception {
        for (boolean cgb : new boolean[] {false, true}) {
            try (Gameboy scalar = session(cgb); Gameboy candidate = session(cgb)) {
                int guard = 0;
                while ((scalar.getGpu().getLine() != 153
                        || scalar.getGpu().getTicksInLine() != 453)
                        && guard++ < 456 * 154) {
                    scalar.tick();
                    candidate.tick();
                }
                assertTrue("line-153 setup did not reach old dot 453", guard < 456 * 154);
                candidate.getGpu().setPerformanceScanlineEnabled(true);
                assertEquals(1, candidate.getGpu().performanceQuietSpanLimit());
                if (cgb) {
                    assertEquals(1, candidate.getGpu().performanceEpochSpanLimit(2));
                } else {
                    assertEquals(1, candidate.getGpu().performancePhysicalDmgEpochSpanLimit(2));
                }

                var checkpoint = candidate.captureState();
                scalar.tick();
                candidate.restoreState(checkpoint);
                candidate.tick();
                assertDeepStateEquals("line153 dot454 restore",
                        scalar.captureState(), candidate.captureState());
                assertEquals(0, candidate.getGpu().performanceQuietSpanLimit());
                scalar.tick();
                candidate.tick();
                assertDeepStateEquals("line153 dot455 scalar handoff",
                        scalar.captureState(), candidate.captureState());
            }
        }
    }

    private static void setOamReaderSourceChangeTicks(Gameboy gameboy, int ticks)
            throws ReflectiveOperationException {
        Field phaseField = Gpu.class.getDeclaredField("oamSearchPhase");
        phaseField.setAccessible(true);
        Object phase = phaseField.get(gameboy.getGpu());
        Field ageField = phase.getClass().getDeclaredField("oamReaderSourceChangeTicks");
        ageField.setAccessible(true);
        ageField.setInt(phase, ticks);
    }

    @Test
    public void trustedVblankSpansMatchScalarGpuStateAndCheckpointRestore() throws Exception {
        int[] starts = {0, 1, 3, 78, 79, 80};
        int[] spans = {1, 3, 54};
        for (boolean cgb : new boolean[] {false, true}) {
            for (boolean epochPath : new boolean[] {false, true}) {
                if (epochPath && !cgb) {
                    // The physical-DMG epoch is covered by the explicit physical path below;
                    // native epochs are only available on CGB hardware.
                    continue;
                }
                for (int start : starts) {
                    for (int requested : spans) {
                        int ticks = Math.min(requested, 456 - start - 1);
                        if (ticks <= 0) {
                            continue;
                        }
                        try (Gameboy scalar = cgb ? nativeCgbSession() : session(false);
                                Gameboy candidate = cgb ? nativeCgbSession() : session(false)) {
                            reachVblankDot(scalar, start, cgb);
                            reachVblankDot(candidate, start, cgb);
                            candidate.getGpu().setPerformanceScanlineEnabled(true);
                            var checkpoint = candidate.captureState();
                            for (int i = 0; i < ticks; i++) {
                                scalar.getGpu().tick();
                            }
                            advanceTrustedVblankSpan(candidate, ticks, cgb, epochPath);
                            assertDeepStateEquals("VBlank " + cgb + " path " + epochPath
                                            + " dot " + start + " span " + ticks,
                                    scalar.getGpu().captureState(), candidate.getGpu().captureState());

                            candidate.restoreState(checkpoint);
                            advanceTrustedVblankSpan(candidate, ticks, cgb, epochPath);
                            assertDeepStateEquals("VBlank restore " + cgb + " path " + epochPath
                                            + " dot " + start + " span " + ticks,
                                    scalar.getGpu().captureState(), candidate.getGpu().captureState());
                        }
                    }
                }
            }
            if (!cgb) {
                for (int start : starts) {
                    for (int requested : spans) {
                        int ticks = Math.min(requested, 456 - start - 1);
                        if (ticks <= 0) {
                            continue;
                        }
                        try (Gameboy scalar = session(false); Gameboy candidate = session(false)) {
                            reachVblankDot(scalar, start, false);
                            reachVblankDot(candidate, start, false);
                            candidate.getGpu().setPerformanceScanlineEnabled(true);
                            var checkpoint = candidate.captureState();
                            for (int i = 0; i < ticks; i++) {
                                scalar.getGpu().tick();
                            }
                            advanceTrustedVblankSpan(candidate, ticks, false, true);
                            assertDeepStateEquals("VBlank physical dot " + start
                                            + " span " + ticks,
                                    scalar.getGpu().captureState(), candidate.getGpu().captureState());
                            candidate.restoreState(checkpoint);
                            advanceTrustedVblankSpan(candidate, ticks, false, true);
                            assertDeepStateEquals("VBlank physical restore dot " + start
                                            + " span " + ticks,
                                    scalar.getGpu().captureState(), candidate.getGpu().captureState());
                        }
                    }
                }
            }
        }
    }

    private static void advanceTrustedVblankSpan(
            Gameboy gameboy, int ticks, boolean cgb, boolean epochPath) {
        if (cgb && epochPath) {
            gameboy.getGpu().advancePerformanceEpochQuietSpanTrusted(ticks, false, false);
        } else if (!cgb && epochPath) {
            gameboy.getGpu().advancePhysicalDmgPerformanceEpochQuietSpanTrusted(
                    ticks, false, false);
        } else {
            gameboy.getGpu().advancePerformanceQuietSpanTrusted(ticks, false, false);
        }
    }

    private static void reachVblankDot(Gameboy gameboy, int dot, boolean nativeCgb) {
        if (nativeCgb) {
            settleNativeDoubleSpeed(gameboy);
        }
        int guard = 0;
        while (gameboy.getGpu().getMode() != Mode.VBlank && guard++ < 456 * 155) {
            gameboy.tick();
        }
        assertTrue("VBlank setup did not reach VBlank", guard < 456 * 155);
        for (int i = 0; i < dot; i++) {
            gameboy.getGpu().tick();
        }
        assertEquals(dot, gameboy.getGpu().getTicksInLine());
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

    private static void assertEquivalent(Gameboy expected, Gameboy actual, String context) {
        assertEquals(context + " LY", expected.getGpu().getLine(), actual.getGpu().getLine());
        assertEquals(context + " line ticks", expected.getGpu().getTicksInLine(), actual.getGpu().getTicksInLine());
        assertEquals(context + " mode", expected.getGpu().getMode(), actual.getGpu().getMode());
        assertEquals(context + " CPU state", expected.getCpu().getState(), actual.getCpu().getState());
        assertEquals(context + " AF", expected.getCpu().getRegisters().getAF(), actual.getCpu().getRegisters().getAF());
        assertEquals(context + " BC", expected.getCpu().getRegisters().getBC(), actual.getCpu().getRegisters().getBC());
        assertEquals(context + " DE", expected.getCpu().getRegisters().getDE(), actual.getCpu().getRegisters().getDE());
        assertEquals(context + " HL", expected.getCpu().getRegisters().getHL(), actual.getCpu().getRegisters().getHL());
        assertEquals(context + " SP", expected.getCpu().getRegisters().getSP(), actual.getCpu().getRegisters().getSP());
        assertEquals(context + " PC", expected.getCpu().getRegisters().getPC(), actual.getCpu().getRegisters().getPC());
        assertEquals(context + " speed", expected.getSpeedMode().getSpeedMode(), actual.getSpeedMode().getSpeedMode());
    }

    private static Gameboy session(boolean cgb) throws Exception {
        byte[] image = new byte[0x8000];
        image[0x100] = (byte) 0xc3; // JP $0100: stable CPU-side workload
        image[0x101] = 0;
        image[0x102] = 1;
        image[0x143] = (byte) (cgb ? 0x80 : 0);
        return new Gameboy.GameboyConfiguration(new Rom(image))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build();
    }

    private static final class SgbProbe implements AutoCloseable {
        private final EventBusImpl eventBus = new EventBusImpl(null, null, false);
        private final Gameboy gameboy;
        private int transferCount;
        private int sgbFrameCount;
        private long transferHash = 0xcbf29ce484222325L;
        private long sgbFrameHash = 0xcbf29ce484222325L;

        private SgbProbe(HardwareProfile profile, ExecutionMode executionMode) throws Exception {
            eventBus.register(event -> {
                sgbFrameCount++;
                for (int pixel : ((SgbDisplay.SgbFrameReadyEvent) event).buffer()) {
                    sgbFrameHash ^= pixel & 0xffffffffL;
                    sgbFrameHash *= 0x100000001b3L;
                }
            }, SgbDisplay.SgbFrameReadyEvent.class);
            gameboy = new Gameboy.GameboyConfiguration(new Rom(sgbRom()))
                    .setHardwareProfile(profile)
                    .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                    .setExecutionMode(executionMode)
                    .setSupportBatterySave(false)
                    .setDisplaySgbBorder(profile.capabilities().superGameboyBorder())
                    .build();
            gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
            sgbBus(gameboy).register(event -> {
                transferCount++;
                for (int pixel : ((VRamTransfer.VRamTransferComplete) event).buffer()) {
                    transferHash ^= pixel & 0xffffffffL;
                    transferHash *= 0x100000001b3L;
                }
            }, VRamTransfer.VRamTransferComplete.class);
            for (int address = 0x8000; address < 0xa000; address++) {
                gameboy.getGpu().writeVideoRam0ForCore(
                        address, (address * 37 ^ address >>> 3 ^ 0x5a) & 0xff);
            }
        }

        private void runToTransfer() {
            int executed = gameboy.runMeasuredTicksUntilStop(200_000,
                    () -> transferCount > 0);
            assertTrue("SGB transfer did not reach VBlank", executed > 0 && transferCount > 0);
        }

        @Override
        public void close() {
            gameboy.closeSilently();
            eventBus.close();
        }
    }

    private static EventBusImpl sgbBus(Gameboy gameboy) throws Exception {
        Field field = Gameboy.class.getDeclaredField("sgbBus");
        field.setAccessible(true);
        return (EventBusImpl) field.get(gameboy);
    }

    private static byte[] sgbRom() {
        byte[] image = new byte[0x8000];
        image[0x100] = (byte) 0xc3;
        image[0x101] = 0;
        image[0x102] = 1;
        return image;
    }

    private static Gameboy nativeCgbSession() throws Exception {
        byte[] image = new byte[0x8000];
        image[0x100] = 0x3e; // LD A,1
        image[0x101] = 0x01;
        image[0x102] = (byte) 0xe0; // LDH (FF4D),A
        image[0x103] = 0x4d;
        image[0x104] = 0x10; // STOP + padding
        image[0x105] = 0;
        image[0x106] = (byte) 0xc3; // JP 0106
        image[0x107] = 0x06;
        image[0x108] = 0x01;
        image[0x143] = (byte) 0x80;
        return new Gameboy.GameboyConfiguration(new Rom(image))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setSupportBatterySave(false)
                .build();
    }

    private static void settleNativeDoubleSpeed(Gameboy gameboy) {
        int guard = 0;
        int stable = 0;
        while (!(stable >= 1_000
                && gameboy.getGpu().getMode() == Mode.OamSearch
                && gameboy.getGpu().getLine() < 144
                && gameboy.getGpu().getTicksInLine() == 13)
                && guard++ < 400_000) {
            gameboy.tick();
            if (gameboy.getSpeedMode().getSpeedMode() == 2
                    && gameboy.getCpu().getState() != eu.rekawek.coffeegb.core.cpu.Cpu.State.SPEED_SWITCH) {
                stable++;
            } else {
                stable = 0;
            }
        }
        assertTrue("native CGB setup did not settle", guard < 400_000);
    }
}
