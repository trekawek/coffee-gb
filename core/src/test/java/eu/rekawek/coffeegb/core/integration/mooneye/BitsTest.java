package eu.rekawek.coffeegb.core.integration.mooneye;

import eu.rekawek.coffeegb.core.integration.support.ParametersProvider;
import eu.rekawek.coffeegb.core.integration.support.RomTestUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

@RunWith(Parameterized.class)
public class BitsTest {

    private final Path romPath;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() throws IOException {
        return ParametersProvider.getParameters("mooneye/acceptance/bits");
    }

    public BitsTest(String name, Path romPath) {
        this.romPath = romPath;
    }

    @Test(timeout = 5000)
    public void test() throws IOException {
        RomTestUtils.testMooneyeRom(romPath);
    }
}
