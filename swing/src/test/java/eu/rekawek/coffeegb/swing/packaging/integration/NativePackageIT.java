package eu.rekawek.coffeegb.swing.packaging.integration;

import eu.rekawek.coffeegb.swing.packaging.NativeBundleEntry;
import eu.rekawek.coffeegb.swing.packaging.NativeBundleManifest;
import eu.rekawek.coffeegb.swing.packaging.NativeComponentInventory;
import eu.rekawek.coffeegb.swing.packaging.NativePackageStager;
import eu.rekawek.coffeegb.swing.packaging.NativePackageTool;
import eu.rekawek.coffeegb.swing.packaging.NativeTarget;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

            Set<String> expectedNatives = NativeBundleManifest.locked(target)
                    .entries()
                    .stream()
                    .map(NativeBundleEntry::resourcePath)
                    .collect(Collectors.toSet());
            Set<String> actualNatives;
            Path nativeRoot = result.input().resolve("native-source");
            try (Stream<Path> paths = Files.walk(nativeRoot)) {
                actualNatives = paths.filter(Files::isRegularFile)
                        .map(nativeRoot::relativize)
                        .map(path -> path.toString().replace('\\', '/'))
                        .collect(Collectors.toSet());
            }
            assertEquals(expectedNatives, actualNatives);

            assertLegalInventory(result.input().resolve("legal"), target);
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
            assertStageChecksums(result);
            assertReleaseInventory(result);
            assertNoForbiddenStageContent(result, app);
            assertTrue(Files.size(result.icon()) > 5_000);
            assertTrue(
                    Files.readString(result.association())
                            .contains("icon=input/coffee-gb." + result.target().iconSuffix()));
            assertEquals(
                    "arguments=--debug\nwin-console=true\n",
                    Files.readString(result.windowsConsoleLauncher()));
            if (target == NativeTarget.LINUX_X86_64) {
                String desktopTemplate = Files.readString(
                        result.jpackageResources().resolve("Coffee GB.desktop"));
                assertTrue(desktopTemplate.contains("Exec=APPLICATION_LAUNCHER %f"));
                assertTrue(desktopTemplate.contains("Categories=Game;"));
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
        NativePackageStager stager = new NativePackageStager();
        NativePackageStager.StageResult first = stager.stage(
                new NativePackageStager.StageRequest(
                        NativeTarget.LINUX_X86_64,
                        app,
                        universal,
                        sbom,
                        resources,
                        base.resolve("first")));
        NativePackageStager.StageResult second = stager.stage(
                new NativePackageStager.StageRequest(
                        NativeTarget.LINUX_X86_64,
                        app,
                        universal,
                        sbom,
                        resources,
                        base.resolve("second")));

        assertEquals(treeDigests(first.root()), treeDigests(second.root()));
        assertThrows(
                java.nio.file.FileAlreadyExistsException.class,
                () -> stager.stage(new NativePackageStager.StageRequest(
                        NativeTarget.LINUX_X86_64,
                        app,
                        universal,
                        sbom,
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

    private void assertReleaseInventory(NativePackageStager.StageResult result) throws Exception {
        Path release = Files.createDirectory(
                temporaryFolder.getRoot()
                        .toPath()
                        .resolve(result.target().nativeTarget().id() + "-release"));
        Files.writeString(release.resolve("package.bin"), "installer-placeholder\n");
        NativePackageTool.ReleaseMetadata metadata =
                NativePackageTool.finalizeReleaseMetadata(result, release);

        assertEquals(
                NativePackageStager.sha256(result.sbom()),
                NativePackageStager.sha256(metadata.mavenSbom()));
        assertEquals(
                NativePackageStager.sha256(result.nativeSbom()),
                NativePackageStager.sha256(metadata.nativeSbom()));
        assertEquals(
                "coffee-gb-"
                        + result.appVersion()
                        + "-"
                        + result.target().nativeTarget().id()
                        + "-native-sbom.cdx.json",
                metadata.nativeSbom().getFileName().toString());
        NativeComponentInventory.verifyNativeSbom(
                metadata.nativeSbom(),
                result.target().nativeTarget(),
                result.appVersion());

        List<String> checksums = Files.readAllLines(metadata.checksums(), StandardCharsets.UTF_8);
        Set<String> covered = checksums.stream()
                .map(line -> line.substring(66))
                .collect(Collectors.toSet());
        assertTrue(covered.contains("package.bin"));
        assertTrue(covered.contains(metadata.mavenSbom().getFileName().toString()));
        assertTrue(covered.contains(metadata.nativeSbom().getFileName().toString()));
        for (String line : checksums) {
            assertTrue("Malformed checksum line: " + line, line.matches("[0-9a-f]{64}  .+"));
            Path file = release.resolve(line.substring(66)).normalize();
            assertTrue(file.startsWith(release));
            assertEquals(line.substring(0, 64), NativePackageStager.sha256(file));
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

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing test property " + property);
        }
        return value;
    }
}
