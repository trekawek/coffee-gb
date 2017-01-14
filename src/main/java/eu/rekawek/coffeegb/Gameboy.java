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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Gameboy {

    private static final Logger LOG = LoggerFactory.getLogger(Gameboy.class);

    private final InterruptManager interruptManager;

    private final Gpu gpu;

    private final Mmu mmu;

    private final Cpu cpu;

    private final Timer timer;

    private final Dma dma;

    private final Display display;

    public Gameboy(Cartridge rom, Display display, Controller controller) {
        this.display = display;

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

        int ticksSinceScreenRefresh = 0;
        long lastScreenRefresh = System.nanoTime();
        while (true) {
            if (cpuTick == 0) {
                cpu.tick();
            }
            cpuTick = (cpuTick + 1) % 4;
            timer.tick();
            dma.tick();
            boolean screenRefreshed = gpu.tick();

            if (screenRefreshed) {
                display.refresh();
            }

            /*ticksSinceScreenRefresh++;
            if (screenRefreshed) {
                long timeSinceScreenRefresh = System.nanoTime() - lastScreenRefresh;
                long gbTime = ticksSinceScreenRefresh * 1_000_000_000 / 4_194_304;

                if (timeSinceScreenRefresh < gbTime) {
                    try {
                        Thread.sleep(0, (int) (gbTime - timeSinceScreenRefresh));
                    } catch (InterruptedException e) {
                        break;
                    }

                    ticksSinceScreenRefresh = 0;
                    lastScreenRefresh = System.nanoTime();
                } else {
                    System.err.println("too long: " + (timeSinceScreenRefresh - gbTime));
                }
            }*/
        }
    }
}
