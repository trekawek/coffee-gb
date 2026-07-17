package eu.rekawek.coffeegb.core.integration.gbmicrotest;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.integration.support.GbMicrotestRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.fail;

@RunWith(Parameterized.class)
public class GbMicrotestRomTest {

    private static final String ARCHIVE = "/roms/gbmicrotest/gbmicrotest-v7.zip";

    private static final String KNOWN_FAILURES_RESOURCE = "/roms/gbmicrotest/known-failures.txt";

    private static final Set<String> KNOWN_FAILURES = readKnownFailures();

    private static final long DMG_TICKS_PER_FRAME = 456L * 154L;

    private final String name;

    private final byte[] rom;

    public GbMicrotestRomTest(String name, byte[] rom) {
        this.name = name;
        this.rom = rom;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() throws IOException {
        InputStream input = GbMicrotestRomTest.class.getResourceAsStream(ARCHIVE);
        if (input == null) {
            throw new IOException("Missing GBMicrotest archive: " + ARCHIVE);
        }

        List<Object[]> parameters = new ArrayList<>();
        try (input; ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".gb")) {
                    String name = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                    parameters.add(new Object[]{name, zip.readAllBytes()});
                }
                zip.closeEntry();
            }
        }
        parameters.sort(Comparator.comparing(parameter -> (String) parameter[0]));

        Set<String> romNames = new HashSet<>();
        parameters.forEach(parameter -> romNames.add((String) parameter[0]));
        if (parameters.size() != 513) {
            throw new IOException("Expected 513 GBMicrotest ROMs, found " + parameters.size());
        }
        if (!romNames.containsAll(KNOWN_FAILURES)) {
            Set<String> missing = new HashSet<>(KNOWN_FAILURES);
            missing.removeAll(romNames);
            throw new IOException("GBMicrotest baseline names missing from archive: " + missing);
        }
        return parameters;
    }

    @Test(timeout = 10000)
    public void test() throws IOException {
        long ticks = name.equals("is_if_set_during_ime0.gb")
                ? Gameboy.TICKS_PER_SEC / 2L
                : 2L * DMG_TICKS_PER_FRAME;
        GbMicrotestRunner.TestResult result = new GbMicrotestRunner(rom).runTest(ticks);
        if (result.status() != 0x01 && !KNOWN_FAILURES.contains(name)) {
            fail(name + " regressed: " + result);
        }
    }

    private static Set<String> readKnownFailures() {
        InputStream input = GbMicrotestRomTest.class.getResourceAsStream(KNOWN_FAILURES_RESOURCE);
        if (input == null) {
            throw new ExceptionInInitializerError("Missing GBMicrotest baseline: " + KNOWN_FAILURES_RESOURCE);
        }
        try (input;
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            Set<String> names = new HashSet<>();
            reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .forEach(names::add);
            return Set.copyOf(names);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
