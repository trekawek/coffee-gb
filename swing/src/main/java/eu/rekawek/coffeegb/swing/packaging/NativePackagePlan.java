package eu.rekawek.coffeegb.swing.packaging;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Pure command planning and module verification for host jdeps, jlink, and jpackage. */
public final class NativePackagePlan {

    public List<String> jdepsCommand(Path javaHome, Path appJar) {
        return List.of(
                tool(javaHome, "jdeps").toString(),
                "--multi-release",
                "16",
                "--ignore-missing-deps",
                "--print-module-deps",
                appJar.toString());
    }

    public void verifyJdepsModules(String output) {
        Set<String> modules = parseCommaSeparatedModules(output);
        if (!modules.equals(NativePackageMetadata.JDEPS_MODULES)) {
            Set<String> missing = new LinkedHashSet<>(NativePackageMetadata.JDEPS_MODULES);
            missing.removeAll(modules);
            Set<String> unexpected = new LinkedHashSet<>(modules);
            unexpected.removeAll(NativePackageMetadata.JDEPS_MODULES);
            throw new IllegalArgumentException(
                    "App module dependencies changed; update and re-smoke the locked runtime. "
                            + "Missing=" + missing + ", unexpected=" + unexpected);
        }
    }

    public List<String> jlinkCommand(Path javaHome, Path runtimeOutput) {
        return List.of(
                tool(javaHome, "jlink").toString(),
                "--module-path",
                javaHome.resolve("jmods").toString(),
                "--add-modules",
                String.join(",", NativePackageMetadata.RUNTIME_ROOT_MODULES),
                "--output",
                runtimeOutput.toString(),
                "--strip-debug",
                "--no-header-files",
                "--no-man-pages",
                "--compress=zip-6");
    }

    public void verifyLinkedModules(String output) {
        Set<String> modules = Arrays.stream(output.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .map(line -> {
                    int version = line.indexOf('@');
                    return version >= 0 ? line.substring(0, version) : line;
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!modules.equals(NativePackageMetadata.LINKED_RUNTIME_MODULES)) {
            throw new IllegalArgumentException(
                    "Linked runtime module closure changed: " + modules);
        }
    }

    public List<String> listModulesCommand(Path runtime) {
        return List.of(runtimeTool(runtime, "java").toString(), "--list-modules");
    }

    public List<String> runtimeVersionSmokeCommand(
            Path runtime, NativePackageStager.StageResult stage) {
        return List.of(
                runtimeTool(runtime, "java").toString(),
                "-jar",
                stage.appJar().toString(),
                "--version");
    }

    public List<String> jpackageCommand(
            Path javaHome,
            NativePackageStager.StageResult stage,
            Path runtime,
            Path destination,
            Path temporaryDirectory,
            NativePackageMetadata.PackageType packageType,
            List<String> releaseSigningOptions) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(packageType, "packageType");
        stage.target().requireSupported(packageType);

        List<String> command = new ArrayList<>();
        command.add(tool(javaHome, "jpackage").toString());
        add(command, "--type", packageType.id());
        add(command, "--name", NativePackageMetadata.APPLICATION_NAME);
        add(command, "--input", stage.input().toString());
        add(command, "--main-jar", stage.appJar().getFileName().toString());
        add(command, "--main-class", NativePackageMetadata.MAIN_CLASS);
        add(command, "--runtime-image", runtime.toString());
        add(command, "--dest", destination.toString());
        add(command, "--temp", temporaryDirectory.toString());
        add(command, "--resource-dir", stage.jpackageResources().toString());
        add(
                command,
                "--app-version",
                NativePackageMetadata.installerVersion(stage.appVersion()));
        add(command, "--vendor", NativePackageMetadata.VENDOR);
        add(command, "--description", NativePackageMetadata.DESCRIPTION);
        add(command, "--copyright", NativePackageMetadata.COPYRIGHT);
        add(command, "--icon", stage.icon().toString());
        add(
                command,
                "--java-options",
                "-Dcoffee-gb.native.target=" + stage.target().nativeTarget().id());
        add(
                command,
                "--java-options",
                "-Dcoffee-gb.native.source=$APPDIR/"
                        + stage.nativeSource().getFileName());
        add(command, "--java-options", "-Dfile.encoding=UTF-8");

        if (packageType != NativePackageMetadata.PackageType.APP_IMAGE) {
            add(command, "--about-url", NativePackageMetadata.SOURCE_URL);
            add(
                    command,
                    "--license-file",
                    stage.input().resolve("legal/LICENSE.txt").toString());
        }
        if (packageType != NativePackageMetadata.PackageType.APP_IMAGE
                || stage.target().hostOs() == NativePackageMetadata.HostOs.MACOS) {
            // A predefined macOS app image keeps its existing Info.plist. Embed document
            // associations before signing so the DMG wrapper cannot silently lose them.
            addFileAssociations(command, stage);
        }

        addPlatformOptions(command, stage.target(), packageType);
        if (stage.target().hostOs() == NativePackageMetadata.HostOs.WINDOWS) {
            add(
                    command,
                    "--add-launcher",
                    NativePackageMetadata.WINDOWS_CONSOLE_LAUNCHER_NAME
                            + "="
                            + stage.windowsConsoleLauncher());
        }
        command.addAll(List.copyOf(releaseSigningOptions));
        return List.copyOf(command);
    }

    public List<String> jpackageInstallerFromAppImageCommand(
            Path javaHome,
            NativePackageStager.StageResult stage,
            Path appImage,
            Path destination,
            Path temporaryDirectory,
            NativePackageMetadata.PackageType packageType) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(appImage, "appImage");
        Objects.requireNonNull(packageType, "packageType");
        stage.target().requireSupported(packageType);
        if (packageType == NativePackageMetadata.PackageType.APP_IMAGE) {
            throw new IllegalArgumentException(
                    "An installable package type is required for a prebuilt app image");
        }

        List<String> command = new ArrayList<>();
        command.add(tool(javaHome, "jpackage").toString());
        add(command, "--type", packageType.id());
        add(command, "--name", NativePackageMetadata.APPLICATION_NAME);
        add(command, "--app-image", appImage.toString());
        add(command, "--dest", destination.toString());
        add(command, "--temp", temporaryDirectory.toString());
        add(command, "--resource-dir", stage.jpackageResources().toString());
        add(
                command,
                "--app-version",
                NativePackageMetadata.installerVersion(stage.appVersion()));
        add(command, "--vendor", NativePackageMetadata.VENDOR);
        add(command, "--description", NativePackageMetadata.DESCRIPTION);
        add(command, "--copyright", NativePackageMetadata.COPYRIGHT);
        add(command, "--about-url", NativePackageMetadata.SOURCE_URL);
        add(
                command,
                "--license-file",
                stage.input().resolve("legal/LICENSE.txt").toString());
        addFileAssociations(command, stage);
        addPlatformOptions(command, stage.target(), packageType);
        return List.copyOf(command);
    }

    public Path expectedAppImage(
            Path destination, NativePackageMetadata.Target target) {
        return destination.resolve(target.hostOs() == NativePackageMetadata.HostOs.MACOS
                ? NativePackageMetadata.APPLICATION_NAME + ".app"
                : NativePackageMetadata.APPLICATION_NAME);
    }

    public Path expectedAppImageLauncher(
            Path destination, NativePackageMetadata.Target target) {
        Path appImage = expectedAppImage(destination, target);
        return switch (target.hostOs()) {
            case LINUX -> appImage.resolve("bin")
                    .resolve(NativePackageMetadata.APPLICATION_NAME);
            case WINDOWS -> appImage
                    .resolve(NativePackageMetadata.APPLICATION_NAME + ".exe");
            case MACOS -> appImage
                    .resolve("Contents")
                    .resolve("MacOS")
                    .resolve(NativePackageMetadata.APPLICATION_NAME);
        };
    }

    public Path expectedCommandLauncher(
            Path destination, NativePackageMetadata.Target target) {
        if (target.hostOs() != NativePackageMetadata.HostOs.WINDOWS) {
            return expectedAppImageLauncher(destination, target);
        }
        return expectedAppImage(destination, target)
                .resolve(NativePackageMetadata.WINDOWS_CONSOLE_LAUNCHER_NAME + ".exe");
    }

    private static void addPlatformOptions(
            List<String> command,
            NativePackageMetadata.Target target,
            NativePackageMetadata.PackageType packageType) {
        switch (target.hostOs()) {
            case LINUX -> {
                if (packageType != NativePackageMetadata.PackageType.APP_IMAGE) {
                    add(command, "--linux-package-name", LinuxPackagePolicy.PACKAGE_NAME);
                    add(command, "--linux-app-release", "1");
                    // jpackage uses this value as the DEB Section/RPM Group. The freedesktop
                    // category is independently fixed to Game; in the desktop resource template.
                    add(command, "--linux-app-category", LinuxPackagePolicy.PACKAGE_SECTION);
                    add(command, "--linux-menu-group", "Games");
                    command.add("--linux-shortcut");
                    if (packageType == NativePackageMetadata.PackageType.DEB) {
                        add(command, "--linux-deb-maintainer", "tomek@rekawek.eu");
                    }
                    if (packageType == NativePackageMetadata.PackageType.RPM) {
                        add(command, "--linux-rpm-license-type", "MIT");
                    }
                }
            }
            case WINDOWS -> {
                if (packageType != NativePackageMetadata.PackageType.APP_IMAGE) {
                    command.add("--win-menu");
                    add(command, "--win-menu-group", NativePackageMetadata.APPLICATION_NAME);
                    command.add("--win-shortcut");
                    command.add("--win-dir-chooser");
                    add(
                            command,
                            "--win-upgrade-uuid",
                            NativePackageMetadata.WINDOWS_UPGRADE_UUID);
                    add(command, "--win-help-url", NativePackageMetadata.SOURCE_URL + "/issues");
                    add(command, "--win-update-url", NativePackageMetadata.SOURCE_URL + "/releases");
                }
            }
            case MACOS -> {
                add(
                        command,
                        "--mac-package-identifier",
                        NativePackageMetadata.APPLICATION_ID);
                add(command, "--mac-package-name", NativePackageMetadata.APPLICATION_NAME);
                add(command, "--mac-app-category", "public.app-category.games");
            }
        }
    }

    private static Set<String> parseCommaSeparatedModules(String output) {
        return Arrays.stream(output.strip().split(","))
                .map(String::trim)
                .filter(module -> !module.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Path tool(Path javaHome, String name) {
        String executable = isWindowsHost() ? name + ".exe" : name;
        return javaHome.resolve("bin").resolve(executable);
    }

    private static Path runtimeTool(Path runtime, String name) {
        String executable = isWindowsHost() ? name + ".exe" : name;
        return runtime.resolve("bin").resolve(executable);
    }

    private static boolean isWindowsHost() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .startsWith("windows");
    }

    private static void add(List<String> command, String option, String value) {
        command.add(option);
        command.add(value);
    }

    private static void addFileAssociations(
            List<String> command, NativePackageStager.StageResult stage) {
        for (Path association : stage.associationFiles()) {
            add(command, "--file-associations", association.toString());
        }
    }
}
