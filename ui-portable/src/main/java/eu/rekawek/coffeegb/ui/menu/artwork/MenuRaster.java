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

    /** Slider endpoints share one coordinate system with the 0, 10, ..., 100% tick marks. */
    static final int AUDIO_KNOB_MIN_X = 427;
    static final int AUDIO_KNOB_MAX_X = 827;
    static final int AUDIO_KNOB_WIDTH = 30;
    static final int AUDIO_KNOB_HEIGHT = 36;
    static final int AUDIO_SLIDER_EMPTY = PAPER;
    static final int AUDIO_SLIDER_FILL = 0xff667657;
    private static final int AUDIO_SLIDER_INSET = 3;
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

    /**
     * Draws the complete audio control from palette primitives.
     *
     * <p>The former source samples contained the 75% knob's baked lighting and drop shadow.
     * Slicing those samples caused a light or dark seam at every other value, so the live
     * control deliberately uses flat fills and one shared percentage coordinate system.</p>
     */
    void drawAudioSlider(MenuRect rail, int progress) {
        Objects.requireNonNull(rail, "rail");
        if (rail.width() != AUDIO_KNOB_MAX_X - AUDIO_KNOB_MIN_X + AUDIO_KNOB_WIDTH
                || rail.height() < AUDIO_SLIDER_INSET * 2 + 1) {
            throw new IllegalArgumentException("Unexpected audio slider geometry");
        }
        int bounded = Math.max(0, Math.min(100, progress));
        fill(rail, INK);
        MenuRect interior = new MenuRect(rail.x() + AUDIO_SLIDER_INSET,
                rail.y() + AUDIO_SLIDER_INSET,
                rail.width() - AUDIO_SLIDER_INSET * 2,
                rail.height() - AUDIO_SLIDER_INSET * 2);
        fill(interior, AUDIO_SLIDER_EMPTY);

        int knobCenter = audioKnobCenter(bounded);
        int fillRight = Math.max(interior.x(), Math.min(interior.right(), knobCenter));
        if (fillRight > interior.x()) {
            fill(new MenuRect(interior.x(), interior.y(), fillRight - interior.x(),
                    interior.height()), AUDIO_SLIDER_FILL);
        }

        int knobLeft = audioKnobLeft(bounded);
        int knobTop = rail.y() - (AUDIO_KNOB_HEIGHT - rail.height()) / 2;
        drawAudioKnob(new MenuRect(knobLeft, knobTop, AUDIO_KNOB_WIDTH, AUDIO_KNOB_HEIGHT));
        drawAudioTicks(rail.y() + rail.height() + 17);
    }

    static int audioKnobLeft(int progress) {
        int bounded = Math.max(0, Math.min(100, progress));
        return AUDIO_KNOB_MIN_X + (int) ((long) (AUDIO_KNOB_MAX_X - AUDIO_KNOB_MIN_X)
                * bounded / 100L);
    }

    static int audioKnobCenter(int progress) {
        return audioKnobLeft(progress) + AUDIO_KNOB_WIDTH / 2;
    }

    /** A small framed, pixel-native thumb with no sampled highlights or shadows. */
    private void drawAudioKnob(MenuRect knob) {
        fill(knob, INK);
        fill(new MenuRect(knob.x() + 2, knob.y() + 2, knob.width() - 4, knob.height() - 4),
                PAPER_TEXT);
        fill(new MenuRect(knob.x() + 5, knob.y() + 5, knob.width() - 10, knob.height() - 10),
                PAPER);
        fill(new MenuRect(knob.x() + knob.width() / 2 - 2, knob.y() + 8, 4,
                knob.height() - 16), INK);
    }

    private void drawAudioTicks(int top) {
        for (int percent = 0; percent <= 100; percent += 10) {
            int center = audioKnobCenter(percent);
            fill(new MenuRect(center - 2, top, 5, 5), INK);
        }
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
        fill(bounds, PAPER_TEXT);
        fill(new MenuRect(bounds.x() + 3, bounds.y() + 3, bounds.width() - 6,
                bounds.height() - 6), INK);
        fill(new MenuRect(bounds.x() + 7, bounds.y() + 7, bounds.width() - 14,
                bounds.height() - 14), PAPER);
        if (!checked) {
            return;
        }
        fill(new MenuRect(bounds.x() + 9, bounds.y() + 18, 5, 5), INK);
        fill(new MenuRect(bounds.x() + 12, bounds.y() + 21, 5, 5), INK);
        for (int step = 0; step < 4; step++) {
            fill(new MenuRect(bounds.x() + 15 + step * 3, bounds.y() + 18 - step * 3,
                    5, 5), INK);
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

    /** Alpha-composites one pinned Proposal 3 sprite without exposing a platform graphics type. */
    void paintSprite(Proposal3WidgetSkins.Sprite sprite, int left, int top, int color) {
        Objects.requireNonNull(sprite, "sprite");
        for (int y = 0; y < sprite.height(); y++) {
            for (int x = 0; x < sprite.width(); x++) {
                int source = sprite.pixel(x, y);
                int alpha = source >>> 24;
                if (alpha != 0) {
                    blend(this, left + x, top + y, color, alpha);
                }
            }
        }
    }

    /** Alpha-composites a packaged sprite while preserving its own approved pixel colors. */
    void paintSprite(Proposal3WidgetSkins.Sprite sprite, int left, int top) {
        Objects.requireNonNull(sprite, "sprite");
        for (int y = 0; y < sprite.height(); y++) {
            for (int x = 0; x < sprite.width(); x++) {
                int source = sprite.pixel(x, y);
                int alpha = source >>> 24;
                if (alpha == 0) {
                    continue;
                }
                int targetX = left + x;
                int targetY = top + y;
                if (targetX < 0 || targetX >= WIDTH || targetY < 0 || targetY >= HEIGHT) {
                    continue;
                }
                blend(this, targetX, targetY, source | 0xff000000, alpha);
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
