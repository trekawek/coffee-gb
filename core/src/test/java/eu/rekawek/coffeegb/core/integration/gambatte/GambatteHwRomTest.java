package eu.rekawek.coffeegb.core.integration.gambatte;

import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.integration.support.GambatteHwTestRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.io.InputStream;
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
@RunWith(Parameterized.class)
public class GambatteHwRomTest {

    private static final String ARCHIVE = "/roms/gambatte/gambatte-hwtests.zip";

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

    private final String expected;

    public GambatteHwRomTest(String name, byte[] rom, GameboyType gameboyType, String expected) {
        this.name = name;
        this.rom = rom;
        this.gameboyType = gameboyType;
        this.expected = expected;
    }

    @Parameterized.Parameters(name = "{0} [{2}]")
    public static Collection<Object[]> data() throws IOException {
        List<TestCase> allCases = discoverTestCases();
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
            parameters.add(new Object[]{test.rom(), bytes, test.gameboyType(), test.expected()});
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

    @Test(timeout = 10000)
    public void test() throws IOException {
        String actual = new GambatteHwTestRunner(rom, gameboyType).runTest(expected.length());
        assertEquals(name, expected, actual);
    }

    private static TestCase dmg(String rom, String expected) {
        return new TestCase(rom, GameboyType.DMG, expected);
    }

    private static TestCase cgb(String rom, String expected) {
        return new TestCase(rom, GameboyType.CGB, expected);
    }

    private record TestCase(String rom, GameboyType gameboyType, String expected) {
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
