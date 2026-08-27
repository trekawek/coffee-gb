package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.ui.menu.MenuController;
import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CoffeeGbSurfaceViewTest {

    @Test
    public void menuClearInvalidatesOnlyAnExistingPresentation() {
        assertFalse(CoffeeGbSurfaceView.requiresMenuInvalidationOnClear(null));

        MenuPresentation existingPresentation =
                new MenuController(new NoopMenuListener()).presentation();
        assertTrue(CoffeeGbSurfaceView.requiresMenuInvalidationOnClear(existingPresentation));
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
