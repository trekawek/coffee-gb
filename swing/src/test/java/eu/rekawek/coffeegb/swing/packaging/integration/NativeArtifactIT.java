package eu.rekawek.coffeegb.swing.packaging.integration;

import eu.rekawek.coffeegb.swing.packaging.NativeArtifactPolicy;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Package-phase inventory and launch smoke for both Maven desktop artifacts. */
public class NativeArtifactIT {

    private static final Set<String> HOST_STABLE_MAVEN_AND_CORE_TEXT = Set.of(
            "META-INF/maven/eu.rekawek.coffeegb/core/pom.xml",
            "META-INF/maven/eu.rekawek.coffeegb/core/pom.properties",
            "META-INF/maven/eu.rekawek.coffeegb/controller/pom.xml",
            "META-INF/maven/eu.rekawek.coffeegb/controller/pom.properties",
            "cheats/SOURCE.md",
            "simplelogger.properties");

    @Test
    public void appIsNativeFreeAndUniversalJarRemainsRunnable() throws Exception {
        Path universal = Path.of(required("coffeeGbUniversalJar"));
        Path app = Path.of(required("coffeeGbAppJar"));
        String expectedVersion = required("coffeeGbExpectedVersion");

        Set<String> universalEntries;
        Set<String> appEntries;
        Set<Long> universalTimestamps;
        Set<Long> appTimestamps;
        try (JarFile universalJar = new JarFile(universal.toFile());
                JarFile appJar = new JarFile(app.toFile())) {
            universalEntries = entries(universalJar);
            appEntries = entries(appJar);
            universalTimestamps = timestamps(universalJar);
            appTimestamps = timestamps(appJar);
            assertEquals(universalJar.size(), universalEntries.size());
            assertEquals(appJar.size(), appEntries.size());
            assertEquals(54, nativeEntries(universalJar).size());
            assertEquals(List.of(), nativeEntries(appJar));
            assertEquals(
                    "eu.rekawek.coffeegb.swing.MainKt",
                    mainAttribute(universalJar, Attributes.Name.MAIN_CLASS));
            assertEquals(
                    mainAttribute(universalJar, Attributes.Name.MAIN_CLASS),
                    mainAttribute(appJar, Attributes.Name.MAIN_CLASS));
            assertEquals(
                    expectedVersion,
                    mainAttribute(universalJar, Attributes.Name.IMPLEMENTATION_VERSION));
            assertEquals(
                    expectedVersion,
                    mainAttribute(appJar, Attributes.Name.IMPLEMENTATION_VERSION));
            assertHostStableTextEntries(appJar);
        }
        assertTrue(universalEntries.containsAll(appEntries));
        assertTrue(appEntries.contains("META-INF/services/org.slf4j.spi.SLF4JServiceProvider"));
        assertTrue(appEntries.contains("META-INF/services/org/jline/terminal/provider/exec"));
        assertTrue(appEntries.contains("META-INF/services/org/jline/terminal/provider/jna"));
        assertTrue(appEntries.contains("eu/rekawek/coffeegb/swing/coffee-gb.png"));
        assertEquals(1, universalTimestamps.size());
        assertEquals(universalTimestamps, appTimestamps);
        Set<String> removed = new HashSet<>(universalEntries);
        removed.removeAll(appEntries);
        assertTrue(removed.remove("META-INF/maven/eu.rekawek.coffeegb/swing/"));
        assertTrue(removed.remove("META-INF/maven/eu.rekawek.coffeegb/swing/pom.properties"));
        assertTrue(removed.remove("META-INF/maven/eu.rekawek.coffeegb/swing/pom.xml"));
        assertTrue(removed.remove("module-info.class"));
        assertTrue(removed.remove("META-INF/versions/9/module-info.class"));
        assertEquals(54, removed.size());
        assertTrue(removed.stream().allMatch(NativeArtifactPolicy::isNativeResource));

        assertVersionLaunch(universal, expectedVersion);
        assertVersionLaunch(app, expectedVersion);
    }

    private static void assertVersionLaunch(Path jar, String expectedVersion) throws Exception {
        String executable = Path.of(
                        System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toString();
        Process process = new ProcessBuilder(executable, "-jar", jar.toString(), "--version")
                .redirectErrorStream(true)
                .start();
        assertTrue("version process timed out", process.waitFor(20, TimeUnit.SECONDS));
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 0, process.exitValue());
        assertEquals("Coffee GB " + expectedVersion, output.strip());
    }

    private static Set<String> entries(JarFile jar) {
        Set<String> entries = new HashSet<>();
        jar.stream().forEach(entry -> entries.add(entry.getName()));
        return entries;
    }

    private static Set<Long> timestamps(JarFile jar) {
        Set<Long> timestamps = new HashSet<>();
        jar.stream().forEach(entry -> timestamps.add(entry.getTime()));
        return timestamps;
    }

    private static List<String> nativeEntries(JarFile jar) {
        List<String> natives = new ArrayList<>();
        jar.stream()
                .map(entry -> entry.getName())
                .filter(NativeArtifactPolicy::isNativeResource)
                .sorted()
                .forEach(natives::add);
        return natives;
    }

    private static String mainAttribute(JarFile jar, Attributes.Name name) throws IOException {
        return jar.getManifest().getMainAttributes().getValue(name);
    }

    private static void assertHostStableTextEntries(JarFile jar) throws IOException {
        List<String> entries = jar.stream()
                .filter(entry -> !entry.isDirectory())
                .map(entry -> entry.getName())
                .filter(name -> name.startsWith("META-INF/coffee-gb/legal/")
                        || HOST_STABLE_MAVEN_AND_CORE_TEXT.contains(name))
                .sorted()
                .toList();
        assertEquals(44, entries.size());
        for (String entry : entries) {
            byte[] contents;
            try (var input = jar.getInputStream(jar.getJarEntry(entry))) {
                contents = input.readAllBytes();
            }
            assertFalse(entry, new String(contents, StandardCharsets.UTF_8).contains("\r"));
        }
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing test property " + property);
        }
        return value;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
