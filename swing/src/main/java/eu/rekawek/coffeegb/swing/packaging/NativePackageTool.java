package eu.rekawek.coffeegb.swing.packaging;

import java.io.ByteArrayOutputStream;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Command-line entry point used by the repository's Linux/macOS and Windows wrappers. */
public final class NativePackageTool {

    private static final int MAX_CAPTURED_OUTPUT = 4 * 1024 * 1024;
    private static final long MAX_DEBIAN_METADATA_FILE_BYTES = 64 * 1024;

    private NativePackageTool() {
    }

    public static void main(String[] args) {
        try {
            execute(args, System.getenv(), System.getProperties());
        } catch (IllegalArgumentException | IOException failure) {
            System.err.println("coffee-gb package: " + failure.getMessage());
            System.exit(2);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            System.err.println("coffee-gb package: interrupted");
            System.exit(130);
        }
    }

    static void execute(
            String[] args,
            Map<String, String> environment,
            java.util.Properties systemProperties)
            throws IOException, InterruptedException {
        Arguments parsed = Arguments.parse(args);
        NativeTarget target = NativeTarget.fromId(parsed.required("--target"))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown --target; expected one of " + NativeTarget.supportedIds()));
        Path appJar = parsed.requiredPath("--app-jar");
        Path nativeJar = parsed.requiredPath("--native-source-jar");
        Path sbom = parsed.requiredPath("--sbom");
        Path resources = parsed.requiredPath("--resources");
        Path output = parsed.requiredPath("--output");
        parsed.rejectUnused(Set.of(
                "--target",
                "--app-jar",
                "--native-source-jar",
                "--sbom",
                "--resources",
                "--output",
                "--type",
                "--java-home",
                "--release-sign"));

        if ("stage".equals(parsed.command)) {
            if (parsed.has("--type")
                    || parsed.has("--java-home")
                    || parsed.flag("--release-sign")) {
                throw new IllegalArgumentException(
                        "stage does not accept --type, --java-home, or --release-sign");
            }
            NativePackageStager.StageResult result = new NativePackageStager().stage(
                    new NativePackageStager.StageRequest(
                            target, appJar, nativeJar, sbom, resources, output));
            System.out.println("Staged " + target.id() + " at " + result.root());
            return;
        }
        if (!"build".equals(parsed.command)) {
            throw new IllegalArgumentException("First argument must be 'stage' or 'build'");
        }

        NativePackageMetadata.Target targetMetadata = NativePackageMetadata.target(target);
        NativePackageMetadata.PackageType packageType = parsed.has("--type")
                ? NativePackageMetadata.packageType(parsed.required("--type"))
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unknown --type: " + parsed.required("--type")))
                : targetMetadata.defaultPackageType();
        targetMetadata.requireSupported(packageType);
        NativePackageMetadata.requireMatchingHost(
                targetMetadata,
                systemProperties.getProperty("os.name", ""),
                systemProperties.getProperty("os.arch", ""));

        Path javaHome = parsed.has("--java-home")
                ? parsed.requiredPath("--java-home")
                : Path.of(systemProperties.getProperty("java.home", ""));
        requireJdk21(javaHome);
        Path buildRoot = createFreshDirectory(output);
        NativePackageStager.StageResult stage = new NativePackageStager().stage(
                new NativePackageStager.StageRequest(
                        target,
                        appJar,
                        nativeJar,
                        sbom,
                        resources,
                        buildRoot.resolve("stage")));

        ReleaseSigningPolicy signing = null;
        if (parsed.flag("--release-sign")) {
            signing = ReleaseSigningPolicy.require(
                    targetMetadata, packageType, stage.appVersion(), environment);
        }

        NativePackagePlan plan = new NativePackagePlan();
        String jdepsOutput = runCaptured(plan.jdepsCommand(javaHome, stage.appJar()));
        plan.verifyJdepsModules(lastNonEmptyLine(jdepsOutput));

        Path runtime = buildRoot.resolve("runtime");
        runInherited(plan.jlinkCommand(javaHome, runtime));
        verifyRuntimeLayout(runtime);
        plan.verifyLinkedModules(runCaptured(plan.listModulesCommand(runtime)));
        verifyVersionOutput(
                runCaptured(plan.runtimeVersionSmokeCommand(runtime, stage)), stage.appVersion());

        Path destination = Files.createDirectory(buildRoot.resolve("dist"));
        Path temporary = Files.createDirectory(buildRoot.resolve("jpackage-temp"));
        List<String> signingOptions =
                signing == null ? List.of() : signing.jpackageOptions();
        runInherited(
                plan.jpackageCommand(
                        javaHome,
                        stage,
                        runtime,
                        destination,
                        temporary,
                        packageType,
                        signingOptions),
                stage.root());

        Path primaryArtifact;
        if (packageType == NativePackageMetadata.PackageType.APP_IMAGE) {
            Path launcher = plan.expectedAppImageLauncher(destination, targetMetadata);
            if (!Files.isRegularFile(launcher, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("jpackage app-image launcher is missing: " + launcher);
            }
            ProcessResult launched = runCapturedResult(List.of(launcher.toString(), "--version"));
            if (launched.exitCode != 0) {
                throw new IOException(
                        "Packaged launcher --version failed with exit " + launched.exitCode);
            }
            if (!launched.output.isBlank()) {
                verifyVersionOutput(launched.output, stage.appVersion());
            }
            primaryArtifact = destination.resolve(
                    targetMetadata.hostOs() == NativePackageMetadata.HostOs.MACOS
                            ? NativePackageMetadata.APPLICATION_NAME + ".app"
                            : NativePackageMetadata.APPLICATION_NAME);
        } else {
            primaryArtifact = findInstaller(destination, packageType);
        }

        if (targetMetadata.hostOs() == NativePackageMetadata.HostOs.LINUX
                && packageType == NativePackageMetadata.PackageType.DEB) {
            verifyLinuxDeb(primaryArtifact, buildRoot);
        }

        if (signing != null) {
            for (List<String> command : signing.postPackageCommands(primaryArtifact)) {
                runInherited(command);
            }
        }
        Path packagedPayload = packageType == NativePackageMetadata.PackageType.APP_IMAGE
                ? primaryArtifact
                : temporary.resolve("images");
        NativePackageVerifier.VerificationResult verification =
                NativePackageVerifier.verify(new NativePackageVerifier.VerificationRequest(
                        target,
                        packageType,
                        packagedPayload,
                        stage.appJar(),
                        stage.sbom()));
        NativePackageVerifier.runSmokes(
                verification, buildRoot.resolve("package-smoke-home"));

        Path releaseSbom = destination.resolve(
                NativePackageMetadata.releaseSbomFileName(stage.appVersion()));
        Files.copy(stage.sbom(), releaseSbom, StandardCopyOption.COPY_ATTRIBUTES);
        Path releaseNativeSbom = destination.resolve(
                NativePackageMetadata.releaseNativeSbomFileName(
                        stage.appVersion(), stage.target().nativeTarget()));
        Files.copy(
                stage.nativeSbom(),
                releaseNativeSbom,
                StandardCopyOption.COPY_ATTRIBUTES);
        NativeComponentInventory.verifyNativeSbom(
                releaseNativeSbom,
                stage.target().nativeTarget(),
                stage.appVersion());
        NativePackageVerifier.writeBuildResult(
                destination,
                target,
                packageType,
                stage.appVersion(),
                primaryArtifact,
                releaseSbom,
                signing != null);
        Path checksumFile = destination.resolve("SHA256SUMS");
        writeChecksums(destination, checksumFile);
        NativePackageVerifier.verifyDistribution(
                destination, target, packageType, stage.appVersion());
        System.out.println(
                "Built " + target.id() + " " + packageType.id() + " at " + primaryArtifact);
        System.out.println("Checksums: " + checksumFile);
    }

    /**
     * Copies both Maven and target-native SBOMs next to the release artifact and then covers the
     * complete release directory with sorted SHA-256 checksums.
     *
     * <p>Public for the packaging integration test; normal callers use the {@code build} command.
     */
    public static ReleaseMetadata finalizeReleaseMetadata(
            NativePackageStager.StageResult stage, Path destination) throws IOException {
        if (Files.isSymbolicLink(destination)
                || !Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Release destination is not a non-symlink directory: " + destination);
        }
        Path releaseSbom = destination.resolve(
                NativePackageMetadata.releaseSbomFileName(stage.appVersion()));
        Files.copy(stage.sbom(), releaseSbom, StandardCopyOption.COPY_ATTRIBUTES);
        Path releaseNativeSbom = destination.resolve(
                NativePackageMetadata.releaseNativeSbomFileName(
                        stage.appVersion(), stage.target().nativeTarget()));
        Files.copy(
                stage.nativeSbom(),
                releaseNativeSbom,
                StandardCopyOption.COPY_ATTRIBUTES);
        NativeComponentInventory.verifyNativeSbom(
                releaseNativeSbom,
                stage.target().nativeTarget(),
                stage.appVersion());
        Path checksumFile = destination.resolve("SHA256SUMS");
        writeChecksums(destination, checksumFile);
        return new ReleaseMetadata(releaseSbom, releaseNativeSbom, checksumFile);
    }

    private static void requireJdk21(Path javaHome) throws IOException {
        Path release = javaHome.resolve("release");
        Path jmods = javaHome.resolve("jmods");
        if (!Files.isRegularFile(release, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(jmods, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("--java-home must name a complete JDK: " + javaHome);
        }
        String contents = Files.readString(release, StandardCharsets.UTF_8);
        int marker = contents.indexOf("JAVA_VERSION=\"");
        if (marker < 0) {
            throw new IOException("JDK release file has no JAVA_VERSION");
        }
        int start = marker + "JAVA_VERSION=\"".length();
        int end = contents.indexOf('"', start);
        String version = end < 0 ? "" : contents.substring(start, end);
        String majorText = version.contains(".")
                ? version.substring(0, version.indexOf('.'))
                : version;
        int major;
        try {
            major = Integer.parseInt(majorText);
        } catch (NumberFormatException invalid) {
            throw new IOException("Cannot parse JDK version: " + version, invalid);
        }
        if (major < 21) {
            throw new IOException("Native packaging requires JDK 21 or later, found " + version);
        }
        for (String tool : List.of("jdeps", "jlink", "jpackage")) {
            String executable = isWindows() ? tool + ".exe" : tool;
            if (!Files.isRegularFile(
                    javaHome.resolve("bin").resolve(executable), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("JDK is missing " + executable);
            }
        }
    }

    private static void verifyRuntimeLayout(Path runtime) throws IOException {
        if (!Files.isRegularFile(
                        runtime.resolve("bin").resolve(isWindows() ? "java.exe" : "java"),
                        LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(
                        runtime.resolve("lib").resolve("modules"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("jlink runtime is incomplete: " + runtime);
        }
        if (Files.exists(runtime.resolve("include")) || Files.exists(runtime.resolve("man"))) {
            throw new IOException("jlink runtime unexpectedly contains headers or man pages");
        }
    }

    private static Path findInstaller(
            Path destination, NativePackageMetadata.PackageType packageType) throws IOException {
        String suffix = "." + packageType.id().toLowerCase(Locale.ROOT);
        List<Path> candidates;
        try (Stream<Path> files = Files.list(destination)) {
            candidates = files.filter(path ->
                            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                                    && path.getFileName()
                                            .toString()
                                            .toLowerCase(Locale.ROOT)
                                            .endsWith(suffix))
                    .toList();
        }
        if (candidates.size() != 1) {
            throw new IOException(
                    "Expected exactly one " + suffix + " installer, found " + candidates);
        }
        return candidates.get(0);
    }

    private static void verifyLinuxDeb(Path installer, Path buildRoot)
            throws IOException, InterruptedException {
        String metadata = runCaptured(List.of(
                "dpkg-deb",
                "--field",
                installer.toString(),
                "Section",
                "Depends"));
        LinuxPackagePolicy.verifyDebianMetadata(metadata);

        Path extracted = Files.createDirectory(buildRoot.resolve("deb-validation"));
        runInherited(List.of(
                "dpkg-deb", "--extract", installer.toString(), extracted.toString()));
        Path desktopEntry =
                requireBoundedRegularFile(
                        extracted.resolve(LinuxPackagePolicy.DESKTOP_FILE_IN_PACKAGE),
                        MAX_DEBIAN_METADATA_FILE_BYTES,
                        "generated Linux desktop entry");
        LinuxPackagePolicy.verifyDesktopEntry(
                Files.readString(desktopEntry, StandardCharsets.UTF_8));
        runInherited(List.of("desktop-file-validate", desktopEntry.toString()));

        Path control = Files.createDirectory(buildRoot.resolve("deb-control"));
        runInherited(List.of(
                "dpkg-deb", "--control", installer.toString(), control.toString()));
        Path postInstall =
                requireBoundedRegularFile(
                        control.resolve("postinst"),
                        MAX_DEBIAN_METADATA_FILE_BYTES,
                        "DEB post-install script");
        LinuxPackagePolicy.verifyDesktopRegistration(
                Files.readString(postInstall, StandardCharsets.UTF_8));
    }

    private static Path requireBoundedRegularFile(
            Path file, long maximumBytes, String description) throws IOException {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " is missing or not a regular file: " + file);
        }
        long size = Files.size(file);
        if (size > maximumBytes) {
            throw new IOException(
                    description + " exceeds " + maximumBytes + " bytes: " + file);
        }
        return file;
    }

    private static void verifyVersionOutput(String output, String appVersion) throws IOException {
        String expected = "Coffee GB " + appVersion;
        if (!output.lines().map(String::trim).anyMatch(expected::equals)) {
            throw new IOException(
                    "Version smoke did not report '" + expected + "': " + output.strip());
        }
    }

    private static Path createFreshDirectory(Path output) throws IOException {
        Path absolute = output.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("Build output has no parent: " + output);
        }
        Files.createDirectories(parent);
        return Files.createDirectory(absolute);
    }

    private static String lastNonEmptyLine(String output) {
        return output.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .reduce((ignored, line) -> line)
                .orElse("");
    }

    private static String runCaptured(List<String> command)
            throws IOException, InterruptedException {
        ProcessResult result = runCapturedResult(command);
        if (result.exitCode != 0) {
            throw new IOException(
                    command.get(0) + " exited " + result.exitCode + ": " + result.output.strip());
        }
        return result.output;
    }

    private static ProcessResult runCapturedResult(List<String> command)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (InputStream output = process.getInputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = output.read(buffer)) >= 0) {
                if (captured.size() + read > MAX_CAPTURED_OUTPUT) {
                    process.destroyForcibly();
                    throw new IOException(command.get(0) + " produced excessive output");
                }
                captured.write(buffer, 0, read);
            }
        }
        int exitCode = process.waitFor();
        return new ProcessResult(
                exitCode, captured.toString(StandardCharsets.UTF_8));
    }

    private static void runInherited(List<String> command)
            throws IOException, InterruptedException {
        runInherited(command, null);
    }

    private static void runInherited(List<String> command, Path workingDirectory)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        Process process = builder.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException(command.get(0) + " exited " + exitCode);
        }
    }

    private static void writeChecksums(Path root, Path output) throws IOException {
        List<Path> files;
        try (Stream<Path> paths = Files.walk(root)) {
            files = paths.filter(path ->
                            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                                    && !path.equals(output))
                    .sorted(Comparator.comparing(path ->
                            root.relativize(path).toString().replace('\\', '/')))
                    .toList();
        }
        StringBuilder contents = new StringBuilder();
        for (Path file : files) {
            contents.append(NativePackageStager.sha256(file))
                    .append("  ")
                    .append(root.relativize(file).toString().replace('\\', '/'))
                    .append('\n');
        }
        Files.writeString(
                output,
                contents.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .startsWith("windows");
    }

    public record ReleaseMetadata(Path mavenSbom, Path nativeSbom, Path checksums) {
    }

    private record ProcessResult(int exitCode, String output) {
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
                        "Usage: NativePackageTool (stage|build) --target ID --app-jar PATH "
                                + "--native-source-jar PATH --sbom PATH --resources PATH "
                                + "--output PATH [--type TYPE] [--java-home PATH] "
                                + "[--release-sign]");
            }
            String command = args[0];
            Map<String, String> values = new LinkedHashMap<>();
            java.util.LinkedHashSet<String> flags = new java.util.LinkedHashSet<>();
            for (int i = 1; i < args.length; i++) {
                String option = args[i];
                if ("--release-sign".equals(option)) {
                    if (!flags.add(option)) {
                        throw new IllegalArgumentException("Duplicate option " + option);
                    }
                    continue;
                }
                if (!option.startsWith("--") || i + 1 >= args.length) {
                    throw new IllegalArgumentException("Expected --option value, found " + option);
                }
                if (values.putIfAbsent(option, args[++i]) != null) {
                    throw new IllegalArgumentException("Duplicate option " + option);
                }
            }
            return new Arguments(command, values, flags);
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
            List<String> unknown = new ArrayList<>();
            values.keySet().stream().filter(option -> !supported.contains(option)).forEach(unknown::add);
            flags.stream().filter(option -> !supported.contains(option)).forEach(unknown::add);
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException("Unknown options: " + unknown);
            }
        }
    }
}
