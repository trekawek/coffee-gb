package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.core.memory.cart.type.CameraSource;
import nu.pattern.OpenCV;
import org.opencv.core.Mat;
import org.opencv.videoio.VideoCapture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;

/**
 * A live {@link CameraSource} for the Game Boy (Pocket) Camera, backed by a real webcam
 * through OpenCV's {@code VideoCapture} (openpnp's OpenCV build, which bundles native
 * libraries for Linux, Windows and macOS including Apple Silicon - unlike the old
 * sarxos/BridJ stack that only had x86_64 natives).
 *
 * <p>A daemon thread grabs frames continuously so {@link #getFrame()} is a cheap
 * non-blocking read of the latest frame - the emulator calls it on every in-game capture
 * and must never stall on the camera. Any failure to load the native library, open the
 * device or read a frame degrades to {@code null} (the camera then falls back to the image
 * file / test pattern) rather than crashing the emulator.
 */
public class WebcamCameraSource implements CameraSource, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WebcamCameraSource.class);

    private static boolean nativeLoaded;

    private final VideoCapture capture;

    private final Thread grabThread;

    private volatile boolean running = true;

    private volatile BufferedImage latest;

    private WebcamCameraSource(VideoCapture capture) {
        this.capture = capture;
        this.grabThread = new Thread(this::grabLoop, "webcam-grab");
        this.grabThread.setDaemon(true);
        this.grabThread.start();
    }

    /**
     * Loads the native library and opens the default camera (device 0).
     *
     * @return the source, or {@code null} if the native library is unavailable (e.g. an
     * unsupported architecture) or no camera can be opened
     */
    public static synchronized WebcamCameraSource open() {
        try {
            if (!nativeLoaded) {
                OpenCV.loadLocally();
                nativeLoaded = true;
            }
            VideoCapture capture = new VideoCapture(0);
            if (!capture.isOpened()) {
                LOG.warn("No webcam could be opened (device 0)");
                capture.release();
                return null;
            }
            LOG.info("Opened webcam device 0 via OpenCV");
            return new WebcamCameraSource(capture);
        } catch (Throwable t) {
            LOG.warn("Failed to open the webcam", t);
            return null;
        }
    }

    private void grabLoop() {
        Mat mat = new Mat();
        while (running) {
            try {
                if (capture.read(mat) && !mat.empty()) {
                    latest = matToBufferedImage(mat);
                } else {
                    Thread.sleep(50);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                LOG.warn("Webcam grab failed", t);
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    @Override
    public BufferedImage getFrame() {
        return latest;
    }

    private static BufferedImage matToBufferedImage(Mat mat) {
        int width = mat.cols();
        int height = mat.rows();
        int channels = mat.channels();
        byte[] data = new byte[width * height * channels];
        mat.get(0, 0, data);
        int type = channels == 1 ? BufferedImage.TYPE_BYTE_GRAY : BufferedImage.TYPE_3BYTE_BGR;
        BufferedImage image = new BufferedImage(width, height, type);
        byte[] target = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(data, 0, target, 0, Math.min(data.length, target.length));
        return image;
    }

    @Override
    public void close() {
        running = false;
        grabThread.interrupt();
        try {
            grabThread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            capture.release();
        } catch (Throwable t) {
            LOG.warn("Failed to release the webcam", t);
        }
    }
}
