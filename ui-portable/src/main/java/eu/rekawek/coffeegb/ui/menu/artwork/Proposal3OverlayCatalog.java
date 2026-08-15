package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuRoute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fixed, audited paint interiors for the Proposal 3 overlays.
 *
 * <p>The text-free route PNG is immutable layer zero. This catalog contains no panel-sized repair
 * rectangles: row/action interiors are used only for palette-class focus changes, and the other
 * rectangles are tight dynamic text, slider, preview, or confirmation regions.
 */
final class Proposal3OverlayCatalog {

    static final MenuRect OPEN_ROM_HEADER = new MenuRect(691, 27, 202, 57);
    static final MenuRect BACK_HEADER = new MenuRect(744, 25, 151, 61);
    /** Full source-artwork footprint of the obsolete confirmation-page Back button. */
    static final MenuRect CONFIRM_HEADER_CLEAR = new MenuRect(735, 25, 165, 61);
    static final MenuRect HEADER_TITLE = new MenuRect(45, 25, 290, 61);
    static final MenuRect HEADER_CONTEXT = new MenuRect(365, 25, 300, 61);
    static final MenuRect HEADER_ACTION_TEXT = new MenuRect(704, 31, 175, 48);

    /** Frozen game image shown while the top-level pause menu is open. */
    // The display aperture extends beneath the stepped bezel.  This is the full half-open inner
    // rectangle; keeping it exact prevents the source illustration from peeking out beside or
    // below an aspect-fitted live frame.
    static final MenuRect PAUSE_PREVIEW = new MenuRect(30, 139, 351, 243);

    /*
     * Pause is deliberately a strict seven-row rail.  Keeping the dividers outside the row
     * interiors means a focus repaint can never eat into an adjacent row.
     */
    static final MenuRect PAUSE_MENU = new MenuRect(424, 121, 484, 516);
    static final MenuRect PAUSE_RESUME = new MenuRect(424, 121, 484, 72);
    static final MenuRect PAUSE_SAVE = new MenuRect(424, 195, 484, 72);
    static final MenuRect PAUSE_LOAD = new MenuRect(424, 269, 484, 72);
    static final MenuRect PAUSE_OPEN_ROM = new MenuRect(424, 343, 484, 72);
    static final MenuRect PAUSE_RESET = new MenuRect(424, 417, 484, 72);
    static final MenuRect PAUSE_SETTINGS = new MenuRect(424, 491, 484, 72);
    static final MenuRect PAUSE_STOP = new MenuRect(424, 565, 484, 72);
    static final List<MenuRect> PAUSE_DIVIDERS = List.of(
            new MenuRect(424, 193, 484, 2), new MenuRect(424, 267, 484, 2),
            new MenuRect(424, 341, 484, 2), new MenuRect(424, 415, 484, 2),
            new MenuRect(424, 489, 484, 2), new MenuRect(424, 563, 484, 2));
    static final MenuRect PAUSE_HEADER_CONTEXT = new MenuRect(340, 25, 325, 61);
    static final MenuRect PAUSE_HEADER_ACTION = new MenuRect(688, 25, 207, 61);

    /** The inner rail remains an audit mask; the complete asset blit uses AUDIO_KNOB_TRAVEL. */
    static final MenuRect AUDIO_SLIDER = new MenuRect(429, 214, 436, 31);
    /** Full visible canonical knob and attached drop shadow, through source y=259 inclusive. */
    static final MenuRect AUDIO_KNOB = new MenuRect(727, 201, 31, 59);
    /** Full 438x59 slider surface, including the two-row knob-shadow cleanup area. */
    static final MenuRect AUDIO_KNOB_TRAVEL = new MenuRect(427, 201, 438, 59);
    static final MenuRect AUDIO_MUTE_ARROW = new MenuRect(403, 342, 24, 31);
    static final MenuRect AUDIO_EMULATED_ARROW = new MenuRect(403, 429, 24, 31);
    static final MenuRect AUDIO_MUTE = new MenuRect(387, 316, 519, 78);
    static final MenuRect AUDIO_EMULATED = new MenuRect(387, 403, 519, 77);

    /** Inner aperture of the left bezel; persisted 160:144 thumbnails are aspect-fitted here. */
    static final MenuRect SAVE_PREVIEW = new MenuRect(30, 140, 352, 340);
    static final List<MenuRect> SAVE_DIVIDERS = List.of(
            new MenuRect(420, 246, 489, 4), new MenuRect(420, 380, 489, 4),
            new MenuRect(420, 514, 489, 4));

    static final MenuRect AUDIO_LEFT_META = new MenuRect(62, 405, 315, 96);
    static final MenuRect TOUCH_LEFT_META = new MenuRect(61, 154, 315, 53);
    static final MenuRect TOUCH_LEFT_STATUS = new MenuRect(53, 535, 327, 54);
    static final MenuRect CONTROLLER_LEFT_META = new MenuRect(42, 155, 300, 142);
    static final MenuRect CONTROLLER_REMAP = new MenuRect(39, 486, 303, 66);
    static final MenuRect CONTROLLER_CAPTURE = new MenuRect(221, 605, 468, 38);
    static final MenuRect OPTIONAL_LEFT_META = new MenuRect(39, 144, 294, 58);
    static final MenuRect OPTIONAL_LEFT_STATUS = new MenuRect(38, 512, 296, 76);
    static final MenuRect DATA_LEFT_META = new MenuRect(37, 145, 350, 55);
    static final MenuRect DATA_LEFT_COPY = new MenuRect(37, 419, 350, 218);
    static final MenuRect LIBRARY_LEFT_META = new MenuRect(38, 143, 315, 66);
    static final MenuRect LIBRARY_PICKER_COPY = new MenuRect(38, 438, 315, 108);
    static final MenuRect CHOOSE_LEFT_META = new MenuRect(38, 143, 350, 66);
    static final MenuRect CHOOSE_ARCHIVE_COPY = new MenuRect(38, 405, 350, 113);
    static final MenuRect SYSTEM_LEFT_META = new MenuRect(38, 145, 350, 55);
    static final MenuRect SYSTEM_LEFT_STATUS = new MenuRect(38, 505, 350, 55);
    static final MenuRect SYSTEM_RIGHT_COPY = new MenuRect(373, 445, 538, 162);
    static final MenuRect CONFIRM_TITLE = new MenuRect(449, 174, 454, 65);
    static final MenuRect CONFIRM_COPY_ONE = new MenuRect(470, 281, 410, 43);
    static final MenuRect CONFIRM_COPY_TWO = new MenuRect(470, 333, 410, 43);
    static final MenuRect CONFIRM_COPY_THREE = new MenuRect(470, 419, 410, 82);
    static final MenuRect PRINTER_PREVIEW = new MenuRect(482, 153, 320, 384);

    private static final Map<MenuRoute, RouteLayout> LAYOUTS = createLayouts();

    private Proposal3OverlayCatalog() {
    }

    static RouteLayout layout(MenuRoute route) {
        return LAYOUTS.get(Objects.requireNonNull(route, "route"));
    }

    static Map<MenuRoute, RouteLayout> all() {
        return LAYOUTS;
    }

    private static Map<MenuRoute, RouteLayout> createLayouts() {
        EnumMap<MenuRoute, RouteLayout> layouts = new EnumMap<>(MenuRoute.class);
        layouts.put(MenuRoute.PAUSE_CONSOLE, layout(MenuRoute.PAUSE_CONSOLE,
                List.of(slot("resume", PAUSE_RESUME, Surface.DARK),
                        slot("save-state", PAUSE_SAVE, Surface.DARK),
                        slot("load-state", PAUSE_LOAD, Surface.DARK),
                        slot("open-rom", PAUSE_OPEN_ROM, Surface.DARK),
                        slot("reset", PAUSE_RESET, Surface.DARK),
                        slot("settings", PAUSE_SETTINGS, Surface.DARK),
                        slot("stop", PAUSE_STOP, Surface.DARK)), List.of(),
                masks(PAUSE_PREVIEW, PAUSE_MENU, PAUSE_HEADER_CONTEXT, PAUSE_HEADER_ACTION),
                false, "resume", new Marker(443, 147, 26, 20, 31), false));

        layouts.put(MenuRoute.SAVE_STATES, layout(MenuRoute.SAVE_STATES,
                rows(new int[][]{{420, 118, 489, 129}, {420, 252, 489, 129},
                        {420, 386, 489, 129}, {420, 520, 489, 129}}, Surface.DARK),
                List.of(),
                masks(SAVE_PREVIEW,
                        rowsMasks(new int[][]{{420, 118, 489, 129}, {420, 252, 489, 129},
                                {420, 386, 489, 129}, {420, 520, 489, 129}}),
                        SAVE_DIVIDERS.toArray(new MenuRect[0])), false, "slot-0",
                new Marker(443, 151, 30, 20, 31), false));

        layouts.put(MenuRoute.SETTINGS, layout(MenuRoute.SETTINGS,
                rows(new int[][]{{423, 116, 487, 56}, {423, 174, 487, 57},
                        {423, 233, 487, 57}, {423, 292, 487, 57}, {423, 351, 487, 57},
                        {423, 410, 487, 56}, {423, 469, 487, 56}, {423, 527, 487, 57},
                        {423, 586, 487, 56}}, Surface.DARK), List.of(),
                masks(inner(423, 116, 487, 56), inner(423, 174, 487, 57),
                        inner(423, 233, 487, 57), inner(423, 292, 487, 57),
                        inner(423, 351, 487, 57), inner(423, 410, 487, 56),
                        inner(423, 469, 487, 56), inner(423, 527, 487, 57),
                        inner(423, 586, 487, 56)), false, "audio",
                new Marker(442, 132, 13, 20, 31), false));

        layouts.put(MenuRoute.AUDIO, layout(MenuRoute.AUDIO,
                List.of(slot("mute-audio", AUDIO_MUTE, Surface.DARK),
                        slot("emulated-audio", AUDIO_EMULATED, Surface.DARK)),
                actions(new int[][]{{417, 529, 190, 72}, {684, 529, 190, 72}}, Surface.PAPER),
                masks(AUDIO_LEFT_META, AUDIO_SLIDER, AUDIO_KNOB_TRAVEL, AUDIO_MUTE, AUDIO_MUTE_ARROW,
                        AUDIO_EMULATED, AUDIO_EMULATED_ARROW, inner(417, 529, 190, 72),
                        inner(684, 529, 190, 72)), false, "mute-audio",
                new Marker(405, 342, 26, 20, 31), false));

        layouts.put(MenuRoute.TOUCH_CONTROLS, layout(MenuRoute.TOUCH_CONTROLS,
                rows(new int[][]{{420, 118, 490, 109}, {420, 231, 490, 108},
                        {420, 343, 490, 108}}, Surface.DARK),
                actions(new int[][]{{435, 477, 457, 59}, {435, 563, 457, 59}}, Surface.PAPER),
                masks(TOUCH_LEFT_META, TOUCH_LEFT_STATUS, rowsMasks(new int[][]{
                        {420, 118, 490, 109}, {420, 231, 490, 108}, {420, 343, 490, 108}}),
                        inner(435, 477, 457, 59), inner(435, 563, 457, 59)), false,
                "haptics", new Marker(443, 158, 40, 20, 31), false));

        layouts.put(MenuRoute.CONTROLLER_MAPPING, layout(MenuRoute.CONTROLLER_MAPPING,
                rows(new int[][]{{366, 115, 544, 41}, {366, 161, 544, 40},
                        {366, 203, 544, 40}, {366, 245, 544, 40}, {366, 287, 544, 40},
                        {366, 330, 544, 40}, {366, 372, 544, 40}, {366, 414, 544, 40},
                        {366, 457, 544, 40}, {366, 499, 544, 40}, {366, 542, 544, 40}},
                        Surface.DARK), List.of(),
                masks(CONTROLLER_LEFT_META, CONTROLLER_REMAP, CONTROLLER_CAPTURE,
                        rowsMasks(new int[][]{{366, 115, 544, 41}, {366, 161, 544, 40},
                                {366, 203, 544, 40}, {366, 245, 544, 40}, {366, 287, 544, 40},
                                {366, 330, 544, 40}, {366, 372, 544, 40}, {366, 414, 544, 40},
                                {366, 457, 544, 40}, {366, 499, 544, 40}, {366, 542, 544, 40}})),
                false, "map-a", null, false));

        layouts.put(MenuRoute.OPTIONAL_DEVICES, layout(MenuRoute.OPTIONAL_DEVICES,
                rows(new int[][]{{353, 117, 556, 67}, {353, 187, 556, 66},
                        {353, 256, 556, 66}, {353, 324, 556, 66}, {353, 393, 556, 66},
                        {353, 461, 556, 66}}, Surface.DARK),
                actions(new int[][]{{370, 561, 239, 59}, {643, 561, 241, 59}}, Surface.PAPER),
                masks(OPTIONAL_LEFT_META, OPTIONAL_LEFT_STATUS,
                        rowsMasks(new int[][]{{353, 117, 556, 67}, {353, 187, 556, 66},
                                {353, 256, 556, 66}, {353, 324, 556, 66}, {353, 393, 556, 66},
                                {353, 461, 556, 66}}), inner(370, 561, 239, 59),
                        inner(643, 561, 241, 59)), false, "rumble",
                new Marker(375, 136, 19, 20, 31), false));

        layouts.put(MenuRoute.DATA_MEDIA, layout(MenuRoute.DATA_MEDIA,
                rows(new int[][]{{374, 119, 535, 85}, {374, 207, 535, 84},
                        {374, 293, 535, 83}, {374, 379, 535, 83}, {374, 465, 535, 84},
                        {374, 553, 535, 86}}, Surface.DARK), List.of(),
                masks(DATA_LEFT_META, DATA_LEFT_COPY,
                        rowsMasks(new int[][]{{374, 119, 535, 85}, {374, 207, 535, 84},
                                {374, 293, 535, 83}, {374, 379, 535, 83}, {374, 465, 535, 84},
                                {374, 553, 535, 86}})), false, "import-battery", null, false));

        layouts.put(MenuRoute.LIBRARY, layout(MenuRoute.LIBRARY,
                rows(new int[][]{{369, 175, 528, 61}, {369, 240, 528, 62},
                        {369, 305, 528, 62}, {369, 370, 528, 62}, {369, 435, 528, 62},
                        {369, 500, 528, 60}}, Surface.DARK),
                actions(new int[][]{{34, 583, 856, 52}}, Surface.PAPER),
                masks(LIBRARY_LEFT_META, LIBRARY_PICKER_COPY,
                        rowsMasks(new int[][]{{369, 175, 528, 61}, {369, 240, 528, 62},
                                {369, 305, 528, 62}, {369, 370, 528, 62}, {369, 435, 528, 62},
                                {369, 500, 528, 60}}), inner(34, 583, 856, 52)), true,
                "recent-0", null, false));

        layouts.put(MenuRoute.CHOOSE_ROM, layout(MenuRoute.CHOOSE_ROM,
                rows(new int[][]{{387, 179, 524, 75}, {387, 257, 524, 75},
                        {387, 335, 524, 75}}, Surface.DARK),
                actions(new int[][]{{13, 515, 898, 70}, {13, 587, 898, 65}}, Surface.DARK),
                masks(CHOOSE_LEFT_META, CHOOSE_ARCHIVE_COPY,
                        rowsMasks(new int[][]{{387, 179, 524, 75}, {387, 257, 524, 75},
                                {387, 335, 524, 75}}), inner(13, 515, 898, 70),
                        inner(13, 587, 898, 65)), true, "rom-1",
                new Marker(407, 202, 23, 20, 31), false));

        layouts.put(MenuRoute.SYSTEM, layout(MenuRoute.SYSTEM,
                rows(new int[][]{{378, 124, 530, 95}, {378, 223, 530, 103},
                        {378, 329, 530, 103}}, Surface.DARK), List.of(),
                masks(SYSTEM_LEFT_META, SYSTEM_LEFT_STATUS, SYSTEM_RIGHT_COPY,
                        rowsMasks(new int[][]{{378, 124, 530, 95}, {378, 223, 530, 103},
                                {378, 329, 530, 103}})), false, "video-status", null, false));

        layouts.put(MenuRoute.ABOUT, layout(MenuRoute.ABOUT,
                rows(new int[][]{{352, 115, 558, 89}, {352, 207, 558, 83},
                        {352, 292, 558, 82}, {352, 376, 558, 83},
                        {352, 461, 558, 87}}, Surface.DARK),
                actions(new int[][]{{18, 558, 888, 84}}, Surface.PAPER),
                masks((Object) rowsMasks(new int[][]{{352, 115, 558, 89}, {352, 207, 558, 83},
                        {352, 292, 558, 82}, {352, 376, 558, 83},
                        {352, 461, 558, 87}})), false,
                "privacy-notices", null, false));

        layouts.put(MenuRoute.CONFIRM_ACTION, layout(MenuRoute.CONFIRM_ACTION, List.of(),
                actions(new int[][]{{440, 514, 198, 75}, {665, 510, 213, 82}}, Surface.PAPER),
                masks(CONFIRM_HEADER_CLEAR, CONFIRM_TITLE, CONFIRM_COPY_ONE, CONFIRM_COPY_TWO,
                        CONFIRM_COPY_THREE,
                        inner(440, 514, 198, 75), inner(665, 510, 213, 82)),
                false, "cancel", null, false));

        layouts.put(MenuRoute.PRINTER_PAPER, layout(MenuRoute.PRINTER_PAPER, List.of(),
                actions(new int[][]{{386, 574, 241, 53}, {637, 574, 259, 53}}, Surface.PAPER),
                masks(PRINTER_PREVIEW, inner(386, 574, 241, 53),
                        inner(637, 574, 259, 53)), false, "export-share-paper",
                new Marker(647, 587, 10, 22, 29), true));

        if (layouts.size() != MenuRoute.values().length) {
            throw new IllegalStateException("Proposal 3 overlay catalog does not cover every route");
        }
        return Collections.unmodifiableMap(layouts);
    }

    private static RouteLayout layout(MenuRoute route, List<Slot> rows, List<Slot> actions,
            List<MenuRect> masks, boolean scrollable, String canonicalFocusId, Marker marker,
            boolean actionMarker) {
        return new RouteLayout(route, rows, actions, masks, scrollable, canonicalFocusId, marker,
                actionMarker);
    }

    private static List<Slot> rows(int[][] values, Surface surface) {
        ArrayList<Slot> result = new ArrayList<>(values.length);
        for (int[] value : values) {
            result.add(slot("row-" + result.size(), inner(value[0], value[1], value[2], value[3]),
                    surface));
        }
        return List.copyOf(result);
    }

    private static List<Slot> actions(int[][] values, Surface surface) {
        ArrayList<Slot> result = new ArrayList<>(values.length);
        for (int[] value : values) {
            result.add(slot("action-" + result.size(), inner(value[0], value[1], value[2], value[3]),
                    surface));
        }
        return List.copyOf(result);
    }

    private static Slot slot(String id, MenuRect bounds, Surface surface) {
        return new Slot(id, bounds, surface);
    }

    private static MenuRect inner(int x, int y, int width, int height) {
        return new MenuRect(x + 3, y + 3, Math.max(1, width - 6), Math.max(1, height - 6));
    }

    private static List<MenuRect> masks(Object... values) {
        ArrayList<MenuRect> result = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof MenuRect rect) {
                result.add(rect);
            } else if (value instanceof MenuRect[] rectangles) {
                Collections.addAll(result, rectangles);
            }
        }
        return List.copyOf(result);
    }

    private static MenuRect[] rowsMasks(int[][] values) {
        MenuRect[] result = new MenuRect[values.length];
        for (int index = 0; index < values.length; index++) {
            int[] value = values[index];
            result[index] = inner(value[0], value[1], value[2], value[3]);
        }
        return result;
    }

    enum Surface {
        PAPER,
        DARK
    }

    static final class Slot {
        private final String id;
        private final MenuRect bounds;
        private final Surface surface;

        private Slot(String id, MenuRect bounds, Surface surface) {
            this.id = Objects.requireNonNull(id, "id");
            this.bounds = Objects.requireNonNull(bounds, "bounds");
            this.surface = Objects.requireNonNull(surface, "surface");
        }

        String id() {
            return id;
        }

        MenuRect bounds() {
            return bounds;
        }

        Surface surface() {
            return surface;
        }
    }

    static final class Region {
        private final MenuRect bounds;

        private Region(MenuRect bounds) {
            this.bounds = bounds;
        }

        MenuRect bounds() {
            return bounds;
        }

        boolean repair() {
            return false;
        }

        Surface surface() {
            return Surface.PAPER;
        }
    }

    static final class Marker {
        private final int sourceX;
        private final int sourceY;
        private final int relativeY;
        private final int width;
        private final int height;

        private Marker(int sourceX, int sourceY, int relativeY, int width, int height) {
            this.sourceX = sourceX;
            this.sourceY = sourceY;
            this.relativeY = relativeY;
            this.width = width;
            this.height = height;
        }

        int sourceX() {
            return sourceX;
        }

        int sourceY() {
            return sourceY;
        }

        int relativeY() {
            return relativeY;
        }

        int width() {
            return width;
        }

        int height() {
            return height;
        }
    }

    static final class RouteLayout {
        private final MenuRoute route;
        private final List<Slot> rows;
        private final List<Slot> actions;
        private final List<MenuRect> dynamicMasks;
        private final boolean scrollable;
        private final String canonicalFocusId;
        private final Marker marker;
        private final boolean actionMarker;

        private RouteLayout(MenuRoute route, List<Slot> rows, List<Slot> actions,
                List<MenuRect> dynamicMasks, boolean scrollable, String canonicalFocusId,
                Marker marker, boolean actionMarker) {
            this.route = route;
            this.rows = List.copyOf(rows);
            this.actions = List.copyOf(actions);
            this.dynamicMasks = List.copyOf(dynamicMasks);
            this.scrollable = scrollable;
            this.canonicalFocusId = canonicalFocusId;
            this.marker = marker;
            this.actionMarker = actionMarker;
        }

        MenuRoute route() {
            return route;
        }

        List<Slot> rows() {
            return rows;
        }

        List<Slot> actions() {
            return actions;
        }

        List<MenuRect> dynamicMasks() {
            return dynamicMasks;
        }

        boolean scrollable() {
            return scrollable;
        }

        String canonicalFocusId() {
            return canonicalFocusId;
        }

        Marker marker() {
            return marker;
        }

        boolean actionMarker() {
            return actionMarker;
        }

        List<Region> regions() {
            return List.of();
        }

        MenuRect footer() {
            return new MenuRect(18, 659, 888, 61);
        }

        Surface surface() {
            return Surface.DARK;
        }

        MenuRect headerActionRegion() {
            return route == MenuRoute.PAUSE_CONSOLE ? OPEN_ROM_HEADER : BACK_HEADER;
        }
    }
}
