package eu.rekawek.coffeegb.swing.packaging;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativePackageWorkflowTest {

    @Test
    public void workflowLocksEveryTargetToHostVerificationAndBoundedArtifacts()
            throws Exception {
        Path workflows = Path.of("../.github/workflows").toAbsolutePath().normalize();
        String packages = Files.readString(workflows.resolve("native-packages.yml"));
        String release = Files.readString(workflows.resolve("maven-release.yml"));
        String maven = Files.readString(workflows.resolve("maven.yml"));
        Path packaging = Path.of("../packaging").toAbsolutePath().normalize();
        String associationSh =
                Files.readString(packaging.resolve("verify-native-association.sh"));
        String associationPs1 =
                Files.readString(packaging.resolve("verify-native-association.ps1"));

        for (String target :
                new String[] {
                    "linux-x86-64",
                    "windows-x86-64",
                    "macos-x86-64",
                    "macos-aarch64"
                }) {
            assertTrue(packages.contains("target: " + target));
        }
        for (String runner :
                new String[] {
                    "ubuntu-24.04",
                    "windows-2025",
                    "macos-15-intel",
                    "macos-15"
                }) {
            assertTrue(packages.contains("runner: " + runner));
        }
        assertTrue(packages.contains("cache: maven"));
        assertTrue(packages.contains("cache-dependency-path: \"**/pom.xml\""));
        assertTrue(packages.contains("persist-credentials: false"));
        assertTrue(packages.contains("verify-native-package.sh"));
        assertTrue(packages.contains("verify-native-package.ps1"));
        assertTrue(packages.contains("verify-native-association.sh"));
        assertTrue(packages.contains("verify-native-association.ps1"));
        assertTrue(packages.contains(
                "Install package and open the synthetic ROM through the OS association"));
        assertTrue(packages.contains("COFFEE_GB_DESKTOP_SMOKE: \"true\""));
        assertTrue(packages.contains("xvfb-run -a ./packaging/package-native.sh"));
        assertTrue(packages.contains("environment: native-release"));
        assertTrue(packages.contains("inputs.release_signing"));
        assertFalse(packages.contains(
                "startsWith(github.ref, 'refs/tags/coffee-gb-')"));
        assertFalse(packages.contains("tags: [ \"coffee-gb-*\" ]"));
        assertTrue(packages.contains("NativeReleaseTool"));
        assertTrue(packages.contains("needs: [resolve-source, package]"));
        assertTrue(packages.contains("source_sha: ${{ steps.source.outputs.source_sha }}"));
        assertTrue(packages.contains("ref: ${{ needs.resolve-source.outputs.source_sha }}"));
        assertTrue(packages.contains("FETCH_HEAD^{commit}"));
        assertTrue(packages.contains("--source-commit \"$SOURCE_SHA\""));
        assertTrue(packages.contains("source.commit=$SOURCE_SHA"));
        assertTrue(packages.contains("package_root=\"$upload_root/package\""));
        assertTrue(packages.contains("does not match tagged/requested release"));
        assertTrue(packages.contains("retention-days: 7"));
        assertTrue(packages.contains("retention-days: 14"));
        assertTrue(packages.contains("permissions:\n  contents: read"));
        assertTrue(packages.contains(
                "!startsWith(github.event_name, 'pull_request')"));
        assertTrue(packages.contains("--release-sign"));
        assertTrue(packages.contains("NATIVE_LINUX_GPG_PRIVATE_KEY"));
        assertTrue(packages.contains("NATIVE_WINDOWS_CERTIFICATE_PFX_BASE64"));
        assertTrue(packages.contains("NATIVE_MACOS_CERTIFICATE_P12_BASE64"));
        assertTrue(packages.contains("NATIVE_MACOS_SIGNING_KEY_USER_NAME"));
        assertTrue(packages.contains("name: native-release-bundle"));
        assertFalse(packages.contains("gh release"));
        assertFalse(packages.contains("contents: write"));
        for (String extension : new String[] {"gb", "gbc", "rom"}) {
            assertTrue(associationSh.contains(extension));
            assertTrue(associationPs1.contains("." + extension));
        }
        assertTrue(associationSh.contains("open \"$fixture\""));
        assertFalse(associationSh.contains("open -b eu.rekawek.coffeegb"));
        assertTrue(associationPs1.contains("Start-Process -FilePath $Fixture.Path"));
        assertTrue(associationPs1.contains("Coffee GB Console.exe"));
        assertTrue(associationPs1.contains("$env:COFFEE_GB_RELEASE_SIGNING -eq \"true\""));
        assertTrue(associationPs1.contains("signtool.exe"));
        assertTrue(associationPs1.contains("/all"));
        assertTrue(associationSh.contains("codesign --verify --deep --strict"));
        assertTrue(associationSh.contains(
                "com.apple.security.cs.disable-library-validation"));

        assertTrue(release.contains("uses: ./.github/workflows/native-packages.yml"));
        assertTrue(release.contains("checkout_ref: ${{ needs.prepare.outputs.tag_ref }}"));
        assertTrue(release.contains("release:clean release:prepare"));
        assertFalse(release.contains("release:perform"));
        assertTrue(release.contains("needs: [prepare, native-packages]"));
        assertTrue(release.contains("Publish Maven artifacts after native validation"));
        assertTrue(release.contains("Publish GitHub release after Maven publication"));
        assertTrue(release.contains(
                "needs: [prepare, native-packages, publish-maven]"));
        assertTrue(release.contains("environment: native-release"));
        assertTrue(release.contains("name: native-release-bundle"));
        assertTrue(release.indexOf("release:clean release:prepare")
                < release.indexOf("uses: ./.github/workflows/native-packages.yml"));
        assertTrue(release.indexOf("uses: ./.github/workflows/native-packages.yml")
                < release.indexOf("Publish the validated tag to Maven Central"));
        assertTrue(release.indexOf("Publish the validated tag to Maven Central")
                < release.indexOf("gh release create"));

        for (String workflow : new String[] {packages, release, maven}) {
            assertFalse(workflow.contains("actions/checkout@v"));
            assertFalse(workflow.contains("actions/setup-java@v"));
            assertFalse(workflow.contains("actions/upload-artifact@v"));
            assertFalse(workflow.contains("actions/download-artifact@v"));
        }
        assertTrue(packages.contains(
                "actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a"));
        assertTrue(packages.contains(
                "actions/download-artifact@3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c"));
        assertTrue(release.contains(
                "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1"));
        assertTrue(maven.contains(
                "actions/setup-java@03ad4de0992f5dab5e18fcb136590ce7c4a0ac95"));
    }
}
