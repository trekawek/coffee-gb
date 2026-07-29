package eu.rekawek.coffeegb.swing.packaging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Explicit release-only signing/notarization gate.
 *
 * <p>Ordinary package builds never call this policy and are unsigned. No credential is accepted
 * on a command line or written to staging; platform tools obtain credentials from the host's
 * protected keychain/certificate/GPG store.
 */
public final class ReleaseSigningPolicy {

    private static final Pattern WINDOWS_CERTIFICATE = Pattern.compile("(?i)^[0-9a-f]{40}$");
    private static final Pattern MAC_DEVELOPER_ID_APPLICATION =
            Pattern.compile("^Developer ID Application: .+ \\([A-Z0-9]{10}\\)$");
    private static final int MAX_WINDOWS_SIGNABLE_FILES = 1_024;
    private static final String MAC_LIBRARY_LOADING_REQUIREMENT =
            "=entitlement[\"com.apple.security.cs.disable-library-validation\"] = true";

    private final NativePackageMetadata.Target target;
    private final List<String> jpackageOptions;
    private final Map<String, String> environment;

    private ReleaseSigningPolicy(
            NativePackageMetadata.Target target,
            List<String> jpackageOptions,
            Map<String, String> environment) {
        this.target = target;
        this.jpackageOptions = List.copyOf(jpackageOptions);
        this.environment = Map.copyOf(environment);
    }

    public static ReleaseSigningPolicy require(
            NativePackageMetadata.Target target,
            NativePackageMetadata.PackageType packageType,
            String applicationVersion,
            Map<String, String> environment) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(packageType, "packageType");
        Objects.requireNonNull(applicationVersion, "applicationVersion");
        Objects.requireNonNull(environment, "environment");
        if (packageType == NativePackageMetadata.PackageType.APP_IMAGE) {
            throw new IllegalArgumentException(
                    "Release signing requires an installer artifact, not app-image");
        }
        if (target.hostOs() == NativePackageMetadata.HostOs.WINDOWS
                && packageType != NativePackageMetadata.PackageType.EXE) {
            throw new IllegalArgumentException(
                    "Windows release signing supports EXE only");
        }
        if (target.hostOs() == NativePackageMetadata.HostOs.MACOS
                && packageType != NativePackageMetadata.PackageType.DMG) {
            throw new IllegalArgumentException(
                    "macOS release signing supports DMG only");
        }
        requireValue(environment, "COFFEE_GB_RELEASE_SIGNING", "true");
        String event = environment.getOrDefault("GITHUB_EVENT_NAME", "")
                .toLowerCase(Locale.ROOT);
        if (event.equals("pull_request")
                || event.equals("pull_request_target")
                || event.startsWith("pull_request_")) {
            throw new IllegalArgumentException("Release signing is forbidden for pull requests");
        }
        boolean taggedCi = "tag".equals(environment.get("GITHUB_REF_TYPE"));
        String releaseTag = taggedCi
                ? environment.get("GITHUB_REF_NAME")
                : environment.get("COFFEE_GB_RELEASE_TAG");
        if (!nonBlank(releaseTag)) {
            throw new IllegalArgumentException(
                    "Release signing requires a tag ref or COFFEE_GB_RELEASE_TAG");
        }
        if (applicationVersion.endsWith("-SNAPSHOT")) {
            throw new IllegalArgumentException("Snapshot packages cannot be release-signed");
        }
        requireValue(environment, "COFFEE_GB_RELEASE_VERSION", applicationVersion);
        String expectedTag = "coffee-gb-" + applicationVersion;
        if (!expectedTag.equals(releaseTag)) {
            throw new IllegalArgumentException(
                    "Release signing tag must equal " + expectedTag);
        }

        List<String> jpackageOptions = new ArrayList<>();
        switch (target.hostOs()) {
            case MACOS -> {
                String keyUserName =
                        requireNonBlank(environment, "COFFEE_GB_MAC_SIGNING_KEY_USER_NAME");
                String identity =
                        requireNonBlank(environment, "COFFEE_GB_MAC_SIGNING_IDENTITY");
                if (!MAC_DEVELOPER_ID_APPLICATION.matcher(identity).matches()) {
                    throw new IllegalArgumentException(
                            "COFFEE_GB_MAC_SIGNING_IDENTITY must name a "
                                    + "Developer ID Application identity");
                }
                requireNonBlank(environment, "COFFEE_GB_MAC_NOTARY_KEYCHAIN_PROFILE");
                jpackageOptions.add("--mac-sign");
                jpackageOptions.add("--mac-signing-key-user-name");
                jpackageOptions.add(keyUserName);
                jpackageOptions.add("--mac-package-signing-prefix");
                jpackageOptions.add(NativePackageMetadata.APPLICATION_ID + ".");
                String keychain = environment.get("COFFEE_GB_MAC_SIGNING_KEYCHAIN");
                if (nonBlank(keychain)) {
                    jpackageOptions.add("--mac-signing-keychain");
                    jpackageOptions.add(keychain);
                }
            }
            case WINDOWS -> {
                String certificate =
                        requireNonBlank(environment, "COFFEE_GB_WINDOWS_CERTIFICATE_SHA1");
                if (!WINDOWS_CERTIFICATE.matcher(certificate).matches()) {
                    throw new IllegalArgumentException(
                            "COFFEE_GB_WINDOWS_CERTIFICATE_SHA1 must be a 40-digit SHA-1");
                }
                String timestamp =
                        requireNonBlank(environment, "COFFEE_GB_WINDOWS_TIMESTAMP_URL");
                if (!timestamp.startsWith("https://")) {
                    throw new IllegalArgumentException(
                            "COFFEE_GB_WINDOWS_TIMESTAMP_URL must use HTTPS");
                }
            }
            case LINUX -> requireNonBlank(environment, "COFFEE_GB_LINUX_GPG_KEY_ID");
        }
        return new ReleaseSigningPolicy(target, jpackageOptions, environment);
    }

    public List<String> jpackageOptions() {
        return jpackageOptions;
    }

    /**
     * Signs executable content in the prebuilt application image before jpackage copies it into
     * the installer payload. macOS app images are already signed by jpackage's {@code --mac-sign}
     * mode, and Linux uses a detached signature for the final installer.
     */
    public List<List<String>> appImagePostPackageCommands(Path appImage) throws IOException {
        Objects.requireNonNull(appImage, "appImage");
        if (target.hostOs() != NativePackageMetadata.HostOs.WINDOWS) {
            return List.of();
        }
        List<List<String>> commands = new ArrayList<>();
        for (Path executable : windowsExecutableContent(appImage)) {
            commands.add(windowsSignCommand(executable, true));
        }
        return List.copyOf(commands);
    }

    /** Validates the signed application payload separately from the outer installer signature. */
    public List<List<String>> appImageVerificationCommands(Path appImage) throws IOException {
        Objects.requireNonNull(appImage, "appImage");
        return switch (target.hostOs()) {
            case MACOS -> List.of(
                    List.of(
                            "codesign",
                            "--verify",
                            "--deep",
                            "--strict",
                            "--verbose=2",
                            appImage.toString()),
                    List.of(
                            "codesign",
                            "--verify",
                            "--verbose=2",
                            "-R" + MAC_LIBRARY_LOADING_REQUIREMENT,
                            appImage.toString()));
            case WINDOWS -> windowsExecutableContent(appImage).stream()
                    .map(executable -> List.of(
                            "signtool",
                            "verify",
                            "/pa",
                            "/all",
                            "/tw",
                            "/v",
                            executable.toString()))
                    .toList();
            case LINUX -> List.of();
        };
    }

    public List<List<String>> postPackageCommands(Path artifact) {
        Objects.requireNonNull(artifact, "artifact");
        return switch (target.hostOs()) {
            case MACOS -> List.of(
                    macDiskImageSigningCommand(artifact),
                    macDiskImageVerificationCommand(artifact),
                    List.of("hdiutil", "verify", artifact.toString()),
                    macNotaryCommand(artifact),
                    List.of("xcrun", "stapler", "staple", artifact.toString()));
            case WINDOWS -> List.of(windowsSignCommand(artifact, false));
            case LINUX -> List.of(List.of(
                    "gpg",
                    "--batch",
                    "--yes",
                    "--armor",
                    "--detach-sign",
                    "--local-user",
                    environment.get("COFFEE_GB_LINUX_GPG_KEY_ID"),
                    artifact.toString()));
        };
    }

    private List<String> macNotaryCommand(Path artifact) {
        List<String> command = new ArrayList<>(List.of(
                "xcrun",
                "notarytool",
                "submit",
                artifact.toString(),
                "--keychain-profile",
                environment.get("COFFEE_GB_MAC_NOTARY_KEYCHAIN_PROFILE")));
        String keychain = environment.get("COFFEE_GB_MAC_NOTARY_KEYCHAIN");
        if (nonBlank(keychain)) {
            command.add("--keychain");
            command.add(keychain);
        }
        command.add("--wait");
        return List.copyOf(command);
    }

    private List<String> macDiskImageSigningCommand(Path artifact) {
        List<String> command = new ArrayList<>(List.of(
                "codesign",
                "--force",
                "--timestamp",
                "--sign",
                environment.get("COFFEE_GB_MAC_SIGNING_IDENTITY")));
        String keychain = environment.get("COFFEE_GB_MAC_SIGNING_KEYCHAIN");
        if (nonBlank(keychain)) {
            command.add("--keychain");
            command.add(keychain);
        }
        command.add(artifact.toString());
        return List.copyOf(command);
    }

    private static List<String> macDiskImageVerificationCommand(Path artifact) {
        return List.of(
                "codesign",
                "--verify",
                "--strict",
                "--verbose=2",
                artifact.toString());
    }

    private List<String> windowsSignCommand(Path artifact, boolean append) {
        List<String> command = new ArrayList<>(List.of(
                "signtool",
                "sign",
                "/sha1",
                environment.get("COFFEE_GB_WINDOWS_CERTIFICATE_SHA1"),
                "/fd",
                "SHA256",
                "/tr",
                environment.get("COFFEE_GB_WINDOWS_TIMESTAMP_URL"),
                "/td",
                "SHA256"));
        if (append) {
            // Preserve a runtime vendor's existing primary signature. SignTool makes this the
            // primary signature automatically when a file was previously unsigned.
            command.add("/as");
        }
        command.add(artifact.toString());
        return List.copyOf(command);
    }

    private List<Path> windowsExecutableContent(Path appImage) throws IOException {
        Path root = appImage.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Windows application image is not a regular directory: " + root);
        }
        List<Path> files;
        try (var paths = Files.walk(root)) {
            files = paths.filter(path ->
                            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                                    && !Files.isSymbolicLink(path))
                    .filter(ReleaseSigningPolicy::isWindowsExecutableContent)
                    .sorted(Comparator.comparing(path ->
                            root.relativize(path).toString().replace('\\', '/')))
                    .toList();
        }
        if (files.isEmpty()) {
            throw new IOException(
                    "Windows application image contains no executable content: " + root);
        }
        if (files.size() > MAX_WINDOWS_SIGNABLE_FILES) {
            throw new IOException(
                    "Windows application image contains more than "
                            + MAX_WINDOWS_SIGNABLE_FILES
                            + " executable files");
        }
        for (String launcher : List.of(
                NativePackageMetadata.APPLICATION_NAME + ".exe",
                NativePackageMetadata.WINDOWS_CONSOLE_LAUNCHER_NAME + ".exe")) {
            Path expected = root.resolve(launcher);
            if (!files.contains(expected)) {
                throw new IOException(
                        "Windows application image launcher is missing from signing set: "
                                + expected);
            }
        }
        return files;
    }

    private static boolean isWindowsExecutableContent(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".exe") || fileName.endsWith(".dll");
    }

    /**
     * Commands which must succeed after signing/notarization before a build may report verified
     * signing. Merely accepting a signing command's exit status is not sufficient evidence.
     */
    public List<List<String>> verificationCommands(Path artifact) {
        Objects.requireNonNull(artifact, "artifact");
        return switch (target.hostOs()) {
            case MACOS -> List.of(
                    macDiskImageVerificationCommand(artifact),
                    List.of("hdiutil", "verify", artifact.toString()),
                    List.of(
                            "spctl",
                            "--assess",
                            "--type",
                            "open",
                            "--context",
                            "context:primary-signature",
                            "--verbose=2",
                            artifact.toString()),
                    List.of("xcrun", "stapler", "validate", artifact.toString()));
            case WINDOWS -> List.of(List.of(
                    "signtool",
                    "verify",
                    "/pa",
                    "/all",
                    "/tw",
                    "/v",
                    artifact.toString()));
            case LINUX -> List.of(List.of(
                    "gpg",
                    "--batch",
                    "--verify",
                    signatureArtifact(artifact).orElseThrow().toString(),
                    artifact.toString()));
        };
    }

    public Optional<Path> signatureArtifact(Path artifact) {
        Objects.requireNonNull(artifact, "artifact");
        if (target.hostOs() != NativePackageMetadata.HostOs.LINUX) {
            return Optional.empty();
        }
        return Optional.of(artifact.resolveSibling(artifact.getFileName() + ".asc"));
    }

    public String verifiedSigningState() {
        return target.hostOs() == NativePackageMetadata.HostOs.LINUX
                ? "verified-detached"
                : "verified-embedded";
    }

    private static void requireValue(
            Map<String, String> environment, String key, String expected) {
        String actual = environment.get(key);
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(key + " must equal " + expected);
        }
    }

    private static String requireNonBlank(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (!nonBlank(value)) {
            throw new IllegalArgumentException(key + " is required for release signing");
        }
        return value;
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
