package eu.rekawek.coffeegb.swing.packaging;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Strict, host-independent inspection and launch smoke for a jpackage application payload.
 *
 * <p>The same verifier runs against jpackage's pre-installer image and the payload unpacked from
 * the final DEB, EXE, or DMG. It never follows symlinks or trusts package metadata to select a
 * target. A target and the authoritative Maven app/SBOM artifacts are supplied independently.
 */
public final class NativePackageVerifier {

    static final String RESULT_FILE = "PACKAGE-RESULT.properties";
    private static final int MAX_TREE_ENTRIES = 30_000;
    private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    static final int MAX_METADATA_BYTES = 1024 * 1024;
    static final int MAX_METADATA_LINES = 10_000;
    private static final int MAX_ARCHIVE_DEPTH = 3;
    private static final int MAX_ARCHIVE_ENTRIES = 50_000;
    private static final int MAX_ARCHIVE_ENTRY_NAME_LENGTH = 4_096;
    private static final long MAX_ARCHIVE_CONTAINER_BYTES = 256L * 1024 * 1024;
    private static final long MAX_ARCHIVE_ENTRY_BYTES = 64L * 1024 * 1024;
    private static final long MAX_ARCHIVE_EXPANDED_BYTES = 512L * 1024 * 1024;
    private static final int MAX_PROCESS_OUTPUT = 4 * 1024 * 1024;
    private static final int MAX_JSON_DEPTH = 64;
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
    private static final Set<String> ARCHIVE_SUFFIXES = Set.of(".jar", ".zip");
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
        RuntimeLayout runtimeLayout = requireRuntimeLayout(
                container.resolve("runtime"), request.target());
        Path runtime = runtimeLayout.home();
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
        Path commandLauncher = target.hostOs() == NativePackageMetadata.HostOs.WINDOWS
                ? applicationRoot.resolve(
                        NativePackageMetadata.WINDOWS_CONSOLE_LAUNCHER_NAME + ".exe")
                : launcher;
        Path runtimeJava = runtimeLayout.javaExecutable();
        requireRegularFile(launcher, "packaged launcher");
        requireRegularFile(commandLauncher, "packaged command launcher");

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
        Path packagedNativeSbom =
                appDirectory.resolve(NativeComponentInventory.STAGED_NATIVE_SBOM);
        Path nativeSource = appDirectory.resolve("native-source.zip");
        requireRegularFile(packagedJar, "packaged app JAR");
        requireRegularFile(packagedSbom, "packaged SBOM");
        requireRegularFile(packagedNativeSbom, "packaged target-native SBOM");
        requireRegularFile(nativeSource, "packaged native-source archive");
        String appDigest = NativePackageStager.sha256(packagedJar);
        String sbomDigest = NativePackageStager.sha256(packagedSbom);
        requireValue(inventory, "app.jar.sha256", appDigest);
        requireValue(inventory, "sbom.sha256", sbomDigest);
        requireValue(inventory, "native.source-format", "stored-zip");
        requireValue(
                inventory, "native.source.sha256", NativePackageStager.sha256(nativeSource));
        requireValue(
                inventory,
                "native.sbom.sha256",
                NativePackageStager.sha256(packagedNativeSbom));
        if (!appDigest.equals(NativePackageStager.sha256(request.sourceAppJar()))) {
            throw new IOException("Packaged app JAR differs from Maven's neutral app JAR");
        }
        String canonicalSourceSbom = canonicalizeMavenSbom(
                ThirdPartyNoticeInventory.readBounded(
                        request.sourceSbom(),
                        ThirdPartyNoticeInventory.MAX_SBOM_BYTES,
                        "source CycloneDX Maven SBOM"));
        if (!sbomDigest.equals(NativePackageStager.sha256(
                canonicalSourceSbom.getBytes(StandardCharsets.UTF_8)))) {
            throw new IOException("Packaged SBOM differs from Maven's CycloneDX SBOM");
        }
        NativePackageStager.verifyNeutralAppJar(packagedJar);
        ThirdPartyNoticeInventory.validate(packagedSbom, request.sourceLegal());
        ThirdPartyNoticeInventory.verifyEmbeddedLegal(packagedJar, request.sourceLegal());
        verifyMavenSbom(packagedSbom, sourceVersion);
        NativeComponentInventory.verifyNativeSbom(
                packagedNativeSbom, request.target(), sourceVersion);
        verifyNativeInventory(nativeSource, request.target());
        verifyLegalInventory(
                appDirectory.resolve("legal"), request.sourceLegal(), request.target());

        return new VerificationResult(
                request.target(),
                request.packageType(),
                sourceVersion,
                applicationRoot,
                appDirectory,
                runtime,
                runtimeJava,
                launcher,
                commandLauncher,
                nativeSource);
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
        SmokeCacheLayout smokeCaches = createSmokeCaches(home);
        List<String> commonJvm = List.of(
                "-Djava.awt.headless=true",
                "-Duser.home=" + home,
                "-Dcoffee-gb.native.target=" + result.target().id(),
                "-Dcoffee-gb.native.source=" + result.nativeSource(),
                "-Dcoffee-gb.native.cache=" + smokeCaches.directRuntime());
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
        requireOutput(
                smokeOutput,
                "native-target=" + result.target().id(),
                "runtime package smoke native target");

        String javaOptions = "-Djava.awt.headless=true -Duser.home=" + home
                + " -Dcoffee-gb.native.cache=" + smokeCaches.packagedLauncher();
        String launcherVersion = run(
                List.of(result.commandLauncher().toString(), "--version"),
                Map.of("_JAVA_OPTIONS", javaOptions));
        requireOutput(
                launcherVersion,
                "Coffee GB " + result.appVersion(),
                "packaged launcher --version");
        String launcherSmoke = run(
                List.of(result.commandLauncher().toString(), "--package-smoke"),
                Map.of("_JAVA_OPTIONS", javaOptions));
        requireOutput(
                launcherSmoke,
                "Coffee GB package smoke OK:",
                "packaged launcher package smoke");
        requireOutput(
                launcherSmoke,
                "native-target=" + result.target().id(),
                "packaged launcher package smoke native target");
        if (desktopSmokeEnabled(System.getenv())) {
            runDesktopSmoke(result, home, smokeCaches.desktopNormal(), false);
            runDesktopSmoke(result, home, smokeCaches.desktopDebug(), true);
        }
        System.out.println("Packaged launch smokes passed for " + result.target().id());
    }

    static SmokeCacheLayout createSmokeCaches(Path home) throws IOException {
        return new SmokeCacheLayout(
                Files.createDirectory(home.resolve("direct-runtime-native-cache")),
                Files.createDirectory(home.resolve("packaged-launcher-native-cache")),
                Files.createDirectory(home.resolve("desktop-normal-native-cache")),
                Files.createDirectory(home.resolve("desktop-debug-native-cache")));
    }

    record SmokeCacheLayout(
            Path directRuntime,
            Path packagedLauncher,
            Path desktopNormal,
            Path desktopDebug) {
    }

    static boolean desktopSmokeEnabled(Map<String, String> environment) {
        return "true".equalsIgnoreCase(
                environment.getOrDefault("COFFEE_GB_DESKTOP_SMOKE", "").strip());
    }

    private static void runDesktopSmoke(
            VerificationResult result, Path home, Path nativeCache, boolean debug)
            throws IOException, InterruptedException {
        String suffix = debug ? "debug" : "normal";
        Path desktopHome = Files.createDirectory(home.resolve(suffix + "-desktop-home"));
        Path marker = home.resolve(suffix + "-desktop-ready.marker");
        String javaOptions = "-Djava.awt.headless=false -Duser.home=" + desktopHome
                + " -Dcoffee-gb.native.cache=" + nativeCache;
        List<String> command = new ArrayList<>();
        if (debug && result.target() == NativeTarget.WINDOWS_X86_64) {
            // The secondary Windows launcher defaults to --debug and has a real console. Passing
            // no argument proves that installed launcher configuration as well as startup.
            command.add(result.commandLauncher().toString());
        } else {
            command.add(result.launcher().toString());
            if (debug) {
                command.add("--debug");
            }
        }
        run(
                command,
                Map.of(
                        "_JAVA_OPTIONS", javaOptions,
                        "COFFEE_GB_DESKTOP_SMOKE_MARKER", marker.toString()));
        long deadline = System.nanoTime() + SMOKE_TIMEOUT.toNanos();
        while (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        requireRegularFile(marker, "desktop startup smoke marker");
        String evidence = Files.readString(marker, StandardCharsets.UTF_8);
        requireOutput(
                evidence,
                "Coffee GB desktop ready OK:",
                debug ? "packaged debug-console startup" : "packaged desktop startup");
    }

    public static void writeBuildResult(
            Path dist,
            NativeTarget target,
            NativePackageMetadata.PackageType packageType,
            String version,
            Path primaryArtifact,
            Path sbom,
            Path nativeSbom,
            String signing,
            Path signature)
            throws IOException {
        Path absoluteDist = dist.toAbsolutePath().normalize();
        Path artifact = primaryArtifact.toAbsolutePath().normalize();
        Path releaseSbom = sbom.toAbsolutePath().normalize();
        Path releaseNativeSbom = nativeSbom.toAbsolutePath().normalize();
        if (!artifact.startsWith(absoluteDist)
                || !releaseSbom.startsWith(absoluteDist)
                || !releaseNativeSbom.startsWith(absoluteDist)) {
            throw new IOException("Build-result files must be inside dist");
        }
        boolean artifactDirectory = Files.isDirectory(artifact, LinkOption.NOFOLLOW_LINKS);
        if (!artifactDirectory) {
            requireRegularFile(artifact, "primary package artifact");
        }
        requireRegularFile(releaseSbom, "release SBOM");
        requireRegularFile(releaseNativeSbom, "release target-native SBOM");
        verifyMavenSbom(releaseSbom, version);
        NativeComponentInventory.verifyNativeSbom(
                releaseNativeSbom, target, version);
        Map<String, String> values = new LinkedHashMap<>();
        if (!Set.of("unsigned", "verified-detached", "verified-embedded").contains(signing)) {
            throw new IOException("Invalid verified signing state: " + signing);
        }
        requireSigningStateForTarget(target, signing);
        if (signing.equals("verified-detached") != (signature != null)) {
            throw new IOException(
                    "Only verified-detached signing may have a detached signature artifact");
        }
        values.put("schema", "3");
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
        values.put(
                "native-sbom.path",
                relativePortable(absoluteDist, releaseNativeSbom));
        values.put(
                "native-sbom.sha256",
                NativePackageStager.sha256(releaseNativeSbom));
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
        requireValue(result, "schema", "3");
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
                "sbom.sha256",
                "native-sbom.path",
                "native-sbom.sha256"));
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
        requireFileName(
                sbom, NativePackageMetadata.releaseSbomFileName(expectedVersion), "release SBOM");
        verifyMavenSbom(sbom, expectedVersion);
        Path nativeSbom =
                safeDistributionPath(root, required(result, "native-sbom.path"));
        requireRegularFile(nativeSbom, "release target-native SBOM");
        requireValue(
                result,
                "native-sbom.sha256",
                NativePackageStager.sha256(nativeSbom));
        requireFileName(
                nativeSbom,
                NativePackageMetadata.releaseNativeSbomFileName(
                        expectedVersion, expectedTarget),
                "release target-native SBOM");
        NativeComponentInventory.verifyNativeSbom(
                nativeSbom, expectedTarget, expectedVersion);
        verifyChecksums(root, checksums);
        return result;
    }

    private static void verifyNativeInventory(Path nativeSource, NativeTarget target)
            throws IOException {
        LockedNativeArchive.verify(nativeSource, NativeBundleManifest.locked(target));
    }

    static void verifyLegalInventory(
            Path legal, Path sourceLegal, NativeTarget target) throws IOException {
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
        Set<String> expected = NativeComponentInventory.requiredLegalPaths(target);
        if (!actual.keySet().equals(expected) || !source.keySet().containsAll(expected)) {
            throw new IOException(
                    "Packaged or authoritative legal inventory mismatch; packaged="
                            + actual.keySet() + ", required=" + expected);
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
        Path expectedRuntimeModules = runtime.resolve("lib").resolve("modules").normalize();
        Set<Path> runtimeModuleCandidates = payloadPaths.stream()
                .filter(path -> path.getFileName().toString().equalsIgnoreCase("modules"))
                .filter(path -> path.getParent() != null
                        && path.getParent().getFileName().toString().equalsIgnoreCase("lib"))
                .collect(Collectors.toUnmodifiableSet());
        if (!runtimeModuleCandidates.equals(Set.of(expectedRuntimeModules))
                || !Files.isRegularFile(
                        expectedRuntimeModules, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(expectedRuntimeModules)) {
            throw new IOException(
                    "Expected only the designated packaged runtime modules file, found "
                            + runtimeModuleCandidates);
        }
        Path lockedNativeArchive = appDirectory.resolve("native-source.zip");
        boolean verifiedLockedNativeArchive =
                Files.isRegularFile(lockedNativeArchive, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(lockedNativeArchive);
        if (verifiedLockedNativeArchive) {
            LockedNativeArchive.verify(
                    lockedNativeArchive, NativeBundleManifest.locked(target));
        }
        verifyForbiddenContent(
                payloadPaths,
                payloadRoot,
                runtime,
                target,
                verifiedLockedNativeArchive ? lockedNativeArchive : null);
    }

    private static void verifyForbiddenContent(
            List<Path> payloadPaths,
            Path payloadRoot,
            Path runtime,
            NativeTarget target,
            Path lockedNativeArchive)
            throws IOException {
        Set<String> foreignNativeSuffixes = foreignNativeSuffixes(target);
        Set<String> lockedNativeEntries = NativeBundleManifest.locked(target).entries().stream()
                .map(NativeBundleEntry::resourcePath)
                .collect(Collectors.toUnmodifiableSet());
        ArchiveBudget archiveBudget = new ArchiveBudget();
        for (Path path : payloadPaths) {
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                String relative = relativePortable(payloadRoot, path);
                verifyForbiddenName(name, relative, foreignNativeSuffixes);
                if (!path.startsWith(runtime) && endsWith(name, ARCHIVE_SUFFIXES)) {
                    verifyArchive(
                            path,
                            relative,
                            1,
                            foreignNativeSuffixes,
                            archiveBudget,
                            path.equals(lockedNativeArchive)
                                    ? lockedNativeEntries
                                    : Set.of());
                }
            }
            if (path.startsWith(runtime)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !endsWith(name, TEXT_SUFFIXES)) {
                continue;
            }
            long textBytes = Files.size(path);
            if (textBytes > MAX_TEXT_BYTES) {
                throw new IOException(
                        "Packaged text exceeds the bounded inspection size: "
                                + relativePortable(payloadRoot, path));
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            verifyTextContent(text, relativePortable(payloadRoot, path), "Packaged text");
        }
    }

    private static void verifyArchive(
            Path archive,
            String archivePath,
            int depth,
            Set<String> foreignNativeSuffixes,
            ArchiveBudget budget,
            Set<String> opaqueLockedNativeEntries)
            throws IOException {
        if (Files.isSymbolicLink(archive)
                || !Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Packaged archive is not a regular non-symlink file: " + archivePath);
        }
        long archiveBytes = Files.size(archive);
        if (archiveBytes <= 0 || archiveBytes > MAX_ARCHIVE_CONTAINER_BYTES) {
            throw new IOException(
                    "Packaged archive exceeds the bounded container size: " + archivePath);
        }
        BoundedZipPreflight.verify(
                archive,
                MAX_ARCHIVE_CONTAINER_BYTES,
                MAX_ARCHIVE_ENTRIES,
                "Packaged archive " + archivePath);
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            verifyArchive(
                    zip,
                    archivePath,
                    depth,
                    foreignNativeSuffixes,
                    budget,
                    opaqueLockedNativeEntries);
        }
    }

    private static void verifyArchive(
            byte[] contents,
            String archivePath,
            int depth,
            Set<String> foreignNativeSuffixes,
            ArchiveBudget budget)
            throws IOException {
        BoundedZipPreflight.verify(
                contents,
                MAX_ARCHIVE_ENTRY_BYTES,
                MAX_ARCHIVE_ENTRIES,
                "Nested packaged archive " + archivePath);
        try (SeekableInMemoryByteChannel channel =
                        new SeekableInMemoryByteChannel(contents);
                ZipFile zip = ZipFile.builder()
                        .setSeekableByteChannel(channel)
                        .get()) {
            verifyArchive(
                    zip,
                    archivePath,
                    depth,
                    foreignNativeSuffixes,
                    budget,
                    Set.of());
        }
    }

    private static void verifyArchive(
            ZipFile archive,
            String archivePath,
            int depth,
            Set<String> foreignNativeSuffixes,
            ArchiveBudget budget,
            Set<String> opaqueLockedNativeEntries)
            throws IOException {
        if (depth > MAX_ARCHIVE_DEPTH) {
            throw new IOException("Packaged archive nesting exceeds " + MAX_ARCHIVE_DEPTH
                    + " levels: " + archivePath);
        }
        Set<String> entryNames = new HashSet<>();
        Enumeration<ZipArchiveEntry> entries = archive.getEntriesInPhysicalOrder();
        while (entries.hasMoreElements()) {
            ZipArchiveEntry entry = entries.nextElement();
            budget.addEntry(archivePath);
            String rawName = entry.getName();
            if (rawName.length() > MAX_ARCHIVE_ENTRY_NAME_LENGTH) {
                throw new IOException(
                        "Packaged archive entry name exceeds "
                                + MAX_ARCHIVE_ENTRY_NAME_LENGTH
                                + " characters: "
                                + archivePath);
            }
            String normalizedName = rawName.endsWith("/")
                    ? rawName.substring(0, rawName.length() - 1)
                    : rawName;
            if (!NativeBundleResolver.isSafeRelativePath(normalizedName)) {
                throw new IOException(
                        "Packaged archive contains an unsafe entry path: "
                                + archivePath + "!/" + rawName);
            }
            if (!entryNames.add(normalizedName)) {
                throw new IOException(
                        "Packaged archive contains a duplicate entry: "
                                + archivePath + "!/" + normalizedName);
            }
            if (entry.isUnixSymlink()
                    || (!entry.isDirectory() && !isRegularArchiveEntry(entry))) {
                throw new IOException(
                        "Packaged archive contains a non-regular entry: "
                                + archivePath + "!/" + normalizedName);
            }
            if (!archive.canReadEntryData(entry)) {
                throw new IOException(
                        "Packaged archive uses an unsupported entry format: "
                                + archivePath + "!/" + normalizedName);
            }
            if (entry.isDirectory()) {
                continue;
            }

            String entryPath = archivePath + "!/" + normalizedName;
            String lowerName = normalizedName.toLowerCase(Locale.ROOT);
            if (lowerName.equals("lib/modules") || lowerName.endsWith("/lib/modules")) {
                throw new IOException(
                        "Packaged archive contains a duplicate runtime modules file: "
                                + entryPath);
            }
            verifyForbiddenName(lowerName, entryPath, foreignNativeSuffixes);
            boolean opaqueLockedNative =
                    opaqueLockedNativeEntries.contains(normalizedName);
            boolean nestedArchive = endsWith(lowerName, ARCHIVE_SUFFIXES);
            byte[] contents;
            try (InputStream input = archive.getInputStream(entry)) {
                contents = readArchiveEntry(
                        input,
                        entryPath,
                        budget,
                        !opaqueLockedNative || nestedArchive);
            }
            if (nestedArchive) {
                verifyArchive(
                        contents,
                        entryPath,
                        depth + 1,
                        foreignNativeSuffixes,
                        budget);
            } else if (!opaqueLockedNative) {
                // ISO-8859-1 preserves every ASCII byte one-to-one, so developer paths and
                // credential-shaped constants are detected in text resources and class files
                // without trusting an archive entry's suffix or declared encoding.
                verifyTextContent(
                        new String(contents, StandardCharsets.ISO_8859_1),
                        entryPath,
                        "Packaged archive entry");
            }
        }
    }

    private static boolean isRegularArchiveEntry(ZipArchiveEntry entry) {
        int mode = entry.getUnixMode();
        return mode == 0 || (mode & 0170000) == 0100000;
    }

    private static byte[] readArchiveEntry(
            InputStream archive,
            String entryPath,
            ArchiveBudget budget,
            boolean capture)
            throws IOException {
        ByteArrayOutputStream contents = capture ? new ByteArrayOutputStream() : null;
        byte[] buffer = new byte[16 * 1024];
        long entryBytes = 0;
        int read;
        while ((read = archive.read(buffer)) >= 0) {
            entryBytes += read;
            budget.addExpandedBytes(read, entryPath);
            if (entryBytes > MAX_ARCHIVE_ENTRY_BYTES) {
                throw new IOException("Packaged archive entry exceeds "
                        + MAX_ARCHIVE_ENTRY_BYTES + " expanded bytes: " + entryPath);
            }
            if (contents != null) {
                contents.write(buffer, 0, read);
            }
        }
        return contents == null ? new byte[0] : contents.toByteArray();
    }

    private static void verifyForbiddenName(
            String lowerName, String displayPath, Set<String> foreignNativeSuffixes)
            throws IOException {
        if (endsWith(lowerName, ROM_SUFFIXES)) {
            throw new IOException("Packaged payload contains a ROM-like file: " + displayPath);
        }
        if (endsWith(lowerName, SIGNING_SUFFIXES)) {
            throw new IOException("Packaged payload contains signing material: " + displayPath);
        }
        if (endsWith(lowerName, foreignNativeSuffixes)
                || (foreignNativeSuffixes.contains(".so")
                        && isVersionedElfSharedObject(lowerName))) {
            throw new IOException(
                    "Packaged payload contains a foreign native library: " + displayPath);
        }
    }

    private static boolean isVersionedElfSharedObject(String lowerName) {
        int suffix = lowerName.lastIndexOf(".so.");
        return suffix >= 0 && suffix + 4 < lowerName.length();
    }

    private static void verifyTextContent(String text, String displayPath, String description)
            throws IOException {
        String normalizedText = text.replace("\\\\", "\\");
        if (DEVELOPER_PATH.matcher(text).find()
                || DEVELOPER_PATH.matcher(normalizedText).find()) {
            throw new IOException(
                    description + " contains a developer home path: " + displayPath);
        }
        if (SECRET_MATERIAL.matcher(text).find()) {
            throw new IOException(
                    description + " contains secret-like material: " + displayPath);
        }
    }

    private static final class ArchiveBudget {
        private int entries;
        private long expandedBytes;

        private void addEntry(String archivePath) throws IOException {
            entries++;
            if (entries > MAX_ARCHIVE_ENTRIES) {
                throw new IOException("Packaged archives exceed "
                        + MAX_ARCHIVE_ENTRIES + " aggregate entries at " + archivePath);
            }
        }

        private void addExpandedBytes(int bytes, String entryPath) throws IOException {
            expandedBytes += bytes;
            if (expandedBytes > MAX_ARCHIVE_EXPANDED_BYTES) {
                throw new IOException("Packaged archives exceed "
                        + MAX_ARCHIVE_EXPANDED_BYTES
                        + " aggregate expanded bytes at " + entryPath);
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

    static void verifyMavenSbom(Path sbom, String version) throws IOException {
        String contents = ThirdPartyNoticeInventory.readBounded(
                sbom,
                ThirdPartyNoticeInventory.MAX_SBOM_BYTES,
                "CycloneDX Maven SBOM");
        new StrictJsonParser(contents, "CycloneDX Maven SBOM").parse();
        requireJsonStringField(contents, "bomFormat", "CycloneDX", "CycloneDX Maven SBOM");
        requireJsonStringField(contents, "specVersion", "1.6", "CycloneDX Maven SBOM");
        String metadata = requireJsonObjectField(contents, "metadata", "CycloneDX Maven SBOM");
        String component = requireJsonObjectField(
                metadata, "component", "CycloneDX Maven SBOM metadata");
        String rootRef =
                "pkg:maven/eu.rekawek.coffeegb/swing@" + version + "?type=jar";
        requireJsonStringField(component, "type", "application", "Maven SBOM root component");
        requireJsonStringField(
                component, "group", "eu.rekawek.coffeegb", "Maven SBOM root component");
        requireJsonStringField(component, "name", "swing", "Maven SBOM root component");
        requireJsonStringField(component, "version", version, "Maven SBOM root component");
        requireJsonStringField(component, "bom-ref", rootRef, "Maven SBOM root component");
        requireJsonStringField(component, "purl", rootRef, "Maven SBOM root component");
        ThirdPartyNoticeInventory.resolvedThirdPartyPurls(contents);
    }

    /**
     * Produces the byte-stable Maven SBOM embedded in every native package.
     *
     * <p>CycloneDX writes host line endings. Repository text and generated Maven descriptors are
     * independently constrained to LF, so normalizing JSON whitespace line endings is sufficient
     * and every component hash and field remains byte-significant to the release gate.
     */
    static String canonicalizeMavenSbom(String contents) throws IOException {
        String description = "CycloneDX Maven SBOM";
        new StrictJsonParser(contents, description).parse();
        String canonical = contents.replace("\r\n", "\n").replace('\r', '\n');
        new StrictJsonParser(canonical, "canonical CycloneDX Maven SBOM").parse();
        return canonical;
    }

    static Set<String> directMavenComponentPurls(String contents) throws IOException {
        String description = "CycloneDX Maven SBOM";
        new StrictJsonParser(contents, description).parse();
        JsonValue components = requireTopLevelJsonField(contents, "components", description);
        if (contents.charAt(components.start()) != '[') {
            throw new IOException(description + " field components is not an array");
        }
        int arrayEnd = jsonCompositeEnd(
                contents, components.start(), '[', ']', description);
        Set<String> purls = new HashSet<>();
        int cursor = components.start() + 1;
        while (true) {
            cursor = skipJsonWhitespace(contents, cursor);
            if (cursor == arrayEnd - 1) {
                break;
            }
            if (cursor >= arrayEnd - 1 || contents.charAt(cursor) != '{') {
                throw new IOException(description + " components must contain only objects");
            }
            int componentEnd = jsonValueEnd(contents, cursor, description);
            String component = contents.substring(cursor, componentEnd);
            JsonValue purl = requireTopLevelJsonField(
                    component, "purl", "CycloneDX Maven SBOM component");
            if (component.charAt(purl.start()) != '"'
                    || jsonStringEnd(
                                    component,
                                    purl.start(),
                                    "CycloneDX Maven SBOM component purl")
                            != purl.end()) {
                throw new IOException("CycloneDX Maven SBOM component purl is not a string");
            }
            String value = decodeJsonString(
                    component,
                    purl.start(),
                    purl.end(),
                    "CycloneDX Maven SBOM component purl");
            if (!value.startsWith("pkg:maven/") || !purls.add(value)) {
                throw new IOException(
                        "CycloneDX Maven SBOM has an invalid or duplicate component purl "
                                + value);
            }
            cursor = skipJsonWhitespace(contents, componentEnd);
            if (cursor == arrayEnd - 1) {
                break;
            }
            if (cursor >= arrayEnd - 1 || contents.charAt(cursor) != ',') {
                throw new IOException(description + " has malformed components array syntax");
            }
            cursor++;
        }
        return Set.copyOf(purls);
    }

    private static String requireJsonObjectField(
            String object, String field, String description) throws IOException {
        JsonValue value = requireTopLevelJsonField(object, field, description);
        if (object.charAt(value.start()) != '{') {
            throw new IOException(description + " field " + field + " is not an object");
        }
        return object.substring(value.start(), value.end());
    }

    private static void requireJsonStringField(
            String object,
            String field,
            String expected,
            String description)
            throws IOException {
        JsonValue value = requireTopLevelJsonField(object, field, description);
        if (object.charAt(value.start()) != '"'
                || jsonStringEnd(object, value.start(), description) != value.end()) {
            throw new IOException(description + " field " + field + " is not a string");
        }
        String actual = decodeJsonString(
                object, value.start(), value.end(), description + " field " + field);
        if (!actual.equals(expected)) {
            throw new IOException(
                    description + " field " + field + " must equal " + expected);
        }
    }

    private static JsonValue requireTopLevelJsonField(
            String object, String field, String description) throws IOException {
        int objectStart = skipJsonWhitespace(object, 0);
        if (objectStart >= object.length() || object.charAt(objectStart) != '{') {
            throw new IOException(description + " is not a JSON object");
        }
        int objectEnd = jsonCompositeEnd(object, objectStart, '{', '}', description);
        if (skipJsonWhitespace(object, objectEnd) != object.length()) {
            throw new IOException(description + " has trailing JSON content");
        }
        JsonValue match = null;
        int cursor = objectStart + 1;
        while (true) {
            cursor = skipJsonWhitespace(object, cursor);
            if (cursor == objectEnd - 1) {
                break;
            }
            if (cursor >= objectEnd - 1 || object.charAt(cursor) != '"') {
                throw new IOException(description + " has a malformed JSON field");
            }
            int keyEnd = jsonStringEnd(object, cursor, description);
            String key = decodeJsonString(object, cursor, keyEnd, description + " field name");
            cursor = skipJsonWhitespace(object, keyEnd);
            if (cursor >= objectEnd - 1 || object.charAt(cursor) != ':') {
                throw new IOException(description + " has a malformed JSON field separator");
            }
            int valueStart = skipJsonWhitespace(object, cursor + 1);
            int valueEnd = jsonValueEnd(object, valueStart, description);
            if (key.equals(field)) {
                if (match != null) {
                    throw new IOException(description + " repeats JSON field " + field);
                }
                match = new JsonValue(valueStart, valueEnd);
            }
            cursor = skipJsonWhitespace(object, valueEnd);
            if (cursor == objectEnd - 1) {
                break;
            }
            if (cursor >= objectEnd - 1 || object.charAt(cursor) != ',') {
                throw new IOException(description + " has malformed JSON object syntax");
            }
            cursor++;
        }
        if (match == null) {
            throw new IOException(description + " omits JSON field " + field);
        }
        return match;
    }

    private static int jsonValueEnd(String json, int start, String description)
            throws IOException {
        if (start >= json.length()) {
            throw new IOException(description + " has a missing JSON value");
        }
        return switch (json.charAt(start)) {
            case '{' -> jsonCompositeEnd(json, start, '{', '}', description);
            case '[' -> jsonCompositeEnd(json, start, '[', ']', description);
            case '"' -> jsonStringEnd(json, start, description);
            default -> {
                int cursor = start;
                while (cursor < json.length()
                        && json.charAt(cursor) != ','
                        && json.charAt(cursor) != '}'
                        && json.charAt(cursor) != ']') {
                    cursor++;
                }
                if (json.substring(start, cursor).isBlank()) {
                    throw new IOException(description + " has a blank JSON value");
                }
                yield cursor;
            }
        };
    }

    private static int jsonCompositeEnd(
            String json,
            int start,
            char opening,
            char closing,
            String description)
            throws IOException {
        int depth = 0;
        for (int cursor = start; cursor < json.length(); cursor++) {
            char current = json.charAt(cursor);
            if (current == '"') {
                cursor = jsonStringEnd(json, cursor, description) - 1;
            } else if (current == opening) {
                depth++;
            } else if (current == closing && --depth == 0) {
                return cursor + 1;
            }
        }
        throw new IOException(description + " has an unterminated JSON value");
    }

    private static int jsonStringEnd(String json, int start, String description)
            throws IOException {
        boolean escaped = false;
        for (int cursor = start + 1; cursor < json.length(); cursor++) {
            char current = json.charAt(cursor);
            if (escaped) {
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '"') {
                return cursor + 1;
            }
        }
        throw new IOException(description + " has an unterminated JSON string");
    }

    private static String decodeJsonString(
            String json, int start, int end, String description) throws IOException {
        StringBuilder decoded = new StringBuilder(end - start - 2);
        for (int cursor = start + 1; cursor < end - 1; cursor++) {
            char value = json.charAt(cursor);
            if (value != '\\') {
                decoded.append(value);
                continue;
            }
            if (++cursor >= end - 1) {
                throw new IOException(description + " has an unterminated JSON escape");
            }
            char escaped = json.charAt(cursor);
            switch (escaped) {
                case '"', '\\', '/' -> decoded.append(escaped);
                case 'b' -> decoded.append('\b');
                case 'f' -> decoded.append('\f');
                case 'n' -> decoded.append('\n');
                case 'r' -> decoded.append('\r');
                case 't' -> decoded.append('\t');
                case 'u' -> {
                    if (cursor + 4 >= end) {
                        throw new IOException(description + " has an incomplete unicode escape");
                    }
                    int codeUnit = 0;
                    for (int digit = 0; digit < 4; digit++) {
                        codeUnit = codeUnit * 16 + jsonHexValue(json.charAt(++cursor));
                    }
                    decoded.append((char) codeUnit);
                }
                default -> throw new IOException(description + " has an invalid JSON escape");
            }
        }
        return decoded.toString();
    }

    private static int jsonHexValue(char value) throws IOException {
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        if (value >= 'a' && value <= 'f') {
            return value - 'a' + 10;
        }
        if (value >= 'A' && value <= 'F') {
            return value - 'A' + 10;
        }
        throw new IOException("JSON unicode escape contains a non-ASCII hex digit");
    }

    private static int skipJsonWhitespace(String json, int start) {
        int cursor = start;
        while (cursor < json.length() && isJsonWhitespace(json.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static boolean isJsonWhitespace(char value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n';
    }

    private static final class StrictJsonParser {
        private final String json;
        private final String description;
        private int cursor;

        private StrictJsonParser(String json, String description) {
            this.json = json;
            this.description = description;
        }

        private void parse() throws IOException {
            skipWhitespace();
            parseValue(0);
            skipWhitespace();
            if (cursor != json.length()) {
                fail("has trailing content");
            }
        }

        private void parseValue(int depth) throws IOException {
            if (depth > MAX_JSON_DEPTH) {
                fail("exceeds the JSON nesting bound");
            }
            skipWhitespace();
            if (cursor >= json.length()) {
                fail("has a missing value");
            }
            switch (json.charAt(cursor)) {
                case '{' -> parseObject(depth);
                case '[' -> parseArray(depth);
                case '"' -> parseString();
                case 't' -> parseLiteral("true");
                case 'f' -> parseLiteral("false");
                case 'n' -> parseLiteral("null");
                default -> parseNumber();
            }
        }

        private void parseObject(int depth) throws IOException {
            cursor++;
            skipWhitespace();
            if (consume('}')) {
                return;
            }
            while (true) {
                if (cursor >= json.length() || json.charAt(cursor) != '"') {
                    fail("has a malformed object key");
                }
                parseString();
                skipWhitespace();
                require(':');
                parseValue(depth + 1);
                skipWhitespace();
                if (consume('}')) {
                    return;
                }
                require(',');
                skipWhitespace();
                if (cursor < json.length() && json.charAt(cursor) == '}') {
                    fail("contains a trailing object comma");
                }
            }
        }

        private void parseArray(int depth) throws IOException {
            cursor++;
            skipWhitespace();
            if (consume(']')) {
                return;
            }
            while (true) {
                parseValue(depth + 1);
                skipWhitespace();
                if (consume(']')) {
                    return;
                }
                require(',');
                skipWhitespace();
                if (cursor < json.length() && json.charAt(cursor) == ']') {
                    fail("contains a trailing array comma");
                }
            }
        }

        private void parseString() throws IOException {
            require('"');
            while (cursor < json.length()) {
                char value = json.charAt(cursor++);
                if (value == '"') {
                    return;
                }
                if (value < 0x20) {
                    fail("contains an unescaped JSON control character");
                }
                if (value != '\\') {
                    continue;
                }
                if (cursor >= json.length()) {
                    fail("has an unterminated JSON escape");
                }
                char escaped = json.charAt(cursor++);
                if (escaped == 'u') {
                    for (int digit = 0; digit < 4; digit++) {
                        if (cursor >= json.length()
                                || !isJsonHexDigit(json.charAt(cursor++))) {
                            fail("contains an invalid JSON unicode escape");
                        }
                    }
                } else if ("\"\\/bfnrt".indexOf(escaped) < 0) {
                    fail("contains an invalid JSON escape");
                }
            }
            fail("has an unterminated JSON string");
        }

        private void parseLiteral(String literal) throws IOException {
            if (!json.startsWith(literal, cursor)) {
                fail("contains an invalid JSON literal");
            }
            cursor += literal.length();
        }

        private void parseNumber() throws IOException {
            int start = cursor;
            consume('-');
            if (consume('0')) {
                if (cursor < json.length() && isJsonDigit(json.charAt(cursor))) {
                    fail("contains a JSON number with a leading zero");
                }
            } else {
                requireDigitOneToNine();
                while (cursor < json.length() && isJsonDigit(json.charAt(cursor))) {
                    cursor++;
                }
            }
            if (consume('.')) {
                requireDigit();
                while (cursor < json.length() && isJsonDigit(json.charAt(cursor))) {
                    cursor++;
                }
            }
            if (consume('e') || consume('E')) {
                if (!consume('+')) {
                    consume('-');
                }
                requireDigit();
                while (cursor < json.length() && isJsonDigit(json.charAt(cursor))) {
                    cursor++;
                }
            }
            if (cursor == start) {
                fail("contains an invalid JSON value");
            }
        }

        private void requireDigitOneToNine() throws IOException {
            if (cursor >= json.length()
                    || json.charAt(cursor) < '1'
                    || json.charAt(cursor) > '9') {
                fail("contains an invalid JSON number");
            }
            cursor++;
        }

        private void requireDigit() throws IOException {
            if (cursor >= json.length() || !isJsonDigit(json.charAt(cursor))) {
                fail("contains an invalid JSON number");
            }
            cursor++;
        }

        private static boolean isJsonDigit(char value) {
            return value >= '0' && value <= '9';
        }

        private static boolean isJsonHexDigit(char value) {
            return isJsonDigit(value)
                    || value >= 'a' && value <= 'f'
                    || value >= 'A' && value <= 'F';
        }

        private void require(char expected) throws IOException {
            if (!consume(expected)) {
                fail("expected JSON token " + expected);
            }
        }

        private boolean consume(char expected) {
            if (cursor < json.length() && json.charAt(cursor) == expected) {
                cursor++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (cursor < json.length() && isJsonWhitespace(json.charAt(cursor))) {
                cursor++;
            }
        }

        private void fail(String message) throws IOException {
            throw new IOException(description + " " + message + " at byte " + cursor);
        }
    }

    private record JsonValue(int start, int end) {
    }

    static void verifyChecksums(Path root, Path checksumFile) throws IOException {
        List<String> lines = readBoundedMetadataLines(checksumFile, "SHA256SUMS");
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
        List<String> lines = readBoundedMetadataLines(file, "properties file");
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

    static List<String> readBoundedMetadataLines(Path file, String description)
            throws IOException {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    description + " is not a regular non-symlink file: " + file);
        }
        long size = Files.size(file);
        if (size <= 0 || size > MAX_METADATA_BYTES) {
            throw new IOException(description + " has invalid bounded size " + size);
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() > MAX_METADATA_LINES) {
            throw new IOException(
                    description + " exceeds " + MAX_METADATA_LINES + " lines");
        }
        return lines;
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

    static void requireSigningStateForTarget(
            NativeTarget target, String signing) throws IOException {
        boolean linux = target == NativeTarget.LINUX_X86_64;
        if ((signing.equals("verified-detached") && !linux)
                || (signing.equals("verified-embedded") && linux)) {
            throw new IOException(
                    "Signing state " + signing + " is invalid for target " + target.id());
        }
    }

    private static void requireFileName(Path file, String expected, String description)
            throws IOException {
        if (!file.getFileName().toString().equals(expected)) {
            throw new IOException(
                    description + " must be named " + expected + ", found " + file.getFileName());
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

    static RuntimeLayout requireRuntimeLayout(Path runtimeBundle, NativeTarget target)
            throws IOException {
        NativePackageMetadata.HostOs hostOs = NativePackageMetadata.target(target).hostOs();
        Path home = switch (hostOs) {
            case MACOS -> runtimeBundle.resolve("Contents").resolve("Home");
            case LINUX, WINDOWS -> runtimeBundle;
        };
        Path javaExecutable = home.resolve("bin").resolve(
                hostOs == NativePackageMetadata.HostOs.WINDOWS ? "java.exe" : "java");
        requireRegularFile(javaExecutable, "packaged runtime java");
        requireRegularFile(home.resolve("lib").resolve("modules"), "packaged runtime modules");
        return new RuntimeLayout(home, javaExecutable);
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

    record RuntimeLayout(Path home, Path javaExecutable) {
    }

    public record VerificationResult(
            NativeTarget target,
            NativePackageMetadata.PackageType packageType,
            String appVersion,
            Path applicationRoot,
            Path appDirectory,
            Path runtime,
            Path runtimeJava,
            Path launcher,
            Path commandLauncher,
            Path nativeSource) {
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
