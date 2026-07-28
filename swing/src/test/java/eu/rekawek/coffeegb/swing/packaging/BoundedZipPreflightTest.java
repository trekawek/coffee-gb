package eu.rekawek.coffeegb.swing.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public class BoundedZipPreflightTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void actualCentralDirectoryCountCannotHideBehindForgedEocdCount() {
        byte[] archive = centralDirectory(2, 1);

        assertThrows(
                java.io.IOException.class,
                () -> BoundedZipPreflight.verify(
                        archive, archive.length, 64, "forged-count ZIP"));
    }

    @Test
    public void reportedEntryLimitRejectsBeforeAnArchiveReaderOpensThePath() throws Exception {
        byte[] archive = centralDirectory(2, 2);
        Path path = temporaryFolder.newFile("too-many.zip").toPath();
        Files.write(path, archive);

        assertThrows(
                java.io.IOException.class,
                () -> BoundedZipPreflight.verify(
                        path, archive.length, 1, "entry-bomb ZIP"));
    }

    @Test
    public void forgedCentralGeometryAndUnsupportedZipModesFailPreflight() {
        byte[] forgedOffset = centralDirectory(1, 1);
        ByteBuffer.wrap(forgedOffset)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(46 + 16, 1);
        assertThrows(
                java.io.IOException.class,
                () -> BoundedZipPreflight.verify(
                        forgedOffset, forgedOffset.length, 64, "forged-offset ZIP"));

        byte[] zip64 = centralDirectory(1, 1);
        ByteBuffer.wrap(zip64)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(46 + 10, (short) 0xffff);
        assertThrows(
                java.io.IOException.class,
                () -> BoundedZipPreflight.verify(zip64, zip64.length, 64, "ZIP64 archive"));

        byte[] multidisk = centralDirectory(1, 1);
        ByteBuffer.wrap(multidisk)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(46 + 4, (short) 1);
        assertThrows(
                java.io.IOException.class,
                () -> BoundedZipPreflight.verify(
                        multidisk, multidisk.length, 64, "multidisk archive"));
    }

    @Test
    public void repeatedFakeEndRecordsCannotRescanTheCentralDirectoryWithoutBound() {
        byte[] archive = centralDirectoryWithFakeEndRecords(2, 129);

        java.io.IOException failure = assertThrows(
                java.io.IOException.class,
                () -> BoundedZipPreflight.verify(
                        archive, archive.length, 64, "repeated-candidate ZIP"));
        assertTrue(failure.getMessage().contains("scan budget"));
    }

    private static byte[] centralDirectory(int actualEntries, int reportedEntries) {
        int centralBytes = actualEntries * 46;
        ByteBuffer archive = ByteBuffer.allocate(centralBytes + 22).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < actualEntries; i++) {
            archive.putInt(0x02014b50);
            archive.position(archive.position() + 42);
        }
        archive.putInt(0x06054b50);
        archive.putShort((short) 0);
        archive.putShort((short) 0);
        archive.putShort((short) reportedEntries);
        archive.putShort((short) reportedEntries);
        archive.putInt(centralBytes);
        archive.putInt(0);
        archive.putShort((short) 0);
        return archive.array();
    }

    private static byte[] centralDirectoryWithFakeEndRecords(
            int entries, int fakeRecords) {
        int centralBytes = entries * 46;
        int commentBytes = fakeRecords * 24;
        ByteBuffer archive = ByteBuffer.allocate(centralBytes + 22 + commentBytes)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < entries; i++) {
            archive.putInt(0x02014b50);
            archive.position(archive.position() + 42);
        }
        archive.putInt(0x06054b50);
        archive.putShort((short) 0);
        archive.putShort((short) 0);
        archive.putShort((short) entries);
        archive.putShort((short) entries);
        archive.putInt(centralBytes);
        archive.putInt(0);
        archive.putShort((short) commentBytes);
        for (int candidate = 0; candidate < fakeRecords; candidate++) {
            int fakeOffset = centralBytes + 22 + candidate * 24;
            archive.position(fakeOffset);
            archive.putInt(0x06054b50);
            archive.putShort((short) 0);
            archive.putShort((short) 0);
            archive.putShort((short) entries);
            archive.putShort((short) entries);
            archive.putInt(fakeOffset);
            archive.putInt(0);
            archive.putShort((short) (archive.capacity() - fakeOffset - 22));
        }
        return archive.array();
    }
}
