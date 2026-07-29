package eu.rekawek.coffeegb.core.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Crash-recoverable same-directory file replacement shared by snapshots and battery saves.
 *
 * <p>The caller must materialize the complete intended bytes before calling {@link #write}. Reads
 * performed with {@link #read} share the target's lock with writes and run recovery first, so they
 * cannot observe the temporary missing-target interval of the non-atomic fallback.
 */
public class AtomicFileWriter {

    private static final Logger LOG = LoggerFactory.getLogger(AtomicFileWriter.class);

    private static final AtomicFileWriter SYSTEM =
            new AtomicFileWriter(new NioFileOperations(), StageListener.NOOP);

    private static final int LOCK_STRIPES = 64;

    private static final ReentrantLock[] LOCKS = new ReentrantLock[LOCK_STRIPES];

    private static final int MAX_STALE_TEMPS = 32;

    static {
        for (int i = 0; i < LOCKS.length; i++) {
            LOCKS[i] = new ReentrantLock();
        }
    }

    private final FileOperations operations;

    private final StageListener stageListener;

    /** Constructor for test subclasses that override the public operations. */
    protected AtomicFileWriter() {
        this(new NioFileOperations(), StageListener.NOOP);
    }

    AtomicFileWriter(FileOperations operations, StageListener stageListener) {
        this.operations = operations;
        this.stageListener = stageListener;
    }

    public static AtomicFileWriter system() {
        return SYSTEM;
    }

    /**
     * Replaces {@code target} with fully materialized {@code intendedBytes}.
     *
     * <p>An {@link AtomicMoveNotSupportedException} from the first replacement attempt selects the
     * recovery-backup fallback. Other move failures are reported without moving the old target out
     * of the way.
     */
    public void write(Path target, byte[] intendedBytes) throws IOException {
        write(target, intendedBytes, false);
    }

    /**
     * Replaces {@code target} with owner-readable/owner-writable bytes when POSIX permissions are
     * available.
     *
     * <p>The temporary inode is restricted and verified before either rename path, so a permission
     * failure cannot commit the new bytes. Filesystems without a POSIX view retain their native
     * protection model.
     */
    public void writeOwnerOnly(Path target, byte[] intendedBytes) throws IOException {
        write(target, intendedBytes, true);
    }

    private void write(Path target, byte[] intendedBytes, boolean ownerOnly) throws IOException {
        if (intendedBytes == null) {
            throw new NullPointerException("intendedBytes");
        }
        Path normalized = normalizeTarget(target);
        withLock(normalized, () -> {
            writeLocked(normalized, intendedBytes, ownerOnly);
            return null;
        });
    }

    /** Restores the deterministic backup only when the committed target is absent. */
    public void recover(Path target) throws IOException {
        recoverWithReport(target);
    }

    /**
     * Restores the deterministic backup only when the committed target is absent and reports every
     * recovery artifact that was handled.
     */
    public RecoveryReport recoverWithReport(Path target) throws IOException {
        Path normalized = normalizeTarget(target);
        return withLock(normalized, () -> recoverAndCleanupLocked(normalized));
    }

    /** Returns target presence after recovery while holding the target's persistence lock. */
    public boolean exists(Path target) throws IOException {
        return existsWithRecovery(target).value();
    }

    /** Returns target presence and the recovery work performed under the target's lock. */
    public RecoveryResult<Boolean> existsWithRecovery(Path target) throws IOException {
        return readWithRecovery(
                target, path -> operations.exists(path, LinkOption.NOFOLLOW_LINKS));
    }

    /**
     * Recovers and reads a target under the same per-target lock used by replacement.
     *
     * <p>The callback may perform bounded streaming instead of allocating the entire file.
     */
    public <T> T read(Path target, PathReader<T> reader) throws IOException {
        return readWithRecovery(target, reader).value();
    }

    /**
     * Recovers and reads a target under its persistence lock, returning the value and a structured
     * explanation of any restored backup or removed transaction artifact.
     */
    public <T> RecoveryResult<T> readWithRecovery(Path target, PathReader<T> reader)
            throws IOException {
        if (reader == null) {
            throw new NullPointerException("reader");
        }
        Path normalized = normalizeTarget(target);
        return withLock(normalized, () -> {
            RecoveryReport recovery = recoverAndCleanupLocked(normalized);
            return new RecoveryResult<>(reader.read(normalized), recovery);
        });
    }

    private void writeLocked(Path target, byte[] intendedBytes, boolean ownerOnly)
            throws IOException {
        Path parent = target.getParent();
        operations.createDirectories(parent);
        if (ownerOnly) {
            operations.verifyOwnerOnlyParent(parent);
        }
        recoverAndCleanupLocked(target);

        Path temp = operations.createTempFile(parent, tempPrefix(target), ".part");
        IOException failure = null;
        try {
            if (ownerOnly) {
                operations.restrictToOwner(temp);
            }
            stageListener.reached(Stage.BEFORE_WRITE, target, temp);
            try (FileChannel channel =
                    operations.openFile(temp, StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer bytes = ByteBuffer.wrap(intendedBytes);
                int zeroWrites = 0;
                while (bytes.hasRemaining()) {
                    int written = operations.write(channel, bytes);
                    if (written < 0) {
                        throw new IOException("File channel ended before all bytes were written");
                    }
                    if (written == 0) {
                        if (++zeroWrites > 1_024) {
                            throw new IOException("File channel made no write progress");
                        }
                        continue;
                    }
                    zeroWrites = 0;
                    if (bytes.hasRemaining()) {
                        stageListener.reached(Stage.WRITE_PROGRESS, target, temp);
                    }
                }
                stageListener.reached(Stage.AFTER_WRITE_BEFORE_FORCE, target, temp);
                operations.force(channel, true);
            }

            stageListener.reached(Stage.AFTER_FORCE_BEFORE_REPLACEMENT, target, temp);
            stageListener.reached(Stage.BEFORE_TARGET_RENAME, target, temp);
            if (ownerOnly) {
                // No test hook occurs between this pathname/inode verification and the move.
                operations.restrictToOwner(temp);
            }
            try {
                operations.move(
                        temp,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                replaceWithRecoveryBackup(target, temp, ownerOnly);
                removeStaleBackupAfterCommit(target);
                cleanupStaleTemps(target);
                return;
            }

            stageListener.reached(Stage.AFTER_TARGET_RENAME, target, temp);
            forceDirectoryBestEffort(parent);
            removeStaleBackupAfterCommit(target);
            cleanupStaleTemps(target);
        } catch (IOException e) {
            failure = e;
            throw e;
        } finally {
            try {
                deleteOwnedRegularFile(temp);
            } catch (IOException cleanupFailure) {
                if (failure != null) {
                    failure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private void replaceWithRecoveryBackup(Path target, Path temp, boolean ownerOnly)
            throws IOException {
        Path parent = target.getParent();
        Path backup = backupPath(target);
        boolean targetExists = operations.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (targetExists) {
            refuseNonRegularArtifact(backup);
            operations.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
            forceDirectoryBestEffort(parent);
            stageListener.reached(Stage.FALLBACK_AFTER_OLD_PRESERVED, target, temp);
        }

        stageListener.reached(Stage.BEFORE_FALLBACK_TARGET_RENAME, target, temp);
        if (ownerOnly) {
            // The fallback stage hook may simulate interruption or substitution after preserving
            // the old target, so verify the new inode again immediately before its rename.
            operations.restrictToOwner(temp);
        }
        operations.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        stageListener.reached(Stage.AFTER_TARGET_RENAME, target, temp);
        forceDirectoryBestEffort(parent);

        if (targetExists) {
            deleteOwnedRegularFile(backup);
            forceDirectoryBestEffort(parent);
        }
    }

    private RecoveryReport recoverAndCleanupLocked(Path target) throws IOException {
        RecoveryReport recovery = recoverLocked(target);
        int removedTemps = cleanupStaleTemps(target);
        return recovery.withStaleTemporaryFilesRemoved(removedTemps);
    }

    private RecoveryReport recoverLocked(Path target) throws IOException {
        Path parent = target.getParent();
        operations.createDirectories(parent);
        Path backup = backupPath(target);
        boolean targetExists = operations.exists(target, LinkOption.NOFOLLOW_LINKS);
        boolean backupIsRegular = isOwnedRegularFile(backup);

        if (targetExists) {
            if (backupIsRegular) {
                boolean removed = operations.deleteIfExists(backup);
                if (removed) {
                    forceDirectoryBestEffort(parent);
                }
                return removed
                        ? new RecoveryReport(false, true, 0)
                        : RecoveryReport.NONE;
            }
            return RecoveryReport.NONE;
        }
        if (!backupIsRegular) {
            return RecoveryReport.NONE;
        }

        try {
            operations.move(backup, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            operations.move(backup, target);
        }
        forceDirectoryBestEffort(parent);
        return new RecoveryReport(true, false, 0);
    }

    private void removeStaleBackupAfterCommit(Path target) throws IOException {
        Path backup = backupPath(target);
        if (isOwnedRegularFile(backup)) {
            operations.deleteIfExists(backup);
            forceDirectoryBestEffort(target.getParent());
        }
    }

    private int cleanupStaleTemps(Path target) throws IOException {
        Path parent = target.getParent();
        String prefix = tempPrefix(target);
        Path[] stale = new Path[MAX_STALE_TEMPS + 1];
        int count = 0;
        try (DirectoryStream<Path> entries = operations.list(parent, prefix + "*.part")) {
            for (Path entry : entries) {
                if (!isOwnedRegularFile(entry)) {
                    continue;
                }
                if (count == stale.length) {
                    throw new IOException(
                            "More than " + MAX_STALE_TEMPS
                                    + " stale transaction files exist for " + target.getFileName());
                }
                stale[count++] = entry;
            }
        }
        if (count > MAX_STALE_TEMPS) {
            throw new IOException(
                    "More than " + MAX_STALE_TEMPS
                            + " stale transaction files exist for " + target.getFileName());
        }
        int removed = 0;
        for (int i = 0; i < count; i++) {
            if (operations.deleteIfExists(stale[i])) {
                removed++;
            }
        }
        if (removed > 0) {
            forceDirectoryBestEffort(parent);
        }
        return removed;
    }

    private void refuseNonRegularArtifact(Path artifact) throws IOException {
        if (operations.exists(artifact, LinkOption.NOFOLLOW_LINKS)
                && !isOwnedRegularFile(artifact)) {
            throw new IOException(
                    "Recovery artifact is not a regular file: " + artifact.getFileName());
        }
    }

    private void deleteOwnedRegularFile(Path artifact) throws IOException {
        if (isOwnedRegularFile(artifact)) {
            operations.deleteIfExists(artifact);
        }
    }

    private boolean isOwnedRegularFile(Path path) throws IOException {
        return operations.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && !operations.isSymbolicLink(path);
    }

    private void forceDirectoryBestEffort(Path parent) {
        try (FileChannel directory = operations.openDirectory(parent)) {
            operations.force(directory, true);
        } catch (IOException | UnsupportedOperationException e) {
            // Windows and some network/filesystem providers cannot open or fsync directories.
            // The file bytes were forced before any move; retain best-effort metadata durability.
            // Do not log the path or exception: callers may use this writer for private state.
            LOG.debug("Parent-directory metadata force is unsupported");
        }
    }

    private static Path normalizeTarget(Path target) throws IOException {
        if (target == null) {
            throw new NullPointerException("target");
        }
        Path normalized = target.toAbsolutePath().normalize();
        if (normalized.getFileName() == null || normalized.getParent() == null) {
            throw new IOException("Persistence target must have a file name and parent directory");
        }
        return normalized;
    }

    private static String artifactId(Path target) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(target.getFileName().toString()
                                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder id = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                id.append(String.format("%02x", digest[i] & 0xff));
            }
            return id.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    static Path backupPath(Path target) {
        return target.getParent().resolve(".coffeegb-" + artifactId(target) + ".backup");
    }

    static String tempPrefix(Path target) {
        return ".coffeegb-" + artifactId(target) + ".tmp-";
    }

    private static ReentrantLock lock(Path target) {
        return LOCKS[(target.hashCode() & Integer.MAX_VALUE) % LOCKS.length];
    }

    private static <T> T withLock(Path target, IoCallable<T> callable) throws IOException {
        ReentrantLock lock = lock(target);
        lock.lock();
        try {
            return callable.call();
        } finally {
            lock.unlock();
        }
    }

    @FunctionalInterface
    public interface PathReader<T> {
        T read(Path path) throws IOException;
    }

    /** Recovery actions performed before a read, existence check, or explicit recovery. */
    public static final class RecoveryReport {

        public static final RecoveryReport NONE = new RecoveryReport(false, false, 0);

        private final boolean backupRestored;

        private final boolean staleBackupRemoved;

        private final int staleTemporaryFilesRemoved;

        private RecoveryReport(
                boolean backupRestored,
                boolean staleBackupRemoved,
                int staleTemporaryFilesRemoved) {
            if (staleTemporaryFilesRemoved < 0 || staleTemporaryFilesRemoved > MAX_STALE_TEMPS) {
                throw new IllegalArgumentException("Invalid stale temporary file count");
            }
            this.backupRestored = backupRestored;
            this.staleBackupRemoved = staleBackupRemoved;
            this.staleTemporaryFilesRemoved = staleTemporaryFilesRemoved;
        }

        public boolean backupRestored() {
            return backupRestored;
        }

        public boolean staleBackupRemoved() {
            return staleBackupRemoved;
        }

        public int staleTemporaryFilesRemoved() {
            return staleTemporaryFilesRemoved;
        }

        public boolean recoveredAnything() {
            return backupRestored || staleBackupRemoved || staleTemporaryFilesRemoved > 0;
        }

        private RecoveryReport withStaleTemporaryFilesRemoved(int count) {
            if (count == 0) {
                return this;
            }
            return new RecoveryReport(backupRestored, staleBackupRemoved, count);
        }
    }

    /** Value returned by a locked read together with its structured recovery report. */
    public static final class RecoveryResult<T> {

        private final T value;

        private final RecoveryReport recovery;

        private RecoveryResult(T value, RecoveryReport recovery) {
            this.value = value;
            this.recovery = recovery;
        }

        public T value() {
            return value;
        }

        public RecoveryReport recovery() {
            return recovery;
        }
    }

    /** Typed pre-commit failure for an owner-only replacement. */
    public static class OwnerOnlyPermissionsException extends IOException {

        public OwnerOnlyPermissionsException(String message) {
            super(message);
        }

        public OwnerOnlyPermissionsException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @FunctionalInterface
    private interface IoCallable<T> {
        T call() throws IOException;
    }

    enum Stage {
        BEFORE_WRITE,
        WRITE_PROGRESS,
        AFTER_WRITE_BEFORE_FORCE,
        AFTER_FORCE_BEFORE_REPLACEMENT,
        BEFORE_TARGET_RENAME,
        FALLBACK_AFTER_OLD_PRESERVED,
        BEFORE_FALLBACK_TARGET_RENAME,
        AFTER_TARGET_RENAME,
    }

    @FunctionalInterface
    interface StageListener {
        StageListener NOOP = (stage, target, temp) -> {
        };

        void reached(Stage stage, Path target, Path temp) throws IOException;
    }

    interface FileOperations {
        void createDirectories(Path directory) throws IOException;

        Path createTempFile(Path directory, String prefix, String suffix) throws IOException;

        void verifyOwnerOnlyParent(Path directory) throws IOException;

        void restrictToOwner(Path path) throws IOException;

        FileChannel openFile(Path path, OpenOption... options) throws IOException;

        FileChannel openDirectory(Path path) throws IOException;

        int write(FileChannel channel, ByteBuffer bytes) throws IOException;

        void force(FileChannel channel, boolean metadata) throws IOException;

        void move(Path source, Path target, StandardCopyOption... options) throws IOException;

        boolean deleteIfExists(Path path) throws IOException;

        boolean exists(Path path, LinkOption... options);

        boolean isRegularFile(Path path, LinkOption... options);

        boolean isSymbolicLink(Path path);

        DirectoryStream<Path> list(Path directory, String glob) throws IOException;
    }

    static class NioFileOperations implements FileOperations {
        @Override
        public void createDirectories(Path directory) throws IOException {
            Files.createDirectories(directory);
        }

        @Override
        public Path createTempFile(Path directory, String prefix, String suffix)
                throws IOException {
            return Files.createTempFile(directory, prefix, suffix);
        }

        @Override
        public void verifyOwnerOnlyParent(Path directory) throws IOException {
            if (Files.isSymbolicLink(directory) ||
                    !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new OwnerOnlyPermissionsException(
                        "Owner-only replacement parent is not a direct directory");
            }
            PosixFileAttributeView view;
            try {
                view = Files.getFileAttributeView(
                        directory, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            } catch (UnsupportedOperationException unsupported) {
                return;
            }
            if (view == null) return;
            try {
                Set<PosixFilePermission> permissions = view.readAttributes().permissions();
                if (permissions.contains(PosixFilePermission.GROUP_WRITE) ||
                        permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                    throw new OwnerOnlyPermissionsException(
                            "Owner-only replacement parent is writable by another principal");
                }
            } catch (OwnerOnlyPermissionsException failure) {
                throw failure;
            } catch (UnsupportedOperationException unsupported) {
                // Non-POSIX providers have no portable directory permission representation.
            } catch (IOException | SecurityException failure) {
                throw new OwnerOnlyPermissionsException(
                        "Owner-only replacement parent permissions could not be verified", failure);
            }
        }

        @Override
        public void restrictToOwner(Path path) throws IOException {
            if (Files.isSymbolicLink(path) ||
                    !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new OwnerOnlyPermissionsException(
                        "Owner-only temporary artifact is not a regular file");
            }
            PosixFileAttributeView view;
            try {
                view = Files.getFileAttributeView(
                        path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            } catch (UnsupportedOperationException unsupported) {
                return;
            }
            if (view == null) return;
            Set<PosixFilePermission> expected = Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            try {
                view.setPermissions(expected);
                if (!view.readAttributes().permissions().equals(expected)) {
                    throw new OwnerOnlyPermissionsException(
                            "Owner-only temporary permissions were not retained");
                }
            } catch (UnsupportedOperationException unsupported) {
                // Non-POSIX providers have no portable owner-only representation.
            } catch (OwnerOnlyPermissionsException failure) {
                throw failure;
            } catch (IOException | SecurityException failure) {
                throw new OwnerOnlyPermissionsException(
                        "Owner-only temporary permissions could not be applied", failure);
            }
        }

        @Override
        public FileChannel openFile(Path path, OpenOption... options) throws IOException {
            return FileChannel.open(path, options);
        }

        @Override
        public FileChannel openDirectory(Path path) throws IOException {
            return FileChannel.open(path, StandardOpenOption.READ);
        }

        @Override
        public int write(FileChannel channel, ByteBuffer bytes) throws IOException {
            return channel.write(bytes);
        }

        @Override
        public void force(FileChannel channel, boolean metadata) throws IOException {
            channel.force(metadata);
        }

        @Override
        public void move(Path source, Path target, StandardCopyOption... options)
                throws IOException {
            Files.move(source, target, options);
        }

        @Override
        public boolean deleteIfExists(Path path) throws IOException {
            return Files.deleteIfExists(path);
        }

        @Override
        public boolean exists(Path path, LinkOption... options) {
            return Files.exists(path, options);
        }

        @Override
        public boolean isRegularFile(Path path, LinkOption... options) {
            return Files.isRegularFile(path, options);
        }

        @Override
        public boolean isSymbolicLink(Path path) {
            return Files.isSymbolicLink(path);
        }

        @Override
        public DirectoryStream<Path> list(Path directory, String glob) throws IOException {
            return Files.newDirectoryStream(directory, glob);
        }
    }
}
