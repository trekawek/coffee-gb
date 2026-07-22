package eu.rekawek.coffeegb.core.integration.mealybug;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.integration.support.ImageTestRunner;
import eu.rekawek.coffeegb.core.integration.support.ParallelParameterized;
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
 * against photos of a real DMG-CPU B (DMG-blob where no CPU B photo exists). Two diagnostics
 * are also compared with the separate DMG-blob references used by Shootout. The tests run
 * with the FAST_FORWARD boot because they reuse the boot ROM's VRAM leftovers (the (r) logo
 * tile) as sprite data.
 */
@RunWith(ParallelParameterized.class)
public class MealybugRomTest {

    private static final int TEST_COUNT = 26;

    private final File rom;

    private final File reference;

    private final boolean mealybugDmgBlob;

    private final String name;

    public MealybugRomTest(String name, File rom, File reference,
                           boolean mealybugDmgBlob) {
        this.name = name;
        this.rom = rom;
        this.reference = reference;
        this.mealybugDmgBlob = mealybugDmgBlob;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        File dir = Paths.get("src/test/resources/roms/mealybug").toFile();
        List<Object[]> params = new ArrayList<>();
        File[] references = dir.listFiles(file -> file.isFile()
                && file.getName().endsWith(".png")
                && !file.getName().endsWith("-dmg-blob.png"));
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
            params.add(new Object[]{name, rom, reference, false});
        }
        addDmgCReference(params, dir, "m3_lcdc_bg_en_change");
        addDmgCReference(params, dir, "m3_lcdc_win_en_change_multiple_wx");
        if (params.size() != TEST_COUNT) {
            throw new IllegalStateException("Expected " + TEST_COUNT
                    + " Mealybug reference pairs, found " + params.size());
        }
        return params;
    }

    private static void addDmgCReference(List<Object[]> params, File dir, String name) {
        File rom = new File(dir, name + ".gb");
        File reference = new File(dir, name + "-dmg-blob.png");
        if (!rom.isFile() || !reference.isFile()) {
            throw new IllegalStateException("Missing DMG-blob Mealybug pair for " + name);
        }
        params.add(new Object[]{name + " [DMG blob]", rom, reference, true});
    }

    @Test
    public void test() throws Exception {
        ImageTestRunner runner = new ImageTestRunner(rom, reference, GameboyType.DMG,
                Gameboy.BootstrapMode.FAST_FORWARD, mealybugDmgBlob);
        ImageTestRunner.TestResult result = runner.runTest();
        int[] actual = result.getResultRGB();
        int[] expected = result.getExpectedRGB();
        assertEquals("frame size", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            if (actual[i] != expected[i]) {
                int x = i % Display.DISPLAY_WIDTH;
                int y = i / Display.DISPLAY_WIDTH;
                fail(String.format("%s differs at (%d,%d): expected #%06x, actual #%06x",
                        name, x, y, expected[i], actual[i]));
            }
        }
    }
}
