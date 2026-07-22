package eu.rekawek.coffeegb.core.integration.samesuite;

import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.integration.support.ScreenshotTestRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class SameSuiteShootoutScreenshotTest {

    private static final Path DMA_DIR = Paths.get("src/test/resources/roms/samesuite/dma");

    private final String name;

    public SameSuiteShootoutScreenshotTest(String name) {
        this.name = name;
    }

    @Parameterized.Parameters(name = "{0} shootout screenshot")
    public static List<String> data() {
        return List.of("gdma_addr_mask", "hdma_lcd_off", "hdma_mode0");
    }

    @Test(timeout = 30000)
    public void testShootoutReference() throws Exception {
        ScreenshotTestRunner.TestResult result = new ScreenshotTestRunner(
                DMA_DIR.resolve(name + "-shootout.gb").toFile(),
                DMA_DIR.resolve(name + "-shootout.png").toFile(),
                GameboyType.CGB,
                1500,
                50).runTest();
        assertTrue("Maximum grayscale delta: " + result.getMaxGrayscaleDelta(),
                result.getMaxGrayscaleDelta() <= 50);
    }
}
