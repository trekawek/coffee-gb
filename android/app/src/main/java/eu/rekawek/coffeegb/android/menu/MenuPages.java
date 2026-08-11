package eu.rekawek.coffeegb.android.menu;

import java.util.Arrays;
import java.util.List;

/** Proposal 3 page catalog; side effects remain outside this foundation slice. */
final class MenuPages {

    private static final List<String> DEFAULT_HINTS = List.of("D-PAD MOVE", "[A] OK", "[B] BACK");

    private MenuPages() {
    }

    static MenuPage forRoute(MenuRoute route) {
        if (route == null) {
            throw new IllegalArgumentException("route cannot be null");
        }
        return switch (route) {
            case PAUSE_CONSOLE -> page(route, "COFFEE GB", "PAUSED", "OPEN ROM", "CURRENT GAME",
                    List.of("PLAYING", "01:24", "BATTERY SAVE READY"),
                    items(
                            item("resume", "RESUME"),
                            item("save-state", "SAVE STATE"),
                            item("load-state", "LOAD STATE"),
                            item("reset", "RESET GAME"),
                            item("settings", "SETTINGS"),
                            item("stop", "STOP GAME")));
            case SAVE_STATES -> page(route, "COFFEE GB", "SAVE STATES", "", "STATE BANK",
                    List.of("SLOT 0 READY", "SLOT 1 EMPTY", "SLOT 2 EMPTY"),
                    items(
                            item("slot-0-save", "SLOT 0", "SAVE / LOAD"),
                            item("slot-1-save", "SLOT 1", "SAVE / LOAD"),
                            item("slot-2-save", "SLOT 2", "SAVE / LOAD"),
                            item("slot-3-save", "SLOT 3", "SAVE / LOAD"),
                            item("delete-state", "DELETE STATE")));
            case SETTINGS -> page(route, "COFFEE GB", "SETTINGS", "", "SETTINGS HUB",
                    List.of("AUDIO + INPUT", "DEVICES + DATA", "SYSTEM PROFILE"),
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
            case AUDIO -> page(route, "COFFEE GB", "AUDIO", "", "AUDIO MIX",
                    List.of("NO LIVE PREVIEW", "VOLUME  100%", "EMULATED AUDIO  ON"),
                    items(
                            item("volume", "VOLUME", "100%"),
                            item("mute-audio", "MUTE", "OFF"),
                            item("emulated-audio", "EMULATED AUDIO", "ON"),
                            item("save-audio", "SAVE"),
                            item("cancel-audio", "CANCEL")));
            case TOUCH_CONTROLS -> page(route, "COFFEE GB", "TOUCH CONTROLS", "", "INPUT DECK",
                    List.of("SKIN  CLASSIC", "HAPTICS  ON", "LAYOUT  SAVED"),
                    items(
                            item("haptics", "HAPTICS", "ON"),
                            item("button-opacity", "OPACITY", "FIXED"),
                            item("reset-touch", "RESET"),
                            item("save-touch", "SAVE"),
                            item("cancel-touch", "CANCEL")));
            case CONTROLLER_MAPPING -> page(route, "COFFEE GB", "CONTROLLER MAPPING", "", "INPUT MAP",
                    List.of("DEVICE  BLUETOOTH", "PROFILE  DEFAULT", "A + B TO CANCEL"),
                    items(
                            item("map-a", "A"),
                            item("map-b", "B"),
                            item("map-start", "START"),
                            item("map-select", "SELECT"),
                            item("map-up", "UP"),
                            item("map-down", "DOWN"),
                            item("map-left", "LEFT"),
                            item("map-right", "RIGHT"),
                            item("invert-x", "INVERT X"),
                            item("invert-y", "INVERT Y"),
                            item("reset-controller", "RESET")));
            case OPTIONAL_DEVICES -> page(route, "COFFEE GB", "OPTIONAL DEVICES", "", "ACCESSORIES",
                    List.of("RUMBLE  READY", "CAMERA  PERMISSION", "PRINTER  READY"),
                    items(
                            item("rumble", "RUMBLE", "ON"),
                            item("live-camera", "CAMERA", "READY"),
                            item("calibrate-tilt", "CALIBRATE TILT"),
                            item("game-boy-printer", "PRINTER"),
                            item("preview-printer-paper", "PRINTER PAPER"),
                            item("export-share-paper", "EXPORT & SHARE PAPER"),
                            item("save-devices", "SAVE"),
                            item("cancel-devices", "CANCEL")));
            case PRINTER_PAPER -> page(route, "COFFEE GB", "PRINTER PAPER", "", "PRINTER ROLL",
                    List.of("LAST PRINT  READY", "PAPER  248 PX", "EXPORT IS NATIVE"),
                    items(
                            item("clear-paper", "CLEAR PAPER"),
                            item("export-share-paper", "EXPORT & SHARE"),
                            item("back", "BACK")));
            case DATA_MEDIA -> page(route, "COFFEE GB", "DATA & MEDIA", "", "DATA DECK",
                    List.of("BATTERY SAVE  READY", "STATE SLOT 0  READY", "SCREENSHOT  PNG"),
                    items(
                            item("import-battery", "IMPORT BATTERY SAVE"),
                            item("export-battery", "EXPORT BATTERY SAVE"),
                            item("import-state-0", "IMPORT STATE SLOT 0"),
                            item("export-state-0", "EXPORT STATE SLOT 0"),
                            item("export-screenshot", "EXPORT SCREENSHOT"),
                            item("preview-printer-paper", "PRINTER PAPER")));
            case LIBRARY -> page(route, "COFFEE GB", "LIBRARY", "OPEN ROM", "RECENT ROMS",
                    List.of("LAST OPENED  TODAY", "DOCUMENT PICKER  NATIVE", "ZIP  MULTI-SELECT"),
                    items(
                            item("recent-rom", "RECENT ROM"),
                            item("open-rom", "OPEN ROM"),
                            item("choose-rom", "CHOOSE ROM"),
                            item("clear-recent", "CLEAR RECENTS")));
            case CHOOSE_ROM -> page(route, "COFFEE GB", "CHOOSE ROM", "", "ZIP CONTENTS",
                    List.of("3 ROMS FOUND", "SELECT ONE TO OPEN", "B BACK TO LIBRARY"),
                    items(
                            item("rom-1", "GAME A", ".GB"),
                            item("rom-2", "GAME B", ".GBC"),
                            item("rom-3", "GAME C", ".GB")));
            case SYSTEM -> page(route, "COFFEE GB", "SYSTEM", "", "SYSTEM PROFILE",
                    List.of("VIDEO  RASTER SKIN", "PROFILE  AUTO", "REWIND  DISABLED"),
                    items(
                            item("video-status", "VIDEO", "NEAREST-NEIGHBOUR / ASPECT FIT"),
                            item("profile-status", "SYSTEM PROFILE", "SELECTED ON ROM OPEN"),
                            item("rewind-save-status", "REWIND & SAVE", "PORTABLE DEFAULTS"),
                            item("back", "BACK")));
            case ABOUT -> page(route, "COFFEE GB", "ABOUT", "", "COFFEE GB",
                    List.of("GAME BOY EMULATOR", "MIT LICENSE", "NO NETWORK"),
                    items(
                            item("version", "VERSION", "CURRENT"),
                            item("license", "MIT LICENSE"),
                            item("network", "NO NETWORK ACCESS"),
                            item("storage", "SAF / NO BROAD STORAGE"),
                            item("live-camera", "CAMERA OPT-IN"),
                            item("source-notices", "SOURCE & NOTICES")));
            case CONFIRM_ACTION -> page(route, "COFFEE GB", "CONFIRM ACTION", "", "ARE YOU SURE?",
                    List.of("THIS ACTION CANNOT BE UNDONE", "CURRENT GAME IS PAUSED", "B CANCEL"),
                    items(item("cancel", "CANCEL"), item("confirm", "CONFIRM")));
        };
    }

    private static MenuPage page(MenuRoute route, String title, String context, String headerAction,
            String sideHeading, List<String> sideLines, List<MenuItem> items) {
        return new MenuPage(route, title, context, headerAction, sideHeading, sideLines, items, 1,
                DEFAULT_HINTS);
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
