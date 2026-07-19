package eu.rekawek.coffeegb.core.integration.mealybug;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.integration.support.ImageTestRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Mealybug Tearoom tests: mid-scanline writes to the PPU registers during mode 3, compared
 * against photos of a real DMG-CPU B (DMG-blob where no CPU B photo exists). The tests run
 * with the FAST_FORWARD boot because they reuse the boot ROM's VRAM leftovers (the (r) logo
 * tile) as sprite data.
 *
 * <p>Every reference is pixel-exact except for the one explicitly documented hardware-photo
 * discrepancy in {@code m3_lcdc_win_en_change_multiple_wx.gb}.
 */
@RunWith(Parameterized.class)
public class MealybugRomTest {

    private static final int TEST_COUNT = 24;

    private static final String PIXEL_EXCEPTION_ROM = "m3_lcdc_win_en_change_multiple_wx.gb";

    private static final int PIXEL_EXCEPTION_X = 32;

    private static final int PIXEL_EXCEPTION_Y = 39;

    private static final int PIXEL_EXCEPTION_EXPECTED = 0xffffff;

    private static final int PIXEL_EXCEPTION_ACTUAL = 0xaaaaaa;

    private final File rom;

    public MealybugRomTest(String name, File rom) {
        this.rom = rom;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        File dir = Paths.get("src/test/resources/roms/mealybug").toFile();
        List<Object[]> params = new ArrayList<>();
        File[] references = dir.listFiles(file -> file.isFile() && file.getName().endsWith(".png"));
        if (references == null) {
            throw new IllegalStateException("Can't list Mealybug test resources in " + dir);
        }
        Arrays.sort(references);
        for (File reference : references) {
            String name = reference.getName().replaceFirst("\\.png$", ".gb");
            File rom = new File(dir, name);
            if (!rom.isFile()) {
                throw new IllegalStateException("Missing Mealybug ROM for " + reference);
            }
            params.add(new Object[]{name, rom});
        }
        if (params.size() != TEST_COUNT) {
            throw new IllegalStateException("Expected " + TEST_COUNT
                    + " Mealybug reference pairs, found " + params.size());
        }
        return params;
    }

    @Test
    public void test() throws Exception {
        ImageTestRunner runner =
                new ImageTestRunner(rom, GameboyType.DMG, Gameboy.BootstrapMode.FAST_FORWARD);
        ImageTestRunner.TestResult result = runner.runTest();
        int[] actual = result.getResultRGB();
        int[] expected = result.getExpectedRGB();
        assertEquals("frame size", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            if (actual[i] != expected[i]) {
                int x = i % Display.DISPLAY_WIDTH;
                int y = i / Display.DISPLAY_WIDTH;
                boolean allowedPixel = rom.getName().equals(PIXEL_EXCEPTION_ROM)
                        && x == PIXEL_EXCEPTION_X && y == PIXEL_EXCEPTION_Y;
                if (!allowedPixel) {
                    fail(String.format("%s differs at (%d,%d): expected #%06x, actual #%06x",
                            rom.getName(), x, y, expected[i], actual[i]));
                }
                assertEquals("exception expected color", PIXEL_EXCEPTION_EXPECTED, expected[i]);
                assertEquals("exception actual color", PIXEL_EXCEPTION_ACTUAL, actual[i]);
            }
        }
    }
}
