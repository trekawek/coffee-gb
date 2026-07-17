package eu.rekawek.coffeegb.core.integration.miscgb;

import eu.rekawek.coffeegb.core.integration.support.MooneyeTestRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Executes every ROM in alyosha-tas/Misc.-GB-Tests through its Mooneye protocol. */
@RunWith(Parameterized.class)
public class MiscGbRomTest {

    private static final String ARCHIVE = "/roms/misc-gb-tests/misc-gb-tests-be3cbb8.zip";

    private static final String KNOWN_FAILURES_RESOURCE =
            "/roms/misc-gb-tests/known-failures.txt";

    private static final Set<String> KNOWN_FAILURES = readKnownFailures();

    private static final long MAX_TICKS = 40_000_000L;

    private static final long KNOWN_FAILURE_TICKS = 1_000_000L;

    private final String name;

    private final byte[] rom;

    public MiscGbRomTest(String name, byte[] rom) {
        this.name = name;
        this.rom = rom;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() throws IOException {
        InputStream input = MiscGbRomTest.class.getResourceAsStream(ARCHIVE);
        if (input == null) {
            throw new IOException("Missing Misc.-GB-Tests archive: " + ARCHIVE);
        }
        List<Object[]> parameters = new ArrayList<>();
        try (input; ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".gb")) {
                    parameters.add(new Object[]{entry.getName(), zip.readAllBytes()});
                }
            }
        }
        parameters.sort(Comparator.comparing(parameter -> (String) parameter[0]));

        Set<String> names = new HashSet<>();
        parameters.forEach(parameter -> names.add((String) parameter[0]));
        if (parameters.size() != 17) {
            throw new IOException("Expected 17 Misc.-GB-Tests ROMs, found " + parameters.size());
        }
        if (!names.containsAll(KNOWN_FAILURES)) {
            Set<String> missing = new HashSet<>(KNOWN_FAILURES);
            missing.removeAll(names);
            throw new IOException("Misc.-GB-Tests baseline names missing from archive: " + missing);
        }
        return parameters;
    }

    @Test(timeout = 10000)
    public void test() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MooneyeTestRunner runner = new MooneyeTestRunner(rom, name, output);
        Boolean result = runner.runTest(KNOWN_FAILURES.contains(name)
                ? KNOWN_FAILURE_TICKS : MAX_TICKS);
        if (KNOWN_FAILURES.contains(name)) {
            return;
        }
        assertNotNull(name + " did not reach its test breakpoint: " + runner.dumpRegs()
                + ", output=" + output, result);
        assertTrue(name + " failed: " + runner.dumpRegs() + ", output=" + output, result);
    }

    private static Set<String> readKnownFailures() {
        InputStream input = MiscGbRomTest.class.getResourceAsStream(KNOWN_FAILURES_RESOURCE);
        if (input == null) {
            throw new ExceptionInInitializerError(
                    "Missing Misc.-GB-Tests baseline: " + KNOWN_FAILURES_RESOURCE);
        }
        try (input;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(input, StandardCharsets.UTF_8))) {
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
