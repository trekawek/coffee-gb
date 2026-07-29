package eu.rekawek.coffeegb.swing.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ReleaseSigningPolicyTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void macSigningRequiresReleaseGateAndUsesKeychainReferences() throws Exception {
        Map<String, String> environment = releaseEnvironment();
        environment.put("COFFEE_GB_MAC_SIGNING_KEY_USER_NAME", "Example");
        environment.put(
                "COFFEE_GB_MAC_SIGNING_IDENTITY",
                "Developer ID Application: Example (ABCD123456)");
        environment.put("COFFEE_GB_MAC_NOTARY_KEYCHAIN_PROFILE", "coffee-gb-notary");
        environment.put("COFFEE_GB_MAC_NOTARY_KEYCHAIN", "/tmp/release.keychain-db");

        ReleaseSigningPolicy policy = ReleaseSigningPolicy.require(
                NativePackageMetadata.target(NativeTarget.MACOS_AARCH64),
                NativePackageMetadata.PackageType.DMG,
                "1.7.15",
                environment);
        assertTrue(policy.jpackageOptions().contains("--mac-sign"));
        assertTrue(policy.jpackageOptions().contains("--mac-signing-key-user-name"));
        List<List<String>> post = policy.postPackageCommands(Path.of("/dist/coffee-gb.dmg"));
        assertEquals("codesign", post.get(0).get(0));
        assertTrue(post.get(0).contains("--timestamp"));
        assertTrue(post.get(0).contains("Developer ID Application: Example (ABCD123456)"));
        assertTrue(post.get(1).contains("--verify"));
        assertEquals("hdiutil", post.get(2).get(0));
        assertTrue(post.get(3).contains("notarytool"));
        assertTrue(post.get(3).contains("--keychain"));
        assertTrue(post.get(3).contains("/tmp/release.keychain-db"));
        assertTrue(post.get(4).contains("stapler"));
        assertEquals("codesign", policy.verificationCommands(
                Path.of("/dist/coffee-gb.dmg")).get(0).get(0));
        assertTrue(policy.verificationCommands(Path.of("/dist/coffee-gb.dmg"))
                .get(2)
                .contains("context:primary-signature"));
        List<List<String>> appImageVerification =
                policy.appImageVerificationCommands(Path.of("/dist/Coffee GB.app"));
        assertEquals("codesign", appImageVerification.get(0).get(0));
        assertTrue(appImageVerification.get(0).contains("--deep"));
        assertEquals(2, appImageVerification.size());
        assertTrue(appImageVerification.get(1).stream().anyMatch(argument ->
                argument.contains("com.apple.security.cs.disable-library-validation")));
        assertEquals("verified-embedded", policy.verifiedSigningState());
    }

    @Test
    public void windowsAndLinuxUsePostPackageCredentialStores() throws Exception {
        Map<String, String> windows = releaseEnvironment();
        windows.put(
                "COFFEE_GB_WINDOWS_CERTIFICATE_SHA1",
                "0123456789abcdef0123456789abcdef01234567");
        windows.put("COFFEE_GB_WINDOWS_TIMESTAMP_URL", "https://timestamp.example");
        ReleaseSigningPolicy windowsPolicy = ReleaseSigningPolicy.require(
                NativePackageMetadata.target(NativeTarget.WINDOWS_X86_64),
                NativePackageMetadata.PackageType.EXE,
                "1.7.15",
                windows);
        assertTrue(windowsPolicy.jpackageOptions().isEmpty());
        List<String> installerSigning =
                windowsPolicy.postPackageCommands(Path.of("coffee-gb.exe")).get(0);
        assertEquals("signtool", installerSigning.get(0));
        assertFalse(installerSigning.contains("/as"));
        assertTrue(windowsPolicy.verificationCommands(Path.of("coffee-gb.exe"))
                .get(0)
                .contains("/tw"));
        Path appImage = temporaryFolder.newFolder("windows-app").toPath();
        Files.write(appImage.resolve("Coffee GB.exe"), new byte[] {1});
        Files.write(appImage.resolve("Coffee GB Console.exe"), new byte[] {2});
        Files.createDirectories(appImage.resolve("runtime/bin"));
        Files.write(appImage.resolve("runtime/bin/java.dll"), new byte[] {3});
        Files.write(appImage.resolve("README.txt"), new byte[] {4});
        List<List<String>> appImageSigning =
                windowsPolicy.appImagePostPackageCommands(appImage);
        assertEquals(3, appImageSigning.size());
        assertTrue(appImageSigning.stream().allMatch(command ->
                command.get(0).equals("signtool") && command.contains("/as")));
        List<List<String>> appImageVerification =
                windowsPolicy.appImageVerificationCommands(appImage);
        assertEquals(3, appImageVerification.size());
        assertTrue(appImageVerification.stream()
                .allMatch(command -> command.contains("verify") && command.contains("/all")));

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
        assertEquals(
                Path.of("coffee-gb.deb.asc"),
                linuxPolicy.signatureArtifact(Path.of("coffee-gb.deb")).orElseThrow());
        assertEquals(
                "gpg",
                linuxPolicy
                        .verificationCommands(Path.of("coffee-gb.deb"))
                        .get(0)
                        .get(0));
        assertEquals("verified-detached", linuxPolicy.verifiedSigningState());
        assertTrue(linuxPolicy.appImagePostPackageCommands(Path.of("Coffee GB")).isEmpty());
        assertTrue(linuxPolicy.appImageVerificationCommands(Path.of("Coffee GB")).isEmpty());
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
        Map<String, String> incompleteMacIdentity = releaseEnvironment();
        incompleteMacIdentity.put("COFFEE_GB_MAC_SIGNING_KEY_USER_NAME", "Example");
        incompleteMacIdentity.put(
                "COFFEE_GB_MAC_SIGNING_IDENTITY",
                "Developer ID Application: Example");
        incompleteMacIdentity.put(
                "COFFEE_GB_MAC_NOTARY_KEYCHAIN_PROFILE", "coffee-gb-notary");
        assertThrows(
                IllegalArgumentException.class,
                () -> ReleaseSigningPolicy.require(
                        NativePackageMetadata.target(NativeTarget.MACOS_X86_64),
                        NativePackageMetadata.PackageType.DMG,
                        "1.7.15",
                        incompleteMacIdentity));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReleaseSigningPolicy.require(
                        NativePackageMetadata.target(NativeTarget.WINDOWS_X86_64),
                        NativePackageMetadata.PackageType.APP_IMAGE,
                        "1.7.15",
                        releaseEnvironment()));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReleaseSigningPolicy.require(
                        NativePackageMetadata.target(NativeTarget.WINDOWS_X86_64),
                        NativePackageMetadata.PackageType.MSI,
                        "1.7.15",
                        releaseEnvironment()));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReleaseSigningPolicy.require(
                        NativePackageMetadata.target(NativeTarget.MACOS_X86_64),
                        NativePackageMetadata.PackageType.PKG,
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
