package eu.rekawek.coffeegb;

import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.controller.Joypad;
import eu.rekawek.coffeegb.cpu.Cpu;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.cpu.Registers;
import eu.rekawek.coffeegb.cpu.SpeedMode;
import eu.rekawek.coffeegb.debug.Console;
import eu.rekawek.coffeegb.gpu.Display;
import eu.rekawek.coffeegb.gpu.Gpu;
import eu.rekawek.coffeegb.memory.Dma;
import eu.rekawek.coffeegb.memory.GbcRam;
import eu.rekawek.coffeegb.memory.Hdma;
import eu.rekawek.coffeegb.memory.Mmu;
import eu.rekawek.coffeegb.memory.Ram;
import eu.rekawek.coffeegb.memory.ShadowAddressSpace;
import eu.rekawek.coffeegb.memory.UndocumentedGbcRegisters;
import eu.rekawek.coffeegb.memory.cart.Cartridge;
import eu.rekawek.coffeegb.serial.SerialEndpoint;
import eu.rekawek.coffeegb.serial.SerialPort;
import eu.rekawek.coffeegb.sound.Sound;
import eu.rekawek.coffeegb.sound.SoundOutput;
import eu.rekawek.coffeegb.timer.Timer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Gameboy implements Runnable {

    public static final int TICKS_PER_SEC = 4_194_304;

    private final InterruptManager interruptManager;

    private final Gpu gpu;

    private final Mmu mmu;

    private final Cpu cpu;

    private final Timer timer;

    private final Dma dma;

    private final Hdma hdma;

    private final Display display;

    private final Sound sound;

    private final SerialPort serialPort;

    private final boolean gbc;

    private final SpeedMode speedMode;

    private final Optional<Console> console;

    private volatile boolean doStop;

    private final List<Runnable> tickListeners = new ArrayList<>();

    public Gameboy(GameboyOptions options, Cartridge rom, Display display, Controller controller, SoundOutput soundOutput, SerialEndpoint serialEndpoint) {
        this(options, rom, display, controller, soundOutput, serialEndpoint, Optional.empty());
    }

    public Gameboy(GameboyOptions options, Cartridge rom, Display display, Controller controller, SoundOutput soundOutput, SerialEndpoint serialEndpoint, Optional<Console> console) {
        this.display = display;
        gbc = rom.isGbc();
        speedMode = new SpeedMode();
        interruptManager = new InterruptManager(gbc);
        timer = new Timer(interruptManager, speedMode);
        mmu = new Mmu();

        Ram oamRam = new Ram(0xfe00, 0x00a0);
        dma = new Dma(mmu, oamRam, speedMode);
        gpu = new Gpu(display, interruptManager, dma, oamRam, gbc);
        hdma = new Hdma(mmu);
        sound = new Sound(soundOutput, gbc);
        serialPort = new SerialPort(interruptManager, serialEndpoint, speedMode);
        mmu.addAddressSpace(rom);
        mmu.addAddressSpace(gpu);
        mmu.addAddressSpace(new Joypad(interruptManager, controller));
        mmu.addAddressSpace(interruptManager);
        mmu.addAddressSpace(serialPort);
        mmu.addAddressSpace(timer);
        mmu.addAddressSpace(dma);
        mmu.addAddressSpace(sound);

        mmu.addAddressSpace(new Ram(0xc000, 0x1000));
        if (gbc) {
            mmu.addAddressSpace(speedMode);
            mmu.addAddressSpace(hdma);
            mmu.addAddressSpace(new GbcRam());
            mmu.addAddressSpace(new UndocumentedGbcRegisters());
        } else {
            mmu.addAddressSpace(new Ram(0xd000, 0x1000));
        }
        mmu.addAddressSpace(new Ram(0xff80, 0x7f));
        mmu.addAddressSpace(new ShadowAddressSpace(mmu, 0xe000, 0xc000, 0x1e00));

        cpu = new Cpu(mmu, interruptManager, gpu, display, speedMode);

        interruptManager.disableInterrupts(false);
        if (!options.isUsingBootstrap()) {
            initRegs();
        }

        this.console = console;
    }

    private void initRegs() {
        Registers r = cpu.getRegisters();

        r.setAF(0x01b0);
        if (gbc) {
            r.setA(0x11);
        }
        r.setBC(0x0013);
        r.setDE(0x00d8);
        r.setHL(0x014d);
        r.setSP(0xfffe);
        r.setPC(0x0100);
    }

    public void run() {
        boolean requestedScreenRefresh = false;
        boolean lcdDisabled = false;
        doStop = false;
        while (!doStop) {
            Gpu.Mode newMode = tick();
            if (newMode != null) {
                hdma.onGpuUpdate(newMode);
            }

            if (!lcdDisabled && !gpu.isLcdEnabled()) {
                lcdDisabled = true;
                display.requestRefresh();
                hdma.onLcdSwitch(false);
            } else if (newMode == Gpu.Mode.VBlank) {
                requestedScreenRefresh = true;
                display.requestRefresh();
            }

            if (lcdDisabled && gpu.isLcdEnabled()) {
                lcdDisabled = false;
                display.waitForRefresh();
                hdma.onLcdSwitch(true);
            } else if (requestedScreenRefresh && newMode == Gpu.Mode.OamSearch) {
                requestedScreenRefresh = false;
                display.waitForRefresh();
            }
            console.ifPresent(Console::tick);
            tickListeners.forEach(Runnable::run);
        }
    }

    public void stop() {
        doStop = true;
    }

    public Gpu.Mode tick() {
        timer.tick();
        if (hdma.isTransferInProgress()) {
            hdma.tick();
        } else {
            cpu.tick();
        }
        dma.tick();
        sound.tick();
        serialPort.tick();
        return gpu.tick();
    }

    public AddressSpace getAddressSpace() {
        return mmu;
    }

    public Cpu getCpu() {
        return cpu;
    }

    public SpeedMode getSpeedMode() {
        return speedMode;
    }

    public Gpu getGpu() {
        return gpu;
    }

    public void registerTickListener(Runnable tickListener) {
        tickListeners.add(tickListener);
    }

    public void unregisterTickListener(Runnable tickListener) {
        tickListeners.remove(tickListener);
    }

    public Sound getSound() {
        return sound;
    }
}
