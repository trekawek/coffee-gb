package eu.rekawek.coffeegb.swing.packaging;

import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Bounds and validates classic ZIP central-directory metadata before an eager ZIP reader opens it.
 *
 * <p>Commons Compress and {@code JarFile} both materialize the complete central directory during
 * construction. Container-size and post-open entry limits therefore cannot prevent a tiny-entry
 * metadata bomb. This preflight rejects ZIP64, multidisk archives, inconsistent EOCD fields, and
 * oversized or malformed central directories while streaming fixed-size headers without retaining
 * entry names.
 */
final class BoundedZipPreflight {

    private static final int EOCD_SIGNATURE = 0x06054b50;
    private static final int ZIP64_EOCD_LOCATOR_SIGNATURE = 0x07064b50;
    private static final int CENTRAL_HEADER_SIGNATURE = 0x02014b50;
    private static final int EOCD_BYTES = 22;
    private static final int MAX_COMMENT_BYTES = 65_535;
    private static final int CENTRAL_HEADER_BYTES = 46;
    private static final long MAX_CENTRAL_DIRECTORY_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_EOCD_CANDIDATES = 128;

    private BoundedZipPreflight() {
    }

    static void verify(
            Path archive,
            long maximumArchiveBytes,
            int maximumEntries,
            String description)
            throws IOException {
        if (Files.isSymbolicLink(archive)
                || !Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    description + " is not a regular non-symlink ZIP file: " + archive);
        }
        try (SeekableByteChannel channel = Files.newByteChannel(archive, StandardOpenOption.READ)) {
            verify(channel, maximumArchiveBytes, maximumEntries, description);
        }
    }

    static void verify(
            byte[] archive,
            long maximumArchiveBytes,
            int maximumEntries,
            String description)
            throws IOException {
        try (SeekableByteChannel channel = new SeekableInMemoryByteChannel(archive)) {
            verify(channel, maximumArchiveBytes, maximumEntries, description);
        }
    }

    private static void verify(
            SeekableByteChannel archive,
            long maximumArchiveBytes,
            int maximumEntries,
            String description)
            throws IOException {
        if (maximumArchiveBytes <= 0 || maximumEntries <= 0) {
            throw new IllegalArgumentException("ZIP preflight bounds must be positive");
        }
        long archiveBytes = archive.size();
        if (archiveBytes < EOCD_BYTES || archiveBytes > maximumArchiveBytes) {
            throw new IOException(description + " has invalid bounded ZIP size " + archiveBytes);
        }
        int tailBytes = (int) Math.min(archiveBytes, EOCD_BYTES + (long) MAX_COMMENT_BYTES);
        byte[] tail = new byte[tailBytes];
        readFully(archive, archiveBytes - tailBytes, ByteBuffer.wrap(tail), description);

        IOException candidateFailure = null;
        ScanBudget scanBudget = new ScanBudget(maximumEntries);
        for (int index = tail.length - EOCD_BYTES; index >= 0; index--) {
            if (unsignedInt(tail, index) != EOCD_SIGNATURE) {
                continue;
            }
            scanBudget.visitCandidate(description);
            long eocdOffset = archiveBytes - tailBytes + index;
            try {
                verifyCandidate(
                        archive,
                        archiveBytes,
                        tail,
                        index,
                        eocdOffset,
                        maximumEntries,
                        scanBudget,
                        description);
                return;
            } catch (IOException invalid) {
                candidateFailure = invalid;
            }
        }
        throw new IOException(
                description + " has no valid bounded classic ZIP end record",
                candidateFailure);
    }

    private static void verifyCandidate(
            SeekableByteChannel archive,
            long archiveBytes,
            byte[] tail,
            int index,
            long eocdOffset,
            int maximumEntries,
            ScanBudget scanBudget,
            String description)
            throws IOException {
        int commentBytes = unsignedShort(tail, index + 20);
        if (eocdOffset + EOCD_BYTES + commentBytes != archiveBytes) {
            throw new IOException(description + " has trailing or inconsistent ZIP data");
        }
        int disk = unsignedShort(tail, index + 4);
        int centralDisk = unsignedShort(tail, index + 6);
        int diskEntries = unsignedShort(tail, index + 8);
        int totalEntries = unsignedShort(tail, index + 10);
        long centralBytes = unsignedInt(tail, index + 12);
        long centralOffset = unsignedInt(tail, index + 16);
        if (disk == 0xffff
                || centralDisk == 0xffff
                || diskEntries == 0xffff
                || totalEntries == 0xffff
                || centralBytes == 0xffffffffL
                || centralOffset == 0xffffffffL) {
            throw new IOException(description + " uses unsupported ZIP64 metadata");
        }
        if (disk != 0 || centralDisk != 0 || diskEntries != totalEntries) {
            throw new IOException(description + " uses unsupported multidisk ZIP metadata");
        }
        if (hasSignatureAt(
                archive,
                eocdOffset - 20,
                ZIP64_EOCD_LOCATOR_SIGNATURE,
                description)) {
            throw new IOException(description + " uses unsupported ZIP64 metadata");
        }
        if (totalEntries > maximumEntries) {
            throw new IOException(
                    description + " exceeds " + maximumEntries + " central-directory entries");
        }
        long perEntryMaximum = CENTRAL_HEADER_BYTES + 3L * 0xffffL;
        long boundedCentralBytes = Math.min(
                MAX_CENTRAL_DIRECTORY_BYTES,
                Math.multiplyExact(maximumEntries, perEntryMaximum));
        if (centralBytes > boundedCentralBytes
                || centralBytes < (long) totalEntries * CENTRAL_HEADER_BYTES
                || centralOffset > eocdOffset
                || centralOffset + centralBytes != eocdOffset) {
            throw new IOException(description + " has invalid bounded central-directory geometry");
        }

        long cursor = centralOffset;
        long centralEnd = centralOffset + centralBytes;
        int actualEntries = 0;
        ByteBuffer header = ByteBuffer.allocate(CENTRAL_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        while (cursor < centralEnd) {
            scanBudget.visitCentralHeader(description);
            if (centralEnd - cursor < CENTRAL_HEADER_BYTES) {
                throw new IOException(description + " has a truncated central-directory header");
            }
            header.clear();
            readFully(archive, cursor, header, description);
            header.flip();
            if (header.getInt() != CENTRAL_HEADER_SIGNATURE) {
                throw new IOException(description + " has an invalid central-directory signature");
            }
            int nameBytes = Short.toUnsignedInt(header.getShort(28));
            int extraBytes = Short.toUnsignedInt(header.getShort(30));
            int entryCommentBytes = Short.toUnsignedInt(header.getShort(32));
            int startDisk = Short.toUnsignedInt(header.getShort(34));
            if (startDisk != 0) {
                throw new IOException(description + " uses unsupported multidisk ZIP entries");
            }
            long recordBytes = CENTRAL_HEADER_BYTES
                    + (long) nameBytes
                    + extraBytes
                    + entryCommentBytes;
            if (recordBytes > centralEnd - cursor) {
                throw new IOException(description + " has a truncated central-directory record");
            }
            cursor += recordBytes;
            actualEntries++;
            if (actualEntries > maximumEntries) {
                throw new IOException(
                        description
                                + " exceeds "
                                + maximumEntries
                                + " actual central-directory entries");
            }
        }
        if (cursor != centralEnd || actualEntries != totalEntries) {
            throw new IOException(
                    description + " has inconsistent central-directory entry metadata");
        }
    }

    private static final class ScanBudget {
        private final long maximumHeaderVisits;
        private int candidates;
        private long headerVisits;

        private ScanBudget(int maximumEntries) {
            maximumHeaderVisits = Math.addExact(
                    Math.multiplyExact((long) maximumEntries, 4L), 1024L);
        }

        private void visitCandidate(String description) throws IOException {
            if (++candidates > MAX_EOCD_CANDIDATES) {
                throw new IOException(description + " exceeds the ZIP end-record scan budget");
            }
        }

        private void visitCentralHeader(String description) throws IOException {
            if (++headerVisits > maximumHeaderVisits) {
                throw new IOException(
                        description + " exceeds the aggregate central-header scan budget");
            }
        }
    }

    private static boolean hasSignatureAt(
            SeekableByteChannel archive,
            long offset,
            int signature,
            String description)
            throws IOException {
        if (offset < 0) {
            return false;
        }
        ByteBuffer bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        readFully(archive, offset, bytes, description);
        bytes.flip();
        return bytes.getInt() == signature;
    }

    private static void readFully(
            SeekableByteChannel source,
            long offset,
            ByteBuffer destination,
            String description)
            throws IOException {
        source.position(offset);
        while (destination.hasRemaining()) {
            int read = source.read(destination);
            if (read <= 0) {
                throw new IOException(description + " ended while reading ZIP metadata");
            }
        }
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static long unsignedInt(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(
                (bytes[offset] & 0xff)
                        | ((bytes[offset + 1] & 0xff) << 8)
                        | ((bytes[offset + 2] & 0xff) << 16)
                        | ((bytes[offset + 3] & 0xff) << 24));
    }
}
