package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.ui.menu.MenuController;
import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import eu.rekawek.coffeegb.ui.menu.artwork.MenuArgbFrame;
import eu.rekawek.coffeegb.ui.menu.artwork.MenuRect;
import eu.rekawek.coffeegb.ui.menu.artwork.MenuViewport;
import eu.rekawek.coffeegb.ui.menu.artwork.Proposal3MenuCompositor;
import org.junit.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class SwingMenuOverlayTest {

    @Test
    public void menuViewportIsUniformAndCentered() {
        MenuViewport viewport = MenuViewport.fit(1600, 900);

        assertEquals(new MenuRect(235, 0, 1129, 900), viewport.contentBounds());
        assertEquals(viewport.contentBounds().width() / (double) viewport.contentBounds().height(),
                MenuViewport.SOURCE_WIDTH / (double) MenuViewport.SOURCE_HEIGHT,
                0.002);
    }

    @Test
    public void frameIdentityReusesTheDecodedSwingImage() {
        MenuArgbFrame frame = composePauseFrame();
        SwingMenuOverlay overlay = new SwingMenuOverlay();

        overlay.setFrame(frame);
        BufferedImage first = overlay.imageForTest();
        overlay.setFrame(frame);

        assertSame(first, overlay.imageForTest());
        assertSame(frame, overlay.frameForTest());
        overlay.setFrame(null);
        assertNull(overlay.imageForTest());
    }

    @Test
    public void exactFitRendersPortablePixelsWithoutReflow() {
        MenuArgbFrame frame = composePauseFrame();
        SwingMenuOverlay overlay = new SwingMenuOverlay();
        overlay.setFrame(frame);
        BufferedImage target = new BufferedImage(frame.width(), frame.height(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        overlay.paint(graphics, target.getWidth(), target.getHeight());
        graphics.dispose();

        int[] expected = frame.copyPixels();
        assertEquals(expected[0], target.getRGB(0, 0));
        int center = (frame.height() / 2) * frame.width() + frame.width() / 2;
        assertEquals(expected[center], target.getRGB(frame.width() / 2, frame.height() / 2));
    }

    private static MenuArgbFrame composePauseFrame() {
        AtomicReference<MenuPresentation> presentation = new AtomicReference<>();
        MenuController controller = new MenuController(new MenuController.Listener() {
            @Override
            public void onPresentation(MenuPresentation next) {
                presentation.set(next);
            }

            @Override
            public void onItemSelected(MenuRoute route, String id, boolean secondary) {
            }

            @Override
            public void onHeaderSelected(MenuRoute route) {
            }
        });
        controller.show(MenuRoute.PAUSE_CONSOLE);
        return new Proposal3MenuCompositor()
                .compose(presentation.get())
                .orElseThrow(() -> new AssertionError("pause frame was not composed"));
    }
}
