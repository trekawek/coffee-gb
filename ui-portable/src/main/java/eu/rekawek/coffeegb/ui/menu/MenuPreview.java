package eu.rekawek.coffeegb.ui.menu;

import java.util.Arrays;

/** Bounded immutable pixel preview published to the menu render thread. */
public final class MenuPreview {

    /**
     * The largest raw Game Boy image we can retain is a Super Game Boy frame including its
     * border.  Keeping this bound here makes the hand-off safe for DMG, CGB, and SGB without
     * making a menu preview a live display buffer.
     */
    public static final int MAX_PIXELS = 256 * 224;

    public enum State {
        LOADING,
        EMPTY,
        READY
    }

    private static final MenuPreview LOADING = new MenuPreview(State.LOADING, 0, 0, null);
    private static final MenuPreview EMPTY = new MenuPreview(State.EMPTY, 0, 0, null);

    private final State state;
    private final int width;
    private final int height;
    private final int[] pixels;

    private MenuPreview(State state, int width, int height, int[] pixels) {
        this.state = state;
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    public static MenuPreview loading() {
        return LOADING;
    }

    public static MenuPreview empty() {
        return EMPTY;
    }

    public static MenuPreview ready(int width, int height, int[] pixels) {
        if (width <= 0 || height <= 0 || pixels == null
                || pixels.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException("A ready preview needs matching positive dimensions");
        }
        if (pixels.length > MAX_PIXELS) {
            throw new IllegalArgumentException("Menu previews must remain bounded");
        }
        return new MenuPreview(State.READY, width, height, Arrays.copyOf(pixels, pixels.length));
    }

    public State state() {
        return state;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Returns a detached bounded copy; callers never receive producer-owned storage. */
    public int[] copyPixels() {
        return pixels == null ? new int[0] : Arrays.copyOf(pixels, pixels.length);
    }
}
