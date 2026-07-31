package eu.rekawek.coffeegb.swing.packaging;

import eu.rekawek.coffeegb.swing.CoffeeGbIcon;
import javax.imageio.ImageIO;
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
        BufferedImage image = CoffeeGbIcon.image(size);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) {
            throw new IOException("The JDK PNG writer is unavailable");
        }
        return bytes.toByteArray();
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
