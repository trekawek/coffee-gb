package eu.rekawek.coffeegb.core.integration.casualpokeplayer;

import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.integration.support.ParallelParameterized;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;

import static eu.rekawek.coffeegb.core.integration.support.RomTestUtils.testRomWithScreenshotBaseline;

@RunWith(ParallelParameterized.class)
public class CasualPokePlayerRomTest {

    private static final Path SUITE_DIR =
            Paths.get("src/test/resources/roms/casualpokeplayer");

    private final String romName;

    private final GameboyType gameboyType;

    public CasualPokePlayerRomTest(String name, String romName, GameboyType gameboyType) {
        this.romName = romName;
        this.gameboyType = gameboyType;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {"RTC invalid banks", "rtc-invalid-banks-test.gb", GameboyType.DMG},
                {"RTC latch", "latch-rtc-test.gb", GameboyType.DMG},
                {"MBC3 RAM gate", "ramg-mbc3-test.gb", GameboyType.DMG},
                {"SGB extended packet protocol", "sgb-ext-test.gb", GameboyType.SGB},
        });
    }

    @Test(timeout = 30_000)
    public void test() throws Exception {
        String imageName = romName.replaceFirst("\\.gb$", ".png");
        testRomWithScreenshotBaseline(
                SUITE_DIR.resolve(romName),
                SUITE_DIR.resolve(imageName),
                SUITE_DIR.resolve("current-baseline").resolve(imageName),
                gameboyType, 500);
    }
}
