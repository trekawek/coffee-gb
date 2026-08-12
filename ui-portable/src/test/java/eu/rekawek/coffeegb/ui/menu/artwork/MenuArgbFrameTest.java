package eu.rekawek.coffeegb.ui.menu.artwork;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MenuArgbFrameTest {

    @Test
    public void pixelsAreImmutableAndCopyToSupportsStrideAndOffset() {
        int[] pixels = {0xff010203, 0x80405060, 0x000000ff, 0xffffffff, 0x7f112233, 0xffaabbcc};
        MenuArgbFrame frame = new MenuArgbFrame(3, 2, pixels);

        pixels[0] = 0;
        int[] copied = frame.copyPixels();
        assertArrayEquals(new int[]{0xff010203, 0x80405060, 0x000000ff,
                0xffffffff, 0x7f112233, 0xffaabbcc}, copied);
        assertNotSame(copied, frame.copyPixels());
        copied[0] = 0;
        assertEquals(0xff010203, frame.copyPixels()[0]);

        int[] destination = new int[11];
        java.util.Arrays.fill(destination, 0x12345678);
        frame.copyTo(destination, 2, 5);
        assertArrayEquals(new int[]{
                0x12345678, 0x12345678, 0xff010203, 0x80405060, 0x000000ff,
                0x12345678, 0x12345678, 0xffffffff, 0x7f112233, 0xffaabbcc,
                0x12345678
        }, destination);
    }

    @Test
    public void copyToValidatesNullDimensionsOffsetStrideCapacityAndOverflow() {
        MenuArgbFrame frame = new MenuArgbFrame(3, 2, new int[6]);
        expectNullPointer(() -> frame.copyTo(null, 0, 3));
        expectIllegalArgument(() -> new MenuArgbFrame(0, 2, new int[0]));
        expectIllegalArgument(() -> new MenuArgbFrame(2, 2, new int[3]));
        expectIllegalArgument(() -> frame.copyTo(new int[6], -1, 3));
        expectIllegalArgument(() -> frame.copyTo(new int[6], 0, 2));
        expectIllegalArgument(() -> frame.copyTo(new int[6], 7, 3));
        expectIllegalArgument(() -> frame.copyTo(new int[6], 4, 3));
        expectIllegalArgument(() -> frame.copyTo(new int[6], Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertTrue(frame.width() == 3 && frame.height() == 2);
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected validation failure.
        }
    }

    private static void expectNullPointer(Runnable action) {
        try {
            action.run();
            fail("Expected NullPointerException");
        } catch (NullPointerException expected) {
            // Expected validation failure.
        }
    }
}
