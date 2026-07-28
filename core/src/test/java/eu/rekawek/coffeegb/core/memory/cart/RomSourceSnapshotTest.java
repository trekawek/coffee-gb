package eu.rekawek.coffeegb.core.memory.cart;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RomSourceSnapshotTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void loadsMixedCaseDirectExtensionFromOneImmutableImage() throws Exception {
        File source = temporaryFolder.newFile("game.GbC");
        byte[] bytes = syntheticRom("DIRECT", 0x41);
        Files.write(source.toPath(), bytes);

        try (RomSourceSnapshot snapshot = RomSourceSnapshot.open(source.toPath())) {
            RomImage image = snapshot.loadSingle();

            assertEquals(source.toPath().toAbsolutePath().normalize(), snapshot.sourcePath());
            assertEquals(RomOrigin.Kind.DIRECT_FILE, image.origin().kind());
            assertArrayEquals(bytes, image.bytes());
        }
    }

    @Test
    public void rejectsInvalidHeaderWithTypedReason() throws Exception {
        File source = temporaryFolder.newFile("junk.gb");
        Files.write(source.toPath(), new byte[0x8000]);

        RomSourceException failure =
                assertThrows(
                        RomSourceException.class,
                        () -> RomSourceSnapshot.open(source.toPath()));

        assertEquals(RomSourceException.Reason.INVALID_HEADER, failure.reason());
    }

    @Test
    public void rejectsOversizedDirectRomWithTypedReasonBeforeAllocatingIt() throws Exception {
        File source = temporaryFolder.newFile("huge.gb");
        try (RandomAccessFile output = new RandomAccessFile(source, "rw")) {
            output.setLength((long) RomImage.MAX_ROM_BYTES + 1);
        }

        RomSourceException failure =
                assertThrows(
                        RomSourceException.class,
                        () -> RomSourceSnapshot.open(source.toPath()));

        assertEquals(RomSourceException.Reason.ROM_TOO_LARGE, failure.reason());
    }

    @Test
    public void directSnapshotCopyCanBeCancelledBetweenChunks() throws Exception {
        File source = temporaryFolder.newFile("cancel.gb");
        Files.write(source.toPath(), syntheticRom("CANCEL", 0x61));
        AtomicBoolean cancelled = new AtomicBoolean();

        assertThrows(
                CancellationException.class,
                () ->
                        RomSourceSnapshot.open(
                                source.toPath(),
                                cancelled::get,
                                copied -> cancelled.set(copied > 0)));
    }

    @Test
    public void rejectsSevenZBeforeOpeningThePath() {
        RomSourceException failure =
                assertThrows(
                        RomSourceException.class,
                        () ->
                                RomSourceSnapshot.open(
                                        temporaryFolder.getRoot().toPath().resolve("missing.7Z")));

        assertEquals(RomSourceException.Reason.UNSUPPORTED_SEVEN_Z, failure.reason());
    }

    @Test
    public void listsValidArchiveCandidatesAndLoadsTheExactSelection() throws Exception {
        File source = temporaryFolder.newFile("games.ZIP");
        byte[] first = syntheticRom("FIRST", 0x11);
        byte[] second = syntheticRom("SECOND", 0x22);
        writeZip(
                source,
                new Entry("readme.txt", "hello".getBytes(StandardCharsets.UTF_8)),
                new Entry("one/game.GB", first),
                new Entry("two/game.gbc", second));

        try (RomSourceSnapshot snapshot = RomSourceSnapshot.open(source.toPath())) {
            List<RomSourceSnapshot.ArchiveCandidate> candidates = snapshot.candidates();

            assertEquals(2, candidates.size());
            assertEquals("one/game.GB", candidates.get(0).entryName());
            assertEquals("two/game.gbc", candidates.get(1).entryName());
            RomImage image = snapshot.load(candidates.get(1).token());
            assertArrayEquals(second, image.bytes());
            assertEquals("two/game.gbc", image.origin().archiveEntry().orElseThrow());
            assertEquals(0, image.origin().archiveEntryOccurrence());
        }
    }

    @Test
    public void pathReplacementAfterInventoryCannotChangeSelectedBytes() throws Exception {
        File source = temporaryFolder.newFile("replace.zip");
        byte[] original = syntheticRom("ORIGINAL", 0x33);
        writeZip(source, new Entry("game.gb", original));

        try (RomSourceSnapshot snapshot = RomSourceSnapshot.open(source.toPath())) {
            byte[] replacement = syntheticRom("REPLACED", 0x44);
            writeZip(source, new Entry("game.gb", replacement));

            RomImage image = snapshot.loadSingle();
            assertArrayEquals(original, image.bytes());
            assertEquals(
                    source.toPath().toAbsolutePath().normalize(),
                    image.origin().containerPath().orElseThrow());
        }
    }

    @Test
    public void duplicateEntryNamesRetainExactOccurrenceIdentity() throws Exception {
        File source = temporaryFolder.newFile("duplicates.zip");
        byte[] first = syntheticRom("FIRST", 0x71);
        byte[] second = syntheticRom("SECOND", 0x72);
        writeZipAllowingDuplicates(
                source,
                new Entry("game.gb", first),
                new Entry("game.gb", second));

        try (RomSourceSnapshot snapshot = RomSourceSnapshot.open(source.toPath())) {
            List<RomSourceSnapshot.ArchiveCandidate> candidates = snapshot.candidates();

            assertEquals(2, candidates.size());
            assertEquals(0, candidates.get(0).entryOccurrence());
            assertEquals(1, candidates.get(1).entryOccurrence());
            RomImage image = snapshot.load(candidates.get(1).token());
            assertArrayEquals(second, image.bytes());
            assertEquals(1, image.origin().archiveEntryOccurrence());
        }
    }

    @Test
    public void unsafeEntryRejectsTheWholeArchiveBeforeSelection() throws Exception {
        File source = temporaryFolder.newFile("unsafe.zip");
        writeZip(
                source,
                new Entry("../escape.txt", new byte[] {1}),
                new Entry("game.gb", syntheticRom("SAFE", 0x55)));

        RomSourceException failure =
                assertThrows(
                        RomSourceException.class,
                        () -> RomSourceSnapshot.open(source.toPath()));

        assertEquals(RomSourceException.Reason.UNSAFE_ARCHIVE_ENTRY, failure.reason());
    }

    @Test
    public void cancellationDuringContainerCopyDoesNotPublishASnapshot() throws Exception {
        File source = temporaryFolder.newFile("cancel.zip");
        writeZip(source, new Entry("game.gb", syntheticRom("CANCEL", 0x66)));
        int before =
                temporarySnapshotCount();

        assertThrows(
                java.util.concurrent.CancellationException.class,
                () -> RomSourceSnapshot.open(source.toPath(), () -> true, ignored -> {}));

        assertEquals(before, temporarySnapshotCount());
    }

    private int temporarySnapshotCount() throws IOException {
        File directory = new File(System.getProperty("java.io.tmpdir"));
        String[] names =
                directory.list(
                        (ignored, name) ->
                                name.startsWith("coffee-gb-rom-snapshot-")
                                        && name.endsWith(".zip"));
        return names == null ? 0 : names.length;
    }

    private static byte[] syntheticRom(String title, int marker) {
        byte[] rom = new byte[0x8000];
        byte[] titleBytes = title.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(titleBytes, 0, rom, 0x134, titleBytes.length);
        rom[0x147] = 0;
        rom[0x148] = 0;
        rom[0x149] = 0;
        rom[0x200] = (byte) marker;
        int checksum = 0;
        for (int address = 0x134; address <= 0x14c; address++) {
            checksum = (checksum - (rom[address] & 0xff) - 1) & 0xff;
        }
        rom[0x14d] = (byte) checksum;
        assertTrue(RomHeaderInspector.inspect(rom).hasCartridgeShape());
        return rom;
    }

    private static void writeZip(File target, Entry... entries) throws IOException {
        try (ZipOutputStream output =
                new ZipOutputStream(
                        new BufferedOutputStream(Files.newOutputStream(target.toPath())))) {
            for (Entry entry : entries) {
                output.putNextEntry(new ZipEntry(entry.name));
                output.write(entry.bytes);
                output.closeEntry();
            }
        }
    }

    private static void writeZipAllowingDuplicates(File target, Entry... entries)
            throws IOException {
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(target)) {
            for (Entry entry : entries) {
                output.putArchiveEntry(new ZipArchiveEntry(entry.name));
                output.write(entry.bytes);
                output.closeArchiveEntry();
            }
        }
    }

    private record Entry(String name, byte[] bytes) {}
}
