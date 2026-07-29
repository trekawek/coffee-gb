package eu.rekawek.coffeegb.swing.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class NativePackageLicenseTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void windowsAndMacOsReceiveAsciiRtfWithUnicodeAuthorName() throws Exception {
        Path canonical = writeCanonical(
                "MIT License\r\n\r\nCopyright (c) 2017 Tomasz Rękawek\r\n"
                        + "Braces {and} slash \\\r\n");

        for (NativePackageMetadata.HostOs hostOs : new NativePackageMetadata.HostOs[] {
            NativePackageMetadata.HostOs.WINDOWS, NativePackageMetadata.HostOs.MACOS
        }) {
            Path stage = temporaryFolder.newFolder(hostOs.id()).toPath();
            Path resources = Files.createDirectory(stage.resolve("jpackage-resources"));
            if (hostOs == NativePackageMetadata.HostOs.MACOS) {
                Files.writeString(
                        resources.resolve(NativePackageLicense.MACOS_LICENSE_TEMPLATE),
                        "<key>RTF </key><data>APPLICATION_LICENSE_TEXT</data>",
                        StandardCharsets.US_ASCII);
            }

            Path installerLicense =
                    NativePackageLicense.prepare(canonical, stage, resources, hostOs);
            byte[] bytes = Files.readAllBytes(installerLicense);
            String rtf = new String(bytes, StandardCharsets.US_ASCII);

            assertArrayEquals(rtf.getBytes(StandardCharsets.US_ASCII), bytes);
            assertTrue(rtf.startsWith("{\\rtf1\\ansi\\ansicpg1252"));
            assertTrue(rtf.contains("Tomasz R\\u281?kawek"));
            assertTrue(rtf.contains("Braces \\{and\\} slash \\\\"));
            assertFalse(rtf.contains("Rękawek"));
            assertFalse(rtf.contains("ƒ"));
        }
    }

    @Test
    public void linuxKeepsTheCanonicalUtf8License() throws Exception {
        Path canonical = writeCanonical("Copyright (c) 2017 Tomasz Rękawek\n");
        Path stage = temporaryFolder.newFolder("linux").toPath();
        Path resources = Files.createDirectory(stage.resolve("jpackage-resources"));

        Path installerLicense = NativePackageLicense.prepare(
                canonical, stage, resources, NativePackageMetadata.HostOs.LINUX);

        assertEquals(canonical, installerLicense);
        assertEquals(
                "Copyright (c) 2017 Tomasz Rękawek\n",
                Files.readString(installerLicense, StandardCharsets.UTF_8));
    }

    @Test
    public void canonicalLicenseRejectsWrongEncodingNormalizationAndName() throws Exception {
        Path malformed = temporaryFolder.newFile("malformed.txt").toPath();
        Files.write(malformed, new byte[] {(byte) 0xc4});
        assertThrows(IOException.class, () -> NativePackageLicense.readCanonicalUtf8(malformed));

        Path decomposed = writeCanonical("Copyright Tomasz Re\u0328kawek\n");
        assertThrows(IOException.class, () -> NativePackageLicense.readCanonicalUtf8(decomposed));

        Path missing = writeCanonical("Copyright Tomasz Rekawek\n");
        assertThrows(IOException.class, () -> NativePackageLicense.readCanonicalUtf8(missing));
    }

    @Test
    public void macOsRequiresTheRtfResourceOverride() throws Exception {
        Path canonical = writeCanonical("Copyright Tomasz Rękawek\n");
        Path stage = temporaryFolder.newFolder("missing-macos-template").toPath();
        Path resources = Files.createDirectory(stage.resolve("jpackage-resources"));

        assertThrows(
                IOException.class,
                () -> NativePackageLicense.prepare(
                        canonical, stage, resources, NativePackageMetadata.HostOs.MACOS));
    }

    private Path writeCanonical(String contents) throws Exception {
        Path file = temporaryFolder.newFile().toPath();
        Files.writeString(file, contents, StandardCharsets.UTF_8);
        return file;
    }
}
