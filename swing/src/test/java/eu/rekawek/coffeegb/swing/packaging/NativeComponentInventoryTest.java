package eu.rekawek.coffeegb.swing.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class NativeComponentInventoryTest {

    private static final Set<String> COMMON = Set.of(
            "jna-dispatch",
            "opencv-native",
            "opencv",
            "ittnotify",
            "protobuf",
            "libjpeg-turbo",
            "libwebp",
            "libpng",
            "libtiff",
            "openjpeg",
            "openexr",
            "zlib",
            "flatbuffers",
            "softfloat",
            "mscr-chi-table");

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void targetComponentSetsMatchEmbeddedOpenCvBuildMetadata() {
        assertComponents(
                NativeTarget.LINUX_X86_64,
                Set.of("sdl2", "intel-ipp", "intel-ipp-iw", "opencl-headers"));
        assertComponents(
                NativeTarget.WINDOWS_X86_64,
                Set.of(
                        "sdl2",
                        "intel-ipp",
                        "intel-ipp-iw",
                        "opencl-headers",
                        "ade",
                        "vasot"));
        assertComponents(
                NativeTarget.MACOS_X86_64,
                Set.of("intel-ipp", "intel-ipp-iw"));
        assertComponents(
                NativeTarget.MACOS_AARCH64,
                Set.of("nvidia-carotene", "nvidia-tegra-hal"));

        Map<NativeTarget, String> ippVersions = Map.of(
                NativeTarget.LINUX_X86_64,
                "2021.10.0",
                NativeTarget.WINDOWS_X86_64,
                "2021.11.0",
                NativeTarget.MACOS_X86_64,
                "2021.9.1");
        for (Map.Entry<NativeTarget, String> expected : ippVersions.entrySet()) {
            assertEquals(
                    expected.getValue(),
                    component(expected.getKey(), "intel-ipp").version());
            assertEquals(
                    expected.getValue(),
                    component(expected.getKey(), "intel-ipp-iw").version());
        }
        assertEquals(
                "0.0.1",
                component(NativeTarget.MACOS_AARCH64, "nvidia-carotene").version());
        assertFalse(ids(NativeTarget.MACOS_AARCH64).contains("intel-ipp"));
        assertFalse(ids(NativeTarget.MACOS_X86_64).contains("opencl-headers"));
        assertFalse(ids(NativeTarget.MACOS_AARCH64).contains("opencl-headers"));
    }

    @Test
    public void nativeBomIsDeterministicTargetedAndStrictlyVerifiable() throws Exception {
        Path first = temporaryFolder.getRoot().toPath().resolve("first.json");
        Path second = temporaryFolder.getRoot().toPath().resolve("second.json");
        NativeComponentInventory.writeNativeSbom(
                NativeTarget.WINDOWS_X86_64, "1.7.15-SNAPSHOT", first);
        NativeComponentInventory.writeNativeSbom(
                NativeTarget.WINDOWS_X86_64, "1.7.15-SNAPSHOT", second);

        assertEquals(Files.readAllBytes(first).length, Files.size(first));
        assertEquals(
                Files.readString(first, StandardCharsets.UTF_8),
                Files.readString(second, StandardCharsets.UTF_8));
        String json = Files.readString(first, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"bomFormat\": \"CycloneDX\""));
        assertTrue(json.contains("\"specVersion\": \"1.6\""));
        assertTrue(json.contains(
                "{\"name\": \"coffee-gb:native-target\", "
                        + "\"value\": \"windows-x86-64\"}"));
        assertTrue(json.contains("\"name\": \"ADE\""));
        assertTrue(json.contains("\"version\": \"2021.11.0\""));
        assertFalse(json.contains("nvidia-carotene"));
        assertFalse(json.contains("SYSTEM_LIBRARY_REQUIRED"));
        NativeComponentInventory.verifyNativeSbom(
                first, NativeTarget.WINDOWS_X86_64, "1.7.15-SNAPSHOT");
        assertThrows(
                java.io.IOException.class,
                () -> NativeComponentInventory.verifyNativeSbom(
                        first, NativeTarget.LINUX_X86_64, "1.7.15-SNAPSHOT"));

        Files.writeString(first, "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        assertThrows(
                java.io.IOException.class,
                () -> NativeComponentInventory.verifyNativeSbom(
                        first, NativeTarget.WINDOWS_X86_64, "1.7.15-SNAPSHOT"));
    }

    @Test
    public void everyComponentReferencesFullPackagedLegalText() {
        Set<String> allPaths = NativeComponentInventory.allLegalPaths();
        assertTrue(allPaths.contains("THIRD-PARTY-NOTICES.txt"));
        assertTrue(allPaths.contains("licenses/OpenCV-COPYRIGHT.txt"));
        assertTrue(allPaths.contains("licenses/OpenCV-libwebp-COPYING.txt"));
        assertTrue(allPaths.contains("licenses/OpenCV-OpenCL-Headers-LICENSE.txt"));
        assertTrue(allPaths.contains("licenses/OpenCV-NVIDIA-Carotene-BSD-3-Clause.txt"));
        assertTrue(allPaths.contains("licenses/Intel-IPP-EULA-October-2022.txt"));
        for (NativeTarget target : NativeTarget.values()) {
            Set<String> targetPaths = NativeComponentInventory.requiredLegalPaths(target);
            for (NativeComponentInventory.NativeSbomComponent component :
                    NativeComponentInventory.components(target)) {
                assertFalse(component.legalFiles().isEmpty());
                assertTrue(targetPaths.containsAll(component.legalFiles()));
            }
        }
    }

    @Test
    public void repositoryLegalTreeExactlyMatchesLockedInventory() throws Exception {
        Path legalRoot = Path.of(System.getProperty("basedir"))
                .resolve("../packaging/resources/legal")
                .normalize();
        Set<String> actual;
        try (var files = Files.walk(legalRoot)) {
            actual = files.filter(Files::isRegularFile)
                    .map(legalRoot::relativize)
                    .map(Path::toString)
                    .map(path -> path.replace('\\', '/'))
                    .collect(Collectors.toSet());
        }
        assertEquals(NativeComponentInventory.allLegalPaths(), actual);
    }

    private static void assertComponents(NativeTarget target, Set<String> targetOnly) {
        Set<String> expected = new java.util.LinkedHashSet<>(COMMON);
        expected.addAll(targetOnly);
        assertEquals(expected, ids(target));
    }

    private static Set<String> ids(NativeTarget target) {
        return NativeComponentInventory.components(target).stream()
                .map(NativeComponentInventory.NativeSbomComponent::id)
                .collect(Collectors.toSet());
    }

    private static NativeComponentInventory.NativeSbomComponent component(
            NativeTarget target, String id) {
        return NativeComponentInventory.components(target).stream()
                .filter(component -> component.id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}
