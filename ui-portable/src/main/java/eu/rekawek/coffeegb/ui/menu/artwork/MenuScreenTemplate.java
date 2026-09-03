package eu.rekawek.coffeegb.ui.menu.artwork;

import java.util.List;

/**
 * Canonical geometry shared by every composed menu screen.
 *
 * <p>The bounds use the portable {@value MenuArtworkCatalog#PACKAGED_WIDTH}x{@value
 * MenuArtworkCatalog#PACKAGED_HEIGHT} coordinate space. They describe the stable chrome only;
 * titles, pictures, subtitles, and option widgets are supplied by the screen model at runtime.
 * The option rail deliberately keeps seven equal 72-pixel rows. When a page scrolls, an arrow
 * occupies one of these rows instead of introducing a differently sized control.
 */
public final class MenuScreenTemplate {

    public static final int OPTION_ROW_COUNT = 7;
    public static final int OPTION_ROW_HEIGHT = 72;
    public static final int OPTION_DIVIDER_HEIGHT = 2;

    /** Complete title-bar chrome, including its border. */
    public static final MenuRect TITLE_BAR = new MenuRect(8, 8, 908, 95);

    /** Text-safe title area inside {@link #TITLE_BAR}. */
    public static final MenuRect TITLE = new MenuRect(45, 25, 834, 61);

    /** Complete left-panel chrome, including its border. */
    public static final MenuRect LEFT_PANEL = new MenuRect(8, 110, 400, 534);

    /** Shared screenshot, thumbnail, or illustration aperture. */
    public static final MenuRect PICTURE = new MenuRect(30, 140, 352, 340);

    /** Optional copy below {@link #PICTURE}; an empty subtitle leaves this area untouched. */
    public static final MenuRect SUBTITLE = new MenuRect(30, 490, 352, 139);

    /** Complete right-panel chrome, including its border. */
    public static final MenuRect RIGHT_PANEL = new MenuRect(418, 110, 498, 534);

    /** Seven-row widget rail inside {@link #RIGHT_PANEL}. */
    public static final MenuRect OPTION_LIST = new MenuRect(424, 121, 484, 516);

    /** Equal-height widget slots, ordered from top to bottom. */
    public static final List<MenuRect> OPTION_ROWS = List.of(
            new MenuRect(424, 121, 484, OPTION_ROW_HEIGHT),
            new MenuRect(424, 195, 484, OPTION_ROW_HEIGHT),
            new MenuRect(424, 269, 484, OPTION_ROW_HEIGHT),
            new MenuRect(424, 343, 484, OPTION_ROW_HEIGHT),
            new MenuRect(424, 417, 484, OPTION_ROW_HEIGHT),
            new MenuRect(424, 491, 484, OPTION_ROW_HEIGHT),
            new MenuRect(424, 565, 484, OPTION_ROW_HEIGHT));

    /** Separators between {@link #OPTION_ROWS}; separators never overlap a widget hit target. */
    public static final List<MenuRect> OPTION_DIVIDERS = List.of(
            new MenuRect(424, 193, 484, OPTION_DIVIDER_HEIGHT),
            new MenuRect(424, 267, 484, OPTION_DIVIDER_HEIGHT),
            new MenuRect(424, 341, 484, OPTION_DIVIDER_HEIGHT),
            new MenuRect(424, 415, 484, OPTION_DIVIDER_HEIGHT),
            new MenuRect(424, 489, 484, OPTION_DIVIDER_HEIGHT),
            new MenuRect(424, 563, 484, OPTION_DIVIDER_HEIGHT));

    /** Complete single content panel used by full-width list pages. */
    public static final MenuRect FULL_WIDTH_PANEL = new MenuRect(8, 110, 908, 534);

    /** Seven-row rail spanning the full-width panel while preserving its generated border. */
    public static final MenuRect FULL_WIDTH_OPTION_LIST = new MenuRect(16, 121, 892, 516);

    /** Full-width list slots, aligned vertically with the standard option rail. */
    public static final List<MenuRect> FULL_WIDTH_OPTION_ROWS = List.of(
            new MenuRect(16, 121, 892, OPTION_ROW_HEIGHT),
            new MenuRect(16, 195, 892, OPTION_ROW_HEIGHT),
            new MenuRect(16, 269, 892, OPTION_ROW_HEIGHT),
            new MenuRect(16, 343, 892, OPTION_ROW_HEIGHT),
            new MenuRect(16, 417, 892, OPTION_ROW_HEIGHT),
            new MenuRect(16, 491, 892, OPTION_ROW_HEIGHT),
            new MenuRect(16, 565, 892, OPTION_ROW_HEIGHT));

    /** Separators between full-width filename rows. */
    public static final List<MenuRect> FULL_WIDTH_OPTION_DIVIDERS = List.of(
            new MenuRect(16, 193, 892, OPTION_DIVIDER_HEIGHT),
            new MenuRect(16, 267, 892, OPTION_DIVIDER_HEIGHT),
            new MenuRect(16, 341, 892, OPTION_DIVIDER_HEIGHT),
            new MenuRect(16, 415, 892, OPTION_DIVIDER_HEIGHT),
            new MenuRect(16, 489, 892, OPTION_DIVIDER_HEIGHT),
            new MenuRect(16, 563, 892, OPTION_DIVIDER_HEIGHT));

    /** Complete footer chrome, including its border. */
    public static final MenuRect FOOTER_PANEL = new MenuRect(8, 652, 908, 76);

    /** Text and navigation-key content area inside {@link #FOOTER_PANEL}. */
    public static final MenuRect FOOTER = new MenuRect(18, 659, 888, 61);

    private MenuScreenTemplate() {
    }

    /** Returns the bounds of one reusable widget row. */
    public static MenuRect optionRow(int index) {
        return OPTION_ROWS.get(index);
    }

    /** Returns one row from the single-panel file-list rail. */
    public static MenuRect fullWidthOptionRow(int index) {
        return FULL_WIDTH_OPTION_ROWS.get(index);
    }
}
