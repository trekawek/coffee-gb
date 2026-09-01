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
                            button("resume", "RESUME"),
                            button("save-state", "SAVE STATE"),
                            button("load-state", "LOAD STATE"),
                            button("open-rom", "OPEN ROM"),
                            button("reset", "RESET GAME"),
                            button("settings", "SETTINGS"),
                            button("recent-games", "RECENT GAMES")));
            case SAVE_STATES -> statePage(false);
            case RECENT_GAMES -> MenuPage.from(MenuPageSpec.recentGames(List.of(), null));
            case SETTINGS -> page(route, "COFFEE GB", "SETTINGS", "", "",
                    List.of(),
                    items(button("system", "SYSTEM"),
                            button("display", "DISPLAY"),
                            button("audio", "AUDIO"),
                            button("peripherals", "PERIPHERALS")));
            case AUDIO -> new MenuPage(route, "COFFEE GB", "AUDIO", "", "",
                    List.of(),
                    items(
                            slider("volume", "VOLUME", "75%", 75),
                            checkbox("mute-audio", "MUTE", "OFF")), 1, DEFAULT_HINTS,
                    "volume", MenuPreview.empty());
            case DISPLAY -> page(route, "COFFEE GB", "DISPLAY", "", "", List.of(),
                    items(checkbox("sgb-border", "SGB BORDER", "OFF"),
                            dropdown("dmg-colors", "DMG COLORS", "GREEN")));
            case TOUCH_CONTROLS -> page(route, "COFFEE GB", "CONTROLS", "", "",
                    List.of(),
                    items(
                            checkbox("haptics", "HAPTIC FEEDBACK", "ON"),
                            button("controller-mapping", "BUTTON MAPPING"),
                            button("reset-touch", "RESET DEFAULTS")));
            case CONTROLLER_MAPPING -> page(route, "COFFEE GB", "CONTROLLER MAPPING", "", "GAMEPAD",
                    List.of("CONNECTED", "PRESS A TO REMAP", ""),
                    items(
                            button("map-a", "A"),
                            button("map-b", "B"),
                            button("map-start", "START"),
                            button("map-select", "SELECT"),
                            button("map-up", "UP"),
                            button("map-down", "DOWN"),
                            button("map-left", "LEFT"),
                            button("map-right", "RIGHT"),
                            button("reset-controller", "RESET MAPPINGS")));
            case OPTIONAL_DEVICES -> page(route, "COFFEE GB", "PERIPHERALS", "", "", List.of(),
                    items(
                            dropdown("camera", "CAMERA", "OFF"),
                            dropdown("gamepad", "GAMEPAD", "AUTO"),
                            checkbox("gps", "GPS", "OFF")));
            case OPTION_PICKER -> page(route, "COFFEE GB", "OPTION PICKER", "", "", List.of(),
                    items(button("choice:default", "DEFAULT", "SELECTED")));
            case PRINTER_PAPER -> new MenuPage(route, "COFFEE GB", "PRINTER PAPER", "",
                    "GAME BOY PRINTER", List.of("PAPER READY", "1 PAGE", ""), items(
                            button("clear-paper", "CLEAR PAPER"),
                            button("export-share-paper", "EXPORT & SHARE")), 1, DEFAULT_HINTS,
                    "export-share-paper", MenuPreview.empty());
            case DATA_MEDIA -> page(route, "COFFEE GB", "DATA & MEDIA", "", "CURRENT GAME",
                    List.of("PRIVATE SAVE DATA", "SELECT FILE", ""),
                    items(
                            button("import-battery", "IMPORT BATTERY SAVE"),
                            button("export-battery", "EXPORT BATTERY SAVE"),
                            button("import-state-0", "IMPORT STATE SLOT 0"),
                            button("export-state-0", "EXPORT STATE SLOT 0"),
                            button("export-screenshot", "EXPORT SCREENSHOT"),
                            button("preview-printer-paper", "PRINTER PAPER")));
            case LIBRARY -> page(route, "COFFEE GB", "LIBRARY", "", "", List.of(),
                    items(
                            button("recent-games", "RECENT GAMES"),
                            button("open-rom", "OPEN ROM"),
                            button("settings", "SETTINGS")));
            case CHOOSE_ROM -> page(route, "COFFEE GB", "CHOOSE ROM", "", "ZIP CONTENTS",
                    List.of("COFFEE TEST.ZIP", "3 ROMS FOUND", ""),
                    items(
                            button("rom-1", "ADVENTURE BOY.GB"),
                            button("rom-2", "POCKET CAMERA.GBC"),
                            button("rom-3", "COFFEE DEMO.GB")));
            case SYSTEM -> page(route, "COFFEE GB", "SYSTEM", "", "", List.of(),
                    items(dropdown("dmg-games", "DMG GAMES", "AUTO"),
                            dropdown("cgb-games", "CGB GAMES", "AUTO"),
                            dropdown("bootstrap", "BOOTSTRAP", "SKIP"),
                            dropdown("execution-mode", "EXECUTION MODE", "PERFORMANCE")));
            case ABOUT -> page(route, "COFFEE GB", "ABOUT", "", "COFFEE GB",
                    List.of("MIT LICENSE", "OPEN SOURCE"),
                    items(
                            button("privacy-notices", "PRIVACY & NOTICES"),
                            button("network", "NO NETWORK ACCESS"),
                            button("storage", "NO BROAD STORAGE ACCESS"),
                            button("live-camera", "CAMERA ONLY WHEN ENABLED"),
                            button("source-notices", "SOURCE & THIRD-PARTY NOTICES")));
            case CONFIRM_ACTION -> page(route, "COFFEE GB", "CONFIRM", "", "RESET GAME",
                    List.of("UNSAVED PROGRESS MAY BE LOST"),
                    items(button("cancel", "CANCEL"),
                            button("confirm", "CONFIRM", "RESET GAME")), 1, DEFAULT_HINTS,
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
                        button("slot-0", "SLOT 0"),
                        button("slot-1", "SLOT 1"),
                        button("slot-2", "SLOT 2"),
                        button("slot-3", "SLOT 3"),
                        button("slot-4", "SLOT 4"),
                        button("slot-5", "SLOT 5"),
                        button("slot-6", "SLOT 6"),
                        button("slot-7", "SLOT 7"),
                        button("slot-8", "SLOT 8"),
                        button("slot-9", "SLOT 9")),
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

    private static MenuItem button(String id, String label) {
        return widget(id, label, "", MenuWidgetType.BUTTON, -1);
    }

    private static MenuItem button(String id, String label, String detail) {
        return widget(id, label, detail, MenuWidgetType.BUTTON, -1);
    }

    private static MenuItem dropdown(String id, String label, String detail) {
        return widget(id, label, detail, MenuWidgetType.DROPDOWN, -1);
    }

    private static MenuItem checkbox(String id, String label, String detail) {
        return widget(id, label, detail, MenuWidgetType.CHECKBOX, -1);
    }

    private static MenuItem slider(String id, String label, String detail, int progress) {
        return widget(id, label, detail, MenuWidgetType.SLIDER, progress);
    }

    private static MenuItem widget(String id, String label, String detail,
            MenuWidgetType widgetType, int progress) {
        return new MenuItem(id, label, detail, true, null, widgetType, progress);
    }
}
