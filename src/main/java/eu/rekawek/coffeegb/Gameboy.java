package eu.rekawek.coffeegb;

import eu.rekawek.coffeegb.cpu.Cpu;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.gpu.Display;
import eu.rekawek.coffeegb.gpu.Gpu;
import eu.rekawek.coffeegb.memory.Mmu;
import eu.rekawek.coffeegb.memory.Ram;
import eu.rekawek.coffeegb.memory.Rom;

import java.io.InputStream;

public class Gameboy {

    private final InterruptManager interruptManager;

    private final Gpu gpu;

    private final Mmu mmu;

    private final Cpu cpu;

    public Gameboy(int[] data, Display display) {
        Rom rom = new Rom(data, 0);
        Ram ram = new Ram();
        interruptManager = new InterruptManager();
        gpu = new Gpu(ram, display);
        mmu = new Mmu(gpu, ram, rom);
        cpu = new Cpu(mmu);
    }

    public void run() {
        int cpuTick = 0;
        while (cpu.getRegisters().getPC() != 0x100) {
            if (cpuTick == 0) {
                cpu.tick();
            }
            cpuTick = (cpuTick + 1) % 4;
            gpu.tick();
        }
    }
}
