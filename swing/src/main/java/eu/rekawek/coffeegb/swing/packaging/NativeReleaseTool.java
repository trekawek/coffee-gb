package eu.rekawek.coffeegb.swing.packaging;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

/**
 * Gathers the independently built target packages only after the complete target set validates.
 *
 * <p>This is the release gate used after artifact download. It normalizes architecture-bearing
 * filenames, proves every target result/checksum/SBOM, records the validated SBOM digests without
 * publishing JSON files, and writes one release-level checksum file.
 */
public final class NativeReleaseTool {

    public static final String MATRIX_FILE = "NATIVE-PACKAGE-MATRIX.properties";
    private static final int MAX_INPUT_ENTRIES = 10_000;
    static final int MAX_RELEASE_DIRECTORY_ENTRIES = 256;

    private NativeReleaseTool() {
    }

    public static void main(String[] args) {
        try {
            Arguments parsed = Arguments.parse(args);
            if (!"assemble".equals(parsed.command)) {
                throw new IllegalArgumentException("First argument must be 'assemble'");
            }
            parsed.rejectUnused(Set.of(
                    "--input", "--portable-jar", "--output", "--version", "--source-commit"));
            assemble(new AssembleRequest(
                    parsed.requiredPath("--input"),
                    parsed.requiredPath("--portable-jar"),
                    parsed.requiredPath("--output"),
                    parsed.required("--version"),
                    parsed.required("--source-commit")));
        } catch (IllegalArgumentException | IOException failure) {
            System.err.println("coffee-gb native release: " + failure.getMessage());
            System.exit(2);
        }
    }

    public static Path assemble(AssembleRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        NativePackageMetadata.installerVersion(request.version());
        if (!request.sourceCommit().matches("[0-9a-f]{40}")) {
            throw new IOException("Source commit must be a full lowercase Git object ID");
        }
        Path input = request.input().toAbsolutePath().normalize();
        Path portableJar = request.portableJar().toAbsolutePath().normalize();
        if (!Files.isDirectory(input, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Downloaded package root is not a directory: " + input);
        }
        requireRegularFile(portableJar, "portable Maven JAR");
        String portableVersion =
                NativePackageStager.jarVersion(portableJar, "portable Maven JAR");
        if (!request.version().equals(portableVersion)) {
            throw new IOException(
                    "Portable JAR version " + portableVersion + " does not match "
                            + request.version());
        }
        verifyPortableNatives(portableJar);

        List<Path> paths;
        try (Stream<Path> stream = Files.walk(input)) {
            paths = stream.limit(MAX_INPUT_ENTRIES + 1L).toList();
        }
        if (paths.size() > MAX_INPUT_ENTRIES) {
            throw new IOException(
                    "Downloaded package tree exceeds " + MAX_INPUT_ENTRIES + " entries");
        }
        List<Path> results = paths.stream()
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> path.getFileName().toString()
                        .equals(NativePackageVerifier.RESULT_FILE))
                .toList();
        EnumMap<NativeTarget, TargetResult> targets = new EnumMap<>(NativeTarget.class);
        for (Path resultFile : results) {
            Path dist = resultFile.getParent();
            Map<String, String> raw = NativePackageVerifier.readStrictProperties(resultFile);
            NativeTarget target = NativeTarget.fromId(required(raw, "target"))
                    .orElseThrow(() -> new IOException(
                            "Unknown target in " + resultFile + ": " + raw.get("target")));
            NativePackageMetadata.PackageType packageType =
                    NativePackageMetadata.packageType(required(raw, "package.type"))
                            .orElseThrow(() -> new IOException(
                                    "Unknown package type in " + resultFile));
            NativePackageMetadata.PackageType expectedType =
                    NativePackageMetadata.target(target).defaultPackageType();
            if (packageType != expectedType) {
                throw new IOException(
                        "Release target " + target.id() + " must use "
                                + expectedType.id() + ", found " + packageType.id());
            }
            Map<String, String> verified = NativePackageVerifier.verifyDistribution(
                    dist, target, packageType, request.version());
            Path artifact = safeResolve(dist, required(verified, "artifact.path"));
            if (!"file".equals(required(verified, "artifact.kind"))) {
                throw new IOException("Release package must be a regular file: " + artifact);
            }
            Path sbom = safeResolve(dist, required(verified, "sbom.path"));
            Path nativeSbom =
                    safeResolve(dist, required(verified, "native-sbom.path"));
            Path signature = "verified-detached".equals(required(verified, "signing"))
                    ? safeResolve(dist, required(verified, "signature.path"))
                    : null;
            TargetResult previous = targets.put(
                    target,
                    new TargetResult(
                            target,
                            packageType,
                            artifact,
                            sbom,
                            nativeSbom,
                            signature,
                            verified));
            if (previous != null) {
                throw new IOException("Duplicate target package result: " + target.id());
            }
        }
        if (!targets.keySet().equals(Set.of(NativeTarget.values()))) {
            Set<NativeTarget> missing = new HashSet<>(Set.of(NativeTarget.values()));
            missing.removeAll(targets.keySet());
            throw new IOException("Required native release targets are missing: " + missing);
        }
        TargetResult firstTarget = targets.get(NativeTarget.values()[0]);
        String mavenSbomDigest = NativePackageStager.sha256(firstTarget.mavenSbom());
        for (TargetResult result : targets.values()) {
            if (!mavenSbomDigest.equals(
                    NativePackageStager.sha256(result.mavenSbom()))) {
                throw new IOException(
                        "Target package results contain different Maven dependency SBOMs");
            }
        }
        Path output = createFreshDirectory(request.output());
        String safeVersion = request.version();
        Path releaseJar = output.resolve("coffee-gb-" + safeVersion + ".jar");
        Files.copy(portableJar, releaseJar, StandardCopyOption.COPY_ATTRIBUTES);

        Map<String, String> matrix = new LinkedHashMap<>();
        matrix.put("schema", "4");
        matrix.put("app.version", safeVersion);
        matrix.put("source.commit", request.sourceCommit());
        matrix.put("portable.path", releaseJar.getFileName().toString());
        matrix.put("portable.sha256", NativePackageStager.sha256(releaseJar));
        matrix.put("sbom.sha256", mavenSbomDigest);
        for (NativeTarget target : NativeTarget.values()) {
            TargetResult result = targets.get(target);
            String filename = "coffee-gb-" + safeVersion + "-" + target.id()
                    + "." + result.packageType().id();
            Path destination = output.resolve(filename);
            Files.copy(result.artifact(), destination, StandardCopyOption.COPY_ATTRIBUTES);
            matrix.put("target." + target.id() + ".path", filename);
            matrix.put(
                    "target." + target.id() + ".sha256",
                    NativePackageStager.sha256(destination));
            matrix.put(
                    "target." + target.id() + ".signing",
                    required(result.properties(), "signing"));
            matrix.put(
                    "target." + target.id() + ".native-sbom.sha256",
                    NativePackageStager.sha256(result.nativeSbom()));
            if (result.signature() != null) {
                String signatureFilename = filename + ".asc";
                Path signatureDestination = output.resolve(signatureFilename);
                Files.copy(
                        result.signature(),
                        signatureDestination,
                        StandardCopyOption.COPY_ATTRIBUTES);
                matrix.put(
                        "target." + target.id() + ".signature.path",
                        signatureFilename);
                matrix.put(
                        "target." + target.id() + ".signature.sha256",
                        NativePackageStager.sha256(signatureDestination));
            }
        }
        writeProperties(output.resolve(MATRIX_FILE), matrix);
        writeChecksums(output, output.resolve("SHA256SUMS"));
        verifyReleaseDirectory(output, request.version());
        System.out.println("Assembled complete native release at " + output);
        return output;
    }

    public static void verifyReleaseDirectory(Path directory, String expectedVersion)
            throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        Map<String, String> matrix =
                NativePackageVerifier.readStrictProperties(root.resolve(MATRIX_FILE));
        requireValue(matrix, "schema", "4");
        requireValue(matrix, "app.version", expectedVersion);
        String sourceCommit = required(matrix, "source.commit");
        if (!sourceCommit.matches("[0-9a-f]{40}")) {
            throw new IOException("Release source.commit is not a full Git object ID");
        }
        verifyMatrixFile(root, matrix, "portable");
        requireSha256(matrix, "sbom.sha256");
        Set<String> expectedKeys = new HashSet<>(Set.of(
                "schema",
                "app.version",
                "source.commit",
                "portable.path",
                "portable.sha256",
                "sbom.sha256"));
        for (NativeTarget target : NativeTarget.values()) {
            String prefix = "target." + target.id();
            Path artifact = verifyMatrixFile(root, matrix, prefix);
            expectedKeys.add(prefix + ".path");
            expectedKeys.add(prefix + ".sha256");
            expectedKeys.add(prefix + ".native-sbom.sha256");
            expectedKeys.add(prefix + ".signing");
            requireSha256(matrix, prefix + ".native-sbom.sha256");
            String suffix =
                    "." + NativePackageMetadata.target(target).defaultPackageType().id();
            if (!artifact.getFileName().toString().endsWith(suffix)) {
                throw new IOException(
                        "Release target " + target.id() + " has the wrong artifact suffix");
            }
            String signing = required(matrix, prefix + ".signing");
            if (!Set.of("unsigned", "verified-detached", "verified-embedded")
                    .contains(signing)) {
                throw new IOException("Invalid release signing state for " + target.id());
            }
            NativePackageVerifier.requireSigningStateForTarget(target, signing);
            if (signing.equals("verified-detached")) {
                Path signature = verifyMatrixFile(root, matrix, prefix + ".signature");
                if (!signature.getFileName().toString().endsWith(".asc")) {
                    throw new IOException(
                            "Detached release signature must use .asc for " + target.id());
                }
                expectedKeys.add(prefix + ".signature.path");
                expectedKeys.add(prefix + ".signature.sha256");
            }
        }
        if (!matrix.keySet().equals(expectedKeys)) {
            Set<String> missing = new TreeSet<>(expectedKeys);
            missing.removeAll(matrix.keySet());
            Set<String> unexpected = new TreeSet<>(matrix.keySet());
            unexpected.removeAll(expectedKeys);
            throw new IOException(
                    "Release matrix keys mismatch; missing=" + missing
                            + ", unexpected=" + unexpected);
        }
        for (Path entry : listBoundedReleaseEntries(root)) {
            if (entry.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
                throw new IOException(
                        "Release directory must not contain JSON artifacts: " + entry);
            }
        }
        verifyReleaseChecksums(root);
    }

    private static void requireSha256(Map<String, String> values, String key)
            throws IOException {
        String value = required(values, key);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IOException(key + " is not a lowercase SHA-256 digest");
        }
    }

    private static Path verifyMatrixFile(
            Path root, Map<String, String> matrix, String prefix) throws IOException {
        Path file = safeResolve(root, required(matrix, prefix + ".path"));
        requireRegularFile(file, prefix + " release file");
        requireValue(matrix, prefix + ".sha256", NativePackageStager.sha256(file));
        return file;
    }

    private static void verifyPortableNatives(Path jar) throws IOException {
        BoundedZipPreflight.verify(
                jar,
                NativePackageStager.MAX_APP_JAR_BYTES,
                NativePackageStager.MAX_APP_JAR_ENTRIES,
                "Portable Maven JAR");
        Map<String, NativeBundleEntry> required = new HashMap<>();
        for (NativeTarget target : NativeTarget.values()) {
            for (NativeBundleEntry entry : NativeBundleManifest.locked(target).entries()) {
                required.put(entry.resourcePath(), entry);
            }
        }
        try (JarFile file = new JarFile(jar.toFile(), false)) {
            verifyPortableEntryInventory(file.entries(), required.keySet());
            for (NativeBundleEntry entry : required.values()) {
                ZipEntry actual = file.getEntry(entry.resourcePath());
                if (actual == null
                        || actual.isDirectory()
                        || actual.getSize() != entry.byteSize()) {
                    throw new IOException(
                            "Portable JAR is missing locked native " + entry.resourcePath());
                }
                try (InputStream input = file.getInputStream(actual)) {
                    verifyPortableEntry(input, entry);
                }
            }
        }
    }

    static void verifyPortableEntryInventory(
            Enumeration<JarEntry> entries, Set<String> requiredPaths) throws IOException {
        Map<String, Integer> occurrences = new HashMap<>();
        requiredPaths.forEach(path -> occurrences.put(path, 0));
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            occurrences.computeIfPresent(name, (ignored, count) -> count + 1);
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".gb")
                    || lower.endsWith(".gbc")
                    || lower.endsWith(".rom")
                    || lower.endsWith(".sgb")) {
                throw new IOException("Portable release JAR contains a ROM: " + lower);
            }
        }
        for (Map.Entry<String, Integer> occurrence : occurrences.entrySet()) {
            if (occurrence.getValue() != 1) {
                throw new IOException(
                        "Portable JAR must contain exactly one locked native "
                                + occurrence.getKey());
            }
        }
    }

    static void verifyPortableEntry(InputStream input, NativeBundleEntry entry)
            throws IOException {
        String digest = BoundedArchiveEntry.sha256Exact(
                input,
                entry.byteSize(),
                NativeBundleManifest.MAX_ENTRY_BYTES,
                "Portable JAR native " + entry.resourcePath());
        if (!digest.equals(entry.sha256())) {
            throw new IOException(
                    "Portable JAR native digest mismatch: " + entry.resourcePath());
        }
    }

    static void verifyReleaseChecksums(Path root) throws IOException {
        Path checksumFile = root.resolve("SHA256SUMS");
        List<String> lines = NativePackageVerifier.readBoundedMetadataLines(
                checksumFile, "release SHA256SUMS");
        List<String> names = new ArrayList<>();
        for (String line : lines) {
            if (!line.matches("[0-9a-f]{64}  [^/\\\\\\r\\n]+")) {
                throw new IOException("Malformed release checksum line: " + line);
            }
            String name = line.substring(66);
            Path file = safeResolve(root, name);
            requireRegularFile(file, "checksummed release file");
            if (!line.substring(0, 64).equals(NativePackageStager.sha256(file))) {
                throw new IOException("Release checksum mismatch: " + name);
            }
            names.add(name);
        }
        if (!names.equals(names.stream().sorted().toList())
                || names.size() != new HashSet<>(names).size()) {
            throw new IOException("Release checksum paths must be unique and sorted");
        }
        Set<String> actual = listBoundedReleaseEntries(root).stream()
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> !path.equals(checksumFile))
                .map(path -> path.getFileName().toString())
                .collect(java.util.stream.Collectors.toSet());
        if (!actual.equals(Set.copyOf(names))) {
            throw new IOException("Release SHA256SUMS does not cover the exact release files");
        }
    }

    static List<Path> listBoundedReleaseEntries(Path root) throws IOException {
        try (Stream<Path> paths = Files.list(root)) {
            List<Path> entries = paths.limit(MAX_RELEASE_DIRECTORY_ENTRIES + 1L).toList();
            if (entries.size() > MAX_RELEASE_DIRECTORY_ENTRIES) {
                throw new IOException(
                        "Release directory exceeds "
                                + MAX_RELEASE_DIRECTORY_ENTRIES
                                + " top-level entries");
            }
            return entries;
        }
    }

    private static void writeChecksums(Path root, Path output) throws IOException {
        List<Path> files;
        try (Stream<Path> paths = Files.list(root)) {
            files = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !path.equals(output))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        StringBuilder contents = new StringBuilder();
        for (Path file : files) {
            contents.append(NativePackageStager.sha256(file))
                    .append("  ")
                    .append(file.getFileName())
                    .append('\n');
        }
        Files.writeString(
                output,
                contents.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    private static void writeProperties(Path output, Map<String, String> values)
            throws IOException {
        StringBuilder contents = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!entry.getKey().matches("[a-z][a-z0-9.-]*")
                    || entry.getValue().isBlank()
                    || entry.getValue().contains("\n")
                    || entry.getValue().contains("\r")) {
                throw new IOException("Unsafe release matrix property: " + entry.getKey());
            }
            contents.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        Files.writeString(
                output,
                contents.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    private static Path createFreshDirectory(Path output) throws IOException {
        Path absolute = output.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("Release output has no parent");
        }
        Files.createDirectories(parent);
        return Files.createDirectory(absolute);
    }

    private static Path safeResolve(Path root, String relative) throws IOException {
        if (!NativeBundleResolver.isSafeRelativePath(relative)) {
            throw new IOException("Unsafe release path: " + relative);
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("Release path escapes root: " + relative);
        }
        return resolved;
    }

    private static void requireRegularFile(Path file, String description) throws IOException {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " is not a regular non-symlink file: " + file);
        }
    }

    private static String required(Map<String, String> values, String key) throws IOException {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IOException("Required release property is missing: " + key);
        }
        return value;
    }

    private static void requireValue(Map<String, String> values, String key, String expected)
            throws IOException {
        String actual = required(values, key);
        if (!expected.equals(actual)) {
            throw new IOException(
                    "Release property " + key + " must equal " + expected + ", found " + actual);
        }
    }

    public record AssembleRequest(
            Path input, Path portableJar, Path output, String version, String sourceCommit) {

        public AssembleRequest {
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(portableJar, "portableJar");
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(sourceCommit, "sourceCommit");
        }
    }

    private record TargetResult(
            NativeTarget target,
            NativePackageMetadata.PackageType packageType,
            Path artifact,
            Path mavenSbom,
            Path nativeSbom,
            Path signature,
            Map<String, String> properties) {
    }

    private static final class Arguments {
        private final String command;
        private final Map<String, String> values;

        private Arguments(String command, Map<String, String> values) {
            this.command = command;
            this.values = Map.copyOf(values);
        }

        private static Arguments parse(String[] args) {
            if (args.length == 0) {
                throw new IllegalArgumentException(
                        "Usage: NativeReleaseTool assemble --input PATH --portable-jar PATH "
                                + "--output PATH --version VERSION --source-commit FULL_SHA");
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 1; index < args.length; index++) {
                String option = args[index];
                if (!option.startsWith("--") || index + 1 >= args.length) {
                    throw new IllegalArgumentException("Expected --option value, found " + option);
                }
                if (values.putIfAbsent(option, args[++index]) != null) {
                    throw new IllegalArgumentException("Duplicate option " + option);
                }
            }
            return new Arguments(args[0], values);
        }

        private String required(String option) {
            String value = values.get(option);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(option + " is required");
            }
            return value;
        }

        private Path requiredPath(String option) {
            return Path.of(required(option)).toAbsolutePath().normalize();
        }

        private void rejectUnused(Set<String> supported) {
            List<String> unknown = values.keySet().stream()
                    .filter(option -> !supported.contains(option))
                    .sorted()
                    .toList();
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("Unknown options: " + unknown);
            }
        }
    }
}
