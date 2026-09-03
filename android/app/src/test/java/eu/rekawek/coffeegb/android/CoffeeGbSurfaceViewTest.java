package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.ui.menu.MenuController;
import eu.rekawek.coffeegb.ui.menu.MenuPageSpec;
import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

public class CoffeeGbSurfaceViewTest {

    @Test
    public void menuClearInvalidatesOnlyAnExistingPresentation() {
        assertFalse(CoffeeGbSurfaceView.requiresMenuInvalidationOnClear(null));

        MenuPresentation existingPresentation =
                new MenuController(new NoopMenuListener()).presentation();
        assertTrue(CoffeeGbSurfaceView.requiresMenuInvalidationOnClear(existingPresentation));
    }

    @Test
    public void onlyExactRatioThemesUseFractionalApertureFit() {
        assertFalse(CoffeeGbSurfaceView.fillsExactThemedAperture(
                NativeFrameStore.Presentation.DMG));
        assertTrue(CoffeeGbSurfaceView.fillsExactThemedAperture(
                NativeFrameStore.Presentation.CGB));
        assertTrue(CoffeeGbSurfaceView.fillsExactThemedAperture(
                NativeFrameStore.Presentation.SGB_BORDER));
    }

    @Test
    public void onlyVisibleFileBrowserRequiresAnimationFrames() {
        assertFalse(CoffeeGbSurfaceView.requiresMenuAnimation(null));
        assertFalse(CoffeeGbSurfaceView.requiresMenuAnimation(
                new MenuController(new NoopMenuListener()).presentation()));

        MenuController controller = new MenuController(new NoopMenuListener());
        controller.setPages(List.of(new MenuPageSpec(MenuRoute.FILE_BROWSER, "COFFEE GB",
                "ROM FOLDER", "", "", List.of(), List.of(
                        MenuPageSpec.Item.button("parent", "..", "", true)), 1,
                List.of("", "A OPEN", "B BACK"))));
        controller.show(MenuRoute.FILE_BROWSER);
        assertTrue(CoffeeGbSurfaceView.requiresMenuAnimation(controller.presentation()));
    }

    private static final class NoopMenuListener implements MenuController.Listener {

        @Override
        public void onPresentation(MenuPresentation presentation) {
        }

        @Override
        public void onItemSelected(MenuRoute route, String id, boolean secondary) {
        }

        @Override
        public void onHeaderSelected(MenuRoute route) {
        }
    }
}
