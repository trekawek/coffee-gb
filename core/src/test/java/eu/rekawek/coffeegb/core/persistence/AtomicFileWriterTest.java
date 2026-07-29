package eu.rekawek.coffeegb.core.persistence;

import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AtomicFileWriterTest {

    private static final byte[] OLD = "complete-old-state".getBytes();

    private static final byte[] NEW = "complete-new-state-with-more-bytes".getBytes();

    @Test
    public void everyInjectedAtomicStageRecoversToOldOrNewCompleteBytes() throws Exception {
        for (AtomicFileWriter.Stage stage : List.of(
                AtomicFileWriter.Stage.BEFORE_WRITE,
                AtomicFileWriter.Stage.WRITE_PROGRESS,
                AtomicFileWriter.Stage.AFTER_WRITE_BEFORE_FORCE,
                AtomicFileWriter.Stage.AFTER_FORCE_BEFORE_REPLACEMENT,
                AtomicFileWriter.Stage.BEFORE_TARGET_RENAME,
                AtomicFileWriter.Stage.AFTER_TARGET_RENAME)) {
            withDirectory(directory -> {
                Path target = directory.resolve("state.sn0");
                Files.write(target, OLD);
                RecordingOperations operations = new RecordingOperations();
                operations.maximumWrite = 3;
                AtomicFileWriter writer =
                        new AtomicFileWriter(operations, failAt(stage));

                expectFailure(() -> writer.write(target, NEW));
                AtomicFileWriter.system().recover(target);

                assertOldOrNew(target);
                assertNoTransactionArtifacts(directory, target);
            });
        }
    }

    @Test
    public void everyInjectedFallbackStageRecoversToOldOrNewCompleteBytes() throws Exception {
        for (AtomicFileWriter.Stage stage : List.of(
                AtomicFileWriter.Stage.FALLBACK_AFTER_OLD_PRESERVED,
                AtomicFileWriter.Stage.BEFORE_FALLBACK_TARGET_RENAME,
                AtomicFileWriter.Stage.AFTER_TARGET_RENAME)) {
            withDirectory(directory -> {
                Path target = directory.resolve("battery.sav");
                Files.write(target, OLD);
                RecordingOperations operations = new RecordingOperations();
                operations.rejectAtomicReplacement = true;
                AtomicFileWriter writer =
                        new AtomicFileWriter(operations, failAt(stage));

                expectFailure(() -> writer.write(target, NEW));
                AtomicFileWriter.system().recover(target);

                assertOldOrNew(target);
                assertNoTransactionArtifacts(directory, target);
            });
        }
    }

    @Test
    public void partialWritesAreCompletedAndForcedBeforeReplacement() throws Exception {
        withDirectory(directory -> {
            Path target = directory.resolve("state.sn1");
            Files.write(target, OLD);
            RecordingOperations operations = new RecordingOperations();
            operations.maximumWrite = 2;
            List<Path> temps = new ArrayList<>();
            AtomicFileWriter writer =
                    new AtomicFileWriter(operations, (stage, ignored, temp) -> {
                        if (stage == AtomicFileWriter.Stage.BEFORE_WRITE) {
                            temps.add(temp);
                        }
                    });

            writer.write(target, NEW);
            writer.write(target, OLD);

            assertArrayEquals(OLD, Files.readAllBytes(target));
            assertTrue(operations.events.indexOf("force-file")
                    < operations.events.indexOf("move-atomic"));
            assertEquals(2, temps.size());
            assertNotEquals(temps.get(0), temps.get(1));
            assertEquals(target.toAbsolutePath().normalize().getParent(), temps.get(0).getParent());
            assertEquals(target.toAbsolutePath().normalize().getParent(), temps.get(1).getParent());
            assertNoTransactionArtifacts(directory, target);
        });
    }

    @Test
    public void ownerOnlyReplacementRestrictsTemporaryInodeBeforeEitherRenamePath()
            throws Exception {
        for (boolean fallback : List.of(false, true)) {
            withDirectory(directory -> {
                Path target = directory.resolve("private.bin");
                Files.write(target, OLD);
                RecordingOperations operations = new RecordingOperations();
                operations.rejectAtomicReplacement = fallback;

                new AtomicFileWriter(operations, AtomicFileWriter.StageListener.NOOP)
                        .writeOwnerOnly(target, NEW);

                String committedMove = fallback ? "move-fallback" : "move-atomic";
                assertTrue(operations.events.indexOf("restrict-owner")
                        < operations.events.indexOf("force-file"));
                assertTrue(operations.events.indexOf("restrict-owner")
                        < operations.events.lastIndexOf(committedMove));
                if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
                    assertEquals(
                            Set.of(PosixFilePermission.OWNER_READ,
                                    PosixFilePermission.OWNER_WRITE),
                            Files.getPosixFilePermissions(target));
                }
                assertArrayEquals(NEW, Files.readAllBytes(target));
            });
        }
    }

    @Test
    public void ownerOnlyPreparationFailureLeavesPriorTargetAndNoArtifacts() throws Exception {
        withDirectory(directory -> {
            Path target = directory.resolve("private-failure.bin");
            Files.write(target, OLD);
            RecordingOperations operations = new RecordingOperations();
            operations.rejectOwnerOnly = true;

            expectFailure(() ->
                    new AtomicFileWriter(operations, AtomicFileWriter.StageListener.NOOP)
                            .writeOwnerOnly(target, NEW));

            assertArrayEquals(OLD, Files.readAllBytes(target));
            assertNoTransactionArtifacts(directory, target);
            assertFalse(operations.events.contains("move-atomic"));
            assertFalse(operations.events.contains("move-fallback"));
        });
    }

    @Test
    public void ownerOnlyWriteDoesNotFollowAReplacedTemporarySymlink() throws Exception {
        withDirectory(directory -> {
            Path target = directory.resolve("private-symlink.bin");
            Path victim = directory.resolve("victim.bin");
            Files.write(target, OLD);
            Files.write(victim, "untouched-victim".getBytes());
            byte[] victimBefore = Files.readAllBytes(victim);
            AtomicReference<Path> hostileTemp = new AtomicReference<>();
            AtomicFileWriter writer = new AtomicFileWriter(
                    new RecordingOperations(),
                    (stage, ignored, temp) -> {
                        if (stage == AtomicFileWriter.Stage.BEFORE_WRITE) {
                            Files.delete(temp);
                            try {
                                Files.createSymbolicLink(temp, victim.getFileName());
                            } catch (UnsupportedOperationException unsupported) {
                                throw new IOException("symbolic links unavailable", unsupported);
                            }
                            hostileTemp.set(temp);
                        }
                    });

            expectFailure(() -> writer.writeOwnerOnly(target, NEW));

            assertArrayEquals(OLD, Files.readAllBytes(target));
            assertArrayEquals(victimBefore, Files.readAllBytes(victim));
            Path substituted = hostileTemp.get();
            if (substituted != null) {
                assertTrue(Files.isSymbolicLink(substituted));
                Files.delete(substituted);
            }
        });
    }

    @Test
    public void ownerOnlyWriteRepairsARegularTemporarySubstitutionBeforeCommit()
            throws Exception {
        withDirectory(directory -> {
            Path target = directory.resolve("private-regular-swap.bin");
            Files.write(target, OLD);
            AtomicBoolean replaced = new AtomicBoolean();
            AtomicFileWriter writer = new AtomicFileWriter(
                    new RecordingOperations(),
                    (stage, ignored, temp) -> {
                        if (stage == AtomicFileWriter.Stage.BEFORE_WRITE &&
                                replaced.compareAndSet(false, true)) {
                            Files.delete(temp);
                            Files.write(temp, "hostile-placeholder".getBytes());
                            if (Files.getFileStore(temp).supportsFileAttributeView("posix")) {
                                Files.setPosixFilePermissions(temp, Set.of(
                                        PosixFilePermission.OWNER_READ,
                                        PosixFilePermission.OWNER_WRITE,
                                        PosixFilePermission.GROUP_READ,
                                        PosixFilePermission.GROUP_WRITE,
                                        PosixFilePermission.OTHERS_READ,
                                        PosixFilePermission.OTHERS_WRITE));
                            }
                        }
                    });

            writer.writeOwnerOnly(target, NEW);

            assertArrayEquals(NEW, Files.readAllBytes(target));
            if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
                assertEquals(
                        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                        Files.getPosixFilePermissions(target));
            }
        });
    }

    @Test
    public void ownerOnlyWriteRejectsGroupWritableParentBeforeCreatingArtifacts()
            throws Exception {
        withDirectory(directory -> {
            if (!Files.getFileStore(directory).supportsFileAttributeView("posix")) return;
            Files.setPosixFilePermissions(directory, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE));
            Path target = directory.resolve("private-untrusted-parent.bin");

            expectFailure(() -> AtomicFileWriter.system().writeOwnerOnly(target, NEW));

            assertFalse(Files.exists(target));
            try (var files = Files.list(directory)) {
                assertEquals(0, files.count());
            }
        });
    }

    @Test
    public void fallbackPreservesOneBackupAndRecoveryPrefersPresentTarget() throws Exception {
        withDirectory(directory -> {
            Path target = directory.resolve("battery.sav").toAbsolutePath().normalize();
            Path backup = AtomicFileWriter.backupPath(target);
            Files.write(target, OLD);
            Files.write(backup, "stale-older-backup".getBytes());

            AtomicFileWriter.system().recover(target);
            assertArrayEquals(OLD, Files.readAllBytes(target));
            assertFalse(Files.exists(backup));

            Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
            assertFalse(Files.exists(target));
            AtomicFileWriter.system().recover(target);
            assertArrayEquals(OLD, Files.readAllBytes(target));
            assertFalse(Files.exists(backup));

            RecordingOperations operations = new RecordingOperations();
            operations.rejectAtomicReplacement = true;
            new AtomicFileWriter(operations, AtomicFileWriter.StageListener.NOOP)
                    .write(target, NEW);
            assertArrayEquals(NEW, Files.readAllBytes(target));
            assertFalse(Files.exists(backup));
        });
    }

    @Test
    public void recoveryReportDistinguishesRestoredBackupFromDiscardedStaleBackup()
            throws Exception {
        withDirectory(directory -> {
            Path target = directory.resolve("reported.sav").toAbsolutePath().normalize();
            Path backup = AtomicFileWriter.backupPath(target);
            Files.write(backup, OLD);

            AtomicFileWriter.RecoveryReport restored =
                    AtomicFileWriter.system().recoverWithReport(target);

            assertTrue(restored.backupRestored());
            assertFalse(restored.staleBackupRemoved());
            assertEquals(0, restored.staleTemporaryFilesRemoved());
            assertTrue(restored.recoveredAnything());
            assertArrayEquals(OLD, Files.readAllBytes(target));

            Files.write(backup, NEW);
            AtomicFileWriter.RecoveryReport discarded =
                    AtomicFileWriter.system().recoverWithReport(target);

            assertFalse(discarded.backupRestored());
            assertTrue(discarded.staleBackupRemoved());
            assertEquals(0, discarded.staleTemporaryFilesRemoved());
            assertTrue(discarded.recoveredAnything());
            assertArrayEquals(OLD, Files.readAllBytes(target));
            assertFalse(Files.exists(backup));
        });
    }

    @Test
    public void readAndExistsReturnValuesTogetherWithBoundedTempCleanupReport()
            throws Exception {
        withDirectory(directory -> {
            Path target = directory.resolve("read-with-recovery.sav").toAbsolutePath().normalize();
            Files.write(target, NEW);
            String prefix = AtomicFileWriter.tempPrefix(target);
            Files.write(directory.resolve(prefix + "one.part"), new byte[] {1});
            Files.write(directory.resolve(prefix + "two.part"), new byte[] {2});

            AtomicFileWriter.RecoveryResult<byte[]> read =
                    AtomicFileWriter.system().readWithRecovery(target, Files::readAllBytes);

            assertArrayEquals(NEW, read.value());
            assertEquals(2, read.recovery().staleTemporaryFilesRemoved());
            assertFalse(read.recovery().backupRestored());
            assertFalse(read.recovery().staleBackupRemoved());
            assertTrue(read.recovery().recoveredAnything());

            AtomicFileWriter.RecoveryResult<Boolean> exists =
                    AtomicFileWriter.system().existsWithRecovery(target);
            assertTrue(exists.value());
            assertFalse(exists.recovery().recoveredAnything());
            assertEquals(0, exists.recovery().staleTemporaryFilesRemoved());
        });
    }

    @Test
    public void firstFallbackWriteCommitsWithoutInventingAnOldFile() throws Exception {
        withDirectory(directory -> {
            Path target = directory.resolve("first.sav");
            RecordingOperations operations = new RecordingOperations();
            operations.rejectAtomicReplacement = true;

            new AtomicFileWriter(operations, AtomicFileWriter.StageListener.NOOP)
                    .write(target, NEW);

            assertArrayEquals(NEW, Files.readAllBytes(target));
            assertFalse(Files.exists(AtomicFileWriter.backupPath(
                    target.toAbsolutePath().normalize())));
            assertNoTransactionArtifacts(directory, target);
        });
    }

    @Test
    public void interruptedFirstWriteIsAbsentBeforeCommitOrCompleteAfterCommit()
            throws Exception {
        for (AtomicFileWriter.Stage stage : List.of(
                AtomicFileWriter.Stage.BEFORE_FALLBACK_TARGET_RENAME,
                AtomicFileWriter.Stage.AFTER_TARGET_RENAME)) {
            withDirectory(directory -> {
                Path target = directory.resolve("interrupted-first.sav");
                RecordingOperations operations = new RecordingOperations();
                operations.rejectAtomicReplacement = true;
                AtomicFileWriter writer = new AtomicFileWriter(operations, failAt(stage));

                expectFailure(() -> writer.write(target, NEW));
                AtomicFileWriter.system().recover(target);

                if (Files.exists(target)) {
                    assertArrayEquals(NEW, Files.readAllBytes(target));
                } else {
                    assertEquals(
                            AtomicFileWriter.Stage.BEFORE_FALLBACK_TARGET_RENAME,
                            stage);
                }
                assertNoTransactionArtifacts(directory, target);
            });
        }
    }

    @Test
    public void fallbackRetainsAtMostOneDeterministicRecoveryBackup() throws Exception {
        withDirectory(directory -> {
            Path target = directory.resolve("single-backup.sav").toAbsolutePath().normalize();
            Files.write(target, OLD);
            Path expectedBackup = AtomicFileWriter.backupPath(target);
            RecordingOperations operations = new RecordingOperations();
            operations.rejectAtomicReplacement = true;
            AtomicBoolean observed = new AtomicBoolean();
            AtomicFileWriter writer =
                    new AtomicFileWriter(operations, (stage, ignored, temp) -> {
                        if (stage == AtomicFileWriter.Stage.FALLBACK_AFTER_OLD_PRESERVED) {
                            try (var files = Files.list(directory)) {
                                List<Path> backups =
                                        files.filter(path ->
                                                        path.getFileName().toString()
                                                                .endsWith(".backup"))
                                                .toList();
                                assertEquals(1, backups.size());
                                assertEquals(expectedBackup, backups.get(0));
                                assertFalse(Files.exists(target));
                                assertArrayEquals(OLD, Files.readAllBytes(expectedBackup));
                                observed.set(true);
                            }
                        }
                    });

            writer.write(target, NEW);

            assertTrue(observed.get());
            assertArrayEquals(NEW, Files.readAllBytes(target));
            assertFalse(Files.exists(expectedBackup));
        });
    }

    @Test
    public void cleanupIsTargetSpecificBoundedAndNeverDeletesSymlinksOrSimilarNames()
            throws Exception {
        withDirectory(directory -> {
            Path target = directory.resolve("state.sn2").toAbsolutePath().normalize();
            String prefix = AtomicFileWriter.tempPrefix(target);
            Path stale = Files.createTempFile(directory, prefix, ".part");
            Path unrelated = directory.resolve(prefix + "similar.txt");
            Path otherTarget = directory.resolve(".coffeegb-other.tmp-123.part");
            Files.write(unrelated, new byte[] {1});
            Files.write(otherTarget, new byte[] {2});

            Path symlink = directory.resolve(prefix + "link.part");
            boolean symlinkCreated = false;
            try {
                Files.createSymbolicLink(symlink, stale.getFileName());
                symlinkCreated = true;
            } catch (UnsupportedOperationException | IOException ignored) {
                // Symlink creation is unavailable on some Windows test hosts.
            }

            AtomicFileWriter.system().write(target, NEW);

            assertFalse(Files.exists(stale));
            assertTrue(Files.exists(unrelated));
            assertTrue(Files.exists(otherTarget));
            if (symlinkCreated) {
                assertTrue(Files.isSymbolicLink(symlink));
            }

            for (int i = 0; i <= 32; i++) {
                Files.write(directory.resolve(prefix + String.format("%02d.part", i)),
                        new byte[] {(byte) i});
            }
            expectFailure(() -> AtomicFileWriter.system().recover(target));
            assertArrayEquals(NEW, Files.readAllBytes(target));
        });
    }

    @Test
    public void concurrentWritesToOneTargetCannotInterleaveArtifacts() throws Exception {
        withDirectory(directory -> {
            Path target = directory.resolve("concurrent.sav");
            byte[] first = new byte[32_000];
            byte[] second = new byte[32_000];
            Arrays.fill(first, (byte) 0x35);
            Arrays.fill(second, (byte) 0x72);
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            AtomicBoolean paused = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            AtomicFileWriter writer =
                    new AtomicFileWriter(new RecordingOperations(), (stage, ignored, temp) -> {
                        if (stage == AtomicFileWriter.Stage.BEFORE_WRITE
                                && paused.compareAndSet(false, true)) {
                            firstEntered.countDown();
                            try {
                                if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                                    throw new IOException("test timed out");
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IOException("test interrupted", e);
                            }
                        }
                    });

            Thread one = new Thread(() -> write(writer, target, first, failure));
            Thread two = new Thread(() -> write(writer, target, second, failure));
            one.start();
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            two.start();
            Thread.sleep(50);
            assertFalse("the second writer must wait for the target lock", Files.exists(target));
            releaseFirst.countDown();
            one.join(5_000);
            two.join(5_000);
            assertFalse(one.isAlive());
            assertFalse(two.isAlive());
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }

            assertArrayEquals(second, Files.readAllBytes(target));
            assertNoTransactionArtifacts(directory, target);
        });
    }

    private static AtomicFileWriter.StageListener failAt(AtomicFileWriter.Stage expected) {
        AtomicBoolean failed = new AtomicBoolean();
        return (stage, target, temp) -> {
            if (stage == expected && failed.compareAndSet(false, true)) {
                throw new IOException("injected " + stage);
            }
        };
    }

    private static void write(
            AtomicFileWriter writer,
            Path target,
            byte[] bytes,
            AtomicReference<Throwable> failure) {
        try {
            writer.write(target, bytes);
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

    private static void assertOldOrNew(Path target) throws IOException {
        assertTrue(Files.exists(target));
        byte[] actual = Files.readAllBytes(target);
        if (!Arrays.equals(OLD, actual) && !Arrays.equals(NEW, actual)) {
            fail("Recovered bytes are neither the complete old nor complete new value");
        }
    }

    private static void assertNoTransactionArtifacts(Path directory, Path target)
            throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        String prefix = AtomicFileWriter.tempPrefix(normalized);
        Path backup = AtomicFileWriter.backupPath(normalized);
        try (var files = Files.list(directory)) {
            assertEquals(
                    0,
                    files.filter(path ->
                                    path.getFileName().toString().startsWith(prefix)
                                            && path.getFileName().toString().endsWith(".part"))
                            .count());
        }
        assertFalse(Files.exists(backup));
    }

    private static void expectFailure(IoRunnable action) {
        try {
            action.run();
            fail("Expected IOException");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    private static void withDirectory(DirectoryTest test) throws Exception {
        Path directory = Files.createTempDirectory("coffee-gb-atomic-writer");
        try {
            test.run(directory);
        } finally {
            try (var files = Files.list(directory)) {
                files.forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            Files.deleteIfExists(directory);
        }
    }

    @FunctionalInterface
    private interface DirectoryTest {
        void run(Path directory) throws Exception;
    }

    @FunctionalInterface
    private interface IoRunnable {
        void run() throws IOException;
    }

    private static class RecordingOperations extends AtomicFileWriter.NioFileOperations {
        private final List<String> events = new ArrayList<>();

        private int maximumWrite = Integer.MAX_VALUE;

        private boolean rejectAtomicReplacement;

        private boolean rejectOwnerOnly;

        @Override
        public void restrictToOwner(Path path) throws IOException {
            events.add("restrict-owner");
            if (rejectOwnerOnly) {
                throw new AtomicFileWriter.OwnerOnlyPermissionsException(
                        "injected owner-only preparation failure");
            }
            super.restrictToOwner(path);
        }

        @Override
        public int write(FileChannel channel, ByteBuffer bytes) throws IOException {
            int originalLimit = bytes.limit();
            bytes.limit(Math.min(originalLimit, bytes.position() + maximumWrite));
            try {
                return super.write(channel, bytes);
            } finally {
                bytes.limit(originalLimit);
            }
        }

        @Override
        public void force(FileChannel channel, boolean metadata) throws IOException {
            events.add("force-file");
            super.force(channel, metadata);
        }

        @Override
        public void move(Path source, Path target, StandardCopyOption... options)
                throws IOException {
            boolean atomic = Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE);
            events.add(atomic ? "move-atomic" : "move-fallback");
            if (rejectAtomicReplacement
                    && atomic
                    && source.getFileName().toString().endsWith(".part")) {
                throw new AtomicMoveNotSupportedException(
                        source.toString(), target.toString(), "injected");
            }
            super.move(source, target, options);
        }
    }
}
