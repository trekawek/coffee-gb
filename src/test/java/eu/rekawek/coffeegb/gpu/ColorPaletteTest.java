package eu.rekawek.coffeegb.gpu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ColorPaletteTest {

    @Test
    public void testAutoIncrement() {
        ColorPalette p = new ColorPalette(0xff68);
        p.setByte(0xff68, 0x80);
        p.setByte(0xff69, 0x00);
        p.setByte(0xff69, 0xaa);
        p.setByte(0xff69, 0x11);
        p.setByte(0xff69, 0xbb);
        p.setByte(0xff69, 0x22);
        p.setByte(0xff69, 0xcc);
        p.setByte(0xff69, 0x33);
        p.setByte(0xff69, 0xdd);
        p.setByte(0xff69, 0x44);
        p.setByte(0xff69, 0xee);
        p.setByte(0xff69, 0x55);
        p.setByte(0xff69, 0xff);
        assertArrayEquals(new int[] {0xaa00, 0xbb11, 0xcc22, 0xdd33}, p.getPalette(0));
        assertArrayEquals(new int[] {0xee44, 0xff55, 0x0000, 0x0000}, p.getPalette(1));
    }

    private static void assertArrayEquals(int[] expected, int[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                StringBuilder msg = new StringBuilder();
                msg.append("arrays first differed at element [").append(i).append("]\n");
                msg.append("Expected :").append(String.format("%04x", expected[i])).append("\n");
                msg.append("Actual   :").append(String.format("%04x", actual[i]));
                fail(msg.toString());
            }
        }
    }

}
