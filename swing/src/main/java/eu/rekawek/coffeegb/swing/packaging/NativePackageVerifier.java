package eu.rekawek.coffeegb.swing.packaging;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Strict, host-independent inspection and launch smoke for a jpackage application payload.
 *
 * <p>The same verifier runs against jpackage's pre-installer image and the payload unpacked from
 * the final DEB, MSI, or DMG. It never follows symlinks or trusts package metadata to select a
 * target. A target and the authoritative Maven app/SBOM artifacts are supplied independently.
 */
public final class NativePackageVerifier {

    static final String RESULT_FILE = "PACKAGE-RESULT.properties";
    private static final int MAX_TREE_ENTRIES = 30_000;
    private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PROCESS_OUTPUT = 4 * 1024 * 1024;
    private static final Duration SMOKE_TIMEOUT = Duration.ofSeconds(45);
    private static final Set<String> ROM_SUFFIXES = Set.of(".gb", ".gbc", ".rom", ".sgb");
    private static final Set<String> SIGNING_SUFFIXES = Set.of(
            ".p12", ".pfx", ".pem", ".key", ".keystore", ".jks", ".mobileprovision");
    private static final Set<String> TEXT_SUFFIXES = Set.of(
            ".cfg",
            ".conf",
            ".desktop",
            ".ini",
            ".json",
            ".md",
            ".plist",
            ".properties",
            ".txt",
            ".xml",
            ".yaml",
            ".yml");
    private static final Pattern DEVELOPER_PATH = Pattern.compile(
            "(?i)(?:/home/[a-z0-9._-]+/|/Users/[a-z0-9._-]+/|[a-z]:\\\\Users\\\\[^\\\\]+\\\\)");
    private static final Pattern SECRET_MATERIAL = Pattern.compile(
            "(?i)(?:-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----"
                    + "|\\bAKIA[0-9A-Z]{16}\\b"
                    + "|\\bgithub_pat_[A-Za-z0-9_]{20,}\\b"
                    + "|\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"
                    + "|\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"
                    + "|\\b(?:api[_-]?key|access[_-]?token|client[_-]?secret|password)"
                    + "\\s*[:=]\\s*[\"']?[A-Za-z0-9+/=_-]{12,})");

    private NativePackageVerifier() {
    }

    public static void main(String[] args) {
        try {
            Arguments parsed = Arguments.parse(args);
            if (!"verify".equals(parsed.command)) {
                throw new IllegalArgumentException("First argument must be 'verify'");
            }
            NativeTarget nativeTarget = NativeTarget.fromId(parsed.required("--target"))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown --target; expected one of " + NativeTarget.supportedIds()));
            NativePackageMetadata.PackageType packageType =
                    NativePackageMetadata.packageType(parsed.required("--type"))
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "Unknown --type: " + parsed.required("--type")));
            NativePackageMetadata.target(nativeTarget).requireSupported(packageType);
            boolean runSmoke = parsed.flag("--run-smoke");
            parsed.rejectUnused(Set.of(
                    "--target",
                    "--type",
                    "--root",
                    "--source-app-jar",
                    "--source-sbom",
                    "--source-legal",
                    "--dist",
                    "--smoke-home",
                    "--run-smoke"));
            if (runSmoke && !parsed.has("--smoke-home")) {
                throw new IllegalArgumentException("--run-smoke requires --smoke-home");
            }
            VerificationResult result = verify(new VerificationRequest(
                    nativeTarget,
                    packageType,
                    parsed.requiredPath("--root"),
                    parsed.requiredPath("--source-app-jar"),
                    parsed.requiredPath("--source-sbom"),
                    parsed.requiredPath("--source-legal")));
            if (parsed.has("--dist")) {
                verifyDistribution(
                        parsed.requiredPath("--dist"), nativeTarget, packageType, result.appVersion());
            }
            if (runSmoke) {
                runSmokes(result, parsed.requiredPath("--smoke-home"));
            }
            System.out.println("Verified " + nativeTarget.id() + " package payload at "
                    + result.applicationRoot());
        } catch (IllegalArgumentException | IOException failure) {
            System.err.println("coffee-gb package verification: " + failure.getMessage());
            System.exit(2);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.err.println("coffee-gb package verification: interrupted");
            System.exit(130);
        }
    }

    public static VerificationResult verify(VerificationRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        NativePackageMetadata.Target target = NativePackageMetadata.target(request.target());
        target.requireSupported(request.packageType());
        requireRegularFile(request.sourceAppJar(), "source app JAR");
        requireRegularFile(request.sourceSbom(), "source SBOM");
        Path root = request.root().toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Package payload root is not a directory: " + root);
        }

        List<Path> payloadPaths = boundedWalk(root, "package payload");
        List<Path> manifests = payloadPaths.stream()
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> path.getFileName().toString().equals("package-manifest.properties"))
                .toList();
        if (manifests.size() != 1) {
            throw new IOException(
                    "Expected exactly one package-manifest.properties, found " + manifests);
        }
        Path appDirectory = manifests.get(0).getParent();
        Path container = appDirectory.getParent();
        if (container == null) {
            throw new IOException("Packaged app directory has no container");
        }
        Path runtime = container.resolve("runtime");
        Path applicationRoot = switch (target.hostOs()) {
            case LINUX -> {
                Path parent = container.getParent();
                if (parent == null) {
                    throw new IOException("Linux package app container has no application root");
                }
                yield parent;
            }
            case WINDOWS, MACOS -> container;
        };
        Path launcher = switch (target.hostOs()) {
            case LINUX -> applicationRoot.resolve("bin")
                    .resolve(NativePackageMetadata.APPLICATION_NAME);
            case WINDOWS -> applicationRoot.resolve(
                    NativePackageMetadata.APPLICATION_NAME + ".exe");
            case MACOS -> applicationRoot.resolve("MacOS")
                    .resolve(NativePackageMetadata.APPLICATION_NAME);
        };
        Path runtimeJava = runtime.resolve("bin").resolve(
                target.hostOs() == NativePackageMetadata.HostOs.WINDOWS ? "java.exe" : "java");
        requireRegularFile(launcher, "packaged launcher");
        requireRegularFile(runtimeJava, "packaged runtime java");
        requireRegularFile(runtime.resolve("lib").resolve("modules"), "packaged runtime modules");

        verifyPayloadPolicy(payloadPaths, root, appDirectory, runtime, request.target());

        Map<String, String> inventory = readStrictProperties(manifests.get(0));
        String sourceVersion =
                NativePackageStager.jarVersion(request.sourceAppJar(), "source app JAR");
        requireValue(inventory, "schema", "1");
        requireValue(inventory, "app.id", NativePackageMetadata.APPLICATION_ID);
        requireValue(inventory, "app.version", sourceVersion);
        requireValue(inventory, "target", request.target().id());
        requireValue(
                inventory,
                "native.fingerprint",
                NativeBundleManifest.locked(request.target()).fingerprint());
        requireValue(inventory, "signing.default", "unsigned");

        Path packagedJar = appDirectory.resolve("coffee-gb.jar");
        Path packagedSbom = appDirectory.resolve("coffee-gb-sbom.cdx.json");
        requireRegularFile(packagedJar, "packaged app JAR");
        requireRegularFile(packagedSbom, "packaged SBOM");
        String appDigest = NativePackageStager.sha256(packagedJar);
        String sbomDigest = NativePackageStager.sha256(packagedSbom);
        requireValue(inventory, "app.jar.sha256", appDigest);
        requireValue(inventory, "sbom.sha256", sbomDigest);
        if (!appDigest.equals(NativePackageStager.sha256(request.sourceAppJar()))) {
            throw new IOException("Packaged app JAR differs from Maven's neutral app JAR");
        }
        if (!sbomDigest.equals(NativePackageStager.sha256(request.sourceSbom()))) {
            throw new IOException("Packaged SBOM differs from Maven's CycloneDX SBOM");
        }
        NativePackageStager.verifyNeutralAppJar(packagedJar);
        ThirdPartyNoticeInventory.validate(packagedSbom, request.sourceLegal());
        ThirdPartyNoticeInventory.verifyEmbeddedLegal(packagedJar, request.sourceLegal());
        verifySbom(packagedSbom, sourceVersion);
        verifyNativeInventory(appDirectory, request.target());
        verifyLegalInventory(appDirectory.resolve("legal"), request.sourceLegal());

        return new VerificationResult(
                request.target(),
                request.packageType(),
                sourceVersion,
                applicationRoot,
                appDirectory,
                runtime,
                runtimeJava,
                launcher);
    }

    public static void runSmokes(VerificationResult result, Path smokeHome)
            throws IOException, InterruptedException {
        Objects.requireNonNull(result, "result");
        Path home = smokeHome.toAbsolutePath().normalize();
        Files.createDirectories(home);
        try (Stream<Path> paths = Files.list(home)) {
            if (paths.findAny().isPresent()) {
                throw new IOException("Smoke home must be empty: " + home);
            }
        }
        Path nativeCache = Files.createDirectory(home.resolve("native-cache"));
        List<String> commonJvm = List.of(
                "-Djava.awt.headless=true",
                "-Duser.home=" + home,
                "-Dcoffee-gb.native.target=" + result.target().id(),
                "-Dcoffee-gb.native.source=" + result.appDirectory().resolve("native-source"),
                "-Dcoffee-gb.native.cache=" + nativeCache);
        List<String> version = new ArrayList<>();
        version.add(result.runtimeJava().toString());
        version.addAll(commonJvm);
        version.add("-jar");
        version.add(result.appDirectory().resolve("coffee-gb.jar").toString());
        version.add("--version");
        String versionOutput = run(version, Map.of());
        requireOutput(
                versionOutput, "Coffee GB " + result.appVersion(), "runtime --version");

        List<String> smoke = new ArrayList<>(version.subList(0, version.size() - 1));
        smoke.add("--package-smoke");
        String smokeOutput = run(smoke, Map.of());
        requireOutput(smokeOutput, "Coffee GB package smoke OK:", "runtime package smoke");

        String javaOptions = "-Djava.awt.headless=true -Duser.home=" + home
                + " -Dcoffee-gb.native.cache=" + nativeCache;
        String launcherVersion = run(
                List.of(result.launcher().toString(), "--version"),
                Map.of("_JAVA_OPTIONS", javaOptions));
        if (result.target() != NativeTarget.WINDOWS_X86_64) {
            requireOutput(
                    launcherVersion,
                    "Coffee GB " + result.appVersion(),
                    "packaged launcher --version");
        }
        String launcherSmoke = run(
                List.of(result.launcher().toString(), "--package-smoke"),
                Map.of("_JAVA_OPTIONS", javaOptions));
        if (result.target() != NativeTarget.WINDOWS_X86_64) {
            requireOutput(
                    launcherSmoke,
                    "Coffee GB package smoke OK:",
                    "packaged launcher package smoke");
        }
        System.out.println("Packaged launch smokes passed for " + result.target().id());
    }

    public static void writeBuildResult(
            Path dist,
            NativeTarget target,
            NativePackageMetadata.PackageType packageType,
            String version,
            Path primaryArtifact,
            Path sbom,
            String signing,
            Path signature)
            throws IOException {
        Path absoluteDist = dist.toAbsolutePath().normalize();
        Path artifact = primaryArtifact.toAbsolutePath().normalize();
        Path releaseSbom = sbom.toAbsolutePath().normalize();
        if (!artifact.startsWith(absoluteDist) || !releaseSbom.startsWith(absoluteDist)) {
            throw new IOException("Build-result files must be inside dist");
        }
        boolean artifactDirectory = Files.isDirectory(artifact, LinkOption.NOFOLLOW_LINKS);
        if (!artifactDirectory) {
            requireRegularFile(artifact, "primary package artifact");
        }
        requireRegularFile(releaseSbom, "release SBOM");
        Map<String, String> values = new LinkedHashMap<>();
        if (!Set.of("unsigned", "verified-detached", "verified-embedded").contains(signing)) {
            throw new IOException("Invalid verified signing state: " + signing);
        }
        requireSigningStateForTarget(target, signing);
        if (signing.equals("verified-detached") != (signature != null)) {
            throw new IOException(
                    "Only verified-detached signing may have a detached signature artifact");
        }
        values.put("schema", "2");
        values.put("target", target.id());
        values.put("package.type", packageType.id());
        values.put("app.version", version);
        values.put("signing", signing);
        values.put(
                "artifact.path",
                relativePortable(absoluteDist, artifact));
        values.put("artifact.kind", artifactDirectory ? "directory" : "file");
        if (!artifactDirectory) {
            values.put("artifact.sha256", NativePackageStager.sha256(artifact));
        }
        values.put("sbom.path", relativePortable(absoluteDist, releaseSbom));
        values.put("sbom.sha256", NativePackageStager.sha256(releaseSbom));
        if (signature != null) {
            Path detached = signature.toAbsolutePath().normalize();
            if (!detached.startsWith(absoluteDist)) {
                throw new IOException("Detached signature must be inside dist");
            }
            requireRegularFile(detached, "detached package signature");
            values.put("signature.path", relativePortable(absoluteDist, detached));
            values.put("signature.sha256", NativePackageStager.sha256(detached));
        }
        writeProperties(absoluteDist.resolve(RESULT_FILE), values);
    }

    public static Map<String, String> verifyDistribution(
            Path dist,
            NativeTarget expectedTarget,
            NativePackageMetadata.PackageType expectedType,
            String expectedVersion)
            throws IOException {
        Path root = dist.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Package dist is not a directory: " + root);
        }
        Path resultFile = root.resolve(RESULT_FILE);
        Path checksums = root.resolve("SHA256SUMS");
        Map<String, String> result = readStrictProperties(resultFile);
        requireValue(result, "schema", "2");
        requireValue(result, "target", expectedTarget.id());
        requireValue(result, "package.type", expectedType.id());
        requireValue(result, "app.version", expectedVersion);
        String signing = required(result, "signing");
        if (!Set.of("unsigned", "verified-detached", "verified-embedded").contains(signing)) {
            throw new IOException("Unknown package signing state: " + signing);
        }
        requireSigningStateForTarget(expectedTarget, signing);
        Path artifact = safeDistributionPath(root, required(result, "artifact.path"));
        String kind = required(result, "artifact.kind");
        Set<String> expectedKeys = new HashSet<>(Set.of(
                "schema",
                "target",
                "package.type",
                "app.version",
                "signing",
                "artifact.path",
                "artifact.kind",
                "sbom.path",
                "sbom.sha256"));
        if (signing.equals("verified-detached")) {
            expectedKeys.add("signature.path");
            expectedKeys.add("signature.sha256");
            Path signature =
                    safeDistributionPath(root, required(result, "signature.path"));
            requireRegularFile(signature, "detached package signature");
            if (!signature.getFileName().toString().endsWith(".asc")) {
                throw new IOException("Detached signature must use the .asc suffix");
            }
            requireValue(
                    result, "signature.sha256", NativePackageStager.sha256(signature));
        }
        if ("file".equals(kind)) {
            expectedKeys.add("artifact.sha256");
            requireRegularFile(artifact, "primary package artifact");
            requireValue(result, "artifact.sha256", NativePackageStager.sha256(artifact));
            String suffix = "." + expectedType.id();
            if (!artifact.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix)) {
                throw new IOException("Primary package artifact does not end with " + suffix);
            }
        } else if ("directory".equals(kind)) {
            if (expectedType != NativePackageMetadata.PackageType.APP_IMAGE
                    || !Files.isDirectory(artifact, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Invalid directory package artifact: " + artifact);
            }
        } else {
            throw new IOException("Unknown artifact.kind: " + kind);
        }
        requireExactKeys(result, expectedKeys, "package result");
        Path sbom = safeDistributionPath(root, required(result, "sbom.path"));
        requireRegularFile(sbom, "release SBOM");
        requireValue(result, "sbom.sha256", NativePackageStager.sha256(sbom));
        NativeTargetSbom.verifyReleaseBom(
                sbom, expectedTarget, expectedType, expectedVersion, artifact, signing);
        verifyChecksums(root, checksums);
        return result;
    }

    private static void verifyNativeInventory(Path appDirectory, NativeTarget target)
            throws IOException {
        Path nativeRoot = appDirectory.resolve("native-source");
        if (!Files.isDirectory(nativeRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Packaged native-source directory is missing");
        }
        Map<String, NativeBundleEntry> expected = NativeBundleManifest.locked(target)
                .entries()
                .stream()
                .collect(Collectors.toMap(NativeBundleEntry::resourcePath, entry -> entry));
        List<Path> nativePaths = boundedWalk(nativeRoot, "packaged native source");
        Map<String, Path> actual = nativePaths.stream()
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .collect(Collectors.toMap(
                        path -> relativePortable(nativeRoot, path),
                        path -> path));
        if (!actual.keySet().equals(expected.keySet())) {
            Set<String> missing = new TreeSet<>(expected.keySet());
            missing.removeAll(actual.keySet());
            Set<String> foreign = new TreeSet<>(actual.keySet());
            foreign.removeAll(expected.keySet());
            throw new IOException(
                    "Packaged native inventory mismatch; missing=" + missing + ", foreign=" + foreign);
        }
        for (Map.Entry<String, NativeBundleEntry> entry : expected.entrySet()) {
            Path file = actual.get(entry.getKey());
            NativeBundleEntry locked = entry.getValue();
            if (Files.size(file) != locked.byteSize()
                    || !NativePackageStager.sha256(file).equals(locked.sha256())) {
                throw new IOException("Packaged native digest mismatch: " + entry.getKey());
            }
        }
    }

    static void verifyLegalInventory(Path legal, Path sourceLegal) throws IOException {
        if (!Files.isDirectory(legal, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Packaged legal directory is missing");
        }
        if (!Files.isDirectory(sourceLegal, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Authoritative legal directory is missing: " + sourceLegal);
        }
        Map<String, Path> actual = boundedWalk(legal, "package legal inventory").stream()
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .collect(Collectors.toMap(path -> relativePortable(legal, path), path -> path));
        Map<String, Path> source = boundedWalk(sourceLegal, "authoritative legal inventory").stream()
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .collect(Collectors.toMap(
                        path -> relativePortable(sourceLegal, path), path -> path));
        Set<String> expected = ThirdPartyNoticeInventory.expectedLegalFiles(sourceLegal);
        if (!actual.keySet().equals(expected) || !source.keySet().equals(expected)) {
            throw new IOException(
                    "Packaged or authoritative legal inventory mismatch; packaged="
                            + actual.keySet() + ", authoritative=" + source.keySet());
        }
        for (String relative : expected) {
            if (!NativePackageStager.sha256(actual.get(relative))
                    .equals(NativePackageStager.sha256(source.get(relative)))) {
                throw new IOException("Packaged legal notice differs from source: " + relative);
            }
        }
    }

    static void verifyPayloadPolicy(
            Path payloadRoot,
            Path appDirectory,
            Path runtime,
            NativeTarget target)
            throws IOException {
        Path root = payloadRoot.toAbsolutePath().normalize();
        verifyPayloadPolicy(
                boundedWalk(root, "package payload"),
                root,
                appDirectory.toAbsolutePath().normalize(),
                runtime.toAbsolutePath().normalize(),
                target);
    }

    private static void verifyPayloadPolicy(
            List<Path> payloadPaths,
            Path payloadRoot,
            Path appDirectory,
            Path runtime,
            NativeTarget target)
            throws IOException {
        assertNoUnexpectedLinks(payloadPaths, appDirectory);
        long runtimeCount = payloadPaths.stream()
                .filter(path -> path.getFileName().toString().equals("modules"))
                .filter(path -> path.getParent() != null
                        && path.getParent().getFileName().toString().equals("lib"))
                .filter(path -> path.getParent().getParent() != null
                        && path.getParent().getParent().getFileName().toString().equals("runtime"))
                .count();
        if (runtimeCount != 1) {
            throw new IOException("Expected one packaged runtime, found " + runtimeCount);
        }
        verifyForbiddenContent(payloadPaths, payloadRoot, runtime, target);
    }

    private static void verifyForbiddenContent(
            List<Path> payloadPaths,
            Path payloadRoot,
            Path runtime,
            NativeTarget target)
            throws IOException {
        Set<String> foreignNativeSuffixes = foreignNativeSuffixes(target);
        for (Path path : payloadPaths) {
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                if (endsWith(name, ROM_SUFFIXES)) {
                    throw new IOException("Packaged payload contains a ROM-like file: "
                            + relativePortable(payloadRoot, path));
                }
                if (endsWith(name, SIGNING_SUFFIXES)) {
                    throw new IOException("Packaged payload contains signing material: "
                            + relativePortable(payloadRoot, path));
                }
                if (endsWith(name, foreignNativeSuffixes)) {
                    throw new IOException("Packaged payload contains a foreign native library: "
                            + relativePortable(payloadRoot, path));
                }
            }
            if (path.startsWith(runtime)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !endsWith(name, TEXT_SUFFIXES)
                    || Files.size(path) > MAX_TEXT_BYTES) {
                continue;
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            String normalizedText = text.replace("\\\\", "\\");
            if (DEVELOPER_PATH.matcher(text).find()
                    || DEVELOPER_PATH.matcher(normalizedText).find()) {
                throw new IOException("Packaged text contains a developer home path: "
                        + relativePortable(payloadRoot, path));
            }
            if (SECRET_MATERIAL.matcher(text).find()) {
                throw new IOException("Packaged text contains secret-like material: "
                        + relativePortable(payloadRoot, path));
            }
        }
    }

    private static Set<String> foreignNativeSuffixes(NativeTarget target) {
        return switch (target) {
            case LINUX_X86_64 -> Set.of(".dll", ".dylib", ".jnilib");
            case WINDOWS_X86_64 -> Set.of(".so", ".dylib", ".jnilib");
            case MACOS_X86_64, MACOS_AARCH64 -> Set.of(".dll", ".so");
        };
    }

    private static void assertNoUnexpectedLinks(List<Path> paths, Path appDirectory)
            throws IOException {
        for (Path path : paths) {
            if (path.startsWith(appDirectory) && Files.isSymbolicLink(path)) {
                throw new IOException("Packaged application input contains a symlink: " + path);
            }
        }
    }

    private static void verifySbom(Path sbom, String version) throws IOException {
        String contents = Files.readString(sbom, StandardCharsets.UTF_8);
        if (!contents.contains("\"bomFormat\"")
                || !contents.contains("\"CycloneDX\"")
                || !contents.contains("\"specVersion\"")
                || !contents.contains("\"" + version + "\"")) {
            throw new IOException("SBOM does not describe Coffee GB " + version);
        }
    }

    private static void verifyChecksums(Path root, Path checksumFile) throws IOException {
        requireRegularFile(checksumFile, "SHA256SUMS");
        List<String> lines = Files.readAllLines(checksumFile, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IOException("SHA256SUMS is empty");
        }
        List<String> relatives = new ArrayList<>();
        Set<String> duplicates = new HashSet<>();
        for (String line : lines) {
            if (!line.matches("[0-9a-f]{64}  [^\\r\\n]+")) {
                throw new IOException("Malformed checksum line: " + line);
            }
            String relative = line.substring(66);
            if (!duplicates.add(relative)) {
                throw new IOException("Duplicate checksum path: " + relative);
            }
            Path file = safeDistributionPath(root, relative);
            requireRegularFile(file, "checksummed package file");
            if (!line.substring(0, 64).equals(NativePackageStager.sha256(file))) {
                throw new IOException("Checksum mismatch: " + relative);
            }
            relatives.add(relative);
        }
        if (!relatives.equals(relatives.stream().sorted().toList())) {
            throw new IOException("SHA256SUMS paths are not sorted");
        }
        Set<String> actual = boundedWalk(root, "package dist").stream()
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> !path.equals(checksumFile))
                .map(path -> relativePortable(root, path))
                .collect(Collectors.toSet());
        if (!duplicates.equals(actual)) {
            Set<String> missing = new TreeSet<>(actual);
            missing.removeAll(duplicates);
            Set<String> stale = new TreeSet<>(duplicates);
            stale.removeAll(actual);
            throw new IOException(
                    "SHA256SUMS coverage mismatch; missing=" + missing + ", stale=" + stale);
        }
    }

    private static List<Path> boundedWalk(Path root, String description) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> result = paths.limit(MAX_TREE_ENTRIES + 1L).toList();
            if (result.size() > MAX_TREE_ENTRIES) {
                throw new IOException(description + " exceeds " + MAX_TREE_ENTRIES + " entries");
            }
            return result;
        }
    }

    private static String run(List<String> command, Map<String, String> environment)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (output.size() + read > MAX_PROCESS_OUTPUT) {
                        process.destroyForcibly();
                        readFailure.set(new IOException(
                                command.get(0) + " produced excessive output"));
                        return;
                    }
                    output.write(buffer, 0, read);
                }
            } catch (IOException failure) {
                readFailure.set(failure);
            }
        }, "coffee-gb-package-smoke-output");
        reader.setDaemon(true);
        reader.start();
        if (!process.waitFor(SMOKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            reader.join(5_000);
            throw new IOException(command.get(0) + " exceeded "
                    + SMOKE_TIMEOUT.toSeconds() + " second smoke deadline");
        }
        reader.join(5_000);
        IOException failure = readFailure.get();
        if (failure != null) {
            throw failure;
        }
        String captured = output.toString(StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IOException(command.get(0) + " exited " + process.exitValue()
                    + ": " + captured.strip());
        }
        return captured;
    }

    private static void requireOutput(String output, String expected, String description)
            throws IOException {
        if (!output.contains(expected)) {
            throw new IOException(description + " did not report '" + expected + "': "
                    + output.strip());
        }
    }

    static Map<String, String> readStrictProperties(Path file) throws IOException {
        requireRegularFile(file, "properties file");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new IOException("Malformed properties line in " + file + ": " + line);
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!key.matches("[a-z][a-z0-9.-]*") || value.isBlank()) {
                throw new IOException("Unsafe properties entry in " + file + ": " + line);
            }
            if (result.putIfAbsent(key, value) != null) {
                throw new IOException("Duplicate properties key in " + file + ": " + key);
            }
        }
        return Map.copyOf(result);
    }

    private static void writeProperties(Path output, Map<String, String> values)
            throws IOException {
        StringBuilder contents = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!entry.getKey().matches("[a-z][a-z0-9.-]*")
                    || entry.getValue().isBlank()
                    || entry.getValue().contains("\n")
                    || entry.getValue().contains("\r")) {
                throw new IOException("Unsafe build-result property: " + entry.getKey());
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

    private static Path safeDistributionPath(Path root, String relative) throws IOException {
        if (!NativeBundleResolver.isSafeRelativePath(relative)) {
            throw new IOException("Unsafe distribution path: " + relative);
        }
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) {
            throw new IOException("Distribution path escapes root: " + relative);
        }
        return path;
    }

    private static String required(Map<String, String> values, String key) throws IOException {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IOException("Required package property is missing: " + key);
        }
        return value;
    }

    private static void requireValue(Map<String, String> values, String key, String expected)
            throws IOException {
        String actual = required(values, key);
        if (!expected.equals(actual)) {
            throw new IOException(
                    "Package property " + key + " must equal " + expected + ", found " + actual);
        }
    }

    private static void requireSigningStateForTarget(
            NativeTarget target, String signing) throws IOException {
        boolean linux = target == NativeTarget.LINUX_X86_64;
        if ((signing.equals("verified-detached") && !linux)
                || (signing.equals("verified-embedded") && linux)) {
            throw new IOException(
                    "Signing state " + signing + " is invalid for target " + target.id());
        }
    }

    private static void requireExactKeys(
            Map<String, String> values, Set<String> expected, String description)
            throws IOException {
        if (!values.keySet().equals(expected)) {
            Set<String> missing = new TreeSet<>(expected);
            missing.removeAll(values.keySet());
            Set<String> unexpected = new TreeSet<>(values.keySet());
            unexpected.removeAll(expected);
            throw new IOException(
                    description + " keys mismatch; missing=" + missing
                            + ", unexpected=" + unexpected);
        }
    }

    private static void requireRegularFile(Path file, String description) throws IOException {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " is not a regular non-symlink file: " + file);
        }
    }

    private static boolean endsWith(String value, Set<String> suffixes) {
        return suffixes.stream().anyMatch(value::endsWith);
    }

    private static String relativePortable(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    public record VerificationRequest(
            NativeTarget target,
            NativePackageMetadata.PackageType packageType,
            Path root,
            Path sourceAppJar,
            Path sourceSbom,
            Path sourceLegal) {

        public VerificationRequest {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(packageType, "packageType");
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(sourceAppJar, "sourceAppJar");
            Objects.requireNonNull(sourceSbom, "sourceSbom");
            Objects.requireNonNull(sourceLegal, "sourceLegal");
        }
    }

    public record VerificationResult(
            NativeTarget target,
            NativePackageMetadata.PackageType packageType,
            String appVersion,
            Path applicationRoot,
            Path appDirectory,
            Path runtime,
            Path runtimeJava,
            Path launcher) {
    }

    private static final class Arguments {
        private final String command;
        private final Map<String, String> values;
        private final Set<String> flags;

        private Arguments(String command, Map<String, String> values, Set<String> flags) {
            this.command = command;
            this.values = Map.copyOf(values);
            this.flags = Set.copyOf(flags);
        }

        private static Arguments parse(String[] args) {
            if (args.length == 0) {
                throw new IllegalArgumentException(
                        "Usage: NativePackageVerifier verify --target ID --type TYPE "
                                + "--root PATH --source-app-jar PATH --source-sbom PATH "
                                + "--source-legal PATH "
                                + "[--dist PATH] [--run-smoke --smoke-home PATH]");
            }
            Map<String, String> values = new HashMap<>();
            Set<String> flags = new HashSet<>();
            for (int index = 1; index < args.length; index++) {
                String option = args[index];
                if ("--run-smoke".equals(option)) {
                    if (!flags.add(option)) {
                        throw new IllegalArgumentException("Duplicate option " + option);
                    }
                    continue;
                }
                if (!option.startsWith("--") || index + 1 >= args.length) {
                    throw new IllegalArgumentException("Expected --option value, found " + option);
                }
                if (values.putIfAbsent(option, args[++index]) != null) {
                    throw new IllegalArgumentException("Duplicate option " + option);
                }
            }
            return new Arguments(args[0], values, flags);
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

        private boolean has(String option) {
            return values.containsKey(option) || flags.contains(option);
        }

        private boolean flag(String option) {
            return flags.contains(option);
        }

        private void rejectUnused(Set<String> supported) {
            List<String> unknown = Stream.concat(values.keySet().stream(), flags.stream())
                    .filter(option -> !supported.contains(option))
                    .sorted()
                    .toList();
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("Unknown options: " + unknown);
            }
        }
    }
}
