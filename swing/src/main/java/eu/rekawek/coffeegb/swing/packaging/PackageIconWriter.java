package eu.rekawek.coffeegb.swing.packaging;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
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
        int[] sizes = {16, 32, 48, 256};
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
            draw(graphics);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) {
            throw new IOException("The JDK PNG writer is unavailable");
        }
        return bytes.toByteArray();
    }

    private static void draw(Graphics2D graphics) {
        graphics.setColor(new Color(0x30, 0x24, 0x1f));
        graphics.fill(new RoundRectangle2D.Double(24, 24, 464, 464, 104, 104));

        graphics.setColor(new Color(0x8d, 0x4e, 0x29));
        graphics.fill(new RoundRectangle2D.Double(48, 40, 416, 432, 82, 82));

        graphics.setColor(new Color(0x27, 0x2a, 0x2b));
        graphics.fill(new RoundRectangle2D.Double(96, 82, 320, 212, 34, 34));

        graphics.setColor(new Color(0xa9, 0xcf, 0x72));
        graphics.fill(new RoundRectangle2D.Double(122, 108, 268, 160, 18, 18));

        Path2D beanUpper = new Path2D.Double();
        beanUpper.moveTo(226, 176);
        beanUpper.curveTo(248, 136, 304, 134, 324, 173);
        beanUpper.curveTo(294, 163, 281, 202, 249, 200);
        beanUpper.curveTo(233, 199, 222, 190, 226, 176);
        graphics.setColor(new Color(0x4d, 0x6d, 0x32));
        graphics.fill(beanUpper);

        Path2D beanLower = new Path2D.Double();
        beanLower.moveTo(252, 195);
        beanLower.curveTo(277, 200, 286, 166, 322, 175);
        beanLower.curveTo(302, 215, 248, 220, 227, 183);
        beanLower.curveTo(234, 189, 242, 193, 252, 195);
        graphics.setColor(new Color(0x30, 0x49, 0x24));
        graphics.fill(beanLower);

        Path2D dpad = new Path2D.Double();
        dpad.moveTo(129, 341);
        dpad.lineTo(171, 341);
        dpad.lineTo(171, 299);
        dpad.lineTo(213, 299);
        dpad.lineTo(213, 341);
        dpad.lineTo(255, 341);
        dpad.lineTo(255, 383);
        dpad.lineTo(213, 383);
        dpad.lineTo(213, 425);
        dpad.lineTo(171, 425);
        dpad.lineTo(171, 383);
        dpad.lineTo(129, 383);
        dpad.closePath();
        graphics.setColor(new Color(0x2d, 0x29, 0x28));
        graphics.fill(dpad);

        graphics.setColor(new Color(0x5c, 0x1d, 0x27));
        graphics.fill(new Ellipse2D.Double(322, 318, 60, 60));
        graphics.fill(new Ellipse2D.Double(375, 367, 60, 60));

        graphics.setColor(new Color(0x3d, 0x2d, 0x29));
        fillRotatedRoundRect(graphics, 273, 417, 64, 14, -16);
        fillRotatedRoundRect(graphics, 330, 417, 64, 14, -16);
    }

    private static void fillRotatedRoundRect(
            Graphics2D graphics, double x, double y, double width, double height, double degrees) {
        AffineTransform previous = graphics.getTransform();
        graphics.rotate(Math.toRadians(degrees), x + width / 2, y + height / 2);
        graphics.fill(new RoundRectangle2D.Double(x, y, width, height, 14, 14));
        graphics.setTransform(previous);
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
