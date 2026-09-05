package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.ui.menu.MenuController;
import eu.rekawek.coffeegb.ui.menu.MenuPageSpec;
import eu.rekawek.coffeegb.ui.menu.MenuPointerGesture;
import eu.rekawek.coffeegb.ui.menu.MenuPointerTarget;
import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import eu.rekawek.coffeegb.ui.menu.artwork.MenuPoint;
import eu.rekawek.coffeegb.ui.menu.artwork.MenuViewport;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class MenuPointerTouchTest {

    @Test
    public void touchUsesTheSkinsOffsetApertureAndReleaseMustMatchThePressedRow() {
        MenuController controller = new MenuController(new MenuController.Listener() {
            @Override
            public void onPresentation(MenuPresentation presentation) {
            }

            @Override
            public void onItemSelected(MenuRoute route, String id, boolean secondary) {
            }

            @Override
            public void onHeaderSelected(MenuRoute route) {
            }
        });
        controller.setPages(List.of(new MenuPageSpec(MenuRoute.SETTINGS, "SETTINGS", "",
                "", "", List.of(), List.of(
                        MenuPageSpec.Item.button("audio", "AUDIO", "", true),
                        MenuPageSpec.Item.button("display", "DISPLAY", "", true)),
                1, List.of("D-PAD MOVE", "A CHOOSE", "B BACK"))));
        controller.show(MenuRoute.SETTINGS);
        MenuPresentation presentation = controller.presentation();
        MenuViewport viewport = MenuViewport.fit(800, 600);
        MenuPoint first = viewport.sourceToView(600, 150);
        MenuPoint second = viewport.sourceToView(600, 230);
        MenuPointerTarget firstTarget = hit(presentation, first);
        MenuPointerTarget secondTarget = hit(presentation, second);
        assertEquals("audio", firstTarget.itemId());
        assertEquals("display", secondTarget.itemId());
        assertNull(CoffeeGbSurfaceView.menuTargetAt(presentation, 100, 200, 800, 600, 105, 350));
        assertNull(CoffeeGbSurfaceView.menuTargetAt(presentation, 100, 200, 800, 600, 99, 350));

        MenuPointerGesture gesture = new MenuPointerGesture();
        gesture.press(7, firstTarget);
        assertFalse(gesture.release(7, secondTarget).isPresent());
        gesture.press(7, secondTarget);
        assertEquals(secondTarget, gesture.release(7, secondTarget).orElseThrow());
        assertFalse(gesture.release(7, secondTarget).isPresent());
    }

    private static MenuPointerTarget hit(MenuPresentation presentation, MenuPoint point) {
        return CoffeeGbSurfaceView.menuTargetAt(presentation, 100, 200, 800, 600,
                (float) point.x() + 100, (float) point.y() + 200);
    }
}
