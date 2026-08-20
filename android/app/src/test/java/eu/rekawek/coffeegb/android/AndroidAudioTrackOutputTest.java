package eu.rekawek.coffeegb.android;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class AndroidAudioTrackOutputTest {

    @Test
    public void standardNativeRateIsTriedOnlyOnceAfterPreferredRates() {
        assertCandidates(new int[]{48_000, 44_100}, 48_000);
        assertCandidates(new int[]{48_000, 44_100}, 44_100);
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

    private static void assertCandidates(int[] expected, int nativeRate) {
        assertArrayEquals(expected,
                AndroidAudioTrackOutput.Factory.candidateSampleRates(nativeRate));
    }
}
