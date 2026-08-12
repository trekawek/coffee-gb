package eu.rekawek.coffeegb.android.menu;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.artwork.MenuArgbFrame;
import eu.rekawek.coffeegb.ui.menu.artwork.MenuRect;
import eu.rekawek.coffeegb.ui.menu.artwork.MenuViewport;
import eu.rekawek.coffeegb.ui.menu.artwork.Proposal3MenuCompositor;

import java.util.Objects;
import java.util.Optional;

/**
 * Android adapter for the portable Proposal 3 raster compositor.
 *
 * <p>This class deliberately contains no text, paths, panels, icons, or other UI drawing. The
 * portable compositor selects the packaged PNG and produces the complete canonical 924x736
 * frame. Android only converts that immutable frame to a Bitmap, aspect-fits it into the skin's
 * display aperture, and paints the shell around it.
 */
public final class MenuRenderer {

    /** Dark letterbox matte visible only in the display aperture around the fitted PNG. */
    static final int MENU_MATTE = Color.rgb(18, 27, 20);

    private final Proposal3MenuCompositor compositor;
    private final Paint mattePaint = new Paint();
    private final Paint bitmapPaint = new Paint();
    private final Rect source = new Rect();
    private final Rect destination = new Rect();

    /** The frame identity that produced {@link #cachedBitmap}. */
    private MenuArgbFrame cachedFrame;
    private Bitmap cachedBitmap;

    public MenuRenderer() {
        this(new Proposal3MenuCompositor());
    }

    MenuRenderer(Proposal3MenuCompositor compositor) {
        this.compositor = Objects.requireNonNull(compositor, "compositor");
        mattePaint.setStyle(Paint.Style.FILL);
        mattePaint.setColor(MENU_MATTE);
        bitmapPaint.setFilterBitmap(false);
        bitmapPaint.setDither(false);
        bitmapPaint.setAntiAlias(false);
    }

    /**
     * Paints one complete Proposal 3 frame into the display aperture.
     *
     * <p>The matte is painted before the fitted artwork. The caller paints the live game frame
     * first and the raster skin last, so the skin remains the visible shell and no menu pixels can
     * escape the transparent aperture.
     */
    public void draw(Canvas canvas, MenuPresentation presentation, RectF displayBounds) {
        if (canvas == null || presentation == null || !presentation.visible()
                || displayBounds == null || !Float.isFinite(displayBounds.left)
                || !Float.isFinite(displayBounds.top) || !Float.isFinite(displayBounds.right)
                || !Float.isFinite(displayBounds.bottom)) {
            return;
        }
        Rect aperture = roundedBounds(displayBounds);
        if (aperture.width() <= 0 || aperture.height() <= 0) {
            return;
        }

        canvas.drawRect(aperture, mattePaint);
        Optional<MenuArgbFrame> composed = compositor.compose(presentation);
        if (composed.isEmpty()) {
            return;
        }
        MenuArgbFrame frame = composed.get();
        if (frame.width() != MenuViewport.SOURCE_WIDTH
                || frame.height() != MenuViewport.SOURCE_HEIGHT) {
            throw new IllegalStateException("Proposal 3 compositor returned "
                    + frame.width() + "x" + frame.height() + "; expected "
                    + MenuViewport.SOURCE_WIDTH + "x" + MenuViewport.SOURCE_HEIGHT);
        }
        Bitmap bitmap = bitmapFor(frame);
        source.set(0, 0, frame.width(), frame.height());
        destination.set(fitDestination(aperture));

        int save = canvas.save();
        try {
            canvas.clipRect(aperture);
            canvas.drawBitmap(bitmap, source, destination, bitmapPaint);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /**
     * Returns the exact integer destination used for the canonical source inside an aperture.
     * This is package-private so Android tests can lock down the no-stretch contract.
     */
    static Rect fitDestination(Rect aperture) {
        Objects.requireNonNull(aperture, "aperture");
        if (aperture.width() <= 0 || aperture.height() <= 0) {
            throw new IllegalArgumentException("A menu aperture must be non-empty");
        }
        MenuViewport viewport = MenuViewport.fit(aperture.width(), aperture.height());
        MenuRect content = viewport.contentBounds();
        return new Rect(aperture.left + content.x(), aperture.top + content.y(),
                aperture.left + content.right(), aperture.top + content.bottom());
    }

    /** Visible to package-local tests; the production cache is identity-based, not value-based. */
    Bitmap cachedBitmapForTest() {
        return cachedBitmap;
    }

    /** Visible to package-local tests; the production cache is identity-based, not value-based. */
    MenuArgbFrame cachedFrameForTest() {
        return cachedFrame;
    }

    private Bitmap bitmapFor(MenuArgbFrame frame) {
        if (frame == cachedFrame && cachedBitmap != null && !cachedBitmap.isRecycled()) {
            return cachedBitmap;
        }
        if (cachedBitmap != null && !cachedBitmap.isRecycled()) {
            cachedBitmap.recycle();
        }
        cachedFrame = frame;
        cachedBitmap = Bitmap.createBitmap(frame.copyPixels(), frame.width(), frame.height(),
                Bitmap.Config.ARGB_8888);
        cachedBitmap.setHasAlpha(true);
        return cachedBitmap;
    }

    private static Rect roundedBounds(RectF bounds) {
        return new Rect(Math.round(bounds.left), Math.round(bounds.top),
                Math.round(bounds.right), Math.round(bounds.bottom));
    }
}
