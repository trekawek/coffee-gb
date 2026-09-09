package eu.rekawek.coffeegb.core.persistence;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Uses separate JVMs, as desktop netplay peers do when sharing the same Saves directory. */
public class AtomicFileWriterProcessTest {
    @Rule public TemporaryFolder directory = new TemporaryFolder();

    @Test(timeout = 20_000)
    public void anotherProcessCannotRecoverAnActiveAutosaveTemporaryFile() throws Exception {
        assertConcurrentOperationWaits("read", false);
    }

    @Test(timeout = 20_000)
    public void simultaneousAutosaveWritersBothCommitSuccessfully() throws Exception {
        assertConcurrentOperationWaits("write", false);
    }

    @Test(timeout = 20_000)
    public void stateEntriesShareTheLockInTheirGameStorageRoot() throws Exception {
        assertConcurrentOperationWaits("write", false, true);
    }

    @Test(timeout = 20_000)
    public void anotherProcessCannotRestoreAnActiveFallbackBackup() throws Exception {
        assertConcurrentOperationWaits("read", true);
    }

    @Test(timeout = 20_000)
    public void processExitReleasesRecoveryLockAndPreservesTheOldSave() throws Exception {
        Path target = directory.getRoot().toPath().resolve("autosave.state");
        Files.writeString(target, "old");
        try (Peer writer = new Peer(target, "hold-fallback")) {
            writer.expect("READY");
            writer.process.destroyForcibly();
            assertTrue(writer.process.waitFor(5, TimeUnit.SECONDS));
        }
        assertEquals("old", AtomicFileWriter.system().read(target, Files::readString));
        AtomicFileWriter.system().write(target, "retry".getBytes());
        assertEquals("retry", Files.readString(target));
    }

    private void assertConcurrentOperationWaits(String operation, boolean fallback) throws Exception {
        assertConcurrentOperationWaits(operation, fallback, false);
    }

    private void assertConcurrentOperationWaits(String operation, boolean fallback, boolean scoped)
            throws Exception {
        Path root = directory.getRoot().toPath();
        Path target = root.resolve(scoped ? "states/autosave/state.cgbstate" : "autosave.state");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "old");
        try (Peer writer = new Peer(target, fallback ? "hold-fallback" : "hold", scoped ? root : null)) {
            writer.expect("READY");
            try (Peer other = new Peer(target, operation, scoped ? root : null)) {
                other.expect("STARTED");
                boolean finishedEarly = other.process.waitFor(300, TimeUnit.MILLISECONDS);
                writer.release();
                writer.expect("DONE");
                writer.assertSuccess();
                other.expect(operation.equals("read") ? "READ first" : "DONE");
                other.assertSuccess();
                assertFalse("another process must wait for the active save, including recovery",
                        finishedEarly);
            }
        }
        assertEquals(operation.equals("read") ? "first" : "second", Files.readString(target));
    }

    private static final class Peer implements AutoCloseable {
        final Process process;
        final BufferedReader output;

        Peer(Path target, String operation) throws IOException {
            this(target, operation, null);
        }

        Peer(Path target, String operation, Path root) throws IOException {
            process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"),
                    Worker.class.getName(), target.toString(), operation, root == null ? "" : root.toString())
                    .redirectError(ProcessBuilder.Redirect.INHERIT).start();
            output = new BufferedReader(new InputStreamReader(process.getInputStream()));
        }

        void expect(String expected) throws Exception {
            assertEquals(expected, CompletableFuture.supplyAsync(() -> {
                try {
                    return output.readLine();
                } catch (IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            }).get(5, TimeUnit.SECONDS));
        }

        void release() throws IOException {
            process.getOutputStream().write('\n');
            process.getOutputStream().flush();
        }

        void assertSuccess() throws InterruptedException {
            assertTrue("worker exited", process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        }

        @Override public void close() throws Exception {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            output.close();
        }
    }

    public static class Worker {
        public static void main(String[] args) throws Exception {
            Path target = Path.of(args[0]);
            String operation = args[1];
            boolean fallback = operation.equals("hold-fallback");
            AtomicFileWriter.NioFileOperations files = new AtomicFileWriter.NioFileOperations() {
                @Override public void move(Path source, Path destination, StandardCopyOption... options)
                        throws IOException {
                    if (fallback && java.util.Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                        throw new AtomicMoveNotSupportedException("source", "destination", "test fallback");
                    }
                    super.move(source, destination, options);
                }
            };
            AtomicFileWriter writer = new AtomicFileWriter(files, (stage, ignored, temp) -> {
                AtomicFileWriter.Stage heldStage = fallback
                        ? AtomicFileWriter.Stage.FALLBACK_AFTER_OLD_PRESERVED
                        : AtomicFileWriter.Stage.AFTER_FORCE_BEFORE_REPLACEMENT;
                if (operation.startsWith("hold") && stage == heldStage) {
                    System.out.println("READY");
                    if (System.in.read() == -1) throw new IOException("parent closed input");
                }
            }, args[2].isEmpty() ? null : Path.of(args[2]));
            if (operation.startsWith("hold")) {
                writer.write(target, "first".getBytes());
            } else {
                System.out.println("STARTED");
                if (operation.equals("read")) {
                    System.out.println("READ " + writer.read(target, Files::readString));
                    return;
                }
                writer.write(target, "second".getBytes());
            }
            System.out.println("DONE");
        }
    }
}
