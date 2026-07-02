package eu.rekawek.coffeegb.core.integration.mealybug;

import eu.rekawek.coffeegb.core.GameboyType;
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

import static eu.rekawek.coffeegb.core.integration.support.RomTestUtils.testRomWithImage;

/**
 * Mealybug Tearoom tests: mid-scanline writes to the PPU registers (SCX/SCY, LCDC bits, BGP)
 * during mode 3. The expected images were captured from real hardware (DMG-CPU B where the
 * revisions differ, DMG-blob otherwise); a test passes when the frame at the LD B,B breakpoint
 * is pixel-identical.
 */
@RunWith(Parameterized.class)
public class MealybugRomTest {

    private final Path rom;

    public MealybugRomTest(String name, Path rom) {
        this.rom = rom;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        File dir = Paths.get("src/test/resources/roms/mealybug").toFile();
        File[] roms = dir.listFiles((d, n) -> n.endsWith(".gb"));
        Arrays.sort(roms);
        List<Object[]> params = new ArrayList<>();
        for (File rom : roms) {
            // ROMs without an expected image are CGB-only variants
            if (new File(dir, rom.getName().replace(".gb", ".png")).isFile()) {
                params.add(new Object[]{rom.getName(), rom.toPath()});
            }
        }
        return params;
    }

    @Test
    public void test() throws Exception {
        // FAST_FORWARD: the tests reuse the boot ROM's VRAM leftovers (the (r) logo tile)
        testRomWithImage(rom, GameboyType.DMG, eu.rekawek.coffeegb.core.Gameboy.BootstrapMode.FAST_FORWARD);
    }
}
