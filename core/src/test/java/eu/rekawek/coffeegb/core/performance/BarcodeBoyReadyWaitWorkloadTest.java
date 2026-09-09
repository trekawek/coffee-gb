package eu.rekawek.coffeegb.core.performance;

import org.junit.Test;

import static eu.rekawek.coffeegb.core.performance.BarcodeBoyReadyWaitFixture.BARCODE;
import static eu.rekawek.coffeegb.core.performance.BarcodeBoyReadyWaitFixture.COMPLETE_PHASE;
import static eu.rekawek.coffeegb.core.performance.BarcodeBoyReadyWaitFixture.HANDSHAKE_REPLY_ADDRESS;
import static eu.rekawek.coffeegb.core.performance.BarcodeBoyReadyWaitFixture.PAYLOAD_ADDRESS;
import static eu.rekawek.coffeegb.core.performance.BarcodeBoyReadyWaitFixture.PAYLOAD_LENGTH;
import static eu.rekawek.coffeegb.core.performance.BarcodeBoyReadyWaitFixture.PHASE_ADDRESS;
import static eu.rekawek.coffeegb.core.performance.BarcodeBoyReadyWaitFixture.WAIT_PHASE;
import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Focused authored correctness proof for the real Barcode Boy external-clock fence. */
public final class BarcodeBoyReadyWaitWorkloadTest {

    @Test
    public void persistentExternalWaitRemainsScalarAndStateEqual() throws Exception {
        for (BarcodeBoyReadyWaitFixture.Profile profile : BarcodeBoyReadyWaitFixture.Profile.values()) {
            try (BarcodeBoyReadyWaitFixture.Session scalar =
                         BarcodeBoyReadyWaitFixture.Session.open(profile, false);
                 BarcodeBoyReadyWaitFixture.Session candidate =
                         BarcodeBoyReadyWaitFixture.Session.open(profile, true)) {
                BarcodeBoyReadyWaitFixture.requireExternalWait(scalar, 100_000);
                BarcodeBoyReadyWaitFixture.requireExternalWait(candidate, 100_000);
                assertEquivalent(profile + " wait entry", scalar, candidate);
                assertEquals(profile + " speed after authored setup", profile.doubleSpeed ? 2 : 1,
                        candidate.gameboy.getSpeedMode().getSpeedMode());
                assertEquals(profile + " wait marker", WAIT_PHASE,
                        scalar.gameboy.getAddressSpace().getByte(PHASE_ADDRESS));

                BarcodeBoyReadyWaitFixture.resetPerformanceCounters(scalar.gameboy);
                BarcodeBoyReadyWaitFixture.resetPerformanceCounters(candidate.gameboy);
                long window = 32_768;
                assertEquals(profile + " scalar frame count", scalar.gameboy.runTicks(window),
                        candidate.gameboy.runTicks(window));
                assertEquivalent(profile + " persistent wait", scalar, candidate);
                assertEquals(profile + " wait phase", WAIT_PHASE,
                        candidate.gameboy.getAddressSpace().getByte(PHASE_ADDRESS));
                assertTrue(profile + " external transfer remains armed",
                        candidate.gameboy.isExternalClockTransferActive());
                assertFalse(profile + " scan remains absent", candidate.endpoint.isScanPending());
                assertNull(profile + " endpoint pending", candidate.endpoint.captureRuntimeState().copyPending());
                assertEquals(profile + " candidate epoch remains scalar during retained wait", 0L,
                        BarcodeBoyReadyWaitFixture.performanceCounter(
                                candidate.gameboy, "getPerformanceEpochTicks"));
                assertEquals(profile + " candidate bulk remains scalar during retained wait", 0L,
                        BarcodeBoyReadyWaitFixture.performanceCounter(
                                candidate.gameboy, "getPerformanceBulkTicks"));
                assertArrayEquals(profile + " wait payload remains zero", new int[PAYLOAD_LENGTH],
                        BarcodeBoyReadyWaitFixture.read(candidate.gameboy.getAddressSpace(),
                                PAYLOAD_ADDRESS, PAYLOAD_LENGTH));
            }
        }
    }

    @Test
    public void finiteScanRetainsFenceAndCompletesBothFrames() throws Exception {
        for (BarcodeBoyReadyWaitFixture.Profile profile : BarcodeBoyReadyWaitFixture.Profile.values()) {
            try (BarcodeBoyReadyWaitFixture.Session scalar =
                         BarcodeBoyReadyWaitFixture.Session.open(profile, false);
                 BarcodeBoyReadyWaitFixture.Session candidate =
                         BarcodeBoyReadyWaitFixture.Session.open(profile, true)) {
                BarcodeBoyReadyWaitFixture.requireExternalWait(scalar, 100_000);
                BarcodeBoyReadyWaitFixture.requireExternalWait(candidate, 100_000);
                assertEquivalent(profile + " scan entry", scalar, candidate);
                assertEquals(profile + " runtime speed", profile.doubleSpeed ? 2 : 1,
                        candidate.gameboy.getSpeedMode().getSpeedMode());

                // The injection happens only after both runTicks calls have returned with SC bit
                // 7 visible. This is the owner-boundary admission point; no scheduler observer or
                // retirement hook is involved.
                scalar.endpoint.scan(BARCODE);
                candidate.endpoint.scan(BARCODE);
                BarcodeBoyReadyWaitFixture.compareEndpointRuntime(
                        profile + " queued scan", scalar, candidate);
                assertTrue(profile + " pending scan", candidate.endpoint.isScanPending());
                assertEquals(profile + " pending frame length", 30,
                        candidate.endpoint.captureRuntimeState().copyPending().length);

                scalar.gameboy.runTicks(1);
                candidate.gameboy.runTicks(1);
                assertEquivalent(profile + " scan admission", scalar, candidate);
                BarcodeBoyReadyWaitFixture.compareEndpointRuntime(
                        profile + " admitted scan", scalar, candidate);
                assertNull(profile + " frame admitted", candidate.endpoint.captureRuntimeState().copyPending());

                long elapsed = 1;
                final long maxTicks = profile.doubleSpeed ? 150_000 : 275_000;
                while (elapsed < maxTicks
                        && scalar.gameboy.getAddressSpace().getByte(PHASE_ADDRESS) != COMPLETE_PHASE) {
                    long step = Math.min(4_096, maxTicks - elapsed);
                    assertEquals(profile + " finite frame count at " + elapsed,
                            scalar.gameboy.runTicks(step), candidate.gameboy.runTicks(step));
                    elapsed += step;
                    assertEquivalent(profile + " finite window at " + elapsed, scalar, candidate);
                }

                assertEquals(profile + " scalar completion phase", COMPLETE_PHASE,
                        scalar.gameboy.getAddressSpace().getByte(PHASE_ADDRESS));
                assertEquals(profile + " candidate completion phase", COMPLETE_PHASE,
                        candidate.gameboy.getAddressSpace().getByte(PHASE_ADDRESS));
                assertFalse(profile + " scan completed", candidate.endpoint.isScanPending());
                assertFalse(profile + " external transfer completed", candidate.gameboy.isExternalClockTransferActive());
                assertArrayEquals(profile + " scanner payload", expectedPayload(),
                        BarcodeBoyReadyWaitFixture.read(candidate.gameboy.getAddressSpace(),
                                PAYLOAD_ADDRESS, PAYLOAD_LENGTH));
                assertArrayEquals(profile + " first handshake reply", expectedHandshakeReply(),
                        BarcodeBoyReadyWaitFixture.read(candidate.gameboy.getAddressSpace(),
                                HANDSHAKE_REPLY_ADDRESS, 4));
                assertArrayEquals(profile + " follow-up handshake reply", expectedHandshakeReply(),
                        BarcodeBoyReadyWaitFixture.read(candidate.gameboy.getAddressSpace(), 0xc090, 4));
                BarcodeBoyReadyWaitFixture.compareEndpointRuntime(
                        profile + " completed endpoint", scalar, candidate);
            }
        }
    }

    private static int[] expectedHandshakeReply() {
        return BarcodeBoyReadyWaitFixture.expectedHandshakeReply();
    }

    private static int[] expectedPayload() {
        return BarcodeBoyReadyWaitFixture.expectedPayload();
    }

    private static void assertEquivalent(String label,
                                         BarcodeBoyReadyWaitFixture.Session expected,
                                         BarcodeBoyReadyWaitFixture.Session actual) {
        assertStateEquals(label + " canonical Gameboy state",
                expected.gameboy.captureStateWithoutTimeSource(),
                actual.gameboy.captureStateWithoutTimeSource());
        assertStateEquals(label + " CPU state", expected.gameboy.getCpu().captureState(),
                actual.gameboy.getCpu().captureState());
        assertEquals(label + " SB", expected.gameboy.getAddressSpace().getByte(0xff01),
                actual.gameboy.getAddressSpace().getByte(0xff01));
        assertEquals(label + " SC", expected.gameboy.getAddressSpace().getByte(0xff02),
                actual.gameboy.getAddressSpace().getByte(0xff02));
        assertEquals(label + " external transfer", expected.gameboy.isExternalClockTransferActive(),
                actual.gameboy.isExternalClockTransferActive());
        BarcodeBoyReadyWaitFixture.compareEndpointRuntime(label, expected, actual);
        assertArrayEquals(label + " payload", expectedObservation(expected).payload(),
                expectedObservation(actual).payload());
    }

    private static BarcodeBoyReadyWaitFixture.Observation expectedObservation(
            BarcodeBoyReadyWaitFixture.Session session) {
        return BarcodeBoyReadyWaitFixture.observe(session);
    }
}
