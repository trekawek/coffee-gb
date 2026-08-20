package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.sgb.SgbDisplay;
import eu.rekawek.coffeegb.core.sgb.SuperGameboy;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.CopyOnWriteArraySet;

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

    private long nextSequence;
    private long droppedFrames;
    private volatile boolean grayscale;
    private boolean closed;

    NativeFrameStore() {
        for (int index = 0; index < slots.length; index++) {
            slots[index] = new Slot();
        }
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
        Slot slot = reserve(Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT);
        if (slot == null) {
            return;
        }
        event.toRgb(slot.pixels, grayscale);
        makeOpaque(slot.pixels, Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT);
        publish(slot);
    }

    void publish(Display.GbcFrameReadyEvent event) {
        Slot slot = reserve(Display.DISPLAY_WIDTH, Display.DISPLAY_HEIGHT);
        if (slot == null) {
            return;
        }
        event.toRgb(slot.pixels, false);
        makeOpaque(slot.pixels, Display.DISPLAY_WIDTH * Display.DISPLAY_HEIGHT);
        publish(slot);
    }

    /** Applies the Android DMG palette choice to future native frames. */
    void setGrayscale(boolean grayscale) {
        this.grayscale = grayscale;
    }

    boolean grayscale() {
        return grayscale;
    }

    void publish(SgbDisplay.SgbFrameReadyEvent event) {
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
        nextSequence++;
        for (Slot slot : slots) {
            if (slot.state == SlotState.PUBLISHED) {
                slot.state = SlotState.FREE;
            }
        }
        notifyListeners();
    }

    synchronized long droppedFrames() {
        return droppedFrames;
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
            droppedFrames++;
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
            droppedFrames++;
            return null;
        }
        chosen.state = SlotState.WRITING;
        chosen.width = width;
        chosen.height = height;
        return chosen;
    }

    private void publish(Slot slot) {
        synchronized (this) {
            if (closed || slot.state != SlotState.WRITING) {
                return;
            }
            slot.sequence = ++nextSequence;
            slot.state = SlotState.PUBLISHED;
        }
        notifyListeners();
    }

    private void notifyListeners() {
        for (Listener listener : listeners) {
            listener.onFrameAvailable();
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
    }
}
