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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.assertEquals;

@RunWith(Parameterized.class)
public class GambatteHwRomTest {

    private static final String ARCHIVE = "/roms/gambatte/gambatte-hwtests.zip";

    private static final List<TestCase> TEST_CASES = List.of(
            dmg("hwtests/display_startstate/ly_dmg08_out00_cgb04c_out90.gbc", "00"),
            cgb("hwtests/cgbpal_m3/cgbpal_m3start_2_cgb04c_out0.gbc", "0"),
            cgb("hwtests/tima/tc00_start_1_cgb04c_outF0.gbc", "F0"),
            cgb("hwtests/dma/hdma_ei_m3halt_m0unhalt_ly_1_cgb04c_out02.gbc", "02"),
            dmg("hwtests/oam_access/postread_2_dmg08_cgb04c_out0.gbc", "0"),
            cgb("hwtests/oam_access/postread_2_dmg08_cgb04c_out0.gbc", "0"),
            dmg("hwtests/sprites/10spritesPrLine_1xpos0_m3stat_1_dmg08_cgb04c_out3.gbc", "3"),
            cgb("hwtests/sprites/10spritesPrLine_1xpos0_m3stat_1_dmg08_cgb04c_out3.gbc", "3"),
            dmg("hwtests/window/m2int_wxA6_m3stat_2_dmg08_out0_cgb04c_out3.gbc", "0")
    );

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
        Set<String> names = TEST_CASES.stream().map(TestCase::rom).collect(java.util.stream.Collectors.toSet());
        Map<String, byte[]> roms = readRoms(names);
        List<Object[]> parameters = new ArrayList<>();
        for (TestCase test : TEST_CASES) {
            byte[] rom = roms.get(test.rom());
            if (rom == null) {
                throw new IOException("Missing Gambatte HWTest ROM: " + test.rom());
            }
            parameters.add(new Object[]{test.rom(), rom, test.gameboyType(), test.expected()});
        }
        return parameters;
    }

    @Test(timeout = 10000)
    public void test() throws IOException {
        String actual = new GambatteHwTestRunner(rom, gameboyType).runTest(expected.length());
        assertEquals(name, expected, actual);
    }

    private static Map<String, byte[]> readRoms(Set<String> names) throws IOException {
        InputStream input = GambatteHwRomTest.class.getResourceAsStream(ARCHIVE);
        if (input == null) {
            throw new IOException("Missing Gambatte HWTest archive: " + ARCHIVE);
        }
        Map<String, byte[]> roms = new LinkedHashMap<>();
        try (input; ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (names.contains(entry.getName())) {
                    roms.put(entry.getName(), zip.readAllBytes());
                }
                zip.closeEntry();
            }
        }
        return roms;
    }

    private static TestCase dmg(String rom, String expected) {
        return new TestCase(rom, GameboyType.DMG, expected);
    }

    private static TestCase cgb(String rom, String expected) {
        return new TestCase(rom, GameboyType.CGB, expected);
    }

    private record TestCase(String rom, GameboyType gameboyType, String expected) {
    }
}
