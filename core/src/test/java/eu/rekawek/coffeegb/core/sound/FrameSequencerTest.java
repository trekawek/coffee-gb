package eu.rekawek.coffeegb.core.sound;

import org.junit.Test;

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
}
