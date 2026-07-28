package eu.rekawek.coffeegb.swing.packaging;

import org.junit.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ReleaseSigningPolicyTest {

    @Test
    public void macSigningRequiresReleaseGateAndUsesKeychainReferences() {
        Map<String, String> environment = releaseEnvironment();
        environment.put("COFFEE_GB_MAC_SIGNING_KEY_USER_NAME", "Developer ID Application: Example");
        environment.put("COFFEE_GB_MAC_NOTARY_KEYCHAIN_PROFILE", "coffee-gb-notary");

        ReleaseSigningPolicy policy = ReleaseSigningPolicy.require(
                NativePackageMetadata.target(NativeTarget.MACOS_AARCH64),
                NativePackageMetadata.PackageType.DMG,
                "1.7.15",
                environment);
        assertTrue(policy.jpackageOptions().contains("--mac-sign"));
        assertTrue(policy.jpackageOptions().contains("--mac-signing-key-user-name"));
        List<List<String>> post = policy.postPackageCommands(Path.of("/dist/coffee-gb.dmg"));
        assertEquals("xcrun", post.get(0).get(0));
        assertTrue(post.get(0).contains("notarytool"));
        assertTrue(post.get(1).contains("stapler"));
    }

    @Test
    public void windowsAndLinuxUsePostPackageCredentialStores() {
        Map<String, String> windows = releaseEnvironment();
        windows.put(
                "COFFEE_GB_WINDOWS_CERTIFICATE_SHA1",
                "0123456789abcdef0123456789abcdef01234567");
        windows.put("COFFEE_GB_WINDOWS_TIMESTAMP_URL", "https://timestamp.example");
        ReleaseSigningPolicy windowsPolicy = ReleaseSigningPolicy.require(
                NativePackageMetadata.target(NativeTarget.WINDOWS_X86_64),
                NativePackageMetadata.PackageType.MSI,
                "1.7.15",
                windows);
        assertTrue(windowsPolicy.jpackageOptions().isEmpty());
        assertEquals(
                "signtool",
                windowsPolicy
                        .postPackageCommands(Path.of("coffee-gb.msi"))
                        .get(0)
                        .get(0));

        Map<String, String> linux = releaseEnvironment();
        linux.put("COFFEE_GB_LINUX_GPG_KEY_ID", "release@example.test");
        ReleaseSigningPolicy linuxPolicy = ReleaseSigningPolicy.require(
                NativePackageMetadata.target(NativeTarget.LINUX_X86_64),
                NativePackageMetadata.PackageType.DEB,
                "1.7.15",
                linux);
        assertEquals(
                "gpg",
                linuxPolicy
                        .postPackageCommands(Path.of("coffee-gb.deb"))
                        .get(0)
                        .get(0));
    }

    @Test
    public void pullRequestsSnapshotsAndMissingSecretsCanNeverSign() {
        Map<String, String> pullRequest = releaseEnvironment();
        pullRequest.put("GITHUB_EVENT_NAME", "pull_request_target");
        pullRequest.put("COFFEE_GB_LINUX_GPG_KEY_ID", "release");
        assertThrows(
                IllegalArgumentException.class,
                () -> ReleaseSigningPolicy.require(
                        NativePackageMetadata.target(NativeTarget.LINUX_X86_64),
                        NativePackageMetadata.PackageType.DEB,
                        "1.7.15",
                        pullRequest));

        Map<String, String> snapshot = releaseEnvironment();
        snapshot.put("COFFEE_GB_RELEASE_VERSION", "1.7.15-SNAPSHOT");
        snapshot.put("COFFEE_GB_LINUX_GPG_KEY_ID", "release");
        assertThrows(
                IllegalArgumentException.class,
                () -> ReleaseSigningPolicy.require(
                        NativePackageMetadata.target(NativeTarget.LINUX_X86_64),
                        NativePackageMetadata.PackageType.DEB,
                        "1.7.15-SNAPSHOT",
                        snapshot));

        Map<String, String> wrongTag = releaseEnvironment();
        wrongTag.put("GITHUB_REF_NAME", "coffee-gb-1.7.14");
        wrongTag.put("COFFEE_GB_LINUX_GPG_KEY_ID", "release");
        assertThrows(
                IllegalArgumentException.class,
                () -> ReleaseSigningPolicy.require(
                        NativePackageMetadata.target(NativeTarget.LINUX_X86_64),
                        NativePackageMetadata.PackageType.DEB,
                        "1.7.15",
                        wrongTag));

        assertThrows(
                IllegalArgumentException.class,
                () -> ReleaseSigningPolicy.require(
                        NativePackageMetadata.target(NativeTarget.MACOS_X86_64),
                        NativePackageMetadata.PackageType.DMG,
                        "1.7.15",
                        releaseEnvironment()));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReleaseSigningPolicy.require(
                        NativePackageMetadata.target(NativeTarget.WINDOWS_X86_64),
                        NativePackageMetadata.PackageType.APP_IMAGE,
                        "1.7.15",
                        releaseEnvironment()));
    }

    private static Map<String, String> releaseEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("COFFEE_GB_RELEASE_SIGNING", "true");
        environment.put("COFFEE_GB_RELEASE_VERSION", "1.7.15");
        environment.put("GITHUB_REF_TYPE", "tag");
        environment.put("GITHUB_REF_NAME", "coffee-gb-1.7.15");
        environment.put("GITHUB_EVENT_NAME", "workflow_dispatch");
        return environment;
    }
}
