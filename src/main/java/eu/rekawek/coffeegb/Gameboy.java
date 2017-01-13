package eu.rekawek.coffeegb;

import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.controller.Joypad;
import eu.rekawek.coffeegb.cpu.Cpu;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.gpu.Display;
import eu.rekawek.coffeegb.gpu.Gpu;
import eu.rekawek.coffeegb.memory.Dma;
import eu.rekawek.coffeegb.memory.Mmu;
import eu.rekawek.coffeegb.memory.Ram;
import eu.rekawek.coffeegb.memory.cart.Cartridge;
import eu.rekawek.coffeegb.serial.SerialPort;
import eu.rekawek.coffeegb.timer.Timer;

public class Gameboy {

    private final InterruptManager interruptManager;

    private final Gpu gpu;

    private final Mmu mmu;

    private final Cpu cpu;

    private final Timer timer;

    private final Dma dma;

    public Gameboy(Cartridge rom, Display display, Controller controller) {
        interruptManager = new InterruptManager();
        timer = new Timer(interruptManager);
        gpu = new Gpu(display, interruptManager);
        mmu = new Mmu();
        dma = new Dma(mmu);
        mmu.addAddressSpace(rom);
        mmu.addAddressSpace(gpu);
        mmu.addAddressSpace(new Joypad(interruptManager, controller));
        mmu.addAddressSpace(interruptManager);
        mmu.addAddressSpace(new SerialPort());
        mmu.addAddressSpace(new Ram(0xff10, 0x30)); // sound
        mmu.addAddressSpace(timer);
        mmu.addAddressSpace(dma);
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
            timer.tick();
            dma.tick();
        }
    }
}
