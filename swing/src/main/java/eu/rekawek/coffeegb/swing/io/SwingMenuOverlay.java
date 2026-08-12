package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.ui.menu.artwork.MenuArgbFrame;
import eu.rekawek.coffeegb.ui.menu.artwork.MenuRect;
import eu.rekawek.coffeegb.ui.menu.artwork.MenuViewport;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * Swing's tiny adapter around the platform-neutral Proposal 3 frame.
 *
 * <p>The portable frame is decoded exactly once per object identity and then drawn with an
 * integer destination rectangle from {@link MenuViewport}. No Swing text, layout, or widget is
 * painted here.</p>
 */
final class SwingMenuOverlay {

    private MenuArgbFrame frame;

    private BufferedImage image;

    synchronized void setFrame(MenuArgbFrame next) {
        if (next == frame) {
            return;
        }
        frame = next;
        image = next == null ? null : decode(next);
    }

    synchronized boolean visible() {
        return frame != null;
    }

    void paint(Graphics2D graphics, int viewWidth, int viewHeight) {
        Objects.requireNonNull(graphics, "graphics");
        if (viewWidth <= 0 || viewHeight <= 0) {
            return;
        }
        BufferedImage current;
        synchronized (this) {
            current = image;
        }
        if (current == null) {
            return;
        }

        MenuViewport viewport = MenuViewport.fit(viewWidth, viewHeight);
        MenuRect destination = viewport.contentBounds();
        Graphics2D copy = (Graphics2D) graphics.create();
        copy.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        copy.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        copy.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                RenderingHints.VALUE_ALPHA_INTERPOLATION_SPEED);
        copy.drawImage(current,
                destination.x(), destination.y(), destination.right(), destination.bottom(),
                0, 0, current.getWidth(), current.getHeight(), null);
        copy.dispose();
    }

    /** Package-private identity seam for cache tests. */
    synchronized BufferedImage imageForTest() {
        return image;
    }

    /** Package-private identity seam for cache tests. */
    synchronized MenuArgbFrame frameForTest() {
        return frame;
    }

    private static BufferedImage decode(MenuArgbFrame source) {
        BufferedImage decoded = new BufferedImage(source.width(), source.height(),
                BufferedImage.TYPE_INT_ARGB);
        int[] pixels = source.copyPixels();
        decoded.setRGB(0, 0, source.width(), source.height(), pixels, 0, source.width());
        return decoded;
    }
}
