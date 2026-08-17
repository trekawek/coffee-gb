package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuController;
import eu.rekawek.coffeegb.ui.menu.MenuPageSpec;
import eu.rekawek.coffeegb.ui.menu.MenuPreview;
import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** Regression coverage for status-only routes supplied by hosts without a platform capability. */
public class Proposal3UnavailableRouteTest {

    @Test
    public void unavailableLibraryDoesNotInventOpenRomAction() {
        MenuPresentation presentation = statusOnly(MenuRoute.LIBRARY, "library-status");

        assertFalse(presentation.items().stream().anyMatch(item -> "open-rom".equals(item.id())));
        int[] pixels = new Proposal3MenuCompositor().compose(presentation).orElseThrow()
                .copyPixels();
        MenuRect action = Proposal3OverlayCatalog.layout(MenuRoute.LIBRARY).actions().get(0)
                .bounds();
        assertEquals("unavailable Library must leave the action strip blank", 0,
                inkPixels(pixels, action));
    }

    @Test
    public void unavailableAboutDoesNotInventGithubAction() {
        MenuPresentation presentation = statusOnly(MenuRoute.ABOUT, "about-status");

        assertFalse(presentation.items().stream()
                .anyMatch(item -> "source-notices".equals(item.id())));
        int[] pixels = new Proposal3MenuCompositor().compose(presentation).orElseThrow()
                .copyPixels();
        MenuRect action = Proposal3OverlayCatalog.layout(MenuRoute.ABOUT).actions().get(0)
                .bounds();
        assertEquals("unavailable About must leave the action strip blank", 0,
                inkPixels(pixels, action));
    }

    private static MenuPresentation statusOnly(MenuRoute route, String id) {
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
        controller.setPage(new MenuPageSpec(route, "COFFEE GB", route.name(), "", "",
                List.of(), List.of(new MenuPageSpec.Item(id, "NOT AVAILABLE", "", true)), 1,
                List.of("", "", "B BACK"), id, MenuPreview.empty()));
        controller.show(route);
        return controller.presentation();
    }

    private static int inkPixels(int[] pixels, MenuRect bounds) {
        int count = 0;
        for (int y = bounds.y(); y < bounds.bottom(); y++) {
            for (int x = bounds.x(); x < bounds.right(); x++) {
                int value = pixels[y * 924 + x];
                int red = value >>> 16 & 0xff;
                int green = value >>> 8 & 0xff;
                int blue = value & 0xff;
                if ((red + green + blue) / 3 < 45) {
                    count++;
                }
            }
        }
        return count;
    }
}
