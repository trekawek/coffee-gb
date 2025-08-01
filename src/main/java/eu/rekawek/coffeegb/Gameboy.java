package eu.rekawek.coffeegb;

import eu.rekawek.coffeegb.controller.Joypad;
import eu.rekawek.coffeegb.cpu.Cpu;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.cpu.SpeedMode;
import eu.rekawek.coffeegb.debug.Console;
import eu.rekawek.coffeegb.events.EventBus;
import eu.rekawek.coffeegb.gpu.Display;
import eu.rekawek.coffeegb.gpu.Gpu;
import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;
import eu.rekawek.coffeegb.memory.Dma;
import eu.rekawek.coffeegb.memory.Hdma;
import eu.rekawek.coffeegb.memory.Mmu;
import eu.rekawek.coffeegb.memory.Ram;
import eu.rekawek.coffeegb.memory.cart.Cartridge;
import eu.rekawek.coffeegb.serial.SerialEndpoint;
import eu.rekawek.coffeegb.serial.SerialPort;
import eu.rekawek.coffeegb.sound.Sound;
import eu.rekawek.coffeegb.timer.Timer;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Gameboy implements Runnable, Serializable, Originator<Gameboy> {

    private static final boolean BOOTSTRAP_FAST_FORWARD = true;

    public static final int TICKS_PER_SEC = 4_194_304;

    // 60 frames per second
    public static final int TICKS_PER_FRAME = Gameboy.TICKS_PER_SEC / 60;

    private final Cartridge rom;

    private final Gpu gpu;

    private final Mmu mmu;

    private final Ram oamRam;

    private final Cpu cpu;

    private final InterruptManager interruptManager;

    private final Timer timer;

    private final Dma dma;

    private final Hdma hdma;

    private final Display display;

    private final Sound sound;

    private final SerialPort serialPort;

    private final Joypad joypad;

    private final SpeedMode speedMode;

    private transient Console console;

    private transient volatile boolean doStop;

    private final List<Runnable> tickListeners = new ArrayList<>();

    private boolean requestedScreenRefresh;

    private boolean lcdDisabled;

    private transient volatile boolean doPause;

    private transient volatile boolean paused;

    public Gameboy(Cartridge rom) {
        this.rom = rom;
        boolean gbc = rom.isGbc();
        speedMode = new SpeedMode();
        interruptManager = new InterruptManager(gbc);
        timer = new Timer(interruptManager, speedMode);
        mmu = new Mmu(gbc);
        display = new Display(gbc);

        oamRam = new Ram(0xfe00, 0x00a0);
        dma = new Dma(mmu, oamRam, speedMode);
        gpu = new Gpu(display, interruptManager, dma, oamRam, gbc);
        hdma = new Hdma(mmu);
        sound = new Sound(gbc);
        joypad = new Joypad(interruptManager);
        serialPort = new SerialPort(interruptManager, gbc, speedMode);
        mmu.addAddressSpace(rom);
        mmu.addAddressSpace(gpu);
        mmu.addAddressSpace(joypad);
        mmu.addAddressSpace(interruptManager);
        mmu.addAddressSpace(serialPort);
        mmu.addAddressSpace(timer);
        mmu.addAddressSpace(dma);
        mmu.addAddressSpace(sound);

        if (gbc) {
            mmu.addAddressSpace(speedMode);
            mmu.addAddressSpace(hdma);
        }
        mmu.indexSpaces();

        cpu = new Cpu(mmu, interruptManager, gpu, speedMode, display);

        interruptManager.disableInterrupts(false);
        if (BOOTSTRAP_FAST_FORWARD) {
            rom.setByte(0xff50, 0);
            var r = cpu.getRegisters();
            if (gbc) {
                r.setAF(0x1180);
                r.setBC(0x0000);
                r.setDE(0xff56);
                r.setHL(0x000d);
                gpu.getOamPalette().getPalette(0)[0] = 0x7f00;
                gpu.getOamPalette().setByte(0xff6a, 1);
            } else {
                r.setAF(0x01b0);
                r.setBC(0x0013);
                r.setDE(0x00d8);
                r.setHL(0x014d);
            }
            r.setSP(0xfffe);
            r.setPC(0x0100);
        }
    }

    public void init(EventBus eventBus, SerialEndpoint serialEndpoint, Console console) {
        this.console = console;
        if (console != null) {
            console.setGameboy(this);
        }

        joypad.init(eventBus);
        display.init(eventBus);
        sound.init(eventBus);
        serialPort.init(serialEndpoint);
    }

    public void run() {
        doStop = false;
        while (!doStop) {
            if (doPause) {
                haltIfNeeded();
            }
            tick();
        }
    }

    private synchronized void haltIfNeeded() {
        paused = true;
        notifyAll();
        while (doPause && !doStop) {
            try {
                wait(10);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        paused = false;
    }

    public synchronized void stop() {
        doStop = true;
        notifyAll();
    }

    public void tick() {
        Gpu.Mode newMode = tickSubsystems();
        if (newMode != null) {
            hdma.onGpuUpdate(newMode);
        }

        if (!lcdDisabled && !gpu.isLcdEnabled()) {
            lcdDisabled = true;
            display.frameIsReady();
            hdma.onLcdSwitch(false);
        } else if (newMode == Gpu.Mode.VBlank) {
            requestedScreenRefresh = true;
            display.frameIsReady();
        }

        if (lcdDisabled && gpu.isLcdEnabled()) {
            lcdDisabled = false;
            hdma.onLcdSwitch(true);
        } else if (requestedScreenRefresh && newMode == Gpu.Mode.OamSearch) {
            requestedScreenRefresh = false;
        }
        if (console != null) {
            console.tick();
        }
        tickListeners.forEach(Runnable::run);
    }

    private Gpu.Mode tickSubsystems() {
        timer.tick();
        if (hdma.isTransferInProgress()) {
            hdma.tick();
        } else {
            cpu.tick();
        }
        dma.tick();
        sound.tick();
        serialPort.tick();
        joypad.tick();
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

    public synchronized void pause() {
        doPause = true;
        while (!paused && !doStop) {
            try {
                wait(10);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public synchronized void resume() {
        doPause = false;
        notifyAll();
        while (paused && !doStop) {
            try {
                wait(10);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public boolean isPaused() {
        return paused;
    }

    @Override
    public Memento<Gameboy> saveToMemento() {
        return new GameboyMemento(rom.saveToMemento(), gpu.saveToMemento(), mmu.saveToMemento(), oamRam.saveToMemento(), cpu.saveToMemento(), interruptManager.saveToMemento(), timer.saveToMemento(), dma.saveToMemento(), hdma.saveToMemento(), display.saveToMemento(), sound.saveToMemento(), serialPort.saveToMemento(), joypad.saveToMemento(), speedMode.saveToMemento(), requestedScreenRefresh, lcdDisabled);
    }

    @Override
    public void restoreFromMemento(Memento<Gameboy> memento) {
        if (!(memento instanceof GameboyMemento mem)) {
            throw new IllegalArgumentException();
        }
        rom.restoreFromMemento(mem.cartridgeMemento());
        gpu.restoreFromMemento(mem.gpuMemento());
        mmu.restoreFromMemento(mem.mmuMemento());
        oamRam.restoreFromMemento(mem.oamRamMemento());
        cpu.restoreFromMemento(mem.cpuMemento());
        interruptManager.restoreFromMemento(mem.interruptManagerMemento());
        timer.restoreFromMemento(mem.timerMemento());
        dma.restoreFromMemento(mem.dmaMemento());
        hdma.restoreFromMemento(mem.hdmaMemento());
        display.restoreFromMemento(mem.displayMemento());
        sound.restoreFromMemento(mem.soundMemento());
        serialPort.restoreFromMemento(mem.serialPortMemento());
        joypad.restoreFromMemento(mem.joypadMemento());
        speedMode.restoreFromMemento(mem.speedModeMemento());
        requestedScreenRefresh = mem.requestScreenRefresh();
        lcdDisabled = mem.lcdDisabled();
    }

    private record GameboyMemento(Memento<Cartridge> cartridgeMemento, Memento<Gpu> gpuMemento, Memento<Mmu> mmuMemento,
                                  Memento<Ram> oamRamMemento, Memento<Cpu> cpuMemento,
                                  Memento<InterruptManager> interruptManagerMemento, Memento<Timer> timerMemento,
                                  Memento<Dma> dmaMemento, Memento<Hdma> hdmaMemento, Memento<Display> displayMemento,
                                  Memento<Sound> soundMemento, Memento<SerialPort> serialPortMemento,
                                  Memento<Joypad> joypadMemento, Memento<SpeedMode> speedModeMemento,
                                  boolean requestScreenRefresh, boolean lcdDisabled) implements Memento<Gameboy> {
    }
}
