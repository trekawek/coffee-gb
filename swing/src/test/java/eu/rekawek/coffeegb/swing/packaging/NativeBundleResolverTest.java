package eu.rekawek.coffeegb.swing.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
