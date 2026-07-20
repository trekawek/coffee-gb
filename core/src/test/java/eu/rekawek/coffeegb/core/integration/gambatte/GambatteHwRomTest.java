package eu.rekawek.coffeegb.core.integration.gambatte;

import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.integration.support.GambatteHwTestRunner;
import eu.rekawek.coffeegb.core.integration.support.ParallelParameterized;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.assertEquals;

/** Evaluates every canonical hexadecimal DMG/CGB verdict in Gambatte's HWTests archive. */
@RunWith(ParallelParameterized.class)
public class GambatteHwRomTest {

    private static final String ARCHIVE = "/roms/gambatte/gambatte-hwtests.zip";

    private static final String CURRENT_BASELINE =
            "/roms/gambatte/current-baseline.tsv";

    private static final int CURRENT_BASELINE_COUNT = 257;

    private static final int ARCHIVE_ROM_COUNT = 3_524;

    private static final int VERDICT_ROM_COUNT = 3_077;

    private static final int TEST_CASE_COUNT = 4_674;

    private static final int DMG_TEST_CASE_COUNT = 1_651;

    private static final int CGB_TEST_CASE_COUNT = 3_023;

    private static final Pattern SHARED_OUTPUT = outputPattern("dmg08_cgb04c_out");

    private static final Pattern DMG_OUTPUT = outputPattern("dmg08_out");

    private static final Pattern CGB_OUTPUT = outputPattern("cgb04c_out");

    private static final Pattern CGB_GENERIC_OUTPUT = outputPattern("_out");

    private final String name;

    private final byte[] rom;

    private final GameboyType gameboyType;

    private final String hardwareExpected;

    private final String guardedExpected;

    public GambatteHwRomTest(String name, byte[] rom, GameboyType gameboyType,
                             String hardwareExpected, String guardedExpected) {
        this.name = name;
        this.rom = rom;
        this.gameboyType = gameboyType;
        this.hardwareExpected = hardwareExpected;
        this.guardedExpected = guardedExpected;
    }

    @Parameterized.Parameters(name = "{0} [{2}]")
    public static Collection<Object[]> data() throws IOException {
        List<TestCase> allCases = discoverTestCases();
        Map<String, String> currentBaseline = readCurrentBaseline();
        validateCurrentBaseline(allCases, currentBaseline);
        Batch batch = Batch.fromSystemProperties();
        List<TestCase> selectedCases = new ArrayList<>();
        for (int ordinal = 0; ordinal < allCases.size(); ordinal++) {
            if (ordinal % batch.count() == batch.index()) {
                selectedCases.add(allCases.get(ordinal));
            }
        }
        if (selectedCases.isEmpty()) {
            throw new IOException("Gambatte batch selected no tests: " + batch);
        }

        Set<String> selectedPaths = new HashSet<>();
        selectedCases.forEach(test -> selectedPaths.add(test.rom()));
        Map<String, byte[]> roms = readSelectedRoms(selectedPaths);
        List<Object[]> parameters = new ArrayList<>(selectedCases.size());
        for (TestCase test : selectedCases) {
            byte[] bytes = roms.get(test.rom());
            if (bytes == null) {
                throw new IOException("Missing Gambatte HWTest ROM: " + test.rom());
            }
            parameters.add(new Object[]{test.rom(), bytes, test.gameboyType(), test.expected(),
                    currentBaseline.getOrDefault(test.key(), test.expected())});
        }
        return parameters;
    }

    private static List<TestCase> discoverTestCases() throws IOException {
        InputStream input = GambatteHwRomTest.class.getResourceAsStream(ARCHIVE);
        if (input == null) {
            throw new IOException("Missing Gambatte HWTest archive: " + ARCHIVE);
        }

        List<TestCase> cases = new ArrayList<>();
        Set<String> archivePaths = new HashSet<>();
        Set<String> verdictRoms = new HashSet<>();
        Set<String> caseKeys = new HashSet<>();
        int archiveRomCount = 0;
        try (input; ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String path = entry.getName();
                if (!archivePaths.add(path)) {
                    throw new IOException("Duplicate Gambatte archive path: " + path);
                }
                if (!entry.isDirectory() && isRom(path)) {
                    archiveRomCount++;
                    List<TestCase> parsed = parseTestCases(path);
                    if (!parsed.isEmpty()) {
                        verdictRoms.add(path);
                    }
                    for (TestCase test : parsed) {
                        String key = test.rom() + " [" + test.gameboyType() + "]";
                        if (!caseKeys.add(key)) {
                            throw new IOException("Duplicate Gambatte test case: " + key);
                        }
                        cases.add(test);
                    }
                }
                zip.closeEntry();
            }
        }

        cases.sort(Comparator.comparing(TestCase::rom)
                .thenComparing(test -> test.gameboyType().name()));
        long dmgCases = cases.stream()
                .filter(test -> test.gameboyType() == GameboyType.DMG).count();
        long cgbCases = cases.stream()
                .filter(test -> test.gameboyType() == GameboyType.CGB).count();
        if (archiveRomCount != ARCHIVE_ROM_COUNT
                || verdictRoms.size() != VERDICT_ROM_COUNT
                || cases.size() != TEST_CASE_COUNT
                || dmgCases != DMG_TEST_CASE_COUNT
                || cgbCases != CGB_TEST_CASE_COUNT) {
            throw new IOException(String.format(Locale.ROOT,
                    "Gambatte manifest changed: ROMs=%d/%d, verdict ROMs=%d/%d, "
                            + "cases=%d/%d, DMG=%d/%d, CGB=%d/%d",
                    archiveRomCount, ARCHIVE_ROM_COUNT,
                    verdictRoms.size(), VERDICT_ROM_COUNT,
                    cases.size(), TEST_CASE_COUNT,
                    dmgCases, DMG_TEST_CASE_COUNT,
                    cgbCases, CGB_TEST_CASE_COUNT));
        }
        return cases;
    }

    private static List<TestCase> parseTestCases(String path) {
        String base = path.substring(0, path.lastIndexOf('.'));
        Matcher shared = SHARED_OUTPUT.matcher(base);
        if (shared.find()) {
            String expected = shared.group(1).toUpperCase(Locale.ROOT);
            return List.of(dmg(path, expected), cgb(path, expected));
        }

        Matcher dmg = DMG_OUTPUT.matcher(base);
        if (dmg.find()) {
            List<TestCase> cases = new ArrayList<>(2);
            cases.add(dmg(path, dmg.group(1).toUpperCase(Locale.ROOT)));
            Matcher cgb = CGB_OUTPUT.matcher(base);
            if (cgb.find()) {
                cases.add(cgb(path, cgb.group(1).toUpperCase(Locale.ROOT)));
            }
            return cases;
        }

        Matcher cgb = CGB_GENERIC_OUTPUT.matcher(base);
        if (cgb.find()) {
            return List.of(cgb(path, cgb.group(1).toUpperCase(Locale.ROOT)));
        }
        return List.of();
    }

    private static Pattern outputPattern(String marker) {
        // The boundary is essential: outaudio1 is an audio oracle, not hex value A,
        // and xout/blank names deliberately do not provide a trusted tile verdict.
        return Pattern.compile(Pattern.quote(marker) + "([0-9A-Fa-f]+)(?=_|$)");
    }

    private static boolean isRom(String path) {
        return path.endsWith(".gb") || path.endsWith(".gbc");
    }

    private static Map<String, byte[]> readSelectedRoms(Set<String> selectedPaths)
            throws IOException {
        InputStream input = GambatteHwRomTest.class.getResourceAsStream(ARCHIVE);
        if (input == null) {
            throw new IOException("Missing Gambatte HWTest archive: " + ARCHIVE);
        }
        Map<String, byte[]> roms = new HashMap<>();
        try (input; ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (selectedPaths.contains(entry.getName())) {
                    if (roms.put(entry.getName(), zip.readAllBytes()) != null) {
                        throw new IOException("Duplicate selected Gambatte ROM: " + entry.getName());
                    }
                }
                zip.closeEntry();
            }
        }
        if (roms.size() != selectedPaths.size()) {
            Set<String> missing = new HashSet<>(selectedPaths);
            missing.removeAll(roms.keySet());
            throw new IOException("Selected Gambatte ROMs missing from archive: " + missing);
        }
        return roms;
    }

    private static Map<String, String> readCurrentBaseline() throws IOException {
        InputStream input = GambatteHwRomTest.class.getResourceAsStream(CURRENT_BASELINE);
        if (input == null) {
            throw new IOException("Missing Gambatte current baseline: " + CURRENT_BASELINE);
        }
        Map<String, String> baseline = new HashMap<>();
        try (input; BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\t", -1);
                if (parts.length != 2 || !parts[1].matches("[0-9A-F]+")) {
                    throw new IOException("Invalid Gambatte baseline line " + lineNumber
                            + ": " + line);
                }
                if (baseline.put(parts[0], parts[1]) != null) {
                    throw new IOException("Duplicate Gambatte baseline case: " + parts[0]);
                }
            }
        }
        if (baseline.size() != CURRENT_BASELINE_COUNT) {
            throw new IOException("Gambatte baseline changed: cases=" + baseline.size()
                    + "/" + CURRENT_BASELINE_COUNT);
        }
        return baseline;
    }

    private static void validateCurrentBaseline(List<TestCase> cases,
                                                Map<String, String> baseline)
            throws IOException {
        Map<String, TestCase> casesByKey = new HashMap<>();
        cases.forEach(test -> casesByKey.put(test.key(), test));
        for (Map.Entry<String, String> entry : baseline.entrySet()) {
            TestCase test = casesByKey.get(entry.getKey());
            if (test == null) {
                throw new IOException("Unknown Gambatte baseline case: " + entry.getKey());
            }
            if (entry.getValue().length() != test.expected().length()) {
                throw new IOException("Gambatte baseline has wrong output length for "
                        + entry.getKey() + ": " + entry.getValue());
            }
            if (entry.getValue().equals(test.expected())) {
                throw new IOException("Hardware-correct Gambatte case remains in baseline: "
                        + entry.getKey());
            }
        }
    }

    @Test(timeout = 10000)
    public void test() throws IOException {
        String actual = new GambatteHwTestRunner(rom, gameboyType)
                .runTest(hardwareExpected.length());
        assertEquals(name + " [" + gameboyType + "] hardware=" + hardwareExpected,
                guardedExpected, actual);
    }

    private static TestCase dmg(String rom, String expected) {
        return new TestCase(rom, GameboyType.DMG, expected);
    }

    private static TestCase cgb(String rom, String expected) {
        return new TestCase(rom, GameboyType.CGB, expected);
    }

    private record TestCase(String rom, GameboyType gameboyType, String expected) {

        private String key() {
            return rom + " [" + gameboyType + "]";
        }
    }

    private record Batch(int count, int index) {

        private static Batch fromSystemProperties() {
            String countValue = System.getProperty("gambatte.batchCount");
            String indexValue = System.getProperty("gambatte.batchIndex");
            if ((countValue == null) != (indexValue == null)) {
                throw new IllegalArgumentException(
                        "gambatte.batchCount and gambatte.batchIndex must be set together");
            }
            if (countValue == null) {
                return new Batch(1, 0);
            }
            int count = parseProperty("gambatte.batchCount", countValue);
            int index = parseProperty("gambatte.batchIndex", indexValue);
            if (count < 1) {
                throw new IllegalArgumentException(
                        "gambatte.batchCount must be >= 1: " + count);
            }
            if (count > TEST_CASE_COUNT) {
                throw new IllegalArgumentException("gambatte.batchCount must be <= "
                        + TEST_CASE_COUNT + ": " + count);
            }
            if (index < 0 || index >= count) {
                throw new IllegalArgumentException("gambatte.batchIndex must be in [0, "
                        + count + "): " + index);
            }
            return new Batch(count, index);
        }

        private static int parseProperty(String name, String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " must be an integer: " + value, e);
            }
        }
    }
}
