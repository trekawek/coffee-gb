package eu.rekawek.coffeegb.core.memory.cart;

import org.apache.commons.io.FilenameUtils;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Immutable, bounded snapshot of one untrusted desktop ROM source.
 *
 * <p>Raw files are retained as an immutable {@link RomImage}. ZIP containers are copied from one
 * source handle into a private temporary file before any inventory or extraction. The original
 * path remains the origin and persistence anchor; the temporary path is never observable outside
 * this object.
 */
public final class RomSourceSnapshot implements Closeable {

    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private final Path sourcePath;

    private final RomImage directImage;

    private final Path zipSnapshot;

    private final List<ArchiveCandidate> candidates;

    private final int extensionCandidateCount;

    private boolean closed;

    private RomSourceSnapshot(
            Path sourcePath,
            RomImage directImage,
            Path zipSnapshot,
            List<ArchiveCandidate> candidates,
            int extensionCandidateCount) {
        this.sourcePath = sourcePath;
        this.directImage = directImage;
        this.zipSnapshot = zipSnapshot;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        this.extensionCandidateCount = extensionCandidateCount;
    }

    public static RomSourceSnapshot open(Path source) throws IOException {
        return open(source, () -> false, ignored -> {});
    }

    public static RomSourceSnapshot open(
            Path source,
            BooleanSupplier cancelled,
            LongConsumer copiedBytes) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(copiedBytes, "copiedBytes");
        Path normalized = source.toAbsolutePath().normalize();
        String extension =
                FilenameUtils.getExtension(
                                normalized.getFileName() == null
                                        ? ""
                                        : normalized.getFileName().toString())
                        .toLowerCase(Locale.ROOT);
        if ("7z".equals(extension)) {
            throw new RomSourceException(
                    RomSourceException.Reason.UNSUPPORTED_SEVEN_Z,
                    "7z archives are not opened because their metadata allocation cannot be "
                            + "bounded before parsing; extract the ROM or use ZIP");
        }
        boolean zip = "zip".equals(extension);
        if (!zip && !isRomExtension(extension)) {
            throw new RomSourceException(
                    RomSourceException.Reason.UNSUPPORTED_TYPE,
                    "Supported ROM inputs are .gb, .gbc, .rom, and .zip");
        }
        validateSourceFile(normalized);
        checkCancelled(cancelled);

        if (!zip) {
            try (FileChannel channel = FileChannel.open(normalized, StandardOpenOption.READ);
                    InputStream input = Channels.newInputStream(channel)) {
                long declaredSize = channel.size();
                RomImage image =
                        new RomImage(
                                RomOrigin.directFile(normalized),
                                readRomBytes(input, declaredSize, cancelled, copiedBytes));
                checkCancelled(cancelled);
                if (!RomHeaderInspector.inspect(image).hasCartridgeShape()) {
                    throw new RomSourceException(
                            RomSourceException.Reason.INVALID_HEADER,
                            "The file does not contain a recognizable Game Boy cartridge header");
                }
                return new RomSourceSnapshot(
                        normalized, image, null, List.of(), 0);
            } catch (RomSourceException e) {
                throw e;
            } catch (NoSuchFileException | FileNotFoundException e) {
                throw missing(normalized, e);
            } catch (IOException e) {
                throw unreadable(normalized, e);
            }
        }

        Path temporary = null;
        try {
            temporary = Files.createTempFile("coffee-gb-rom-snapshot-", ".zip");
            copyContainer(normalized, temporary, cancelled, copiedBytes);
            checkCancelled(cancelled);
            Rom.preflightZip(temporary);
            Inventory inventory = inventory(normalized, temporary, cancelled);
            return new RomSourceSnapshot(
                    normalized,
                    null,
                    temporary,
                    inventory.candidates,
                    inventory.extensionCandidateCount);
        } catch (RomSourceException e) {
            deleteSnapshot(temporary, e);
            throw e;
        } catch (NoSuchFileException | FileNotFoundException e) {
            deleteSnapshot(temporary, e);
            throw missing(normalized, e);
        } catch (ZipException e) {
            deleteSnapshot(temporary, e);
            throw new RomSourceException(
                    RomSourceException.Reason.INVALID_ARCHIVE,
                    "The ZIP archive is invalid or unsupported",
                    e);
        } catch (IllegalArgumentException e) {
            deleteSnapshot(temporary, e);
            throw new RomSourceException(
                    RomSourceException.Reason.UNSAFE_ARCHIVE_ENTRY,
                    "The ZIP archive contains an unsafe entry path",
                    e);
        } catch (IOException e) {
            deleteSnapshot(temporary, e);
            throw new RomSourceException(
                    classifyArchiveFailure(e),
                    archiveMessage(e),
                    e);
        } catch (RuntimeException e) {
            deleteSnapshot(temporary, e);
            throw e;
        }
    }

    public Path sourcePath() {
        return sourcePath;
    }

    public boolean isArchive() {
        return zipSnapshot != null;
    }

    public List<ArchiveCandidate> candidates() {
        return candidates;
    }

    public RomImage loadSingle() throws IOException {
        if (directImage != null) {
            ensureOpen();
            return directImage;
        }
        if (candidates.size() != 1) {
            throw new RomSourceException(
                    RomSourceException.Reason.INVALID_SELECTION,
                    "The archive requires an explicit ROM selection");
        }
        return load(candidates.get(0).token(), () -> false);
    }

    public RomImage load(long candidateToken) throws IOException {
        return load(candidateToken, () -> false);
    }

    public RomImage load(long candidateToken, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(cancelled, "cancelled");
        ensureOpen();
        if (directImage != null) {
            if (candidateToken != ArchiveCandidate.DIRECT_TOKEN) {
                throw invalidSelection();
            }
            return directImage;
        }
        ArchiveCandidate selected =
                candidates.stream()
                        .filter(candidate -> candidate.token() == candidateToken)
                        .findFirst()
                        .orElseThrow(RomSourceSnapshot::invalidSelection);
        checkCancelled(cancelled);
        try (ZipFile zip = new ZipFile(zipSnapshot.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            long ordinal = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (ordinal++ != selected.token()) {
                    continue;
                }
                if (!entry.getName().equals(selected.entryName())) {
                    throw invalidSelection();
                }
                checkCancelled(cancelled);
                byte[] bytes;
                try (InputStream input = zip.getInputStream(entry)) {
                    bytes = readRomBytes(input, entry.getSize(), cancelled, ignored -> {});
                }
                checkCancelled(cancelled);
                RomOrigin origin =
                        RomOrigin.archiveEntry(
                                sourcePath,
                                selected.entryName(),
                                selected.entryOccurrence(),
                                extensionCandidateCount == 1);
                RomImage image = new RomImage(origin, bytes);
                if (!RomHeaderInspector.inspect(image).hasCartridgeShape()) {
                    throw new RomSourceException(
                            RomSourceException.Reason.INVALID_HEADER,
                            "The selected entry no longer has a recognizable cartridge header");
                }
                return image;
            }
            throw invalidSelection();
        } catch (RomSourceException e) {
            throw e;
        } catch (ZipException e) {
            throw new RomSourceException(
                    RomSourceException.Reason.INVALID_ARCHIVE,
                    "The selected ZIP entry is corrupt",
                    e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        if (zipSnapshot != null) {
            Files.deleteIfExists(zipSnapshot);
        }
    }

    private synchronized void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("ROM source snapshot is closed");
        }
    }

    private static void validateSourceFile(Path source) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(source, BasicFileAttributes.class);
        } catch (NoSuchFileException | FileNotFoundException e) {
            throw missing(source, e);
        } catch (IOException e) {
            throw unreadable(source, e);
        }
        if (!attributes.isRegularFile()) {
            throw new RomSourceException(
                    RomSourceException.Reason.NOT_A_FILE,
                    "The selected path is not a regular file");
        }
    }

    private static void copyContainer(
            Path source,
            Path target,
            BooleanSupplier cancelled,
            LongConsumer copiedBytes) throws IOException {
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
                FileChannel output =
                        FileChannel.open(
                                target,
                                StandardOpenOption.WRITE,
                                StandardOpenOption.TRUNCATE_EXISTING)) {
            long declaredSize = input.size();
            if (declaredSize > Rom.MAX_ARCHIVE_CONTAINER_BYTES) {
                throw new RomSourceException(
                        RomSourceException.Reason.CONTAINER_TOO_LARGE,
                        "Archive exceeds the "
                                + Rom.MAX_ARCHIVE_CONTAINER_BYTES
                                + "-byte compressed-size safety limit");
            }
            ByteBuffer buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES);
            long total = 0;
            while (true) {
                checkCancelled(cancelled);
                buffer.clear();
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                if (read > Rom.MAX_ARCHIVE_CONTAINER_BYTES - total) {
                    throw new RomSourceException(
                            RomSourceException.Reason.CONTAINER_TOO_LARGE,
                            "Archive exceeds the "
                                    + Rom.MAX_ARCHIVE_CONTAINER_BYTES
                                    + "-byte compressed-size safety limit");
                }
                total += read;
                buffer.flip();
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                copiedBytes.accept(total);
            }
        }
    }

    private static byte[] readRomBytes(
            InputStream input,
            long declaredSize,
            BooleanSupplier cancelled,
            LongConsumer copiedBytes) throws IOException {
        if (declaredSize > RomImage.MAX_ROM_BYTES) {
            throw romTooLarge(declaredSize);
        }
        int initialSize =
                declaredSize > 0
                        ? (int) Math.min(declaredSize, COPY_BUFFER_BYTES * 4L)
                        : COPY_BUFFER_BYTES;
        ByteArrayOutputStream output = new ByteArrayOutputStream(initialSize);
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        int total = 0;
        while (true) {
            checkCancelled(cancelled);
            int read = input.read(buffer);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                int value = input.read();
                if (value < 0) {
                    break;
                }
                if (total == RomImage.MAX_ROM_BYTES) {
                    throw romTooLarge((long) total + 1);
                }
                output.write(value);
                total++;
            } else {
                if (read > RomImage.MAX_ROM_BYTES - total) {
                    throw romTooLarge((long) total + read);
                }
                output.write(buffer, 0, read);
                total += read;
            }
            copiedBytes.accept((long) total);
        }
        checkCancelled(cancelled);
        if (total < RomHeaderInspector.HEADER_LENGTH) {
            throw new RomSourceException(
                    RomSourceException.Reason.INVALID_HEADER,
                    "ROM is truncated: "
                            + total
                            + " bytes, expected at least "
                            + RomHeaderInspector.HEADER_LENGTH);
        }
        return output.toByteArray();
    }

    private static RomSourceException romTooLarge(long observedBytes) {
        return new RomSourceException(
                RomSourceException.Reason.ROM_TOO_LARGE,
                "ROM exceeds the "
                        + RomImage.MAX_ROM_BYTES
                        + "-byte safety limit (observed at least "
                        + observedBytes
                        + " bytes)");
    }

    private static Inventory inventory(
            Path sourcePath,
            Path snapshot,
            BooleanSupplier cancelled) throws IOException {
        List<ArchiveCandidate> candidates = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        int entryCount = 0;
        int extensionCandidates = 0;
        long totalSize = 0;
        try (ZipFile zip = new ZipFile(snapshot.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            long ordinal = 0;
            while (entries.hasMoreElements()) {
                checkCancelled(cancelled);
                ZipEntry entry = entries.nextElement();
                entryCount++;
                if (entryCount > Rom.MAX_ARCHIVE_ENTRIES) {
                    throw new IOException(
                            "Archive exceeds the "
                                    + Rom.MAX_ARCHIVE_ENTRIES
                                    + "-entry safety limit");
                }
                if (!entry.isDirectory()) {
                    // Constructing an origin validates exact entry spelling without extracting it.
                    RomOrigin.archiveEntry(sourcePath, entry.getName());
                    if (entry.getCompressedSize() < 0 || entry.getSize() < 0) {
                        throw new IOException("Archive entry has an unknown declared size");
                    }
                }
                totalSize = Rom.checkedArchiveSize(totalSize, entry.getSize(), entry.isDirectory());
                int occurrence = occurrences.merge(entry.getName(), 1, Integer::sum) - 1;
                if (!entry.isDirectory() && isRomEntry(entry.getName())) {
                    extensionCandidates++;
                    if (entry.getSize() >= RomHeaderInspector.HEADER_LENGTH
                            && entry.getSize() <= RomImage.MAX_ROM_BYTES) {
                        try (InputStream input = zip.getInputStream(entry)) {
                            RomHeaderInspector.Header header = RomHeaderInspector.inspect(input);
                            if (header.hasCartridgeShape()) {
                                candidates.add(
                                        new ArchiveCandidate(
                                                ordinal,
                                                entry.getName(),
                                                occurrence,
                                                entry.getSize(),
                                                header.title()));
                            }
                        }
                    }
                }
                ordinal++;
            }
        }
        if (extensionCandidates == 0) {
            throw new RomSourceException(
                    RomSourceException.Reason.NO_ROM_CANDIDATES,
                    "The ZIP archive contains no .gb, .gbc, or .rom entries");
        }
        if (candidates.isEmpty()) {
            throw new RomSourceException(
                    RomSourceException.Reason.INVALID_HEADER,
                    "No ROM entry in the ZIP contains a recognizable cartridge header");
        }
        return new Inventory(candidates, extensionCandidates);
    }

    private static boolean isRomExtension(String extension) {
        return "gb".equals(extension) || "gbc".equals(extension) || "rom".equals(extension);
    }

    private static boolean isRomEntry(String name) {
        return isRomExtension(FilenameUtils.getExtension(name).toLowerCase(Locale.ROOT));
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("ROM opening was cancelled");
        }
    }

    private static RomSourceException missing(Path source, Throwable cause) {
        return new RomSourceException(
                RomSourceException.Reason.MISSING,
                "The selected file no longer exists: " + source.getFileName(),
                cause);
    }

    private static RomSourceException unreadable(Path source, Throwable cause) {
        return new RomSourceException(
                RomSourceException.Reason.UNREADABLE,
                "The selected file cannot be read: " + source.getFileName(),
                cause);
    }

    private static RomSourceException invalidSelection() {
        return new RomSourceException(
                RomSourceException.Reason.INVALID_SELECTION,
                "The archive selection is stale or invalid");
    }

    private static RomSourceException.Reason classifyArchiveFailure(IOException failure) {
        String message = failure.getMessage();
        if (message != null
                && (message.contains("compressed-size safety limit")
                        || message.contains("entry safety limit")
                        || message.contains("uncompressed-size safety limit"))) {
            return RomSourceException.Reason.CONTAINER_TOO_LARGE;
        }
        return RomSourceException.Reason.INVALID_ARCHIVE;
    }

    private static String archiveMessage(IOException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? "The ZIP archive is invalid or unreadable"
                : message;
    }

    private static void deleteSnapshot(Path snapshot, Throwable failure) {
        if (snapshot == null) {
            return;
        }
        try {
            Files.deleteIfExists(snapshot);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    public record ArchiveCandidate(
            long token,
            String entryName,
            int entryOccurrence,
            long uncompressedBytes,
            String title) {

        public static final long DIRECT_TOKEN = -1L;

        public ArchiveCandidate {
            Objects.requireNonNull(entryName, "entryName");
            Objects.requireNonNull(title, "title");
            if (token < 0 || entryOccurrence < 0 || uncompressedBytes < 0) {
                throw new IllegalArgumentException("Archive candidate metadata is invalid");
            }
        }

        public String displayName() {
            int slash = Math.max(entryName.lastIndexOf('/'), entryName.lastIndexOf('\\'));
            return entryName.substring(slash + 1);
        }
    }

    private record Inventory(
            List<ArchiveCandidate> candidates,
            int extensionCandidateCount) {}
}
