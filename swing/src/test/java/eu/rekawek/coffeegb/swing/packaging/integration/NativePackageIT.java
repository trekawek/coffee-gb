package eu.rekawek.coffeegb.swing.packaging.integration;

import eu.rekawek.coffeegb.swing.packaging.NativeBundleEntry;
import eu.rekawek.coffeegb.swing.packaging.NativeBundleManifest;
import eu.rekawek.coffeegb.swing.packaging.NativePackageStager;
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

            assertLegalInventory(result.input().resolve("legal"));
            assertStageChecksums(result);
            assertNoForbiddenStageContent(result, app);
            assertTrue(Files.size(result.icon()) > 5_000);
            assertTrue(
                    Files.readString(result.association())
                            .contains("icon=input/coffee-gb." + result.target().iconSuffix()));
            if (target == NativeTarget.LINUX_X86_64) {
                String desktopTemplate = Files.readString(
                        result.jpackageResources().resolve("Coffee GB.desktop"));
                assertTrue(desktopTemplate.contains("Exec=APPLICATION_LAUNCHER %f"));
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

    private static void assertLegalInventory(Path legal) throws Exception {
        Set<String> expected = Set.of(
                "LICENSE.txt",
                "THIRD-PARTY-NOTICES.txt",
                "licenses/Apache-2.0.txt",
                "licenses/JLine-BSD-3-Clause.txt",
                "licenses/JNA-DUAL-LICENSE.txt",
                "licenses/LGPL-2.1.txt",
                "licenses/OpenCV-BSD-3-Clause.txt",
                "licenses/SDL2-zlib.txt");
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
