package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuRoute;

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
 * Runtime controls that change geometry (the focus cursor, slider and checkbox) are drawn from
 * the same fixed palette primitives as their rows, so no sampled shadow or gradient can leak
 * into a different state.
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
    private final Sprite dataArrowLeft;
    private final Sprite dataArrowRight;
    private final Sprite dataCamera;
    private final Sprite dataPrinter;
    private final Sprite aboutNetwork;
    private final Sprite aboutStorage;
    private final Sprite aboutCamera;
    private final Sprite aboutSource;
    private final Sprite actionSave;
    private final Sprite actionLoad;
    private final Sprite actionDelete;
    private final Sprite actionOptionalSave;
    private final Sprite actionOptionalCancel;
    private final Sprite actionLibrary;
    private final Sprite actionGithub;

    private Proposal3WidgetSkins(Sprite dark, Sprite paper, Sprite selected,
            Sprite dataArrowLeft, Sprite dataArrowRight, Sprite dataCamera, Sprite dataPrinter,
            Sprite aboutNetwork, Sprite aboutStorage, Sprite aboutCamera, Sprite aboutSource,
            Sprite actionSave, Sprite actionLoad, Sprite actionDelete, Sprite actionOptionalSave,
            Sprite actionOptionalCancel, Sprite actionLibrary, Sprite actionGithub) {
        this.dark = dark;
        this.paper = paper;
        this.selected = selected;
        this.dataArrowLeft = dataArrowLeft;
        this.dataArrowRight = dataArrowRight;
        this.dataCamera = dataCamera;
        this.dataPrinter = dataPrinter;
        this.aboutNetwork = aboutNetwork;
        this.aboutStorage = aboutStorage;
        this.aboutCamera = aboutCamera;
        this.aboutSource = aboutSource;
        this.actionSave = actionSave;
        this.actionLoad = actionLoad;
        this.actionDelete = actionDelete;
        this.actionOptionalSave = actionOptionalSave;
        this.actionOptionalCancel = actionOptionalCancel;
        this.actionLibrary = actionLibrary;
        this.actionGithub = actionGithub;
    }

    static Proposal3WidgetSkins load() throws IOException {
        Sprite dark = load("dark-widget.png");
        Sprite paper = load("paper-widget.png");
        Sprite selected = load("selected-widget.png");
        Sprite dataArrowLeft = load("data-arrow-left.png");
        Sprite dataArrowRight = load("data-arrow-right.png");
        Sprite dataCamera = load("data-camera.png");
        Sprite dataPrinter = load("data-printer.png");
        Sprite aboutNetwork = load("about-network.png");
        Sprite aboutStorage = load("about-storage.png");
        Sprite aboutCamera = load("about-camera.png");
        Sprite aboutSource = load("about-source.png");
        Sprite actionSave = load("action-save.png");
        Sprite actionLoad = load("action-load.png");
        Sprite actionDelete = load("action-delete.png");
        Sprite actionOptionalSave = load("action-optional-save.png");
        Sprite actionOptionalCancel = load("action-optional-cancel.png");
        Sprite actionLibrary = load("action-library.png");
        Sprite actionGithub = load("action-github.png");
        requireDimensions("dark-widget.png", dark, 900, 160);
        requireDimensions("paper-widget.png", paper, 900, 160);
        requireDimensions("selected-widget.png", selected, 900, 160);
        requireDimensions("data-arrow-left.png", dataArrowLeft, 45, 45);
        requireDimensions("data-arrow-right.png", dataArrowRight, 45, 45);
        requireDimensions("data-camera.png", dataCamera, 50, 50);
        requireDimensions("data-printer.png", dataPrinter, 50, 50);
        requireDimensions("about-network.png", aboutNetwork, 70, 58);
        requireDimensions("about-storage.png", aboutStorage, 70, 58);
        requireDimensions("about-camera.png", aboutCamera, 70, 58);
        requireDimensions("about-source.png", aboutSource, 70, 58);
        requireDimensions("action-save.png", actionSave, 39, 39);
        requireDimensions("action-load.png", actionLoad, 39, 39);
        requireDimensions("action-delete.png", actionDelete, 42, 46);
        requireDimensions("action-optional-save.png", actionOptionalSave, 39, 38);
        requireDimensions("action-optional-cancel.png", actionOptionalCancel, 44, 42);
        requireDimensions("action-library.png", actionLibrary, 45, 39);
        requireDimensions("action-github.png", actionGithub, 53, 53);
        return new Proposal3WidgetSkins(dark, paper, selected, dataArrowLeft, dataArrowRight,
                dataCamera, dataPrinter, aboutNetwork, aboutStorage, aboutCamera, aboutSource,
                actionSave, actionLoad, actionDelete, actionOptionalSave, actionOptionalCancel,
                actionLibrary, actionGithub);
    }

    Sprite surface(Surface surface) {
        return switch (Objects.requireNonNull(surface, "surface")) {
            case DARK -> dark;
            case PAPER -> paper;
            case SELECTED -> selected;
        };
    }

    Sprite dataRowIcon(int index) {
        return switch (index) {
            case 0, 2 -> dataArrowLeft;
            case 1, 3 -> dataArrowRight;
            case 4 -> dataCamera;
            case 5 -> dataPrinter;
            default -> null;
        };
    }

    Sprite aboutRowIcon(int index) {
        return switch (index) {
            case 1 -> aboutNetwork;
            case 2 -> aboutStorage;
            case 3 -> aboutCamera;
            case 4 -> aboutSource;
            default -> null;
        };
    }

    Sprite actionIcon(MenuRoute route, int index) {
        return switch (route) {
            case SAVE_STATES -> switch (index) {
                case 0 -> actionSave;
                case 1 -> actionLoad;
                case 2 -> actionDelete;
                default -> null;
            };
            case OPTIONAL_DEVICES -> index == 0 ? actionOptionalSave
                    : index == 1 ? actionOptionalCancel : null;
            case LIBRARY -> index == 0 ? actionLibrary : null;
            case ABOUT -> index == 0 ? actionGithub : null;
            default -> null;
        };
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
