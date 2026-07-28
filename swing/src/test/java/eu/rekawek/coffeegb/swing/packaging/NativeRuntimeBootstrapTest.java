package eu.rekawek.coffeegb.swing.packaging;

import org.junit.After;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class NativeRuntimeBootstrapTest {

    private final Map<String, String> previous = new java.util.HashMap<>();

    @After
    public void restoreProperties() {
        for (String property : previous.keySet()) {
            String value = previous.get(property);
            if (value == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, value);
            }
        }
    }

    @Test
    public void verifiedBundleConfiguresExactExternalPaths() {
        remember("jna.boot.library.path");
        remember(NativeRuntimeBootstrap.LIBRARY_DIRECTORY_PROPERTY);
        remember(NativeRuntimeBootstrap.OPENCV_LIBRARY_PROPERTY);
        Path root = Path.of("target", "native-test").toAbsolutePath();
        EnumMap<NativeComponent, Path> libraries = new EnumMap<>(NativeComponent.class);
        libraries.put(NativeComponent.JNA_DISPATCH, root.resolve("lib/libjnidispatch.so"));
        libraries.put(NativeComponent.OPENCV, root.resolve("lib/libopencv_java490.so"));
        NativeRuntimeBundle bundle = new NativeRuntimeBundle(
                NativeTarget.LINUX_X86_64,
                root,
                libraries,
                GamepadNativeSupport.SYSTEM_LIBRARY_REQUIRED);

        NativeRuntimeBootstrap.configure(bundle);

        assertEquals(
                root.resolve("lib").toString(),
                System.getProperty("jna.boot.library.path"));
        assertEquals(
                root.resolve("lib").toString(),
                System.getProperty(NativeRuntimeBootstrap.LIBRARY_DIRECTORY_PROPERTY));
        assertEquals(
                root.resolve("lib/libopencv_java490.so").toString(),
                System.getProperty(NativeRuntimeBootstrap.OPENCV_LIBRARY_PROPERTY));
    }

    @Test
    public void explicitTargetRequiresAnExternalResourceSource() {
        Properties properties = new Properties();
        properties.setProperty(
                NativeRuntimeBootstrap.TARGET_PROPERTY,
                NativeTarget.LINUX_X86_64.id());
        Path cache = Path.of("target", "must-not-be-created");

        NativeRuntimeSelection selection = NativeRuntimeBootstrap.bootstrapFromProperties(
                properties, cache, getClass().getClassLoader());

        NativeRuntimeSelection.Portable portable =
                (NativeRuntimeSelection.Portable) selection;
        assertEquals(
                new NativeBundleFailure.NativeSourceNotConfigured(
                        NativeTarget.LINUX_X86_64),
                portable.fallbackCause().orElseThrow());
        assertFalse(java.nio.file.Files.exists(cache));
        assertThrows(
                IllegalStateException.class,
                () -> NativeRuntimeBootstrap.requirePackageSmokeSelection(
                        selection, NativeTarget.LINUX_X86_64.id()));
    }

    @Test
    public void packageSmokeRejectsCorruptAndMismatchedTargetSources() throws Exception {
        Path directory = Files.createTempDirectory("native-package-smoke-policy");
        try {
            Path corrupt = Files.writeString(directory.resolve("native-source.zip"), "not a zip");
            Properties properties = new Properties();
            properties.setProperty(
                    NativeRuntimeBootstrap.TARGET_PROPERTY,
                    NativeTarget.LINUX_X86_64.id());
            properties.setProperty(
                    NativeRuntimeBootstrap.SOURCE_PROPERTY,
                    corrupt.toString());
            NativeRuntimeSelection corruptSelection =
                    NativeRuntimeBootstrap.bootstrapFromProperties(
                            properties,
                            directory.resolve("cache"),
                            getClass().getClassLoader());
            assertThrows(
                    IllegalStateException.class,
                    () -> NativeRuntimeBootstrap.requirePackageSmokeSelection(
                            corruptSelection, NativeTarget.LINUX_X86_64.id()));

            Path root = directory.resolve("selected-windows");
            NativeRuntimeBundle wrongBundle = new NativeRuntimeBundle(
                    NativeTarget.WINDOWS_X86_64,
                    root,
                    new EnumMap<>(NativeComponent.class),
                    GamepadNativeSupport.BUNDLED);
            NativeRuntimeSelection mismatched =
                    new NativeRuntimeSelection.TargetBundle(wrongBundle);
            assertThrows(
                    IllegalStateException.class,
                    () -> NativeRuntimeBootstrap.requirePackageSmokeSelection(
                            mismatched, NativeTarget.LINUX_X86_64.id()));

            assertEquals(
                    "portable",
                    NativeRuntimeBootstrap.requirePackageSmokeSelection(
                            NativeRuntimeSelection.Portable.normal(), null));
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (java.io.IOException failure) {
                                throw new java.io.UncheckedIOException(failure);
                            }
                        });
            }
        }
    }

    private void remember(String property) {
        previous.put(property, System.getProperty(property));
    }
}
