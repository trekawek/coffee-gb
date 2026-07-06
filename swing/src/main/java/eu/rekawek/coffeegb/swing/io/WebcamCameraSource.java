package eu.rekawek.coffeegb.swing.io;

import com.github.sarxos.webcam.Webcam;
import eu.rekawek.coffeegb.core.memory.cart.type.CameraSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Dimension;
import java.awt.image.BufferedImage;

/**
 * A live {@link CameraSource} for the Game Boy (Pocket) Camera, backed by a real webcam
 * through the sarxos webcam-capture library. The webcam runs in async mode: a background
 * thread keeps the latest frame buffered, so {@link #getFrame()} is a cheap non-blocking
 * read the emulator can call on every in-game capture.
 */
public class WebcamCameraSource implements CameraSource, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WebcamCameraSource.class);

    private final Webcam webcam;

    private WebcamCameraSource(Webcam webcam) {
        this.webcam = webcam;
    }

    /**
     * Opens the default webcam.
     *
     * @return the source, or {@code null} if no webcam is available or it cannot be opened
     */
    public static WebcamCameraSource open() {
        try {
            Webcam webcam = Webcam.getDefault();
            if (webcam == null) {
                LOG.warn("No webcam detected");
                return null;
            }
            // the frame is downscaled to the 128x112 sensor anyway, so prefer the smallest
            // supported resolution that still covers it (lower latency and CPU)
            Dimension best = null;
            for (Dimension d : webcam.getViewSizes()) {
                if (d.width < 128 || d.height < 112) {
                    continue;
                }
                if (best == null || (long) d.width * d.height < (long) best.width * best.height) {
                    best = d;
                }
            }
            if (best != null) {
                webcam.setViewSize(best);
            }
            webcam.open(true);
            LOG.info("Opened webcam {} at {}", webcam.getName(), webcam.getViewSize());
            return new WebcamCameraSource(webcam);
        } catch (Throwable t) {
            LOG.warn("Failed to open the webcam", t);
            return null;
        }
    }

    @Override
    public BufferedImage getFrame() {
        return webcam.isOpen() ? webcam.getImage() : null;
    }

    @Override
    public void close() {
        try {
            webcam.close();
        } catch (Throwable t) {
            LOG.warn("Failed to close the webcam", t);
        }
    }
}
