package eu.rekawek.coffeegb.core.integration.cgbacidhell;

import eu.rekawek.coffeegb.core.GameboyType;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static eu.rekawek.coffeegb.core.integration.support.RomTestUtils.testRomWithScreenshot;

public class CgbAcidHellRomTest {

    private static final Path SUITE_DIR = Paths.get("src/test/resources/roms/cgb-acid-hell");

    @Test(timeout = 30_000)
    public void testCgbAcidHell() throws Exception {
        testRomWithScreenshot(
                SUITE_DIR.resolve("cgb-acid-hell.gbc"),
                SUITE_DIR.resolve("cgb-acid-hell.png"),
                GameboyType.CGB, 1500);
    }
}
