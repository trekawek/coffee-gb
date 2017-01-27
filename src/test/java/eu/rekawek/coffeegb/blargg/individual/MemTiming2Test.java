package eu.rekawek.coffeegb.blargg.individual;

import eu.rekawek.coffeegb.blargg.support.ParametersProvider;
import eu.rekawek.coffeegb.blargg.support.RomTestUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.util.Collection;

@RunWith(Parameterized.class)
public class MemTiming2Test {

    private final String rom;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
        return ParametersProvider.getParameters("mem_timing-2");
    }

    public MemTiming2Test(String name, String rom) {
        this.rom = rom;
    }

    @Test
    public void test() throws IOException {
        RomTestUtils.testRomWithMemory(rom);
    }
}
