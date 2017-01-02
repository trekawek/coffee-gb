package eu.rekawek.coffegb;

import eu.rekawek.coffeegb.cpu.Cpu;
import eu.rekawek.coffeegb.memory.Mmu;
import org.junit.Test;

public class GameboyTest {

    @Test
    public void testBoot() {
        Mmu mmu = new Mmu();
        Cpu cpu = new Cpu(mmu);
        while (true) {
            cpu.runCommand();
        }
    }

}