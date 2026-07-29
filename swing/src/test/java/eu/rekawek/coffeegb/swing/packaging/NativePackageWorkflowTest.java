package eu.rekawek.coffeegb.swing.packaging;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativePackageWorkflowTest {

    @Test
    public void workflowLocksEveryTargetToHostVerificationAndBoundedArtifacts()
            throws Exception {
        Path workflows = Path.of("../.github/workflows").toAbsolutePath().normalize();
        String packages = normalizedText(workflows.resolve("native-packages.yml"));
        String release = normalizedText(workflows.resolve("maven-release.yml"));
        String maven = normalizedText(workflows.resolve("maven.yml"));
        Path packaging = Path.of("../packaging").toAbsolutePath().normalize();
        String packageSh = normalizedText(packaging.resolve("verify-native-package.sh"));
        String packagePs1 = normalizedText(packaging.resolve("verify-native-package.ps1"));

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
        assertEquals(2, occurrences(packages, "package_type: exe"));
        assertFalse(packages.contains("package_type: msi"));
        assertTrue(packages.contains("verify-native-package.sh"));
        assertTrue(packages.contains("verify-native-package.ps1"));
        assertFalse(packages.contains("verify-native-association"));
        assertFalse(packages.contains("OS association"));
        assertTrue(packages.contains("COFFEE_GB_DESKTOP_SMOKE: \"true\""));
        assertTrue(packages.contains("xvfb-run -a ./packaging/package-native.sh"));
        assertEquals(2, occurrences(packages,
                "sudo apt-get install --yes --no-install-recommends "
                        + "desktop-file-utils gnome-menus xdg-utils"));
        assertEquals(2, occurrences(packages, "command -v desktop-file-validate"));
        assertEquals(0, occurrences(packages, "command -v gio"));
        assertEquals(0, occurrences(packages, "command -v mimetype"));
        assertEquals(0, occurrences(packages, "command -v update-mime-database"));
        assertEquals(2, occurrences(packages, "command -v xdg-desktop-menu"));
        assertEquals(2, occurrences(packages, "test -d /etc/xdg/menus"));
        assertEquals(2, occurrences(packages, "test -d /usr/share/desktop-directories"));
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
        assertTrue(packageSh.contains("grep -Eq '^MimeType='"));
        assertTrue(packageSh.contains("CFBundleDocumentTypes"));
        assertTrue(packageSh.contains("UTExportedTypeDeclarations"));
        assertTrue(packageSh.contains("UTImportedTypeDeclarations"));
        assertTrue(packagePs1.contains("Assert-NoRomAssociations"));
        assertTrue(packagePs1.contains("OpenWithProgids"));
        assertTrue(packagePs1.contains("OpenWithList"));
        assertLicensedDmgAttach(packageSh, "\"$extraction\"");
        assertFalse(packageSh.contains("-acceptlicense"));

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
        assertTrue(release.contains("gh release create \"$tag\""));
        assertTrue(release.contains("--draft"));
        assertTrue(release.contains("gh release delete-asset"));
        assertTrue(release.contains("gh release upload \"$tag\" \"${release_paths[@]}\""));
        assertTrue(release.contains("must not contain JSON artifacts"));
        assertTrue(release.contains("-iname '*.json'"));
        assertTrue(release.contains("GitHub release asset names differ"));
        assertTrue(release.contains("cmp --silent"));
        assertTrue(release.contains("--draft=false"));
        assertTrue(release.contains("--json isDraft,isPrerelease"));
        assertTrue(release.contains("--prerelease=false"));
        assertTrue(release.contains("Existing public GitHub release is unexpectedly a prerelease"));
        assertTrue(release.contains("Existing public GitHub release already matches"));
        assertTrue(release.contains("## Native package signing evidence"));
        assertTrue(release.contains("## Known platform limitations"));
        assertTrue(release.contains(
                "game controllers require a compatible system SDL2 installation; keyboard input "
                        + "and emulation remain usable without it"));
        assertTrue(release.contains("--jq '.body | @base64'"));
        assertTrue(release.contains("base64 --decode"));
        assertTrue(release.contains("--json name"));
        assertTrue(release.contains("cmp --silent \"$release_notes\""));
        assertTrue(release.contains("GitHub release title differs"));
        assertTrue(release.contains("GitHub release notes differ from the exact"));
        assertTrue(release.contains("releases/generate-notes"));
        assertTrue(release.contains("target\\\\.${target}\\\\.signing="));
        assertTrue(release.contains("--notes-file \"$release_notes\""));
        assertTrue(release.contains("--title \"coffee-gb ${RELEASE_VERSION}\""));
        assertFalse(release.contains("--clobber"));

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

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static String normalizedText(Path path) throws Exception {
        return Files.readString(path).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static void assertLicensedDmgAttach(String script, String mountPoint) {
        int start = script.indexOf("hdiutil attach");
        assertTrue("Missing licensed DMG attach command", start >= 0);
        int end = script.indexOf("<<<Y", start);
        assertTrue("Licensed DMG attach command has no stdin acceptance", end > start);
        String command = script.substring(start, end);
        assertTrue(command.contains("-nobrowse"));
        assertTrue(command.contains("-readonly"));
        assertTrue(command.contains("-mountpoint " + mountPoint));
        assertTrue(command.lastIndexOf("\"${installers[0]}\"")
                > command.indexOf("-mountpoint " + mountPoint));
        assertFalse(script.contains("| hdiutil attach"));
    }
}
