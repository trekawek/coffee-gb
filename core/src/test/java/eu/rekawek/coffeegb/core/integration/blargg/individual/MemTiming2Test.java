package eu.rekawek.coffeegb.core.integration.blargg.individual;

import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.integration.support.ParametersProvider;
import eu.rekawek.coffeegb.core.integration.support.RomTestUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;

@RunWith(Parameterized.class)
public class MemTiming2Test {

    private final Path rom;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() throws IOException {
        return ParametersProvider.getParameters("blargg/mem_timing-2");
    }

    public MemTiming2Test(String name, Path rom) {
        this.rom = rom;
    }

    @Test
    public void test() throws IOException {
        RomTestUtils.testRomWithMemory(rom, GameboyType.DMG);
    }
}
