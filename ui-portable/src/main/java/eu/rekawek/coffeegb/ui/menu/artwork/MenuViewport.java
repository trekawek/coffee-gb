package eu.rekawek.coffeegb.ui.menu.artwork;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Deterministic integer aspect-fit placement for the canonical 924x736 menu screen.
 *
 * <p>The limiting cross-product is selected with {@code long} arithmetic. The other destination
 * dimension is the mathematical floor, and the left/top placement uses integer division; any odd
 * remainder is therefore assigned to the right/bottom bars. The destination rectangle is
 * half-open, so its right and bottom edges are never valid hit coordinates.
 */
public final class MenuViewport {

    /** Width of the canonical visible source crop. */
    public static final int SOURCE_WIDTH = 924;

    /** Height of the canonical visible source crop. */
    public static final int SOURCE_HEIGHT = 736;

    private final int viewWidth;
    private final int viewHeight;
    private final MenuRect contentBounds;

    public MenuViewport(int viewWidth, int viewHeight) {
        if (viewWidth <= 0 || viewHeight <= 0) {
            throw new IllegalArgumentException("A menu viewport must have positive dimensions");
        }
        this.viewWidth = viewWidth;
        this.viewHeight = viewHeight;
        long widthCrossProduct = (long) viewWidth * SOURCE_HEIGHT;
        long heightCrossProduct = (long) viewHeight * SOURCE_WIDTH;
        int contentWidth;
        int contentHeight;
        if (widthCrossProduct <= heightCrossProduct) {
            contentWidth = viewWidth;
            contentHeight = (int) (widthCrossProduct / SOURCE_WIDTH);
        } else {
            contentHeight = viewHeight;
            contentWidth = (int) (heightCrossProduct / SOURCE_HEIGHT);
        }
        if (contentWidth <= 0 || contentHeight <= 0) {
            throw new IllegalArgumentException("Viewport is too small for a positive menu placement");
        }
        int left = (viewWidth - contentWidth) / 2;
        int top = (viewHeight - contentHeight) / 2;
        contentBounds = new MenuRect(left, top, contentWidth, contentHeight);
    }

    /** Creates an aspect-fit transform for a destination viewport. */
    public static MenuViewport fit(int viewWidth, int viewHeight) {
        return new MenuViewport(viewWidth, viewHeight);
    }

    public int viewWidth() {
        return viewWidth;
    }

    public int viewHeight() {
        return viewHeight;
    }

    /** Returns the integer destination rectangle as {@code [left,right) x [top,bottom)}. */
    public MenuRect contentBounds() {
        return contentBounds;
    }

    /** Alias for {@link #contentBounds()} for renderers that call the rectangle a placement. */
    public MenuRect destinationRect() {
        return contentBounds;
    }

    /** Returns whether an integer destination pixel belongs to the content, not a letterbox bar. */
    public boolean containsView(int viewX, int viewY) {
        return contentBounds.contains(viewX, viewY);
    }

    /** Half-open-safe inverse mapping for a continuous destination coordinate. */
    public Optional<MenuPoint> viewToSource(MenuPoint viewPoint) {
        MenuPoint point = Objects.requireNonNull(viewPoint, "viewPoint");
        if (point.x() < contentBounds.x() || point.x() >= contentBounds.right()
                || point.y() < contentBounds.y() || point.y() >= contentBounds.bottom()) {
            return Optional.empty();
        }
        double sourceX = (point.x() - contentBounds.x()) * SOURCE_WIDTH / contentBounds.width();
        double sourceY = (point.y() - contentBounds.y()) * SOURCE_HEIGHT / contentBounds.height();
        return Optional.of(new MenuPoint(sourceX, sourceY));
    }

    /** Alias for {@link #viewToSource(MenuPoint)}. */
    public Optional<MenuPoint> inverse(MenuPoint viewPoint) {
        return viewToSource(viewPoint);
    }

    /**
     * Maps an integer destination pixel to an integer source pixel. The right and bottom edges,
     * bars, and any point outside the destination viewport return {@link OptionalInt#empty()}.
     */
    public Optional<MenuPoint> viewToSource(int viewX, int viewY) {
        OptionalInt sourceX = sourceX(viewX);
        OptionalInt sourceY = sourceY(viewY);
        if (sourceX.isEmpty() || sourceY.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MenuPoint(sourceX.getAsInt(), sourceY.getAsInt()));
    }

    /** Returns the source x pixel selected by a destination pixel, or empty in bars/edges. */
    public OptionalInt sourceX(int viewX) {
        if (viewX < contentBounds.x() || viewX >= contentBounds.right()) {
            return OptionalInt.empty();
        }
        long relative = (long) viewX - contentBounds.x();
        return OptionalInt.of((int) (relative * SOURCE_WIDTH / contentBounds.width()));
    }

    /** Returns the source y pixel selected by a destination pixel, or empty in bars/edges. */
    public OptionalInt sourceY(int viewY) {
        if (viewY < contentBounds.y() || viewY >= contentBounds.bottom()) {
            return OptionalInt.empty();
        }
        long relative = (long) viewY - contentBounds.y();
        return OptionalInt.of((int) (relative * SOURCE_HEIGHT / contentBounds.height()));
    }

    /** Maps a source pixel edge coordinate into the integer destination placement. */
    public int sourceToViewX(int sourceX) {
        validateSourceX(sourceX);
        return contentBounds.x() + (int) ((long) sourceX * contentBounds.width() / SOURCE_WIDTH);
    }

    /** Maps a source pixel edge coordinate into the integer destination placement. */
    public int sourceToViewY(int sourceY) {
        validateSourceY(sourceY);
        return contentBounds.y() + (int) ((long) sourceY * contentBounds.height() / SOURCE_HEIGHT);
    }

    /** Maps a source pixel edge coordinate pair into the integer destination placement. */
    public MenuPoint sourceToView(int sourceX, int sourceY) {
        return new MenuPoint(sourceToViewX(sourceX), sourceToViewY(sourceY));
    }

    /** Half-open alias retained for callers that describe the placement as content. */
    public boolean isInsideContent(int viewX, int viewY) {
        return containsView(viewX, viewY);
    }

    private static void validateSourceX(int sourceX) {
        if (sourceX < 0 || sourceX > SOURCE_WIDTH) {
            throw new IllegalArgumentException("sourceX must be in [0, " + SOURCE_WIDTH + "]");
        }
    }

    private static void validateSourceY(int sourceY) {
        if (sourceY < 0 || sourceY > SOURCE_HEIGHT) {
            throw new IllegalArgumentException("sourceY must be in [0, " + SOURCE_HEIGHT + "]");
        }
    }
}
