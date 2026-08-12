package eu.rekawek.coffeegb.ui.menu.artwork;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Package-private bitmap glyph atlas used by the dependency-free compositor. */
final class Proposal3GlyphAtlas {

    static final String MEDIUM_RESOURCE_PATH =
            "/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/overlay/pixelify-sans-medium-atlas.png";
    static final String SEMIBOLD_RESOURCE_PATH =
            "/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/overlay/pixelify-sans-semibold-atlas.png";
    static final String DISPLAY_RESOURCE_PATH =
            "/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/overlay/pixelify-sans-display-atlas.png";
    static final String SMALL_RESOURCE_PATH =
            "/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/overlay/pixelify-sans-small-atlas.png";
    static final String NOTICE_RESOURCE_PATH =
            "/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/overlay/pixelify-sans-notice-atlas.png";
    static final String CHARACTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 .:/&-_%?+()[]!,'";
    enum Role {
        // The atlas cells retain generous transparent gutters, but Proposal 3's compact row
        // typography advances on a 17 px rhythm. Wide M/W glyphs end immediately before the
        // next cell's left bearing, so this remains overlap-free while matching the mockup.
        SMALL(22, 36, 11, 5),
        NOTICE(28, 36, 15, 7),
        MEDIUM(36, 36, 19, 8),
        DISPLAY(36, 48, 20, 8),
        SEMIBOLD(48, 48, 27, 9);

        private final int cellWidth;
        private final int cellHeight;
        private final int advance;
        private final int spaceAdvance;

        Role(int cellWidth, int cellHeight, int advance, int spaceAdvance) {
            this.cellWidth = cellWidth;
            this.cellHeight = cellHeight;
            this.advance = advance;
            this.spaceAdvance = spaceAdvance;
        }

        int width() {
            return 16 * cellWidth;
        }

        int height() {
            return ((CHARACTERS.length() + 15) / 16) * cellHeight;
        }

        int cellHeight() {
            return cellHeight;
        }
    }

    private final int[][] pixels;

    private Proposal3GlyphAtlas(int[][] pixels) {
        this.pixels = pixels;
    }

    static Proposal3GlyphAtlas load() throws IOException {
        int[][] rolePixels = new int[Role.values().length][];
        for (Role role : Role.values()) {
            String path = switch (role) {
                case SMALL -> SMALL_RESOURCE_PATH;
                case NOTICE -> NOTICE_RESOURCE_PATH;
                case MEDIUM -> MEDIUM_RESOURCE_PATH;
                case DISPLAY -> DISPLAY_RESOURCE_PATH;
                case SEMIBOLD -> SEMIBOLD_RESOURCE_PATH;
            };
            InputStream stream = Proposal3GlyphAtlas.class.getResourceAsStream(path);
            if (stream == null) {
                throw new IOException("Missing Proposal 3 " + role + " glyph atlas: " + path);
            }
            try (InputStream input = stream) {
                MenuArgbFrame frame = PngArgbDecoder.decode(input);
                if (frame.width() != role.width() || frame.height() != role.height()) {
                    throw new IOException("Unexpected Proposal 3 " + role + " glyph atlas dimensions: "
                            + frame.width() + "x" + frame.height());
                }
                rolePixels[role.ordinal()] = frame.copyPixels();
            }
        }
        return new Proposal3GlyphAtlas(rolePixels);
    }

    int width() {
        return width(Role.MEDIUM);
    }

    int height() {
        return height(Role.MEDIUM);
    }

    int advance(char value) {
        return advance(Role.MEDIUM, value);
    }

    int width(Role role) {
        return role.width();
    }

    int height(Role role) {
        return role.height();
    }

    int cellWidth(Role role) {
        return role.cellWidth;
    }

    int cellHeight(Role role) {
        return role.cellHeight;
    }

    int advance(Role role, char value) {
        return value == ' ' ? role.spaceAdvance : role.advance;
    }

    int measure(String value) {
        return measure(Role.MEDIUM, value);
    }

    int measure(Role role, String value) {
        Objects.requireNonNull(value, "value");
        int width = 0;
        for (int index = 0; index < value.length(); index++) {
            width += advance(role, normalize(value.charAt(index)));
        }
        return width;
    }

    /** Returns the atlas cell index, using '?' for characters outside the portable alphabet. */
    int index(char value) {
        int index = CHARACTERS.indexOf(normalize(value));
        int fallback = CHARACTERS.indexOf('?');
        return index < 0 ? fallback : index;
    }

    int pixel(int index, int x, int y) {
        return pixel(Role.MEDIUM, index, x, y);
    }

    int pixel(Role role, int index, int x, int y) {
        int cellX = (index % 16) * role.cellWidth;
        int cellY = (index / 16) * role.cellHeight;
        return pixels[role.ordinal()][(cellY + y) * role.width() + cellX + x];
    }

    private static char normalize(char value) {
        if (value >= 'a' && value <= 'z') {
            return (char) (value - ('a' - 'A'));
        }
        return value;
    }
}
