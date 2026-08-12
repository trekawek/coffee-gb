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

    /** The canonical 75% knob begins at x=727; the endpoint centers travel 400 pixels. */
    private static final int AUDIO_KNOB_MIN_X = 427;
    private static final int AUDIO_KNOB_MAX_X = 827;

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

    /** Paints complete audited rail surfaces, then the immutable exact knob sprite. */
    void drawAudioSlider(Proposal3WidgetSkins.Sprite emptyTrack,
            Proposal3WidgetSkins.Sprite filledTrack,
            Proposal3WidgetSkins.Sprite exactKnob,
            MenuRect widget, MenuRect canonicalKnob, int progress) {
        Objects.requireNonNull(emptyTrack, "emptyTrack");
        Objects.requireNonNull(filledTrack, "filledTrack");
        Objects.requireNonNull(exactKnob, "exactKnob");
        Objects.requireNonNull(widget, "widget");
        Objects.requireNonNull(canonicalKnob, "canonicalKnob");
        int bounded = Math.max(0, Math.min(100, progress));
        if (emptyTrack.width() != widget.width() || emptyTrack.height() > widget.height()
                || filledTrack.width() != widget.width()
                || filledTrack.height() != emptyTrack.height()
                || exactKnob.width() != canonicalKnob.width()
                || exactKnob.height() != canonicalKnob.height()) {
            throw new IllegalArgumentException("The packaged audio slider geometry has changed");
        }

        MenuRect rail = new MenuRect(widget.x(), widget.y(), widget.width(), emptyTrack.height());
        paintWidget(emptyTrack, rail);
        int destinationX = AUDIO_KNOB_MIN_X
                + (int) ((long) (AUDIO_KNOB_MAX_X - AUDIO_KNOB_MIN_X) * bounded / 100L);
        int localKnobX = destinationX - widget.x();
        paintWidgetSlice(filledTrack, rail, localKnobX + exactKnob.width() / 2 + 1);
        paintWidget(exactKnob, new MenuRect(destinationX, widget.y(), exactKnob.width(),
                exactKnob.height()));
    }

    /** Blits a left-hand slice of a complete packaged surface. */
    private void paintWidgetSlice(Proposal3WidgetSkins.Sprite texture, MenuRect bounds, int width) {
        int clippedWidth = Math.max(0, Math.min(bounds.width(), width));
        for (int y = 0; y < bounds.height(); y++) {
            for (int x = 0; x < clippedWidth; x++) {
                pixels[(bounds.y() + y) * WIDTH + bounds.x() + x] = texture.pixel(x, y);
            }
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
