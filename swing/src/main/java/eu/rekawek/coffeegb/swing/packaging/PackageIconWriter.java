package eu.rekawek.coffeegb.swing.packaging;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/** Generates platform raster containers from the repository's vector icon geometry. */
final class PackageIconWriter {

    private static final int CANVAS = 512;

    private PackageIconWriter() {
    }

    static void write(NativePackageMetadata.Target target, Path output) throws IOException {
        switch (target.iconSuffix()) {
            case "png" -> writeBytes(output, png(256));
            case "ico" -> writeIco(output);
            case "icns" -> writeIcns(output);
            default -> throw new IllegalArgumentException(
                    "Unsupported package icon format: " + target.iconSuffix());
        }
    }

    private static void writeIco(Path output) throws IOException {
        int[] sizes = {16, 24, 32, 48, 64, 128, 256};
        List<byte[]> images = new ArrayList<>();
        for (int size : sizes) {
            images.add(png(size));
        }
        try (OutputStream raw = Files.newOutputStream(
                        output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                DataOutputStream stream = new DataOutputStream(raw)) {
            littleShort(stream, 0);
            littleShort(stream, 1);
            littleShort(stream, sizes.length);
            int offset = 6 + sizes.length * 16;
            for (int i = 0; i < sizes.length; i++) {
                int size = sizes[i];
                byte[] image = images.get(i);
                stream.writeByte(size == 256 ? 0 : size);
                stream.writeByte(size == 256 ? 0 : size);
                stream.writeByte(0);
                stream.writeByte(0);
                littleShort(stream, 1);
                littleShort(stream, 32);
                littleInt(stream, image.length);
                littleInt(stream, offset);
                offset += image.length;
            }
            for (byte[] image : images) {
                stream.write(image);
            }
        }
    }

    private static void writeIcns(Path output) throws IOException {
        String[] types = {"icp4", "icp5", "icp6", "ic07", "ic08", "ic09", "ic10"};
        int[] sizes = {16, 32, 64, 128, 256, 512, 1024};
        List<byte[]> images = new ArrayList<>();
        int total = 8;
        for (int size : sizes) {
            byte[] image = png(size);
            images.add(image);
            total = Math.addExact(total, Math.addExact(8, image.length));
        }
        try (OutputStream raw = Files.newOutputStream(
                        output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                DataOutputStream stream = new DataOutputStream(raw)) {
            stream.writeBytes("icns");
            stream.writeInt(total);
            for (int i = 0; i < types.length; i++) {
                stream.writeBytes(types[i]);
                stream.writeInt(8 + images.get(i).length);
                stream.write(images.get(i));
            }
        }
    }

    private static byte[] png(int size) throws IOException {
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
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) {
            throw new IOException("The JDK PNG writer is unavailable");
        }
        return bytes.toByteArray();
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

    private static void writeBytes(Path output, byte[] bytes) throws IOException {
        Files.write(
                output,
                bytes,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    private static void littleShort(DataOutputStream stream, int value) throws IOException {
        stream.writeByte(value & 0xff);
        stream.writeByte((value >>> 8) & 0xff);
    }

    private static void littleInt(DataOutputStream stream, int value) throws IOException {
        stream.writeByte(value & 0xff);
        stream.writeByte((value >>> 8) & 0xff);
        stream.writeByte((value >>> 16) & 0xff);
        stream.writeByte((value >>> 24) & 0xff);
    }
}
