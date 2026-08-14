package eu.rekawek.coffeegb.core.signal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class SignalDelayLineTest {

    @Test
    public void everyShortInputSequenceEmergesAfterExactlyTheConfiguredDelay() {
        for (int stages = 1; stages <= 8; stages++) {
            int sequenceLength = 8;
            int sequenceCount = 1 << sequenceLength;
            for (int sequence = 0; sequence < sequenceCount; sequence++) {
                SignalDelayLine delay = new SignalDelayLine(stages, false);
                for (int clock = 0; clock < sequenceLength + stages; clock++) {
                    boolean expected = clock >= stages
                            && ((sequence >>> (clock - stages)) & 1) != 0;
                    assertEquals(expected, delay.output());

                    boolean input = clock < sequenceLength && ((sequence >>> clock) & 1) != 0;
                    delay.resolve(input);
                    assertEquals(expected, delay.output());
                    delay.commit();
                }
            }
        }
    }

    @Test
    public void trueInitializationFillsEveryStage() {
        for (int stages = 1; stages <= 63; stages++) {
            SignalDelayLine delay = new SignalDelayLine(stages, true);
            assertEquals(true, delay.output());
            assertEquals((1L << stages) - 1, delay.state());
        }
    }

    @Test
    public void restoreIsValidatedAndDiscardsUncommittedState() {
        SignalDelayLine delay = new SignalDelayLine(4, false);
        delay.resolve(true);
        delay.restore(0b1010);

        assertEquals(0b1010, delay.state());
        assertEquals(true, delay.output());
        delay.commit();
        assertEquals(0b1010, delay.state());
        assertThrows(IllegalArgumentException.class, () -> delay.restore(0b1_0000));
    }

    @Test
    public void rejectsUnsupportedDepths() {
        assertThrows(IllegalArgumentException.class, () -> new SignalDelayLine(0, false));
        assertThrows(IllegalArgumentException.class, () -> new SignalDelayLine(64, false));
    }
}
