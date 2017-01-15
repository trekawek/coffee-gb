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
        int ticksSinceScreenRefresh = 0;
        long lastScreenRefresh = System.nanoTime();

        boolean requestedScreenRefresh = false;

        while (true) {
            ticksSinceScreenRefresh++;

            Gpu.Mode newMode = tick();
            if (newMode == Gpu.Mode.VBlank) {
                requestedScreenRefresh = true;
                display.requestRefresh();
            }
            if (requestedScreenRefresh && newMode == Gpu.Mode.OamSearch) {
                requestedScreenRefresh = false;
                display.waitForRefresh();

                long timeSinceScreenRefresh = System.nanoTime() - lastScreenRefresh;
                long gbTime = (1_000_000_000 / 4_194_304) * ticksSinceScreenRefresh;

                if (timeSinceScreenRefresh < gbTime) {
                    sleepNanos(gbTime - timeSinceScreenRefresh);
                }

                ticksSinceScreenRefresh = 0;
                lastScreenRefresh = System.nanoTime();
            }
        }
    }

    public Gpu.Mode tick() {
        cpu.tick();
        timer.tick();
        dma.tick();
        return gpu.tick();
    }

    private void sleepNanos(long nanos) {
        try {
            Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000));
        } catch (InterruptedException e) {
            LOG.warn("Interrupted", e);
        }
    }
}
