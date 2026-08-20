package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.memory.cart.type.CameraFrame;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import static org.junit.Assert.assertArrayEquals;
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
    public void decodesRgbaWithRowPaddingWithoutRetainingTheBuffer() {
        ByteBuffer rgba = ByteBuffer.wrap(new byte[]{
                0x11, 0x22, 0x33, 0x44, 0, 0, 0, 0,
                0x55, 0x66, 0x77, (byte) 0x88, 0, 0, 0, 0,
        });

        CameraFrame frame = AndroidCameraSource.decodeRgba(rgba, 1, 2, 8, 4);

        assertArrayEquals(new int[]{0x00112233, 0x00556677}, frame.copyRgb());
        rgba.put(0, (byte) 0xff);
        assertArrayEquals(new int[]{0x00112233, 0x00556677}, frame.copyRgb());
        assertNull(AndroidCameraSource.decodeRgba(ByteBuffer.wrap(new byte[3]), 1, 1, 4, 4));
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
