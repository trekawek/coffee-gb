package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.performance.PerformanceDiagnostics;
import eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions;
import eu.rekawek.coffeegb.core.state.ComponentState;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression for the native CGB x2 terminal FF40 journal seam.
 *
 * The image is a small repository-authored fixture: it initializes a deterministic VRAM and
 * palette pattern, then repeatedly writes FF40 on->on while the CPU runs from ROM bank 1.  The
 * bytes are kept here so this test has no dependency on an external ROM file or a commercial cart.
 * The checkpoint is the previously diagnosed line 141, dot 246 state.  A 63-dot caller must
 * publish the pending FF40 before the final GPU dot, so 63, 54+9, and 9+54 remain canonical.
 */
public final class PerformanceFf40TerminalSeamTest {
    private static final long WARMUP_TICKS = 1_000_000L;
    private static final int CONTROLLER_TICKS_PER_FRAME = 69_905;
    private static final int[] PARTITIONS = {1, 7, 54, 63, 64, 97, 512, 4_096, 69_905};
    private static final int[] CONTINUATION = {1, 7, 54, 63, 69_905};
    private static final String[] SEQUENCES = {"63", "54,9", "9,54"};

    @Test
    public void terminalHintProvidesSteadyLcdcReplayCoverage() throws Exception {
        PlayerInputHub hub = new PlayerInputHub();
        try (PlayerInputHub.SourceHandle ignored = hub.openSource(0);
                Gameboy gameboy = configuration(hub)) {
            gameboy.setPerformanceBatchingEnabled(true);
            advance(gameboy, WARMUP_TICKS);
            assertEquals("measured window must be native CGB x2", 2,
                    gameboy.getSpeedMode().getSpeedMode());
            assertEquals("controller chunk contract", CONTROLLER_TICKS_PER_FRAME,
                    gameboy.getClockSpec().controllerTicksPerFrame());
            long epochBefore = gameboy.getPerformanceEpochTicks();
            long replayBefore = gameboy.getPerformanceLcdcWriteReplayTicks();
            long measuredTicks = (long) CONTROLLER_TICKS_PER_FRAME * 8;
            long measuredFrames = advance(gameboy, measuredTicks);
            long epochTicks = gameboy.getPerformanceEpochTicks() - epochBefore;
            long replayTicks = gameboy.getPerformanceLcdcWriteReplayTicks() - replayBefore;
            assertTrue("measured window did not advance epochs", epochTicks > 0);
            assertTrue("measured window did not produce frames", measuredFrames > 0);
            // The no-hint combined overlay observed about 30% replay duty; the terminal
            // retry hint raises steady LCDC replay coverage to about 80%. Keep this as a
            // broad route-coverage threshold rather than pinning strategy counters.
            assertTrue("terminal retry route lacks steady LCDC replay coverage: replay="
                            + replayTicks + " epoch=" + epochTicks,
                    replayTicks * 10 >= epochTicks * 7);
        }
    }

    @Test
    public void terminalOnWriteCallerPartitionsRemainCanonicalAndRestorable() throws Exception {
        Verification verification = verifyVariant();
        Step direct = verification.steps.get("63");
        assertEquals("FAST_FORWARD checkpoint line", 141, verification.startLine);
        assertEquals("FAST_FORWARD checkpoint dot", 246, verification.startDot);
        assertEquals("terminal witness line", 141, direct.line);
        assertEquals("terminal witness dot", 309, direct.dot);
        assertTrue("terminal FF40 route did not publish a PPU replay dot",
                direct.ppuReplayDelta > 0);
        assertTrue("terminal FF40 route did not publish the paired STAT replay dot",
                direct.statReplayDelta > 0);
        assertEquals("terminal GPU/STAT replay accounting", direct.ppuReplayDelta,
                direct.statReplayDelta);
        // This is the exact positive witness from the diagnosed owner seam, rather than a
        // generic 'some batching happened' assertion.  The route records one canonical terminal
        // dot in each replay stream while the rest of this 63-dot caller remains unchanged.
        assertEquals("terminal FF40 PPU replay witness", 7L, direct.ppuReplayDelta);
        assertEquals("terminal FF40 STAT replay witness", 7L, direct.statReplayDelta);
        assertEquals("terminal route must keep the controller clock", CONTROLLER_TICKS_PER_FRAME,
                verification.controllerTicksPerFrame);
    }

    private static Verification verifyVariant() throws Exception {
        PlayerInputHub hub = new PlayerInputHub();
        try (PlayerInputHub.SourceHandle ignored = hub.openSource(0);
                Gameboy gameboy = configuration(hub)) {
            gameboy.setPerformanceBatchingEnabled(true);
            PerformanceDiagnostics diagnostics = new PerformanceDiagnostics(4_096);
            gameboy.setPerformanceDiagnostics(diagnostics);
            ComponentState<Gameboy> start = checkpoint(gameboy);
            int startLine = gameboy.getGpu().getLine();
            int startDot = gameboy.getGpu().getTicksInLine();
            assertEquals("controller tick contract", CONTROLLER_TICKS_PER_FRAME,
                    gameboy.getClockSpec().controllerTicksPerFrame());
            Map<String, Step> steps = new LinkedHashMap<>();
            for (String sequence : SEQUENCES) {
                gameboy.restoreStateSilently(start);
                PerformanceDiagnostics.Snapshot before = diagnostics.snapshot();
                long endpointFrames = advance(gameboy, parse(sequence));
                ComponentState<Gameboy> endpoint = gameboy.captureStateWithoutTimeSource();
                int endpointLine = gameboy.getGpu().getLine();
                int endpointDot = gameboy.getGpu().getTicksInLine();
                PerformanceDiagnostics.Snapshot after = diagnostics.snapshot();
                long ppuBefore = before.subsystemTicks().get(
                        PerformanceDiagnostics.Subsystem.PPU_REPLAY);
                long statBefore = before.subsystemTicks().get(
                        PerformanceDiagnostics.Subsystem.STAT_REPLAY);
                long ppuAfter = after.subsystemTicks().get(
                        PerformanceDiagnostics.Subsystem.PPU_REPLAY);
                long statAfter = after.subsystemTicks().get(
                        PerformanceDiagnostics.Subsystem.STAT_REPLAY);
                long continuationFrames = advance(gameboy, CONTINUATION);
                ComponentState<Gameboy> continuation = gameboy.captureStateWithoutTimeSource();
                gameboy.restoreStateSilently(endpoint);
                PerformanceStateAssertions.assertStateEquals(
                        sequence + " immediate endpoint restore", endpoint,
                        gameboy.captureStateWithoutTimeSource());
                long restoredContinuationFrames = advance(gameboy, CONTINUATION);
                ComponentState<Gameboy> restoredContinuation =
                        gameboy.captureStateWithoutTimeSource();
                PerformanceStateAssertions.assertStateEquals(
                        sequence + " continuation after endpoint restore", continuation,
                        restoredContinuation);
                steps.put(sequence, new Step(endpoint, continuation, restoredContinuation,
                        endpointFrames, continuationFrames, restoredContinuationFrames,
                        endpointLine, endpointDot, ppuAfter - ppuBefore,
                        statAfter - statBefore));
            }
            Step direct = steps.get("63");
            for (String sequence : new String[]{"54,9", "9,54"}) {
                Step partitioned = steps.get(sequence);
                PerformanceStateAssertions.assertStateEquals(
                        "63 versus " + sequence + " endpoint", direct.endpoint,
                        partitioned.endpoint);
                PerformanceStateAssertions.assertStateEquals(
                        "63 versus " + sequence + " continuation", direct.continuation,
                        partitioned.continuation);
                PerformanceStateAssertions.assertStateEquals(
                        "63 versus " + sequence + " restored continuation",
                        direct.restoredContinuation, partitioned.restoredContinuation);
                assertEquals(sequence + " endpoint frame accounting", direct.endpointFrames,
                        partitioned.endpointFrames);
                assertEquals(sequence + " continuation frame accounting", direct.continuationFrames,
                        partitioned.continuationFrames);
                assertEquals(sequence + " restored frame accounting",
                        direct.restoredContinuationFrames, partitioned.restoredContinuationFrames);
            }
            return new Verification(startLine, startDot,
                    gameboy.getClockSpec().controllerTicksPerFrame(), steps);
        }
    }

    private static Gameboy configuration(PlayerInputHub hub) throws Exception {
        return new Gameboy.GameboyConfiguration(new Rom(authoredLcdcCgbX2V2()))
                .setHardwareProfile(HardwareProfileRegistry.CGB)
                .setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setRtcTimeSource(() -> 0L)
                .setSupportBatterySave(false)
                .setPlayerInputSource(hub)
                .build();
    }

    private static ComponentState<Gameboy> checkpoint(Gameboy gameboy) throws Exception {
        advance(gameboy, WARMUP_TICKS);
        assertEquals("FAST_FORWARD enters native CGB x2", 2,
                gameboy.getSpeedMode().getSpeedMode());
        int checkpoint = 0;
        for (int repeat = 0; repeat < 12; repeat++) {
            for (int partition : PARTITIONS) {
                advance(gameboy, partition);
                ComponentState<Gameboy> state = gameboy.captureStateWithoutTimeSource();
                if (checkpoint == 15) {
                    // Preserve the exact diagnosed start: restore after the normal continuation
                    // and then re-run that continuation from the checkpoint.
                    advance(gameboy, CONTINUATION);
                    gameboy.restoreStateSilently(state);
                    advance(gameboy, CONTINUATION);
                    return gameboy.captureStateWithoutTimeSource();
                }
                advance(gameboy, CONTINUATION);
                gameboy.restoreStateSilently(state);
                advance(gameboy, CONTINUATION);
                checkpoint++;
            }
        }
        throw new AssertionError("checkpoint 15 was not reached");
    }

    private static long advance(Gameboy gameboy, long ticks) {
        long frames = 0;
        while (ticks > 0) {
            int count = (int) Math.min(ticks, CONTROLLER_TICKS_PER_FRAME);
            frames += gameboy.runTicks(count);
            ticks -= count;
        }
        return frames;
    }

    private static long advance(Gameboy gameboy, int[] chunks) {
        long frames = 0;
        for (int chunk : chunks) frames += advance(gameboy, chunk);
        return frames;
    }

    private static int[] parse(String sequence) {
        String[] parts = sequence.split(",");
        int[] chunks = new int[parts.length];
        for (int i = 0; i < parts.length; i++) chunks[i] = Integer.parseInt(parts[i]);
        return chunks;
    }

    private record Step(ComponentState<Gameboy> endpoint, ComponentState<Gameboy> continuation,
                        ComponentState<Gameboy> restoredContinuation, long endpointFrames,
                        long continuationFrames, long restoredContinuationFrames, int line, int dot,
                        long ppuReplayDelta, long statReplayDelta) {}

    private record Verification(int startLine, int startDot, int controllerTicksPerFrame,
                                Map<String, Step> steps) {}

    private static byte[] authoredLcdcCgbX2V2() {
        byte[] image = new byte[0x8000];
        put(image, 0x100,
        "c3004000ceed6666cc0d000b03730083000c000d0008111f8889000edccc6ee6ddddd999bbbb67636e0eecccdddc999f" +
        "bbb9333e50455246204c4344435f5752495445800000000000000000001a0000f331feff3e00eaffff3e00e00f3e01e0" +
        "4d10003e91e0403eb1e040c36301"
        );
        put(image, 0x4000,
        "f33e11e0403e00e04f3eaaea00803e00ea01803e55ea02803e00ea03803eaaea04803e00ea05803e55ea06803e00ea07" +
        "803eaaea08803e00ea09803e55ea0a803e00ea0b803eaaea0c803e00ea0d803e55ea0e803e00ea0f80210098010008af" +
        "220b78b1c25f403e10ea00c03e08ea01c03e00ea02c03e00ea03c03e28ea04c03e14ea05c03e00ea06c03e00ea07c03e" +
        "40ea08c03e20ea09c03e00ea0ac03e00ea0bc03e58ea0cc03e2cea0dc03e00ea0ec03e00ea0fc03e70ea10c03e38ea11" +
        "c03e00ea12c03e00ea13c03e10ea14c03e44ea15c03e00ea16c03e00ea17c03e28ea18c03e50ea19c03e00ea1ac03e00" +
        "ea1bc03e40ea1cc03e5cea1dc03e00ea1ec03e00ea1fc03e58ea20c03e68ea21c03e00ea22c03e00ea23c03e70ea24c0" +
        "3e74ea25c03e00ea26c03e00ea27c03e10ea28c03e08ea29c03e00ea2ac03e00ea2bc03e28ea2cc03e14ea2dc03e00ea" +
        "2ec03e00ea2fc03e40ea30c03e20ea31c03e00ea32c03e00ea33c03e58ea34c03e2cea35c03e00ea36c03e00ea37c03e" +
        "70ea38c03e38ea39c03e00ea3ac03e00ea3bc03e10ea3cc03e44ea3dc03e00ea3ec03e00ea3fc03e28ea40c03e50ea41" +
        "c03e00ea42c03e00ea43c03e40ea44c03e5cea45c03e00ea46c03e00ea47c03e58ea48c03e68ea49c03e00ea4ac03e00" +
        "ea4bc03e70ea4cc03e74ea4dc03e00ea4ec03e00ea4fc03e10ea50c03e08ea51c03e00ea52c03e00ea53c03e28ea54c0" +
        "3e14ea55c03e00ea56c03e00ea57c03e40ea58c03e20ea59c03e00ea5ac03e00ea5bc03e58ea5cc03e2cea5dc03e00ea" +
        "5ec03e00ea5fc03e70ea60c03e38ea61c03e00ea62c03e00ea63c03e10ea64c03e44ea65c03e00ea66c03e00ea67c03e" +
        "28ea68c03e50ea69c03e00ea6ac03e00ea6bc03e40ea6cc03e5cea6dc03e00ea6ec03e00ea6fc03e58ea70c03e68ea71" +
        "c03e00ea72c03e00ea73c03e70ea74c03e74ea75c03e00ea76c03e00ea77c03e10ea78c03e08ea79c03e00ea7ac03e00" +
        "ea7bc03e28ea7cc03e14ea7dc03e00ea7ec03e00ea7fc03e40ea80c03e20ea81c03e00ea82c03e00ea83c03e58ea84c0" +
        "3e2cea85c03e00ea86c03e00ea87c03e70ea88c03e38ea89c03e00ea8ac03e00ea8bc03e10ea8cc03e44ea8dc03e00ea" +
        "8ec03e00ea8fc03e28ea90c03e50ea91c03e00ea92c03e00ea93c03e40ea94c03e5cea95c03e00ea96c03e00ea97c03e" +
        "58ea98c03e68ea99c03e00ea9ac03e00ea9bc03e70ea9cc03e74ea9dc03e00ea9ec03e00ea9fc03ee4e0473ee4e0483e" +
        "e4e0493e80e0683effe0693e7fe0693e00e0693e7ce0693ee0e0693e03e0693e00e0693e00e0693e80e06a3effe06b3e" +
        "7fe06b3e00e06b3e7ce06b3ee0e06b3e03e06b3e00e06b3e00e06b3e80e0263e77e0243e11e0253e80e0113ef0e0123e" +
        "d6e0133e86e0143e30e04a3e50e04b3e93e040c350010000"
        );
        return image;
    }

    private static void put(byte[] image, int offset, String... parts) {
        StringBuilder hex = new StringBuilder();
        for (String part : parts) hex.append(part);
        if ((hex.length() & 1) != 0) throw new AssertionError("odd fixture hex length");
        for (int i = 0; i < hex.length(); i += 2) {
            image[offset + i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
    }
}
