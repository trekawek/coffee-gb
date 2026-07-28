package eu.rekawek.coffeegb.swing.packaging;

import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipMethod;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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
    public void everySmokeHandoffStartsWithAnIndependentEmptyNativeCache() throws Exception {
        Path home = temporaryFolder.newFolder("smoke-caches").toPath();

        NativePackageVerifier.SmokeCacheLayout caches =
                NativePackageVerifier.createSmokeCaches(home);

        List<Path> paths = List.of(
                caches.directRuntime(),
                caches.packagedLauncher(),
                caches.desktopNormal(),
                caches.desktopDebug());
        assertEquals(paths.size(), new HashSet<>(paths).size());
        for (Path cache : paths) {
            assertTrue(Files.isDirectory(cache));
            try (Stream<Path> contents = Files.list(cache)) {
                assertEquals(0, contents.count());
            }
        }
        Files.writeString(caches.directRuntime().resolve("prewarmed"), "cached");
        try (Stream<Path> launcherContents = Files.list(caches.packagedLauncher())) {
            assertEquals(0, launcherContents.count());
        }
    }

    @Test
    public void packagedRuntimeLayoutUsesTheNestedMacRuntimeHome() throws Exception {
        Path runtimeBundle = temporaryFolder.newFolder("mac-runtime").toPath();
        Path runtimeHome = runtimeBundle.resolve("Contents").resolve("Home");
        Path java = writeSyntheticRuntime(runtimeHome, "java");

        NativePackageVerifier.RuntimeLayout layout =
                NativePackageVerifier.requireRuntimeLayout(
                        runtimeBundle, NativeTarget.MACOS_AARCH64);

        assertEquals(runtimeHome, layout.home());
        assertEquals(java, layout.javaExecutable());

        Path flatRuntime = temporaryFolder.newFolder("flat-mac-runtime").toPath();
        writeSyntheticRuntime(flatRuntime, "java");
        assertThrows(
                java.io.IOException.class,
                () -> NativePackageVerifier.requireRuntimeLayout(
                        flatRuntime, NativeTarget.MACOS_X86_64));
    }

    @Test
    public void packagedRuntimeLayoutRemainsFlatOnLinuxAndWindows() throws Exception {
        Path linuxRuntime = temporaryFolder.newFolder("linux-runtime").toPath();
        Path linuxJava = writeSyntheticRuntime(linuxRuntime, "java");
        NativePackageVerifier.RuntimeLayout linuxLayout =
                NativePackageVerifier.requireRuntimeLayout(
                        linuxRuntime, NativeTarget.LINUX_X86_64);
        assertEquals(linuxRuntime, linuxLayout.home());
        assertEquals(linuxJava, linuxLayout.javaExecutable());

        Path windowsRuntime = temporaryFolder.newFolder("windows-runtime").toPath();
        Path windowsJava = writeSyntheticRuntime(windowsRuntime, "java.exe");
        NativePackageVerifier.RuntimeLayout windowsLayout =
                NativePackageVerifier.requireRuntimeLayout(
                        windowsRuntime, NativeTarget.WINDOWS_X86_64);
        assertEquals(windowsRuntime, windowsLayout.home());
        assertEquals(windowsJava, windowsLayout.javaExecutable());
    }

    @Test
    public void distributionResultAndExactChecksumCoverageRoundTrip() throws Exception {
        Path dist = temporaryFolder.newFolder("dist").toPath();
        Path installer = Files.writeString(
                dist.resolve("coffee-gb.deb"), "synthetic installer", StandardCharsets.UTF_8);
        Path sbom = NativePackagingTestSupport.writeMavenSbom(
                dist.resolve("coffee-gb-1.7.15-sbom.cdx.json"), "1.7.15");
        Path nativeSbom = NativePackagingTestSupport.writeNativeSbom(
                dist.resolve("coffee-gb-1.7.15-linux-x86-64-native-sbom.cdx.json"),
                NativeTarget.LINUX_X86_64,
                "1.7.15");
        NativePackageVerifier.writeBuildResult(
                dist,
                NativeTarget.LINUX_X86_64,
                NativePackageMetadata.PackageType.DEB,
                "1.7.15",
                installer,
                sbom,
                nativeSbom,
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
    public void externalPropertiesAndTargetChecksumsAreReadWithMetadataBounds()
            throws Exception {
        Path properties = temporaryFolder.newFile("oversized.properties").toPath();
        try (var channel = Files.newByteChannel(
                properties,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.position(NativePackageVerifier.MAX_METADATA_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {1}));
        }
        assertThrows(
                java.io.IOException.class,
                () -> NativePackageVerifier.readStrictProperties(properties));

        Path dist = temporaryFolder.newFolder("oversized-checksums").toPath();
        Path checksums = dist.resolve("SHA256SUMS");
        try (var channel = Files.newByteChannel(
                checksums,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            channel.position(NativePackageVerifier.MAX_METADATA_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {1}));
        }
        assertThrows(
                java.io.IOException.class,
                () -> NativePackageVerifier.verifyChecksums(dist, checksums));
    }

    @Test
    public void detachedSignatureIsARequiredChecksummedReleaseArtifact() throws Exception {
        Path dist = temporaryFolder.newFolder("signed-dist").toPath();
        Path installer = Files.writeString(
                dist.resolve("coffee-gb.deb"), "signed installer", StandardCharsets.UTF_8);
        Path signature = Files.writeString(
                dist.resolve("coffee-gb.deb.asc"), "detached signature", StandardCharsets.UTF_8);
        Path sbom = NativePackagingTestSupport.writeMavenSbom(
                dist.resolve("coffee-gb-1.7.15-sbom.cdx.json"), "1.7.15");
        Path nativeSbom = NativePackagingTestSupport.writeNativeSbom(
                dist.resolve("coffee-gb-1.7.15-linux-x86-64-native-sbom.cdx.json"),
                NativeTarget.LINUX_X86_64,
                "1.7.15");
        NativePackageVerifier.writeBuildResult(
                dist,
                NativeTarget.LINUX_X86_64,
                NativePackageMetadata.PackageType.DEB,
                "1.7.15",
                installer,
                sbom,
                nativeSbom,
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

        Files.createDirectories(payload.resolve("other/jre/LIB"));
        Files.write(payload.resolve("other/jre/LIB/MODULES"), new byte[] {1});
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

        Files.delete(archive);
        writeArchive(archive, Map.of(
                "hidden/windows/controller.dll", new byte[] {1, 2, 3}));
        assertPayloadPolicyRejects(layout.payload(), layout.appDirectory(), layout.runtime());

        Files.delete(archive);
        byte[] versionedElf = archiveBytes(Map.of(
                "native/linux/libforeign.so.6", new byte[] {1, 2, 3}));
        writeArchive(archive, Map.of("nested/native-resources.zip", versionedElf));
        assertThrows(
                java.io.IOException.class,
                () -> NativePackageVerifier.verifyPayloadPolicy(
                        layout.payload(),
                        layout.appDirectory(),
                        layout.runtime(),
                        NativeTarget.WINDOWS_X86_64));

        Files.delete(archive);
        writeSymlinkArchive(archive);
        assertPayloadPolicyRejects(layout.payload(), layout.appDirectory(), layout.runtime());

        Files.delete(archive);
        writeArchive(archive, Map.of(
                "runtime-copy/lib/modules", new byte[] {1, 2, 3}));
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
    public void payloadPolicyBoundsTopLevelArchiveContainersAndEntryNames() throws Exception {
        PayloadLayout layout = createPayloadLayout("bounded-archive-payload");
        Path archive = layout.appDirectory().resolve("libraries.jar");
        try (var channel = Files.newByteChannel(
                archive,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            channel.position(256L * 1024 * 1024);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }
        java.io.IOException oversized = assertThrows(
                java.io.IOException.class,
                () -> NativePackageVerifier.verifyPayloadPolicy(
                        layout.payload(),
                        layout.appDirectory(),
                        layout.runtime(),
                        NativeTarget.LINUX_X86_64));
        assertTrue(oversized.getMessage().contains("bounded container size"));

        Files.delete(archive);
        writeArchive(
                archive,
                Map.of("a".repeat(4_097), new byte[] {1}));
        java.io.IOException longName = assertThrows(
                java.io.IOException.class,
                () -> NativePackageVerifier.verifyPayloadPolicy(
                        layout.payload(),
                        layout.appDirectory(),
                        layout.runtime(),
                        NativeTarget.LINUX_X86_64));
        assertTrue(longName.getMessage().contains("entry name exceeds"));
    }

    @Test
    public void payloadPolicyRejectsRecognizedTextBeyondTheInspectionBound() throws Exception {
        PayloadLayout layout = createPayloadLayout("oversized-text-payload");
        Path text = layout.appDirectory().resolve("credentials.txt");
        try (var channel = Files.newByteChannel(
                text,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(
                    "client_secret=synthetic-secret-value".getBytes(StandardCharsets.UTF_8)));
            channel.position(2L * 1024 * 1024);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }

        java.io.IOException oversized = assertThrows(
                java.io.IOException.class,
                () -> NativePackageVerifier.verifyPayloadPolicy(
                        layout.payload(),
                        layout.appDirectory(),
                        layout.runtime(),
                        NativeTarget.LINUX_X86_64));

        assertTrue(oversized.getMessage().contains("bounded inspection size"));
    }

    @Test
    public void mavenSbomIsBoundedBeforeItIsRead() throws Exception {
        Path sbom = temporaryFolder.newFile("oversized-sbom.json").toPath();
        try (var channel = Files.newByteChannel(
                sbom,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.write(ByteBuffer.wrap(
                    "{\"bomFormat\":\"CycloneDX\"}".getBytes(StandardCharsets.UTF_8)));
            channel.position(8L * 1024 * 1024);
            channel.write(ByteBuffer.wrap(new byte[] {0}));
        }

        java.io.IOException oversized = assertThrows(
                java.io.IOException.class,
                () -> NativePackageVerifier.verifyMavenSbom(sbom, "1.7.15"));

        assertTrue(oversized.getMessage().contains("invalid size"));
    }

    @Test
    public void mavenSbomVersionMustBelongToTheExactRootComponent() throws Exception {
        Path sbom = temporaryFolder.newFile("wrong-root-sbom.json").toPath();
        String wrongRoot =
                "pkg:maven/eu.rekawek.coffeegb/swing@9.9.9?type=jar";
        Files.writeString(
                sbom,
                "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\","
                        + "\"metadata\":{\"component\":{\"type\":\"application\","
                        + "\"bom-ref\":\"" + wrongRoot + "\","
                        + "\"group\":\"eu.rekawek.coffeegb\",\"name\":\"swing\","
                        + "\"version\":\"9.9.9\",\"purl\":\"" + wrongRoot + "\"}},"
                        + "\"components\":[{\"type\":\"library\",\"name\":\"expected-version\","
                        + "\"version\":\"1.7.15\","
                        + "\"purl\":\"pkg:maven/example/dependency@1.7.15?type=jar\"}]}",
                StandardCharsets.UTF_8);

        assertThrows(
                java.io.IOException.class,
                () -> NativePackageVerifier.verifyMavenSbom(sbom, "1.7.15"));
    }

    @Test
    public void mavenSbomRequiresStrictJsonAcrossTheCompleteDocument() throws Exception {
        Path valid = NativePackagingTestSupport.writeMavenSbom(
                temporaryFolder.newFile("valid-sbom.json").toPath(), "1.7.15");
        String json = Files.readString(valid, StandardCharsets.UTF_8).stripTrailing();

        Path trailingComma = temporaryFolder.newFile("trailing-comma-sbom.json").toPath();
        Files.writeString(
                trailingComma,
                json.substring(0, json.length() - 1) + ",}",
                StandardCharsets.UTF_8);
        assertThrows(
                java.io.IOException.class,
                () -> NativePackageVerifier.verifyMavenSbom(trailingComma, "1.7.15"));

        Path malformedNested = temporaryFolder.newFile("malformed-nested-sbom.json").toPath();
        Files.writeString(
                malformedNested,
                json.substring(0, json.length() - 1) + ",\"malformed\":[{]}",
                StandardCharsets.UTF_8);
        assertThrows(
                java.io.IOException.class,
                () -> NativePackageVerifier.verifyMavenSbom(malformedNested, "1.7.15"));

        Path nonAsciiNumber = temporaryFolder.newFile("non-ascii-number-sbom.json").toPath();
        Files.writeString(
                nonAsciiNumber,
                json.substring(0, json.length() - 1)
                        + ",\"nonAscii\":1."
                        + Character.toString(0x0661)
                        + "}",
                StandardCharsets.UTF_8);
        assertThrows(
                java.io.IOException.class,
                () -> NativePackageVerifier.verifyMavenSbom(nonAsciiNumber, "1.7.15"));

        Path nonAsciiEscape = temporaryFolder.newFile("non-ascii-escape-sbom.json").toPath();
        Files.writeString(
                nonAsciiEscape,
                json.substring(0, json.length() - 1)
                        + ",\"nonAsciiEscape\":\"bad\\u066"
                        + Character.toString(0x0661)
                        + "\"}",
                StandardCharsets.UTF_8);
        assertThrows(
                java.io.IOException.class,
                () -> NativePackageVerifier.verifyMavenSbom(nonAsciiEscape, "1.7.15"));
    }

    @Test
    public void mavenSbomPurlsMustBeDirectUniqueComponentFields() throws Exception {
        Path valid = NativePackagingTestSupport.writeMavenSbom(
                temporaryFolder.newFile("component-purls.json").toPath(), "1.7.15");
        String json = Files.readString(valid, StandardCharsets.UTF_8);
        int components = json.indexOf("\"components\":");
        int dependencies = json.indexOf("\"dependencies\":");
        String prefix = json.substring(0, components);
        String suffix = json.substring(dependencies);
        String dependencyPurl = "pkg:maven/example/example@1?type=jar";

        Path relocated = temporaryFolder.newFile("relocated-purls.json").toPath();
        Files.writeString(
                relocated,
                prefix + "\"components\":[],\"unrelated\":{\"purl\":\""
                        + dependencyPurl + "\"}," + suffix,
                StandardCharsets.UTF_8);
        assertThrows(
                java.io.IOException.class,
                () -> NativePackageVerifier.verifyMavenSbom(relocated, "1.7.15"));

        for (String malformedComponents : List.of(
                "[1]",
                "[{\"name\":\"missing-purl\"}]",
                "[{\"purl\":1}]",
                "[{\"purl\":\"" + dependencyPurl + "\",\"purl\":\""
                        + dependencyPurl + "\"}]",
                "[{\"purl\":\"" + dependencyPurl + "\"},{\"purl\":\""
                        + dependencyPurl + "\"}]")) {
            Path malformed = temporaryFolder.newFile().toPath();
            Files.writeString(
                    malformed,
                    prefix + "\"components\":" + malformedComponents + "," + suffix,
                    StandardCharsets.UTF_8);
            assertThrows(
                    java.io.IOException.class,
                    () -> NativePackageVerifier.verifyMavenSbom(malformed, "1.7.15"));
        }
    }

    @Test
    public void legalNoticesMustMatchTheAuthoritativeResources() throws Exception {
        Path source = Path.of("../packaging/resources/legal").toAbsolutePath().normalize();
        Path packaged = temporaryFolder.newFolder("legal").toPath();
        for (String relative :
                NativeComponentInventory.requiredLegalPaths(NativeTarget.LINUX_X86_64)) {
            Path destination = packaged.resolve(relative);
            Files.createDirectories(destination.getParent());
            Files.copy(
                    source.resolve(relative),
                    destination,
                    StandardCopyOption.COPY_ATTRIBUTES);
        }

        NativePackageVerifier.verifyLegalInventory(
                packaged, source, NativeTarget.LINUX_X86_64);
        Files.writeString(
                packaged.resolve("THIRD-PARTY-NOTICES.txt"),
                "altered notice",
                StandardCharsets.UTF_8);
        assertThrows(
                java.io.IOException.class,
                () -> NativePackageVerifier.verifyLegalInventory(
                        packaged, source, NativeTarget.LINUX_X86_64));
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

    private static Path writeSyntheticRuntime(Path home, String javaName) throws Exception {
        Path java = Files.write(
                Files.createDirectories(home.resolve("bin")).resolve(javaName),
                new byte[] {1});
        Files.write(
                Files.createDirectories(home.resolve("lib")).resolve("modules"),
                new byte[] {1});
        return java;
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

    private static void writeSymlinkArchive(Path output) throws Exception {
        byte[] target = "../outside".getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(target);
        try (ZipArchiveOutputStream archive = new ZipArchiveOutputStream(output)) {
            ZipArchiveEntry entry = new ZipArchiveEntry("hidden/link");
            entry.setMethod(ZipMethod.STORED.getCode());
            entry.setSize(target.length);
            entry.setCrc(crc.getValue());
            entry.setUnixMode(UnixStat.LINK_FLAG | UnixStat.DEFAULT_LINK_PERM);
            archive.putArchiveEntry(entry);
            archive.write(target);
            archive.closeArchiveEntry();
        }
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
