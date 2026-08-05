package eu.rekawek.coffeegb.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import eu.rekawek.coffeegb.core.joypad.Button;

import java.util.List;

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
    private final Paint videoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint skinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect source = new Rect();
    private final Rect destination = new Rect();
    private final Bitmap bitmap = Bitmap.createBitmap(
            NativeFrameStore.MAX_WIDTH, NativeFrameStore.MAX_HEIGHT, Bitmap.Config.ARGB_8888);
    private final RasterSkin portraitSkin;
    private final RasterSkin landscapeSkin;

    private volatile NativeFrameStore frames;
    private final TouchControlsPreferences touchPreferences;
    private volatile TouchControlsLayout touchLayout;
    private AndroidInputRouter input;
    private RenderThread renderThread;
    private volatile boolean surfaceReady;

    public CoffeeGbSurfaceView(Context context) {
        super(context);
        videoPaint.setFilterBitmap(false);
        skinPaint.setFilterBitmap(true);
        portraitSkin = RasterSkin.load(context, R.drawable.coffee_gb_skin_portrait);
        landscapeSkin = RasterSkin.load(context, R.drawable.coffee_gb_skin_landscape);
        touchPreferences = new TouchControlsPreferences(context);
        touchLayout = touchPreferences.load();
        setZOrderOnTop(false);
        setZOrderMediaOverlay(false);
        getHolder().addCallback(this);
        setContentDescription("Coffee GB emulated video output");
    }

    TouchControlsLayout touchLayout() {
        return touchLayout;
    }

    void updateTouchLayout(TouchControlsLayout layout) {
        touchLayout = layout;
        touchPreferences.save(layout);
        onFrameAvailable();
    }

    void resetTouchLayout() {
        touchPreferences.reset();
        touchLayout = touchPreferences.load();
        onFrameAvailable();
    }

    /** Attaches this transient Surface to the long-lived service frame store. */
    public void attach(NativeFrameStore frameStore, AndroidInputRouter inputRouter) {
        if (frames == frameStore) {
            input = inputRouter;
            return;
        }
        detach();
        frames = frameStore;
        input = inputRouter;
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
        if (input != null) {
            input.releaseAllTouch();
        }
        input = null;
        stopRenderer();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        AndroidInputRouter router = input;
        if (router == null) {
            return false;
        }
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_OUTSIDE) {
            router.releaseAllTouch();
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            router.releaseTouchPointer(event.getPointerId(event.getActionIndex()));
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_MOVE) {
            TouchControlsLayout layout = touchLayout;
            for (int index = 0; index < event.getPointerCount(); index++) {
                List<Button> buttons = layout.buttonsAt(event.getX(index), event.getY(index),
                        getWidth(), getHeight());
                router.updateTouchPointer(event.getPointerId(index), buttons);
                if (index == event.getActionIndex() && action != MotionEvent.ACTION_MOVE
                        && !buttons.isEmpty() && layout.haptics()) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
            }
            return true;
        }
        return super.onTouchEvent(event);
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
        AndroidInputRouter router = input;
        if (router != null) {
            router.releaseAllTouch();
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

    private RasterSkin skinFor(int width, int height) {
        return height >= width ? portraitSkin : landscapeSkin;
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
                RasterSkin skin = skinFor(canvas.getWidth(), canvas.getHeight());
                RectF display = skin.displayBounds(canvas.getWidth(), canvas.getHeight());
                if (frame != null) {
                    bitmap.setPixels(frame.pixels(), 0, frame.width(), 0, 0,
                            frame.width(), frame.height());
                    source.set(0, 0, frame.width(), frame.height());
                    destination.set(Math.round(display.left), Math.round(display.top),
                            Math.round(display.right), Math.round(display.bottom));
                    canvas.drawBitmap(bitmap, source, destination, videoPaint);
                }
                skin.draw(canvas, skinPaint);
            } finally {
                if (canvas != null) {
                    getHolder().unlockCanvasAndPost(canvas);
                }
            }
        }
    }
}
