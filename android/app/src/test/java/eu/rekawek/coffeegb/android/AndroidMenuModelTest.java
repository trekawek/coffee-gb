package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.android.menu.MenuPageSpec;
import eu.rekawek.coffeegb.android.menu.MenuPreview;
import eu.rekawek.coffeegb.core.joypad.Button;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AndroidMenuModelTest {

    @Test
    public void settingsExposesEveryApprovedRouteId() {
        assertEquals(List.of("audio", "touch-controls", "controller-mapping",
                        "optional-devices", "video", "system-profile", "rewind-save",
                        "data-media", "about"),
                AndroidMenuModel.settingsPage().items().stream()
                        .map(MenuPageSpec.Item::id).toList());
    }

    @Test
    public void everyPr3RouteUsesTheStableApprovedIds() {
        assertIds(AndroidMenuModel.audioPage(AndroidMenuModel.audioDraft(100, false)),
                "volume", "mute-audio", "emulated-audio", "save-audio", "cancel-audio");
        assertIds(AndroidMenuModel.touchPage(AndroidMenuModel.resetTouchDraft()),
                "haptics", "button-opacity", "reset-touch", "save-touch", "cancel-touch");
        assertIds(AndroidMenuModel.controllerPage("PAD", Map.of(), null,
                        false, false, false),
                "map-a", "map-b", "map-start", "map-select", "map-up", "map-down",
                "map-left", "map-right", "invert-x", "invert-y", "reset-controller", "back");
        assertIds(AndroidMenuModel.optionalDevicesPage(
                        new AndroidMenuModel.DevicesDraft(false, false, false), "READY",
                        MenuPreview.empty()),
                "rumble", "live-camera", "game-boy-printer", "calibrate-tilt",
                "preview-printer-paper", "export-share-paper", "save-devices",
                "cancel-devices");
        assertIds(AndroidMenuModel.printerPaperPage(MenuPreview.empty(), "READY"),
                "clear-paper", "export-share-paper", "back");
        assertIds(AndroidMenuModel.systemPage("video-status"),
                "video-status", "profile-status", "rewind-save-status", "back");
        assertIds(AndroidMenuModel.dataMediaPage(new AndroidMenuModel.TransferAvailability(
                        true, true, "READY / NATIVE PICKER")),
                "import-battery", "export-battery", "import-state-0", "export-state-0",
                "export-screenshot", "preview-printer-paper", "back");
        assertIds(AndroidMenuModel.aboutPage("1.2.3", "OPEN"),
                "version", "license", "network", "storage", "live-camera", "source",
                "third-party", "source-notices", "back");
    }

    @Test
    public void audioAdjustmentsClampToFivePercentEdgesAndDraftTogglesStayLocal() {
        AndroidMenuModel.AudioDraft draft = AndroidMenuModel.audioDraft(98, false);
        assertEquals(100, draft.volume());
        assertEquals(100, AndroidMenuModel.adjustVolume(draft, 1).volume());
        for (int index = 0; index < 30; index++) {
            draft = AndroidMenuModel.adjustVolume(draft, -1);
        }
        assertEquals(0, draft.volume());
        assertTrue(draft.toggleMuted().muted());

        MenuPageSpec page = AndroidMenuModel.audioPage(draft);
        assertTrue(page.items().get(0).adjustable());
        assertEquals(0, page.items().get(0).progress());
        assertTrue(page.items().stream().filter(item -> item.id().equals("mute-audio"))
                .findFirst().orElseThrow().adjustable());
        assertFalse(page.items().stream().filter(item -> item.id().equals("emulated-audio"))
                .findFirst().orElseThrow().enabled());
    }

    @Test
    public void touchResetUsesCanonicalHapticsOnAndDeviceDraftsToggleIndependently() {
        AndroidMenuModel.TouchDraft touch = AndroidMenuModel.resetTouchDraft();
        assertTrue(touch.haptics());
        assertFalse(touch.leftHanded());
        assertTrue(touch.layout().haptics());

        AndroidMenuModel.DevicesDraft devices =
                new AndroidMenuModel.DevicesDraft(false, false, false)
                        .toggleRumble().toggleCamera();
        assertTrue(devices.rumble());
        assertTrue(devices.camera());
        assertFalse(devices.printer());

        AndroidMenuModel.DevicesCommit pending = AndroidMenuModel.commitDevices(devices, false);
        assertTrue(pending.rumble());
        assertFalse(pending.persistedCamera());
        assertFalse(pending.cameraEnabled());
        assertTrue(pending.requestCameraPermission());
        AndroidMenuModel.DevicesCommit granted = AndroidMenuModel.commitDevices(devices, true);
        assertTrue(granted.persistedCamera());
        assertTrue(granted.cameraEnabled());
        assertFalse(granted.requestCameraPermission());
    }

    @Test
    public void transferAvailabilityReportsTruthfulDisabledReasons() {
        assertEquals("NO GAME", AndroidMenuModel.transferAvailability(
                false, RuntimeState.stopped()).detail());
        RuntimeState starting = new RuntimeState(RuntimeState.Phase.OPENING, "OPENING", List.of(),
                false, false, false, 1);
        assertEquals("RUNTIME STARTING",
                AndroidMenuModel.transferAvailability(true, starting).detail());
        RuntimeState flushing = new RuntimeState(RuntimeState.Phase.PAUSED, "FLUSH", List.of(),
                true, true, true, 2);
        assertEquals("SAVE FLUSH PENDING",
                AndroidMenuModel.transferAvailability(true, flushing).detail());
        RuntimeState ready = new RuntimeState(RuntimeState.Phase.PAUSED, "READY", List.of(),
                true, true, false, 3);
        assertTrue(AndroidMenuModel.transferAvailability(true, ready).enabled());
    }

    @Test
    public void controllerSystemAboutAndPrinterPagesStayTruthful() {
        MenuPageSpec controller = AndroidMenuModel.controllerPage(null, Map.of(), null,
                false, false, false);
        assertFalse(controller.items().stream().filter(item -> item.id().equals("map-a"))
                .findFirst().orElseThrow().enabled());
        assertTrue(controller.items().stream().filter(item -> item.id().equals("back"))
                .findFirst().orElseThrow().enabled());

        MenuPageSpec mapped = AndroidMenuModel.controllerPage("PAD",
                Map.of(Button.A, "BUTTON A"), Button.A, false, false, true);
        assertEquals("WAITING FOR TARGET INPUT", mapped.items().get(0).detail());

        MenuPageSpec system = AndroidMenuModel.systemPage("profile-status");
        assertEquals("profile-status", system.preferredFocusId());
        assertTrue(system.items().stream().anyMatch(item -> item.detail()
                .equals("NEAREST-NEIGHBOUR / ASPECT FIT")));

        MenuPageSpec about = AndroidMenuModel.aboutPage("1.2.3", "OPEN");
        assertTrue(about.sideLines().contains("MIT LICENSE"));
        assertFalse(about.sideLines().stream().anyMatch(line -> line.contains("GPL")));

        MenuPageSpec printer = AndroidMenuModel.printerPaperPage(MenuPreview.empty(), "READY");
        assertFalse(printer.items().get(0).enabled());
        assertEquals("back", printer.preferredFocusId());
    }

    @Test
    public void optionalPrinterExportIsEnabledOnlyForReadyPaper() {
        AndroidMenuModel.DevicesDraft draft =
                new AndroidMenuModel.DevicesDraft(false, false, true);
        for (MenuPreview preview : List.of(MenuPreview.loading(), MenuPreview.empty())) {
            MenuPageSpec page = AndroidMenuModel.optionalDevicesPage(draft, "READY", preview);
            assertFalse(page.items().stream()
                    .filter(item -> item.id().equals("export-share-paper"))
                    .findFirst().orElseThrow().enabled());
        }
        MenuPageSpec ready = AndroidMenuModel.optionalDevicesPage(draft, "READY",
                MenuPreview.ready(1, 1, new int[]{0xffffffff}));
        assertTrue(ready.items().stream()
                .filter(item -> item.id().equals("export-share-paper"))
                .findFirst().orElseThrow().enabled());
    }

    private static void assertIds(MenuPageSpec page, String... expected) {
        assertEquals(List.of(expected), page.items().stream().map(MenuPageSpec.Item::id).toList());
    }
}
