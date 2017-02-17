package eu.rekawek.coffeegb.integration.mooneye;

import eu.rekawek.coffeegb.integration.support.ParametersProvider;
import eu.rekawek.coffeegb.integration.support.RomTestUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static eu.rekawek.coffeegb.integration.mooneye.MooneyeAcceptanceTest.EXCLUDES;

@RunWith(Parameterized.class)
public class MooneyeEmulatorOnlyTest {

    private final Path romPath;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() throws IOException {
        return ParametersProvider.getParameters("mooneye/emulator-only", EXCLUDES);
    }

    public MooneyeEmulatorOnlyTest(String name, Path romPath) {
        this.romPath = romPath;
    }

    @Test(timeout = 5000)
    public void test() throws IOException {
        RomTestUtils.testMooneyeRom(romPath);
    }
}
