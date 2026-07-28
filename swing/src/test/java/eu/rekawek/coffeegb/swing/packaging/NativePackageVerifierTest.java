package eu.rekawek.coffeegb.swing.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class NativePackageVerifierTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void realDesktopSmokeRequiresAnExplicitCiOptIn() {
        assertEquals(false, NativePackageVerifier.desktopSmokeEnabled(Map.of()));
        assertEquals(
                true,
                NativePackageVerifier.desktopSmokeEnabled(
                        Map.of("COFFEE_GB_DESKTOP_SMOKE", "TrUe")));
        assertEquals(
                false,
                NativePackageVerifier.desktopSmokeEnabled(
                        Map.of("COFFEE_GB_DESKTOP_SMOKE", "false")));
    }

    @Test
    public void distributionResultAndExactChecksumCoverageRoundTrip() throws Exception {
        Path dist = temporaryFolder.newFolder("dist").toPath();
        Path installer = Files.writeString(
                dist.resolve("coffee-gb.deb"), "synthetic installer", StandardCharsets.UTF_8);
        Path sbom = NativePackagingTestSupport.writeTargetSbom(
                dist.resolve("coffee-gb-1.7.15-linux-x86-64-sbom.cdx.json"),
                installer,
                NativeTarget.LINUX_X86_64,
                NativePackageMetadata.PackageType.DEB,
                "1.7.15",
                "unsigned");
        NativePackageVerifier.writeBuildResult(
                dist,
                NativeTarget.LINUX_X86_64,
                NativePackageMetadata.PackageType.DEB,
                "1.7.15",
                installer,
                sbom,
                "unsigned",
                null);
        writeChecksums(dist);

        assertEquals(
                "unsigned",
                NativePackageVerifier.verifyDistribution(
                                dist,
                                NativeTarget.LINUX_X86_64,
                                NativePackageMetadata.PackageType.DEB,
                                "1.7.15")
                        .get("signing"));

        Files.writeString(dist.resolve("unlisted.key"), "secret", StandardCharsets.UTF_8);
        assertThrows(
                java.io.IOException.class,
                () -> NativePackageVerifier.verifyDistribution(
                        dist,
                        NativeTarget.LINUX_X86_64,
                        NativePackageMetadata.PackageType.DEB,
                        "1.7.15"));
    }

    @Test
    public void strictPropertiesRejectDuplicateKeys() throws Exception {
        Path properties = temporaryFolder.newFile("duplicate.properties").toPath();
        Files.writeString(
                properties,
                "schema=1\nschema=2\n",
                StandardCharsets.UTF_8);

        assertThrows(
                java.io.IOException.class,
                () -> NativePackageVerifier.readStrictProperties(properties));
    }

    @Test
    public void detachedSignatureIsARequiredChecksummedReleaseArtifact() throws Exception {
        Path dist = temporaryFolder.newFolder("signed-dist").toPath();
        Path installer = Files.writeString(
                dist.resolve("coffee-gb.deb"), "signed installer", StandardCharsets.UTF_8);
        Path signature = Files.writeString(
                dist.resolve("coffee-gb.deb.asc"), "detached signature", StandardCharsets.UTF_8);
        Path sbom = NativePackagingTestSupport.writeTargetSbom(
                dist.resolve("coffee-gb-1.7.15-linux-x86-64-sbom.cdx.json"),
                installer,
                NativeTarget.LINUX_X86_64,
                NativePackageMetadata.PackageType.DEB,
                "1.7.15",
                "verified-detached");
        NativePackageVerifier.writeBuildResult(
                dist,
                NativeTarget.LINUX_X86_64,
                NativePackageMetadata.PackageType.DEB,
                "1.7.15",
                installer,
                sbom,
                "verified-detached",
                signature);
        writeChecksums(dist);

        assertEquals(
                "verified-detached",
                NativePackageVerifier.verifyDistribution(
                                dist,
                                NativeTarget.LINUX_X86_64,
                                NativePackageMetadata.PackageType.DEB,
                                "1.7.15")
                        .get("signing"));
        Files.delete(signature);
        assertThrows(
                java.io.IOException.class,
                () -> NativePackageVerifier.verifyDistribution(
                        dist,
                        NativeTarget.LINUX_X86_64,
                        NativePackageMetadata.PackageType.DEB,
                        "1.7.15"));
    }

    @Test
    public void payloadPolicyCoversFilesOutsideTheApplicationRoot() throws Exception {
        Path payload = temporaryFolder.newFolder("payload").toPath();
        Path appDirectory = payload.resolve("opt/coffee-gb/lib/app");
        Path runtime = payload.resolve("opt/coffee-gb/lib/runtime");
        Files.createDirectories(appDirectory);
        Files.createDirectories(runtime.resolve("lib"));
        Files.write(runtime.resolve("lib/modules"), new byte[] {1});
        Path outside = Files.createDirectories(payload.resolve("usr/share/coffee-gb"));

        NativePackageVerifier.verifyPayloadPolicy(
                payload, appDirectory, runtime, NativeTarget.LINUX_X86_64);

        Path forbidden = Files.write(outside.resolve("foreign.dll"), new byte[] {1});
        assertPayloadPolicyRejects(payload, appDirectory, runtime);
        Files.delete(forbidden);

        forbidden = Files.write(outside.resolve("test.gbc"), new byte[] {1});
        assertPayloadPolicyRejects(payload, appDirectory, runtime);
        Files.delete(forbidden);

        forbidden = Files.writeString(
                outside.resolve("build.properties"),
                "source=C:\\\\Users\\\\developer\\\\coffee-gb",
                StandardCharsets.UTF_8);
        assertPayloadPolicyRejects(payload, appDirectory, runtime);
        Files.delete(forbidden);

        forbidden = Files.writeString(
                outside.resolve("credentials.txt"),
                "client_secret=synthetic-secret-value",
                StandardCharsets.UTF_8);
        assertPayloadPolicyRejects(payload, appDirectory, runtime);
        Files.delete(forbidden);

        Files.createDirectories(payload.resolve("other/runtime/lib"));
        Files.write(payload.resolve("other/runtime/lib/modules"), new byte[] {1});
        assertPayloadPolicyRejects(payload, appDirectory, runtime);
    }

    @Test
    public void legalNoticesMustMatchTheAuthoritativeResources() throws Exception {
        Path source = Path.of("../packaging/resources/legal").toAbsolutePath().normalize();
        Path packaged = temporaryFolder.newFolder("legal").toPath();
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = packaged.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }

        NativePackageVerifier.verifyLegalInventory(packaged, source);
        Files.writeString(
                packaged.resolve("THIRD-PARTY-NOTICES.txt"),
                "altered notice",
                StandardCharsets.UTF_8);
        assertThrows(
                java.io.IOException.class,
                () -> NativePackageVerifier.verifyLegalInventory(packaged, source));
    }

    private static void assertPayloadPolicyRejects(
            Path payload, Path appDirectory, Path runtime) {
        assertThrows(
                java.io.IOException.class,
                () -> NativePackageVerifier.verifyPayloadPolicy(
                        payload, appDirectory, runtime, NativeTarget.LINUX_X86_64));
    }

    private static void writeChecksums(Path dist) throws Exception {
        List<Path> files;
        try (Stream<Path> paths = Files.list(dist)) {
            files = paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        StringBuilder contents = new StringBuilder();
        for (Path file : files) {
            contents.append(NativePackageStager.sha256(file))
                    .append("  ")
                    .append(file.getFileName())
                    .append('\n');
        }
        Files.writeString(
                dist.resolve("SHA256SUMS"), contents.toString(), StandardCharsets.UTF_8);
    }
}
