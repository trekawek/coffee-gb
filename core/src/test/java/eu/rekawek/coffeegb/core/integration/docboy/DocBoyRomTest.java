package eu.rekawek.coffeegb.core.integration.docboy;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.integration.support.DocBoyTestRunner;
import eu.rekawek.coffeegb.core.integration.support.ParallelParameterized;
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
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Executes the strict, machine-readable subset of Docheinstein/docboy-test-suite. */
@RunWith(ParallelParameterized.class)
public class DocBoyRomTest {

    private static final String ARCHIVE = "/roms/docboy/docboy-e417e4e7.zip";

    private static final Map<String, Integer> EXPECTED_VARIANT_COUNTS = Map.of(
            "dmg/", 65,
            "cgb/", 63,
            "cgb_dmg_mode/", 34);

    private static final long MAX_TICKS = Gameboy.TICKS_PER_SEC / 2L;

    private final String name;

    private final byte[] rom;

    private final GameboyType gameboyType;

    public DocBoyRomTest(String name, byte[] rom, GameboyType gameboyType) {
        this.name = name;
        this.rom = rom;
        this.gameboyType = gameboyType;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() throws IOException {
        InputStream input = DocBoyRomTest.class.getResourceAsStream(ARCHIVE);
        if (input == null) {
            throw new IOException("Missing DocBoy archive: " + ARCHIVE);
        }

        List<Object[]> parameters = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        Map<String, Integer> variantCounts = new HashMap<>();
        try (input; ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    String path = entry.getName();
                    if (!paths.add(path)) {
                        throw new IOException("Duplicate DocBoy archive path: " + path);
                    }
                    String variant = variant(path);
                    byte[] rom = zip.readAllBytes();
                    if (!usesHramVerdictProtocol(rom)) {
                        throw new IOException("DocBoy ROM does not contain the FFF0 verdict writes: "
                                + path);
                    }
                    variantCounts.merge(variant, 1, Integer::sum);
                    GameboyType type = variant.equals("dmg/")
                            ? GameboyType.DMG : GameboyType.CGB;
                    parameters.add(new Object[]{path, rom, type});
                }
                zip.closeEntry();
            }
        }
        parameters.sort(Comparator.comparing(parameter -> (String) parameter[0]));

        if (!variantCounts.equals(EXPECTED_VARIANT_COUNTS)) {
            throw new IOException("Unexpected DocBoy variant counts: " + variantCounts
                    + ", expected " + EXPECTED_VARIANT_COUNTS);
        }
        return parameters;
    }

    private static String variant(String path) throws IOException {
        for (String variant : EXPECTED_VARIANT_COUNTS.keySet()) {
            if (path.startsWith(variant)) {
                String extension = variant.equals("cgb/") ? ".gbc" : ".gb";
                if (!path.endsWith(extension)) {
                    throw new IOException("Unexpected DocBoy ROM extension: " + path);
                }
                return variant;
            }
        }
        throw new IOException("Unexpected DocBoy archive path: " + path);
    }

    /** The suite's common success/failure handlers store {@code $01}/{@code $02} at FFF0. */
    private static boolean usesHramVerdictProtocol(byte[] rom) {
        boolean success = false;
        boolean failure = false;
        for (int i = 0; i + 4 < rom.length; i++) {
            if ((rom[i] & 0xff) == 0x21
                    && (rom[i + 1] & 0xff) == 0xf0
                    && (rom[i + 2] & 0xff) == 0xff
                    && (rom[i + 3] & 0xff) == 0x36) {
                success |= (rom[i + 4] & 0xff) == 0x01;
                failure |= (rom[i + 4] & 0xff) == 0x02;
            }
        }
        return success && failure;
    }

    @Test(timeout = 10_000)
    public void test() throws IOException {
        DocBoyTestRunner.TestResult result = new DocBoyTestRunner(
                rom, gameboyType, Gameboy.BootstrapMode.SKIP).runTest(MAX_TICKS);
        assertTrue(name + " did not produce a terminal verdict: " + result,
                result.status() == 0x01 || result.status() == 0x02);
        assertEquals(name + " failed: " + result, 0x01, result.status());
    }
}
