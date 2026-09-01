package eu.rekawek.coffeegb.ui.menu.artwork;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Shared, route-neutral texture skins for the common menu's interchangeable widget rows. */
final class Proposal3WidgetSkins {

    enum Surface {
        DARK,
        PAPER,
        SELECTED
    }

    private static final String ROOT =
            "/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/widgets/";

    private final Sprite dark;
    private final Sprite paper;
    private final Sprite selected;

    private Proposal3WidgetSkins(Sprite dark, Sprite paper, Sprite selected) {
        this.dark = dark;
        this.paper = paper;
        this.selected = selected;
    }

    static Proposal3WidgetSkins load() throws IOException {
        Sprite dark = load("dark-widget.png");
        Sprite paper = load("paper-widget.png");
        Sprite selected = load("selected-widget.png");
        requireDimensions("dark-widget.png", dark, 900, 160);
        requireDimensions("paper-widget.png", paper, 900, 160);
        requireDimensions("selected-widget.png", selected, 900, 160);
        return new Proposal3WidgetSkins(dark, paper, selected);
    }

    Sprite surface(Surface surface) {
        return switch (Objects.requireNonNull(surface, "surface")) {
            case DARK -> dark;
            case PAPER -> paper;
            case SELECTED -> selected;
        };
    }

    private static Sprite load(String name) throws IOException {
        try (InputStream stream = Proposal3WidgetSkins.class.getResourceAsStream(ROOT + name)) {
            if (stream == null) {
                throw new IOException("Missing common menu widget skin: " + name);
            }
            MenuArgbFrame frame = PngArgbDecoder.decode(stream);
            return new Sprite(frame.width(), frame.height(), frame.copyPixels());
        }
    }

    private static void requireDimensions(String name, Sprite sprite, int width, int height)
            throws IOException {
        if (sprite.width != width || sprite.height != height) {
            throw new IOException("Unexpected common menu widget dimensions for " + name);
        }
    }

    static final class Sprite {
        private final int width;
        private final int height;
        private final int[] pixels;

        private Sprite(int width, int height, int[] pixels) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }

        int width() {
            return width;
        }

        int height() {
            return height;
        }

        int pixel(int x, int y) {
            return pixels[y * width + x];
        }
    }
}
