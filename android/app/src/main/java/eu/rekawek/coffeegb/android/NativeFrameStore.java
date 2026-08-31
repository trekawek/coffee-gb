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

    /** Raster presentation paired with one published native frame. */
    enum Presentation {
        DMG,
        CGB,
        SGB_BORDER
    }

    static final int MAX_WIDTH = SuperGameboy.SGB_DISPLAY_WIDTH;
    static final int MAX_HEIGHT = SuperGameboy.SGB_DISPLAY_HEIGHT;
    private static final int SLOT_COUNT = 3;
    private static final int SLOT_INDEX_BITS = 2;
    private static final long SLOT_INDEX_MASK = (1L << SLOT_INDEX_BITS) - 1L;
    private static final long MAX_RESERVATION_TOKEN =
            Long.MAX_VALUE >>> SLOT_INDEX_BITS;

    interface Listener {
        /** Signals a frame or presentation change; consumers must query the store themselves. */
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
    private long nextReservationToken;
    private long reservationGeneration;
    private long droppedFrames;
    private long benchmarkEpoch;
    private volatile boolean grayscale;
    private volatile HardwareProfile.Family hardwareFamily = HardwareProfile.Family.DMG;
    /** Current fallback for redraws which do not claim a frame, such as Surface recreation. */
    private volatile Presentation presentation = Presentation.DMG;
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
            slots[index] = new Slot(index);
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
        if (hardwareFamily == HardwareProfile.Family.SGB) {
            return;
        }
        if (BuildConfig.DIAGNOSTICS_ENABLED) {
            recordFrameReady();
        }
        if (BuildConfig.DIAGNOSTICS_ENABLED && diagnostics != null && diagnostics.frameSink()) {
            return;
        }
        long claim = reserve(Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT,
                basePresentation());
        if (claim == 0L) {
            return;
        }
        Slot slot = slotForClaim(claim);
        int[] palette = grayscale ? Display.DmgFrameReadyEvent.COLORS_GRAYSCALE
                : Display.DmgFrameReadyEvent.COLORS;
        int[] source = event.pixels();
        for (int index = 0; index < source.length; index++) {
            slot.pixels[index] = palette[source[index]] | 0xff000000;
        }
        publish(claim);
    }

    void publish(Display.GbcFrameReadyEvent event) {
        if (BuildConfig.DIAGNOSTICS_ENABLED) {
            recordFrameReady();
        }
        if (BuildConfig.DIAGNOSTICS_ENABLED && diagnostics != null && diagnostics.frameSink()) {
            return;
        }
        long claim = reserve(Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT,
                basePresentation());
        if (claim == 0L) {
            return;
        }
        Slot slot = slotForClaim(claim);
        int[] source = event.pixels();
        for (int index = 0; index < source.length; index++) {
            slot.pixels[index] = Display.GbcFrameReadyEvent.translateGbcRgb(source[index])
                    | 0xff000000;
        }
        publish(claim);
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
        HardwareProfile checked = Objects.requireNonNull(profile, "profile");
        HardwareProfile.Family family = checked.family();
        Presentation next = family == HardwareProfile.Family.CGB
                ? Presentation.CGB : Presentation.DMG;
        boolean changed;
        synchronized (this) {
            hardwareFamily = family;
            changed = presentation != next;
            presentation = next;
        }
        if (changed) {
            notifyListeners();
        }
    }

    Presentation presentation() {
        return presentation;
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
        Presentation framePresentation = hardwareFamily == HardwareProfile.Family.SGB
                && event.includeBorder() ? Presentation.SGB_BORDER : basePresentation();
        long claim = reserve(width, height, framePresentation);
        if (claim == 0L) {
            return;
        }
        Slot slot = slotForClaim(claim);
        try {
            int expectedLength = Math.multiplyExact(width, height);
            int[] source = event.buffer();
            if (source == null || source.length != expectedLength) {
                throw new IllegalArgumentException(
                        "SGB frame pixel count must be exactly " + expectedLength);
            }
            // SgbFrameReadyEvent is callback-scoped; fuse RGB copying and alpha insertion while
            // the producer still owns the source. A failed conversion must not strand the slot in
            // WRITING, otherwise one malformed callback permanently reduces the three-slot pool.
            event.copyToOpaqueArgb(slot.pixels);
        } catch (RuntimeException | Error failure) {
            abortWriting(claim);
            throw failure;
        }
        publish(claim);
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
        // its raw DMG transfer input or change its skin. The next HardwareProfileEvent overwrites
        // this retained state.
        clearFrames();
        notifyListeners();
    }

    /** Clears a successfully stopped session and restores the no-system DMG presentation. */
    synchronized void clearToDefaultPresentation() {
        hardwareFamily = HardwareProfile.Family.DMG;
        presentation = Presentation.DMG;
        clearFrames();
        notifyListeners();
    }

    private void clearFrames() {
        reservationGeneration++;
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
        reservationGeneration++;
        nextSequence = 0L;
        for (Slot slot : slots) {
            // A producer may still be converting a callback-scoped frame outside this lock.
            // Keep its WRITING claim until that owner publishes or aborts; reusing its pixels
            // here would let the old conversion corrupt a newer reservation.
            if (slot.state != SlotState.WRITING) {
                slot.state = SlotState.FREE;
                slot.presentationConsumed = false;
                slot.epoch = generation;
            }
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
            // Leave WRITING claims owned until their conversion returns. No future reservation
            // can observe a closed store, and releasing the claim here could race a new writer in
            // a concurrently reused store instance.
            if (slot.state != SlotState.WRITING) {
                slot.state = SlotState.FREE;
            }
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

    private Presentation basePresentation() {
        return hardwareFamily == HardwareProfile.Family.CGB
                ? Presentation.CGB : Presentation.DMG;
    }

    /** Compatibility seam retained for package tests which reserve a synthetic frame. */
    private long reserve(int width, int height) {
        return reserve(width, height, presentation);
    }

    private synchronized long reserve(int width, int height,
            Presentation framePresentation) {
        if (closed || width < 1 || height < 1 || width > MAX_WIDTH || height > MAX_HEIGHT) {
            recordDroppedFrame();
            return 0L;
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
            return 0L;
        }
        if (chosen.state == SlotState.PUBLISHED) {
            // Reusing a published slot discards that frame before presentation. Count it here;
            // takeLatest() cannot count it again because the slot becomes WRITING below.
            if (!chosen.presentationConsumed) {
                recordDroppedFrame();
            }
            chosen.presentationConsumed = false;
        }
        long token = nextReservationToken + 1L;
        if (token <= 0L || token > MAX_RESERVATION_TOKEN) {
            token = 1L;
        }
        nextReservationToken = token;
        long claim = (token << SLOT_INDEX_BITS) | chosen.index;
        chosen.state = SlotState.WRITING;
        chosen.width = width;
        chosen.height = height;
        chosen.presentation = Objects.requireNonNull(framePresentation, "framePresentation");
        chosen.epoch = benchmarkEpoch;
        chosen.reservationClaim = claim;
        chosen.reservationGeneration = reservationGeneration;
        return claim;
    }

    private void publish(long claim) {
        Slot slot = slotForClaim(claim);
        synchronized (this) {
            boolean owner = slot.state == SlotState.WRITING
                    && slot.reservationClaim == claim;
            boolean current = owner && slot.reservationGeneration == reservationGeneration;
            if (closed || !current) {
                if (owner) {
                    slot.state = SlotState.FREE;
                    slot.presentationConsumed = false;
                }
                recordDroppedFrame();
                return;
            }
            slot.sequence = ++nextSequence;
            slot.presentationConsumed = false;
            slot.state = SlotState.PUBLISHED;
            presentation = slot.presentation;
        }
        notifyListeners();
    }

    private synchronized void abortWriting(long claim) {
        Slot slot = slotForClaim(claim);
        if (slot.state == SlotState.WRITING
                && slot.reservationClaim == claim) {
            slot.state = SlotState.FREE;
            slot.presentationConsumed = false;
        }
    }

    private Slot slotForClaim(long claim) {
        return slots[(int) (claim & SLOT_INDEX_MASK)];
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

        Presentation presentation() {
            return slot.presentation;
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
        private final int index;
        private final int[] pixels = new int[MAX_WIDTH * MAX_HEIGHT];
        private SlotState state = SlotState.FREE;
        private int width;
        private int height;
        private Presentation presentation = Presentation.DMG;
        private long sequence;
        private long epoch;
        private long reservationClaim;
        private long reservationGeneration;
        /** True once the renderer has consumed this published frame; prevents double drop counts. */
        private boolean presentationConsumed;

        private Slot(int index) {
            this.index = index;
        }
    }

}
