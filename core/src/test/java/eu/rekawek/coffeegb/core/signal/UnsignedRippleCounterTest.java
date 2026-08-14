package eu.rekawek.coffeegb.core.signal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class UnsignedRippleCounterTest {

    @Test
    public void exhaustivelyResolvesEverySixteenBitStateAndInputCombination() {
        int width = 16;
        long valueMask = 0xffff;
        for (long value = 0; value <= valueMask; value++) {
            for (int increment = 0; increment < 2; increment++) {
                for (int clear = 0; clear < 2; clear++) {
                    UnsignedRippleCounter counter = new UnsignedRippleCounter(width, value);

                    counter.resolve(increment != 0, clear != 0);

                    long expectedNext = clear != 0
                            ? 0
                            : increment != 0 ? (value + 1) & valueMask : value;
                    long expectedRising = ~value & expectedNext & valueMask;
                    long expectedFalling = value & ~expectedNext & valueMask;
                    assertEquals(value, counter.value());
                    assertEquals(expectedNext, counter.nextValue());
                    assertEquals(expectedRising, counter.risingMask());
                    assertEquals(expectedFalling, counter.fallingMask());
                    for (int bit = 0; bit < width; bit++) {
                        long bitMask = 1L << bit;
                        assertEquals((expectedRising & bitMask) != 0, counter.rose(bit));
                        assertEquals((expectedFalling & bitMask) != 0, counter.fell(bit));
                    }
                }
            }
        }
    }

    @Test
    public void incrementExposesTheRippleCarryTransitions() {
        UnsignedRippleCounter counter = new UnsignedRippleCounter(8, 0b0010_1111);

        counter.resolve(true, false);

        assertEquals(0b0010_1111, counter.value());
        assertEquals(0b0011_0000, counter.nextValue());
        assertEquals(0b0001_0000, counter.risingMask());
        assertEquals(0b0000_1111, counter.fallingMask());
    }

    @Test
    public void asynchronousClearExposesEveryFallingTapAndDominatesIncrement() {
        UnsignedRippleCounter counter = new UnsignedRippleCounter(8, 0b1010_0101);

        counter.resolve(true, true);

        assertEquals(0, counter.nextValue());
        assertEquals(0, counter.risingMask());
        assertEquals(0b1010_0101, counter.fallingMask());
    }

    @Test
    public void commitAdvancesTheValueAndClearsDerivedTransitionWires() {
        UnsignedRippleCounter counter = new UnsignedRippleCounter(4, 0b0111);
        counter.resolve(true, false);
        assertEquals(0b1000, counter.nextValue());

        counter.commit();

        assertEquals(0b1000, counter.value());
        assertEquals(0b1000, counter.nextValue());
        assertEquals(0, counter.risingMask());
        assertEquals(0, counter.fallingMask());
    }

    @Test
    public void restoreDiscardsAnUncommittedTransition() {
        UnsignedRippleCounter counter = new UnsignedRippleCounter(8, 0xff);
        counter.resolve(true, false);

        counter.restore(0x55);

        assertEquals(0x55, counter.value());
        assertEquals(0x55, counter.nextValue());
        assertEquals(0, counter.risingMask());
        assertEquals(0, counter.fallingMask());
        counter.commit();
        assertEquals(0x55, counter.value());
    }

    @Test
    public void supportsAFullUnsignedThirtyTwoBitValue() {
        UnsignedRippleCounter counter = new UnsignedRippleCounter(32, 0xffff_ffffL);

        counter.resolve(true, false);

        assertEquals(0, counter.nextValue());
        assertEquals(0, counter.risingMask());
        assertEquals(0xffff_ffffL, counter.fallingMask());
        assertEquals(true, counter.fell(31));
    }

    @Test
    public void validatesWidthsValuesAndBitIndices() {
        assertThrows(IllegalArgumentException.class, () -> new UnsignedRippleCounter(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new UnsignedRippleCounter(33, 0));
        assertThrows(IllegalArgumentException.class, () -> new UnsignedRippleCounter(8, -1));
        assertThrows(IllegalArgumentException.class, () -> new UnsignedRippleCounter(8, 0x100));

        UnsignedRippleCounter counter = new UnsignedRippleCounter(8, 0);
        assertThrows(IllegalArgumentException.class, () -> counter.rose(-1));
        assertThrows(IllegalArgumentException.class, () -> counter.fell(8));
        assertThrows(IllegalArgumentException.class, () -> counter.restore(0x100));
    }
}
