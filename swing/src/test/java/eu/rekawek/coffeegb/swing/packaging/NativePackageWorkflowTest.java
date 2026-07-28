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
        assertTrue(packages.contains("NativeReleaseTool"));
        assertTrue(packages.contains("needs: package"));
        assertTrue(packages.contains("package_root=\"$upload_root/package\""));
        assertTrue(packages.contains("does not match tagged/requested release"));
        assertTrue(packages.contains("retention-days: 7"));
        assertTrue(packages.contains("retention-days: 14"));
        assertTrue(packages.contains("permissions:\n  contents: read"));
        assertFalse(packages.contains("${{ secrets."));
        assertFalse(packages.contains("--release-sign"));

        assertTrue(release.contains("uses: ./.github/workflows/native-packages.yml"));
        assertTrue(release.contains("checkout_ref: coffee-gb-${{ inputs.release_version }}"));
        assertTrue(release.contains("publish: true"));
    }
}
