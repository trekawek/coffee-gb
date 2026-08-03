package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.core.memory.cart.type.CameraFrame;
import eu.rekawek.coffeegb.core.memory.cart.type.CameraSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Desktop-only camera adapter that preserves the historical webcam-then-image-file behavior.
 *
 * <p>The static system-property and ImageIO details intentionally stay on this side of the core
 * boundary. Every returned frame is immutable and the emulator only sees {@link CameraFrame}.
 */
public final class DesktopCameraSource implements CameraSource {

    public static final DesktopCameraSource INSTANCE = new DesktopCameraSource();

    private volatile CameraSource liveSource;

    private long sourceTimestamp;

    private CameraFrame sourceFrame;

    private DesktopCameraSource() {
    }

    public void setLiveSource(CameraSource source) {
        liveSource = source;
    }

    @Override
    public CameraFrame getFrame() {
        CameraSource live = liveSource;
        if (live != null) {
            CameraFrame frame = live.getFrame();
            if (frame != null) {
                return frame;
            }
        }
        return imageFileFrame();
    }

    private synchronized CameraFrame imageFileFrame() {
        String path = System.getProperty("coffeegb.camera.image");
        if (path == null) {
            return null;
        }
        File file = new File(path);
        if (!file.isFile()) {
            return null;
        }
        try {
            if (sourceFrame == null || file.lastModified() != sourceTimestamp) {
                BufferedImage image = ImageIO.read(file);
                if (image != null) {
                    int width = image.getWidth();
                    int height = image.getHeight();
                    int[] rgb = image.getRGB(0, 0, width, height, null, 0, width);
                    sourceFrame = new CameraFrame(width, height, rgb);
                    sourceTimestamp = file.lastModified();
                }
            }
            return sourceFrame;
        } catch (RuntimeException | java.io.IOException ignored) {
            return sourceFrame;
        }
    }
}
