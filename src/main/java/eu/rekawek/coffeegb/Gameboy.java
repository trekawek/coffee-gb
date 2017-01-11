package eu.rekawek.coffeegb;

import eu.rekawek.coffeegb.cpu.Cpu;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.gpu.Display;
import eu.rekawek.coffeegb.gpu.Gpu;
import eu.rekawek.coffeegb.memory.Mmu;
import eu.rekawek.coffeegb.memory.Cartridge;

public class Gameboy {

    private final InterruptManager interruptManager;

    private final Gpu gpu;

    private final Mmu mmu;

    private final Cpu cpu;

    public Gameboy(Cartridge rom, Display display) {
        interruptManager = new InterruptManager();
        gpu = new Gpu(display, interruptManager);
        mmu = new Mmu();
        mmu.addAddressSpace(rom);
        mmu.addAddressSpace(gpu);
        mmu.addAddressSpace(interruptManager);
        cpu = new Cpu(mmu, interruptManager);
    }

    public void run() {
        int cpuTick = 0;
        while (true) {
            if (cpuTick == 0) {
                cpu.tick();
            }
            cpuTick = (cpuTick + 1) % 4;
            gpu.tick();
        }
    }
}
