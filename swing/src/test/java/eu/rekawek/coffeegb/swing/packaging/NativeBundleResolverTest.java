package eu.rekawek.coffeegb.swing.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativeBundleResolverTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void absentTargetUsesPortablePathWithoutTouchingCache() throws Exception {
        Path cache = temporary.getRoot().toPath().resolve("absent");
        AtomicInteger opens = new AtomicInteger();
        NativeRuntimeSelection selected = new NativeBundleResolver().select(
                Optional.empty(),
                path -> {
                    opens.incrementAndGet();
                    return Optional.empty();
                },
                cache);

        assertEquals(NativeRuntimeSelection.Portable.normal(), selected);
        assertEquals(0, opens.get());
        assertFalse(Files.exists(cache));
    }

    @Test
    public void unsupportedTargetFallsBackWithTypedFailure() {
        NativeRuntimeSelection selected = new NativeBundleResolver().select(
                Optional.of("linux-aarch64"),
                path -> Optional.empty(),
                temporary.getRoot().toPath().resolve("unsupported"));

        NativeRuntimeSelection.Portable portable =
                (NativeRuntimeSelection.Portable) selected;
        NativeBundleFailure.UnsupportedTarget failure =
                (NativeBundleFailure.UnsupportedTarget)
                        portable.fallbackCause().orElseThrow();
        assertEquals("linux-aarch64", failure.requestedTarget());
        assertEquals(NativeTarget.supportedIds(), failure.supportedTargets());
    }

    @Test
    public void missingNativeFallsBackWithoutPublishingPartialDirectory() throws Exception {
        Path cache = temporary.newFolder("missing").toPath();
        NativeRuntimeSelection selected = new NativeBundleResolver().select(
                Optional.of(NativeTarget.LINUX_X86_64.id()),
                path -> Optional.empty(),
                cache);

        NativeRuntimeSelection.Portable portable =
                (NativeRuntimeSelection.Portable) selected;
        NativeBundleFailure.MissingNative failure =
                (NativeBundleFailure.MissingNative)
                        portable.fallbackCause().orElseThrow();
        assertEquals(NativeComponent.JNA_DISPATCH, failure.component());
        try (Stream<Path> children = Files.list(cache)) {
            assertTrue(children.allMatch(path -> path.getFileName().toString().endsWith(".lock")));
        }
    }

    @Test
    public void maliciousOutputPathIsRejectedBeforeOpeningSource() throws Exception {
        byte[] bytes = "safe bytes".getBytes(StandardCharsets.UTF_8);
        NativeBundleEntry entry = entry(
                NativeComponent.JNA_DISPATCH, "native/source.so", "../outside.so", bytes);
        NativeBundleManifest manifest = manifest(List.of(entry));
        AtomicInteger opens = new AtomicInteger();
        Path root = temporary.newFolder("malicious").toPath();

        NativeBundleResult result = new NativeBundleResolver().resolve(
                manifest,
                path -> {
                    opens.incrementAndGet();
                    return Optional.of(new ByteArrayInputStream(bytes));
                },
                root.resolve("cache"));

        NativeBundleFailure.InvalidManifest failure =
                (NativeBundleFailure.InvalidManifest)
                        ((NativeBundleResult.Failed) result).failure();
        assertEquals("unsafe output path", failure.reason());
        assertEquals(0, opens.get());
        assertFalse(Files.exists(root.resolve("outside.so")));
        assertFalse(Files.exists(root.resolve("cache")));
    }

    @Test
    public void maliciousResourcePathIsRejected() throws Exception {
        byte[] bytes = {1};
        NativeBundleManifest manifest = manifest(List.of(entry(
                NativeComponent.JNA_DISPATCH, "../../secret", "lib/native.so", bytes)));

        NativeBundleResult result = new NativeBundleResolver().resolve(
                manifest,
                path -> Optional.of(new ByteArrayInputStream(bytes)),
                temporary.newFolder("malicious-resource").toPath());

        NativeBundleFailure.InvalidManifest failure =
                (NativeBundleFailure.InvalidManifest)
                        ((NativeBundleResult.Failed) result).failure();
        assertEquals("unsafe resource path", failure.reason());
    }

    @Test
    public void concurrentExtractionPublishesOneCompleteDeterministicBundle() throws Exception {
        byte[] jna = "jna-test-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] opencv = "opencv-test-bytes".getBytes(StandardCharsets.UTF_8);
        NativeBundleManifest manifest = manifest(List.of(
                entry(NativeComponent.JNA_DISPATCH, "native/jna.so", "lib/jna.so", jna),
                entry(NativeComponent.OPENCV, "native/opencv.so", "lib/opencv.so", opencv)));
        Map<String, byte[]> resources = Map.of(
                "native/jna.so", jna,
                "native/opencv.so", opencv);
        AtomicInteger opens = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        NativeResourceSource source = path -> {
            opens.incrementAndGet();
            return Optional.of(new ByteArrayInputStream(resources.get(path)));
        };
        Path cache = temporary.newFolder("concurrent").toPath();
        NativeBundleResolver resolver = new NativeBundleResolver();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<NativeBundleResult>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                tasks.add(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return resolver.resolve(manifest, source, cache);
                });
            }
            List<Future<NativeBundleResult>> futures = new ArrayList<>();
            for (Callable<NativeBundleResult> task : tasks) {
                futures.add(executor.submit(task));
            }
            start.countDown();

            Path published = null;
            for (Future<NativeBundleResult> future : futures) {
                NativeRuntimeBundle bundle =
                        ((NativeBundleResult.Ready) future.get(10, TimeUnit.SECONDS)).bundle();
                if (published == null) {
                    published = bundle.root();
                }
                assertEquals(published, bundle.root());
                assertArrayEquals(jna, Files.readAllBytes(bundle.library(NativeComponent.JNA_DISPATCH)));
                assertArrayEquals(
                        opencv, Files.readAllBytes(bundle.library(NativeComponent.OPENCV)));
            }
            assertEquals("only the publishing thread reads resources", 2, opens.get());
            assertEquals(
                    List.of(
                            ".coffee-gb-native-bundle",
                            "lib/jna.so",
                            "lib/opencv.so"),
                    regularFiles(published));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void inProcessLockContentionHasABoundedTypedFailureAndIsInterruptible()
            throws Exception {
        byte[] bytes = "locked".getBytes(StandardCharsets.UTF_8);
        NativeBundleManifest manifest = manifest(List.of(entry(
                NativeComponent.JNA_DISPATCH, "native/jna.so", "lib/jna.so", bytes)));
        Path cache = temporary.newFolder("in-process-lock").toPath();
        CountDownLatch sourceEntered = new CountDownLatch(1);
        CountDownLatch releaseSource = new CountDownLatch(1);
        NativeResourceSource blockingSource = path -> {
            sourceEntered.countDown();
            while (true) {
                try {
                    releaseSource.await();
                    break;
                } catch (InterruptedException ignored) {
                    // Keep the first resolver inside the critical section until the test releases it.
                }
            }
            return Optional.of(new ByteArrayInputStream(bytes));
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<NativeBundleResult> first = executor.submit(
                    () -> new NativeBundleResolver().resolve(manifest, blockingSource, cache));
            assertTrue(sourceEntered.await(3, TimeUnit.SECONDS));

            NativeBundleResult timedOut = new NativeBundleResolver(0, 1)
                    .resolve(
                            manifest,
                            path -> {
                                throw new AssertionError("contending resolver must not open sources");
                            },
                            cache);
            NativeBundleFailure.NativeCacheBusy timeoutFailure =
                    (NativeBundleFailure.NativeCacheBusy)
                            ((NativeBundleResult.Failed) timedOut).failure();
            assertTrue(timeoutFailure.operation().contains("in-process"));

            AtomicReference<NativeBundleResult> interruptedResult = new AtomicReference<>();
            AtomicReference<Boolean> interruptRestored = new AtomicReference<>(false);
            Thread waiter = new Thread(
                    () -> {
                        interruptedResult.set(new NativeBundleResolver().resolve(
                                manifest,
                                path -> {
                                    throw new AssertionError(
                                            "interrupted resolver must not open sources");
                                },
                                cache));
                        interruptRestored.set(Thread.currentThread().isInterrupted());
                    },
                    "native-lock-interrupt-test");
            waiter.start();
            waiter.interrupt();
            waiter.join(3_000);
            assertFalse("interrupted lock waiter did not terminate", waiter.isAlive());
            NativeBundleFailure.NativeCacheBusy interruptedFailure =
                    (NativeBundleFailure.NativeCacheBusy)
                            ((NativeBundleResult.Failed) interruptedResult.get()).failure();
            assertTrue(interruptedFailure.operation().contains("interrupted"));
            assertTrue(interruptRestored.get());

            releaseSource.countDown();
            assertTrue(first.get(3, TimeUnit.SECONDS) instanceof NativeBundleResult.Ready);
        } finally {
            releaseSource.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void externalProcessLockHasABoundedTypedFailure() throws Exception {
        byte[] bytes = "locked".getBytes(StandardCharsets.UTF_8);
        NativeBundleManifest manifest = manifest(List.of(entry(
                NativeComponent.JNA_DISPATCH, "native/jna.so", "lib/jna.so", bytes)));
        Path cache = temporary.newFolder("external-lock").toPath();
        Path lockPath = lockPath(cache, manifest);

        try (ExternalLockHolder holder = ExternalLockHolder.start(lockPath, temporary)) {
            NativeBundleResult result = new NativeBundleResolver(0, 1)
                    .resolve(
                            manifest,
                            path -> {
                                throw new AssertionError("locked resolver must not open sources");
                            },
                            cache);

            NativeBundleFailure.NativeCacheBusy failure =
                    (NativeBundleFailure.NativeCacheBusy)
                            ((NativeBundleResult.Failed) result).failure();
            assertTrue(failure.operation().contains("file lock"));
        }
    }

    @Test
    public void validPublishedCacheHitDoesNotWaitForExternalWriterLock() throws Exception {
        byte[] bytes = "locked".getBytes(StandardCharsets.UTF_8);
        NativeBundleManifest manifest = manifest(List.of(entry(
                NativeComponent.JNA_DISPATCH, "native/jna.so", "lib/jna.so", bytes)));
        Path cache = temporary.newFolder("optimistic-cache-hit").toPath();
        NativeBundleResolver resolver = new NativeBundleResolver();
        NativeRuntimeBundle published =
                ((NativeBundleResult.Ready)
                                resolver.resolve(
                                        manifest,
                                        path -> Optional.of(new ByteArrayInputStream(bytes)),
                                        cache))
                        .bundle();

        try (ExternalLockHolder holder =
                ExternalLockHolder.start(lockPath(cache, manifest), temporary)) {
            long started = System.nanoTime();
            NativeBundleResult result = new NativeBundleResolver(TimeUnit.SECONDS.toNanos(2), 1)
                    .resolve(
                            manifest,
                            path -> {
                                throw new AssertionError("cache hit must not reopen sources");
                            },
                            cache);
            long elapsedMillis =
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(result instanceof NativeBundleResult.Ready);
            assertEquals(
                    published.root(),
                    ((NativeBundleResult.Ready) result).bundle().root());
            assertTrue("valid cache hit waited for held lock: " + elapsedMillis, elapsedMillis < 500);
        }
    }

    @Test
    public void corruptPublishedBundleIsReportedAndNeverSilentlyReplaced() throws Exception {
        byte[] bytes = "locked".getBytes(StandardCharsets.UTF_8);
        NativeBundleManifest manifest = manifest(List.of(entry(
                NativeComponent.JNA_DISPATCH, "native/jna.so", "lib/jna.so", bytes)));
        AtomicInteger opens = new AtomicInteger();
        NativeResourceSource source = path -> {
            opens.incrementAndGet();
            return Optional.of(new ByteArrayInputStream(bytes));
        };
        Path cache = temporary.newFolder("corrupt").toPath();
        NativeBundleResolver resolver = new NativeBundleResolver();
        NativeRuntimeBundle first =
                ((NativeBundleResult.Ready) resolver.resolve(manifest, source, cache)).bundle();
        Files.writeString(first.library(NativeComponent.JNA_DISPATCH), "tampered");

        NativeBundleResult result = resolver.resolve(manifest, source, cache);

        assertTrue(
                ((NativeBundleResult.Failed) result).failure()
                        instanceof NativeBundleFailure.IntegrityMismatch);
        assertEquals(1, opens.get());
        assertEquals("tampered", Files.readString(first.library(NativeComponent.JNA_DISPATCH)));
    }

    @Test
    public void oversizedPublishedMarkerIsRejectedBeforeItIsRead() throws Exception {
        byte[] bytes = "locked".getBytes(StandardCharsets.UTF_8);
        NativeBundleManifest manifest = manifest(List.of(entry(
                NativeComponent.JNA_DISPATCH, "native/jna.so", "lib/jna.so", bytes)));
        Path cache = temporary.newFolder("marker").toPath();
        NativeBundleResolver resolver = new NativeBundleResolver();
        NativeRuntimeBundle first =
                ((NativeBundleResult.Ready)
                                resolver.resolve(
                                        manifest,
                                        path -> Optional.of(new ByteArrayInputStream(bytes)),
                                        cache))
                        .bundle();
        Path marker = first.root().resolve(".coffee-gb-native-bundle");
        Files.write(marker, new byte[1024 * 1024]);

        NativeBundleResult result = resolver.resolve(
                manifest,
                path -> {
                    throw new AssertionError("corrupt published content must not reopen sources");
                },
                cache);

        NativeBundleFailure.IntegrityMismatch failure =
                (NativeBundleFailure.IntegrityMismatch)
                        ((NativeBundleResult.Failed) result).failure();
        assertEquals("bundle marker differs", failure.reason());
    }

    @Test
    public void digestMismatchIsTypedAndStagingIsRemoved() throws Exception {
        byte[] expected = "expected".getBytes(StandardCharsets.UTF_8);
        byte[] actual = "tampered".getBytes(StandardCharsets.UTF_8);
        NativeBundleManifest manifest = manifest(List.of(entry(
                NativeComponent.JNA_DISPATCH,
                "native/jna.so",
                "lib/jna.so",
                expected)));
        Path cache = temporary.newFolder("digest").toPath();

        NativeBundleResult result = new NativeBundleResolver().resolve(
                manifest,
                path -> Optional.of(new ByteArrayInputStream(actual)),
                cache);

        NativeBundleFailure.IntegrityMismatch failure =
                (NativeBundleFailure.IntegrityMismatch)
                        ((NativeBundleResult.Failed) result).failure();
        assertEquals("resource digest differs from manifest", failure.reason());
        try (Stream<Path> children = Files.list(cache)) {
            assertTrue(children.noneMatch(
                    path -> path.getFileName().toString().startsWith(".coffee-gb-native-stage-")));
        }
    }

    private static NativeBundleManifest manifest(List<NativeBundleEntry> entries) {
        return new NativeBundleManifest(
                NativeTarget.LINUX_X86_64,
                GamepadNativeSupport.SYSTEM_LIBRARY_REQUIRED,
                entries);
    }

    private static NativeBundleEntry entry(
            NativeComponent component,
            String source,
            String destination,
            byte[] bytes) {
        return new NativeBundleEntry(
                component,
                source,
                destination,
                bytes.length,
                NativeBundleManifest.sha256(bytes));
    }

    private static List<String> regularFiles(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    private static Path lockPath(Path cache, NativeBundleManifest manifest) {
        String basename = manifest.target().id() + "-" + manifest.fingerprint();
        return cache.resolve("." + basename + ".lock");
    }

    public static final class LockHolder {

        private LockHolder() {
        }

        public static void main(String[] args) throws Exception {
            Path lockPath = Path.of(args[0]);
            Path ready = Path.of(args[1]);
            Path release = Path.of(args[2]);
            Files.createDirectories(lockPath.getParent());
            try (FileChannel channel = FileChannel.open(
                            lockPath,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                Files.writeString(ready, "ready", StandardOpenOption.CREATE_NEW);
                while (!Files.exists(release)) {
                    Thread.sleep(10);
                }
            }
        }
    }

    private static final class ExternalLockHolder implements AutoCloseable {

        private final Process process;
        private final Path release;

        private ExternalLockHolder(Process process, Path release) {
            this.process = process;
            this.release = release;
        }

        static ExternalLockHolder start(
                Path lockPath, TemporaryFolder temporary) throws Exception {
            Path communication = temporary.newFolder("lock-helper").toPath();
            Path ready = communication.resolve("ready");
            Path release = communication.resolve("release");
            Path java = Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    System.getProperty("os.name", "").toLowerCase().contains("win")
                            ? "java.exe"
                            : "java");
            Path testClasses = Path.of(
                    NativeBundleResolverTest.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI());
            Process process = new ProcessBuilder(
                            java.toString(),
                            "-cp",
                            testClasses.toString(),
                            LockHolder.class.getName(),
                            lockPath.toString(),
                            ready.toString(),
                            release.toString())
                    .redirectErrorStream(true)
                    .start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(ready)
                    && process.isAlive()
                    && System.nanoTime() - deadline < 0) {
                Thread.sleep(10);
            }
            if (!Files.exists(ready)) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
                throw new AssertionError(
                        "lock helper did not become ready: "
                                + new String(
                                        process.getInputStream().readAllBytes(),
                                        StandardCharsets.UTF_8));
            }
            return new ExternalLockHolder(process, release);
        }

        @Override
        public void close() throws Exception {
            Files.writeString(release, "release", StandardOpenOption.CREATE_NEW);
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertTrue("lock helper did not terminate", process.waitFor(3, TimeUnit.SECONDS));
            }
            assertEquals(
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                    0,
                    process.exitValue());
        }
    }
}
