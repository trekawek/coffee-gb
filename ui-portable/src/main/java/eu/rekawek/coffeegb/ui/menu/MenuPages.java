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
                            item("stop", "STOP GAME")));
            case SAVE_STATES -> statePage(false);
            case SETTINGS -> page(route, "COFFEE GB", "SETTINGS", "", "CONFIGURATION",
                    List.of("SESSION READY", "", ""),
                    items(
                            item("audio", "AUDIO"),
                            item("touch-controls", "TOUCH CONTROLS"),
                            item("controller-mapping", "CONTROLLER MAPPING"),
                            item("optional-devices", "OPTIONAL DEVICES"),
                            item("video", "VIDEO"),
                            item("system-profile", "SYSTEM PROFILE"),
                            item("rewind-save", "REWIND & SAVE"),
                            item("data-media", "DATA & MEDIA"),
                            item("about", "ABOUT")));
            case AUDIO -> new MenuPage(route, "COFFEE GB", "AUDIO", "", "AUDIO OUTPUT",
                    List.of("VOLUME 75%", "ACTIVE", ""),
                    items(
                            item("volume", "VOLUME", "75%"),
                            item("mute-audio", "MUTE AUDIO", "OFF"),
                            item("emulated-audio", "EMULATED AUDIO", "ON"),
                            item("save-audio", "SAVE"),
                            item("cancel-audio", "CANCEL")), 1, DEFAULT_HINTS,
                    "mute-audio", MenuPreview.empty());
            case TOUCH_CONTROLS -> page(route, "COFFEE GB", "TOUCH CONTROLS", "", "COFFEE GB SKIN",
                    List.of("POSITIONS FIXED", "", ""),
                    items(
                            item("haptics", "HAPTIC FEEDBACK", "ON"),
                            item("button-opacity", "BUTTON OPACITY", "70%"),
                            item("reset-touch", "RESET DEFAULTS"),
                            item("save-touch", "SAVE"),
                            item("cancel-touch", "CANCEL")));
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
                            item("invert-x", "HORIZONTAL AXIS", "NORMAL"),
                            item("invert-y", "VERTICAL AXIS", "NORMAL"),
                            item("reset-controller", "RESET MAPPINGS")));
            case OPTIONAL_DEVICES -> page(route, "COFFEE GB", "OPTIONAL DEVICES", "", "PERIPHERALS",
                    List.of("CARTRIDGE DEPENDENT", "", ""),
                    items(
                            item("rumble", "RUMBLE", "OFF"),
                            item("live-camera", "LIVE CAMERA", "OFF"),
                            item("game-boy-printer", "GAME BOY PRINTER", "OFF"),
                            item("calibrate-tilt", "CALIBRATE TILT"),
                            item("preview-printer-paper", "PREVIEW PRINTER PAPER"),
                            item("export-share-paper", "EXPORT & SHARE PAPER"),
                            item("save-devices", "SAVE"),
                            item("cancel-devices", "CANCEL")));
            case PRINTER_PAPER -> new MenuPage(route, "COFFEE GB", "PRINTER PAPER", "",
                    "GAME BOY PRINTER", List.of("PAPER READY", "1 PAGE", ""), items(
                            item("clear-paper", "CLEAR PAPER"),
                            item("export-share-paper", "EXPORT & SHARE"),
                            item("back", "BACK")), 1, DEFAULT_HINTS,
                    "export-share-paper", MenuPreview.empty());
            case DATA_MEDIA -> page(route, "COFFEE GB", "DATA & MEDIA", "", "CURRENT GAME",
                    List.of("PRIVATE SAVE DATA", "ANDROID PICKER", ""),
                    items(
                            item("import-battery", "IMPORT BATTERY SAVE"),
                            item("export-battery", "EXPORT BATTERY SAVE"),
                            item("import-state-0", "IMPORT STATE SLOT 0"),
                            item("export-state-0", "EXPORT STATE SLOT 0"),
                            item("export-screenshot", "EXPORT NATIVE SCREENSHOT"),
                            item("preview-printer-paper", "PRINTER PAPER")));
            case LIBRARY -> page(route, "COFFEE GB", "LIBRARY", "OPEN ROM", "RECENT ROMS",
                    List.of("ANDROID", "FILE PICKER", ""),
                    items(
                            item("recent-rom", "ADVENTURE BOY.GB", "TODAY"),
                            item("open-rom", "OPEN ROM"),
                            item("choose-rom", "POCKET CAMERA.GBC", "YESTERDAY"),
                            item("clear-recent", "COFFEE TEST.ZIP", "3 DAYS AGO")));
            case CHOOSE_ROM -> page(route, "COFFEE GB", "CHOOSE ROM", "", "ZIP CONTENTS",
                    List.of("COFFEE TEST.ZIP", "3 ROMS FOUND", ""),
                    items(
                            item("rom-1", "ADVENTURE BOY.GB"),
                            item("rom-2", "POCKET CAMERA.GBC"),
                            item("rom-3", "COFFEE DEMO.GB")));
            case SYSTEM -> page(route, "COFFEE GB", "SYSTEM", "", "RUNTIME",
                    List.of("COFFEE GB ANDROID", "SESSION READY", ""),
                    items(
                            item("video-status", "VIDEO", "NEAREST NEIGHBOR / ASPECT FIT"),
                            item("profile-status", "SYSTEM PROFILE", "SELECTED ON OPEN"),
                            item("rewind-save-status", "REWIND & SAVE", "PORTABLE DEFAULTS"),
                            item("back", "BACK")));
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
}
