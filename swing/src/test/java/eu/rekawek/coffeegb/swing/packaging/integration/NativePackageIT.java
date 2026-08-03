package eu.rekawek.coffeegb.swing.packaging.integration;

import eu.rekawek.coffeegb.swing.packaging.NativeBundleEntry;
import eu.rekawek.coffeegb.swing.packaging.NativeBundleManifest;
import eu.rekawek.coffeegb.swing.packaging.NativeComponentInventory;
import eu.rekawek.coffeegb.swing.packaging.NativePackageMetadata;
import eu.rekawek.coffeegb.swing.packaging.NativePackageStager;
import eu.rekawek.coffeegb.swing.packaging.NativeTarget;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Post-package content and determinism checks that do not cross-build foreign installers. */
public class NativePackageIT {

    private static final FileTime DETERMINISTIC_TIME =
            FileTime.from(Instant.parse("2000-01-01T00:00:00Z"));

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void stagesEveryTargetFromMavenArtifactsWithOnlyItsLockedNatives() throws Exception {
        Path app = Path.of(required("coffeeGbAppJar"));
        Path universal = Path.of(required("coffeeGbUniversalJar"));
        Path sbom = Path.of(required("coffeeGbSbom"));
        Path resources = Path.of(required("coffeeGbPackagingResources"));
        String expectedVersion = required("coffeeGbExpectedVersion");

        for (NativeTarget target : NativeTarget.values()) {
            Path output = temporaryFolder.getRoot().toPath().resolve(target.id());
            NativePackageStager.StageResult result = new NativePackageStager().stage(
                    new NativePackageStager.StageRequest(
                            target, app, universal, sbom, resources, output));
            assertEquals(expectedVersion, result.appVersion());
            assertEquals(
                    NativePackageStager.sha256(app),
                    NativePackageStager.sha256(result.appJar()));
            assertEquals(target, result.target().nativeTarget());

            List<NativeBundleEntry> expectedEntries = NativeBundleManifest.locked(target)
                    .entries()
                    .stream().toList();
            assertEquals(result.input().resolve("native-source.zip"), result.nativeSource());
            assertTrue(Files.isRegularFile(result.nativeSource(), LinkOption.NOFOLLOW_LINKS));
            try (ZipFile archive = new ZipFile(result.nativeSource().toFile())) {
                List<? extends ZipEntry> actualEntries =
                        archive.stream().filter(entry -> !entry.isDirectory()).toList();
                assertEquals(
                        expectedEntries.stream()
                                .map(NativeBundleEntry::resourcePath)
                                .toList(),
                        actualEntries.stream().map(ZipEntry::getName).toList());
                for (int index = 0; index < expectedEntries.size(); index++) {
                    NativeBundleEntry expected = expectedEntries.get(index);
                    ZipEntry actual = actualEntries.get(index);
                    assertEquals(ZipEntry.STORED, actual.getMethod());
                    assertEquals(expected.byteSize(), actual.getSize());
                    assertEquals(expected.byteSize(), actual.getCompressedSize());
                    try (InputStream input = archive.getInputStream(actual)) {
                        assertEquals(expected.sha256(), sha256(input));
                    }
                }
            }

            assertLegalInventory(result.input().resolve("legal"), target);
            assertInstallerLicense(result);
            NativeComponentInventory.verifyNativeSbom(
                    result.nativeSbom(), target, expectedVersion);
            String nativeSbom = Files.readString(result.nativeSbom());
            assertTrue(nativeSbom.contains("\"bomFormat\": \"CycloneDX\""));
            assertTrue(nativeSbom.contains("\"specVersion\": \"1.6\""));
            assertTrue(nativeSbom.contains(
                    "{\"name\": \"coffee-gb:native-target\", "
                            + "\"value\": \"" + target.id() + "\"}"));
            for (NativeComponentInventory.NativeSbomComponent component :
                    NativeComponentInventory.components(target)) {
                assertTrue(
                        component.id(),
                        nativeSbom.contains("\"name\": \"" + component.name() + "\""));
                assertTrue(
                        component.id(),
                        nativeSbom.contains(
                                "\"version\": \"" + component.version() + "\""));
            }
            String packageManifest = Files.readString(result.inventory());
            assertTrue(packageManifest.contains(
                    "native.sbom.sha256="
                            + NativePackageStager.sha256(result.nativeSbom())));
            assertTrue(packageManifest.contains(
                    "installer-license.sha256="
                            + NativePackageStager.sha256(result.installerLicense())));
            assertStageChecksums(result);
            assertNoForbiddenStageContent(result, app);
            assertTrue(Files.size(result.icon()) > 5_000);
            assertFalse(Files.exists(result.root().resolve("associations")));
            assertEquals(
                    "arguments=--debug\nwin-console=true\n",
                    Files.readString(result.windowsConsoleLauncher()));
            String inventory = Files.readString(result.inventory());
            assertTrue(inventory.contains("native.source-format=stored-zip\n"));
            assertFalse(inventory.contains("file-associations"));
            assertTrue(inventory.contains(
                    "native.source.sha256="
                            + NativePackageStager.sha256(result.nativeSource())
                            + "\n"));
            if (target == NativeTarget.LINUX_X86_64) {
                String desktopTemplate = Files.readString(
                        result.jpackageResources().resolve("Coffee GB.desktop"));
                assertTrue(desktopTemplate.contains("Exec=APPLICATION_LAUNCHER %f"));
                assertTrue(desktopTemplate.contains("Categories=Game;"));
                assertFalse(desktopTemplate.contains("MimeType="));
                assertFalse(desktopTemplate.contains("DESKTOP_MIMES"));
                assertFalse(desktopTemplate.contains("DEPLOY_BUNDLE_CATEGORY"));
            }
            assertEquals(DETERMINISTIC_TIME, Files.getLastModifiedTime(result.root()));
        }
    }

    @Test
    public void repeatedStageHasByteIdenticalTreeAndRefusesOverwrite() throws Exception {
        Path app = Path.of(required("coffeeGbAppJar"));
        Path universal = Path.of(required("coffeeGbUniversalJar"));
        Path sbom = Path.of(required("coffeeGbSbom"));
        Path resources = Path.of(required("coffeeGbPackagingResources"));
        Path base = temporaryFolder.getRoot().toPath();
        String lfSbom = Files.readString(sbom, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        Path lfSbomFile = base.resolve("maven-sbom-lf.json");
        Path crlfSbomFile = base.resolve("maven-sbom-crlf.json");
        Files.writeString(lfSbomFile, lfSbom, StandardCharsets.UTF_8);
        Files.writeString(
                crlfSbomFile, lfSbom.replace("\n", "\r\n"), StandardCharsets.UTF_8);
        NativePackageStager stager = new NativePackageStager();
        TimeZone originalTimeZone = TimeZone.getDefault();
        NativePackageStager.StageResult first;
        NativePackageStager.StageResult second;
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            first = stager.stage(new NativePackageStager.StageRequest(
                    NativeTarget.LINUX_X86_64,
                    app,
                    universal,
                    lfSbomFile,
                    resources,
                    base.resolve("first")));
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            second = stager.stage(new NativePackageStager.StageRequest(
                    NativeTarget.LINUX_X86_64,
                    app,
                    universal,
                    crlfSbomFile,
                    resources,
                    base.resolve("second")));
        } finally {
            TimeZone.setDefault(originalTimeZone);
        }

        assertEquals(treeDigests(first.root()), treeDigests(second.root()));
        assertThrows(
                java.nio.file.FileAlreadyExistsException.class,
                () -> stager.stage(new NativePackageStager.StageRequest(
                        NativeTarget.LINUX_X86_64,
                        app,
                        universal,
                        lfSbomFile,
                        resources,
                        first.root())));
    }

    private static void assertLegalInventory(Path legal, NativeTarget target) throws Exception {
        Set<String> expected = NativeComponentInventory.requiredLegalPaths(target);
        Set<String> actual;
        try (Stream<Path> paths = Files.walk(legal)) {
            actual = paths.filter(Files::isRegularFile)
                    .map(legal::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .collect(Collectors.toSet());
        }
        assertEquals(expected, actual);
        assertTrue(
                Files.readString(legal.resolve("LICENSE.txt"), StandardCharsets.UTF_8)
                        .contains(NativePackageMetadata.AUTHOR_NAME));
        assertTrue(
                Files.readString(legal.resolve("THIRD-PARTY-NOTICES.txt"))
                        .contains("https://github.com/trekawek/coffee-gb"));
        assertTrue(
                Files.readString(legal.resolve("licenses/Apache-Commons-NOTICE.txt"))
                        .contains("Apache Commons Codec"));
        for (NativeComponentInventory.NativeSbomComponent component :
                NativeComponentInventory.components(target)) {
            for (String legalFile : component.legalFiles()) {
                Path file = legal.resolve(legalFile).normalize();
                assertTrue(file.startsWith(legal));
                assertTrue(component.id(), Files.size(file) > 0);
            }
        }
    }

    private static void assertInstallerLicense(NativePackageStager.StageResult result)
            throws Exception {
        NativePackageMetadata.HostOs hostOs = result.target().hostOs();
        String manifest = Files.readString(result.inventory(), StandardCharsets.UTF_8);
        if (hostOs == NativePackageMetadata.HostOs.LINUX) {
            assertEquals(result.input().resolve("legal/LICENSE.txt"), result.installerLicense());
            assertTrue(manifest.contains("installer-license.format=utf-8-text\n"));
            assertTrue(
                    Files.readString(result.installerLicense(), StandardCharsets.UTF_8)
                            .contains(NativePackageMetadata.AUTHOR_NAME));
            return;
        }

        byte[] bytes = Files.readAllBytes(result.installerLicense());
        for (byte value : bytes) {
            assertTrue("Installer RTF must be ASCII", (value & 0x80) == 0);
        }
        String rtf = new String(bytes, StandardCharsets.US_ASCII);
        assertTrue(rtf.startsWith("{\\rtf1\\ansi\\ansicpg1252"));
        assertTrue(rtf.contains("Tomasz R\\u281?kawek"));
        assertFalse(rtf.contains("Tomasz Rekawek"));
        assertFalse(rtf.contains("ƒô"));
        assertTrue(manifest.contains("installer-license.format=ascii-rtf-unicode\n"));

        if (hostOs == NativePackageMetadata.HostOs.MACOS) {
            String template = Files.readString(
                    result.jpackageResources().resolve("Coffee GB-license.plist"),
                    StandardCharsets.US_ASCII);
            assertTrue(template.contains("<key>RTF </key>"));
            assertTrue(template.contains("<data>APPLICATION_LICENSE_TEXT</data>"));
        }
    }

    private static void assertStageChecksums(NativePackageStager.StageResult result)
            throws Exception {
        List<String> lines = Files.readAllLines(result.checksums(), StandardCharsets.UTF_8);
        assertFalse(lines.isEmpty());
        for (String line : lines) {
            assertTrue("Malformed checksum line: " + line, line.matches("[0-9a-f]{64}  .+"));
            Path file = result.root().resolve(line.substring(66)).normalize();
            assertTrue(file.startsWith(result.root()));
            assertTrue(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS));
            assertEquals(line.substring(0, 64), NativePackageStager.sha256(file));
            assertEquals(DETERMINISTIC_TIME, Files.getLastModifiedTime(file));
        }
        List<String> relativePaths =
                lines.stream().map(line -> line.substring(66)).toList();
        assertEquals(relativePaths.stream().sorted().toList(), relativePaths);
    }

    private static void assertNoForbiddenStageContent(
            NativePackageStager.StageResult result, Path sourceApp) throws Exception {
        String sourceRoot = sourceApp.toAbsolutePath().getParent().toString();
        try (Stream<Path> paths = Files.walk(result.root())) {
            List<Path> all = paths.toList();
            assertFalse(all.stream().anyMatch(Files::isSymbolicLink));
            assertFalse(all.stream().anyMatch(path -> {
                String lower = path.getFileName().toString().toLowerCase();
                return lower.endsWith(".gb")
                        || lower.endsWith(".gbc")
                        || lower.endsWith(".rom")
                        || lower.endsWith(".sgb");
            }));
            assertFalse(all.stream().anyMatch(path ->
                    Files.isDirectory(path)
                            && path.getFileName().toString().equals("runtime")));
            assertFalse(all.stream().anyMatch(path ->
                    path.getFileName().toString().matches("coffee-gb-.+\\.jar")
                            && !path.getFileName().toString().equals("coffee-gb.jar")));
            for (Path path : all) {
                if (!Files.isRegularFile(path)
                        || !(path.toString().endsWith(".txt")
                                || path.toString().endsWith(".properties")
                                || path.toString().endsWith(".json")
                                || path.toString().endsWith(".svg"))) {
                    continue;
                }
                String contents = Files.readString(path, StandardCharsets.UTF_8);
                assertFalse(path + " leaked a developer path", contents.contains(sourceRoot));
                assertFalse(path + " leaked a token name", contents.contains("GITHUB_TOKEN"));
                assertFalse(path + " leaked a secret name", contents.contains("CLIENT_SECRET"));
            }
        }
    }

    private static Map<String, String> treeDigests(Path root) throws Exception {
        Map<String, String> digests = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList()) {
                digests.put(
                        root.relativize(file).toString().replace('\\', '/'),
                        NativePackageStager.sha256(file));
            }
        }
        return digests;
    }

    private static String sha256(InputStream input) throws Exception {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        byte[] bytes = digest.digest();
        char[] encoded = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            encoded[index * 2] = Character.forDigit(value >>> 4, 16);
            encoded[index * 2 + 1] = Character.forDigit(value & 0x0f, 16);
        }
        return new String(encoded);
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing test property " + property);
        }
        return value;
    }
}
