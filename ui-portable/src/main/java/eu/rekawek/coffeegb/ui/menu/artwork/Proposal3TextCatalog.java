package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuRoute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

/**
 * Runtime text regions for Proposal 3.
 *
 * <p>The text-free route templates supply layer zero. This catalog only places runtime copy; it
 * never repairs or clears template pixels.
 */
final class Proposal3TextCatalog {

    /** Sol Ultra audit bounds for common chrome; these are also the shared audit masks. */
    static final MenuRect HEADER_TITLE = new MenuRect(45, 25, 290, 61);
    static final MenuRect HEADER_CONTEXT = new MenuRect(340, 25, 365, 61);
    static final MenuRect HEADER_ACTION = new MenuRect(704, 31, 175, 48);
    static final MenuRect FOOTER = new MenuRect(18, 659, 888, 61);

    private static final MenuRect FOOTER_DPAD_CLEAR = new MenuRect(70, 669, 240, 43);
    /* The approved A/B keycaps remain untouched unless a host explicitly omits that control. */
    static final MenuRect FOOTER_CHOOSE_KEYCAP_CLEAR = new MenuRect(404, 665, 48, 54);
    private static final MenuRect FOOTER_CHOOSE_CLEAR = new MenuRect(455, 669, 126, 48);
    private static final MenuRect FOOTER_BACK_CLEAR = new MenuRect(708, 669, 128, 48);

    private static final MenuRect FOOTER_DPAD = new MenuRect(73, 660, 226, 56);
    private static final MenuRect FOOTER_CHOOSE = new MenuRect(458, 670, 123, 48);
    private static final MenuRect FOOTER_BACK = new MenuRect(722, 670, 94, 48);

    private static final EnumMap<MenuRoute, List<TextRegion>> ROUTES = createRoutes();

    private Proposal3TextCatalog() {
    }

    static List<TextRegion> regions(MenuRoute route) {
        return ROUTES.get(Objects.requireNonNull(route, "route"));
    }

    static List<MenuRect> masks(MenuRoute route) {
        ArrayList<MenuRect> masks = new ArrayList<>();
        masks.add(FOOTER);
        for (TextRegion region : regions(route)) {
            masks.add(region.bounds());
        }
        return List.copyOf(masks);
    }

    static List<MenuRect> footerClearRegions() {
        return List.of(FOOTER_DPAD_CLEAR, FOOTER_CHOOSE_CLEAR, FOOTER_BACK_CLEAR);
    }

    private static EnumMap<MenuRoute, List<TextRegion>> createRoutes() {
        EnumMap<MenuRoute, List<TextRegion>> routes = new EnumMap<>(MenuRoute.class);
        for (MenuRoute route : MenuRoute.values()) {
            ArrayList<TextRegion> regions = new ArrayList<>();
            common(regions);
            switch (route) {
                case PAUSE_CONSOLE -> pause(regions);
                case SAVE_STATES -> saveStates(regions);
                case SETTINGS -> settings(regions);
                case AUDIO -> audio(regions);
                case TOUCH_CONTROLS -> touchControls(regions);
                case CONTROLLER_MAPPING -> controllerMapping(regions);
                case OPTIONAL_DEVICES -> optionalDevices(regions);
                case DATA_MEDIA -> dataMedia(regions);
                case LIBRARY -> library(regions);
                case CHOOSE_ROM -> chooseRom(regions);
                case SYSTEM -> system(regions);
                case ABOUT -> about(regions);
                case CONFIRM_ACTION -> confirm(regions);
                case PRINTER_PAPER -> printer(regions);
            }
            routes.put(route, Collections.unmodifiableList(regions));
        }
        return routes;
    }

    private static void common(List<TextRegion> regions) {
        regions.add(region(Key.HEADER_TITLE, 0, HEADER_TITLE, Surface.PAPER,
                align(Horizontal.LEFT), Proposal3GlyphAtlas.Role.SEMIBOLD));
        regions.add(region(Key.HEADER_CONTEXT, 0, HEADER_CONTEXT, Surface.PAPER,
                align(Horizontal.LEFT), Proposal3GlyphAtlas.Role.MEDIUM));
        regions.add(region(Key.HEADER_ACTION, 0, HEADER_ACTION, Surface.PAPER,
                align(Horizontal.CENTER), Proposal3GlyphAtlas.Role.MEDIUM));
        regions.add(region(Key.FOOTER_DPAD, 0, FOOTER_DPAD, Surface.PAPER,
                align(Horizontal.CENTER), Proposal3GlyphAtlas.Role.MEDIUM));
        regions.add(region(Key.FOOTER_LABEL, 1, FOOTER_CHOOSE, Surface.PAPER,
                align(Horizontal.LEFT), Proposal3GlyphAtlas.Role.MEDIUM));
        regions.add(region(Key.FOOTER_LABEL, 2, FOOTER_BACK, Surface.PAPER,
                align(Horizontal.LEFT), Proposal3GlyphAtlas.Role.MEDIUM));
    }

    private static void pause(List<TextRegion> regions) {
        side(regions, new MenuRect(32, 405, 330, 45),
                new MenuRect[]{new MenuRect(31, 496, 172, 48),
                        new MenuRect(220, 496, 159, 48), new MenuRect(102, 577, 285, 52)},
                Proposal3GlyphAtlas.Role.NOTICE);
    }

    private static void saveStates(List<TextRegion> regions) {
        // The blank area below the selected thumbnail is reserved for authoritative managed-state
        // metadata. Empty/loading/unavailable slots simply provide no side line, leaving the
        // approved artwork untouched.
        regions.add(region(Key.SIDE_LINE, 0, new MenuRect(30, 505, 352, 44), Surface.PAPER,
                align(Horizontal.CENTER), Proposal3GlyphAtlas.Role.NOTICE));
    }

    private static void settings(List<TextRegion> regions) {
        // Settings is intentionally a small route index. Its header and three rows carry all
        // required context; the old configuration/status copy only duplicated that information.
    }

    private static void audio(List<TextRegion> regions) {
        // The slider carries its own live value. Do not repeat VOLUME/ACTIVE in the side panel.
    }

    private static void touchControls(List<TextRegion> regions) {
        // Controls rows and the B footer are sufficient; fixed skin/status copy is omitted.
    }

    private static void controllerMapping(List<TextRegion> regions) {
        side(regions, new MenuRect(53, 150, 286, 44),
                new MenuRect[]{new MenuRect(41, 247, 302, 40),
                        new MenuRect(41, 493, 302, 38), new MenuRect(41, 535, 302, 38)});
        // Capture status is supplied by the host while a mapping is being captured. It must not
        // be baked into every idle controller page.
    }

    private static void optionalDevices(List<TextRegion> regions) {
        side(regions, new MenuRect(43, 147, 294, 44),
                new MenuRect[]{new MenuRect(43, 546, 294, 34),
                        new MenuRect(43, 583, 294, 31), new MenuRect(43, 616, 294, 22)});
        literal(regions, "RUMBLE", new MenuRect(45, 291, 124, 34), Surface.PAPER,
                align(Horizontal.CENTER), Proposal3GlyphAtlas.Role.MEDIUM);
        literal(regions, "CAMERA", new MenuRect(186, 291, 126, 34), Surface.PAPER,
                align(Horizontal.CENTER), Proposal3GlyphAtlas.Role.MEDIUM);
        literal(regions, "TILT", new MenuRect(63, 457, 102, 34), Surface.PAPER,
                align(Horizontal.CENTER), Proposal3GlyphAtlas.Role.MEDIUM);
        literal(regions, "PRINTER", new MenuRect(184, 457, 130, 34), Surface.PAPER,
                align(Horizontal.CENTER), Proposal3GlyphAtlas.Role.SMALL);
    }

    private static void dataMedia(List<TextRegion> regions) {
        side(regions, new MenuRect(44, 143, 306, 44),
                new MenuRect[]{new MenuRect(42, 409, 324, 38),
                        new MenuRect(42, 453, 324, 38), new MenuRect(42, 506, 324, 105)});
    }

    private static void library(List<TextRegion> regions) {
        literal(regions, "OPEN ROM", new MenuRect(69, 167, 280, 44), Surface.PAPER,
                align(Horizontal.CENTER), Proposal3GlyphAtlas.Role.SEMIBOLD);
        side(regions, new MenuRect(494, 123, 320, 44),
                new MenuRect[]{new MenuRect(42, 425, 320, 36),
                        new MenuRect(42, 466, 320, 36), new MenuRect(42, 507, 320, 37)});
    }

    private static void chooseRom(List<TextRegion> regions) {
        literal(regions, "ARCHIVE", new MenuRect(82, 123, 250, 43), Surface.PAPER,
                align(Horizontal.CENTER), Proposal3GlyphAtlas.Role.SEMIBOLD);
        side(regions, new MenuRect(478, 123, 340, 43),
                new MenuRect[]{new MenuRect(43, 393, 320, 38),
                        new MenuRect(43, 435, 320, 38), new MenuRect(43, 477, 320, 38)});
    }

    private static void system(List<TextRegion> regions) {
        // Display options are self-describing rows. The old system/profile/rewind explanation
        // referred to unavailable host settings and did not belong in this in-screen route.
    }

    private static void about(List<TextRegion> regions) {
        side(regions, new MenuRect(51, 144, 282, 40),
                new MenuRect[]{new MenuRect(44, 407, 314, 38),
                        new MenuRect(44, 491, 314, 38)});
        literal(regions, "GITHUB.COM/TREKAWEK/COFFEE-GB", new MenuRect(209, 585, 680, 43),
                Surface.PAPER, align(Horizontal.CENTER), Proposal3GlyphAtlas.Role.MEDIUM);
    }

    private static void confirm(List<TextRegion> regions) {
        literal(regions, "CONFIRM", new MenuRect(68, 158, 300, 43), Surface.PAPER,
                align(Horizontal.CENTER), Proposal3GlyphAtlas.Role.MEDIUM);
        regions.add(region(Key.CONFIRM_TITLE, 0, new MenuRect(447, 171, 458, 70),
                Surface.PAPER, align(Horizontal.CENTER), Proposal3GlyphAtlas.Role.SEMIBOLD));
        regions.add(region(Key.CONFIRM_COPY_ONE, 0, new MenuRect(468, 279, 414, 46),
                Surface.PAPER, align(Horizontal.CENTER), Proposal3GlyphAtlas.Role.MEDIUM));
        regions.add(region(Key.CONFIRM_COPY_TWO, 0, new MenuRect(468, 331, 414, 46),
                Surface.PAPER, align(Horizontal.CENTER), Proposal3GlyphAtlas.Role.MEDIUM));
        regions.add(region(Key.CONFIRM_COPY_THREE, 0, new MenuRect(468, 417, 414, 86),
                Surface.PAPER, align(Horizontal.CENTER), Proposal3GlyphAtlas.Role.SMALL));
    }

    private static void printer(List<TextRegion> regions) {
        side(regions, new MenuRect(37, 153, 315, 44),
                new MenuRect[]{new MenuRect(45, 487, 306, 39),
                        new MenuRect(45, 530, 306, 39), new MenuRect(45, 572, 306, 39)});
    }

    private static void side(List<TextRegion> regions, MenuRect heading, MenuRect[] lines) {
        side(regions, heading, lines, Proposal3GlyphAtlas.Role.SMALL);
    }

    private static void side(List<TextRegion> regions, MenuRect heading, MenuRect[] lines,
            Proposal3GlyphAtlas.Role lineRole) {
        regions.add(region(Key.SIDE_HEADING, 0, heading, Surface.PAPER,
                align(Horizontal.CENTER), Proposal3GlyphAtlas.Role.MEDIUM));
        for (int index = 0; index < lines.length; index++) {
            regions.add(region(Key.SIDE_LINE, index, lines[index], Surface.PAPER,
                    align(Horizontal.CENTER), lineRole));
        }
    }

    private static TextRegion region(Key key, int index, MenuRect bounds, Surface surface,
            Alignment alignment, Proposal3GlyphAtlas.Role role) {
        return new TextRegion(key, index, null, bounds, surface, alignment, role);
    }

    private static TextRegion literal(List<TextRegion> regions, String value, MenuRect bounds,
            Surface surface, Alignment alignment, Proposal3GlyphAtlas.Role role) {
        TextRegion region = new TextRegion(Key.LITERAL, regions.size(), value, bounds, surface,
                alignment, role);
        regions.add(region);
        return region;
    }

    private static Alignment align(Horizontal alignment) {
        return new Alignment(alignment);
    }

    enum Key {
        HEADER_TITLE,
        HEADER_CONTEXT,
        HEADER_ACTION,
        FOOTER_DPAD,
        FOOTER_BUTTON,
        FOOTER_LABEL,
        SIDE_HEADING,
        SIDE_LINE,
        LITERAL,
        CONFIRM_TITLE,
        CONFIRM_COPY_ONE,
        CONFIRM_COPY_TWO,
        CONFIRM_COPY_THREE
    }

    enum Surface {
        PAPER,
        DARK
    }

    enum Horizontal {
        LEFT,
        CENTER,
        RIGHT
    }

    static final class Alignment {
        private final Horizontal horizontal;

        private Alignment(Horizontal horizontal) {
            this.horizontal = horizontal;
        }

        Horizontal horizontal() {
            return horizontal;
        }
    }

    static final class TextRegion {
        private final Key key;
        private final int index;
        private final String literal;
        private final MenuRect bounds;
        private final Surface surface;
        private final Alignment alignment;
        private final Proposal3GlyphAtlas.Role role;

        private TextRegion(Key key, int index, String literal, MenuRect bounds, Surface surface,
                Alignment alignment, Proposal3GlyphAtlas.Role role) {
            this.key = Objects.requireNonNull(key, "key");
            this.index = index;
            this.literal = literal;
            this.bounds = Objects.requireNonNull(bounds, "bounds");
            this.surface = Objects.requireNonNull(surface, "surface");
            this.alignment = Objects.requireNonNull(alignment, "alignment");
            this.role = Objects.requireNonNull(role, "role");
        }

        Key key() {
            return key;
        }

        int index() {
            return index;
        }

        String literal() {
            return literal;
        }

        MenuRect bounds() {
            return bounds;
        }

        Surface surface() {
            return surface;
        }

        Alignment alignment() {
            return alignment;
        }

        Proposal3GlyphAtlas.Role role() {
            return role;
        }
    }
}
