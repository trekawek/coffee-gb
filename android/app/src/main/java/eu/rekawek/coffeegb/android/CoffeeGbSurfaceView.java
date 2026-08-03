package eu.rekawek.coffeegb.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * Dedicated nearest-neighbour native-frame surface.
 *
 * <p>This view owns only a short-lived Surface renderer. It attaches to the service-owned
 * {@link NativeFrameStore} while its Surface exists and never owns the controller or its frame
 * producer, so a Surface loss during rotation cannot stop the emulation session.
 */
public final class CoffeeGbSurfaceView extends SurfaceView
        implements SurfaceHolder.Callback, NativeFrameStore.Listener {

    private final Object renderLock = new Object();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect source = new Rect();
    private final Rect destination = new Rect();
    private final Bitmap bitmap = Bitmap.createBitmap(
            NativeFrameStore.MAX_WIDTH, NativeFrameStore.MAX_HEIGHT, Bitmap.Config.ARGB_8888);

    private volatile NativeFrameStore frames;
    private RenderThread renderThread;
    private volatile boolean surfaceReady;

    public CoffeeGbSurfaceView(Context context) {
        super(context);
        paint.setFilterBitmap(false);
        getHolder().addCallback(this);
        setContentDescription("Coffee GB emulated video output");
    }

    /** Attaches this transient Surface to the long-lived service frame store. */
    public void attach(NativeFrameStore frameStore) {
        if (frames == frameStore) {
            return;
        }
        detach();
        frames = frameStore;
        if (surfaceReady) {
            frames.addListener(this);
            startRenderer();
        }
    }

    /** Releases only the Surface subscription; the active emulator keeps running in its service. */
    public void detach() {
        NativeFrameStore active = frames;
        if (active != null) {
            active.removeListener(this);
        }
        frames = null;
        stopRenderer();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (frames != null) {
            frames.addListener(this);
            startRenderer();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        onFrameAvailable();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        NativeFrameStore active = frames;
        if (active != null) {
            active.removeListener(this);
        }
        stopRenderer();
    }

    @Override
    protected void onDetachedFromWindow() {
        detach();
        super.onDetachedFromWindow();
    }

    @Override
    public void onFrameAvailable() {
        synchronized (renderLock) {
            if (renderThread != null) {
                renderThread.frameAvailable = true;
                renderLock.notifyAll();
            }
        }
    }

    private void startRenderer() {
        synchronized (renderLock) {
            if (renderThread != null || !surfaceReady || frames == null) {
                return;
            }
            renderThread = new RenderThread();
            renderThread.start();
            renderThread.frameAvailable = true;
            renderLock.notifyAll();
        }
    }

    private void stopRenderer() {
        RenderThread retiring;
        synchronized (renderLock) {
            retiring = renderThread;
            renderThread = null;
            if (retiring != null) {
                retiring.running = false;
                renderLock.notifyAll();
            }
        }
        if (retiring != null && Thread.currentThread() != retiring) {
            try {
                retiring.join(500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private final class RenderThread extends Thread {
        private boolean running = true;
        private boolean frameAvailable;

        private RenderThread() {
            super("coffee-gb-android-video");
            setDaemon(true);
        }

        @Override
        public void run() {
            while (awaitFrame()) {
                NativeFrameStore active = frames;
                if (active == null || !surfaceReady) {
                    continue;
                }
                NativeFrameStore.Frame frame = active.takeLatest();
                try {
                    draw(frame);
                } finally {
                    active.finishDrawing(frame);
                }
            }
        }

        private boolean awaitFrame() {
            synchronized (renderLock) {
                while (running && !frameAvailable) {
                    try {
                        renderLock.wait();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                if (!running) {
                    return false;
                }
                frameAvailable = false;
                return true;
            }
        }

        private void draw(NativeFrameStore.Frame frame) {
            Canvas canvas = null;
            try {
                canvas = getHolder().lockCanvas();
                if (canvas == null) {
                    return;
                }
                canvas.drawColor(Color.BLACK);
                if (frame == null) {
                    return;
                }
                bitmap.setPixels(frame.pixels(), 0, frame.width(), 0, 0, frame.width(), frame.height());
                source.set(0, 0, frame.width(), frame.height());
                VideoGeometry.Viewport viewport = VideoGeometry.nearestFit(
                        frame.width(), frame.height(), canvas.getWidth(), canvas.getHeight());
                destination.set(viewport.left(), viewport.top(),
                        viewport.left() + viewport.width(), viewport.top() + viewport.height());
                canvas.drawBitmap(bitmap, source, destination, paint);
            } finally {
                if (canvas != null) {
                    getHolder().unlockCanvasAndPost(canvas);
                }
            }
        }
    }
}
