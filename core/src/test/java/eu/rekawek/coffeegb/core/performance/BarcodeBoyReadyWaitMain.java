package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.Gameboy;

import java.util.Arrays;

/**
 * One-session, bounded cost driver for a selected production classpath.
 *
 * <p>Usage: {@code <profile> <persistent|finite> <warmupTicks> <measuredTicks> [batchTicks]}.
 * The caller runs this main once per classpath and keeps the JSON row for a later paired
 * measurement. Validation and scan injection are outside the measured window.</p>
 */
public final class BarcodeBoyReadyWaitMain {
    private static final long WAIT_SEEK_LIMIT = 100_000L;
    private static final int DEFAULT_BATCH_TICKS = 4_096;

    private BarcodeBoyReadyWaitMain() {
    }

    public static void main(String[] args) {
        int exitCode = 0;
        try {
            run(args);
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            exitCode = 1;
        } finally {
            // EventBus has non-daemon lifecycle resources in some production profiles.
            System.exit(exitCode);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length < 4 || args.length > 5) {
            throw new IllegalArgumentException(
                    "usage: profile persistent|finite warmupTicks measuredTicks [batchTicks]");
        }
        BarcodeBoyReadyWaitFixture.Profile profile =
                BarcodeBoyReadyWaitFixture.Profile.valueOf(args[0]);
        String mode = args[1].toLowerCase(java.util.Locale.ROOT);
        if (!mode.equals("persistent") && !mode.equals("finite")) {
            throw new IllegalArgumentException("mode must be persistent or finite: " + args[1]);
        }
        long warmupTicks = positiveLong(args[2], "warmupTicks");
        long measuredTicks = positiveLong(args[3], "measuredTicks");
        long batchTicks = args.length == 5
                ? positiveLong(args[4], "batchTicks") : DEFAULT_BATCH_TICKS;

        // This is deliberately one selected build/session. A candidate scalar reference remains
        // a JUnit differential session; this driver leaves candidate batching at its default.
        try (BarcodeBoyReadyWaitFixture.Session session =
                     BarcodeBoyReadyWaitFixture.Session.open(profile, true)) {
            BarcodeBoyReadyWaitFixture.requireExternalWait(session,
                    Math.max(WAIT_SEEK_LIMIT, warmupTicks + WAIT_SEEK_LIMIT));
            validateWait("pre-warmup", session);

            Window warmup = runBatches(session.gameboy, warmupTicks, batchTicks);
            validateWait("post-warmup", session);

            boolean finite = mode.equals("finite");
            if (finite) {
                // scan() is called between two returned runTicks batches, before timing starts.
                session.endpoint.scan(BarcodeBoyReadyWaitFixture.BARCODE);
                if (!session.endpoint.isScanPending()
                        || session.endpoint.captureRuntimeState().copyPending() == null) {
                    throw new AssertionError("scan was not queued at the owner boundary");
                }
            }

            BarcodeBoyReadyWaitFixture.resetPerformanceCounters(session.gameboy);
            long measuredStart = System.nanoTime();
            Window measured = runBatches(session.gameboy, measuredTicks, batchTicks);
            long measuredNanos = System.nanoTime() - measuredStart;

            if (finite) {
                validateCompleted("post-measurement", session);
            } else {
                validateWait("post-measurement", session);
            }
            printResult(profile, mode, session, warmup, measured, measuredNanos,
                    warmupTicks, measuredTicks, batchTicks);
        }
    }

    private static void validateWait(String label, BarcodeBoyReadyWaitFixture.Session session) {
        BarcodeBoyReadyWaitFixture.Observation observation =
                BarcodeBoyReadyWaitFixture.observe(session);
        if (observation.phase() != BarcodeBoyReadyWaitFixture.WAIT_PHASE
                || !observation.externalTransfer()
                || observation.scanPending()
                || observation.pending() != null
                || !observation.transferArmed()
                || !Arrays.equals(observation.payload(),
                new int[BarcodeBoyReadyWaitFixture.PAYLOAD_LENGTH])) {
            throw new AssertionError(label + " did not retain the external wait: " + observation);
        }
        if (!Arrays.equals(observation.handshakeReply(),
                BarcodeBoyReadyWaitFixture.expectedHandshakeReply())) {
            throw new AssertionError(label + " handshake reply mismatch: "
                    + Arrays.toString(observation.handshakeReply()));
        }
        int expectedSpeed = session.profile.doubleSpeed ? 2 : 1;
        if (observation.speedMode() != expectedSpeed) {
            throw new AssertionError(label + " speed mismatch expected=" + expectedSpeed
                    + " actual=" + observation.speedMode());
        }
    }

    private static void validateCompleted(String label,
                                          BarcodeBoyReadyWaitFixture.Session session) {
        BarcodeBoyReadyWaitFixture.Observation observation =
                BarcodeBoyReadyWaitFixture.observe(session);
        if (observation.phase() != BarcodeBoyReadyWaitFixture.COMPLETE_PHASE
                || observation.externalTransfer()
                || observation.scanPending()
                || observation.pending() != null
                || !Arrays.equals(observation.payload(), BarcodeBoyReadyWaitFixture.expectedPayload())
                || !Arrays.equals(observation.handshakeReply(),
                BarcodeBoyReadyWaitFixture.expectedHandshakeReply())
                || !Arrays.equals(observation.followupReply(),
                BarcodeBoyReadyWaitFixture.expectedHandshakeReply())) {
            throw new AssertionError(label + " finite scan mismatch: " + observation);
        }
    }

    private static Window runBatches(Gameboy gameboy, long requestedTicks, long batchTicks) {
        long remaining = requestedTicks;
        long frames = 0;
        long ticks = 0;
        while (remaining > 0) {
            long step = Math.min(remaining, batchTicks);
            frames += gameboy.runTicks(step);
            ticks += step;
            remaining -= step;
        }
        return new Window(ticks, frames);
    }

    private static void printResult(BarcodeBoyReadyWaitFixture.Profile profile, String mode,
                                    BarcodeBoyReadyWaitFixture.Session session,
                                    Window warmup, Window measured, long measuredNanos,
                                    long requestedWarmup, long requestedMeasured, long batchTicks) {
        long epochTicks = BarcodeBoyReadyWaitFixture.performanceCounter(
                session.gameboy, "getPerformanceEpochTicks");
        long bulkTicks = BarcodeBoyReadyWaitFixture.performanceCounter(
                session.gameboy, "getPerformanceBulkTicks");
        long epochCount = BarcodeBoyReadyWaitFixture.performanceCounter(
                session.gameboy, "getPerformanceEpochCount");
        long bulkSpanCount = BarcodeBoyReadyWaitFixture.performanceCounter(
                session.gameboy, "getPerformanceBulkSpanCount");
        int phase = session.gameboy.getAddressSpace().getByte(
                BarcodeBoyReadyWaitFixture.PHASE_ADDRESS);
        System.out.println("{"
                + "\"profile\":\"" + profile + "\""
                + ",\"mode\":\"" + mode + "\""
                + ",\"batchingDefault\":true"
                + ",\"warmupTicksRequested\":" + requestedWarmup
                + ",\"warmupTicks\":" + warmup.ticks()
                + ",\"warmupFrames\":" + warmup.frames()
                + ",\"measuredTicksRequested\":" + requestedMeasured
                + ",\"measuredTicks\":" + measured.ticks()
                + ",\"measuredFrames\":" + measured.frames()
                + ",\"batchTicks\":" + batchTicks
                + ",\"measuredNanos\":" + measuredNanos
                + ",\"scanCount\":" + (mode.equals("finite") ? 1 : 0)
                + ",\"phase\":" + phase
                + ",\"externalTransfer\":" + session.gameboy.isExternalClockTransferActive()
                + ",\"scanPending\":" + session.endpoint.isScanPending()
                + ",\"epochTicks\":" + epochTicks
                + ",\"bulkTicks\":" + bulkTicks
                + ",\"epochCount\":" + epochCount
                + ",\"bulkSpanCount\":" + bulkSpanCount
                + "}");
    }

    private static long positiveLong(String text, String name) {
        long value;
        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a positive integer: " + text, e);
        }
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive: " + text);
        }
        return value;
    }

    private record Window(long ticks, long frames) {
    }
}
