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

@RunWith(Parameterized.class)
public class BitsTest {

    static final List<String> EXCLUDES = Arrays.asList(
            "-dmgABCX.gb",
            "-dmgABCXmgb.gb",
            "-mgb.gb",
            "-sgb.gb",
            "-sgb2.gb",
            "-S.gb"
    );

    private final Path romPath;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() throws IOException {
        return ParametersProvider.getParameters("mooneye/acceptance/bits", EXCLUDES, 1);
    }

    public BitsTest(String name, Path romPath) {
        this.romPath = romPath;
    }

    @Test(timeout = 5000)
    public void test() throws IOException {
        RomTestUtils.testMooneyeRom(romPath);
    }
}
