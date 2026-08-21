package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

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
            scalar.getGpu().setPerformanceObservationBlocked(false);
            scalar.getGpu().setPerformanceScanlineEnabled(true);
            assertEquals("scalar PixelTransfer output tail was bulk-skipped", 0,
                    scalar.getGpu().performanceQuietSpanLimit());
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
}
