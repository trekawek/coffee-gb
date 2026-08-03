package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.core.memory.cart.type.CameraSource;
import eu.rekawek.coffeegb.core.memory.cart.type.CameraFrame;
import eu.rekawek.coffeegb.swing.packaging.NativeRuntimeBootstrap;
import nu.pattern.OpenCV;
import org.opencv.core.Mat;
import org.opencv.videoio.VideoCapture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

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

    private volatile CameraFrame latest;

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
    public static WebcamCameraSource open() {
        return open(0);
    }

    /**
     * Loads the native library and opens one explicitly selected camera device.
     *
     * @param deviceIndex the non-negative OpenCV camera index
     * @return the source, or {@code null} if the native library is unavailable or the selected
     * device cannot be opened
     */
    public static synchronized WebcamCameraSource open(int deviceIndex) {
        if (deviceIndex < 0) {
            throw new IllegalArgumentException("Camera device index must not be negative");
        }
        try {
            if (!nativeLoaded) {
                String packaged =
                        System.getProperty(NativeRuntimeBootstrap.OPENCV_LIBRARY_PROPERTY, "");
                if (!packaged.isBlank()) {
                    Path library = Path.of(packaged).toAbsolutePath().normalize();
                    if (!Files.isRegularFile(library)) {
                        throw new IllegalStateException("Packaged OpenCV library is missing");
                    }
                    System.load(library.toString());
                } else {
                    OpenCV.loadLocally();
                }
                nativeLoaded = true;
            }
            VideoCapture capture = new VideoCapture(deviceIndex);
            if (!capture.isOpened()) {
                LOG.warn("No webcam could be opened (device {})", deviceIndex);
                capture.release();
                return null;
            }
            LOG.info("Opened webcam device {} via OpenCV", deviceIndex);
            return new WebcamCameraSource(capture);
        } catch (Throwable t) {
            LOG.warn("Failed to open webcam device " + deviceIndex, t);
            return null;
        }
    }

    private void grabLoop() {
        Mat mat = new Mat();
        while (running) {
            try {
                if (capture.read(mat) && !mat.empty()) {
                    latest = matToCameraFrame(mat);
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
    public CameraFrame getFrame() {
        return latest;
    }

    private static CameraFrame matToCameraFrame(Mat mat) {
        int width = mat.cols();
        int height = mat.rows();
        int channels = mat.channels();
        byte[] data = new byte[Math.multiplyExact(Math.multiplyExact(width, height), channels)];
        mat.get(0, 0, data);
        int[] rgb = new int[Math.multiplyExact(width, height)];
        for (int pixel = 0; pixel < rgb.length; pixel++) {
            int offset = pixel * channels;
            if (channels == 1) {
                int grey = data[offset] & 0xff;
                rgb[pixel] = (grey << 16) | (grey << 8) | grey;
            } else {
                int blue = data[offset] & 0xff;
                int green = data[offset + 1] & 0xff;
                int red = data[offset + 2] & 0xff;
                rgb[pixel] = (red << 16) | (green << 8) | blue;
            }
        }
        return new CameraFrame(width, height, rgb);
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
