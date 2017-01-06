package eu.rekawek.coffegb;

import eu.rekawek.coffeegb.cpu.Cpu;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.gpu.Gpu;
import eu.rekawek.coffeegb.memory.Mmu;
import org.junit.Test;

public class GameboyTest {

    @Test
    public void testBoot() {
        InterruptManager interruptManager = new InterruptManager();
        Gpu gpu = new Gpu(interruptManager);
        Mmu mmu = new Mmu(gpu);
        Cpu cpu = new Cpu(mmu);
        while (cpu.getRegisters().getPC() != 0x100) {
            cpu.tick();
            gpu.proceed(1);
        }
    }

}