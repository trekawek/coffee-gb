package eu.rekawek.coffeegb.core.memory.cart;

import org.apache.commons.compress.MemoryLimitException;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.io.FilenameUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Runs Commons Compress's 7z parser in a bounded helper JVM.
 *
 * <p>Commons Compress 1.28 applies its configured memory limit to decoders and its archive
 * statistics, but malformed headers can still allocate count-sized parsing structures before that
 * check. Isolating both inventory and extraction keeps such a failure outside the emulator JVM
 * while retaining the immutable private snapshot as the only worker input.
 */
public final class SevenZArchiveWorker {

    private static final int REPORT_MAGIC = 0x4347_375a;

    private static final int REPORT_VERSION = 1;

    private static final int WORKER_MEMORY_MIB = 192;

    private static final int WORKER_MEMORY_EXIT = 75;

    private static final int WORKER_CRASH_EXIT = 76;

    private static final long WORKER_TIMEOUT_MILLIS = 60_000;

    private static final int MAX_REPORT_BYTES = 8 * 1024 * 1024;

    private static final int MAX_STRING_BYTES = 32 * 1024;

    private static final int MAX_FAILURE_MESSAGE_BYTES = 4 * 1024;

    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private SevenZArchiveWorker() {
    }

    static Inventory inspectIsolated(Path snapshot, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(cancelled, "cancelled");
        try (TemporaryFiles temporaryFiles = new TemporaryFiles()) {
            Path report = temporaryFiles.create(".report");
            WorkerReport result = invoke(Action.INVENTORY, snapshot, report, null, -1, cancelled);
            throwIfFailed(result);
            if (result.inventory == null) {
                throw invalidWorkerReport("inventory is missing");
            }
            return result.inventory;
        }
    }

    static byte[] extractIsolated(
            Path snapshot,
            long candidateToken,
            String expectedEntryName,
            long expectedSize,
            BooleanSupplier cancelled)
            throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(expectedEntryName, "expectedEntryName");
        Objects.requireNonNull(cancelled, "cancelled");
        try (TemporaryFiles temporaryFiles = new TemporaryFiles()) {
            Path report = temporaryFiles.create(".report");
            Path output = temporaryFiles.create(".rom");
            WorkerReport result =
                    invoke(
                            Action.EXTRACT,
                            snapshot,
                            report,
                            output,
                            candidateToken,
                            cancelled);
            throwIfFailed(result);
            Entry extracted = result.extracted;
            if (extracted == null
                    || extracted.token != candidateToken
                    || !extracted.entryName.equals(expectedEntryName)
                    || extracted.uncompressedBytes != expectedSize) {
                throw invalidWorkerReport("extracted entry identity does not match the selection");
            }
            return readExtractedRom(output, expectedSize, cancelled);
        }
    }

    public static void main(String[] args) {
        try {
            runWorker(args);
        } catch (OutOfMemoryError failure) {
            Runtime.getRuntime().halt(WORKER_MEMORY_EXIT);
        } catch (Throwable failure) {
            Runtime.getRuntime().halt(WORKER_CRASH_EXIT);
        }
    }

    private static void runWorker(String[] args) throws IOException {
        if (args.length < 3) {
            throw new IOException("Missing 7z worker arguments");
        }
        Action action = Action.fromId(args[0]);
        Path snapshot = Paths.get(args[1]);
        Path report = Paths.get(args[2]);
        requireExistingWorkerFile(snapshot, "snapshot");
        requireExistingWorkerFile(report, "report");

        try {
            switch (action) {
                case INVENTORY -> writeInventoryReport(report, inspectInProcess(snapshot));
                case EXTRACT -> {
                    if (args.length != 5) {
                        throw new IOException("Invalid 7z extraction arguments");
                    }
                    Path output = Paths.get(args[3]);
                    requireExistingWorkerFile(output, "output");
                    long candidateToken = Long.parseLong(args[4]);
                    Entry extracted = extractInProcess(snapshot, output, candidateToken);
                    writeExtractionReport(report, extracted);
                }
            }
        } catch (Exception failure) {
            writeFailureReport(
                    report, action, classifyFailure(failure), failureMessage(failure));
        }
    }

    private static Inventory inspectInProcess(Path snapshot) throws IOException {
        List<RawCandidate> candidates = new ArrayList<>();
        Map<String, Integer> occurrences = new HashMap<>();
        int entryCount = 0;
        int extensionCandidates = 0;
        int oversizedRomCandidates = 0;
        long totalSize = 0;
        long reportBytes = 0;

        try (SevenZFile archive = openArchive(snapshot)) {
            long ordinal = 0;
            for (SevenZArchiveEntry entry : archive.getEntries()) {
                entryCount = checkedEntryCount(entryCount);
                String name = requireEntryName(entry);
                reportBytes = checkedReportBytes(reportBytes, name);
                validateEntryName(snapshot, name);
                totalSize =
                        Rom.checkedArchiveSize(totalSize, entry.getSize(), entry.isDirectory());
                int occurrence = occurrences.merge(name, 1, Integer::sum) - 1;
                if (!entry.isDirectory() && isRomEntry(name)) {
                    extensionCandidates++;
                    if (entry.getSize() > RomImage.MAX_ROM_BYTES) {
                        oversizedRomCandidates++;
                    } else if (entry.getSize() >= RomHeaderInspector.HEADER_LENGTH) {
                        candidates.add(new RawCandidate(ordinal, entry, occurrence));
                    }
                }
                ordinal++;
            }

            List<Entry> inspected = new ArrayList<>(candidates.size());
            for (RawCandidate candidate : candidates) {
                try (InputStream input = archive.getInputStream(candidate.archiveEntry)) {
                    RomHeaderInspector.Header header = RomHeaderInspector.inspect(input);
                    String title = header.hasCartridgeShape() ? header.title() : "";
                    inspected.add(
                            new Entry(
                                    candidate.token,
                                    candidate.archiveEntry.getName(),
                                    candidate.entryOccurrence,
                                    candidate.archiveEntry.getSize(),
                                    title));
                }
            }

            if (extensionCandidates == 0) {
                throw new RomSourceException(
                        RomSourceException.Reason.NO_ROM_CANDIDATES,
                        "The 7z archive contains no .gb, .gbc, or .rom entries");
            }
            if (inspected.isEmpty()) {
                if (oversizedRomCandidates > 0) {
                    throw romTooLarge(RomImage.MAX_ROM_BYTES + 1L);
                }
                throw new RomSourceException(
                        RomSourceException.Reason.INVALID_HEADER,
                        "No ROM entry in the 7z archive is large enough to contain a cartridge header");
            }
            return new Inventory(inspected, extensionCandidates);
        }
    }

    private static Entry extractInProcess(Path snapshot, Path output, long candidateToken)
            throws IOException {
        Inventory inventory = inspectInProcess(snapshot);
        Entry selected =
                inventory.entries.stream()
                        .filter(candidate -> candidate.token == candidateToken)
                        .findFirst()
                        .orElseThrow(SevenZArchiveWorker::invalidSelection);

        try (SevenZFile archive = openArchive(snapshot)) {
            SevenZArchiveEntry archiveEntry = null;
            long ordinal = 0;
            for (SevenZArchiveEntry entry : archive.getEntries()) {
                if (ordinal++ == selected.token) {
                    archiveEntry = entry;
                    break;
                }
            }
            if (archiveEntry == null
                    || !selected.entryName.equals(archiveEntry.getName())
                    || selected.uncompressedBytes != archiveEntry.getSize()) {
                throw invalidSelection();
            }
            try (InputStream input = archive.getInputStream(archiveEntry);
                    OutputStream destination =
                            Files.newOutputStream(
                                    output,
                                    StandardOpenOption.WRITE,
                                    StandardOpenOption.TRUNCATE_EXISTING,
                                    LinkOption.NOFOLLOW_LINKS)) {
                copyExactRom(input, destination, selected.uncompressedBytes);
            }
            return selected;
        }
    }

    private static SevenZFile openArchive(Path snapshot) throws IOException {
        FileChannel channel =
                FileChannel.open(
                        snapshot, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            long size = channel.size();
            if (size > Rom.MAX_ARCHIVE_CONTAINER_BYTES) {
                throw new RomSourceException(
                        RomSourceException.Reason.CONTAINER_TOO_LARGE,
                        "Archive exceeds the "
                                + Rom.MAX_ARCHIVE_CONTAINER_BYTES
                                + "-byte compressed-size safety limit");
            }
            return SevenZFile.builder()
                    .setSeekableByteChannel(channel)
                    .setDefaultName(snapshot.getFileName().toString())
                    .setMaxMemoryLimitKiB(Rom.MAX_SEVEN_Z_MEMORY_KIB)
                    .get();
        } catch (IOException | RuntimeException | Error failure) {
            try {
                channel.close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static WorkerReport invoke(
            Action action,
            Path snapshot,
            Path report,
            Path output,
            long candidateToken,
            BooleanSupplier cancelled)
            throws IOException {
        String classPath = System.getProperty("java.class.path");
        if (classPath == null || classPath.isBlank()) {
            throw new RomSourceException(
                    RomSourceException.Reason.INVALID_ARCHIVE,
                    "The 7z helper class path is unavailable");
        }
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-Xms16m");
        command.add("-Xmx" + WORKER_MEMORY_MIB + "m");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-cp");
        command.add(classPath);
        command.add(SevenZArchiveWorker.class.getName());
        command.add(action.id);
        command.add(snapshot.toString());
        command.add(report.toString());
        if (action == Action.EXTRACT) {
            command.add(Objects.requireNonNull(output, "output").toString());
            command.add(Long.toString(candidateToken));
        }

        Process process;
        try {
            ProcessBuilder builder =
                    new ProcessBuilder(command)
                            .redirectInput(ProcessBuilder.Redirect.PIPE)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD);
            // Keep the heap cap authoritative even when the desktop launcher inherited
            // developer or packaging JVM options from its environment.
            builder.environment().keySet().removeIf(SevenZArchiveWorker::isJavaOptionsVariable);
            process = builder.start();
        } catch (IOException failure) {
            throw new RomSourceException(
                    RomSourceException.Reason.INVALID_ARCHIVE,
                    "The isolated 7z helper could not be started",
                    failure);
        }

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WORKER_TIMEOUT_MILLIS);
        try (ProcessGuard ignored = new ProcessGuard(process)) {
            try {
                process.getOutputStream().close();
            } catch (IOException failure) {
                throw new RomSourceException(
                        RomSourceException.Reason.INVALID_ARCHIVE,
                        "The isolated 7z helper communication could not be initialized",
                        failure);
            }
            while (!waitFor(process, 50)) {
                checkCancelled(cancelled);
                if (System.nanoTime() - deadline >= 0) {
                    throw new RomSourceException(
                            RomSourceException.Reason.INVALID_ARCHIVE,
                            "The 7z archive exceeded the processing time limit");
                }
            }
            checkCancelled(cancelled);
            if (process.exitValue() == WORKER_MEMORY_EXIT) {
                throw new RomSourceException(
                        RomSourceException.Reason.CONTAINER_TOO_LARGE,
                        "The 7z archive exceeded the isolated parser memory limit");
            }
            if (process.exitValue() != 0) {
                throw new RomSourceException(
                        RomSourceException.Reason.INVALID_ARCHIVE,
                        "The isolated 7z helper stopped unexpectedly");
            }
            return readReport(report, action);
        }
    }

    private static boolean waitFor(Process process, long timeoutMillis) {
        try {
            return process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new CancellationException("7z processing was interrupted");
        }
    }

    private static Path javaExecutable() {
        Path bin = Paths.get(System.getProperty("java.home"), "bin");
        if (isWindows()) {
            Path windowless = bin.resolve("javaw.exe");
            if (Files.isRegularFile(windowless)) {
                return windowless;
            }
            return bin.resolve("java.exe");
        }
        return bin.resolve("java");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isJavaOptionsVariable(String name) {
        return "_JAVA_OPTIONS".equalsIgnoreCase(name)
                || "JAVA_TOOL_OPTIONS".equalsIgnoreCase(name)
                || "JDK_JAVA_OPTIONS".equalsIgnoreCase(name);
    }

    private static WorkerReport readReport(Path report, Action expectedAction) throws IOException {
        byte[] bytes =
                readPrivateFile(
                        report,
                        1,
                        MAX_REPORT_BYTES,
                        () -> false,
                        "helper report");
        try (ByteArrayInputStream raw = new ByteArrayInputStream(bytes);
                DataInputStream input = new DataInputStream(raw)) {
            if (input.readInt() != REPORT_MAGIC || input.readInt() != REPORT_VERSION) {
                throw invalidWorkerReport("report header is invalid");
            }
            Action action = Action.fromOrdinal(input.readUnsignedByte());
            if (action != expectedAction) {
                throw invalidWorkerReport("report action is invalid");
            }
            boolean success = input.readBoolean();
            WorkerReport result;
            if (!success) {
                String reasonName = readString(input);
                String message = readString(input);
                RomSourceException.Reason reason;
                try {
                    reason = RomSourceException.Reason.valueOf(reasonName);
                } catch (IllegalArgumentException failure) {
                    throw invalidWorkerReport("failure reason is invalid");
                }
                result = new WorkerReport(reason, message, null, null);
            } else if (action == Action.INVENTORY) {
                int extensionCandidateCount = input.readInt();
                int candidateCount = input.readInt();
                if (candidateCount < 1
                        || candidateCount > Rom.MAX_ARCHIVE_ENTRIES
                        || extensionCandidateCount < candidateCount
                        || extensionCandidateCount > Rom.MAX_ARCHIVE_ENTRIES) {
                    throw invalidWorkerReport("candidate count is invalid");
                }
                List<Entry> entries = new ArrayList<>(candidateCount);
                long previousToken = -1;
                for (int i = 0; i < candidateCount; i++) {
                    Entry entry = readEntry(input);
                    if (entry.token <= previousToken) {
                        throw invalidWorkerReport("candidate tokens are not strictly ordered");
                    }
                    previousToken = entry.token;
                    entries.add(entry);
                }
                result =
                        new WorkerReport(
                                null,
                                null,
                                new Inventory(entries, extensionCandidateCount),
                                null);
            } else {
                result = new WorkerReport(null, null, null, readEntry(input));
            }
            if (raw.available() != 0) {
                throw invalidWorkerReport("report has trailing data");
            }
            return result;
        } catch (RomSourceException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new RomSourceException(
                    RomSourceException.Reason.INVALID_ARCHIVE,
                    "The isolated 7z helper returned an invalid report",
                    failure);
        }
    }

    private static Entry readEntry(DataInputStream input) throws IOException {
        long token = input.readLong();
        String entryName = readString(input);
        int entryOccurrence = input.readInt();
        long uncompressedBytes = input.readLong();
        String title = readString(input);
        if (token < 0
                || token >= Rom.MAX_ARCHIVE_ENTRIES
                || entryOccurrence < 0
                || entryOccurrence >= Rom.MAX_ARCHIVE_ENTRIES
                || uncompressedBytes < RomHeaderInspector.HEADER_LENGTH
                || uncompressedBytes > RomImage.MAX_ROM_BYTES
                || entryName.isBlank()) {
            throw invalidWorkerReport("entry metadata is invalid");
        }
        try {
            RomOrigin.archiveEntry(Paths.get("worker-report.7z"), entryName);
        } catch (IllegalArgumentException failure) {
            throw invalidWorkerReport("entry path is invalid");
        }
        return new Entry(token, entryName, entryOccurrence, uncompressedBytes, title);
    }

    private static void writeInventoryReport(Path report, Inventory inventory) throws IOException {
        writeReport(
                report,
                Action.INVENTORY,
                output -> {
                    output.writeBoolean(true);
                    output.writeInt(inventory.extensionCandidateCount);
                    output.writeInt(inventory.entries.size());
                    for (Entry entry : inventory.entries) {
                        writeEntry(output, entry);
                    }
                });
    }

    private static void writeExtractionReport(Path report, Entry entry) throws IOException {
        writeReport(
                report,
                Action.EXTRACT,
                output -> {
                    output.writeBoolean(true);
                    writeEntry(output, entry);
                });
    }

    private static void writeFailureReport(
            Path report,
            Action action,
            RomSourceException.Reason reason,
            String message) throws IOException {
        writeReport(
                report,
                action,
                output -> {
                    output.writeBoolean(false);
                    writeString(output, reason.name(), MAX_FAILURE_MESSAGE_BYTES);
                    writeTruncatedString(output, message, MAX_FAILURE_MESSAGE_BYTES);
                });
    }

    private static void writeReport(Path report, Action action, ReportBody body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(REPORT_MAGIC);
            output.writeInt(REPORT_VERSION);
            output.writeByte(action.ordinal());
            body.write(output);
        }
        if (bytes.size() > MAX_REPORT_BYTES) {
            throw new RomSourceException(
                    RomSourceException.Reason.CONTAINER_TOO_LARGE,
                    "The 7z archive entry metadata exceeds the safety limit");
        }
        Files.write(
                report,
                bytes.toByteArray(),
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS);
    }

    private static void writeEntry(DataOutputStream output, Entry entry) throws IOException {
        output.writeLong(entry.token);
        writeString(output, entry.entryName, MAX_STRING_BYTES);
        output.writeInt(entry.entryOccurrence);
        output.writeLong(entry.uncompressedBytes);
        writeString(output, entry.title, MAX_STRING_BYTES);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw invalidWorkerReport("string length is invalid");
        }
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(DataOutputStream output, String value, int limit)
            throws IOException {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) {
            throw new RomSourceException(
                    RomSourceException.Reason.CONTAINER_TOO_LARGE,
                    "The 7z archive entry metadata exceeds the safety limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void writeTruncatedString(DataOutputStream output, String value, int limit)
            throws IOException {
        byte[] bytes = Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
        int length = Math.min(bytes.length, limit);
        while (length > 0
                && length < bytes.length
                && (bytes[length] & 0xc0) == 0x80) {
            length--;
        }
        output.writeInt(length);
        output.write(bytes, 0, length);
    }

    private static void copyExactRom(InputStream input, OutputStream output, long declaredSize)
            throws IOException {
        if (declaredSize > RomImage.MAX_ROM_BYTES) {
            throw romTooLarge(declaredSize);
        }
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long total = 0;
        while (true) {
            int read = input.read(buffer);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                int value = input.read();
                if (value < 0) {
                    break;
                }
                if (total >= declaredSize || total >= RomImage.MAX_ROM_BYTES) {
                    throw new RomSourceException(
                            RomSourceException.Reason.INVALID_ARCHIVE,
                            "The selected 7z entry exceeds its declared size");
                }
                output.write(value);
                total++;
            } else {
                if (read > declaredSize - total || read > RomImage.MAX_ROM_BYTES - total) {
                    throw new RomSourceException(
                            RomSourceException.Reason.INVALID_ARCHIVE,
                            "The selected 7z entry exceeds its declared size");
                }
                output.write(buffer, 0, read);
                total += read;
            }
        }
        if (total != declaredSize) {
            throw new RomSourceException(
                    RomSourceException.Reason.INVALID_ARCHIVE,
                    "The selected 7z entry is truncated");
        }
    }

    private static byte[] readExtractedRom(
            Path output, long declaredSize, BooleanSupplier cancelled) throws IOException {
        if (declaredSize < RomHeaderInspector.HEADER_LENGTH
                || declaredSize > RomImage.MAX_ROM_BYTES) {
            throw new RomSourceException(
                    RomSourceException.Reason.INVALID_ARCHIVE,
                    "The isolated 7z extraction returned an invalid ROM size");
        }
        return readPrivateFile(
                output,
                declaredSize,
                declaredSize,
                cancelled,
                "extracted ROM");
    }

    private static byte[] readPrivateFile(
            Path path,
            long minimumSize,
            long maximumSize,
            BooleanSupplier cancelled,
            String description)
            throws IOException {
        try (FileChannel channel =
                FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size < minimumSize || size > maximumSize || size > Integer.MAX_VALUE) {
                throw invalidWorkerReport(description + " size is invalid");
            }
            byte[] bytes = new byte[(int) size];
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                checkCancelled(cancelled);
                int read = channel.read(buffer);
                if (read < 0) {
                    throw invalidWorkerReport(description + " is truncated");
                }
            }
            checkCancelled(cancelled);
            if (channel.size() != size) {
                throw invalidWorkerReport(description + " changed while being read");
            }
            return bytes;
        } catch (RomSourceException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new RomSourceException(
                    RomSourceException.Reason.INVALID_ARCHIVE,
                    "The isolated 7z " + description + " could not be read",
                    failure);
        }
    }

    private static void throwIfFailed(WorkerReport result) throws RomSourceException {
        if (result.failureReason != null) {
            throw new RomSourceException(result.failureReason, result.failureMessage);
        }
    }

    private static String requireEntryName(SevenZArchiveEntry entry) throws RomSourceException {
        String name = entry.getName();
        if (name == null || name.isBlank()) {
            throw new RomSourceException(
                    RomSourceException.Reason.INVALID_ARCHIVE,
                    "The 7z archive contains an unnamed entry");
        }
        return name;
    }

    private static void validateEntryName(Path snapshot, String name) throws RomSourceException {
        try {
            RomOrigin.archiveEntry(snapshot, name);
        } catch (IllegalArgumentException failure) {
            throw new RomSourceException(
                    RomSourceException.Reason.UNSAFE_ARCHIVE_ENTRY,
                    "The 7z archive contains an unsafe entry path",
                    failure);
        }
    }

    private static long checkedReportBytes(long current, String name) throws RomSourceException {
        int bytes = name.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_STRING_BYTES || current > MAX_REPORT_BYTES - bytes) {
            throw new RomSourceException(
                    RomSourceException.Reason.CONTAINER_TOO_LARGE,
                    "The 7z archive entry metadata exceeds the safety limit");
        }
        return current + bytes;
    }

    private static int checkedEntryCount(int current) throws RomSourceException {
        if (current >= Rom.MAX_ARCHIVE_ENTRIES) {
            throw new RomSourceException(
                    RomSourceException.Reason.CONTAINER_TOO_LARGE,
                    "Archive exceeds the "
                            + Rom.MAX_ARCHIVE_ENTRIES
                            + "-entry safety limit");
        }
        return current + 1;
    }

    private static RomSourceException.Reason classifyFailure(Exception failure) {
        if (failure instanceof RomSourceException sourceFailure) {
            return sourceFailure.reason();
        }
        if (failure instanceof MemoryLimitException) {
            return RomSourceException.Reason.CONTAINER_TOO_LARGE;
        }
        String message = failure.getMessage();
        if (message != null
                && (message.contains("compressed-size safety limit")
                        || message.contains("entry safety limit")
                        || message.contains("uncompressed-size safety limit")
                        || message.contains("memory limit"))) {
            return RomSourceException.Reason.CONTAINER_TOO_LARGE;
        }
        if (failure instanceof NoSuchFileException || failure instanceof FileNotFoundException) {
            return RomSourceException.Reason.MISSING;
        }
        return RomSourceException.Reason.INVALID_ARCHIVE;
    }

    private static String failureMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? "The 7z archive is invalid or unreadable"
                : message;
    }

    private static RomSourceException invalidSelection() {
        return new RomSourceException(
                RomSourceException.Reason.INVALID_SELECTION,
                "The 7z archive selection is stale or invalid");
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

    private static RomSourceException invalidWorkerReport(String detail) {
        return new RomSourceException(
                RomSourceException.Reason.INVALID_ARCHIVE,
                "The isolated 7z helper report is invalid: " + detail);
    }

    private static boolean isRomEntry(String name) {
        String extension = FilenameUtils.getExtension(name).toLowerCase(Locale.ROOT);
        return "gb".equals(extension) || "gbc".equals(extension) || "rom".equals(extension);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("7z processing was cancelled");
        }
    }

    private static Path createPrivateTemporaryFile(String suffix) throws IOException {
        FileAttribute<?> permissions =
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------"));
        try {
            return Files.createTempFile("coffee-gb-seven-z-worker-", suffix, permissions);
        } catch (UnsupportedOperationException failure) {
            return Files.createTempFile("coffee-gb-seven-z-worker-", suffix);
        }
    }

    private static void requireExistingWorkerFile(Path path, String description)
            throws IOException {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The 7z worker " + description + " is not a regular file");
        }
    }

    private static final class ProcessGuard implements AutoCloseable {

        private final Process process;

        private ProcessGuard(Process process) {
            this.process = process;
        }

        @Override
        public void close() throws IOException {
            if (!process.isAlive()) {
                return;
            }
            process.destroyForcibly();
            if (!waitFor(process, 1_000)) {
                throw new IOException("The isolated 7z helper could not be terminated");
            }
        }
    }

    private static final class TemporaryFiles implements AutoCloseable {

        private final List<Path> paths = new ArrayList<>();

        private Path create(String suffix) throws IOException {
            Path path = createPrivateTemporaryFile(suffix);
            paths.add(path);
            return path;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (int i = paths.size() - 1; i >= 0; i--) {
                Path path = paths.get(i);
                try {
                    Files.deleteIfExists(path);
                } catch (IOException cleanupFailure) {
                    try {
                        path.toFile().deleteOnExit();
                    } catch (RuntimeException fallbackFailure) {
                        cleanupFailure.addSuppressed(fallbackFailure);
                    }
                    if (failure == null) {
                        failure = cleanupFailure;
                    } else {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    static record Inventory(List<Entry> entries, int extensionCandidateCount) {
        Inventory {
            entries = List.copyOf(entries);
        }
    }

    static record Entry(
            long token,
            String entryName,
            int entryOccurrence,
            long uncompressedBytes,
            String title) {
    }

    private record RawCandidate(
            long token, SevenZArchiveEntry archiveEntry, int entryOccurrence) {
    }

    private record WorkerReport(
            RomSourceException.Reason failureReason,
            String failureMessage,
            Inventory inventory,
            Entry extracted) {
    }

    @FunctionalInterface
    private interface ReportBody {
        void write(DataOutputStream output) throws IOException;
    }

    private enum Action {
        INVENTORY("inventory"),
        EXTRACT("extract");

        private final String id;

        Action(String id) {
            this.id = id;
        }

        static Action fromId(String id) throws IOException {
            for (Action action : values()) {
                if (action.id.equals(id)) {
                    return action;
                }
            }
            throw new IOException("Unknown 7z worker action");
        }

        static Action fromOrdinal(int ordinal) throws IOException {
            Action[] actions = values();
            if (ordinal < 0 || ordinal >= actions.length) {
                throw new IOException("Unknown 7z worker report action");
            }
            return actions[ordinal];
        }
    }
}
