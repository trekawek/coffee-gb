package eu.rekawek.coffeegb;

import eu.rekawek.coffeegb.cpu.Cpu;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.gpu.Gpu;
import eu.rekawek.coffeegb.memory.Mmu;

public class Gameboy {

    private final InterruptManager interruptManager;

    private final Gpu gpu;

    private final Mmu mmu;

    private final Cpu cpu;

    public Gameboy() {
        interruptManager = new InterruptManager();
        gpu = new Gpu(interruptManager);
        mmu = new Mmu(gpu);
        cpu = new Cpu(mmu);
    }

    public void run() {
        while (cpu.getRegisters().getPC() != 0x100) {
            cpu.tick();
            gpu.proceed(1);
        }
    }
}
