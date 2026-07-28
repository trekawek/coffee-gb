package eu.rekawek.coffeegb.core.memory.cart;

import org.apache.commons.compress.MemoryLimitException;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tukaani.xz.LZMA2Options;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RomArchiveTest {

    private static final byte[] ZERO_BUFFER = new byte[64 * 1024];

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
        assertEquals("games/game.GBC", rom.getOrigin().archiveEntry().orElseThrow());
        assertArrayEquals(romBytes, rom.getImage().bytes());
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

    @Test
    public void shouldRejectArchiveContainerLargerThanSafetyLimit() throws Exception {
        File archive = temporaryFolder.newFile("oversized.zip");
        try (RandomAccessFile sparseArchive = new RandomAccessFile(archive, "rw")) {
            sparseArchive.setLength(Rom.MAX_ARCHIVE_CONTAINER_BYTES + 1);
        }

        IOException exception = assertThrows(IOException.class, () -> new Rom(archive));

        assertEquals(
                "Archive exceeds the "
                        + Rom.MAX_ARCHIVE_CONTAINER_BYTES
                        + "-byte compressed-size safety limit",
                exception.getMessage());
    }

    @Test
    public void shouldRejectArchiveWithTooManyEntries() throws Exception {
        File archive = temporaryFolder.newFile("too-many-entries.zip");
        writeZipWithEmptyEntries(archive, Rom.MAX_ARCHIVE_ENTRIES + 1);

        IOException exception = assertThrows(IOException.class, () -> new Rom(archive));

        assertEquals(
                "Archive exceeds the " + Rom.MAX_ARCHIVE_ENTRIES + "-entry safety limit",
                exception.getMessage());
    }

    @Test
    public void shouldRejectDeclaredEntryCountBeforeZipParserTouchesCentralDirectory()
            throws Exception {
        File archive = temporaryFolder.newFile("hostile-count.zip");
        writeZip(archive, new ArchiveFile("game.gb", createRom("COUNT")));
        corruptCentralDirectoryAndDeclareEntryCount(
                archive, Rom.MAX_ARCHIVE_ENTRIES + 1);

        IOException exception = assertThrows(IOException.class, () -> new Rom(archive));

        assertEquals(
                "Archive exceeds the " + Rom.MAX_ARCHIVE_ENTRIES + "-entry safety limit",
                exception.getMessage());
    }

    @Test
    public void shouldPreflightZip64DeclaredEntryCountBeforeZipParser() throws Exception {
        File archive = temporaryFolder.newFile("hostile-zip64-count.zip");
        writeZip(archive, new ArchiveFile("game.gb", createRom("ZIP64")));
        convertEndRecordToZip64WithDeclaredEntryCount(
                archive, Rom.MAX_ARCHIVE_ENTRIES + 1, true);

        IOException exception = assertThrows(IOException.class, () -> new Rom(archive));

        assertEquals(
                "Archive exceeds the " + Rom.MAX_ARCHIVE_ENTRIES + "-entry safety limit",
                exception.getMessage());
    }

    @Test
    public void shouldLoadRomThroughZip64MetadataPreflight() throws Exception {
        File archive = temporaryFolder.newFile("game-zip64.zip");
        writeZip(archive, new ArchiveFile("game.gb", createRom("ZIP64")));
        convertEndRecordToZip64WithDeclaredEntryCount(archive, 1, false);

        Rom rom = new Rom(archive);

        assertEquals("ZIP64", rom.getTitle());
        assertEquals("game.gb", rom.getOrigin().archiveEntry().orElseThrow());
    }

    @Test
    public void shouldRejectRepeatedCentralDirectoryDigitalSignaturesWithoutScanningThem()
            throws Exception {
        File archive = temporaryFolder.newFile("hostile-digital-signatures.zip");
        writeZip(archive, new ArchiveFile("game.gb", createRom("SIGNATURES")));
        appendCentralDirectoryDigitalSignatures(archive, 10_000);

        IOException exception = assertThrows(IOException.class, () -> new Rom(archive));

        assertEquals(
                "Invalid ZIP archive: central directory contains repeated trailing metadata",
                exception.getMessage());
    }

    @Test
    public void shouldRejectAggregateDeclaredUncompressedSizeAboveSafetyLimit() throws Exception {
        File archive = temporaryFolder.newFile("aggregate-too-large.zip");
        long halfLimit = Rom.MAX_ARCHIVE_UNCOMPRESSED_BYTES / 2;
        writeZipWithZeros(
                archive,
                new ZeroArchiveFile("first.dat", halfLimit),
                new ZeroArchiveFile("second.dat", halfLimit),
                new ZeroArchiveFile("excess.dat", 1));

        IOException exception = assertThrows(IOException.class, () -> new Rom(archive));

        assertEquals(
                "Archive exceeds the "
                        + Rom.MAX_ARCHIVE_UNCOMPRESSED_BYTES
                        + "-byte uncompressed-size safety limit",
                exception.getMessage());
    }

    @Test
    public void shouldRejectSelectedRomAboveSafetyLimit() throws Exception {
        File archive = temporaryFolder.newFile("rom-too-large.zip");
        writeZipWithZeros(
                archive,
                new ZeroArchiveFile("oversized.gb", (long) RomImage.MAX_ROM_BYTES + 1));

        RomImage.RomSizeLimitException exception =
                assertThrows(RomImage.RomSizeLimitException.class, () -> new Rom(archive));

        assertEquals((long) RomImage.MAX_ROM_BYTES + 1, exception.observedBytes());
        assertEquals(RomImage.MAX_ROM_BYTES, exception.limitBytes());
    }

    @Test
    public void shouldRejectUnknownDeclaredUncompressedSize() {
        IOException exception =
                assertThrows(
                        IOException.class,
                        () -> Rom.checkedArchiveSize(0, -1, false));

        assertEquals("Archive entry has an unknown uncompressed size", exception.getMessage());
    }

    @Test
    public void shouldRejectUnsafeSelectedArchiveEntryNames() throws Exception {
        String[] unsafeNames = {"../escape.gb", "/absolute.gb", "C:/drive.gb"};
        String[] expectedMessages = {
                "Archive entry contains a traversal component",
                "Archive entry must be a relative safe path",
                "Archive entry must be a relative safe path"
        };
        for (int i = 0; i < unsafeNames.length; i++) {
            File archive = temporaryFolder.newFile("unsafe-" + i + ".zip");
            writeZip(archive, new ArchiveFile(unsafeNames[i], createRom("UNSAFE")));

            IllegalArgumentException exception =
                    assertThrows(IllegalArgumentException.class, () -> new Rom(archive));

            assertEquals(expectedMessages[i], exception.getMessage());
        }
    }

    @Test
    public void shouldEnforceSevenZParserAndDecoderMemoryLimit() throws Exception {
        File archive = temporaryFolder.newFile("large-dictionary.7z");
        LZMA2Options options = new LZMA2Options();
        options.setMode(LZMA2Options.MODE_UNCOMPRESSED);
        options.setDictSize((Rom.MAX_SEVEN_Z_MEMORY_KIB + 8 * 1024) * 1024);
        writeSevenZ(
                archive,
                List.of(new SevenZMethodConfiguration(SevenZMethod.LZMA2, options)),
                new ArchiveFile("game.gb", createRom("MEMORY")));

        MemoryLimitException exception =
                assertThrows(MemoryLimitException.class, () -> new Rom(archive));

        assertEquals(Rom.MAX_SEVEN_Z_MEMORY_KIB, exception.getMemoryLimitInKb());
        assertTrue(exception.getMemoryNeededInKb() > exception.getMemoryLimitInKb());
    }

    private static byte[] createRom(String title) {
        byte[] rom = new byte[0x8000];
        byte[] titleBytes = title.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(titleBytes, 0, rom, 0x0134, titleBytes.length);
        return rom;
    }

    private static void writeSevenZ(File archive, ArchiveFile... files) throws IOException {
        writeSevenZ(
                archive,
                List.of(new SevenZMethodConfiguration(SevenZMethod.LZMA2)),
                files);
    }

    private static void writeSevenZ(
            File archive,
            Iterable<? extends SevenZMethodConfiguration> methods,
            ArchiveFile... files)
            throws IOException {
        try (SevenZOutputFile output = new SevenZOutputFile(archive)) {
            output.setContentMethods(methods);
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

    private static void corruptCentralDirectoryAndDeclareEntryCount(
            File archive, int declaredCount) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(archive, "rw")) {
            long endOffset = file.length() - 22;
            file.seek(endOffset + 16);
            long centralOffset = readUnsignedIntLittleEndian(file);
            file.seek(endOffset + 8);
            writeShortLittleEndian(file, declaredCount);
            writeShortLittleEndian(file, declaredCount);
            file.seek(centralOffset);
            file.writeInt(0);
        }
    }

    private static void appendCentralDirectoryDigitalSignatures(File archive, int count)
            throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(archive, "rw")) {
            long classicEndOffset = file.length() - 22;
            byte[] classicEnd = new byte[22];
            file.seek(classicEndOffset);
            file.readFully(classicEnd);
            long centralSize = unsignedIntLittleEndian(classicEnd, 12);

            file.seek(classicEndOffset);
            for (int i = 0; i < count; i++) {
                writeIntLittleEndian(file, 0x05054b50);
                writeShortLittleEndian(file, 0);
            }
            classicEnd[12] = (byte) (centralSize + count * 6L);
            classicEnd[13] = (byte) ((centralSize + count * 6L) >>> 8);
            classicEnd[14] = (byte) ((centralSize + count * 6L) >>> 16);
            classicEnd[15] = (byte) ((centralSize + count * 6L) >>> 24);
            file.write(classicEnd);
        }
    }

    private static void convertEndRecordToZip64WithDeclaredEntryCount(
            File archive, int declaredCount, boolean corruptCentralDirectory)
            throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(archive, "rw")) {
            long classicEndOffset = file.length() - 22;
            byte[] classicEnd = new byte[22];
            file.seek(classicEndOffset);
            file.readFully(classicEnd);
            long centralSize = unsignedIntLittleEndian(classicEnd, 12);
            long centralOffset = unsignedIntLittleEndian(classicEnd, 16);

            if (corruptCentralDirectory) {
                file.seek(centralOffset);
                file.writeInt(0);
            }
            file.seek(classicEndOffset);
            writeIntLittleEndian(file, 0x06064b50);
            writeLongLittleEndian(file, 44);
            writeShortLittleEndian(file, 45);
            writeShortLittleEndian(file, 45);
            writeIntLittleEndian(file, 0);
            writeIntLittleEndian(file, 0);
            writeLongLittleEndian(file, declaredCount);
            writeLongLittleEndian(file, declaredCount);
            writeLongLittleEndian(file, centralSize);
            writeLongLittleEndian(file, centralOffset);
            writeIntLittleEndian(file, 0x07064b50);
            writeIntLittleEndian(file, 0);
            writeLongLittleEndian(file, classicEndOffset);
            writeIntLittleEndian(file, 1);

            classicEnd[8] = (byte) 0xff;
            classicEnd[9] = (byte) 0xff;
            classicEnd[10] = (byte) 0xff;
            classicEnd[11] = (byte) 0xff;
            file.write(classicEnd);
        }
    }

    private static long readUnsignedIntLittleEndian(RandomAccessFile file) throws IOException {
        return Integer.toUnsignedLong(Integer.reverseBytes(file.readInt()));
    }

    private static long unsignedIntLittleEndian(byte[] value, int offset) {
        return Integer.toUnsignedLong(
                (value[offset] & 0xff)
                        | (value[offset + 1] & 0xff) << 8
                        | (value[offset + 2] & 0xff) << 16
                        | (value[offset + 3] & 0xff) << 24);
    }

    private static void writeShortLittleEndian(RandomAccessFile file, int value)
            throws IOException {
        file.writeByte(value);
        file.writeByte(value >>> 8);
    }

    private static void writeIntLittleEndian(RandomAccessFile file, long value)
            throws IOException {
        file.writeByte((int) value);
        file.writeByte((int) (value >>> 8));
        file.writeByte((int) (value >>> 16));
        file.writeByte((int) (value >>> 24));
    }

    private static void writeLongLittleEndian(RandomAccessFile file, long value)
            throws IOException {
        writeIntLittleEndian(file, value);
        writeIntLittleEndian(file, value >>> 32);
    }

    private static void writeZip(File archive, ArchiveFile... files) throws IOException {
        try (ZipOutputStream output =
                new ZipOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(archive.toPath())))) {
            for (ArchiveFile file : files) {
                output.putNextEntry(new ZipEntry(file.name()));
                output.write(file.contents());
                output.closeEntry();
            }
        }
    }

    private static void writeZipWithEmptyEntries(File archive, int entryCount)
            throws IOException {
        try (ZipOutputStream output =
                new ZipOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(archive.toPath())))) {
            for (int i = 0; i < entryCount; i++) {
                output.putNextEntry(new ZipEntry("entry-" + i + ".txt"));
                output.closeEntry();
            }
        }
    }

    private static void writeZipWithZeros(File archive, ZeroArchiveFile... files)
            throws IOException {
        try (ZipOutputStream output =
                new ZipOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(archive.toPath())))) {
            output.setLevel(Deflater.BEST_SPEED);
            for (ZeroArchiveFile file : files) {
                output.putNextEntry(new ZipEntry(file.name()));
                long remaining = file.size();
                while (remaining > 0) {
                    int chunk = (int) Math.min(remaining, ZERO_BUFFER.length);
                    output.write(ZERO_BUFFER, 0, chunk);
                    remaining -= chunk;
                }
                output.closeEntry();
            }
        }
    }

    private record ArchiveFile(String name, byte[] contents) {
    }

    private record ZeroArchiveFile(String name, long size) {
    }
}
