package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.lang.reflect.Field;

/**
 * Authored native-CGB x2 regression for the cooked Sachen logical ROM lease.
 * The image is self-contained and contains no commercial cartridge data.
 *
 * <p>The scalar and batched PERFORMANCE representations are compared at single-dot boundaries,
 * at direct 63 versus 54+9 versus 9+54 partitions, after continuation, and after restoring each
 * partition endpoint. The positive replay assertion keeps this as an owner-coverage test rather
 * than a state-only no-op check.</p>
 */
public final class SachenCgbStatReplayTest {
    private static final int WARMUP_TICKS = 1_000_000;
    private static final int[] SINGLE_BUDGETS = {1, 7, 54, 55, 62, 63, 64, 97, 512, 4_096};
    private static final int[] DIRECT_63 = {63};
    private static final int[] SPLIT_54_9 = {54, 9};
    private static final int[] SPLIT_9_54 = {9, 54};
    private static final int[] CONTINUATION = {1, 7, 54, 63, 69_905};

    private record Observation(ComponentState<Gameboy> endpoint,
                               ComponentState<Gameboy> continuation,
                               ComponentState<Gameboy> restoredContinuation,
                               long endpointFrames, long continuationFrames,
                               long restoredContinuationFrames) {}

    @Test
    public void nativeCgbX2ReplayPartitionAndRestoreMatchScalar() throws Exception {
        PlayerInputHub scalarHub = new PlayerInputHub();
        PlayerInputHub batchedHub = new PlayerInputHub();
        try (PlayerInputHub.SourceHandle scalarSource = scalarHub.openSource(0);
             PlayerInputHub.SourceHandle batchedSource = batchedHub.openSource(0);
             Gameboy scalar = build(scalarHub);
             Gameboy batched = build(batchedHub)) {
            scalar.setPerformanceBatchingEnabled(false);
            batched.setPerformanceBatchingEnabled(true);
            advance(scalar, WARMUP_TICKS);
            advance(batched, WARMUP_TICKS);
            require(scalar.getSpeedMode().getSpeedMode() == 2,
                    "scalar did not enter native CGB x2");
            require(batched.getSpeedMode().getSpeedMode() == 2,
                    "batched did not enter native CGB x2");
            require(sachenPeekSafe(scalar), "scalar mapper did not expose cooked logical lease");
            require(sachenPeekSafe(batched), "batched mapper did not expose cooked logical lease");
            PerformanceStateAssertions.assertStateEquals("warmup canonical state",
                    scalar.captureStateWithoutTimeSource(), batched.captureStateWithoutTimeSource());

            ComponentState<Gameboy> scalarSeed = scalar.captureStateWithoutTimeSource();
            ComponentState<Gameboy> batchedSeed = batched.captureStateWithoutTimeSource();
            for (int budget : SINGLE_BUDGETS) {
                scalar.restoreStateSilently(scalarSeed);
                batched.restoreStateSilently(batchedSeed);
                long scalarFrames = advance(scalar, budget);
                long batchedFrames = advance(batched, budget);
                require(scalarFrames == batchedFrames,
                        "single budget frame mismatch at " + budget);
                PerformanceStateAssertions.assertStateEquals(
                        "single budget " + budget,
                        scalar.captureStateWithoutTimeSource(), batched.captureStateWithoutTimeSource());
            }

            long replayTicksBefore = batched.getPerformanceLcdcWriteReplayTicks();
            Observation scalarReference = null;
            Observation batchedReference = null;
            for (int[] partition : new int[][]{DIRECT_63, SPLIT_54_9, SPLIT_9_54}) {
                scalar.restoreStateSilently(scalarSeed);
                batched.restoreStateSilently(batchedSeed);
                long scalarFrames = advance(scalar, partition);
                long batchedFrames = advance(batched, partition);
                require(scalarFrames == batchedFrames, "partition frame mismatch");
                ComponentState<Gameboy> scalarCheckpoint = scalar.captureStateWithoutTimeSource();
                ComponentState<Gameboy> batchedCheckpoint = batched.captureStateWithoutTimeSource();
                PerformanceStateAssertions.assertStateEquals("partition endpoint",
                        scalarCheckpoint, batchedCheckpoint);

                long scalarContinuationFrames = advance(scalar, CONTINUATION);
                long batchedContinuationFrames = advance(batched, CONTINUATION);
                require(scalarContinuationFrames == batchedContinuationFrames,
                        "continuation frame mismatch");
                ComponentState<Gameboy> scalarContinuation = scalar.captureStateWithoutTimeSource();
                ComponentState<Gameboy> batchedContinuation = batched.captureStateWithoutTimeSource();
                PerformanceStateAssertions.assertStateEquals("partition continuation",
                        scalarContinuation, batchedContinuation);

                scalar.restoreStateSilently(scalarCheckpoint);
                batched.restoreStateSilently(batchedCheckpoint);
                require(advance(scalar, CONTINUATION) == scalarContinuationFrames,
                        "scalar restored frame mismatch");
                require(advance(batched, CONTINUATION) == batchedContinuationFrames,
                        "batched restored frame mismatch");
                PerformanceStateAssertions.assertStateEquals("scalar restored continuation",
                        scalarContinuation, scalar.captureStateWithoutTimeSource());
                PerformanceStateAssertions.assertStateEquals("batched restored continuation",
                        batchedContinuation, batched.captureStateWithoutTimeSource());

                Observation scalarObservation = new Observation(scalarCheckpoint, scalarContinuation,
                        scalar.captureStateWithoutTimeSource(), scalarFrames, scalarContinuationFrames,
                        scalarContinuationFrames);
                Observation batchedObservation = new Observation(batchedCheckpoint, batchedContinuation,
                        batched.captureStateWithoutTimeSource(), batchedFrames, batchedContinuationFrames,
                        batchedContinuationFrames);
                if (scalarReference == null) {
                    scalarReference = scalarObservation;
                    batchedReference = batchedObservation;
                } else {
                    assertObservationEquals("scalar direct/split partition", scalarReference,
                            scalarObservation);
                    assertObservationEquals("batched direct/split partition", batchedReference,
                            batchedObservation);
                }
            }
            long replayTicks = batched.getPerformanceLcdcWriteReplayTicks() - replayTicksBefore;
            require(replayTicks > 0, "native CGB x2 fixture did not reach LCDC replay");
            require(batched.getPerformanceEpochTicks() + batched.getPerformanceBulkTicks() > 0,
                    "native CGB x2 fixture found no positive PERFORMANCE path");
        }
    }

    private static void assertObservationEquals(String label, Observation expected,
                                                Observation actual) {
        PerformanceStateAssertions.assertStateEquals(label + " endpoint",
                expected.endpoint(), actual.endpoint());
        PerformanceStateAssertions.assertStateEquals(label + " continuation",
                expected.continuation(), actual.continuation());
        PerformanceStateAssertions.assertStateEquals(label + " restored continuation",
                expected.restoredContinuation(), actual.restoredContinuation());
        require(expected.endpointFrames() == actual.endpointFrames(),
                label + " endpoint frame count");
        require(expected.continuationFrames() == actual.continuationFrames(),
                label + " continuation frame count");
        require(expected.restoredContinuationFrames() == actual.restoredContinuationFrames(),
                label + " restored frame count");
    }

    private static Gameboy build(PlayerInputHub hub) throws Exception {
        return new Gameboy.GameboyConfiguration(new Rom(authoredImage()))
                .setHardwareProfile(HardwareProfileRegistry.CGB)
                .setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
                .setExecutionMode(ExecutionMode.PERFORMANCE)
                .setRtcTimeSource(() -> 0L)
                .setSupportBatterySave(false)
                .setPlayerInputSource(hub)
                .build();
    }

    private static boolean sachenPeekSafe(Gameboy gameboy) throws Exception {
        Field field = Gameboy.class.getDeclaredField("cartridge");
        field.setAccessible(true);
        Cartridge cartridge = (Cartridge) field.get(gameboy);
        return cartridge.getSachenMmc() != null
                && cartridge.getSachenMmc().isPerformanceRomPeekSafe();
    }

    private static long advance(Gameboy gameboy, long ticks) {
        long frames = 0;
        while (ticks > 0) {
            int chunk = (int) Math.min(ticks, gameboy.getClockSpec().controllerTicksPerFrame());
            frames += gameboy.runTicks(chunk);
            ticks -= chunk;
        }
        return frames;
    }

    private static long advance(Gameboy gameboy, int[] chunks) {
        long frames = 0;
        for (int chunk : chunks) frames += advance(gameboy, chunk);
        return frames;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static byte[] authoredImage() {
        byte[] image = new byte[0x40000];
        image[0x100] = 0;
        image[0x101] = (byte) 0xc3;
        image[0x102] = 0x50;
        image[0x103] = 0x01;
        int[] header = {0x11, 0x23, 0xf1, 0x1e, 0x01, 0x22, 0xf0, 0x00,
                0x08, 0x99, 0x78, 0x00, 0x08, 0x11, 0x9a, 0x48};
        for (int i = 0; i < header.length; i++) image[0x104 + i] = (byte) header[i];
        image[0x143] = (byte) 0x80;
        image[0x147] = (byte) 0x99;
        image[0x148] = 0x04;

        int pc = 0x150;
        int[] program = {
                0xf3, 0x31, 0xfe, 0xff,       // DI; SP=FFFE
                0x3e, 0x01, 0xea, 0x00, 0x20, // take over the cooked mapper
                0x3e, 0x01, 0xe0, 0x4d,       // prepare CGB double speed
                0x10, 0x00,                    // STOP: release the speed switch
                0x3e, 0x91, 0xe0, 0x40,       // native CGB LCDC write
                0x3e, 0xb1, 0xe0, 0x40,       // paired on->on write
                0xc3, 0x5f, 0x01              // loop at the first LCDC write
        };
        for (int value : program) image[pc++] = (byte) value;
        return image;
    }
}
