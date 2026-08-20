package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.ui.menu.MenuController;
import eu.rekawek.coffeegb.ui.menu.MenuPageSpec;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import eu.rekawek.coffeegb.ui.menu.MenuPreview;
import eu.rekawek.coffeegb.core.joypad.Button;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AndroidMenuModelTest {

    @Test
    public void settingsExposesOnlyInlineEditableRoutes() {
        assertEquals(List.of("system", "display", "audio", "peripherals", "execution-mode"),
                AndroidMenuModel.settingsPage().items().stream()
                        .map(MenuPageSpec.Item::id).toList());
        assertEquals("system", AndroidMenuModel.settingsPage().preferredFocusId());
    }

    @Test
    public void libraryUsesThreePauseEquivalentRowsWithoutLegacyPickerCopy() {
        MenuPageSpec page = AndroidMenuModel.libraryPage(true);

        assertIds(page, "recent-games", "open-rom", "settings");
        assertEquals("", page.headerAction());
        assertEquals("", page.sideHeading());
        assertTrue(page.sideLines().isEmpty());
        assertEquals("recent-games", page.preferredFocusId());

        MenuPageSpec unavailable = AndroidMenuModel.libraryPage(false);
        assertFalse(unavailable.items().get(0).enabled());
        assertFalse(unavailable.items().get(1).enabled());
        assertTrue(unavailable.items().get(2).enabled());
        assertEquals("settings", unavailable.preferredFocusId());
    }

    @Test
    public void sharedFooterAdvertisesTheThreeButtonMenuContract() {
        assertEquals(List.of("D-PAD MOVE", "A CHOOSE", "B BACK"),
                AndroidMenuModel.settingsPage().footerHints());
        assertFalse(AndroidMenuModel.settingsPage().footerHints().stream()
                .anyMatch(hint -> hint.contains("SELECT") || hint.contains("START")));
    }

    @Test
    public void everyPr3RouteUsesTheStableApprovedIds() {
        assertIds(AndroidMenuModel.audioPage(AndroidMenuModel.audioDraft(100, false)),
                "volume", "mute-audio");
        assertIds(AndroidMenuModel.touchPage(AndroidMenuModel.resetTouchDraft(), true),
                "haptics", "controller-mapping");
        assertIds(AndroidMenuModel.touchPage(AndroidMenuModel.resetTouchDraft(), false),
                "haptics");
        assertIds(AndroidMenuModel.controllerPage("PAD", Map.of(), null, false),
                "map-a", "map-b", "map-start", "map-select", "map-up", "map-down",
                "map-left", "map-right", "reset-controller");
        assertIds(AndroidMenuModel.optionalDevicesPage(
                        new AndroidMenuModel.DevicesDraft(false, false, false), "READY",
                        MenuPreview.empty()),
                "camera", "gamepad", "gps");
        assertIds(AndroidMenuModel.printerPaperPage(MenuPreview.empty(), "READY"),
                "paper-status");
        assertIds(AndroidMenuModel.systemPage("dmg-games"),
                "dmg-games", "cgb-games", "bootstrap");
        assertIds(AndroidMenuModel.dataMediaPage(new AndroidMenuModel.TransferAvailability(
                        true, true, "READY / NATIVE PICKER")),
                "import-battery", "export-battery", "import-state-0", "export-state-0",
                "export-screenshot", "preview-printer-paper", "back");
        assertIds(AndroidMenuModel.aboutPage("1.2.3", "OPEN"),
                "privacy-notices", "network", "storage", "live-camera",
                "source-notices", "back");
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
        assertFalse(page.items().stream().filter(item -> item.id().equals("mute-audio"))
                .findFirst().orElseThrow().adjustable());
        assertFalse(page.items().stream().anyMatch(item -> item.id().contains("save")
                || item.id().contains("cancel") || item.id().contains("emulated")));
    }

    @Test
    public void unavailableDataAndPaperPagesCanBeInstalledWithoutAnInvisibleBackRow() {
        MenuPageSpec data = AndroidMenuModel.dataMediaPage(
                new AndroidMenuModel.TransferAvailability(false, false, "NO GAME"));
        MenuPageSpec paper = AndroidMenuModel.printerPaperPage(MenuPreview.empty(), "NO GAME");

        assertEquals(List.of("transfer-status"), data.items().stream()
                .map(MenuPageSpec.Item::id).toList());
        assertEquals(List.of("paper-status"), paper.items().stream()
                .map(MenuPageSpec.Item::id).toList());
        assertEquals(List.of("", "", "B BACK"), data.footerHints());
        assertEquals(List.of("", "", "B BACK"), paper.footerHints());

        MenuController controller = new MenuController(new MenuController.Listener() {
            @Override
            public void onPresentation(eu.rekawek.coffeegb.ui.menu.MenuPresentation presentation) {
            }

            @Override
            public void onItemSelected(MenuRoute route, String id, boolean secondary) {
            }

            @Override
            public void onHeaderSelected(MenuRoute route) {
            }
        });
        controller.setPages(List.of(data, paper));
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
        MenuPageSpec controller = AndroidMenuModel.controllerPage(null, Map.of(), null, false);
        assertEquals(List.of("controller-status"), controller.items().stream()
                .map(MenuPageSpec.Item::id).toList());
        assertEquals("NO CONTROLLER CONNECTED", controller.items().get(0).label());
        assertEquals("", controller.items().get(0).detail());
        assertEquals("", controller.sideHeading());
        assertTrue(controller.sideLines().isEmpty());
        assertFalse(controller.items().stream().anyMatch(item -> item.id().equals("back")));

        MenuPageSpec mapped = AndroidMenuModel.controllerPage("PAD",
                Map.of(Button.A, "BUTTON A"), Button.A, false);
        assertEquals("WAITING FOR INPUT", mapped.items().get(0).detail());
        assertEquals("", mapped.sideHeading());

        assertEquals("", AndroidMenuModel.audioPage(
                AndroidMenuModel.audioDraft(100, false)).sideHeading());
        assertEquals("", AndroidMenuModel.touchPage(
                AndroidMenuModel.resetTouchDraft(), true).sideHeading());

        MenuPageSpec system = AndroidMenuModel.systemPage("bootstrap");
        assertEquals("bootstrap", system.preferredFocusId());
        assertEquals(List.of("dmg-games", "cgb-games", "bootstrap"),
                system.items().stream().map(MenuPageSpec.Item::id).toList());

        MenuPageSpec about = AndroidMenuModel.aboutPage("1.2.3", "OPEN");
        assertTrue(about.sideLines().contains("MIT LICENSE"));
        assertFalse(about.sideLines().stream().anyMatch(line -> line.contains("GPL")));

        for (MenuPreview preview : List.of(MenuPreview.loading(), MenuPreview.empty())) {
            MenuPageSpec printer = AndroidMenuModel.printerPaperPage(preview, "READY");
            assertEquals(List.of("paper-status"), printer.items().stream()
                    .map(MenuPageSpec.Item::id).toList());
            assertTrue(printer.items().get(0).enabled());
            assertEquals("paper-status", printer.preferredFocusId());
        }
    }

    @Test
    public void printerPreviewReadyIsTheOnlyStateThatEnablesPaperActions() {
        assertFalse(AndroidMenuModel.printerPreviewReady(MenuPreview.loading()));
        assertFalse(AndroidMenuModel.printerPreviewReady(MenuPreview.empty()));
        assertTrue(AndroidMenuModel.printerPreviewReady(
                MenuPreview.ready(1, 1, new int[]{0xffffffff})));
    }

    @Test
    public void expandedSettingsPagesExposeStableRowsAndDetails() {
        assertEquals(List.of("sgb-border", "dmg-colors"),
                AndroidMenuModel.displayPage(true, false).items().stream()
                        .map(MenuPageSpec.Item::id).toList());
        assertEquals("ON", AndroidMenuModel.displayPage(true, false).items().get(0).detail());
        assertEquals("GREEN", AndroidMenuModel.displayPage(true, false).items().get(1).detail());
        assertEquals(List.of("dmg-games", "cgb-games", "bootstrap"),
                AndroidMenuModel.systemPage("DMG", "CGB", "FULL", "dmg-games")
                        .items().stream().map(MenuPageSpec.Item::id).toList());
        assertEquals("DMG", AndroidMenuModel.systemPage("DMG", "CGB", "FULL", "dmg-games")
                .items().get(0).detail());
    }

    @Test
    public void recentGamesPageUsesSelectedNameTimestampAndDetachedPreview() {
        MenuPreview preview = MenuPreview.ready(2, 1, new int[]{0xff102030, 0xff405060});
        MenuPageSpec page = AndroidMenuModel.recentGamesPage(List.of(
                new MenuPageSpec.RecentGame("recent:7", "TETRIS.GB", "TODAY",
                        true, preview)), "recent:7");

        assertEquals(MenuRoute.RECENT_GAMES, page.route());
        assertEquals(List.of("recent:7"), page.items().stream()
                .map(MenuPageSpec.Item::id).toList());
        assertEquals("TETRIS.GB", page.items().get(0).label());
        assertEquals(List.of("LAST PLAYED: TODAY"), page.sideLines());
        assertEquals(preview, page.preview());
        assertEquals("recent:7", page.preferredFocusId());
    }

    @Test
    public void emptyRecentGamesPageIsSafeAndBackOnly() {
        MenuPageSpec page = AndroidMenuModel.recentGamesPage(List.of(), null);
        assertEquals(List.of("recent-games-status"), page.items().stream()
                .map(MenuPageSpec.Item::id).toList());
        assertEquals("NO RECENT GAMES", page.items().get(0).label());
        assertEquals(List.of("", "", "B BACK"), page.footerHints());
    }

    @Test
    public void loadingRecentGamesPageIsExplicitAndInert() {
        MenuPageSpec page = AndroidMenuModel.recentGamesPage(List.of(), null, true);
        assertEquals(List.of("recent-games-loading"), page.items().stream()
                .map(MenuPageSpec.Item::id).toList());
        assertEquals("LOADING RECENT GAMES", page.items().get(0).label());
        assertEquals(List.of("", "", "B BACK"), page.footerHints());
    }

    private static void assertIds(MenuPageSpec page, String... expected) {
        assertEquals(List.of(expected), page.items().stream().map(MenuPageSpec.Item::id).toList());
    }
}
