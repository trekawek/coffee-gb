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

    static MenuPageSpec settingsPage(boolean controllerAvailable) {
        // Keep this list deliberately short.  A settings row is only useful when its
        // value can be changed in this overlay; platform pickers, read-only runtime
        // status and window controls do not belong here.
        return page(MenuRoute.SETTINGS, "COFFEE GB", "SETTINGS", "", "", List.of(), List.of(
                        item("audio", "AUDIO", "VOLUME / MUTE", true),
                        item("touch-controls", "CONTROLS",
                                controllerAvailable ? "HAPTICS / REMAP" : "HAPTICS", true)),
                "audio",
                MenuPreview.empty());
    }

    static MenuPageSpec audioPage(AudioDraft draft) {
        return page(MenuRoute.AUDIO, "COFFEE GB", "AUDIO", "", "", List.of(), List.of(
                adjustable("volume", "VOLUME", draft.volume() + "%", true,
                                draft.volume()),
                        item("mute-audio", "MUTE", onOff(draft.muted()), true)),
                "volume", MenuPreview.empty());
    }

    static MenuPageSpec touchPage(TouchDraft draft, boolean controllerAvailable) {
        ArrayList<MenuPageSpec.Item> rows = new ArrayList<>();
        rows.add(item("haptics", "HAPTIC FEEDBACK", onOff(draft.haptics()), true));
        if (controllerAvailable) {
            rows.add(item("controller-mapping", "REMAP CONTROLS", "", true));
        }
        return page(MenuRoute.TOUCH_CONTROLS, "COFFEE GB", "CONTROLS", "", "",
                List.of(), rows,
                "haptics", MenuPreview.empty());
    }

    static MenuPageSpec optionalDevicesPage(DevicesDraft draft, String status,
            MenuPreview paperPreview) {
        boolean paperReady = printerPreviewReady(paperPreview);
        String paperState = switch (paperPreview.state()) {
            case LOADING -> "LOADING";
            case EMPTY -> "EMPTY";
            case READY -> "READY";
        };
        return page(MenuRoute.OPTIONAL_DEVICES, "COFFEE GB", "OPTIONAL DEVICES", "",
                "PERIPHERALS", List.of("CARTRIDGE DEPENDENT", status, "CAMERA PERMISSION NATIVE"),
                List.of(
                        item("rumble", "RUMBLE", onOff(draft.rumble()), true),
                        item("live-camera", "LIVE CAMERA", onOff(draft.camera()), true),
                        item("game-boy-printer", "GAME BOY PRINTER", onOff(draft.printer()), true),
                        item("calibrate-tilt", "CALIBRATE TILT", "IMMEDIATE", true),
                        item("preview-printer-paper", "PREVIEW PRINTER PAPER", paperState, true),
                        item("export-share-paper", "EXPORT & SHARE PAPER",
                                paperReady ? "NATIVE PNG" : paperState, paperReady),
                        item("save-devices", "SAVE", "COMMIT", true),
                        item("cancel-devices", "CANCEL", "DISCARD", true)),
                "rumble", MenuPreview.empty());
    }

    static MenuPageSpec controllerPage(String controllerName, Map<Button, String> labels,
            Button captureTarget, boolean waitingForRelease) {
        boolean connected = controllerName != null;
        ArrayList<MenuPageSpec.Item> rows = new ArrayList<>();
        if (!connected) {
            // Normal navigation omits the remap affordance when disconnected.  Keep one
            // concise recovery row for a restored stack whose controller disappeared.
            rows.add(item("controller-status", "NO CONTROLLER CONNECTED", "", true));
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
        rows.add(item("reset-controller", "RESET CONTROLLER", "", true));
        String preferred = captureTarget == null ? "map-a" : mappingId(captureTarget);
        return page(MenuRoute.CONTROLLER_MAPPING, "COFFEE GB", "REMAP CONTROLS", "",
                "", List.of(controllerName), rows, preferred, MenuPreview.empty());
    }

    static MenuPageSpec systemPage(String preferredFocusId) {
        return page(MenuRoute.SYSTEM, "COFFEE GB", "SYSTEM", "", "RUNTIME",
                List.of("COFFEE GB ANDROID", "FIXED THIS SESSION", "READ-ONLY STATUS"), List.of(
                        item("video-status", "VIDEO STATUS", "NEAREST-NEIGHBOUR / ASPECT FIT", true),
                        item("profile-status", "SYSTEM PROFILE", "SELECTED ON ROM OPEN / FIXED THIS SESSION", true),
                        item("rewind-save-status", "REWIND & SAVE", "PORTABLE DEFAULTS / LIVE CHANGES UNAVAILABLE", true),
                        item("back", "BACK", "RETURN", true)),
                preferredFocusId, MenuPreview.empty());
    }

    static MenuPageSpec dataMediaPage(TransferAvailability availability) {
        String detail = availability.detail();
        if (!availability.enabled() && !availability.runtimePresent()) {
            // Back is a global B action and is removed from the immutable page model. Keep
            // unavailable direct/restored routes valid with one concise, inert status row.
            return page(MenuRoute.DATA_MEDIA, "COFFEE GB", "DATA & MEDIA", "", "CURRENT GAME",
                    List.of(detail), List.of(item("transfer-status", detail, "", true)),
                    "transfer-status", MenuPreview.empty(), BACK_ONLY_HINTS);
        }
        String transferDetail = availability.enabled() ? "NATIVE PICKER" : detail;
        boolean enabled = availability.enabled();
        return page(MenuRoute.DATA_MEDIA, "COFFEE GB", "DATA & MEDIA", "", "CURRENT GAME",
                List.of("PRIVATE SAVE DATA", "ANDROID PICKER", detail), List.of(
                        item("import-battery", "IMPORT BATTERY SAVE", transferDetail, enabled),
                        item("export-battery", "EXPORT BATTERY SAVE", transferDetail, enabled),
                        item("import-state-0", "IMPORT STATE SLOT 0", transferDetail, enabled),
                        item("export-state-0", "EXPORT STATE SLOT 0", transferDetail, enabled),
                        item("export-screenshot", "EXPORT NATIVE SCREENSHOT", transferDetail, enabled),
                        item("preview-printer-paper", "PRINTER PAPER", availability.runtimePresent()
                                ? "OPEN" : "NO GAME", availability.runtimePresent()),
                        item("back", "BACK", "RETURN", true)),
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
                        item("privacy-notices", "PRIVACY & NOTICES", "OPEN", true),
                        item("network", "NO NETWORK ACCESS", "", false),
                        item("storage", "NO BROAD STORAGE ACCESS", "", false),
                        item("live-camera", "CAMERA ONLY WHEN ENABLED", "", false),
                        item("source-notices", "SOURCE & THIRD-PARTY NOTICES", status, true),
                        item("back", "BACK", "RETURN", true)),
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
                    List.of(item("paper-status", label, "", true)), "paper-status", preview,
                    BACK_ONLY_HINTS);
        }
        return page(MenuRoute.PRINTER_PAPER, "COFFEE GB", "PRINTER PAPER", "",
                "GAME BOY PRINTER", List.of("PAPER " + state, status, "EXPORT FULL RESOLUTION"),
                List.of(
                        item("clear-paper", "CLEAR PAPER", "CONFIRM", true),
                        item("export-share-paper", "EXPORT & SHARE", "NATIVE PNG", true),
                        item("back", "BACK", "RETURN", true)),
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
        rows.add(item(id, label, detail, true));
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

    private static MenuPageSpec.Item item(String id, String label, String detail,
            boolean enabled) {
        return new MenuPageSpec.Item(id, label, detail, enabled);
    }

    private static MenuPageSpec.Item adjustable(String id, String label, String detail,
            boolean enabled, int progress) {
        return new MenuPageSpec.Item(id, label, detail, enabled, null, true, progress);
    }

    private static String onOff(boolean enabled) {
        return enabled ? "ON" : "OFF";
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

    record TransferAvailability(boolean enabled, boolean runtimePresent, String detail) {
    }
}
