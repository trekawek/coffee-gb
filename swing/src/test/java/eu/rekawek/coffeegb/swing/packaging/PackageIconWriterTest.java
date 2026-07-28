package eu.rekawek.coffeegb.swing.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PackageIconWriterTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void writesLinuxPngFromVectorGeometry() throws Exception {
        Path icon = temporaryFolder.getRoot().toPath().resolve("coffee-gb.png");
        PackageIconWriter.write(
                NativePackageMetadata.target(NativeTarget.LINUX_X86_64), icon);
        BufferedImage image = ImageIO.read(icon.toFile());
        assertEquals(256, image.getWidth());
        assertEquals(256, image.getHeight());
        assertTrue(((image.getRGB(128, 128) >>> 24) & 0xff) > 0);
    }

    @Test
    public void writesMultiResolutionWindowsIco() throws Exception {
        Path icon = temporaryFolder.getRoot().toPath().resolve("coffee-gb.ico");
        PackageIconWriter.write(
                NativePackageMetadata.target(NativeTarget.WINDOWS_X86_64), icon);
        byte[] header = Files.readAllBytes(icon);
        assertTrue(header.length > 5_000);
        assertArrayEquals(new byte[] {0, 0, 1, 0, 4, 0}, slice(header, 0, 6));
        assertEquals(16, header[6] & 0xff);
        assertEquals(32, header[22] & 0xff);
        assertEquals(48, header[38] & 0xff);
        assertEquals(0, header[54] & 0xff);
    }

    @Test
    public void writesMultiResolutionMacIcns() throws Exception {
        Path icon = temporaryFolder.getRoot().toPath().resolve("coffee-gb.icns");
        PackageIconWriter.write(
                NativePackageMetadata.target(NativeTarget.MACOS_AARCH64), icon);
        byte[] bytes = Files.readAllBytes(icon);
        assertTrue(bytes.length > 20_000);
        assertEquals("icns", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
        int declared = ((bytes[4] & 0xff) << 24)
                | ((bytes[5] & 0xff) << 16)
                | ((bytes[6] & 0xff) << 8)
                | (bytes[7] & 0xff);
        assertEquals(bytes.length, declared);
        assertEquals("icp4", new String(bytes, 8, 4, StandardCharsets.US_ASCII));
    }

    private static byte[] slice(byte[] value, int start, int end) {
        byte[] result = new byte[end - start];
        System.arraycopy(value, start, result, 0, result.length);
        return result;
    }
}
