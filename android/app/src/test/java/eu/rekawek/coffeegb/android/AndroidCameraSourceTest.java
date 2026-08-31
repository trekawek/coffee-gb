package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.memory.cart.type.CameraFrame;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class AndroidCameraSourceTest {

    @Test
    public void capturesOnlyForAnEnabledForegroundPocketCameraCartridge() {
        FakeInput input = new FakeInput();
        AndroidCameraSource source = new AndroidCameraSource(input);
        CameraFrame frame = new CameraFrame(1, 1, new int[]{0x00112233});

        source.setEnabled(true);
        assertEquals(0, input.starts);
        source.setCartridgeActive(true);
        assertEquals(1, input.starts);
        input.publish(frame);
        assertSame(frame, source.getFrame());

        source.pause();
        assertEquals(1, input.stops);
        assertNull(source.getFrame());
        source.resume();
        assertEquals(2, input.starts);
        input.publish(frame);
        source.setEnabled(false);
        assertEquals(2, input.stops);
        assertNull(source.getFrame());

        source.close();
        assertEquals(1, input.closes);
    }

    @Test
    public void downsamplesPaddedLumaWithoutRetainingTheBuffer() {
        ByteBuffer luma = ByteBuffer.wrap(new byte[]{
                0x11, 0, 0x22, 0, 0, 0,
                0x33, 0, 0x44, 0, 0, 0,
                0x55, 0, 0x66, 0, 0, 0,
        });

        CameraFrame frame = AndroidCameraSource.decodeLuma(luma, 2, 3, 6, 2, 0);

        assertEquals(128, frame.getWidth());
        assertEquals(112, frame.getHeight());
        assertCorners(frame, 0x111111, 0x222222, 0x555555, 0x666666);
        luma.put(0, (byte) 0xff);
        assertCorners(frame, 0x111111, 0x222222, 0x555555, 0x666666);
        assertNull(AndroidCameraSource.decodeLuma(ByteBuffer.wrap(new byte[2]),
                2, 3, 6, 2, 0));
        assertNull(AndroidCameraSource.decodeLuma(luma, 2, 3, 6, 2, 45));
    }

    @Test
    public void appliesCameraXRotationMetadata() {
        ByteBuffer luma = ByteBuffer.wrap(new byte[]{
                0x11, 0x22,
                0x33, 0x44,
                0x55, 0x66,
        });

        assertCorners(AndroidCameraSource.decodeLuma(luma, 2, 3, 2, 1, 90),
                0x555555, 0x111111, 0x666666, 0x222222);
        assertCorners(AndroidCameraSource.decodeLuma(luma, 2, 3, 2, 1, 180),
                0x666666, 0x555555, 0x222222, 0x111111);
        assertCorners(AndroidCameraSource.decodeLuma(luma, 2, 3, 2, 1, 270),
                0x222222, 0x666666, 0x111111, 0x555555);
    }

    @Test
    public void persistsAndForwardsFrontAndRearLensSelection() {
        FakeInput input = new FakeInput();
        AndroidCameraSource source = new AndroidCameraSource(input);
        source.setLens("front");
        assertEquals(AndroidCameraSource.Lens.FRONT, source.lens());
        assertEquals(AndroidCameraSource.Lens.FRONT, input.lens);
        source.setLens("rear");
        assertEquals(AndroidCameraSource.Lens.REAR, source.lens());
        assertEquals(AndroidCameraSource.Lens.REAR, input.lens);
    }

    private static void assertCorners(CameraFrame frame, int topLeft, int topRight,
                                      int bottomLeft, int bottomRight) {
        int[] rgb = frame.copyRgb();
        int width = frame.getWidth();
        int height = frame.getHeight();
        assertEquals(topLeft, rgb[0]);
        assertEquals(topRight, rgb[width - 1]);
        assertEquals(bottomLeft, rgb[(height - 1) * width]);
        assertEquals(bottomRight, rgb[height * width - 1]);
    }

    private static final class FakeInput implements AndroidCameraSource.Input {
        private Consumer<CameraFrame> listener;
        private int starts;
        private int stops;
        private int closes;
        private AndroidCameraSource.Lens lens = AndroidCameraSource.Lens.REAR;

        @Override
        public void start(Consumer<CameraFrame> listener) {
            starts++;
            this.listener = listener;
        }

        @Override
        public void stop() {
            stops++;
            listener = null;
        }

        @Override
        public void setLens(AndroidCameraSource.Lens lens) {
            this.lens = lens;
        }

        @Override
        public void close() {
            closes++;
            listener = null;
        }

        private void publish(CameraFrame frame) {
            listener.accept(frame);
        }
    }
}
