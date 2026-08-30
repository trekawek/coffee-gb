package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.ui.menu.MenuPageSpec;
import android.view.InputDevice;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure Android-side regression coverage for the expanded in-screen settings graph. */
public class SettingsSelectionTest {

    @Test
    public void settingsGraphIncludesTheCoreExecutionModeSelector() {
        assertEquals(List.of("system", "display", "audio", "peripherals", "execution-mode"),
                AndroidMenuModel.settingsPage("performance").items().stream()
                        .map(MenuPageSpec.Item::id).toList());
        assertEquals("PERFORMANCE", AndroidMenuModel.settingsPage("performance").items().get(4)
                .detail());
        assertEquals("PERFORMANCE", AndroidMenuModel.settingsPage().items().get(4).detail());
        assertEquals(List.of("dmg-games", "cgb-games", "bootstrap"),
                AndroidMenuModel.systemPage("AUTO", "AUTO", "SKIP", "dmg-games")
                        .items().stream().map(MenuPageSpec.Item::id).toList());
        assertEquals(List.of("sgb-border", "dmg-colors"),
                AndroidMenuModel.displayPage(false, false).items().stream()
                        .map(MenuPageSpec.Item::id).toList());
        assertEquals(List.of("camera", "gamepad", "gps"),
                AndroidMenuModel.optionalDevicesPage("off", "auto", false, List.of())
                        .items().stream().map(MenuPageSpec.Item::id).toList());
        assertEquals("FAST-FORWARD",
                AndroidMenuModel.systemPage("auto", "auto", "fast-forward", "dmg-games")
                        .items().get(2).detail());
    }

    @Test
    public void optionPickerMarksOnlyCommittedChoiceAndUsesAChooseFooter() {
        MenuPageSpec page = AndroidMenuModel.optionPickerPage("DMG GAMES", List.of(
                new AndroidMenuModel.ChoiceValue("auto", "AUTO"),
                new AndroidMenuModel.ChoiceValue("dmg", "DMG")), "dmg");
        assertEquals(List.of("choice:auto", "choice:dmg"),
                page.items().stream().map(MenuPageSpec.Item::id).toList());
        assertEquals("", page.items().get(0).detail());
        assertEquals("SELECTED", page.items().get(1).detail());
        assertEquals(List.of("D-PAD MOVE", "A CHOOSE", "B BACK"), page.footerHints());
    }

    @Test
    public void unavailableChoiceIsVisibleButCannotBeApplied() {
        MenuPageSpec page = AndroidMenuModel.optionPickerPage("GAMEPAD", List.of(
                new AndroidMenuModel.ChoiceValue("sdl-missing", "UNAVAILABLE", false)),
                "sdl-missing");
        assertEquals(List.of("picker-status"), page.items().stream()
                .map(MenuPageSpec.Item::id).toList());
        assertEquals("NOT AVAILABLE", page.items().get(0).label());
        assertTrue(page.items().get(0).enabled());
        assertEquals(List.of("", "", "B BACK"), page.footerHints());
    }

    @Test
    public void gamepadSelectionRejectsDisabledAndUnselectedDevices() {
        assertFalse(AndroidInputRouter.allowsSelection("none", "sdl-abc"));
        assertTrue(AndroidInputRouter.allowsSelection("auto", "sdl-abc"));
        assertTrue(AndroidInputRouter.allowsSelection("sdl-abc", "sdl-abc"));
        assertFalse(AndroidInputRouter.allowsSelection("sdl-abc", "sdl-def"));
        assertTrue(AndroidInputRouter.acceptsMenuControllerSources(
                InputDevice.SOURCE_GAMEPAD, false));
    }
}
