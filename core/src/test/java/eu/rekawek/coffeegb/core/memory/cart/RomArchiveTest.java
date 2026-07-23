package eu.rekawek.coffeegb.core.memory.cart;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class RomArchiveTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldLoadRomFromSevenZArchive() throws Exception {
        File archive = temporaryFolder.newFile("game.7Z");
        byte[] romBytes = createRom("SEVENZ");
        romBytes[0x0200] = 0x42;
        writeSevenZ(archive,
                new ArchiveFile("README.txt", "not a ROM".getBytes(StandardCharsets.UTF_8)),
                new ArchiveFile("games/game.GBC", romBytes));

        Rom rom = new Rom(archive);

        assertEquals("SEVENZ", rom.getTitle());
        assertEquals(0x42, rom.getRom()[0x0200]);
        assertSame(archive, rom.getFile());
    }

    @Test
    public void shouldRejectSevenZArchiveWithoutRom() throws Exception {
        File archive = temporaryFolder.newFile("empty.7z");
        writeSevenZ(archive, new ArchiveFile("README.txt", new byte[0]));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new Rom(archive));

        assertEquals("Can't find ROM file inside the 7z.", exception.getMessage());
    }

    private static byte[] createRom(String title) {
        byte[] rom = new byte[0x8000];
        byte[] titleBytes = title.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(titleBytes, 0, rom, 0x0134, titleBytes.length);
        return rom;
    }

    private static void writeSevenZ(File archive, ArchiveFile... files) throws IOException {
        try (SevenZOutputFile output = new SevenZOutputFile(archive)) {
            output.setContentCompression(SevenZMethod.LZMA2);
            for (ArchiveFile file : files) {
                SevenZArchiveEntry entry = new SevenZArchiveEntry();
                entry.setName(file.name());
                entry.setSize(file.contents().length);
                output.putArchiveEntry(entry);
                output.write(file.contents());
                output.closeArchiveEntry();
            }
        }
    }

    private record ArchiveFile(String name, byte[] contents) {
    }
}
