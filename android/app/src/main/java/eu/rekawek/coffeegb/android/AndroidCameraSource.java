package eu.rekawek.coffeegb.android;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.lifecycle.ProcessLifecycleOwner;
import eu.rekawek.coffeegb.core.memory.cart.type.CameraFrame;
import eu.rekawek.coffeegb.core.memory.cart.type.CameraSource;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Lifecycle-safe CameraX adapter for the portable Pocket Camera source.
 *
 * <p>The emulator only ever reads the latest immutable frame, so CameraX analysis is deliberately
 * keep-only-latest. Capture starts only for an enabled Pocket Camera cartridge while the host is
 * active; a missing frame remains the core's deterministic synthetic-camera fallback.
 */
final class AndroidCameraSource implements CameraSource, AutoCloseable {

    private static final int SENSOR_WIDTH = 128;
    private static final int SENSOR_HEIGHT = 112;

    enum Lens {
        REAR,
        FRONT;

        static Lens fromToken(String token) {
            return "front".equalsIgnoreCase(token) ? FRONT : REAR;
        }
    }

    interface Input extends AutoCloseable {
        void start(Consumer<CameraFrame> listener);

        void stop();

        /** Rebinds the host camera when supported; test inputs may remain lens agnostic. */
        default void setLens(Lens lens) {
        }

        @Override
        default void close() {
            stop();
        }
    }

    private static final class CameraXInput implements Input {
        private static final Size TARGET_RESOLUTION = new Size(320, 240);

        private final Context context;
        private final DisplayManager displayManager;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final ExecutorService analyzer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "coffee-gb-android-camera");
            thread.setDaemon(true);
            return thread;
        });

        private volatile boolean running;
        private volatile Consumer<CameraFrame> listener;
        private Lens lens = Lens.REAR;
        private boolean displayListenerRegistered;
        // Accessed on the main thread only.
        private ProcessCameraProvider provider;
        private ImageAnalysis analysis;
        private final DisplayManager.DisplayListener displayListener =
                new DisplayManager.DisplayListener() {
                    @Override
                    public void onDisplayAdded(int displayId) {
                    }

                    @Override
                    public void onDisplayRemoved(int displayId) {
                    }

                    @Override
                    public void onDisplayChanged(int displayId) {
                        if (displayId == Display.DEFAULT_DISPLAY && analysis != null) {
                            analysis.setTargetRotation(displayRotation());
                        }
                    }
                };

        private CameraXInput(Context context) {
            this.context = Objects.requireNonNull(context, "context").getApplicationContext();
            displayManager = this.context.getSystemService(DisplayManager.class);
        }

        @Override
        public void start(Consumer<CameraFrame> listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            running = true;
            mainHandler.post(() -> {
                if (displayManager != null && !displayListenerRegistered) {
                    displayManager.registerDisplayListener(displayListener, mainHandler);
                    displayListenerRegistered = true;
                }
                bind();
            });
        }

        @Override
        public void stop() {
            running = false;
            listener = null;
            mainHandler.post(() -> {
                if (provider != null && analysis != null) {
                    provider.unbind(analysis);
                    analysis = null;
                }
                if (displayManager != null && displayListenerRegistered) {
                    displayManager.unregisterDisplayListener(displayListener);
                    displayListenerRegistered = false;
                }
            });
        }

        @Override
        public void setLens(Lens lens) {
            mainHandler.post(() -> {
                this.lens = Objects.requireNonNull(lens, "lens");
                if (running) {
                    bind();
                }
            });
        }

        @Override
        public void close() {
            stop();
            analyzer.shutdownNow();
        }

        private void bind() {
            if (!running) {
                return;
            }
            var providerFuture = ProcessCameraProvider.getInstance(context);
            providerFuture.addListener(() -> {
                if (!running) {
                    return;
                }
                try {
                    ProcessCameraProvider nextProvider = providerFuture.get();
                    if (!running) {
                        return;
                    }
                    ImageAnalysis nextAnalysis = new ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                            .setTargetResolution(TARGET_RESOLUTION)
                            .setTargetRotation(displayRotation())
                            .build();
                    nextAnalysis.setAnalyzer(analyzer, this::onImage);
                    if (analysis != null) {
                        nextProvider.unbind(analysis);
                    }
                    CameraSelector selector = lens == Lens.FRONT
                            ? CameraSelector.DEFAULT_FRONT_CAMERA
                            : CameraSelector.DEFAULT_BACK_CAMERA;
                    nextProvider.bindToLifecycle(ProcessLifecycleOwner.get(), selector,
                            nextAnalysis);
                    provider = nextProvider;
                    analysis = nextAnalysis;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | RuntimeException ignored) {
                    // The portable camera receives no frame and deterministically falls back.
                }
            }, mainHandler::post);
        }

        private int displayRotation() {
            Display display = displayManager == null
                    ? null : displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            return display == null ? Surface.ROTATION_0 : display.getRotation();
        }

        private void onImage(ImageProxy image) {
            try {
                if (!running || image.getPlanes().length < 1) {
                    return;
                }
                ImageProxy.PlaneProxy plane = image.getPlanes()[0];
                CameraFrame frame = decodeLuma(plane.getBuffer(), image.getWidth(), image.getHeight(),
                        plane.getRowStride(), plane.getPixelStride(),
                        image.getImageInfo().getRotationDegrees());
                Consumer<CameraFrame> target = listener;
                if (running && frame != null && target != null) {
                    target.accept(frame);
                }
            } finally {
                image.close();
            }
        }
    }

    private final Input input;
    private final Consumer<CameraFrame> frames = this::accept;

    private volatile CameraFrame latest;
    private boolean enabled;
    private Lens lens = Lens.REAR;
    private boolean cartridgeActive;
    private boolean hostActive = true;
    private volatile boolean capturing;
    private boolean closed;

    AndroidCameraSource(Context context) {
        this(new CameraXInput(context));
    }

    AndroidCameraSource(Input input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    synchronized void setEnabled(boolean enabled) {
        if (closed) {
            return;
        }
        this.enabled = enabled;
        apply();
    }

    synchronized void setLens(String token) {
        Lens selected = Lens.fromToken(token);
        if (closed || lens == selected) {
            return;
        }
        lens = selected;
        input.setLens(selected);
    }

    synchronized Lens lens() {
        return lens;
    }

    synchronized void setCartridgeActive(boolean active) {
        if (closed || cartridgeActive == active) {
            return;
        }
        cartridgeActive = active;
        apply();
    }

    synchronized void pause() {
        hostActive = false;
        apply();
    }

    synchronized void resume() {
        hostActive = true;
        apply();
    }

    @Override
    public CameraFrame getFrame() {
        return capturing ? latest : null;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        enabled = false;
        cartridgeActive = false;
        latest = null;
        if (capturing) {
            capturing = false;
        }
        try {
            input.close();
        } catch (Exception ignored) {
            // Camera teardown must never prevent the controller from releasing its own resources.
        }
    }

    private synchronized void accept(CameraFrame frame) {
        if (capturing) {
            latest = frame;
        }
    }

    private void apply() {
        boolean shouldCapture = enabled && cartridgeActive && hostActive && !closed;
        if (shouldCapture == capturing) {
            return;
        }
        capturing = shouldCapture;
        latest = null;
        if (shouldCapture) {
            input.start(frames);
        } else {
            input.stop();
        }
    }

    /**
     * Rotates and downsamples CameraX's luma plane directly to the Pocket Camera sensor size.
     * This avoids allocating and copying a full RGBA analysis frame for every camera update.
     */
    static CameraFrame decodeLuma(ByteBuffer bytes, int width, int height, int rowStride,
                                  int pixelStride, int rotationDegrees) {
        if (width < 1 || height < 1 || rowStride < 1 || pixelStride < 1
                || (rotationDegrees != 0 && rotationDegrees != 90
                && rotationDegrees != 180 && rotationDegrees != 270)) {
            return null;
        }
        try {
            ByteBuffer source = Objects.requireNonNull(bytes, "bytes").slice();
            long finalOffset = Math.addExact(
                    Math.multiplyExact((long) height - 1L, rowStride),
                    Math.multiplyExact((long) width - 1L, pixelStride));
            if (finalOffset >= source.remaining()) {
                return null;
            }

            int rotatedWidth = rotationDegrees == 90 || rotationDegrees == 270
                    ? height : width;
            int rotatedHeight = rotationDegrees == 90 || rotationDegrees == 270
                    ? width : height;
            int[] rgb = new int[SENSOR_WIDTH * SENSOR_HEIGHT];
            for (int y = 0; y < SENSOR_HEIGHT; y++) {
                int rotatedY = (int) ((long) y * rotatedHeight / SENSOR_HEIGHT);
                for (int x = 0; x < SENSOR_WIDTH; x++) {
                    int rotatedX = (int) ((long) x * rotatedWidth / SENSOR_WIDTH);
                    int sourceX;
                    int sourceY;
                    switch (rotationDegrees) {
                        case 90 -> {
                            sourceX = rotatedY;
                            sourceY = height - 1 - rotatedX;
                        }
                        case 180 -> {
                            sourceX = width - 1 - rotatedX;
                            sourceY = height - 1 - rotatedY;
                        }
                        case 270 -> {
                            sourceX = width - 1 - rotatedY;
                            sourceY = rotatedX;
                        }
                        default -> {
                            sourceX = rotatedX;
                            sourceY = rotatedY;
                        }
                    }
                    int offset = Math.addExact(Math.multiplyExact(sourceY, rowStride),
                            Math.multiplyExact(sourceX, pixelStride));
                    int luma = source.get(offset) & 0xff;
                    rgb[y * SENSOR_WIDTH + x] = luma * 0x010101;
                }
            }
            return new CameraFrame(SENSOR_WIDTH, SENSOR_HEIGHT, rgb);
        } catch (ArithmeticException | IllegalArgumentException ignored) {
            return null;
        }
    }
}
