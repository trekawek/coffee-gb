package eu.rekawek.coffeegb.swing;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;

/** Raster derivative of the repository SVG shared by runtime and native packaging. */
public final class CoffeeGbIcon {

    private static final String SOURCE_RESOURCE =
            "/eu/rekawek/coffeegb/swing/coffee-gb.png";

    private static volatile BufferedImage source;

    private CoffeeGbIcon() {
    }

    public static BufferedImage image(int size) {
        if (size < 1 || size > 4096) {
            throw new IllegalArgumentException("Icon size must be between 1 and 4096 pixels");
        }

        BufferedImage original = source();
        BufferedImage current = original;
        while (current.getWidth() / 2 >= size) {
            current = scale(current, Math.max(size, current.getWidth() / 2));
        }
        if (current.getWidth() != size) {
            current = scale(current, size);
        } else if (current == original) {
            current = scale(current, size);
        }
        return current;
    }

    public static ImageIcon swingIcon(int size) {
        return new ImageIcon(
                image(size), "Coffee GB handheld with a coffee cup on its screen");
    }

    private static BufferedImage loadSource() {
        try (InputStream input = CoffeeGbIcon.class.getResourceAsStream(SOURCE_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Bundled application icon is missing: " + SOURCE_RESOURCE);
            }
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new IllegalStateException(
                        "Bundled application icon is not a supported image: " + SOURCE_RESOURCE);
            }
            if (image.getWidth() != image.getHeight()) {
                throw new IllegalStateException(
                        "Bundled application icon must be square: " + SOURCE_RESOURCE);
            }
            if (image.getWidth() < 1024) {
                throw new IllegalStateException(
                        "Bundled application icon must be at least 1024 pixels: "
                                + SOURCE_RESOURCE);
            }
            return image;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot read bundled application icon: " + SOURCE_RESOURCE, e);
        }
    }

    private static BufferedImage source() {
        BufferedImage current = source;
        if (current == null) {
            synchronized (CoffeeGbIcon.class) {
                current = source;
                if (current == null) {
                    current = loadSource();
                    source = current;
                }
            }
        }
        return current;
    }

    private static BufferedImage scale(BufferedImage source, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setRenderingHint(
                    RenderingHints.KEY_ALPHA_INTERPOLATION,
                    RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
            graphics.setRenderingHint(
                    RenderingHints.KEY_COLOR_RENDERING,
                    RenderingHints.VALUE_COLOR_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, size, size, null);
        } finally {
            graphics.dispose();
        }
        return image;
    }
}
