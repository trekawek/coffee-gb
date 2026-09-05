package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.controller.properties.EmulatorProperties;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.ui.menu.MenuController;
import eu.rekawek.coffeegb.ui.menu.MenuPageSpec;
import eu.rekawek.coffeegb.ui.menu.MenuPointerTarget;
import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import eu.rekawek.coffeegb.ui.menu.artwork.MenuPoint;
import eu.rekawek.coffeegb.ui.menu.artwork.MenuViewport;
import eu.rekawek.coffeegb.ui.menu.artwork.Proposal3MenuCompositor;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;

public class SwingMenuPointerTest {

    @Test
    public void scaledMenuClickActivatesClickedRowAndRejectsBarsAndMismatchedRelease()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<String> selected = new ArrayList<>();
            SwingDisplay display = new SwingDisplay(new EmulatorProperties().getDisplay(),
                    EventBus.NULL_EVENT_BUS, "pointer-test");
            display.setSize(1600, 900);
            Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
            MenuController controller = new MenuController(new MenuController.Listener() {
                @Override
                public void onPresentation(MenuPresentation presentation) {
                    display.setMenuOverlay(compositor.compose(presentation).orElse(null));
                }

                @Override
                public void onItemSelected(MenuRoute route, String id, boolean secondary) {
                    selected.add(id);
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
            display.setMenuPointerInput(new DesktopMenuPointerInput() {
                @Override
                public Optional<MenuPointerTarget> targetAt(int sourceX, int sourceY) {
                    return Proposal3MenuCompositor.hitTest(controller.presentation(), sourceX, sourceY);
                }

                @Override
                public boolean activateTarget(MenuPointerTarget target) {
                    return controller.activateTarget(target);
                }
            });

            MenuViewport viewport = MenuViewport.fit(display.getWidth(), display.getHeight());
            MenuPoint first = viewport.sourceToView(600, 150);
            MenuPoint second = viewport.sourceToView(600, 230);
            pointer(display, MouseEvent.MOUSE_PRESSED, second);
            assertEquals(List.of(), selected);
            pointer(display, MouseEvent.MOUSE_RELEASED, second);
            assertEquals(List.of("display"), selected);
            pointer(display, MouseEvent.MOUSE_PRESSED, first);
            pointer(display, MouseEvent.MOUSE_RELEASED, second);
            pointer(display, MouseEvent.MOUSE_PRESSED, new MenuPoint(20, 200));
            pointer(display, MouseEvent.MOUSE_RELEASED, new MenuPoint(20, 200));
            pointer(display, MouseEvent.MOUSE_PRESSED, first);
            controller.hide();
            controller.show(MenuRoute.SETTINGS);
            pointer(display, MouseEvent.MOUSE_RELEASED, first);
            assertEquals(List.of("display"), selected);
        });
    }

    private static void pointer(SwingDisplay display, int event, MenuPoint point) {
        display.dispatchEvent(new MouseEvent(display, event, 0L, 0,
                (int) point.x(), (int) point.y(), 1, false, MouseEvent.BUTTON1));
    }
}
