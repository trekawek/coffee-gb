package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.ui.menu.MenuPageSpec;
import eu.rekawek.coffeegb.ui.menu.MenuPreview;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import eu.rekawek.coffeegb.core.joypad.Button;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pure Proposal 3 page and draft model; Android side effects stay in {@link MainActivity}. */
final class AndroidMenuModel {

    private static final List<String> HINTS = List.of("D-PAD MOVE", "A CHOOSE", "B BACK");
    private static final List<String> BACK_ONLY_HINTS = List.of("", "", "B BACK");

    private AndroidMenuModel() {
    }

    static AudioDraft audioDraft(int volume, boolean muted) {
        return new AudioDraft(volume, muted);
    }

    static AudioDraft adjustVolume(AudioDraft draft, int edgeDirection) {
        return new AudioDraft(draft.volume() + Integer.signum(edgeDirection) * 5, draft.muted());
    }

    static TouchDraft touchDraft(TouchControlsLayout layout) {
        return new TouchDraft(layout.opacity(), layout.scale(), layout.verticalPosition(),
                layout.leftHanded(), layout.haptics());
    }

    static TouchDraft resetTouchDraft() {
        return new TouchDraft(TouchControlsLayout.DEFAULT_OPACITY,
                TouchControlsLayout.DEFAULT_SCALE,
                TouchControlsLayout.DEFAULT_VERTICAL_POSITION, false, true);
    }

    static DevicesCommit commitDevices(DevicesDraft draft, boolean cameraPermissionGranted) {
        boolean cameraEnabled = draft.camera() && cameraPermissionGranted;
        return new DevicesCommit(draft.rumble(), draft.printer(), cameraEnabled, cameraEnabled,
                draft.camera() && !cameraPermissionGranted);
    }

    static boolean printerPreviewReady(MenuPreview preview) {
        return preview != null && preview.state() == MenuPreview.State.READY;
    }

    static MenuPageSpec settingsPage() {
        return page(MenuRoute.SETTINGS, "COFFEE GB", "SETTINGS", "", "", List.of(), List.of(
                        button("system", "SYSTEM", "", true),
                        button("display", "DISPLAY", "", true),
                        button("audio", "AUDIO", "", true),
                        button("peripherals", "PERIPHERALS", "", true)),
                "system",
                MenuPreview.empty());
    }

    static MenuPageSpec libraryPage(boolean runtimeAvailable) {
        return page(MenuRoute.LIBRARY, "COFFEE GB", "LIBRARY", "", "", List.of(), List.of(
                        button("recent-games", "RECENT GAMES", "", runtimeAvailable),
                        button("open-rom", "OPEN ROM", "", runtimeAvailable),
                        button("settings", "SETTINGS", "", true)),
                runtimeAvailable ? "recent-games" : "settings", MenuPreview.empty());
    }

    static MenuPageSpec displayPage(boolean sgbBorder, boolean grayscale) {
        return page(MenuRoute.DISPLAY, "COFFEE GB", "DISPLAY", "", "", List.of(), List.of(
                checkbox("sgb-border", "SGB BORDER", sgbBorder, true),
                dropdown("dmg-colors", "DMG COLORS", grayscale ? "GREY" : "GREEN", true)),
                "sgb-border", MenuPreview.empty());
    }

    static MenuPageSpec systemPage(String dmgGames, String cgbGames, String bootstrap,
            String executionMode, String preferredFocusId) {
        return page(MenuRoute.SYSTEM, "COFFEE GB", "SYSTEM", "", "", List.of(), List.of(
                dropdown("dmg-games", "DMG GAMES", systemChoiceLabel(dmgGames), true),
                dropdown("cgb-games", "CGB GAMES", systemChoiceLabel(cgbGames), true),
                dropdown("bootstrap", "BOOTSTRAP", systemChoiceLabel(bootstrap), true),
                dropdown("execution-mode", "MODE", executionModeLabel(executionMode),
                        true)),
                preferredFocusId, MenuPreview.empty());
    }

    static MenuPageSpec optionalDevicesPage(String camera, String gamepad, boolean gps,
            List<ChoiceValue> gamepadChoices) {
        return page(MenuRoute.OPTIONAL_DEVICES, "COFFEE GB", "PERIPHERALS", "", "", List.of(),
                List.of(
                        dropdown("camera", "CAMERA", cameraLabel(camera), true),
                        dropdown("gamepad", "GAMEPAD", gamepadLabel(gamepad, gamepadChoices), true),
                        checkbox("gps", "GPS", gps, true)),
                "camera", MenuPreview.empty());
    }

    static MenuPageSpec optionPickerPage(String title, List<ChoiceValue> choices,
            String selectedToken) {
        ArrayList<MenuPageSpec.Item> items = new ArrayList<>();
        for (ChoiceValue choice : choices) {
            items.add(checkbox("choice:" + choice.token(), choice.label(),
                    choice.token().equals(selectedToken), choice.enabled()));
        }
        boolean fallback = items.isEmpty() || items.stream().noneMatch(MenuPageSpec.Item::enabled);
        if (fallback) {
            // A missing option is a safe, inert page rather than a token the Activity could
            // accidentally persist. Keep the status visible so a transient device/query
            // failure is understandable, while requiring B to leave the picker.
            items.clear();
            items.add(button("picker-status", "NOT AVAILABLE", "", true));
        }
        String preferred = items.stream().filter(MenuPageSpec.Item::enabled)
                .findFirst().map(MenuPageSpec.Item::id).orElse(items.get(0).id());
        if (selectedToken != null) {
            String selectedId = "choice:" + selectedToken;
            if (items.stream().anyMatch(item -> item.id().equals(selectedId) && item.enabled())) {
                preferred = selectedId;
            }
        }
        return page(MenuRoute.OPTION_PICKER, "COFFEE GB", title, "", "", List.of(), items,
                preferred, MenuPreview.empty(), fallback ? BACK_ONLY_HINTS : HINTS);
    }

    /**
     * Builds the host-fed Recent Games page.  The portable page owns the left preview aperture;
     * Android only supplies detached metadata and the currently focused screenshot.
     */
    static MenuPageSpec recentGamesPage(List<MenuPageSpec.RecentGame> games,
            String focusedId) {
        return MenuPageSpec.recentGames(games, focusedId);
    }

    static MenuPageSpec recentGamesPage(List<MenuPageSpec.RecentGame> games,
            String focusedId, boolean loading) {
        if (!loading) {
            return recentGamesPage(games, focusedId);
        }
        return page(MenuRoute.RECENT_GAMES, "COFFEE GB", "RECENT GAMES", "", "",
                List.of(), List.of(button("recent-games-loading", "LOADING RECENT GAMES", "",
                        true)), "recent-games-loading", MenuPreview.empty(), BACK_ONLY_HINTS);
    }

    private static String cameraLabel(String token) {
        return switch (token == null ? "off" : token.toLowerCase(java.util.Locale.US)) {
            case "rear" -> "REAR";
            case "front" -> "FRONT";
            case "unavailable" -> "UNAVAILABLE";
            default -> "OFF";
        };
    }

    private static String gamepadLabel(String token, List<ChoiceValue> choices) {
        if (token == null || token.equals("none")) {
            return "OFF";
        }
        if (token.equals("auto")) {
            return "AUTO";
        }
        for (ChoiceValue choice : choices) {
            if (choice.token().equals(token)) {
                return choice.label();
            }
        }
        return "UNAVAILABLE";
    }

    private static String systemChoiceLabel(String token) {
        if (token == null) {
            return "AUTO";
        }
        return switch (token.toLowerCase(java.util.Locale.US)) {
            case "auto" -> "AUTO";
            case "dmg" -> "DMG";
            case "cgb" -> "CGB";
            case "sgb" -> "SGB";
            case "skip" -> "SKIP";
            case "fast-forward", "fast_forward" -> "FAST-FORWARD";
            case "full" -> "FULL";
            default -> token;
        };
    }

    private static String executionModeLabel(String token) {
        return "performance".equalsIgnoreCase(token) ? "PERFORMANCE" : "ACCURACY";
    }

    static MenuPageSpec audioPage(AudioDraft draft) {
        return page(MenuRoute.AUDIO, "COFFEE GB", "AUDIO", "", "", List.of(), List.of(
                slider("volume", "VOLUME", draft.volume() + "%", true,
                                draft.volume()),
                        checkbox("mute-audio", "MUTE", draft.muted(), true)),
                "volume", MenuPreview.empty());
    }

    static MenuPageSpec touchPage(TouchDraft draft, boolean controllerAvailable) {
        ArrayList<MenuPageSpec.Item> rows = new ArrayList<>();
        rows.add(checkbox("haptics", "HAPTIC FEEDBACK", draft.haptics(), true));
        if (controllerAvailable) {
            rows.add(button("controller-mapping", "REMAP CONTROLS", "", true));
        }
        return page(MenuRoute.TOUCH_CONTROLS, "COFFEE GB", "CONTROLS", "", "",
                List.of(), rows,
                "haptics", MenuPreview.empty());
    }

    static MenuPageSpec optionalDevicesPage(DevicesDraft draft, String status,
            MenuPreview paperPreview) {
        return optionalDevicesPage(draft.camera() ? "rear" : "off", "auto", false, List.of());
    }

    static MenuPageSpec controllerPage(String controllerName, Map<Button, String> labels,
            Button captureTarget, boolean waitingForRelease) {
        boolean connected = controllerName != null;
        ArrayList<MenuPageSpec.Item> rows = new ArrayList<>();
        if (!connected) {
            // Normal navigation omits the remap affordance when disconnected.  Keep one
            // concise recovery row for a restored stack whose controller disappeared.
            rows.add(button("controller-status", "NO CONTROLLER CONNECTED", "", true));
            return page(MenuRoute.CONTROLLER_MAPPING, "COFFEE GB", "REMAP CONTROLS", "",
                    "", List.of(), rows,
                    "controller-status", MenuPreview.empty(), BACK_ONLY_HINTS);
        }
        addMapping(rows, "map-a", "A", Button.A, labels, captureTarget,
                waitingForRelease);
        addMapping(rows, "map-b", "B", Button.B, labels, captureTarget,
                waitingForRelease);
        addMapping(rows, "map-start", "START", Button.START, labels, captureTarget,
                waitingForRelease);
        addMapping(rows, "map-select", "SELECT", Button.SELECT, labels, captureTarget,
                waitingForRelease);
        addMapping(rows, "map-up", "UP", Button.UP, labels, captureTarget,
                waitingForRelease);
        addMapping(rows, "map-down", "DOWN", Button.DOWN, labels, captureTarget,
                waitingForRelease);
        addMapping(rows, "map-left", "LEFT", Button.LEFT, labels, captureTarget,
                waitingForRelease);
        addMapping(rows, "map-right", "RIGHT", Button.RIGHT, labels, captureTarget,
                waitingForRelease);
        rows.add(button("reset-controller", "RESET CONTROLLER", "", true));
        String preferred = captureTarget == null ? "map-a" : mappingId(captureTarget);
        return page(MenuRoute.CONTROLLER_MAPPING, "COFFEE GB", "REMAP CONTROLS", "",
                "", List.of(controllerName), rows, preferred, MenuPreview.empty());
    }

    static MenuPageSpec systemPage(String preferredFocusId) {
        return systemPage("AUTO", "AUTO", "SKIP", "performance", preferredFocusId);
    }

    static MenuPageSpec dataMediaPage(TransferAvailability availability) {
        String detail = availability.detail();
        if (!availability.enabled() && !availability.runtimePresent()) {
            // Back is a global B action and is removed from the immutable page model. Keep
            // unavailable direct/restored routes valid with one concise, inert status row.
            return page(MenuRoute.DATA_MEDIA, "COFFEE GB", "DATA & MEDIA", "", "CURRENT GAME",
                    List.of(detail), List.of(button("transfer-status", detail, "", true)),
                    "transfer-status", MenuPreview.empty(), BACK_ONLY_HINTS);
        }
        String transferDetail = availability.enabled() ? "NATIVE PICKER" : detail;
        boolean enabled = availability.enabled();
        return page(MenuRoute.DATA_MEDIA, "COFFEE GB", "DATA & MEDIA", "", "CURRENT GAME",
                List.of("PRIVATE SAVE DATA", "ANDROID PICKER", detail), List.of(
                        button("import-battery", "IMPORT BATTERY SAVE", transferDetail, enabled),
                        button("export-battery", "EXPORT BATTERY SAVE", transferDetail, enabled),
                        button("import-state-0", "IMPORT STATE SLOT 0", transferDetail, enabled),
                        button("export-state-0", "EXPORT STATE SLOT 0", transferDetail, enabled),
                        button("export-screenshot", "EXPORT NATIVE SCREENSHOT", transferDetail,
                                enabled),
                        button("preview-printer-paper", "PRINTER PAPER", availability.runtimePresent()
                                ? "OPEN" : "NO GAME", availability.runtimePresent()),
                        button("back", "BACK", "RETURN", true)),
                "import-battery", MenuPreview.empty());
    }

    static TransferAvailability transferAvailability(boolean runtimePresent, RuntimeState state) {
        if (!runtimePresent || state.phase() == RuntimeState.Phase.STOPPED) {
            return new TransferAvailability(false, runtimePresent, "NO GAME");
        }
        if (state.flushPending()) {
            return new TransferAvailability(false, true, "SAVE FLUSH PENDING");
        }
        if (!state.transferReady()) {
            return new TransferAvailability(false, true, "RUNTIME STARTING");
        }
        return new TransferAvailability(true, true, "READY / NATIVE PICKER");
    }

    static MenuPageSpec aboutPage(String version, String status) {
        return page(MenuRoute.ABOUT, "COFFEE GB", "ABOUT", "", "COFFEE GB",
                List.of("VERSION " + version, "MIT LICENSE", status), List.of(
                        button("privacy-notices", "PRIVACY & NOTICES", "OPEN", true),
                        button("network", "NO NETWORK ACCESS", "", false),
                        button("storage", "NO BROAD STORAGE ACCESS", "", false),
                        button("live-camera", "CAMERA ONLY WHEN ENABLED", "", false),
                        button("source-notices", "SOURCE & THIRD-PARTY NOTICES", status, true),
                        button("back", "BACK", "RETURN", true)),
                "privacy-notices", MenuPreview.empty());
    }

    static MenuPageSpec printerPaperPage(MenuPreview preview, String status) {
        String state = switch (preview.state()) {
            case LOADING -> "LOADING";
            case EMPTY -> "EMPTY";
            case READY -> "READY";
        };
        boolean ready = printerPreviewReady(preview);
        if (!ready) {
            String label = preview.state() == MenuPreview.State.LOADING
                    ? "PAPER LOADING" : "NO PAPER";
            return page(MenuRoute.PRINTER_PAPER, "COFFEE GB", "PRINTER PAPER", "",
                    "GAME BOY PRINTER", List.of(status),
                    List.of(button("paper-status", label, "", true)), "paper-status", preview,
                    BACK_ONLY_HINTS);
        }
        return page(MenuRoute.PRINTER_PAPER, "COFFEE GB", "PRINTER PAPER", "",
                "GAME BOY PRINTER", List.of("PAPER " + state, status, "EXPORT FULL RESOLUTION"),
                List.of(
                        button("clear-paper", "CLEAR PAPER", "CONFIRM", true),
                        button("export-share-paper", "EXPORT & SHARE", "NATIVE PNG", true),
                        button("back", "BACK", "RETURN", true)),
                "export-share-paper", preview);
    }

    private static void addMapping(List<MenuPageSpec.Item> rows, String id, String label,
            Button button, Map<Button, String> labels, Button captureTarget,
            boolean waitingForRelease) {
        String detail;
        if (button == captureTarget) {
            detail = waitingForRelease ? "RELEASE INPUT" : "WAITING FOR INPUT";
        } else {
            detail = labels.getOrDefault(button, "UNMAPPED");
        }
        rows.add(button(id, label, detail, true));
    }

    private static String mappingId(Button button) {
        return "map-" + button.name().toLowerCase(java.util.Locale.US);
    }

    private static MenuPageSpec page(MenuRoute route, String title, String context,
            String headerAction, String sideHeading, List<String> sideLines,
            List<MenuPageSpec.Item> items, String preferredFocusId, MenuPreview preview) {
        return page(route, title, context, headerAction, sideHeading, sideLines, items,
                preferredFocusId, preview, HINTS);
    }

    private static MenuPageSpec page(MenuRoute route, String title, String context,
            String headerAction, String sideHeading, List<String> sideLines,
            List<MenuPageSpec.Item> items, String preferredFocusId, MenuPreview preview,
            List<String> footerHints) {
        return new MenuPageSpec(route, title, context, headerAction, sideHeading, sideLines,
                items, 1, footerHints, preferredFocusId, preview);
    }

    private static MenuPageSpec.Item button(String id, String label, String detail,
            boolean enabled) {
        return MenuPageSpec.Item.button(id, label, detail, enabled);
    }

    private static MenuPageSpec.Item dropdown(String id, String label, String detail,
            boolean enabled) {
        return MenuPageSpec.Item.dropdown(id, label, detail, enabled);
    }

    private static MenuPageSpec.Item checkbox(String id, String label, boolean checked,
            boolean enabled) {
        return MenuPageSpec.Item.checkbox(id, label, checked, enabled);
    }

    private static MenuPageSpec.Item slider(String id, String label, String detail,
            boolean enabled, int progress) {
        return MenuPageSpec.Item.slider(id, label, detail, enabled, progress);
    }

    record AudioDraft(int volume, boolean muted) {
        AudioDraft {
            volume = Math.max(0, Math.min(100, volume));
            volume = Math.round(volume / 5.0f) * 5;
        }

        AudioDraft toggleMuted() {
            return new AudioDraft(volume, !muted);
        }
    }

    record TouchDraft(float opacity, float scale, float verticalPosition, boolean leftHanded,
                      boolean haptics) {
        TouchDraft toggleHaptics() {
            return new TouchDraft(opacity, scale, verticalPosition, leftHanded, !haptics);
        }

        TouchControlsLayout layout() {
            return new TouchControlsLayout(opacity, scale, verticalPosition, leftHanded, haptics);
        }
    }

    record DevicesDraft(boolean rumble, boolean camera, boolean printer) {
        DevicesDraft toggleRumble() {
            return new DevicesDraft(!rumble, camera, printer);
        }

        DevicesDraft toggleCamera() {
            return new DevicesDraft(rumble, !camera, printer);
        }

        DevicesDraft togglePrinter() {
            return new DevicesDraft(rumble, camera, !printer);
        }
    }

    record DevicesCommit(boolean rumble, boolean printer, boolean persistedCamera,
                         boolean cameraEnabled, boolean requestCameraPermission) {
    }

    record ChoiceValue(String token, String label, boolean enabled) {
        ChoiceValue(String token, String label) {
            this(token, label, true);
        }

        ChoiceValue {
            if (token == null || token.isBlank() || label == null || label.isBlank()) {
                throw new IllegalArgumentException("Choice token and label are required");
            }
        }
    }

    record TransferAvailability(boolean enabled, boolean runtimePresent, String detail) {
    }
}
