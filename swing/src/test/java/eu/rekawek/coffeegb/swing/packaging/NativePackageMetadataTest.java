package eu.rekawek.coffeegb.swing.packaging;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class NativePackageMetadataTest {

    @Test
    public void declaresFourExplicitTargetsAndHostPackageTypes() {
        assertEquals("Tomasz Rękawek", NativePackageMetadata.AUTHOR_NAME);
        assertTrue(NativePackageMetadata.COPYRIGHT.contains(NativePackageMetadata.AUTHOR_NAME));
        assertEquals(
                Set.of(
                        "linux-x86-64",
                        "windows-x86-64",
                        "macos-x86-64",
                        "macos-aarch64"),
                Set.copyOf(NativeTarget.supportedIds()));

        NativePackageMetadata.Target linux =
                NativePackageMetadata.target(NativeTarget.LINUX_X86_64);
        assertEquals(NativePackageMetadata.HostOs.LINUX, linux.hostOs());
        assertEquals(NativePackageMetadata.Architecture.X86_64, linux.architecture());
        assertEquals("png", linux.iconSuffix());
        assertEquals(NativePackageMetadata.PackageType.DEB, linux.defaultPackageType());
        assertEquals(
                Set.of(
                        NativePackageMetadata.PackageType.APP_IMAGE,
                        NativePackageMetadata.PackageType.DEB,
                        NativePackageMetadata.PackageType.RPM),
                linux.packageTypes());

        NativePackageMetadata.Target windows =
                NativePackageMetadata.target(NativeTarget.WINDOWS_X86_64);
        assertEquals("ico", windows.iconSuffix());
        assertEquals(NativePackageMetadata.PackageType.EXE, windows.defaultPackageType());

        NativePackageMetadata.Target macArm =
                NativePackageMetadata.target(NativeTarget.MACOS_AARCH64);
        assertEquals(NativePackageMetadata.Architecture.AARCH64, macArm.architecture());
        assertEquals("icns", macArm.iconSuffix());
        assertEquals(NativePackageMetadata.PackageType.DMG, macArm.defaultPackageType());
    }

    @Test
    public void installerVersionKeepsNumericSemanticCore() {
        assertEquals("1.7.15", NativePackageMetadata.installerVersion("1.7.15"));
        assertEquals(
                "1.7.15", NativePackageMetadata.installerVersion("1.7.15-SNAPSHOT"));
        assertEquals("12.0.3", NativePackageMetadata.installerVersion("12.0.3-rc.2"));
        assertThrows(
                IllegalArgumentException.class,
                () -> NativePackageMetadata.installerVersion("1.7"));
        assertThrows(
                IllegalArgumentException.class,
                () -> NativePackageMetadata.installerVersion("v1.7.15"));
        assertThrows(
                IllegalArgumentException.class,
                () -> NativePackageMetadata.installerVersion("01.7.15"));
        assertEquals(
                "coffee-gb-1.7.15-SNAPSHOT-sbom.cdx.json",
                NativePackageMetadata.releaseSbomFileName("1.7.15-SNAPSHOT"));
        assertEquals(
                "coffee-gb-1.7.15-SNAPSHOT-linux-x86-64-native-sbom.cdx.json",
                NativePackageMetadata.releaseNativeSbomFileName(
                        "1.7.15-SNAPSHOT", NativeTarget.LINUX_X86_64));
        assertThrows(
                IllegalArgumentException.class,
                () -> NativePackageMetadata.releaseSbomFileName("1.7.15-../../escape"));
        assertThrows(
                IllegalArgumentException.class,
                () -> NativePackageMetadata.releaseNativeSbomFileName(
                        "1.7.15-../../escape", NativeTarget.WINDOWS_X86_64));
    }

    @Test
    public void selectedTargetMustMatchHostAndArchitecture() {
        NativePackageMetadata.requireMatchingHost(
                NativePackageMetadata.target(NativeTarget.LINUX_X86_64),
                "Linux",
                "amd64");
        NativePackageMetadata.requireMatchingHost(
                NativePackageMetadata.target(NativeTarget.WINDOWS_X86_64),
                "Windows 11",
                "x86_64");
        NativePackageMetadata.requireMatchingHost(
                NativePackageMetadata.target(NativeTarget.MACOS_AARCH64),
                "Mac OS X",
                "arm64");

        IllegalArgumentException foreign = assertThrows(
                IllegalArgumentException.class,
                () -> NativePackageMetadata.requireMatchingHost(
                        NativePackageMetadata.target(NativeTarget.MACOS_X86_64),
                        "Linux",
                        "amd64"));
        assertTrue(foreign.getMessage().contains("cannot cross-build"));
        assertThrows(
                IllegalArgumentException.class,
                () -> NativePackageMetadata.requireMatchingHost(
                        NativePackageMetadata.target(NativeTarget.LINUX_X86_64),
                        "Linux",
                        "aarch64"));
    }

    @Test
    public void runtimeModuleInventoryIsMinimizedAndLocked() {
        assertEquals(
                Set.of(
                        "java.base",
                        "java.compiler",
                        "java.desktop",
                        "java.logging",
                        "java.management",
                        "jdk.crypto.ec",
                        "jdk.unsupported"),
                Set.copyOf(NativePackageMetadata.RUNTIME_ROOT_MODULES));
        assertEquals(10, NativePackageMetadata.LINKED_RUNTIME_MODULES.size());
        assertTrue(
                NativePackageMetadata.LINKED_RUNTIME_MODULES.containsAll(
                        NativePackageMetadata.RUNTIME_ROOT_MODULES));
    }
}
