package eu.rekawek.coffeegb.swing.packaging;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stable native-package metadata shared by staging, jlink, jpackage, tests, and release CI.
 *
 * <p>A target is always selected explicitly. Host properties are used only to reject an
 * impossible cross-build after selection; jpackage cannot generate a foreign platform image.
 */
public final class NativePackageMetadata {

    public static final String APPLICATION_NAME = "Coffee GB";
    public static final String WINDOWS_CONSOLE_LAUNCHER_NAME = "Coffee GB Console";
    public static final String APPLICATION_ID = "eu.rekawek.coffeegb";
    public static final String MAIN_CLASS = "eu.rekawek.coffeegb.swing.MainKt";
    public static final String VENDOR = "Coffee GB contributors";
    public static final String DESCRIPTION =
            "Game Boy, Game Boy Color, and Super Game Boy emulator";
    public static final String SOURCE_URL = "https://github.com/trekawek/coffee-gb";
    public static final String COPYRIGHT =
            "Copyright (c) 2017-2026 Tomasz Rękawek and contributors";
    public static final String WINDOWS_UPGRADE_UUID =
            "a3d9752e-bbb8-4c51-9b4e-60cff9f55ec8";
    static final String GAME_BOY_COLOR_ROM_EXTENSION = "gb" + "c";
    static final String SUPER_GAME_BOY_ROM_EXTENSION = "s" + "gb";
    public static final String GAME_BOY_ROM_MIME_TYPE = "application/x-gameboy-rom";
    public static final String GAME_BOY_COLOR_ROM_MIME_TYPE =
            "application/x-gameboy-color-rom";
    public static final List<String> ROM_EXTENSIONS =
            List.of("gb", GAME_BOY_COLOR_ROM_EXTENSION, "rom");

    /**
     * The roots are the direct static jdeps result plus the EC security provider used by
     * encrypted transports. Transitive modules are locked separately and verified after jlink.
     */
    public static final List<String> RUNTIME_ROOT_MODULES = List.of(
            "java.base",
            "java.compiler",
            "java.desktop",
            "java.logging",
            "java.management",
            "jdk.crypto.ec",
            "jdk.unsupported");

    public static final Set<String> JDEPS_MODULES = Set.of(
            "java.base",
            "java.compiler",
            "java.desktop",
            "java.logging",
            "java.management",
            "jdk.unsupported");

    public static final Set<String> LINKED_RUNTIME_MODULES = Set.of(
            "java.base",
            "java.compiler",
            "java.datatransfer",
            "java.desktop",
            "java.logging",
            "java.management",
            "java.prefs",
            "java.xml",
            "jdk.crypto.ec",
            "jdk.unsupported");

    private static final Pattern MAVEN_VERSION =
            Pattern.compile("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:[-.].*)?$");
    private static final Pattern SAFE_ARTIFACT_VERSION =
            Pattern.compile("^[0-9A-Za-z][0-9A-Za-z._-]*$");
    private static final Map<NativeTarget, Target> TARGETS = targets();

    private NativePackageMetadata() {
    }

    public static Target target(NativeTarget target) {
        return TARGETS.get(Objects.requireNonNull(target, "target"));
    }

    public static Optional<PackageType> packageType(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(PackageType.values())
                .filter(type -> type.id.equals(value))
                .findFirst();
    }

    /**
     * Maps a Maven semantic version to jpackage's portable numeric package-version subset.
     *
     * <p>The complete Maven version remains in the application manifest and package inventory.
     * Snapshot and prerelease suffixes are omitted only from OS installer metadata.
     */
    public static String installerVersion(String mavenVersion) {
        Matcher matcher = MAVEN_VERSION.matcher(Objects.requireNonNull(mavenVersion, "mavenVersion"));
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Maven version must start with three numeric semantic-version components: "
                            + mavenVersion);
        }
        return matcher.group(1) + "." + matcher.group(2) + "." + matcher.group(3);
    }

    public static String releaseSbomFileName(String applicationVersion) {
        requireSafeArtifactVersion(applicationVersion);
        return "coffee-gb-" + applicationVersion + "-sbom.cdx.json";
    }

    public static String releaseNativeSbomFileName(
            String applicationVersion, NativeTarget target) {
        requireSafeArtifactVersion(applicationVersion);
        return "coffee-gb-"
                + applicationVersion
                + "-"
                + Objects.requireNonNull(target, "target").id()
                + "-native-sbom.cdx.json";
    }

    private static void requireSafeArtifactVersion(String applicationVersion) {
        installerVersion(applicationVersion);
        if (!SAFE_ARTIFACT_VERSION.matcher(applicationVersion).matches()) {
            throw new IllegalArgumentException(
                    "Application version is unsafe in a release filename: " + applicationVersion);
        }
    }

    public static void requireMatchingHost(
            Target target, String osName, String osArch) {
        Objects.requireNonNull(target, "target");
        String normalizedOs = Objects.requireNonNull(osName, "osName")
                .toLowerCase(Locale.ROOT);
        String normalizedArch = Objects.requireNonNull(osArch, "osArch")
                .toLowerCase(Locale.ROOT);
        HostOs actualOs;
        if (normalizedOs.startsWith("linux")) {
            actualOs = HostOs.LINUX;
        } else if (normalizedOs.startsWith("windows")) {
            actualOs = HostOs.WINDOWS;
        } else if (normalizedOs.startsWith("mac") || normalizedOs.startsWith("darwin")) {
            actualOs = HostOs.MACOS;
        } else {
            throw new IllegalArgumentException("Unsupported jpackage host OS: " + osName);
        }

        Architecture actualArch = switch (normalizedArch) {
            case "amd64", "x86_64", "x64" -> Architecture.X86_64;
            case "aarch64", "arm64" -> Architecture.AARCH64;
            default -> throw new IllegalArgumentException(
                    "Unsupported jpackage host architecture: " + osArch);
        };
        if (target.hostOs != actualOs || target.architecture != actualArch) {
            throw new IllegalArgumentException(
                    "Target " + target.nativeTarget.id() + " requires "
                            + target.hostOs.id + "/" + target.architecture.id
                            + "; jpackage cannot cross-build from "
                            + actualOs.id + "/" + actualArch.id);
        }
    }

    public enum HostOs {
        LINUX("linux"),
        WINDOWS("windows"),
        MACOS("macos");

        private final String id;

        HostOs(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public enum Architecture {
        X86_64("x86-64"),
        AARCH64("aarch64");

        private final String id;

        Architecture(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public enum PackageType {
        APP_IMAGE("app-image"),
        DEB("deb"),
        RPM("rpm"),
        MSI("msi"),
        EXE("exe"),
        DMG("dmg"),
        PKG("pkg");

        private final String id;

        PackageType(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    public record Target(
            NativeTarget nativeTarget,
            HostOs hostOs,
            Architecture architecture,
            String iconSuffix,
            Set<PackageType> packageTypes,
            PackageType defaultPackageType) {

        public Target {
            Objects.requireNonNull(nativeTarget, "nativeTarget");
            Objects.requireNonNull(hostOs, "hostOs");
            Objects.requireNonNull(architecture, "architecture");
            Objects.requireNonNull(iconSuffix, "iconSuffix");
            packageTypes = Set.copyOf(packageTypes);
            Objects.requireNonNull(defaultPackageType, "defaultPackageType");
            if (!packageTypes.contains(PackageType.APP_IMAGE)
                    || !packageTypes.contains(defaultPackageType)) {
                throw new IllegalArgumentException("Target package-type set is incomplete");
            }
        }

        public void requireSupported(PackageType packageType) {
            if (!packageTypes.contains(packageType)) {
                throw new IllegalArgumentException(
                        packageType.id + " is not supported for " + nativeTarget.id());
            }
        }
    }

    private static Map<NativeTarget, Target> targets() {
        EnumMap<NativeTarget, Target> targets = new EnumMap<>(NativeTarget.class);
        targets.put(
                NativeTarget.LINUX_X86_64,
                new Target(
                        NativeTarget.LINUX_X86_64,
                        HostOs.LINUX,
                        Architecture.X86_64,
                        "png",
                        Set.of(PackageType.APP_IMAGE, PackageType.DEB, PackageType.RPM),
                        PackageType.DEB));
        targets.put(
                NativeTarget.WINDOWS_X86_64,
                new Target(
                        NativeTarget.WINDOWS_X86_64,
                        HostOs.WINDOWS,
                        Architecture.X86_64,
                        "ico",
                        Set.of(PackageType.APP_IMAGE, PackageType.MSI, PackageType.EXE),
                        PackageType.MSI));
        targets.put(
                NativeTarget.MACOS_X86_64,
                new Target(
                        NativeTarget.MACOS_X86_64,
                        HostOs.MACOS,
                        Architecture.X86_64,
                        "icns",
                        Set.of(PackageType.APP_IMAGE, PackageType.DMG, PackageType.PKG),
                        PackageType.DMG));
        targets.put(
                NativeTarget.MACOS_AARCH64,
                new Target(
                        NativeTarget.MACOS_AARCH64,
                        HostOs.MACOS,
                        Architecture.AARCH64,
                        "icns",
                        Set.of(PackageType.APP_IMAGE, PackageType.DMG, PackageType.PKG),
                        PackageType.DMG));
        return Map.copyOf(targets);
    }
}
