package eu.rekawek.coffeegb.core.integration.daid;

import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.integration.support.DaidTestRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.fail;

/**
 * Screenshot-based tests from Daid's GB Emulator Shootout suite.
 *
 * <p>Each case asserts that the pixels outside Daid's luminance tolerance do not exceed
 * the known baseline. Passing cases are locked at zero; non-zero cases cannot regress
 * silently and their baselines should be tightened when PPU accuracy improves.
 */
@RunWith(Parameterized.class)
public class DaidRomTest {

    private static final Path SUITE_DIR = Paths.get("src/test/resources/roms/daid");

    private final String name;

    private final String romName;

    private final GameboyType gameboyType;

    private final String[] expectedNames;

    private final int baseline;

    public DaidRomTest(String name, String romName, GameboyType gameboyType,
                       String[] expectedNames, int baseline) {
        this.name = name;
        this.romName = romName;
        this.gameboyType = gameboyType;
        this.expectedNames = expectedNames;
        this.baseline = baseline;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                // Correct palette patterns, currently phased 4 DMG / 5 CGB dots early.
                {"ppu_scanline_bgp (DMG)", "ppu_scanline_bgp.gb", GameboyType.DMG,
                        new String[]{"ppu_scanline_bgp_0.dmg.png", "ppu_scanline_bgp_1.dmg.png",
                                "ppu_scanline_bgp_2.dmg.png"}, 2192},
                {"ppu_scanline_bgp (CGB)", "ppu_scanline_bgp.gb", GameboyType.CGB,
                        new String[]{"ppu_scanline_bgp.gbc.png"}, 2880},
                {"stop_instr (DMG)", "stop_instr.gb", GameboyType.DMG,
                        new String[]{"stop_instr.dmg.png"}, 0},
                {"stop_instr (CGB)", "stop_instr.gb", GameboyType.CGB,
                        new String[]{"stop_instr.gbc.png"}, 0},
                {"stop_instr_gbc_mode3", "stop_instr_gbc_mode3.gb", GameboyType.CGB,
                        new String[]{"stop_instr_gbc_mode3.png"}, 0},
                {"speed_switch_timing_div", "speed_switch_timing_div.gbc", GameboyType.CGB,
                        new String[]{"speed_switch_timing_div.png"}, 0},
                {"speed_switch_timing_ly", "speed_switch_timing_ly.gbc", GameboyType.CGB,
                        new String[]{"speed_switch_timing_ly.png"}, 0},
                {"speed_switch_timing_stat", "speed_switch_timing_stat.gbc", GameboyType.CGB,
                        new String[]{"speed_switch_timing_stat.png"}, 0},
        });
    }

    @Test(timeout = 30_000)
    public void test() throws Exception {
        List<File> expectedFiles = new ArrayList<>();
        for (String expectedName : expectedNames) {
            expectedFiles.add(SUITE_DIR.resolve(expectedName).toFile());
        }

        DaidTestRunner runner = new DaidTestRunner(
                SUITE_DIR.resolve(romName).toFile(), gameboyType, expectedFiles);
        DaidTestRunner.TestResult result = runner.runTest();
        System.out.printf("%s: %d pixels outside tolerance, max luminance delta %d%n",
                name, result.getViolatingPixels(), result.getMaxDelta());

        if (result.getViolatingPixels() > baseline) {
            File resultFile = File.createTempFile(romName + "-", "-result.png");
            result.writeResultToFile(resultFile);
            fail(result.getViolatingPixels() + " pixels exceed Daid's luminance tolerance; expected at most "
                    + baseline + ", closest reference " + expectedNames[result.getExpectedIndex()]
                    + ", max delta " + result.getMaxDelta() + ", actual image: " + resultFile);
        }
    }
}
