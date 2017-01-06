package eu.rekawek.coffegb;

import eu.rekawek.coffeegb.Gameboy;
import eu.rekawek.coffeegb.cpu.Cpu;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.gpu.Gpu;
import eu.rekawek.coffeegb.memory.Mmu;
import org.junit.Test;

public class GameboyTest {

    @Test
    public void testBoot() {
        new Gameboy().run();
    }

}