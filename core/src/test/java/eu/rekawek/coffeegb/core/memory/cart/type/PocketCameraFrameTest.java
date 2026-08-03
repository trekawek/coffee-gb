package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** Golden sensor-pixel baseline for the platform-neutral Pocket Camera source contract. */
public class PocketCameraFrameTest {

    @Test
    public void nearestNeighbourCameraFrameProducesTheApprovedSensorQuadrants() throws Exception {
        PocketCamera.setCameraSource(() -> new CameraFrame(
                2,
                2,
                new int[]{0x000000, 0x808080, 0x404040, 0xffffff}));
        try {
            PocketCamera camera = new PocketCamera(new Rom(new byte[0x8000]), Battery.NULL_BATTERY);
            camera.setByte(0x4000, 0x10);
            camera.setByte(0xa001, 4); // gain 1.0
            camera.setByte(0xa002, 0x10); // exposure 0x1000
            camera.setByte(0xa003, 0x00);
            for (int threshold = 0; threshold < 16; threshold++) {
                int register = 6 + threshold * 3;
                camera.setByte(0xa000 + register, 64);
                camera.setByte(0xa000 + register + 1, 128);
                camera.setByte(0xa000 + register + 2, 192);
            }
            camera.setByte(0xa000, 1);
            camera.setByte(0x4000, 0);

            assertEquals(3, colorAt(camera, 0, 0));
            assertEquals(1, colorAt(camera, 64, 0));
            assertEquals(2, colorAt(camera, 0, 56));
            assertEquals(0, colorAt(camera, 64, 56));
        } finally {
            PocketCamera.setCameraSource(null);
        }
    }

    @Test
    public void frameOwnsAndBoundsItsRgbPixels() {
        int[] pixels = {0xff112233};
        CameraFrame frame = new CameraFrame(1, 1, pixels);
        pixels[0] = 0;

        assertArrayEquals(new int[]{0x112233}, frame.copyRgb());
        assertThrows(IllegalArgumentException.class, () -> new CameraFrame(0, 1, new int[0]));
        assertThrows(IllegalArgumentException.class, () -> new CameraFrame(1, 1, new int[2]));
    }

    private static int colorAt(PocketCamera camera, int x, int y) {
        int tile = (y / 8) * 16 + (x / 8);
        int address = 0xa100 + tile * 16 + (y & 7) * 2;
        int bit = 7 - (x & 7);
        int low = (camera.getByte(address) >> bit) & 1;
        int high = (camera.getByte(address + 1) >> bit) & 1;
        return low | (high << 1);
    }
}
