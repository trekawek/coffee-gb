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

    private static final List<String> HINTS = List.of("D-PAD MOVE", "[A] OK", "[B] BACK");

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

    static MenuPageSpec settingsPage() {
        return page(MenuRoute.SETTINGS, "COFFEE GB", "SETTINGS", "", "CONFIGURATION",
                List.of("AUDIO + INPUT", "DEVICES + DATA", "SYSTEM + ABOUT"), List.of(
                        item("audio", "AUDIO", "VOLUME / MUTE", true),
                        item("touch-controls", "TOUCH CONTROLS", "HAPTICS", true),
                        item("controller-mapping", "CONTROLLER MAPPING", "BUTTONS / AXES", true),
                        item("optional-devices", "OPTIONAL DEVICES", "RUMBLE / CAMERA / PRINTER", true),
                        item("video", "VIDEO", "SYSTEM STATUS", true),
                        item("system-profile", "SYSTEM PROFILE", "SYSTEM STATUS", true),
                        item("rewind-save", "REWIND & SAVE", "SYSTEM STATUS", true),
                        item("data-media", "DATA & MEDIA", "NATIVE PICKERS", true),
                        item("about", "ABOUT", "PRIVACY / NOTICES", true)), null,
                MenuPreview.empty());
    }

    static MenuPageSpec audioPage(AudioDraft draft) {
        return page(MenuRoute.AUDIO, "COFFEE GB", "AUDIO", "", "AUDIO MIX",
                List.of("NO LIVE PREVIEW", "CHANGES APPLY ON SAVE", "EMULATED AUDIO  ON"),
                List.of(
                        adjustable("volume", "VOLUME", draft.volume() + "%", true,
                                draft.volume()),
                        adjustable("mute-audio", "MUTE", onOff(draft.muted()), true, -1),
                        item("emulated-audio", "EMULATED AUDIO", "ON", false),
                        item("save-audio", "SAVE", "COMMIT", true),
                        item("cancel-audio", "CANCEL", "DISCARD", true)),
                "volume", MenuPreview.empty());
    }

    static MenuPageSpec touchPage(TouchDraft draft) {
        return page(MenuRoute.TOUCH_CONTROLS, "COFFEE GB", "TOUCH CONTROLS", "", "INPUT DECK",
                List.of("POSITIONS FIXED", "BAKED INTO SKIN", "SAVE COMMITS HAPTICS"), List.of(
                        item("haptics", "HAPTIC FEEDBACK", onOff(draft.haptics()), true),
                        item("button-opacity", "OPACITY", "FIXED / BAKED INTO SKIN", false),
                        item("reset-touch", "RESET", "HAPTICS ON", true),
                        item("save-touch", "SAVE", "COMMIT", true),
                        item("cancel-touch", "CANCEL", "DISCARD", true)),
                "haptics", MenuPreview.empty());
    }

    static MenuPageSpec optionalDevicesPage(DevicesDraft draft, String status,
            MenuPreview paperPreview) {
        boolean paperReady = paperPreview.state() == MenuPreview.State.READY;
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
            Button captureTarget, boolean waitingForRelease, boolean invertX, boolean invertY) {
        boolean connected = controllerName != null;
        ArrayList<MenuPageSpec.Item> rows = new ArrayList<>();
        addMapping(rows, "map-a", "A", Button.A, connected, labels, captureTarget,
                waitingForRelease);
        addMapping(rows, "map-b", "B", Button.B, connected, labels, captureTarget,
                waitingForRelease);
        addMapping(rows, "map-start", "START", Button.START, connected, labels, captureTarget,
                waitingForRelease);
        addMapping(rows, "map-select", "SELECT", Button.SELECT, connected, labels, captureTarget,
                waitingForRelease);
        addMapping(rows, "map-up", "UP", Button.UP, connected, labels, captureTarget,
                waitingForRelease);
        addMapping(rows, "map-down", "DOWN", Button.DOWN, connected, labels, captureTarget,
                waitingForRelease);
        addMapping(rows, "map-left", "LEFT", Button.LEFT, connected, labels, captureTarget,
                waitingForRelease);
        addMapping(rows, "map-right", "RIGHT", Button.RIGHT, connected, labels, captureTarget,
                waitingForRelease);
        rows.add(item("invert-x", "INVERT X", connected ? onOff(invertX) : "NO CONTROLLER",
                connected));
        rows.add(item("invert-y", "INVERT Y", connected ? onOff(invertY) : "NO CONTROLLER",
                connected));
        rows.add(item("reset-controller", "RESET CONTROLLER", connected ? "IMMEDIATE"
                : "NO CONTROLLER", connected));
        rows.add(item("back", "BACK", "RETURN", true));
        String preferred = captureTarget == null ? "map-a" : mappingId(captureTarget);
        return page(MenuRoute.CONTROLLER_MAPPING, "COFFEE GB", "CONTROLLER MAPPING", "",
                "INPUT MAP", List.of(connected ? controllerName : "NO CONTROLLER",
                        captureTarget == null ? "A MAPS BUTTON" : "WAITING FOR TARGET INPUT",
                        "ESCAPE / BACK CANCELS CAPTURE"), rows, preferred, MenuPreview.empty());
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
        return page(MenuRoute.ABOUT, "COFFEE GB", "ABOUT", "", "COFFEE GB ANDROID",
                List.of("VERSION " + version, "MIT LICENSE", "OPEN SOURCE"), List.of(
                        item("version", "VERSION", version, false),
                        item("license", "LICENSE", "MIT LICENSE", false),
                        item("network", "NETWORK ACCESS", "NONE", false),
                        item("storage", "STORAGE", "SAF / NO BROAD ACCESS", false),
                        item("live-camera", "CAMERA", "OPT-IN ONLY", false),
                        item("source", "SOURCE", "GITHUB.COM/TREKAWEK/COFFEE-GB", false),
                        item("third-party", "THIRD-PARTY DEPENDENCIES", "OPEN SOURCE", false),
                        item("source-notices", "SOURCE & NOTICES", status, true),
                        item("back", "BACK", "RETURN", true)),
                "source-notices", MenuPreview.empty());
    }

    static MenuPageSpec printerPaperPage(MenuPreview preview, String status) {
        String state = switch (preview.state()) {
            case LOADING -> "LOADING";
            case EMPTY -> "EMPTY";
            case READY -> "READY";
        };
        boolean ready = preview.state() == MenuPreview.State.READY;
        return page(MenuRoute.PRINTER_PAPER, "COFFEE GB", "PRINTER PAPER", "",
                "GAME BOY PRINTER", List.of("PAPER " + state, status, "EXPORT FULL RESOLUTION"),
                List.of(
                        item("clear-paper", "CLEAR PAPER", ready ? "CONFIRM" : "EMPTY", ready),
                        item("export-share-paper", "EXPORT & SHARE", ready ? "NATIVE PNG" : "EMPTY", ready),
                        item("back", "BACK", "RETURN", true)),
                ready ? "export-share-paper" : "back", preview);
    }

    private static void addMapping(List<MenuPageSpec.Item> rows, String id, String label,
            Button button, boolean connected, Map<Button, String> labels, Button captureTarget,
            boolean waitingForRelease) {
        String detail;
        if (!connected) {
            detail = "NO CONTROLLER";
        } else if (button == captureTarget) {
            detail = waitingForRelease ? "RELEASE TARGET INPUT" : "WAITING FOR TARGET INPUT";
        } else {
            detail = labels.getOrDefault(button, "UNMAPPED");
        }
        rows.add(item(id, label, detail, connected));
    }

    private static String mappingId(Button button) {
        return "map-" + button.name().toLowerCase(java.util.Locale.US);
    }

    private static MenuPageSpec page(MenuRoute route, String title, String context,
            String headerAction, String sideHeading, List<String> sideLines,
            List<MenuPageSpec.Item> items, String preferredFocusId, MenuPreview preview) {
        return new MenuPageSpec(route, title, context, headerAction, sideHeading, sideLines,
                items, 1, HINTS, preferredFocusId, preview);
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
