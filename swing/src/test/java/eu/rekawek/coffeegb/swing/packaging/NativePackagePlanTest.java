package eu.rekawek.coffeegb.swing.packaging;

import org.junit.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class NativePackagePlanTest {

    private final NativePackagePlan plan = new NativePackagePlan();

    @Test
    public void jdepsAndJlinkPlansLockTheRuntimeClosure() {
        Path javaHome = Path.of("/jdk");
        Path app = Path.of("/stage/input/coffee-gb.jar");
        List<String> jdeps = plan.jdepsCommand(javaHome, app);
        String jdepsExecutable = System.getProperty("os.name", "").startsWith("Windows")
                ? "jdeps.exe"
                : "jdeps";
        assertEquals(
                javaHome.resolve("bin").resolve(jdepsExecutable).toString(),
                jdeps.get(0));
        assertTrue(jdeps.contains("--print-module-deps"));
        assertEquals(app.toString(), jdeps.get(jdeps.size() - 1));

        plan.verifyJdepsModules(
                "java.base,java.compiler,java.desktop,java.logging,java.management,java.prefs,"
                        + "jdk.unsupported");
        assertThrows(
                IllegalArgumentException.class,
                () -> plan.verifyJdepsModules("java.base,java.desktop"));
        assertThrows(
                IllegalArgumentException.class,
                () -> plan.verifyJdepsModules(
                        "java.base,java.compiler,java.desktop,java.logging,"
                                + "java.management,java.sql,jdk.unsupported"));

        List<String> jlink = plan.jlinkCommand(javaHome, Path.of("/runtime"));
        assertOption(
                jlink,
                "--add-modules",
                String.join(",", NativePackageMetadata.RUNTIME_ROOT_MODULES));
        assertTrue(jlink.contains("--strip-debug"));
        assertTrue(jlink.contains("--no-header-files"));
        assertTrue(jlink.contains("--no-man-pages"));
        assertTrue(jlink.contains("--compress=zip-6"));
        assertFalse(jlink.contains("--bind-services"));

        plan.verifyLinkedModules(
                "java.base@21\n"
                        + "java.compiler@21\n"
                        + "java.datatransfer@21\n"
                        + "java.desktop@21\n"
                        + "java.logging@21\n"
                        + "java.management@21\n"
                        + "java.prefs@21\n"
                        + "java.xml@21\n"
                        + "jdk.crypto.ec@21\n"
                        + "jdk.unsupported@21\n");
    }

    @Test
    public void everyInstallerPlanIncludesMetadataWithoutRomAssociationsOrSigning() {
        for (NativeTarget nativeTarget : NativeTarget.values()) {
            NativePackageMetadata.Target target = NativePackageMetadata.target(nativeTarget);
            for (NativePackageMetadata.PackageType packageType : target.packageTypes()) {
                NativePackageStager.StageResult staged = stage(nativeTarget);
                List<String> command = plan.jpackageCommand(
                        Path.of("/jdk"),
                        staged,
                        Path.of("/runtime"),
                        Path.of("/dist"),
                        Path.of("/temp"),
                        packageType,
                        List.of());
                assertOption(command, "--name", NativePackageMetadata.APPLICATION_NAME);
                assertOption(command, "--vendor", NativePackageMetadata.VENDOR);
                assertOption(command, "--description", NativePackageMetadata.DESCRIPTION);
                assertOption(command, "--app-version", "1.7.15");
                assertOption(command, "--main-class", NativePackageMetadata.MAIN_CLASS);
                assertOption(command, "--resource-dir", staged.jpackageResources().toString());
                assertOption(
                        command,
                        "--java-options",
                        "-Dcoffee-gb.native.target=" + nativeTarget.id());
                assertTrue(command.contains(
                        "-Dcoffee-gb.native.source=$APPDIR/native-source.zip"));
                assertFalse(command.contains("--mac-sign"));
                assertFalse(command.stream().anyMatch(value ->
                        value.contains("TOKEN")
                                || value.contains("PASSWORD")
                                || value.contains("SECRET")));
                assertFalse(command.contains("--file-associations"));
                if (nativeTarget == NativeTarget.WINDOWS_X86_64) {
                    assertOption(
                            command,
                            "--add-launcher",
                            NativePackageMetadata.WINDOWS_CONSOLE_LAUNCHER_NAME
                                    + "="
                                    + staged.windowsConsoleLauncher());
                } else {
                    assertFalse(command.contains("--add-launcher"));
                }

                if (packageType == NativePackageMetadata.PackageType.APP_IMAGE) {
                    assertFalse(command.contains("--license-file"));
                } else {
                    assertOption(command, "--about-url", NativePackageMetadata.SOURCE_URL);
                    assertOption(
                            command,
                            "--license-file",
                            staged.installerLicense().toString());
                }
            }
        }
    }

    @Test
    public void platformPlansCarryMenuAndUninstallMetadata() {
        List<String> linux = installer(NativeTarget.LINUX_X86_64, "deb");
        assertTrue(linux.contains("--linux-shortcut"));
        assertOption(linux, "--linux-menu-group", "Games");
        assertOption(linux, "--linux-package-name", "coffee-gb");
        assertOption(linux, "--linux-app-category", LinuxPackagePolicy.PACKAGE_SECTION);
        assertOption(linux, "--linux-deb-maintainer", "tomek@rekawek.eu");

        List<String> windows = installer(NativeTarget.WINDOWS_X86_64, "exe");
        assertTrue(windows.contains("--win-menu"));
        assertTrue(windows.contains("--win-shortcut"));
        assertTrue(windows.contains("--win-dir-chooser"));
        assertOption(
                windows,
                "--win-upgrade-uuid",
                NativePackageMetadata.WINDOWS_UPGRADE_UUID);

        List<String> mac = installer(NativeTarget.MACOS_AARCH64, "dmg");
        assertOption(
                mac,
                "--mac-package-identifier",
                NativePackageMetadata.APPLICATION_ID);
        assertOption(mac, "--mac-app-category", "public.app-category.games");
    }

    @Test
    public void signedInstallerPlanWrapsOnlyThePrebuiltApplicationImage() {
        for (NativeTarget nativeTarget : NativeTarget.values()) {
            NativePackageMetadata.Target target = NativePackageMetadata.target(nativeTarget);
            NativePackageMetadata.PackageType packageType = target.defaultPackageType();
            NativePackageStager.StageResult staged = stage(nativeTarget);
            Path appImage = Path.of("/signed-app-image");
            List<String> command = plan.jpackageInstallerFromAppImageCommand(
                    Path.of("/jdk"),
                    staged,
                    appImage,
                    Path.of("/dist"),
                    Path.of("/temp"),
                    packageType);

            assertOption(command, "--app-image", appImage.toString());
            assertOption(command, "--type", packageType.id());
            assertOption(command, "--app-version", "1.7.15");
            assertFalse(command.contains("--file-associations"));
            assertOption(
                    command,
                    "--license-file",
                    staged.installerLicense().toString());
            assertFalse(command.contains("--input"));
            assertFalse(command.contains("--runtime-image"));
            assertFalse(command.contains("--main-jar"));
            assertFalse(command.contains("--main-class"));
            assertFalse(command.contains("--add-launcher"));
            assertFalse(command.contains("--mac-sign"));
        }
    }

    @Test
    public void rejectsForeignPackageType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> plan.jpackageCommand(
                        Path.of("/jdk"),
                        stage(NativeTarget.LINUX_X86_64),
                        Path.of("/runtime"),
                        Path.of("/dist"),
                        Path.of("/temp"),
                        NativePackageMetadata.PackageType.MSI,
                        List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> plan.jpackageInstallerFromAppImageCommand(
                        Path.of("/jdk"),
                        stage(NativeTarget.LINUX_X86_64),
                        Path.of("/app-image"),
                        Path.of("/dist"),
                        Path.of("/temp"),
                        NativePackageMetadata.PackageType.APP_IMAGE));
    }

    private List<String> installer(NativeTarget target, String type) {
        return plan.jpackageCommand(
                Path.of("/jdk"),
                stage(target),
                Path.of("/runtime"),
                Path.of("/dist"),
                Path.of("/temp"),
                NativePackageMetadata.packageType(type).orElseThrow(),
                List.of());
    }

    private static NativePackageStager.StageResult stage(NativeTarget nativeTarget) {
        NativePackageMetadata.Target target = NativePackageMetadata.target(nativeTarget);
        Path root = Path.of("/stage");
        return new NativePackageStager.StageResult(
                root,
                root.resolve("input"),
                root.resolve("jpackage-resources"),
                root.resolve("input/coffee-gb.jar"),
                root.resolve("input/coffee-gb-sbom.cdx.json"),
                root.resolve("input/coffee-gb-native-sbom.cdx.json"),
                root.resolve("input/coffee-gb." + target.iconSuffix()),
                root.resolve("input/native-source.zip"),
                root.resolve("launchers/windows-console.properties"),
                target.hostOs() == NativePackageMetadata.HostOs.LINUX
                        ? root.resolve("input/legal/LICENSE.txt")
                        : root.resolve("installer-license/coffee-gb-license.rtf"),
                root.resolve("input/package-manifest.properties"),
                root.resolve("STAGE-SHA256SUMS"),
                "1.7.15-SNAPSHOT",
                target,
                NativeBundleManifest.locked(nativeTarget));
    }

    private static void assertOption(List<String> command, String option, String expected) {
        int position = command.indexOf(option);
        assertTrue("Missing option " + option + " in " + command, position >= 0);
        assertTrue("Missing value for " + option, position + 1 < command.size());
        assertEquals(expected, command.get(position + 1));
    }

    private static void assertOptions(
            List<String> command, String option, List<String> expected) {
        List<String> actual = new ArrayList<>();
        for (int position = 0; position < command.size(); position++) {
            if (option.equals(command.get(position))) {
                assertTrue("Missing value for " + option, position + 1 < command.size());
                actual.add(command.get(position + 1));
            }
        }
        assertEquals(expected, actual);
    }
}
