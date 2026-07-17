package eu.rekawek.coffeegb.core.integration.gbchw;

import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.integration.support.GbcHwTestRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.fail;

/** Compares the automated gbc-hw-tests ROMs with SRAM captured on original hardware. */
@RunWith(Parameterized.class)
public class GbcHwRomTest {

    private static final String ARCHIVE = "/roms/gbc-hw-tests/gbc-hw-tests-631e600.zip";

    private static final String KNOWN_FAILURES_RESOURCE = "/roms/gbc-hw-tests/known-failures.txt";

    private static final Set<String> KNOWN_FAILURES = readKnownFailures();

    private static final long MAX_TICKS = 5_000_000L;

    private final String name;

    private final GameboyType gameboyType;

    private final byte[] rom;

    private final byte[] expected;

    public GbcHwRomTest(String name, GameboyType gameboyType, byte[] rom, byte[] expected) {
        this.name = name;
        this.gameboyType = gameboyType;
        this.rom = rom;
        this.expected = expected;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() throws IOException {
        Map<String, byte[]> entries = readArchive();
        List<Object[]> parameters = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String path = entry.getKey();
            if (!path.endsWith(".gb") && !path.endsWith(".gbc")) {
                continue;
            }
            int separator = path.lastIndexOf('/');
            String directory = separator < 0 ? "" : path.substring(0, separator + 1);
            addReference(parameters, entries, path, directory + "real_gb.sav",
                    GameboyType.DMG, entry.getValue());
            addReference(parameters, entries, path, directory + "real_gbc.sav",
                    GameboyType.CGB, entry.getValue());
        }
        parameters.sort(Comparator.comparing(parameter -> (String) parameter[0]));

        Set<String> names = new HashSet<>();
        for (Object[] parameter : parameters) {
            names.add((String) parameter[0]);
        }
        if (parameters.size() != 221) {
            throw new IOException("Expected 221 gbc-hw-tests hardware comparisons, found "
                    + parameters.size());
        }
        if (!names.containsAll(KNOWN_FAILURES)) {
            Set<String> missing = new HashSet<>(KNOWN_FAILURES);
            missing.removeAll(names);
            throw new IOException("gbc-hw-tests baseline names missing from archive: " + missing);
        }
        return parameters;
    }

    private static void addReference(List<Object[]> parameters, Map<String, byte[]> entries,
                                     String romPath, String referencePath, GameboyType type, byte[] rom) {
        byte[] reference = entries.get(referencePath);
        int magicOffset = findLastMagic(reference);
        if (magicOffset < 0) {
            return;
        }
        String name = romPath + " [" + type + "]";
        parameters.add(new Object[]{name, type, rom,
                Arrays.copyOf(reference, magicOffset + GbcHwTestRunner.RESULT_MAGIC.length)});
    }

    private static int findLastMagic(byte[] data) {
        if (data == null) {
            return -1;
        }
        int result = -1;
        int limit = Math.min(data.length, 0x2000);
        for (int offset = 0; offset <= limit - GbcHwTestRunner.RESULT_MAGIC.length; offset++) {
            boolean matches = true;
            for (int i = 0; i < GbcHwTestRunner.RESULT_MAGIC.length; i++) {
                if (data[offset + i] != GbcHwTestRunner.RESULT_MAGIC[i]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                result = offset;
            }
        }
        return result;
    }

    private static Map<String, byte[]> readArchive() throws IOException {
        InputStream input = GbcHwRomTest.class.getResourceAsStream(ARCHIVE);
        if (input == null) {
            throw new IOException("Missing gbc-hw-tests archive: " + ARCHIVE);
        }
        Map<String, byte[]> entries = new HashMap<>();
        try (input; ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    entries.put(entry.getName(), zip.readAllBytes());
                }
            }
        }
        return entries;
    }

    @Test(timeout = 10000)
    public void test() throws IOException {
        GbcHwTestRunner.TestResult result =
                new GbcHwTestRunner(rom, gameboyType).runTest(expected.length, MAX_TICKS);
        if (!result.completed()) {
            if (!KNOWN_FAILURES.contains(name)) {
                fail(name + " did not write its complete SRAM result: " + result.cpuState());
            }
            return;
        }
        int mismatch = firstMismatch(expected, result.actual());
        if (mismatch >= 0 && !KNOWN_FAILURES.contains(name)) {
            fail(String.format("%s differs at SRAM+$%04x: expected $%02x, actual $%02x",
                    name, mismatch, expected[mismatch] & 0xff, result.actual()[mismatch] & 0xff));
        }
    }

    private static int firstMismatch(byte[] expected, byte[] actual) {
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                return i;
            }
        }
        return -1;
    }

    private static Set<String> readKnownFailures() {
        InputStream input = GbcHwRomTest.class.getResourceAsStream(KNOWN_FAILURES_RESOURCE);
        if (input == null) {
            throw new ExceptionInInitializerError("Missing gbc-hw-tests baseline: " + KNOWN_FAILURES_RESOURCE);
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
