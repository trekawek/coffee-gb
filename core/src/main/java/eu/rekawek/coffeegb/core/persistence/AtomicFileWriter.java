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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
        if (intendedBytes == null) {
            throw new NullPointerException("intendedBytes");
        }
        Path normalized = normalizeTarget(target);
        withLock(normalized, () -> {
            writeLocked(normalized, intendedBytes);
            return null;
        });
    }

    /** Restores the deterministic backup only when the committed target is absent. */
    public void recover(Path target) throws IOException {
        Path normalized = normalizeTarget(target);
        withLock(normalized, () -> {
            recoverLocked(normalized);
            cleanupStaleTemps(normalized);
            return null;
        });
    }

    /** Returns target presence after recovery while holding the target's persistence lock. */
    public boolean exists(Path target) throws IOException {
        return read(target, path -> operations.exists(path, LinkOption.NOFOLLOW_LINKS));
    }

    /**
     * Recovers and reads a target under the same per-target lock used by replacement.
     *
     * <p>The callback may perform bounded streaming instead of allocating the entire file.
     */
    public <T> T read(Path target, PathReader<T> reader) throws IOException {
        Path normalized = normalizeTarget(target);
        return withLock(normalized, () -> {
            recoverLocked(normalized);
            cleanupStaleTemps(normalized);
            return reader.read(normalized);
        });
    }

    private void writeLocked(Path target, byte[] intendedBytes) throws IOException {
        Path parent = target.getParent();
        operations.createDirectories(parent);
        recoverLocked(target);
        cleanupStaleTemps(target);

        Path temp = operations.createTempFile(parent, tempPrefix(target), ".part");
        IOException failure = null;
        try {
            stageListener.reached(Stage.BEFORE_WRITE, target, temp);
            try (FileChannel channel =
                    operations.openFile(temp, StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
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
            try {
                operations.move(
                        temp,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                replaceWithRecoveryBackup(target, temp);
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

    private void replaceWithRecoveryBackup(Path target, Path temp) throws IOException {
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
        operations.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        stageListener.reached(Stage.AFTER_TARGET_RENAME, target, temp);
        forceDirectoryBestEffort(parent);

        if (targetExists) {
            deleteOwnedRegularFile(backup);
            forceDirectoryBestEffort(parent);
        }
    }

    private void recoverLocked(Path target) throws IOException {
        Path parent = target.getParent();
        operations.createDirectories(parent);
        Path backup = backupPath(target);
        boolean targetExists = operations.exists(target, LinkOption.NOFOLLOW_LINKS);
        boolean backupIsRegular = isOwnedRegularFile(backup);

        if (targetExists) {
            if (backupIsRegular) {
                operations.deleteIfExists(backup);
                forceDirectoryBestEffort(parent);
            }
            return;
        }
        if (!backupIsRegular) {
            return;
        }

        try {
            operations.move(backup, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            operations.move(backup, target);
        }
        forceDirectoryBestEffort(parent);
    }

    private void removeStaleBackupAfterCommit(Path target) throws IOException {
        Path backup = backupPath(target);
        if (isOwnedRegularFile(backup)) {
            operations.deleteIfExists(backup);
            forceDirectoryBestEffort(target.getParent());
        }
    }

    private void cleanupStaleTemps(Path target) throws IOException {
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
        for (int i = 0; i < count; i++) {
            operations.deleteIfExists(stale[i]);
        }
        if (count > 0) {
            forceDirectoryBestEffort(parent);
        }
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
            LOG.debug("Parent-directory metadata force is unsupported for {}", parent, e);
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
