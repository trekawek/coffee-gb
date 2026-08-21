package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.sgb.SgbDisplay;
import eu.rekawek.coffeegb.core.sgb.SuperGameboy;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.LongConsumer;

/**
 * Bounded native-frame hand-off between the controller event thread and an Android renderer.
 *
 * <p>Core display events expose producer-owned arrays. This store converts and copies each event
 * into one of three fixed ARGB buffers before the event callback returns. A consumer then claims
 * only the newest complete frame; older queued frames are discarded. This gives the renderer a
 * stable frame without retaining a core buffer, allocating a Bitmap, or allowing an unbounded
 * frame queue to accumulate behind a slow Surface.
 */
final class NativeFrameStore implements AutoCloseable {

    static final int MAX_WIDTH = SuperGameboy.SGB_DISPLAY_WIDTH;
    static final int MAX_HEIGHT = SuperGameboy.SGB_DISPLAY_HEIGHT;
    private static final int SLOT_COUNT = 3;

    interface Listener {
        /** Signals availability only; consumers must call {@link #takeLatest()} themselves. */
        void onFrameAvailable();
    }

    private enum SlotState {
        FREE,
        WRITING,
        PUBLISHED,
        DRAWING
    }

    private final Slot[] slots = new Slot[SLOT_COUNT];
    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    private final AndroidBenchmarkDiagnostics diagnostics;
    private final LongConsumer benchmarkBoundary;

    private long nextSequence;
    private long droppedFrames;
    private long benchmarkEpoch;
    private volatile boolean grayscale;
    /** True while the active session has a Super Game Boy presentation path. */
    private volatile boolean sgbOutput;
    private boolean closed;

    NativeFrameStore() {
        this(null, null);
    }

    NativeFrameStore(AndroidBenchmarkDiagnostics diagnostics) {
        this(diagnostics, null);
    }

    NativeFrameStore(AndroidBenchmarkDiagnostics diagnostics, LongConsumer benchmarkBoundary) {
        this.diagnostics = diagnostics;
        this.benchmarkBoundary = benchmarkBoundary;
        for (int index = 0; index < slots.length; index++) {
            slots[index] = new Slot();
        }
    }

    boolean diagnosticsEnabled() {
        return diagnostics != null && diagnostics.enabled();
    }

    void addListener(Listener listener) {
        listeners.add(listener);
        if (hasFrame()) {
            listener.onFrameAvailable();
        }
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    void publish(Display.DmgFrameReadyEvent event) {
        // SGB consumes the DMG event as its transfer input and synchronously publishes the final
        // SGB presentation afterward. Publishing both lets a renderer scheduled between those
        // callbacks briefly display the raw DMG palette. The active profile is latched by the
        // controller before emulation starts, so only the final SGB event reaches this store.
        if (sgbOutput) {
            return;
        }
        if (BuildConfig.DIAGNOSTICS_ENABLED) {
            recordFrameReady();
        }
        if (BuildConfig.DIAGNOSTICS_ENABLED && diagnostics != null && diagnostics.frameSink()) {
            return;
        }
        Slot slot = reserve(Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT);
        if (slot == null) {
            return;
        }
        int[] palette = grayscale ? Display.DmgFrameReadyEvent.COLORS_GRAYSCALE
                : Display.DmgFrameReadyEvent.COLORS;
        int[] source = event.pixels();
        for (int index = 0; index < source.length; index++) {
            slot.pixels[index] = palette[source[index]] | 0xff000000;
        }
        publish(slot);
    }

    void publish(Display.GbcFrameReadyEvent event) {
        if (BuildConfig.DIAGNOSTICS_ENABLED) {
            recordFrameReady();
        }
        if (BuildConfig.DIAGNOSTICS_ENABLED && diagnostics != null && diagnostics.frameSink()) {
            return;
        }
        Slot slot = reserve(Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT);
        if (slot == null) {
            return;
        }
        int[] source = event.pixels();
        for (int index = 0; index < source.length; index++) {
            slot.pixels[index] = Display.GbcFrameReadyEvent.translateGbcRgb(source[index])
                    | 0xff000000;
        }
        publish(slot);
    }

    /** Applies the Android DMG palette choice to future native frames. */
    void setGrayscale(boolean grayscale) {
        this.grayscale = grayscale;
    }

    boolean grayscale() {
        return grayscale;
    }

    /** Selects the presentation family for the active controller session. */
    void setHardwareProfile(HardwareProfile profile) {
        sgbOutput = Objects.requireNonNull(profile, "profile")
                .capabilities().superGameboyCommands();
    }

    void publish(SgbDisplay.SgbFrameReadyEvent event) {
        if (BuildConfig.DIAGNOSTICS_ENABLED) {
            recordFrameReady();
        }
        if (BuildConfig.DIAGNOSTICS_ENABLED && diagnostics != null && diagnostics.frameSink()) {
            return;
        }
        int width = event.includeBorder() ? SuperGameboy.SGB_DISPLAY_WIDTH : Display.DISPLAY_WIDTH;
        int height = event.includeBorder() ? SuperGameboy.SGB_DISPLAY_HEIGHT : Display.DISPLAY_HEIGHT;
        Slot slot = reserve(width, height);
        if (slot == null) {
            return;
        }
        event.toRgb(slot.pixels, false);
        makeOpaque(slot.pixels, Math.multiplyExact(width, height));
        publish(slot);
    }

    /**
     * Claims the latest complete frame for drawing, releasing any older queued frames. The caller
     * must later pair a non-null result with {@link #finishDrawing(Frame)}.
     */
    synchronized Frame takeLatest() {
        Slot newest = null;
        for (Slot slot : slots) {
            if (slot.state == SlotState.PUBLISHED
                    && (newest == null || slot.sequence > newest.sequence)) {
                newest = slot;
            }
        }
        if (newest == null) {
            return null;
        }
        for (Slot slot : slots) {
            if (slot != newest && slot.state == SlotState.PUBLISHED) {
                slot.state = SlotState.FREE;
                if (!slot.presentationConsumed) {
                    recordDroppedFrame();
                }
                slot.presentationConsumed = false;
            }
        }
        newest.state = SlotState.DRAWING;
        return new Frame(newest);
    }

    /**
     * Releases a frame after drawing. The most recent frame remains published for screenshot and
     * Surface recreation; a stale frame is immediately made reusable.
     */
    synchronized void finishDrawing(Frame frame) {
        if (frame == null || frame.slot.state != SlotState.DRAWING) {
            return;
        }
        frame.slot.state = frame.slot.sequence == nextSequence ? SlotState.PUBLISHED : SlotState.FREE;
    }

    /** Returns a caller-owned native-frame copy for an explicit screenshot request. */
    synchronized Snapshot snapshot() {
        Slot newest = null;
        for (Slot slot : slots) {
            if ((slot.state == SlotState.PUBLISHED || slot.state == SlotState.DRAWING)
                    && slot.sequence == nextSequence
                    && (newest == null || slot.sequence > newest.sequence)) {
                newest = slot;
            }
        }
        if (newest == null) {
            return null;
        }
        int length = Math.multiplyExact(newest.width, newest.height);
        return new Snapshot(newest.width, newest.height, Arrays.copyOf(newest.pixels, length));
    }

    synchronized void clear() {
        // Keep the last authoritative profile while a replacement is being parsed or selected.
        // openRom() clears the presentation before it knows whether the old session will actually
        // be replaced; a failed/rejected load must not let that still-active SGB session publish
        // its raw DMG transfer input. The next HardwareProfileEvent overwrites this gate.
        nextSequence++;
        for (Slot slot : slots) {
            if (slot.state == SlotState.PUBLISHED) {
                slot.state = SlotState.FREE;
                if (!slot.presentationConsumed) {
                    recordDroppedFrame();
                }
                slot.presentationConsumed = false;
            }
        }
        notifyListeners();
    }

    synchronized long droppedFrames() {
        return droppedFrames;
    }

    /** Starts a new benchmark epoch while the anchor-paused renderer is quiescent. */
    synchronized void beginBenchmarkEpoch(long generation) {
        if (generation <= 0L) {
            throw new IllegalArgumentException("Benchmark generation must be positive");
        }
        benchmarkEpoch = generation;
        nextSequence = 0L;
        for (Slot slot : slots) {
            slot.state = SlotState.FREE;
            slot.presentationConsumed = false;
            slot.epoch = generation;
        }
        // The anchor renderer is quiescent at this boundary. Do not wake it with a synthetic
        // null draw: the first real measured publication must schedule the renderer callback,
        // otherwise a post-baseline neutral buffer could become frame 601.
    }

    /** Records a Surface BufferQueue submission after the canvas post has completed. */
    synchronized void frameSubmitted(Frame frame) {
        if (frame != null) {
            frame.slot.presentationConsumed = true;
            if (BuildConfig.DIAGNOSTICS_ENABLED && diagnostics != null
                    && diagnostics.acceptsFrameEpoch(frame.epoch())) {
                diagnostics.frameSubmitted(frame.sequence());
            }
        }
    }

    boolean submissionLimitReached() {
        return BuildConfig.DIAGNOSTICS_ENABLED && diagnostics != null
                && diagnostics.submissionLimitReached();
    }

    /** Completes the benchmark-only out-of-epoch drain without exposing diagnostics to release. */
    void benchmarkDrainPosted(boolean success) {
        if (BuildConfig.DIAGNOSTICS_ENABLED && diagnostics != null) {
            diagnostics.benchmarkDrainPosted(success);
        }
    }

    /** Compatibility seam for existing package tests; new renderer code uses frameSubmitted. */
    synchronized void framePresented(Frame frame) {
        frameSubmitted(frame);
    }

    synchronized void framePresentationLate(Frame frame) {
        if (frame != null) {
            frame.slot.presentationConsumed = true;
            if (BuildConfig.DIAGNOSTICS_ENABLED && diagnostics != null
                    && diagnostics.acceptsFrameEpoch(frame.epoch())) {
                diagnostics.frameLate();
            }
        }
    }

    synchronized void framePresentationCorrupt(Frame frame) {
        if (frame != null) {
            frame.slot.presentationConsumed = true;
            if (BuildConfig.DIAGNOSTICS_ENABLED && diagnostics != null
                    && diagnostics.acceptsFrameEpoch(frame.epoch())) {
                diagnostics.frameCorrupt();
            }
        }
    }

    synchronized int bufferCount() {
        return slots.length;
    }

    synchronized int[] bufferAt(int index) {
        return slots[index].pixels;
    }

    @Override
    public synchronized void close() {
        closed = true;
        listeners.clear();
        for (Slot slot : slots) {
            slot.state = SlotState.FREE;
        }
    }

    private synchronized boolean hasFrame() {
        for (Slot slot : slots) {
            if (slot.state == SlotState.PUBLISHED) {
                return true;
            }
        }
        return false;
    }

    private synchronized Slot reserve(int width, int height) {
        if (closed || width < 1 || height < 1 || width > MAX_WIDTH || height > MAX_HEIGHT) {
            recordDroppedFrame();
            return null;
        }
        Slot chosen = null;
        for (Slot slot : slots) {
            if (slot.state == SlotState.FREE) {
                chosen = slot;
                break;
            }
        }
        if (chosen == null) {
            for (Slot slot : slots) {
                if (slot.state == SlotState.PUBLISHED
                        && (chosen == null || slot.sequence < chosen.sequence)) {
                    chosen = slot;
                }
            }
        }
        if (chosen == null) {
            // Rendering owns every fixed buffer. Dropping one arriving frame is preferable to
            // blocking the controller thread or allocating a fourth frame.
            recordDroppedFrame();
            return null;
        }
        if (chosen.state == SlotState.PUBLISHED) {
            // Reusing a published slot discards that frame before presentation. Count it here;
            // takeLatest() cannot count it again because the slot becomes WRITING below.
            if (!chosen.presentationConsumed) {
                recordDroppedFrame();
            }
            chosen.presentationConsumed = false;
        }
        chosen.state = SlotState.WRITING;
        chosen.width = width;
        chosen.height = height;
        chosen.epoch = benchmarkEpoch;
        return chosen;
    }

    private void publish(Slot slot) {
        synchronized (this) {
            if (closed || slot.state != SlotState.WRITING) {
                recordDroppedFrame();
                return;
            }
            slot.sequence = ++nextSequence;
            slot.presentationConsumed = false;
            slot.state = SlotState.PUBLISHED;
        }
        notifyListeners();
    }

    private void notifyListeners() {
        for (Listener listener : listeners) {
            listener.onFrameAvailable();
        }
    }

    private void recordDroppedFrame() {
        droppedFrames++;
        if (BuildConfig.DIAGNOSTICS_ENABLED && diagnostics != null) {
            diagnostics.frameDropped();
        }
    }

    private void recordFrameReady() {
        if (diagnostics == null) {
            return;
        }
        boolean boundary = diagnostics.frameReady();
        if (boundary && benchmarkBoundary != null) {
            benchmarkBoundary.accept(diagnostics.benchmarkGeneration());
        }
    }

    private static void makeOpaque(int[] pixels, int length) {
        for (int index = 0; index < length; index++) {
            pixels[index] |= 0xff000000;
        }
    }

    static final class Frame {
        private final Slot slot;

        private Frame(Slot slot) {
            this.slot = slot;
        }

        long sequence() {
            return slot.sequence;
        }

        long epoch() {
            return slot.epoch;
        }

        int[] pixels() {
            return slot.pixels;
        }

        int width() {
            return slot.width;
        }

        int height() {
            return slot.height;
        }
    }

    record Snapshot(int width, int height, int[] pixels) {

        String sha256() {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                updateInt(digest, width);
                updateInt(digest, height);
                for (int pixel : pixels) {
                    updateInt(digest, pixel);
                }
                byte[] hash = digest.digest();
                StringBuilder text = new StringBuilder(hash.length * 2);
                for (byte value : hash) {
                    text.append(String.format("%02x", value));
                }
                return text.toString();
            } catch (NoSuchAlgorithmException failure) {
                throw new IllegalStateException("SHA-256 is unavailable", failure);
            }
        }

        private static void updateInt(MessageDigest digest, int value) {
            digest.update((byte) (value >>> 24));
            digest.update((byte) (value >>> 16));
            digest.update((byte) (value >>> 8));
            digest.update((byte) value);
        }
    }

    private static final class Slot {
        private final int[] pixels = new int[MAX_WIDTH * MAX_HEIGHT];
        private SlotState state = SlotState.FREE;
        private int width;
        private int height;
        private long sequence;
        private long epoch;
        /** True once the renderer has consumed this published frame; prevents double drop counts. */
        private boolean presentationConsumed;
    }
}
