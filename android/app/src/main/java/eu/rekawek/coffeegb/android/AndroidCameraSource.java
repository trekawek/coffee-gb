package eu.rekawek.coffeegb.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
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

    interface Input extends AutoCloseable {
        void start(Consumer<CameraFrame> listener);

        void stop();

        @Override
        default void close() {
            stop();
        }
    }

    private static final class CameraXInput implements Input {
        private static final Size TARGET_RESOLUTION = new Size(320, 240);

        private final Context context;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private final ExecutorService analyzer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "coffee-gb-android-camera");
            thread.setDaemon(true);
            return thread;
        });

        private volatile boolean running;
        private volatile Consumer<CameraFrame> listener;
        // Accessed on the main thread only.
        private ProcessCameraProvider provider;
        private ImageAnalysis analysis;

        private CameraXInput(Context context) {
            this.context = Objects.requireNonNull(context, "context").getApplicationContext();
        }

        @Override
        public void start(Consumer<CameraFrame> listener) {
            this.listener = Objects.requireNonNull(listener, "listener");
            running = true;
            mainHandler.post(this::bind);
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
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .setTargetResolution(TARGET_RESOLUTION)
                            .build();
                    nextAnalysis.setAnalyzer(analyzer, this::onImage);
                    if (analysis != null) {
                        nextProvider.unbind(analysis);
                    }
                    nextProvider.bindToLifecycle(ProcessLifecycleOwner.get(),
                            CameraSelector.DEFAULT_BACK_CAMERA, nextAnalysis);
                    provider = nextProvider;
                    analysis = nextAnalysis;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | RuntimeException ignored) {
                    // The portable camera receives no frame and deterministically falls back.
                }
            }, mainHandler::post);
        }

        private void onImage(ImageProxy image) {
            try {
                // OUTPUT_IMAGE_FORMAT_RGBA_8888 guarantees one RGBA plane. Do not inspect an
                // Android framework format constant here: CameraX owns that compatibility layer
                // across our API 26+ range.
                if (!running || image.getPlanes().length != 1) {
                    return;
                }
                ImageProxy.PlaneProxy plane = image.getPlanes()[0];
                CameraFrame frame = decodeRgba(plane.getBuffer(), image.getWidth(), image.getHeight(),
                        plane.getRowStride(), plane.getPixelStride());
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

    static CameraFrame decodeRgba(ByteBuffer bytes, int width, int height, int rowStride,
                                  int pixelStride) {
        if (width < 1 || height < 1 || rowStride < 1 || pixelStride < 4) {
            return null;
        }
        try {
            ByteBuffer source = Objects.requireNonNull(bytes, "bytes").slice();
            long finalOffset = Math.addExact(
                    Math.addExact(Math.multiplyExact((long) height - 1L, rowStride),
                            Math.multiplyExact((long) width - 1L, pixelStride)), 3L);
            if (finalOffset >= source.remaining()) {
                return null;
            }
            int[] rgb = new int[Math.multiplyExact(width, height)];
            for (int y = 0; y < height; y++) {
                int row = Math.multiplyExact(y, rowStride);
                for (int x = 0; x < width; x++) {
                    int offset = Math.addExact(row, Math.multiplyExact(x, pixelStride));
                    rgb[y * width + x] = ((source.get(offset) & 0xff) << 16)
                            | ((source.get(offset + 1) & 0xff) << 8)
                            | (source.get(offset + 2) & 0xff);
                }
            }
            return new CameraFrame(width, height, rgb);
        } catch (ArithmeticException | IllegalArgumentException ignored) {
            return null;
        }
    }
}
