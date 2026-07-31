package eu.rekawek.coffeegb.swing;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;

/** Repository-owned Pocket Brew mark shared by runtime and native-package presentation. */
public final class CoffeeGbIcon {

    private static final int CANVAS = 512;

    private CoffeeGbIcon() {
    }

    public static BufferedImage image(int size) {
        if (size < 1 || size > 4096) {
            throw new IllegalArgumentException("Icon size must be between 1 and 4096 pixels");
        }
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.scale(size / (double) CANVAS, size / (double) CANVAS);
            draw(graphics, size <= 24);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    public static ImageIcon swingIcon(int size) {
        return new ImageIcon(image(size), "Coffee GB Pocket Brew mark");
    }

    private static void draw(Graphics2D graphics, boolean compact) {
        Color espresso = new Color(0x21, 0x14, 0x10);
        Color oat = new Color(0xe7, 0xc7, 0x90);
        Color cream = new Color(0xff, 0xf0, 0xd2);
        Color coffee = new Color(0x4b, 0x2a, 0x1b);

        graphics.setColor(espresso);
        graphics.fill(new RoundRectangle2D.Double(24, 24, 464, 464, 108, 108));

        Path2D handle = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        handle.moveTo(360, 205);
        handle.lineTo(395, 205);
        handle.curveTo(442, 205, 478, 242, 478, 289);
        handle.lineTo(478, 330);
        handle.curveTo(478, 377, 442, 414, 395, 414);
        handle.lineTo(360, 414);
        handle.closePath();
        handle.moveTo(395, 249);
        handle.lineTo(382, 249);
        handle.lineTo(382, 370);
        handle.lineTo(395, 370);
        handle.curveTo(417, 370, 434, 352, 434, 330);
        handle.lineTo(434, 289);
        handle.curveTo(434, 267, 417, 249, 395, 249);
        handle.closePath();
        graphics.setColor(oat);
        graphics.fill(handle);

        Stroke previousStroke = graphics.getStroke();
        graphics.setStroke(new BasicStroke(
                compact ? 32 : 26, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(cream);
        if (compact) {
            Path2D steam = new Path2D.Double();
            steam.moveTo(252, 127);
            steam.curveTo(231, 107, 267, 90, 250, 66);
            graphics.draw(steam);
        } else {
            Path2D leftSteam = new Path2D.Double();
            leftSteam.moveTo(219, 127);
            leftSteam.curveTo(200, 109, 234, 91, 217, 66);
            graphics.draw(leftSteam);
            Path2D rightSteam = new Path2D.Double();
            rightSteam.moveTo(286, 127);
            rightSteam.curveTo(267, 109, 301, 91, 284, 66);
            graphics.draw(rightSteam);
        }
        graphics.setStroke(previousStroke);

        graphics.setColor(oat);
        graphics.fill(new RoundRectangle2D.Double(78, 140, 330, 305, 72, 72));
        graphics.setColor(coffee);
        graphics.fill(new RoundRectangle2D.Double(128, 160, 230, 28, 28, 28));
        graphics.setColor(new Color(0xa8, 0xb8, 0x6c));
        graphics.fill(new RoundRectangle2D.Double(122, 205, 242, 110, 48, 48));

        Path2D dpad = new Path2D.Double();
        dpad.moveTo(158, 335);
        dpad.lineTo(196, 335);
        dpad.lineTo(196, 367);
        dpad.lineTo(228, 367);
        dpad.lineTo(228, 405);
        dpad.lineTo(196, 405);
        dpad.lineTo(196, 437);
        dpad.lineTo(158, 437);
        dpad.lineTo(158, 405);
        dpad.lineTo(126, 405);
        dpad.lineTo(126, 367);
        dpad.lineTo(158, 367);
        dpad.closePath();
        graphics.setColor(coffee);
        graphics.fill(dpad);

        graphics.setColor(new Color(0xc4, 0x5c, 0x3c));
        graphics.fill(new Ellipse2D.Double(290, 340, 50, 50));
        graphics.fill(new Ellipse2D.Double(337, 377, 50, 50));
    }
}
