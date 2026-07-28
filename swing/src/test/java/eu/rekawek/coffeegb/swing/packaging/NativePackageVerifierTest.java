package eu.rekawek.coffeegb.swing.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    public void payloadPolicyInspectsForbiddenContentInsideNestedArchives() throws Exception {
        PayloadLayout layout = createPayloadLayout("archive-payload");
        Path archive = layout.appDirectory().resolve("libraries.jar");

        writeArchive(
                archive,
                Map.of(
                        "safe/readme.txt", "ordinary package resource".getBytes(StandardCharsets.UTF_8),
                        "config/build.properties",
                                "source=/home/release-user/coffee-gb"
                                        .getBytes(StandardCharsets.UTF_8)));
        assertPayloadPolicyRejects(layout.payload(), layout.appDirectory(), layout.runtime());

        Files.delete(archive);
        byte[] nested = archiveBytes(Map.of(
                "fixtures/package-smoke.gbc", new byte[] {1, 2, 3}));
        writeArchive(archive, Map.of("nested/resources.zip", nested));
        assertPayloadPolicyRejects(layout.payload(), layout.appDirectory(), layout.runtime());

        Files.delete(archive);
        writeArchive(archive, Map.of(
                "keys/release.p12", "not-a-real-key".getBytes(StandardCharsets.UTF_8)));
        assertPayloadPolicyRejects(layout.payload(), layout.appDirectory(), layout.runtime());
    }

    @Test
    public void payloadPolicyBoundsNestedArchiveDepth() throws Exception {
        PayloadLayout layout = createPayloadLayout("deep-archive-payload");
        byte[] archive = archiveBytes(
                Map.of("level-four/readme.txt", "safe".getBytes(StandardCharsets.UTF_8)));
        archive = archiveBytes(Map.of("level-three.zip", archive));
        archive = archiveBytes(Map.of("level-two.zip", archive));
        archive = archiveBytes(Map.of("level-one.zip", archive));
        Files.write(layout.appDirectory().resolve("outer.jar"), archive);

        assertPayloadPolicyRejects(layout.payload(), layout.appDirectory(), layout.runtime());
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

    private PayloadLayout createPayloadLayout(String name) throws Exception {
        Path payload = temporaryFolder.newFolder(name).toPath();
        Path appDirectory = payload.resolve("opt/coffee-gb/lib/app");
        Path runtime = payload.resolve("opt/coffee-gb/lib/runtime");
        Files.createDirectories(appDirectory);
        Files.createDirectories(runtime.resolve("lib"));
        Files.write(runtime.resolve("lib/modules"), new byte[] {1});
        return new PayloadLayout(payload, appDirectory, runtime);
    }

    private static void writeArchive(Path output, Map<String, byte[]> entries) throws Exception {
        Files.write(output, archiveBytes(entries));
    }

    private static byte[] archiveBytes(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream archive = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                archive.putNextEntry(new ZipEntry(entry.getKey()));
                archive.write(entry.getValue());
                archive.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private record PayloadLayout(Path payload, Path appDirectory, Path runtime) {
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
