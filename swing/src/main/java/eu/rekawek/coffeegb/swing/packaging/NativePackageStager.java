package eu.rekawek.coffeegb.swing.packaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

/**
 * Builds a deterministic, target-specific jpackage input tree from Maven's neutral app JAR.
 *
 * <p>The universal JAR is read only as the Maven-resolved source of entries locked by
 * {@link NativeBundleManifest}. It is never copied into the package.
 */
public final class NativePackageStager {

    private static final long MAX_APP_JAR_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_SBOM_BYTES = 16L * 1024L * 1024L;
    private static final long MAX_LEGAL_FILE_BYTES = 2L * 1024L * 1024L;
    private static final FileTime DETERMINISTIC_TIME =
            FileTime.from(Instant.parse("2000-01-01T00:00:00Z"));
    private static final Set<String> NATIVE_SUFFIXES = Set.of(
            ".dll", ".dylib", ".jnilib", ".so", ".a", ".bundle", ".node");
    private static final Set<String> ROM_SUFFIXES = Set.of(
            ".gb",
            "." + NativePackageMetadata.GAME_BOY_COLOR_ROM_EXTENSION,
            ".rom",
            "." + NativePackageMetadata.SUPER_GAME_BOY_ROM_EXTENSION);

    public StageResult stage(StageRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        NativePackageMetadata.Target target = NativePackageMetadata.target(request.target());
        NativeBundleManifest nativeManifest = NativeBundleManifest.locked(request.target());

        requireRegularFile(request.appJar(), MAX_APP_JAR_BYTES, "neutral app JAR");
        requireRegularFile(request.nativeSourceJar(), MAX_APP_JAR_BYTES, "native source JAR");
        requireRegularFile(request.sbom(), MAX_SBOM_BYTES, "CycloneDX SBOM");
        requireDirectoryWithoutSymlinks(request.resourcesRoot(), "package resources");

        String appVersion = jarVersion(request.appJar(), "neutral app JAR");
        String nativeJarVersion = jarVersion(request.nativeSourceJar(), "native source JAR");
        if (!appVersion.equals(nativeJarVersion)) {
            throw new IOException(
                    "Maven app/native artifacts have different versions: "
                            + appVersion + " and " + nativeJarVersion);
        }
        verifyNeutralAppJar(request.appJar());
        verifySbom(request.sbom(), appVersion);
        ThirdPartyNoticeInventory.validate(
                request.sbom(), request.resourcesRoot().resolve("legal"));
        ThirdPartyNoticeInventory.verifyEmbeddedLegal(
                request.appJar(), request.resourcesRoot().resolve("legal"));

        Path stage = createFreshDirectory(request.output());
        Path input = Files.createDirectory(stage.resolve("input"));
        Path associations = Files.createDirectory(stage.resolve("associations"));
        Path launchers = Files.createDirectory(stage.resolve("launchers"));
        Path jpackageResources = Files.createDirectory(stage.resolve("jpackage-resources"));
        Path nativeSource = Files.createDirectory(input.resolve("native-source"));
        Path legal = Files.createDirectory(input.resolve("legal"));
        Path assets = Files.createDirectory(input.resolve("assets"));

        Path stagedApp = input.resolve("coffee-gb.jar");
        copyFile(request.appJar(), stagedApp);
        Path stagedSbom = input.resolve("coffee-gb-sbom.cdx.json");
        copyFile(request.sbom(), stagedSbom);
        Path stagedNativeSbom = input.resolve(NativeComponentInventory.STAGED_NATIVE_SBOM);
        NativeComponentInventory.writeNativeSbom(
                request.target(), appVersion, stagedNativeSbom);
        NativeComponentInventory.verifyNativeSbom(
                stagedNativeSbom, request.target(), appVersion);
        copyFile(request.resourcesRoot().resolve("coffee-gb.svg"), assets.resolve("coffee-gb.svg"));
        copyLegalTree(
                request.resourcesRoot().resolve("legal"),
                legal,
                request.target());
        copyJpackageResourceTree(
                request.resourcesRoot()
                        .resolve("jpackage")
                        .resolve(target.hostOs().id()),
                jpackageResources);

        Path icon = input.resolve("coffee-gb." + target.iconSuffix());
        PackageIconWriter.write(target, icon);
        extractLockedNativeSource(request.nativeSourceJar(), nativeManifest, nativeSource);

        Path association = associations.resolve("game-boy-rom.properties");
        writeUtf8(
                association,
                "description=Game Boy ROM\n"
                        + "extension=" + String.join(",", NativePackageMetadata.ROM_EXTENSIONS) + "\n"
                        + "icon=input/" + icon.getFileName() + "\n"
                        + "mime-type=application/x-gameboy-rom\n");
        Path windowsConsoleLauncher = launchers.resolve("windows-console.properties");
        writeUtf8(
                windowsConsoleLauncher,
                "arguments=--debug\n"
                        + "win-console=true\n");

        Map<String, String> inventory = new TreeMap<>();
        inventory.put("schema", "1");
        inventory.put("app.id", NativePackageMetadata.APPLICATION_ID);
        inventory.put("app.name", NativePackageMetadata.APPLICATION_NAME);
        inventory.put("app.version", appVersion);
        inventory.put(
                "app.installer-version", NativePackageMetadata.installerVersion(appVersion));
        inventory.put("app.vendor", NativePackageMetadata.VENDOR);
        inventory.put("app.description", NativePackageMetadata.DESCRIPTION);
        inventory.put("app.source-url", NativePackageMetadata.SOURCE_URL);
        inventory.put("app.main-class", NativePackageMetadata.MAIN_CLASS);
        inventory.put("target", request.target().id());
        inventory.put("native.fingerprint", nativeManifest.fingerprint());
        inventory.put("native.gamepad-support", nativeManifest.gamepadSupport().name());
        inventory.put(
                "runtime.root-modules",
                String.join(",", NativePackageMetadata.RUNTIME_ROOT_MODULES));
        inventory.put(
                "file-associations", String.join(",", NativePackageMetadata.ROM_EXTENSIONS));
        inventory.put("signing.default", "unsigned");
        inventory.put("signing.release-hook", "explicit-environment-gated");
        inventory.put("app.jar.sha256", sha256(stagedApp));
        inventory.put("sbom.sha256", sha256(stagedSbom));
        inventory.put("native.sbom.sha256", sha256(stagedNativeSbom));
        Path inventoryFile = input.resolve("package-manifest.properties");
        writeMap(inventoryFile, inventory);

        Path checksums = stage.resolve("STAGE-SHA256SUMS");
        writeChecksums(stage, checksums);
        normalizeTimestamps(stage);

        return new StageResult(
                stage,
                input,
                associations,
                jpackageResources,
                stagedApp,
                stagedSbom,
                stagedNativeSbom,
                icon,
                association,
                windowsConsoleLauncher,
                inventoryFile,
                checksums,
                appVersion,
                target,
                nativeManifest);
    }

    static String jarVersion(Path jar, String description) throws IOException {
        try (JarFile file = new JarFile(jar.toFile(), false)) {
            Manifest manifest = file.getManifest();
            if (manifest == null) {
                throw new IOException(description + " has no manifest");
            }
            Attributes attributes = manifest.getMainAttributes();
            String version = attributes.getValue("Implementation-Version");
            String mainClass = attributes.getValue(Attributes.Name.MAIN_CLASS);
            if (version == null || version.isBlank()) {
                throw new IOException(description + " has no Implementation-Version");
            }
            if (!NativePackageMetadata.MAIN_CLASS.equals(mainClass)) {
                throw new IOException(
                        description + " has unexpected Main-Class: " + mainClass);
            }
            NativePackageMetadata.installerVersion(version);
            return version;
        }
    }

    static void verifyNeutralAppJar(Path appJar) throws IOException {
        try (JarFile jar = new JarFile(appJar.toFile(), false)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String lower = entry.getName().toLowerCase(Locale.ROOT);
                if (isNativePath(lower)) {
                    throw new IOException(
                            "Neutral app JAR contains a native library: " + entry.getName());
                }
                if (endsWithAny(lower, ROM_SUFFIXES)) {
                    throw new IOException(
                            "Neutral app JAR contains a ROM file: " + entry.getName());
                }
            }
        }
    }

    private static boolean isNativePath(String lower) {
        if (endsWithAny(lower, NATIVE_SUFFIXES)) {
            return true;
        }
        int versioned = lower.lastIndexOf(".so.");
        return versioned >= 0 && versioned + 4 < lower.length();
    }

    private static boolean endsWithAny(String value, Set<String> suffixes) {
        return suffixes.stream().anyMatch(value::endsWith);
    }

    private static void verifySbom(Path sbom, String appVersion) throws IOException {
        String contents = Files.readString(sbom, StandardCharsets.UTF_8);
        if (!contents.contains("\"bomFormat\"")
                || !contents.contains("\"CycloneDX\"")
                || !contents.contains("\"specVersion\"")
                || !contents.contains("\"" + appVersion + "\"")) {
            throw new IOException(
                    "SBOM is not a CycloneDX inventory for application version " + appVersion);
        }
    }

    private static void extractLockedNativeSource(
            Path sourceJar, NativeBundleManifest manifest, Path output) throws IOException {
        Map<String, Integer> occurrences = new HashMap<>();
        try (JarFile jar = new JarFile(sourceJar.toFile(), false)) {
            Enumeration<JarEntry> jarEntries = jar.entries();
            while (jarEntries.hasMoreElements()) {
                String name = jarEntries.nextElement().getName();
                occurrences.computeIfPresent(name, (ignored, count) -> count + 1);
                if (manifest.entries().stream().anyMatch(entry -> entry.resourcePath().equals(name))) {
                    occurrences.putIfAbsent(name, 1);
                }
            }
            for (NativeBundleEntry expected : manifest.entries()) {
                if (occurrences.getOrDefault(expected.resourcePath(), 0) != 1) {
                    throw new IOException(
                            "Native source JAR must contain exactly one "
                                    + expected.resourcePath());
                }
                ZipEntry entry = jar.getEntry(expected.resourcePath());
                if (entry == null || entry.isDirectory()) {
                    throw new IOException(
                            "Native source JAR is missing " + expected.resourcePath());
                }
                if (entry.getSize() != expected.byteSize()) {
                    throw new IOException(
                            "Native source size mismatch for " + expected.resourcePath());
                }
                Path destination = safeResolve(output, expected.resourcePath());
                Files.createDirectories(destination.getParent());
                try (InputStream raw = jar.getInputStream(entry)) {
                    copyLocked(raw, destination, expected);
                }
            }
        }
    }

    private static void copyLocked(
            InputStream source, Path destination, NativeBundleEntry expected) throws IOException {
        MessageDigest digest = sha256Digest();
        long copied = 0;
        byte[] buffer = new byte[64 * 1024];
        try (DigestInputStream input = new DigestInputStream(source, digest);
                OutputStream output = Files.newOutputStream(
                        destination,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                copied = Math.addExact(copied, read);
                if (copied > expected.byteSize()) {
                    throw new IOException(
                            "Native source exceeds locked size for " + expected.resourcePath());
                }
                output.write(buffer, 0, read);
            }
        }
        String actualDigest = hex(digest.digest());
        if (copied != expected.byteSize() || !actualDigest.equals(expected.sha256())) {
            throw new IOException(
                    "Native source digest mismatch for " + expected.resourcePath());
        }
    }

    private static void copyLegalTree(
            Path source, Path destination, NativeTarget target) throws IOException {
        requireDirectoryWithoutSymlinks(source, "legal resources");
        List<String> relativePaths = NativeComponentInventory.requiredLegalPaths(target)
                .stream()
                .sorted()
                .toList();
        for (String relativePath : relativePaths) {
            Path file = safeResolve(source, relativePath);
            if (Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > MAX_LEGAL_FILE_BYTES
                    || !file.getFileName().toString().endsWith(".txt")) {
                throw new IOException("Invalid package legal resource: " + file);
            }
            Path output = safeResolve(destination, relativePath);
            Files.createDirectories(output.getParent());
            copyFile(file, output);
        }
    }

    private static void copyJpackageResourceTree(Path source, Path destination)
            throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        requireDirectoryWithoutSymlinks(source, "jpackage resources");
        List<Path> files;
        try (Stream<Path> paths = Files.walk(source)) {
            files = paths.filter(Files::isRegularFile).sorted().toList();
        }
        for (Path file : files) {
            if (Files.isSymbolicLink(file) || Files.size(file) > MAX_LEGAL_FILE_BYTES) {
                throw new IOException("Invalid jpackage resource: " + file);
            }
            Path relative = source.relativize(file);
            Path target = safeResolve(destination, relative.toString().replace('\\', '/'));
            Files.createDirectories(target.getParent());
            copyFile(file, target);
        }
    }

    private static void requireRegularFile(Path file, long limit, String description)
            throws IOException {
        Objects.requireNonNull(file, description);
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " is not a regular non-symlink file: " + file);
        }
        long size = Files.size(file);
        if (size <= 0 || size > limit) {
            throw new IOException(description + " has invalid size " + size);
        }
    }

    private static void requireDirectoryWithoutSymlinks(Path directory, String description)
            throws IOException {
        Objects.requireNonNull(directory, description);
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " is not a non-symlink directory: " + directory);
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            OptionalPath symlink = paths.filter(Files::isSymbolicLink)
                    .findFirst()
                    .map(OptionalPath::new)
                    .orElse(null);
            if (symlink != null) {
                throw new IOException(description + " contains a symlink: " + symlink.path);
            }
        }
    }

    private static Path createFreshDirectory(Path output) throws IOException {
        Objects.requireNonNull(output, "output");
        Path parent = output.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("Stage output has no parent: " + output);
        }
        Files.createDirectories(parent);
        return Files.createDirectory(output.toAbsolutePath().normalize());
    }

    private static Path safeResolve(Path root, String relative) throws IOException {
        if (!NativeBundleResolver.isSafeRelativePath(relative)) {
            throw new IOException("Unsafe package path: " + relative);
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root.normalize())) {
            throw new IOException("Package path escapes stage: " + relative);
        }
        return resolved;
    }

    private static void copyFile(Path source, Path destination) throws IOException {
        if (Files.isSymbolicLink(source)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Package source is not a regular non-symlink file: " + source);
        }
        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void writeMap(Path output, Map<String, String> values) throws IOException {
        StringBuilder contents = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            contents.append(entry.getKey())
                    .append('=')
                    .append(entry.getValue())
                    .append('\n');
        }
        writeUtf8(output, contents.toString());
    }

    private static void writeChecksums(Path root, Path output) throws IOException {
        List<Path> files;
        try (Stream<Path> paths = Files.walk(root)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(Predicate.not(output::equals))
                    .sorted(Comparator.comparing(path ->
                            root.relativize(path).toString().replace('\\', '/')))
                    .toList();
        }
        StringBuilder contents = new StringBuilder();
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            contents.append(sha256(file)).append("  ").append(relative).append('\n');
        }
        writeUtf8(output, contents.toString());
    }

    private static void writeUtf8(Path output, String contents) throws IOException {
        Files.writeString(
                output,
                contents,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    private static void normalizeTimestamps(Path root) throws IOException {
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(root)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.setLastModifiedTime(path, DETERMINISTIC_TIME);
        }
    }

    public static String sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(file);
                DigestInputStream digested = new DigestInputStream(input, digest)) {
            byte[] buffer = new byte[64 * 1024];
            while (digested.read(buffer) >= 0) {
                // DigestInputStream updates the digest.
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String hex(byte[] digest) {
        char[] result = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int value = digest[i] & 0xff;
            result[i * 2] = Character.forDigit(value >>> 4, 16);
            result[i * 2 + 1] = Character.forDigit(value & 0x0f, 16);
        }
        return new String(result);
    }

    public record StageRequest(
            NativeTarget target,
            Path appJar,
            Path nativeSourceJar,
            Path sbom,
            Path resourcesRoot,
            Path output) {

        public StageRequest {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(appJar, "appJar");
            Objects.requireNonNull(nativeSourceJar, "nativeSourceJar");
            Objects.requireNonNull(sbom, "sbom");
            Objects.requireNonNull(resourcesRoot, "resourcesRoot");
            Objects.requireNonNull(output, "output");
        }
    }

    public record StageResult(
            Path root,
            Path input,
            Path associations,
            Path jpackageResources,
            Path appJar,
            Path sbom,
            Path nativeSbom,
            Path icon,
            Path association,
            Path windowsConsoleLauncher,
            Path inventory,
            Path checksums,
            String appVersion,
            NativePackageMetadata.Target target,
            NativeBundleManifest nativeManifest) {
    }

    private record OptionalPath(Path path) {
    }
}
