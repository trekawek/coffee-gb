package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuRoute;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Lazy library of route-neutral pictograms for the common menu template.
 *
 * <p>Pages select a logical illustration through their route while the renderer owns every
 * resource path.  A host can always replace the pictogram with a detached {@code MenuPreview}; no
 * Android, Swing, filesystem, or image-decoder type crosses the portable boundary.</p>
 */
final class MenuIllustrationCatalog {

    private static final String ROOT =
            "/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/illustrations/";

    private static final Map<MenuRoute, MenuArgbFrame> CACHE = new EnumMap<>(MenuRoute.class);

    private MenuIllustrationCatalog() {
    }

    static Optional<MenuArgbFrame> decode(MenuRoute route) throws IOException {
        Objects.requireNonNull(route, "route");
        String name = resourceName(route);
        if (name == null) {
            return Optional.empty();
        }
        synchronized (CACHE) {
            MenuArgbFrame cached = CACHE.get(route);
            if (cached != null) {
                return Optional.of(cached);
            }
            try (InputStream stream = MenuIllustrationCatalog.class.getResourceAsStream(ROOT + name)) {
                if (stream == null) {
                    throw new IOException("Missing common menu illustration: " + name);
                }
                MenuArgbFrame decoded = PngArgbDecoder.decode(stream);
                CACHE.put(route, decoded);
                return Optional.of(decoded);
            }
        }
    }

    static String resourceName(MenuRoute route) {
        return switch (Objects.requireNonNull(route, "route")) {
            case PAUSE_CONSOLE, SAVE_STATES, RECENT_GAMES, FILE_BROWSER -> null;
            case SETTINGS, OPTION_PICKER -> "settings.png";
            case AUDIO -> "audio.png";
            case DISPLAY, SYSTEM -> "system.png";
            case TOUCH_CONTROLS -> "touch-controls.png";
            case CONTROLLER_MAPPING -> "controller.png";
            case OPTIONAL_DEVICES -> "peripherals.png";
            case DATA_MEDIA -> "data-media.png";
            case LIBRARY -> "library.png";
            case CHOOSE_ROM -> "archive.png";
            case ABOUT -> "about.png";
            case CONFIRM_ACTION -> "warning.png";
            case PRINTER_PAPER -> "printer.png";
        };
    }
}
