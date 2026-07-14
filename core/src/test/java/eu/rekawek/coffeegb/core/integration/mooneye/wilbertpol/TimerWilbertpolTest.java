package eu.rekawek.coffeegb.core.integration.mooneye.wilbertpol;

import eu.rekawek.coffeegb.core.integration.support.ParametersProvider;
import eu.rekawek.coffeegb.core.integration.support.RomTestUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

@RunWith(Parameterized.class)
public class TimerWilbertpolTest {

    private final Path romPath;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() throws IOException {
        return ParametersProvider.getParameters("mooneye-wilbertpol/acceptance/timer");
    }

    public TimerWilbertpolTest(String name, Path romPath) {
        this.romPath = romPath;
    }

    @Test
    public void test() throws IOException {
        RomTestUtils.testMooneyeRom(romPath);
    }
}
