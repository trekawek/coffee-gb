package eu.rekawek.coffeegb.core.sound;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FrameSequencerTest {

    @Test
    public void powerOnAdvancesPhaseOnlyInFinalFourDividerClocksBeforeRisingEdge() {
        FrameSequencer sequencer = new FrameSequencer();

        sequencer.reset(0x0ff8, false);
        assertFalse(sequencer.isFirstHalfOfLengthPeriod());

        sequencer.reset(0x0ffc, false);
        assertTrue(sequencer.isFirstHalfOfLengthPeriod());

        sequencer.reset(0x1ff8, true);
        assertFalse(sequencer.isFirstHalfOfLengthPeriod());

        sequencer.reset(0x1ffc, true);
        assertTrue(sequencer.isFirstHalfOfLengthPeriod());
    }

    @Test
    public void stateRoundTripPreservesPoweredHighBlockedPulse() {
        FrameSequencer sequencer = new FrameSequencer();
        sequencer.reset(0x1000, false);
        var poweredHighState = sequencer.captureState();

        assertTrue(sequencer.isFirstHalfOfLengthPeriod());
        assertEquals(-1, sequencer.tick(0, true, false));
        assertFalse(sequencer.isFirstHalfOfLengthPeriod());

        sequencer.restoreState(poweredHighState);
        assertTrue(sequencer.isFirstHalfOfLengthPeriod());
        assertEquals(-1, sequencer.tick(0, true, false));
        sequencer.tick(0x1000, true, false);
        assertEquals(0, sequencer.tick(0, true, false));
    }

    @Test
    public void everyResetPhaseAndContinuationMatchesPreviousMachine() {
        FrameSequencer actual = new FrameSequencer();
        PreviousFrameSequencer expected = new PreviousFrameSequencer();

        for (boolean doubleSpeed : new boolean[]{false, true}) {
            int selectedBit = doubleSpeed ? 1 << 13 : 1 << 12;
            int[] counters = {selectedBit, 0, selectedBit, 0, selectedBit, 0};
            for (int divCounter = 0; divCounter <= 0xffff; divCounter++) {
                actual.reset(divCounter, doubleSpeed);
                expected.reset(divCounter, doubleSpeed);
                var resetState = actual.captureState();
                assertEquivalent(actual, expected, -1, doubleSpeed, divCounter);

                for (int counter : counters) {
                    int expectedStep = expected.tick(counter, true, doubleSpeed);
                    assertEquivalent(actual, expected,
                            actual.tick(counter, true, doubleSpeed), expectedStep,
                            doubleSpeed, divCounter);
                }

                actual.restoreState(resetState);
                expected.reset(divCounter, doubleSpeed);
                expected.tick(selectedBit, true, doubleSpeed);
                assertEquivalent(actual, expected,
                        actual.tick(selectedBit, true, doubleSpeed), -1,
                        doubleSpeed, divCounter);
                expected.tick(0, false, doubleSpeed);
                assertEquivalent(actual, expected,
                        actual.tick(0, false, doubleSpeed), -1,
                        doubleSpeed, divCounter);
                for (int counter : counters) {
                    int expectedStep = expected.tick(counter, true, doubleSpeed);
                    assertEquivalent(actual, expected,
                            actual.tick(counter, true, doubleSpeed), expectedStep,
                            doubleSpeed, divCounter);
                }
            }
        }
    }

    private static void assertEquivalent(FrameSequencer actual,
                                         PreviousFrameSequencer expected,
                                         int expectedStep, boolean doubleSpeed,
                                         int resetDiv) {
        assertEquivalent(actual, expected, expectedStep, expectedStep,
                doubleSpeed, resetDiv);
    }

    private static void assertEquivalent(FrameSequencer actual,
                                         PreviousFrameSequencer expected,
                                         int actualStep, int expectedStep,
                                         boolean doubleSpeed, int resetDiv) {
        if (actualStep != expectedStep
                || actual.getDebugStep() != expected.step
                || actual.isFirstHalfOfLengthPeriod() != expected.isFirstHalfOfLengthPeriod()) {
            throw new AssertionError(String.format(
                    "reset div=%04x double=%s: fired %d/%d, step %d/%d, firstHalf %s/%s",
                    resetDiv, doubleSpeed, actualStep, expectedStep,
                    actual.getDebugStep(), expected.step,
                    actual.isFirstHalfOfLengthPeriod(), expected.isFirstHalfOfLengthPeriod()));
        }
    }

    /** Exact pre-change transition machine, retained here as a differential oracle. */
    private static final class PreviousFrameSequencer {

        private int step;

        private boolean previousBit;

        private boolean skipNextEdge;

        private int tick(int divCounter, boolean apuEnabled, boolean doubleSpeed) {
            int selectedBit = doubleSpeed ? 1 << 13 : 1 << 12;
            boolean bit = (divCounter & selectedBit) != 0;
            int firedStep = -1;
            if (previousBit && !bit && apuEnabled) {
                if (skipNextEdge) {
                    skipNextEdge = false;
                } else {
                    firedStep = step;
                    step = (step + 1) & 7;
                }
            }
            previousBit = bit;
            return firedStep;
        }

        private boolean isFirstHalfOfLengthPeriod() {
            return skipNextEdge || (step & 1) == 1;
        }

        private void reset(int divCounter, boolean doubleSpeed) {
            int selectedBit = doubleSpeed ? 1 << 13 : 1 << 12;
            int phase = divCounter & (selectedBit * 2 - 1);
            step = phase >= selectedBit - 4 && phase < selectedBit ? 1 : 0;
            previousBit = (divCounter & selectedBit) != 0;
            skipNextEdge = previousBit;
        }
    }
}
