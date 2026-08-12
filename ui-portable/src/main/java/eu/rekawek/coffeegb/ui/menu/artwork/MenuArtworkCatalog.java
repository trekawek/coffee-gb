package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuRoute;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The canonical Proposal 3 artwork catalog.
 *
 * <p>The source PNGs are complete 1672x941 compositions, but the test fixtures packaged by this
 * revision are already the fixed 924x736 crop declared by {@link #SOURCE_VISIBLE_CROP}. Runtime
 * route/atlas paths are reserved for the later sanitized compositor integration.
 */
public final class MenuArtworkCatalog {

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

    private static final String FIXTURE_RESOURCE_ROOT =
            "/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/source/";

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
        add(catalog, MenuRoute.SETTINGS, "02-settings.png");
        add(catalog, MenuRoute.AUDIO, "03-audio.png");
        add(catalog, MenuRoute.TOUCH_CONTROLS, "04-touch-controls.png");
        add(catalog, MenuRoute.CONTROLLER_MAPPING, "05-controller-mapping.png");
        add(catalog, MenuRoute.OPTIONAL_DEVICES, "06-optional-devices.png");
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
        Set<String> paths = new HashSet<>();
        for (MenuRoute route : MenuRoute.values()) {
            MenuArtwork artwork = catalog.get(route);
            if (artwork == null || !paths.add(artwork.resourcePath())) {
                throw new IllegalStateException("Proposal 3 artwork catalog contains a duplicate or missing route");
            }
        }
        return Collections.unmodifiableMap(catalog);
    }

    private static void add(EnumMap<MenuRoute, MenuArtwork> catalog, MenuRoute route, String filename) {
        if (catalog.containsKey(route)) {
            throw new IllegalStateException("Duplicate artwork route: " + route);
        }
        MenuArtwork artwork = new MenuArtwork(route, filename, FIXTURE_RESOURCE_ROOT + filename,
                SOURCE_VISIBLE_CROP);
        for (MenuArtwork existing : catalog.values()) {
            if (existing.resourcePath().equals(artwork.resourcePath())) {
                throw new IllegalStateException("Duplicate artwork resource: " + artwork.resourcePath());
            }
        }
        catalog.put(route, artwork);
    }
}
