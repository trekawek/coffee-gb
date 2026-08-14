package eu.rekawek.coffeegb.core.signal;

import org.junit.Test;

import java.util.ArrayDeque;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ByteSignalDelayLineTest {

    @Test
    public void everyByteAppearsAfterEverySupportedDepth() {
        for (int stages = 1; stages <= 7; stages++) {
            ByteSignalDelayLine delay = new ByteSignalDelayLine(stages, 0xa5);
            ArrayDeque<Integer> expected = new ArrayDeque<>();
            for (int i = 0; i < stages; i++) {
                expected.add(0xa5);
            }

            for (int value = 0; value <= 0xff; value++) {
                assertEquals((int) expected.remove(), delay.output());
                expected.add(value);
                delay.resolve(value);
                delay.commit();
            }
            while (!expected.isEmpty()) {
                assertEquals((int) expected.remove(), delay.output());
                delay.resolve(0);
                delay.commit();
            }
        }
    }

    @Test
    public void fillMakesAllReceiverStagesTransparent() {
        ByteSignalDelayLine delay = new ByteSignalDelayLine(7, 0);
        for (int i = 0; i < 7; i++) {
            delay.resolve(i * 17);
            delay.commit();
        }

        delay.fill(0x6d);

        for (int i = 0; i < 7; i++) {
            assertEquals(0x6d, delay.output());
            delay.resolve(0x6d);
            delay.commit();
        }
    }

    @Test
    public void restoreDiscardsAnUncommittedResolution() {
        ByteSignalDelayLine delay = new ByteSignalDelayLine(3, 0x12);
        delay.resolve(0x34);
        delay.commit();
        long state = delay.state();
        delay.resolve(0x56);

        delay.restore(state);
        delay.commit();

        assertEquals(0x12, delay.output());
        assertEquals(state, delay.state());
    }

    @Test
    public void validatesDepthByteAndPackedState() {
        assertThrows(IllegalArgumentException.class, () -> new ByteSignalDelayLine(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ByteSignalDelayLine(8, 0));
        assertThrows(IllegalArgumentException.class, () -> new ByteSignalDelayLine(1, 0x100));

        ByteSignalDelayLine delay = new ByteSignalDelayLine(2, 0);
        assertThrows(IllegalArgumentException.class, () -> delay.resolve(-1));
        assertThrows(IllegalArgumentException.class, () -> delay.fill(0x100));
        assertThrows(IllegalArgumentException.class, () -> delay.restore(1L << 16));
    }
}
