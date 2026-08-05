package eu.rekawek.coffeegb.swing.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PortableWindowsExeTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void appendsTheSfxConfigurationAndApplicationArchiveToTheModule() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        Path module = Files.write(root.resolve("7z.sfx"), new byte[] {'M', 'Z', 1, 2});
        Path archive = Files.write(root.resolve("coffee-gb.7z"), new byte[] {3, 4, 5});
        Path output = root.resolve("Coffee GB.exe");

        PortableWindowsExe.assemble(module, archive, output);

        byte[] bytes = Files.readAllBytes(output);
        assertEquals('M', bytes[0]);
        assertEquals('Z', bytes[1]);
        String contents = new String(bytes, StandardCharsets.ISO_8859_1);
        assertTrue(contents.contains(";!@Install@!UTF-8!"));
        assertTrue(contents.contains("Title=\"Coffee GB\""));
        assertTrue(contents.contains("RunProgram=\"Coffee GB\\Coffee GB.exe\""));
        assertTrue(contents.contains(";!@InstallEnd@!"));
        assertEquals(5, bytes[bytes.length - 1]);
        assertEquals(4, bytes[bytes.length - 2]);
        assertEquals(3, bytes[bytes.length - 3]);
    }

    @Test
    public void requiresAnExplicitSevenZipCommandAndItsSfxModule() throws Exception {
        Path root = temporaryFolder.getRoot().toPath();
        assertThrows(
                java.io.IOException.class,
                () -> PortableWindowsExe.requireSevenZip(Map.of()));

        Path sevenZip = Files.writeString(root.resolve("7z.exe"), "tool");
        assertThrows(
                java.io.IOException.class,
                () -> PortableWindowsExe.requireSevenZip(
                        Map.of(PortableWindowsExe.SEVEN_ZIP_COMMAND, sevenZip.toString())));

        Path module = Files.writeString(root.resolve("7z.sfx"), "module");
        PortableWindowsExe.SevenZip discovered = PortableWindowsExe.requireSevenZip(
                Map.of(PortableWindowsExe.SEVEN_ZIP_COMMAND, sevenZip.toString()));
        assertEquals(sevenZip, discovered.executable());
        assertEquals(module, discovered.sfxModule());
    }
}
