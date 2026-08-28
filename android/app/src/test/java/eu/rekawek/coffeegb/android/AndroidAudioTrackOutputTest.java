package eu.rekawek.coffeegb.android;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AndroidAudioTrackOutputTest {

    @Test
    public void standardNativeRateIsPreferredWithoutDuplicateAttempts() {
        assertCandidates(new int[]{48_000, 44_100}, 48_000);
        assertCandidates(new int[]{44_100, 48_000}, 44_100);
    }

    @Test
    public void distinctHighNativeRateIsOnlyTheCompatibilityFallback() {
        assertCandidates(new int[]{48_000, 44_100, 96_000}, 96_000);
        assertCandidates(new int[]{48_000, 44_100, 192_000}, 192_000);
    }

    @Test
    public void invalidNativeRatesUseBothStandardCandidates() {
        assertCandidates(new int[]{48_000, 44_100}, 0);
        assertCandidates(new int[]{48_000, 44_100}, -1);
    }

    @Test
    public void candidateListContainsNoDuplicateOpenAttempts() {
        for (int nativeRate : new int[]{48_000, 44_100, 96_000, 192_000, 0, -1}) {
            int[] candidates = AndroidAudioTrackOutput.Factory.candidateSampleRates(nativeRate);
            long distinct = Arrays.stream(candidates).distinct().count();
            assertEquals("duplicate candidate for native rate " + nativeRate,
                    candidates.length, distinct);
        }
    }

    @Test
    public void configuredCapacityHoldsFiveMaximumPacketsAtEveryCandidateRate() {
        for (int sampleRate : new int[]{44_100, 48_000, 96_000, 192_000}) {
            int maximumPacket = BoundedPcmQueue.maximumFrameBytes(sampleRate);
            int packetCapacity = AndroidAudioTrackOutput.Factory.packetBufferBytes(sampleRate);

            assertEquals(AndroidAudioTrackOutput.Factory.BUFFER_PACKETS * maximumPacket,
                    packetCapacity);
            assertTrue(packetCapacity >= 5 * maximumPacket);
            assertEquals(packetCapacity,
                    AndroidAudioTrackOutput.Factory.configuredBufferBytes(sampleRate, 1_024));
            assertEquals(packetCapacity + 4,
                    AndroidAudioTrackOutput.Factory.configuredBufferBytes(
                            sampleRate, packetCapacity + 4));
        }
    }

    @Test
    public void actualRateCapacityGateRejectsEitherUndersizedCapacityReport() {
        int required = AndroidAudioTrackOutput.Factory.packetBufferBytes(96_000);

        assertTrue(AndroidAudioTrackOutput.Factory.hasPacketCapacity(
                96_000, required, required));
        assertFalse(AndroidAudioTrackOutput.Factory.hasPacketCapacity(
                96_000, required - 4, required));
        assertFalse(AndroidAudioTrackOutput.Factory.hasPacketCapacity(
                96_000, required, required - 4));
    }

    @Test
    public void effectivePrimerWindowAcceptsMeasuredFivePacketBufferAndBoundsSixSlots() {
        int measuredEffectiveFrames = 4_100;
        assertTrue(AndroidAudioTrackOutput.Factory.hasEffectivePrimerWindow(
                48_000, measuredEffectiveFrames, measuredEffectiveFrames));

        int fourPacketMaximum = BoundedPcmQueue.maximumOutputFramesForPackets(
                48_000, AndroidAudioSink.PRIMER_PACKETS);
        int sixPacketMinimum = BoundedPcmQueue.minimumOutputFramesForPackets(
                48_000, BoundedPcmQueue.DEFAULT_CAPACITY);
        assertFalse(AndroidAudioTrackOutput.Factory.hasEffectivePrimerWindow(
                48_000, fourPacketMaximum - 1, fourPacketMaximum - 1));
        assertFalse(AndroidAudioTrackOutput.Factory.hasEffectivePrimerWindow(
                48_000, sixPacketMinimum + 1, sixPacketMinimum + 1));
        assertTrue("API31 can retain a larger effective reservoir with a bounded threshold",
                AndroidAudioTrackOutput.Factory.hasEffectivePrimerWindow(
                        48_000, sixPacketMinimum + 1, fourPacketMaximum));
    }

    @Test
    public void preSUsesConservativeEffectiveThresholdAndSUsesReportedThreshold() {
        assertEquals(4_100,
                AndroidAudioTrackOutput.Factory.startThresholdFrames(30, 2_000, 4_100));
        assertEquals(3_200,
                AndroidAudioTrackOutput.Factory.startThresholdFrames(31, 3_200, 4_100));
        try {
            AndroidAudioTrackOutput.Factory.startThresholdFrames(31, 4_101, 4_100);
            fail("threshold beyond effective buffer must fail closed");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void assertCandidates(int[] expected, int nativeRate) {
        assertArrayEquals(expected,
                AndroidAudioTrackOutput.Factory.candidateSampleRates(nativeRate));
    }
}
