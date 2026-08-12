package eu.rekawek.coffeegb.ui.menu.artwork;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Exact raster fragments used as opaque/alpha widget overlays above Proposal 3 layer zero.
 *
 * <p>The large surface fragments were mechanically sampled from text-free areas of the approved
 * mockups. They are deliberately packaged PNGs rather than synthesized colors: repainting a
 * changing row or action therefore replaces the complete widget interior, including its paper
 * grain, without attempting to reconstruct the immutable base image pixel-by-pixel.
 * The audio slider follows the same rule with complete empty/filled track surfaces and a separate
 * exact knob sprite; its runtime compositor never receives the raw route raster.
 */
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
    private final Sprite focusArrow;
    private final Sprite audioSliderEmpty;
    private final Sprite audioSliderFilled;
    private final Sprite audioKnob;

    private Proposal3WidgetSkins(Sprite dark, Sprite paper, Sprite selected, Sprite focusArrow,
            Sprite audioSliderEmpty, Sprite audioSliderFilled, Sprite audioKnob) {
        this.dark = dark;
        this.paper = paper;
        this.selected = selected;
        this.focusArrow = focusArrow;
        this.audioSliderEmpty = audioSliderEmpty;
        this.audioSliderFilled = audioSliderFilled;
        this.audioKnob = audioKnob;
    }

    static Proposal3WidgetSkins load() throws IOException {
        Sprite dark = load("dark-widget.png");
        Sprite paper = load("paper-widget.png");
        Sprite selected = load("selected-widget.png");
        Sprite arrow = load("focus-arrow.png");
        Sprite audioSliderEmpty = load("audio-slider-empty.png");
        Sprite audioSliderFilled = load("audio-slider-filled.png");
        Sprite audioKnob = load("audio-knob.png");
        requireDimensions("dark-widget.png", dark, 900, 160);
        requireDimensions("paper-widget.png", paper, 900, 160);
        requireDimensions("selected-widget.png", selected, 900, 160);
        requireDimensions("focus-arrow.png", arrow, 13, 20);
        requireDimensions("audio-slider-empty.png", audioSliderEmpty, 438, 59);
        requireDimensions("audio-slider-filled.png", audioSliderFilled, 438, 59);
        requireDimensions("audio-knob.png", audioKnob, 31, 59);
        return new Proposal3WidgetSkins(dark, paper, selected, arrow, audioSliderEmpty,
                audioSliderFilled, audioKnob);
    }

    Sprite surface(Surface surface) {
        return switch (Objects.requireNonNull(surface, "surface")) {
            case DARK -> dark;
            case PAPER -> paper;
            case SELECTED -> selected;
        };
    }

    Sprite focusArrow() {
        return focusArrow;
    }

    Sprite audioSliderEmpty() {
        return audioSliderEmpty;
    }

    Sprite audioSliderFilled() {
        return audioSliderFilled;
    }

    Sprite audioKnob() {
        return audioKnob;
    }

    private static Sprite load(String name) throws IOException {
        try (InputStream stream = Proposal3WidgetSkins.class.getResourceAsStream(ROOT + name)) {
            if (stream == null) {
                throw new IOException("Missing Proposal 3 widget asset: " + name);
            }
            MenuArgbFrame frame = PngArgbDecoder.decode(stream);
            return new Sprite(frame.width(), frame.height(), frame.copyPixels());
        }
    }

    private static void requireDimensions(String name, Sprite sprite, int width, int height)
            throws IOException {
        if (sprite.width != width || sprite.height != height) {
            throw new IOException("Unexpected Proposal 3 widget dimensions for " + name);
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
