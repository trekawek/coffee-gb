package eu.rekawek.coffeegb.core.sound;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Exhaustive differential proof for the gated down-counter terminal-count signal. */
public class LengthCounterGateSignalTest {

    private static final int[] EXTREME_VALUES = {
            Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -65537, -65536, -257,
            -1, 0, 1, 2, 63, 64, 255, 256, 65535, 65536,
            Integer.MAX_VALUE - 1, Integer.MAX_VALUE
    };

    private static final int[] FULL_LENGTHS = {
            Integer.MIN_VALUE, -1, 0, 1, 2, 64, 256, Integer.MAX_VALUE
    };

    @Test
    public void normalClockMatchesTheImperativeCounterForAllBoundaries() {
        for (boolean enabled : new boolean[]{false, true}) {
            for (int length = -1024; length <= 1024; length++) {
                assertClock(length, enabled);
            }
            for (int length : EXTREME_VALUES) {
                assertClock(length, enabled);
            }
        }
    }

    @Test
    public void zeroLoadMuxMatchesForEverySignedLowWordAndExtremes() {
        for (int fullLength : FULL_LENGTHS) {
            FrameSequencer sequencer = secondHalfSequencer();
            LengthCounter production = new LengthCounter(fullLength, sequencer);
            for (int length = Short.MIN_VALUE; length <= Short.MAX_VALUE; length++) {
                production.setLength(length);
                assertEquals(referenceLoad(fullLength, length), production.getValue());
            }
            for (int length : EXTREME_VALUES) {
                production.setLength(length);
                assertEquals(referenceLoad(fullLength, length), production.getValue());
            }
        }
    }

    @Test
    public void nr4TransitionMatchesAcrossCounterAndInputBoundaries() {
        int[] values = {
                Integer.MIN_VALUE, Integer.MIN_VALUE + 0x40, Integer.MIN_VALUE + 0x80,
                Integer.MIN_VALUE + 0xc0, -65537, -65536, -193, -192, -129, -128,
                -65, -64, -1, 0, 0x40, 0x80, 0xc0, 0x100, 0x140, 0x180,
                0x1c0, 65535, 65536, Integer.MAX_VALUE
        };
        for (int fullLength : FULL_LENGTHS) {
            for (int length = -260; length <= 260; length++) {
                for (boolean enabled : new boolean[]{false, true}) {
                    for (boolean firstHalf : new boolean[]{false, true}) {
                        for (int value : values) {
                            assertNr4(fullLength, length, enabled, firstHalf, value);
                        }
                    }
                }
            }
            for (int length : EXTREME_VALUES) {
                for (boolean enabled : new boolean[]{false, true}) {
                    for (boolean firstHalf : new boolean[]{false, true}) {
                        for (int value : values) {
                            assertNr4(fullLength, length, enabled, firstHalf, value);
                        }
                    }
                }
            }
        }
    }

    @Test
    public void nr4DecoderMatchesEverySignedLowWord() {
        State[] states = {
                new State(64, -1, false, false),
                new State(64, 0, false, true),
                new State(64, 1, false, true),
                new State(64, 2, false, true),
                new State(64, 1, true, true),
                new State(256, 0, true, false),
                new State(256, 1, true, false),
                new State(0, 0, false, true),
                new State(-1, 1, false, true),
                new State(Integer.MIN_VALUE, 0, false, false),
                new State(Integer.MAX_VALUE, 0, false, true),
                new State(Integer.MAX_VALUE, Integer.MAX_VALUE, true, true)
        };
        for (State state : states) {
            for (int value = Short.MIN_VALUE; value <= Short.MAX_VALUE; value++) {
                assertNr4(state.fullLength, state.length, state.enabled, state.firstHalf, value);
            }
        }
    }

    private static void assertClock(int length, boolean enabled) {
        LengthCounter production = counter(64, length, enabled, false);
        Transition expected = referenceClock(length, enabled);
        boolean returned = production.clockTick();
        assertEquals(context(64, length, enabled, false, 0), expected.returned, returned);
        assertEquals(context(64, length, enabled, false, 0), expected.length, production.getValue());
        assertEquals(context(64, length, enabled, false, 0), expected.enabled, production.isEnabled());
    }

    private static void assertNr4(int fullLength, int length, boolean enabled,
                                  boolean firstHalf, int value) {
        LengthCounter production = counter(fullLength, length, enabled, firstHalf);
        Transition expected = referenceNr4(fullLength, length, enabled, firstHalf, value);
        boolean returned = production.setNr4(value);
        String context = context(fullLength, length, enabled, firstHalf, value);
        assertEquals(context, expected.returned, returned);
        assertEquals(context, expected.length, production.getValue());
        assertEquals(context, expected.enabled, production.isEnabled());
    }

    private static LengthCounter counter(int fullLength, int length, boolean enabled,
                                         boolean firstHalf) {
        FrameSequencer sequencer = secondHalfSequencer();
        LengthCounter counter = new LengthCounter(fullLength, sequencer);
        counter.reset();
        if (length != 0) {
            counter.setLength(length);
        }
        if (enabled) {
            counter.setNr4(0x40);
        }
        if (firstHalf) {
            sequencer.tick(1 << 12, true, false);
            sequencer.tick(0, true, false);
        }
        assertEquals(firstHalf, sequencer.isFirstHalfOfLengthPeriod());
        return counter;
    }

    private static FrameSequencer secondHalfSequencer() {
        FrameSequencer sequencer = new FrameSequencer();
        sequencer.reset();
        return sequencer;
    }

    private static int referenceLoad(int fullLength, int length) {
        return length == 0 ? fullLength : length;
    }

    private static Transition referenceClock(int length, boolean enabled) {
        if (enabled && length > 0) {
            length--;
            return new Transition(length, enabled, length == 0);
        }
        return new Transition(length, enabled, false);
    }

    private static Transition referenceNr4(int fullLength, int length, boolean enabled,
                                           boolean firstHalf, int value) {
        boolean enable = (value & (1 << 6)) != 0;
        boolean trigger = (value & (1 << 7)) != 0;
        boolean zeroed = false;
        if (firstHalf && !enabled && enable && length > 0) {
            length--;
            zeroed = length == 0;
        }
        enabled = enable;
        if (trigger && length == 0) {
            length = (firstHalf && enable) ? fullLength - 1 : fullLength;
            zeroed = false;
        }
        return new Transition(length, enabled, zeroed && !trigger);
    }

    private static String context(int fullLength, int length, boolean enabled,
                                  boolean firstHalf, int value) {
        return String.format("full=%d length=%d enabled=%s firstHalf=%s value=%08x",
                fullLength, length, enabled, firstHalf, value);
    }

    private record State(int fullLength, int length, boolean enabled, boolean firstHalf) {
    }

    private record Transition(int length, boolean enabled, boolean returned) {
    }
}
