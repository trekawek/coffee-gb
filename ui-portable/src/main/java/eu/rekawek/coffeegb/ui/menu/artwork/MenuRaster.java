package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuPreview;

import java.util.Objects;

/** Package-private mutable raster used only during one portable composition. */
final class MenuRaster {

    enum HorizontalAlignment {
        LEFT,
        CENTER,
        RIGHT
    }

    static final int WIDTH = MenuArtworkCatalog.PACKAGED_WIDTH;
    static final int HEIGHT = MenuArtworkCatalog.PACKAGED_HEIGHT;

    static final int INK = 0xff1d2a1c;
    static final int PAPER_TEXT = 0xffe4dfb5;
    static final int PAPER = 0xffd4d2ad;

    static final int SLIDER_EMPTY = PAPER;
    static final int SLIDER_FILL = 0xff667657;
    static final int FOCUS_ARROW_WIDTH = 18;
    static final int FOCUS_ARROW_HEIGHT = 20;

    private final int[] pixels;

    MenuRaster(int[] pixels) {
        if (pixels == null || pixels.length != WIDTH * HEIGHT) {
            throw new IllegalArgumentException("A Proposal 3 raster must be 924x736");
        }
        this.pixels = pixels;
    }

    int[] pixels() {
        return pixels;
    }

    /** Draws the reusable row-relative slider used by every screen. */
    void drawSlider(MenuRect rail, int progress) {
        Objects.requireNonNull(rail, "rail");
        if (rail.width() < 48 || rail.height() < 7) {
            throw new IllegalArgumentException("Slider rail is too small");
        }
        int bounded = Math.max(0, Math.min(100, progress));
        int inset = Math.max(2, Math.min(4, rail.height() / 3));
        fill(rail, INK);
        MenuRect interior = new MenuRect(rail.x() + inset, rail.y() + inset,
                rail.width() - inset * 2, rail.height() - inset * 2);
        fill(interior, SLIDER_EMPTY);

        int knobWidth = 20;
        int knobHeight = 34;
        int travel = Math.max(0, rail.width() - knobWidth);
        int knobLeft = rail.x() + (int) ((long) travel * bounded / 100L);
        int knobCenter = knobLeft + knobWidth / 2;
        int fillRight = Math.max(interior.x(), Math.min(interior.right(), knobCenter));
        if (fillRight > interior.x()) {
            fill(new MenuRect(interior.x(), interior.y(), fillRight - interior.x(),
                    interior.height()), SLIDER_FILL);
        }
        drawSliderKnob(new MenuRect(knobLeft, rail.y() - (knobHeight - rail.height()) / 2,
                knobWidth, knobHeight));
    }

    private void drawSliderKnob(MenuRect knob) {
        fill(knob, INK);
        fill(new MenuRect(knob.x() + 2, knob.y() + 2, knob.width() - 4, knob.height() - 4),
                PAPER_TEXT);
        fill(new MenuRect(knob.x() + 5, knob.y() + 5, knob.width() - 10, knob.height() - 10),
                PAPER);
    }

    /** Draws a vertically symmetric cursor instead of relying on the truncated source crop. */
    void drawFocusArrow(int left, int centerY, int color) {
        for (int x = 0; x < FOCUS_ARROW_WIDTH; x++) {
            int height = Math.max(2, FOCUS_ARROW_HEIGHT - 2 * ((x + 1) / 2));
            fill(new MenuRect(left + x, centerY - height / 2, 1, height), color);
        }
    }

    /** A compact double-framed checkbox derived from the approved pixel-art direction. */
    void drawCheckbox(MenuRect bounds, boolean checked) {
        Objects.requireNonNull(bounds, "bounds");
        if (bounds.width() < 32 || bounds.height() < 32) {
            throw new IllegalArgumentException("Checkbox needs a 32px square minimum");
        }
        int size = Math.min(bounds.width(), bounds.height());
        int frameInset = Math.max(3, size / 12);
        int interiorInset = Math.max(7, size * 7 / 36);
        fill(bounds, PAPER_TEXT);
        fill(new MenuRect(bounds.x() + frameInset, bounds.y() + frameInset,
                bounds.width() - frameInset * 2, bounds.height() - frameInset * 2), INK);
        fill(new MenuRect(bounds.x() + interiorInset, bounds.y() + interiorInset,
                bounds.width() - interiorInset * 2, bounds.height() - interiorInset * 2), PAPER);
        if (!checked) {
            return;
        }
        int stepSize = Math.max(3, size / 12);
        int stroke = Math.max(5, size / 7);
        int startX = bounds.x() + size / 4;
        int centerY = bounds.y() + size / 2;
        fill(new MenuRect(startX, centerY, stroke, stroke), INK);
        fill(new MenuRect(startX + stepSize, centerY + stepSize, stroke, stroke), INK);
        for (int step = 0; step < 4; step++) {
            fill(new MenuRect(startX + stepSize * (2 + step), centerY - step * stepSize,
                    stroke, stroke), INK);
        }
    }

    /** Paints one complete widget interior from a pinned Proposal 3 PNG surface fragment. */
    void paintWidget(Proposal3WidgetSkins.Sprite texture, MenuRect bounds) {
        Objects.requireNonNull(texture, "texture");
        Objects.requireNonNull(bounds, "bounds");
        if (bounds.width() > texture.width() || bounds.height() > texture.height()) {
            throw new IllegalArgumentException("Widget surface is smaller than its paint bounds");
        }
        for (int y = 0; y < bounds.height(); y++) {
            for (int x = 0; x < bounds.width(); x++) {
                pixels[(bounds.y() + y) * WIDTH + bounds.x() + x] = texture.pixel(x, y);
            }
        }
    }

    void drawText(Proposal3GlyphAtlas atlas, Proposal3GlyphAtlas.Role role, String value,
            MenuRect target, int color, HorizontalAlignment alignment) {
        Objects.requireNonNull(atlas, "atlas");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(target, "target");
        String fitted = ellipsize(atlas, role, value, target.width());
        int textWidth = atlas.measure(role, fitted);
        int x = switch (alignment) {
            case LEFT -> target.x();
            case CENTER -> target.x() + Math.max(0, (target.width() - textWidth) / 2);
            case RIGHT -> target.right() - textWidth;
        };
        int y = target.y() + Math.max(0, (target.height() - atlas.cellHeight(role)) / 2);
        for (int index = 0; index < fitted.length(); index++) {
            char character = fitted.charAt(index);
            drawGlyph(this, atlas, role, character, x, y, target, color);
            x += atlas.advance(role, character);
            if (x >= target.right()) {
                break;
            }
        }
    }

    /** Copies a ready preview with integer nearest-neighbour aspect fit and centered matte. */
    void copyPreview(MenuPreview preview, MenuRect target, int matteColor) {
        Objects.requireNonNull(preview, "preview");
        if (preview.state() != MenuPreview.State.READY) {
            fill(target, matteColor);
            return;
        }
        int sourceWidth = preview.width();
        int sourceHeight = preview.height();
        int[] source = preview.copyPixels();
        long sourceAspect = (long) sourceWidth * target.height();
        long targetAspect = (long) target.width() * sourceHeight;
        int destinationWidth;
        int destinationHeight;
        if (sourceAspect >= targetAspect) {
            destinationWidth = target.width();
            destinationHeight = Math.max(1, (int) ((long) target.width() * sourceHeight
                    / sourceWidth));
        } else {
            destinationHeight = target.height();
            destinationWidth = Math.max(1, (int) ((long) target.height() * sourceWidth
                    / sourceHeight));
        }
        fill(target, matteColor);
        int left = target.x() + (target.width() - destinationWidth) / 2;
        int top = target.y() + (target.height() - destinationHeight) / 2;
        for (int y = 0; y < destinationHeight; y++) {
            int sourceY = (int) ((long) y * sourceHeight / destinationHeight);
            for (int x = 0; x < destinationWidth; x++) {
                int sourceX = (int) ((long) x * sourceWidth / destinationWidth);
                set(left + x, top + y, source[sourceY * sourceWidth + sourceX]);
            }
        }
    }

    /** Centers an immutable library image, shrinking only when it exceeds the target. */
    void paintFrame(MenuArgbFrame frame, MenuRect target) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(target, "target");
        int sourceWidth = frame.width();
        int sourceHeight = frame.height();
        int destinationWidth = sourceWidth;
        int destinationHeight = sourceHeight;
        if (sourceWidth > target.width() || sourceHeight > target.height()) {
            long sourceAspect = (long) sourceWidth * target.height();
            long targetAspect = (long) target.width() * sourceHeight;
            if (sourceAspect >= targetAspect) {
                destinationWidth = target.width();
                destinationHeight = Math.max(1,
                        (int) ((long) target.width() * sourceHeight / sourceWidth));
            } else {
                destinationHeight = target.height();
                destinationWidth = Math.max(1,
                        (int) ((long) target.height() * sourceWidth / sourceHeight));
            }
        }
        int left = target.x() + (target.width() - destinationWidth) / 2;
        int top = target.y() + (target.height() - destinationHeight) / 2;
        int[] source = frame.copyPixels();
        for (int y = 0; y < destinationHeight; y++) {
            int sourceY = (int) ((long) y * sourceHeight / destinationHeight);
            for (int x = 0; x < destinationWidth; x++) {
                int sourceX = (int) ((long) x * sourceWidth / destinationWidth);
                int color = source[sourceY * sourceWidth + sourceX];
                int alpha = color >>> 24;
                if (alpha != 0) {
                    blend(this, left + x, top + y, color | 0xff000000, alpha);
                }
            }
        }
    }

    void fill(MenuRect target, int color) {
        for (int y = target.y(); y < target.bottom(); y++) {
            int offset = y * WIDTH + target.x();
            for (int x = 0; x < target.width(); x++) {
                pixels[offset + x] = color;
            }
        }
    }

    void set(int x, int y, int color) {
        if (x >= 0 && x < WIDTH && y >= 0 && y < HEIGHT) {
            pixels[y * WIDTH + x] = color;
        }
    }

    private static void drawGlyph(MenuRaster raster, Proposal3GlyphAtlas atlas,
            Proposal3GlyphAtlas.Role role, char character, int left, int top,
            MenuRect clip, int color) {
        int glyphIndex = atlas.index(character);
        for (int y = 0; y < atlas.cellHeight(role); y++) {
            int targetY = top + y;
            if (targetY < clip.y() || targetY >= clip.bottom()) {
                continue;
            }
            for (int x = 0; x < atlas.cellWidth(role); x++) {
                int targetX = left + x;
                if (targetX < clip.x() || targetX >= clip.right()) {
                    continue;
                }
                int alpha = atlas.pixel(role, glyphIndex, x, y) >>> 24;
                if (alpha != 0) {
                    blend(raster, targetX, targetY, color, alpha);
                }
            }
        }
    }

    private static void blend(MenuRaster raster, int x, int y, int color, int alpha) {
        int destination = raster.pixels[y * WIDTH + x];
        if (alpha >= 255) {
            raster.pixels[y * WIDTH + x] = color;
            return;
        }
        int inverse = 255 - alpha;
        int red = (((color >>> 16) & 0xff) * alpha
                + ((destination >>> 16) & 0xff) * inverse + 127) / 255;
        int green = (((color >>> 8) & 0xff) * alpha
                + ((destination >>> 8) & 0xff) * inverse + 127) / 255;
        int blue = ((color & 0xff) * alpha + (destination & 0xff) * inverse + 127) / 255;
        raster.pixels[y * WIDTH + x] = 0xff000000 | red << 16 | green << 8 | blue;
    }

    private static String ellipsize(Proposal3GlyphAtlas atlas, Proposal3GlyphAtlas.Role role,
            String value, int width) {
        String normalized = value.toUpperCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", " ").trim();
        if (atlas.measure(role, normalized) <= width) {
            return normalized;
        }
        String suffix = "...";
        int suffixWidth = atlas.measure(role, suffix);
        if (suffixWidth >= width) {
            return suffix;
        }
        int end = normalized.length();
        while (end > 0 && atlas.measure(role, normalized.substring(0, end)) + suffixWidth > width) {
            end--;
        }
        return normalized.substring(0, end).stripTrailing() + suffix;
    }

}
