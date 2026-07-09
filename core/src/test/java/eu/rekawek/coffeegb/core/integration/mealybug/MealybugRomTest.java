package eu.rekawek.coffeegb.core.integration.mealybug;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.integration.support.ImageTestRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;

/**
 * Mealybug Tearoom tests: mid-scanline writes to the PPU registers during mode 3, compared
 * against photos of a real DMG-CPU B (DMG-blob where no CPU B photo exists). The tests run
 * with the FAST_FORWARD boot because they reuse the boot ROM's VRAM leftovers (the (r) logo
 * tile) as sprite data.
 *
 * <p>Each ROM asserts that the number of differing pixels does not exceed its known
 * baseline, so the pixel-exact tests are locked at 0 and the rest cannot regress silently.
 * When an accuracy change improves a score, tighten the baseline here.
 */
@RunWith(Parameterized.class)
public class MealybugRomTest {

    private static final Map<String, Integer> BASELINES = Map.ofEntries(
            Map.entry("m2_win_en_toggle.gb", 0),
            Map.entry("m3_bgp_change.gb", 0),
            Map.entry("m3_bgp_change_sprites.gb", 0),
            Map.entry("m3_lcdc_bg_en_change.gb", 0),
            Map.entry("m3_lcdc_bg_map_change.gb", 0),
            Map.entry("m3_lcdc_obj_en_change.gb", 0),
            Map.entry("m3_lcdc_obj_en_change_variant.gb", 0),
            Map.entry("m3_lcdc_obj_size_change.gb", 0),
            Map.entry("m3_lcdc_obj_size_change_scx.gb", 0),
            Map.entry("m3_lcdc_tile_sel_change.gb", 0),
            Map.entry("m3_lcdc_tile_sel_win_change.gb", 124),
            Map.entry("m3_lcdc_win_en_change_multiple.gb", 0),
            Map.entry("m3_lcdc_win_en_change_multiple_wx.gb", 3),
            Map.entry("m3_lcdc_win_map_change.gb", 0),
            Map.entry("m3_obp0_change.gb", 0),
            Map.entry("m3_scx_high_5_bits.gb", 0),
            Map.entry("m3_scx_low_3_bits.gb", 0),
            Map.entry("m3_scy_change.gb", 0),
            Map.entry("m3_window_timing.gb", 0),
            Map.entry("m3_window_timing_wx_0.gb", 0),
            Map.entry("m3_wx_4_change.gb", 0),
            Map.entry("m3_wx_4_change_sprites.gb", 0),
            Map.entry("m3_wx_5_change.gb", 0),
            Map.entry("m3_wx_6_change.gb", 0));

    private final File rom;

    private final int baseline;

    public MealybugRomTest(String name, File rom, int baseline) {
        this.rom = rom;
        this.baseline = baseline;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        File dir = Paths.get("src/test/resources/roms/mealybug").toFile();
        List<Object[]> params = new ArrayList<>();
        for (Map.Entry<String, Integer> e : BASELINES.entrySet()) {
            File rom = new File(dir, e.getKey());
            if (rom.isFile()) {
                params.add(new Object[]{e.getKey(), rom, e.getValue()});
            }
        }
        params.sort((a, b) -> ((String) a[0]).compareTo((String) b[0]));
        return params;
    }

    @Test
    public void test() throws Exception {
        ImageTestRunner runner =
                new ImageTestRunner(rom, GameboyType.DMG, Gameboy.BootstrapMode.FAST_FORWARD);
        ImageTestRunner.TestResult result = runner.runTest();
        int[] actual = result.getResultRGB();
        int[] expected = result.getExpectedRGB();
        int diff = 0;
        for (int i = 0; i < expected.length; i++) {
            if (actual[i] != expected[i]) {
                diff++;
            }
        }
        assertTrue("diff pixels " + diff + " exceeds the known baseline " + baseline
                + " - an accuracy regression", diff <= baseline);
    }
}
