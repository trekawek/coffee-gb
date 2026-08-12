package eu.rekawek.coffeegb.ui.menu.artwork;

/**
 * An immutable integer rectangle represented as {@code [x, right) x [y, bottom)}.
 */
public record MenuRect(int x, int y, int width, int height) {

    public MenuRect {
        if (x < 0 || y < 0 || width <= 0 || height <= 0) {
            throw new IllegalArgumentException("A menu rectangle must be non-empty and non-negative");
        }
        if ((long) x + width > Integer.MAX_VALUE || (long) y + height > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("A menu rectangle must fit in integer coordinates");
        }
    }

    /** Returns the exclusive right edge. */
    public int right() {
        return x + width;
    }

    /** Returns the exclusive bottom edge. */
    public int bottom() {
        return y + height;
    }

    /** Returns whether an integer point is inside this half-open rectangle. */
    public boolean contains(int pointX, int pointY) {
        return pointX >= x && pointX < right() && pointY >= y && pointY < bottom();
    }
}
