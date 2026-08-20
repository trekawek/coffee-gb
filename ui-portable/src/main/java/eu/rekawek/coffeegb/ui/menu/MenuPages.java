package eu.rekawek.coffeegb.ui.menu;

import java.util.Arrays;
import java.util.List;

/** Proposal 3 page catalog; side effects remain outside this foundation slice. */
final class MenuPages {

    private static final List<String> DEFAULT_HINTS = List.of("D-PAD MOVE", "A CHOOSE", "B BACK");

    private MenuPages() {
    }

    static MenuPage forRoute(MenuRoute route) {
        if (route == null) {
            throw new IllegalArgumentException("route cannot be null");
        }
        return switch (route) {
            case PAUSE_CONSOLE -> page(route, "COFFEE GB", "", "", "",
                    List.of("PLAY TIME", "00:00", "NO BATTERY SAVE"),
                    items(
                            item("resume", "RESUME"),
                            item("save-state", "SAVE STATE"),
                            item("load-state", "LOAD STATE"),
                            item("open-rom", "OPEN ROM"),
                            item("reset", "RESET GAME"),
                            item("settings", "SETTINGS"),
                            item("recent-games", "RECENT GAMES")));
            case SAVE_STATES -> statePage(false);
            case RECENT_GAMES -> MenuPage.from(MenuPageSpec.recentGames(List.of(), null));
            case SETTINGS -> page(route, "COFFEE GB", "SETTINGS", "", "",
                    List.of(),
                    items(item("system", "SYSTEM"),
                            item("display", "DISPLAY"),
                            item("audio", "AUDIO"),
                            item("peripherals", "PERIPHERALS")));
            case AUDIO -> new MenuPage(route, "COFFEE GB", "AUDIO", "", "",
                    List.of(),
                    items(
                            adjustable("volume", "VOLUME", "75%", 75),
                            item("mute-audio", "MUTE", "OFF")), 1, DEFAULT_HINTS,
                    "volume", MenuPreview.empty());
            case DISPLAY -> page(route, "COFFEE GB", "DISPLAY", "", "", List.of(),
                    items(item("sgb-border", "SGB BORDER", "OFF"),
                            item("dmg-colors", "DMG COLORS", "GREEN")));
            case TOUCH_CONTROLS -> page(route, "COFFEE GB", "CONTROLS", "", "",
                    List.of(),
                    items(
                            item("haptics", "HAPTIC FEEDBACK", "ON"),
                            item("controller-mapping", "BUTTON MAPPING"),
                            item("reset-touch", "RESET DEFAULTS")));
            case CONTROLLER_MAPPING -> page(route, "COFFEE GB", "CONTROLLER MAPPING", "", "GAMEPAD",
                    List.of("CONNECTED", "PRESS A TO REMAP", ""),
                    items(
                            item("map-a", "A"),
                            item("map-b", "B"),
                            item("map-start", "START"),
                            item("map-select", "SELECT"),
                            item("map-up", "UP"),
                            item("map-down", "DOWN"),
                            item("map-left", "LEFT"),
                            item("map-right", "RIGHT"),
                            item("reset-controller", "RESET MAPPINGS")));
            case OPTIONAL_DEVICES -> page(route, "COFFEE GB", "PERIPHERALS", "", "", List.of(),
                    items(
                            item("camera", "CAMERA", "OFF"),
                            item("gamepad", "GAMEPAD", "AUTO"),
                            item("gps", "GPS", "OFF")));
            case OPTION_PICKER -> page(route, "COFFEE GB", "OPTION PICKER", "", "", List.of(),
                    items(item("choice:default", "DEFAULT", "SELECTED")));
            case PRINTER_PAPER -> new MenuPage(route, "COFFEE GB", "PRINTER PAPER", "",
                    "GAME BOY PRINTER", List.of("PAPER READY", "1 PAGE", ""), items(
                            item("clear-paper", "CLEAR PAPER"),
                            item("export-share-paper", "EXPORT & SHARE")), 1, DEFAULT_HINTS,
                    "export-share-paper", MenuPreview.empty());
            case DATA_MEDIA -> page(route, "COFFEE GB", "DATA & MEDIA", "", "CURRENT GAME",
                    List.of("PRIVATE SAVE DATA", "SELECT FILE", ""),
                    items(
                            item("import-battery", "IMPORT BATTERY SAVE"),
                            item("export-battery", "EXPORT BATTERY SAVE"),
                            item("import-state-0", "IMPORT STATE SLOT 0"),
                            item("export-state-0", "EXPORT STATE SLOT 0"),
                            item("export-screenshot", "EXPORT SCREENSHOT"),
                            item("preview-printer-paper", "PRINTER PAPER")));
            case LIBRARY -> page(route, "COFFEE GB", "LIBRARY", "", "", List.of(),
                    items(
                            item("recent-games", "RECENT GAMES"),
                            item("open-rom", "OPEN ROM"),
                            item("settings", "SETTINGS")));
            case CHOOSE_ROM -> page(route, "COFFEE GB", "CHOOSE ROM", "", "ZIP CONTENTS",
                    List.of("COFFEE TEST.ZIP", "3 ROMS FOUND", ""),
                    items(
                            item("rom-1", "ADVENTURE BOY.GB"),
                            item("rom-2", "POCKET CAMERA.GBC"),
                            item("rom-3", "COFFEE DEMO.GB")));
            case SYSTEM -> page(route, "COFFEE GB", "SYSTEM", "", "", List.of(),
                    items(item("dmg-games", "DMG GAMES", "AUTO"),
                            item("cgb-games", "CGB GAMES", "AUTO"),
                            item("bootstrap", "BOOTSTRAP", "SKIP")));
            case ABOUT -> page(route, "COFFEE GB", "ABOUT", "", "COFFEE GB",
                    List.of("MIT LICENSE", "OPEN SOURCE"),
                    items(
                            item("privacy-notices", "PRIVACY & NOTICES"),
                            item("network", "NO NETWORK ACCESS"),
                            item("storage", "NO BROAD STORAGE ACCESS"),
                            item("live-camera", "CAMERA ONLY WHEN ENABLED"),
                            item("source-notices", "SOURCE & THIRD-PARTY NOTICES")));
            case CONFIRM_ACTION -> page(route, "COFFEE GB", "CONFIRM", "", "RESET GAME",
                    List.of("UNSAVED PROGRESS MAY BE LOST"),
                    items(item("cancel", "CANCEL"),
                            item("confirm", "CONFIRM", "RESET GAME")), 2, DEFAULT_HINTS,
                    "cancel", MenuPreview.empty());
        };
    }

    /**
     * The route identity is shared by save and load screens, but their page specs are not.  Hosts
     * replace this page with their detached catalog snapshot before presenting the route.
     */
    static MenuPage statePage(boolean load) {
        String context = load ? "LOAD STATES" : "SAVE STATES";
        return page(MenuRoute.SAVE_STATES, "COFFEE GB", context, "", "", List.of(),
                items(
                        item("slot-0", "SLOT 0"),
                        item("slot-1", "SLOT 1"),
                        item("slot-2", "SLOT 2"),
                        item("slot-3", "SLOT 3"),
                        item("slot-4", "SLOT 4"),
                        item("slot-5", "SLOT 5"),
                        item("slot-6", "SLOT 6"),
                        item("slot-7", "SLOT 7"),
                        item("slot-8", "SLOT 8"),
                        item("slot-9", "SLOT 9")),
                1, List.of("D-PAD MOVE", "A " + (load ? "LOAD" : "SAVE"), "B BACK"),
                "slot-0", MenuPreview.empty());
    }

    private static MenuPage page(MenuRoute route, String title, String context, String headerAction,
            String sideHeading, List<String> sideLines, List<MenuItem> items) {
        return new MenuPage(route, title, context, headerAction, sideHeading, sideLines, items, 1,
                DEFAULT_HINTS);
    }

    private static MenuPage page(MenuRoute route, String title, String context, String headerAction,
            String sideHeading, List<String> sideLines, List<MenuItem> items, int columns,
            List<String> footerHints, String preferredFocusId, MenuPreview preview) {
        return new MenuPage(route, title, context, headerAction, sideHeading, sideLines, items,
                columns, footerHints, preferredFocusId, preview);
    }

    private static List<MenuItem> items(MenuItem... items) {
        return Arrays.asList(items);
    }

    private static MenuItem item(String id, String label) {
        return new MenuItem(id, label);
    }

    private static MenuItem item(String id, String label, String detail) {
        return new MenuItem(id, label, detail);
    }

    private static MenuItem adjustable(String id, String label, String detail, int progress) {
        return new MenuItem(id, label, detail, true, null, true, progress);
    }
}
