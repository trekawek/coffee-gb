package eu.rekawek.coffeegb.swing.io;

import org.junit.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class DisplayFrameSnapshotTest {

    @Test
    public void snapshotCopiesInputAndDoesNotExposeItsRaster() {
        int[] emulatorOwned = {0x123456, 0xabcdef};

        DisplayFrameSnapshot snapshot = DisplayFrameSnapshot.copyOf(2, 1, emulatorOwned);
        emulatorOwned[0] = 0;
        emulatorOwned[1] = 0;

        assertEquals(0x123456, snapshot.rgbAt(0, 0));
        assertEquals(0xabcdef, snapshot.rgbAt(1, 0));
    }

    @Test
    public void paintingReadsSnapshotWithoutChangingItsPixels() {
        DisplayFrameSnapshot snapshot =
                DisplayFrameSnapshot.copyOf(2, 1, new int[]{0x010203, 0xa0b0c0});
        BufferedImage target = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            snapshot.paint(graphics);
        } finally {
            graphics.dispose();
        }

        assertEquals(0x010203, target.getRGB(0, 0) & 0xffffff);
        assertEquals(0xa0b0c0, target.getRGB(1, 0) & 0xffffff);
        assertEquals(0x010203, snapshot.rgbAt(0, 0));
        assertEquals(0xa0b0c0, snapshot.rgbAt(1, 0));
    }

    @Test
    public void snapshotRequiresAnExactPixelCount() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DisplayFrameSnapshot.copyOf(2, 2, new int[3]));
        assertThrows(
                IllegalArgumentException.class,
                () -> DisplayFrameSnapshot.copyOf(2, 2, new int[5]));
    }
}
