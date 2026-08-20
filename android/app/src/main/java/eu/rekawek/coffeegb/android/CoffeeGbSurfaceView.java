package eu.rekawek.coffeegb.android;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.android.menu.MenuRenderer;
import eu.rekawek.coffeegb.ui.menu.MenuKey;
import eu.rekawek.coffeegb.ui.menu.MenuTouchInput;
import eu.rekawek.coffeegb.core.joypad.Button;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Dedicated nearest-neighbour native-frame surface.
 *
 * <p>This view owns only a short-lived Surface renderer. It attaches to the service-owned
 * {@link NativeFrameStore} while its Surface exists and never owns the controller or its frame
 * producer, so a Surface loss during rotation cannot stop the emulation session.
 */
public final class CoffeeGbSurfaceView extends SurfaceView
        implements SurfaceHolder.Callback, NativeFrameStore.Listener {

    private static final int CANVAS_MATTE = 0xFFFAFAFA;
    private static final float DEFAULT_SURFACE_FRAME_RATE_HZ = 60.0f;
    private static final int BENCHMARK_ANCHOR_POSTS = 4;
    // Keep the final warm-up interval in the 200 ms SurfaceFlinger bucket.  These posts are
    // outside the measured epoch and give TimeStats a real, known pending boundary record.
    private static final long BENCHMARK_ANCHOR_INTERVAL_MILLIS = 180L;
    /** Lets the 600th BufferQueue fence cross a few display periods before the drain is queued. */
    private static final long BENCHMARK_DRAIN_DELAY_MILLIS = 100L;

    private final Object renderLock = new Object();
    private final Paint videoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint displayPaint = new Paint();
    private final Paint skinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint transientPanelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint transientTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect source = new Rect();
    private final Rect destination = new Rect();
    private final Bitmap bitmap = Bitmap.createBitmap(
            NativeFrameStore.MAX_WIDTH, NativeFrameStore.MAX_HEIGHT, Bitmap.Config.ARGB_8888);
    private final RasterSkin portraitSkin;
    private final RasterSkin landscapeSkin;
    private final MenuRenderer menuRenderer = new MenuRenderer();
    private final Set<Integer> menuTouchPointers = new HashSet<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong transientRevision = new AtomicLong();

    private volatile NativeFrameStore frames;
    private volatile MenuPresentation menuPresentation;
    private volatile MenuTouchInput menuInput;
    private final TouchControlsPreferences touchPreferences;
    private volatile TouchControlsLayout touchLayout;
    private volatile String transientMessage;
    private volatile long transientExpiresAt;
    /** One-time benchmark-only blank post used to establish a real Surface layer anchor. */
    private volatile boolean benchmarkAnchorRequested;
    private volatile int benchmarkAnchorPosts;
    private volatile Consumer<Boolean> benchmarkAnchorCallback;
    private AndroidInputRouter input;
    private RenderThread renderThread;
    private volatile boolean surfaceReady;
    /** Guards the one frame-rate hint allowed for each newly-created Surface. */
    private boolean surfaceFrameRateHintApplied;
    private float surfaceFrameRateHz = DEFAULT_SURFACE_FRAME_RATE_HZ;

    public CoffeeGbSurfaceView(Context context) {
        super(context);
        videoPaint.setFilterBitmap(false);
        displayPaint.setColor(Color.BLACK);
        skinPaint.setFilterBitmap(true);
        transientPanelPaint.setColor(Color.argb(210, 0, 0, 0));
        transientTextPaint.setColor(Color.WHITE);
        transientTextPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        transientTextPaint.setTextAlign(Paint.Align.LEFT);
        portraitSkin = RasterSkin.portrait(context);
        landscapeSkin = RasterSkin.landscape(context);
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

    /**
     * Publishes an immutable in-screen menu snapshot for the render thread.
     *
     * <p>The volatile handoff does not retain a controller lock or wait for a frame. The existing
     * frame signal only wakes the short-lived Surface renderer so the next canvas pass includes the
     * new snapshot. Passing {@code null} is equivalent to {@link #clearMenuPresentation()}.
     */
    public void setMenuPresentation(MenuPresentation presentation) {
        menuPresentation = presentation;
        onFrameAvailable();
    }

    /** Clears the in-screen menu and schedules one redraw without changing frame ownership. */
    public void clearMenuPresentation() {
        menuPresentation = null;
        onFrameAvailable();
    }

    /**
     * Selects the benchmark-only display vote before the Surface is created.  The compositor
     * gate still verifies the active display mode; this hint never changes emulated cadence.
     */
    void setBenchmarkFrameRate(int rateHz) {
        setBenchmarkContentRateMillihz(rateHz > 0 ? rateHz * 1000 : -1);
    }

    /** Advertises the exact emulated producer cadence, not the host display mode target. */
    void setBenchmarkContentRateMillihz(int rateMillihz) {
        if (rateMillihz > 0 && rateMillihz <= 240_000) {
            surfaceFrameRateHz = rateMillihz / 1000.0f;
        } else {
            surfaceFrameRateHz = DEFAULT_SURFACE_FRAME_RATE_HZ;
        }
        surfaceFrameRateHintApplied = false;
    }

    /**
     * Requests one out-of-epoch opaque post on this exact SurfaceView.  It is intentionally not
     * a game frame and never touches NativeFrameStore counters; the callback runs on the renderer
     * thread only after unlockCanvasAndPost has returned.
     */
    void requestBenchmarkAnchor(Consumer<Boolean> callback) {
        if (!BuildConfig.DIAGNOSTICS_ENABLED || callback == null) {
            return;
        }
        synchronized (renderLock) {
            if (benchmarkAnchorRequested || benchmarkAnchorCallback != null) {
                return;
            }
            benchmarkAnchorRequested = true;
            benchmarkAnchorPosts = BENCHMARK_ANCHOR_POSTS;
            benchmarkAnchorCallback = callback;
            if (renderThread != null) {
                renderThread.frameAvailable = true;
                renderLock.notifyAll();
            }
        }
    }

    /** Displays host feedback over gameplay or the opaque menu for the standard short duration. */
    void showTransientMessage(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        long revision = transientRevision.incrementAndGet();
        transientMessage = message;
        transientExpiresAt = SystemClock.uptimeMillis()
                + TransientMessage.DURATION_MILLIS;
        onFrameAvailable();
        mainHandler.postDelayed(() -> {
            if (transientRevision.get() != revision) {
                return;
            }
            transientMessage = null;
            transientExpiresAt = 0L;
            onFrameAvailable();
        }, TransientMessage.DURATION_MILLIS);
    }

    /** Clears feedback during surface/lifecycle teardown and invalidates pending expiry callbacks. */
    void clearTransientMessage() {
        transientRevision.incrementAndGet();
        transientMessage = null;
        transientExpiresAt = 0L;
        onFrameAvailable();
    }

    /** Installs the input bridge used to consume skin controls while the menu is visible. */
    public void setMenuInput(MenuTouchInput input) {
        MenuTouchInput previous = menuInput;
        if (previous != null) {
            previous.releaseAllPointers();
        }
        synchronized (menuTouchPointers) {
            menuTouchPointers.clear();
        }
        menuInput = input;
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
        clearTransientMessage();
        NativeFrameStore active = frames;
        if (active != null) {
            active.removeListener(this);
        }
        frames = null;
        if (input != null) {
            input.releaseAllTouch();
        }
        MenuTouchInput menu = menuInput;
        if (menu != null) {
            menu.releaseAllPointers();
        }
        synchronized (menuTouchPointers) {
            menuTouchPointers.clear();
        }
        input = null;
        stopRenderer();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        MenuTouchInput menu = menuInput;
        int action = event.getActionMasked();
        int actionPointerId = event.getPointerId(event.getActionIndex());
        boolean menuVisible = menu != null && menu.visible();
        boolean menuPointer = false;
        synchronized (menuTouchPointers) {
            menuPointer = menuTouchPointers.contains(actionPointerId)
                    || !menuTouchPointers.isEmpty();
        }
        if (menu != null && (menuVisible || menuPointer)) {
            if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_OUTSIDE) {
                menu.releaseAllPointers();
                synchronized (menuTouchPointers) {
                    menuTouchPointers.clear();
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
                menu.releasePointer(actionPointerId);
                synchronized (menuTouchPointers) {
                    menuTouchPointers.remove(actionPointerId);
                }
                return true;
            }
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
                    || action == MotionEvent.ACTION_MOVE) {
                if (menuVisible) {
                    for (int index = 0; index < event.getPointerCount(); index++) {
                        int pointerId = event.getPointerId(index);
                        menu.updatePointer(pointerId, menuKeysAt(event.getX(index), event.getY(index)));
                        synchronized (menuTouchPointers) {
                            menuTouchPointers.add(pointerId);
                        }
                    }
                }
                // A menu may close from the A/B edge generated above. Keep consuming this pointer
                // until its UP so that no partial touch chord reaches gameplay input.
                return true;
            }
        }

        AndroidInputRouter router = input;
        if (router == null) {
            return false;
        }
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
                List<Button> buttons = buttonsAtViewPoint(layout,
                        event.getX(index), event.getY(index));
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

    private List<MenuKey> menuKeysAt(float x, float y) {
        List<Button> buttons = buttonsAtViewPoint(touchLayout, x, y);
        if (buttons.isEmpty()) {
            return List.of();
        }
        ArrayList<MenuKey> keys = new ArrayList<>(buttons.size());
        for (Button button : buttons) {
            MenuKey key = switch (button) {
                case UP -> MenuKey.UP;
                case DOWN -> MenuKey.DOWN;
                case LEFT -> MenuKey.LEFT;
                case RIGHT -> MenuKey.RIGHT;
                case A -> MenuKey.A;
                case B -> MenuKey.B;
                case START -> MenuKey.START;
                case SELECT -> MenuKey.SELECT;
            };
            keys.add(key);
        }
        return List.copyOf(keys);
    }

    private List<Button> buttonsAtViewPoint(TouchControlsLayout layout, float x, float y) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return List.of();
        }
        RasterSkin skin = skinFor(width, height);
        return layout.buttonsAtViewPoint(x, y, skin.transform(width, height));
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        applySurfaceFrameRateHint(holder);
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
        surfaceFrameRateHintApplied = false;
        NativeFrameStore active = frames;
        if (active != null) {
            active.removeListener(this);
        }
        AndroidInputRouter router = input;
        if (router != null) {
            router.releaseAllTouch();
        }
        MenuTouchInput menu = menuInput;
        if (menu != null) {
            menu.releaseAllPointers();
        }
        synchronized (menuTouchPointers) {
            menuTouchPointers.clear();
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
            renderThread = new RenderThread(getHolder());
            renderThread.start();
            renderThread.frameAvailable = true;
            renderLock.notifyAll();
        }
    }

    private void stopRenderer() {
        RenderThread retiring;
        synchronized (renderLock) {
            retiring = renderThread;
            if (retiring != null) {
                retiring.running = false;
                renderLock.notifyAll();
            }
        }
        if (retiring != null && Thread.currentThread() != retiring) {
            // A Surface callback must not return while its renderer can still own a Canvas.
            boolean interrupted = false;
            while (retiring.isAlive()) {
                try {
                    retiring.join();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (renderLock) {
            if (renderThread == retiring) {
                renderThread = null;
            }
        }
    }

    private RasterSkin skinFor(int width, int height) {
        return height >= width ? portraitSkin : landscapeSkin;
    }

    /** Applies Android's non-disruptive game frame-rate hint once for this Surface lifecycle. */
    private void applySurfaceFrameRateHint(SurfaceHolder holder) {
        if (surfaceFrameRateHintApplied) {
            return;
        }
        // Mark the lifecycle attempt before calling into the framework. A Surface callback can
        // race with teardown, and a failed/unsupported hint must not turn into a per-frame call.
        surfaceFrameRateHintApplied = true;
        SurfaceRatePolicy.apply(holder.getSurface(), Build.VERSION.SDK_INT,
                surfaceFrameRateHz);
    }

    PointF menuControlCenter(int width, int height) {
        RasterSkin skin = skinFor(width, height);
        SkinTransform transform = skin.transform(width, height);
        return skin.menuControlCenter(transform);
    }

    private final class RenderThread extends Thread {
        private final SurfaceHolder holder;
        private boolean running = true;
        private boolean frameAvailable;
        /** Hardware Canvas is preferred, but one failed lock permanently selects the safe fallback
         * for this short-lived renderer. A new Surface gets a fresh RenderThread. */
        private boolean hardwareCanvasAvailable = true;

        private RenderThread(SurfaceHolder holder) {
            super("coffee-gb-android-video");
            this.holder = holder;
            setDaemon(true);
        }

        @Override
        public void run() {
            while (awaitFrame()) {
                NativeFrameStore active = frames;
                if (active == null || !surfaceReady) {
                    continue;
                }
                boolean anchor;
                int anchorPosts = 0;
                Consumer<Boolean> anchorCallback = null;
                synchronized (renderLock) {
                    anchor = benchmarkAnchorRequested;
                    if (anchor) {
                        benchmarkAnchorRequested = false;
                        anchorPosts = benchmarkAnchorPosts;
                        benchmarkAnchorPosts = 0;
                        anchorCallback = benchmarkAnchorCallback;
                        benchmarkAnchorCallback = null;
                    }
                }
                NativeFrameStore.Frame frame = anchor ? null : active.takeLatest();
                try {
                    boolean submitted = true;
                    if (anchor) {
                        // A single Canvas post can be coalesced before TimeStats records a
                        // present-to-present sample.  Keep the renderer quiescent and post a
                        // bounded, paced neutral sequence on the same SurfaceView layer.
                        for (int post = 0; post < anchorPosts; post++) {
                            submitted &= draw(null);
                            if (!submitted) {
                                break;
                            }
                            if (post + 1 < anchorPosts) {
                                SystemClock.sleep(BENCHMARK_ANCHOR_INTERVAL_MILLIS);
                            }
                        }
                    } else {
                        submitted = draw(frame);
                    }
                    if (anchor) {
                        if (anchorCallback != null) {
                            anchorCallback.accept(submitted);
                        }
                    } else if (BuildConfig.DIAGNOSTICS_ENABLED) {
                        if (submitted) {
                            active.frameSubmitted(frame);
                            if (active.submissionLimitReached()) {
                                // The measured window ends at the 600th successful submission.
                                // Post exactly one neutral drain after that boundary so the last
                                // measured buffer's present fence can enter SurfaceFlinger
                                // TimeStats.  It is not a NativeFrameStore frame and cannot become
                                // measured frame 601; the diagnostics final record is emitted only
                                // after this post returns.
                                SystemClock.sleep(BENCHMARK_DRAIN_DELAY_MILLIS);
                                boolean drainPosted = postBenchmarkDrain();
                                active.benchmarkDrainPosted(drainPosted);
                                synchronized (renderLock) {
                                    running = false;
                                    renderLock.notifyAll();
                                }
                            }
                        } else {
                            active.framePresentationLate(frame);
                        }
                    }
                } catch (RuntimeException presentationFailure) {
                    if (anchor) {
                        if (anchorCallback != null) {
                            anchorCallback.accept(false);
                        }
                    } else if (BuildConfig.DIAGNOSTICS_ENABLED) {
                        // Keep a diagnostic owner thread alive for a later Surface frame, but
                        // make the result reject this run as corrupted presentation output.
                        active.framePresentationCorrupt(frame);
                    } else {
                        // Preserve the pre-M2 production behavior when diagnostics are disabled:
                        // a real presentation failure remains an uncaught renderer failure.
                        throw presentationFailure;
                    }
                } finally {
                    active.finishDrawing(frame);
                }
            }
        }

        /** One diagnostic-only out-of-epoch neutral post; release builds never call this path. */
        private boolean postBenchmarkDrain() {
            if (!BuildConfig.DIAGNOSTICS_ENABLED) {
                return false;
            }
            try {
                return draw(null);
            } catch (RuntimeException failure) {
                // The diagnostic final record is still emitted with drain_success=false so the
                // host gate rejects the run.  Ordinary production presentation failures are
                // handled by the surrounding non-diagnostic branch and remain uncaught.
                return false;
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

        private boolean draw(NativeFrameStore.Frame frame) {
            Canvas canvas = lockCanvas();
            if (canvas == null) {
                return false;
            }
            try {
                canvas.drawColor(CANVAS_MATTE);
                RasterSkin skin = skinFor(canvas.getWidth(), canvas.getHeight());
                SkinTransform transform = skin.transform(canvas.getWidth(), canvas.getHeight());
                RectF display = skin.displayBounds(transform);
                canvas.drawRect(display, displayPaint);
                if (frame != null) {
                    bitmap.setPixels(frame.pixels(), 0, frame.width(), 0, 0,
                            frame.width(), frame.height());
                    source.set(0, 0, frame.width(), frame.height());
                    VideoGeometry.Viewport viewport = VideoGeometry.nearestFit(
                            frame.width(), frame.height(), Math.round(display.width()),
                            Math.round(display.height()));
                    destination.set(Math.round(display.left) + viewport.left(),
                            Math.round(display.top) + viewport.top(),
                            Math.round(display.left) + viewport.left() + viewport.width(),
                            Math.round(display.top) + viewport.top() + viewport.height());
                    canvas.drawBitmap(bitmap, source, destination, videoPaint);
                }
                MenuPresentation menu = menuPresentation;
                if (menu != null) {
                    menuRenderer.draw(canvas, menu, display);
                }
                drawTransientMessage(canvas, display);
                skin.draw(canvas, skinPaint, transform);
            } finally {
                holder.unlockCanvasAndPost(canvas);
            }
            return true;
        }

        /**
         * Uses the accelerated Surface canvas on API 26+ and falls back to the software canvas
         * when a device/framework cannot provide it. Both paths return a Canvas that must be
         * paired with the same {@link SurfaceHolder#unlockCanvasAndPost(Canvas)} call.
         */
        private Canvas lockCanvas() {
            if (hardwareCanvasAvailable) {
                try {
                    return holder.lockHardwareCanvas();
                } catch (RuntimeException hardwareFailure) {
                    hardwareCanvasAvailable = false;
                    if (surfaceLost()) {
                        return null;
                    }
                }
            }
            try {
                return holder.lockCanvas();
            } catch (RuntimeException softwareFailure) {
                if (surfaceLost()) {
                    return null;
                }
                throw softwareFailure;
            }
        }

        private void drawTransientMessage(Canvas canvas, RectF display) {
            String text = transientMessage;
            if (text == null || text.isBlank()
                    || SystemClock.uptimeMillis() >= transientExpiresAt) {
                return;
            }
            float scale = Math.max(1.0f, display.width() / 160.0f);
            float textSize = Math.max(12.0f, 7.0f * scale);
            transientTextPaint.setTextSize(textSize);
            Paint.FontMetrics metrics = transientTextPaint.getFontMetrics();
            float paddingX = Math.max(6.0f, 4.0f * scale);
            float paddingY = Math.max(4.0f, 2.0f * scale);
            float boxWidth = transientTextPaint.measureText(text) + 2.0f * paddingX;
            float boxHeight = metrics.descent - metrics.ascent + 2.0f * paddingY;
            float x = display.centerX() - boxWidth / 2.0f;
            float y = display.bottom - boxHeight - Math.max(4.0f, 4.0f * scale);
            int save = canvas.save();
            try {
                // The menu and message are host overlays, but neither may escape the exact
                // display aperture into the raster skin or letterbox.
                canvas.clipRect(display);
                float radius = Math.max(6.0f, 4.0f * scale);
                canvas.drawRoundRect(x, y, x + boxWidth, y + boxHeight, radius, radius,
                        transientPanelPaint);
                canvas.drawText(text, x + paddingX, y + paddingY - metrics.ascent,
                        transientTextPaint);
            } finally {
                canvas.restoreToCount(save);
            }
        }

        private boolean surfaceLost() {
            synchronized (renderLock) {
                // isValid() alone is racy; retirement identity/state is authoritative.
                return !running || renderThread != this || !surfaceReady
                        || !holder.getSurface().isValid();
            }
        }
    }

    private static final class TransientMessage {
        private static final long DURATION_MILLIS = 1_500L;

        private TransientMessage() {
        }
    }

    /** Small policy seam keeping API-level and frame-rate choices unit-testable without a Surface. */
    static final class SurfaceRatePolicy {

        enum Request {
            UNSUPPORTED,
            DEFAULT_COMPATIBILITY,
            DEFAULT_COMPATIBILITY_SEAMLESS_ONLY
        }

        private SurfaceRatePolicy() {
        }

        static Request requestAt(int sdkInt) {
            if (sdkInt < Build.VERSION_CODES.R) {
                return Request.UNSUPPORTED;
            }
            return sdkInt < Build.VERSION_CODES.S
                    ? Request.DEFAULT_COMPATIBILITY
                    : Request.DEFAULT_COMPATIBILITY_SEAMLESS_ONLY;
        }

        @TargetApi(Build.VERSION_CODES.S)
        static void apply(Surface surface, int sdkInt, float frameRateHz) {
            Request request = requestAt(sdkInt);
            if (request == Request.UNSUPPORTED || surface == null || !surface.isValid()) {
                return;
            }
            try {
                if (request == Request.DEFAULT_COMPATIBILITY_SEAMLESS_ONLY) {
                    // The strategy overload is API 31+. It is deliberately restricted to
                    // seamless switches so a game's hint cannot trigger a disruptive mode change.
                    surface.setFrameRate(frameRateHz,
                            Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                            Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
                } else {
                    // Android 11 has only the default two-argument game hint. Do not use
                    // FIXED_SOURCE: that compatibility mode is intended for video content.
                    surface.setFrameRate(frameRateHz, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
                }
            } catch (RuntimeException ignored) {
                // A device can reject a hint during a concurrent Surface teardown. Rendering
                // remains correct, and the next Surface lifecycle gets a fresh one-shot attempt.
            }
        }
    }
}
