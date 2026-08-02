package eu.rekawek.coffeegb.swing;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CoffeeGbIconTest {

    @Test
    public void bundlesHighResolutionSquareRasterDerivative() throws Exception {
        try (InputStream input = CoffeeGbIcon.class.getResourceAsStream(
                "/eu/rekawek/coffeegb/swing/coffee-gb.png")) {
            assertNotNull(input);
            BufferedImage master = ImageIO.read(input);
            assertNotNull(master);
            assertEquals(master.getWidth(), master.getHeight());
            assertTrue(master.getWidth() >= 1024);
        }
    }

    @Test
    public void rendersRequestedDesktopAndPackageSizes() {
        for (int size : new int[] {16, 32, 256, 1024}) {
            BufferedImage image = CoffeeGbIcon.image(size);
            assertEquals(size, image.getWidth());
            assertEquals(size, image.getHeight());
            assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
        }
    }

    @Test
    public void returnedImagesCannotMutateTheCachedMaster() {
        BufferedImage first = CoffeeGbIcon.image(32);
        int original = first.getRGB(16, 16);
        first.setRGB(16, 16, original ^ 0x00ffffff);

        BufferedImage second = CoffeeGbIcon.image(32);
        assertNotEquals(first.getRGB(16, 16), second.getRGB(16, 16));
        assertEquals(original, second.getRGB(16, 16));
    }

    @Test
    public void rejectsSizesOutsideThePublicContract() {
        assertInvalidSize(0);
        assertInvalidSize(4097);
    }

    private static void assertInvalidSize(int size) {
        try {
            CoffeeGbIcon.image(size);
            fail("Expected invalid icon size " + size + " to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("between 1 and 4096"));
        }
    }
}
