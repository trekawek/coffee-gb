package eu.rekawek.coffeegb.swing.packaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Resolves exact target-native resources into an immutable content-addressed directory.
 *
 * <p>A JVM-local lock and an OS file lock serialize writers. Files are copied into a private
 * staging directory, size/digest verified, and the complete directory is renamed into place.
 * Callers receive typed failures; partial files are never published.
 */
public final class NativeBundleResolver {

    private static final String MARKER = ".coffee-gb-native-bundle";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Map<Path, ReentrantLock> PROCESS_LOCKS = new ConcurrentHashMap<>();

    public NativeRuntimeSelection select(
            Optional<String> requestedTarget,
            NativeResourceSource source,
            Path cacheRoot) {
        if (requestedTarget.isEmpty()) {
            return NativeRuntimeSelection.Portable.normal();
        }
        String requested = requestedTarget.orElseThrow();
        Optional<NativeTarget> target = NativeTarget.fromId(requested);
        if (target.isEmpty()) {
            return NativeRuntimeSelection.Portable.after(
                    new NativeBundleFailure.UnsupportedTarget(
                            requested, NativeTarget.supportedIds()));
        }
        NativeBundleResult result =
                resolve(NativeBundleManifest.locked(target.orElseThrow()), source, cacheRoot);
        if (result instanceof NativeBundleResult.Ready ready) {
            return new NativeRuntimeSelection.TargetBundle(ready.bundle());
        }
        return NativeRuntimeSelection.Portable.after(
                ((NativeBundleResult.Failed) result).failure());
    }

    public NativeBundleResult resolve(
            NativeTarget target,
            NativeResourceSource source,
            Path cacheRoot) {
        return resolve(NativeBundleManifest.locked(target), source, cacheRoot);
    }

    NativeBundleResult resolve(
            NativeBundleManifest manifest,
            NativeResourceSource source,
            Path cacheRoot) {
        if (source == null || cacheRoot == null) {
            throw new NullPointerException("source and cacheRoot are required");
        }
        Optional<NativeBundleFailure> invalid = validate(manifest);
        if (invalid.isPresent()) {
            return new NativeBundleResult.Failed(invalid.orElseThrow());
        }

        Path root = cacheRoot.toAbsolutePath().normalize();
        String basename = manifest.target().id() + "-" + manifest.fingerprint();
        Path bundle = root.resolve(basename);
        Path lockPath = root.resolve("." + basename + ".lock");
        ReentrantLock processLock = PROCESS_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        processLock.lock();
        try {
            Files.createDirectories(root);
            try (FileChannel channel = FileChannel.open(
                            lockPath,
                            Set.of(
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE,
                                    LinkOption.NOFOLLOW_LINKS));
                    FileLock ignored = channel.lock()) {
                if (Files.exists(bundle, LinkOption.NOFOLLOW_LINKS)) {
                    return verify(manifest, bundle);
                }
                return extractAndPublish(manifest, source, root, bundle);
            } catch (IOException | SecurityException failure) {
                return failedIo(manifest, "lock or inspect native cache");
            }
        } catch (IOException | SecurityException failure) {
            return failedIo(manifest, "create native cache");
        } finally {
            processLock.unlock();
        }
    }

    private NativeBundleResult extractAndPublish(
            NativeBundleManifest manifest,
            NativeResourceSource source,
            Path root,
            Path bundle) {
        Path staging = null;
        try {
            staging = Files.createTempDirectory(root, ".coffee-gb-native-stage-");
            for (NativeBundleEntry entry : manifest.entries()) {
                Optional<InputStream> opened = source.open(entry.resourcePath());
                if (opened.isEmpty()) {
                    return new NativeBundleResult.Failed(
                            new NativeBundleFailure.MissingNative(
                                    manifest.target(), entry.component()));
                }
                Path output = staging.resolve(entry.relativeOutputPath()).normalize();
                if (!output.startsWith(staging)) {
                    return invalid(manifest, entry, "output escapes staging directory");
                }
                Files.createDirectories(output.getParent());
                try (InputStream input = opened.orElseThrow();
                        OutputStream target = Files.newOutputStream(
                                output,
                                StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS)) {
                    Optional<NativeBundleFailure> copyFailure =
                            copyAndVerify(manifest, entry, input, target);
                    if (copyFailure.isPresent()) {
                        return new NativeBundleResult.Failed(copyFailure.orElseThrow());
                    }
                }
            }
            Files.writeString(
                    staging.resolve(MARKER),
                    manifest.markerContents(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            NativeBundleResult staged = verify(manifest, staging);
            if (staged instanceof NativeBundleResult.Failed) {
                return staged;
            }
            try {
                Files.move(staging, bundle, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                try {
                    Files.move(staging, bundle);
                } catch (FileAlreadyExistsException raced) {
                    return verify(manifest, bundle);
                }
            } catch (FileAlreadyExistsException raced) {
                return verify(manifest, bundle);
            }
            staging = null;
            return verify(manifest, bundle);
        } catch (IOException | SecurityException failure) {
            return failedIo(manifest, "extract native bundle");
        } finally {
            if (staging != null) {
                deleteStaging(staging);
            }
        }
    }

    private static Optional<NativeBundleFailure> copyAndVerify(
            NativeBundleManifest manifest,
            NativeBundleEntry entry,
            InputStream input,
            OutputStream target) throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > entry.byteSize()) {
                return Optional.of(
                        new NativeBundleFailure.IntegrityMismatch(
                                manifest.target(), entry.component(), "resource is larger than locked size"));
            }
            digest.update(buffer, 0, count);
            target.write(buffer, 0, count);
        }
        if (total != entry.byteSize()) {
            return Optional.of(
                    new NativeBundleFailure.IntegrityMismatch(
                            manifest.target(), entry.component(), "resource size differs from manifest"));
        }
        if (!hex(digest.digest()).equals(entry.sha256())) {
            return Optional.of(
                    new NativeBundleFailure.IntegrityMismatch(
                            manifest.target(), entry.component(), "resource digest differs from manifest"));
        }
        return Optional.empty();
    }

    private static NativeBundleResult verify(NativeBundleManifest manifest, Path bundle) {
        try {
            if (!Files.isDirectory(bundle, LinkOption.NOFOLLOW_LINKS)) {
                return integrity(manifest, manifest.entries().get(0), "bundle is not a directory");
            }
            Path marker = bundle.resolve(MARKER);
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    || !markerMatches(marker, manifest.markerContents())) {
                return integrity(manifest, manifest.entries().get(0), "bundle marker differs");
            }

            Set<Path> allowedFiles = new HashSet<>();
            Set<Path> allowedDirectories = new HashSet<>();
            allowedDirectories.add(bundle);
            allowedFiles.add(marker);
            EnumMap<NativeComponent, Path> libraries = new EnumMap<>(NativeComponent.class);
            for (NativeBundleEntry entry : manifest.entries()) {
                Path file = bundle.resolve(entry.relativeOutputPath()).normalize();
                if (!file.startsWith(bundle)
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(file) != entry.byteSize()) {
                    return integrity(manifest, entry, "published file size differs");
                }
                try (InputStream input = Files.newInputStream(
                        file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    if (!digest(input, entry.byteSize()).equals(entry.sha256())) {
                        return integrity(manifest, entry, "published file digest differs");
                    }
                }
                allowedFiles.add(file);
                Path parent = file.getParent();
                while (parent != null && parent.startsWith(bundle)) {
                    allowedDirectories.add(parent);
                    if (parent.equals(bundle)) {
                        break;
                    }
                    parent = parent.getParent();
                }
                libraries.put(entry.component(), file);
            }
            try (var paths = Files.walk(bundle)) {
                int maximumNodes = allowedFiles.size() + allowedDirectories.size();
                int nodes = 0;
                Iterator<Path> iterator = paths.iterator();
                while (iterator.hasNext()) {
                    Path path = iterator.next();
                    nodes++;
                    if (nodes > maximumNodes) {
                        return integrity(
                                manifest,
                                manifest.entries().get(0),
                                "unexpected content in bundle");
                    }
                    if (Files.isSymbolicLink(path)) {
                        return integrity(manifest, manifest.entries().get(0), "symbolic link in bundle");
                    }
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            && !allowedFiles.contains(path)) {
                        return integrity(manifest, manifest.entries().get(0), "unexpected file in bundle");
                    }
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            && !allowedDirectories.contains(path)) {
                        return integrity(
                                manifest,
                                manifest.entries().get(0),
                                "unexpected directory in bundle");
                    }
                }
            }
            return new NativeBundleResult.Ready(
                    new NativeRuntimeBundle(
                            manifest.target(), bundle, libraries, manifest.gamepadSupport()));
        } catch (IOException | SecurityException failure) {
            return failedIo(manifest, "verify native bundle");
        }
    }

    private static boolean markerMatches(Path marker, String expectedContents) throws IOException {
        byte[] expected = expectedContents.getBytes(StandardCharsets.UTF_8);
        if (Files.size(marker) != expected.length) {
            return false;
        }
        try (InputStream input =
                Files.newInputStream(marker, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] actual = input.readNBytes(expected.length + 1);
            return Arrays.equals(expected, actual);
        }
    }

    private static String digest(InputStream input, long maximum) throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > maximum) {
                return "";
            }
            digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static Optional<NativeBundleFailure> validate(NativeBundleManifest manifest) {
        Set<NativeComponent> components = new HashSet<>();
        Set<String> outputs = new HashSet<>();
        if (manifest.entries().isEmpty()) {
            return Optional.of(
                    new NativeBundleFailure.InvalidManifest(
                            manifest.target(), NativeComponent.JNA_DISPATCH, "manifest is empty"));
        }
        for (NativeBundleEntry entry : manifest.entries()) {
            if (!isSafeRelativePath(entry.resourcePath())) {
                return Optional.of(
                        new NativeBundleFailure.InvalidManifest(
                                manifest.target(), entry.component(), "unsafe resource path"));
            }
            if (!isSafeRelativePath(entry.relativeOutputPath())) {
                return Optional.of(
                        new NativeBundleFailure.InvalidManifest(
                                manifest.target(), entry.component(), "unsafe output path"));
            }
            if (entry.byteSize() <= 0 || entry.byteSize() > NativeBundleManifest.MAX_ENTRY_BYTES) {
                return Optional.of(
                        new NativeBundleFailure.InvalidManifest(
                                manifest.target(), entry.component(), "invalid byte-size bound"));
            }
            if (!SHA256.matcher(entry.sha256()).matches()) {
                return Optional.of(
                        new NativeBundleFailure.InvalidManifest(
                                manifest.target(), entry.component(), "invalid SHA-256"));
            }
            if (!components.add(entry.component())) {
                return Optional.of(
                        new NativeBundleFailure.InvalidManifest(
                                manifest.target(), entry.component(), "duplicate component"));
            }
            if (!outputs.add(entry.relativeOutputPath())) {
                return Optional.of(
                        new NativeBundleFailure.InvalidManifest(
                                manifest.target(), entry.component(), "duplicate output"));
            }
        }
        if (manifest.gamepadSupport() == GamepadNativeSupport.BUNDLED
                && !components.contains(NativeComponent.SDL2)) {
            return Optional.of(
                    new NativeBundleFailure.InvalidManifest(
                            manifest.target(), NativeComponent.SDL2, "bundled SDL2 entry is missing"));
        }
        if (manifest.gamepadSupport() == GamepadNativeSupport.SYSTEM_LIBRARY_REQUIRED
                && components.contains(NativeComponent.SDL2)) {
            return Optional.of(
                    new NativeBundleFailure.InvalidManifest(
                            manifest.target(), NativeComponent.SDL2, "system SDL2 target bundles SDL2"));
        }
        return Optional.empty();
    }

    static boolean isSafeRelativePath(String value) {
        if (value == null
                || value.isBlank()
                || value.startsWith("/")
                || value.startsWith("\\")
                || value.contains("\\")
                || value.contains(":")
                || value.indexOf('\0') >= 0) {
            return false;
        }
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static NativeBundleResult invalid(
            NativeBundleManifest manifest, NativeBundleEntry entry, String reason) {
        return new NativeBundleResult.Failed(
                new NativeBundleFailure.InvalidManifest(
                        manifest.target(), entry.component(), reason));
    }

    private static NativeBundleResult integrity(
            NativeBundleManifest manifest, NativeBundleEntry entry, String reason) {
        return new NativeBundleResult.Failed(
                new NativeBundleFailure.IntegrityMismatch(
                        manifest.target(), entry.component(), reason));
    }

    private static NativeBundleResult failedIo(NativeBundleManifest manifest, String operation) {
        return new NativeBundleResult.Failed(
                new NativeBundleFailure.IoFailure(manifest.target(), operation));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String hex(byte[] digest) {
        char[] encoded = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int value = digest[i] & 0xff;
            encoded[i * 2] = Character.forDigit(value >>> 4, 16);
            encoded[i * 2 + 1] = Character.forDigit(value & 0x0f, 16);
        }
        return new String(encoded);
    }

    private static void deleteStaging(Path staging) {
        try {
            if (!Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            List<Path> paths = new ArrayList<>();
            try (var walked = Files.walk(staging)) {
                paths.addAll(walked.toList());
            }
            for (int i = paths.size() - 1; i >= 0; i--) {
                Files.deleteIfExists(paths.get(i));
            }
        } catch (IOException | SecurityException ignored) {
            // A stale private staging directory is never treated as a valid published bundle.
        }
    }
}
