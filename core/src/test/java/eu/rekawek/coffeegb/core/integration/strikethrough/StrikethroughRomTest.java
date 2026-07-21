package eu.rekawek.coffeegb.core.integration.strikethrough;

import eu.rekawek.coffeegb.core.GameboyType;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static eu.rekawek.coffeegb.core.integration.support.RomTestUtils.testRomWithScreenshot;

public class StrikethroughRomTest {

    private static final Path SUITE_DIR = Paths.get("src/test/resources/roms/strikethrough");

    @Test(timeout = 30_000)
    public void testStrikethrough() throws Exception {
        testRomWithScreenshot(
                SUITE_DIR.resolve("strikethrough.gb"),
                SUITE_DIR.resolve("strikethrough.png"),
                GameboyType.DMG, 500);
    }
}
