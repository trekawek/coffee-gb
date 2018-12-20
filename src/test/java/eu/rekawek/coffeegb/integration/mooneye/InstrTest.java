package eu.rekawek.coffeegb.integration.mooneye;

import eu.rekawek.coffeegb.integration.support.ParametersProvider;
import eu.rekawek.coffeegb.integration.support.RomTestUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

@RunWith(Parameterized.class)
public class InstrTest {

    private final Path romPath;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() throws IOException {
        return ParametersProvider.getParameters("mooneye/acceptance/instr");
    }

    public InstrTest(String name, Path romPath) {
        this.romPath = romPath;
    }

    @Test
    public void test() throws IOException {
        RomTestUtils.testMooneyeRom(romPath);
    }
}
