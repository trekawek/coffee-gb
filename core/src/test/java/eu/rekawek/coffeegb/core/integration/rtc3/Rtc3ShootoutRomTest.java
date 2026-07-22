package eu.rekawek.coffeegb.core.integration.rtc3;

import eu.rekawek.coffeegb.core.GameboyType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;

import static eu.rekawek.coffeegb.core.integration.support.RomTestUtils.testRomWithScreenshot;

@RunWith(Parameterized.class)
public class Rtc3ShootoutRomTest {

    private static final Path SUITE_DIR = Paths.get("src/test/resources/roms/rtc3");

    private final String version;

    private final int runtimeMillis;

    public Rtc3ShootoutRomTest(String version, int runtimeMillis) {
        this.version = version;
        this.runtimeMillis = runtimeMillis;
    }

    @Parameterized.Parameters(name = "RTC3Test v{0} shootout")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {"1", 15_000},
                {"2", 12_000},
                {"3", 30_000}
        });
    }

    @Test(timeout = 120_000)
    public void testHistoricalShootoutRelease() throws Exception {
        String basename = "rtc3test-" + version + "-shootout";
        testRomWithScreenshot(
                SUITE_DIR.resolve(basename + ".gb"),
                SUITE_DIR.resolve(basename + ".png"),
                GameboyType.CGB, runtimeMillis, 50);
    }
}
