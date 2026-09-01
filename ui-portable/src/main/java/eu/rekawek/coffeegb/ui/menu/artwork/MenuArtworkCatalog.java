package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuRoute;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * The canonical portable menu artwork catalog.
 *
 * <p>The legacy route PNG names describe the original 1672x941 Proposal 3 compositions. All
 * runtime routes now share one text-free 924x736 frame; only runtime title, picture, subtitle, and
 * option widgets vary. Template resource paths and decoding remain package-private.
 */
public final class MenuArtworkCatalog {

    /** Filename of the common text-free frame used by every route. */
    public static final String COMMON_TEMPLATE_FILENAME = "common-menu-frame.png";

    /** Width of every original Proposal 3 composition in pixels. */
    public static final int SOURCE_WIDTH = 1672;

    /** Height of every original Proposal 3 composition in pixels. */
    public static final int SOURCE_HEIGHT = 941;

    /** Width of each lossless packaged canonical crop. */
    public static final int PACKAGED_WIDTH = 924;

    /** Height of each lossless packaged canonical crop. */
    public static final int PACKAGED_HEIGHT = 736;

    /** Original-composition crop retained as provenance for every packaged image. */
    public static final MenuRect SOURCE_VISIBLE_CROP = new MenuRect(374, 102, 924, 736);

    /** The complete bounds of each packaged image. */
    public static final MenuRect PACKAGED_BOUNDS = new MenuRect(0, 0, PACKAGED_WIDTH, PACKAGED_HEIGHT);

    private static final Map<MenuRoute, MenuArtwork> ARTWORK = createCatalog();

    private MenuArtworkCatalog() {
    }

    /** Returns the one canonical artwork entry for a route. */
    public static MenuArtwork artwork(MenuRoute route) {
        return ARTWORK.get(Objects.requireNonNull(route, "route"));
    }

    /** Returns an immutable route-to-artwork view containing every menu route exactly once. */
    public static Map<MenuRoute, MenuArtwork> all() {
        return ARTWORK;
    }

    private static Map<MenuRoute, MenuArtwork> createCatalog() {
        EnumMap<MenuRoute, MenuArtwork> catalog = new EnumMap<>(MenuRoute.class);
        add(catalog, MenuRoute.PAUSE_CONSOLE, "00-pause-console.png");
        add(catalog, MenuRoute.SAVE_STATES, "01-save-states.png");
        add(catalog, MenuRoute.RECENT_GAMES, "16-recent-games.png");
        add(catalog, MenuRoute.SETTINGS, "02-settings.png");
        add(catalog, MenuRoute.AUDIO, "03-audio.png");
        add(catalog, MenuRoute.DISPLAY, "14-display.png");
        add(catalog, MenuRoute.TOUCH_CONTROLS, "04-touch-controls.png");
        add(catalog, MenuRoute.CONTROLLER_MAPPING, "05-controller-mapping.png");
        add(catalog, MenuRoute.OPTIONAL_DEVICES, "06-optional-devices.png");
        add(catalog, MenuRoute.OPTION_PICKER, "15-option-picker.png");
        add(catalog, MenuRoute.DATA_MEDIA, "07-data-media.png");
        add(catalog, MenuRoute.LIBRARY, "08-library.png");
        add(catalog, MenuRoute.CHOOSE_ROM, "09-choose-rom.png");
        add(catalog, MenuRoute.SYSTEM, "10-system.png");
        add(catalog, MenuRoute.ABOUT, "11-about.png");
        add(catalog, MenuRoute.CONFIRM_ACTION, "12-confirm-action.png");
        add(catalog, MenuRoute.PRINTER_PAPER, "13-printer-paper.png");

        if (catalog.size() != MenuRoute.values().length) {
            throw new IllegalStateException("Proposal 3 artwork catalog does not cover every route");
        }
        java.util.Set<String> sourceFilenames = new java.util.HashSet<>();
        for (MenuRoute route : MenuRoute.values()) {
            MenuArtwork artwork = catalog.get(route);
            if (artwork == null || !sourceFilenames.add(artwork.sourceFilename())) {
                throw new IllegalStateException("Proposal 3 artwork catalog contains a duplicate or missing route");
            }
            if (!COMMON_TEMPLATE_FILENAME.equals(artwork.templateFilename())) {
                throw new IllegalStateException("Menu route does not use the common template: " + route);
            }
        }
        return Collections.unmodifiableMap(catalog);
    }

    private static void add(EnumMap<MenuRoute, MenuArtwork> catalog, MenuRoute route, String filename) {
        if (catalog.containsKey(route)) {
            throw new IllegalStateException("Duplicate artwork route: " + route);
        }
        MenuArtwork artwork = new MenuArtwork(route, filename, SOURCE_VISIBLE_CROP);
        catalog.put(route, artwork);
    }
}
