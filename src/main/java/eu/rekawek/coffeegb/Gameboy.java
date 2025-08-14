package eu.rekawek.coffeegb;

import eu.rekawek.coffeegb.controller.Joypad;
import eu.rekawek.coffeegb.cpu.Cpu;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.cpu.SpeedMode;
import eu.rekawek.coffeegb.debug.Console;
import eu.rekawek.coffeegb.events.EventBus;
import eu.rekawek.coffeegb.events.EventBusImpl;
import eu.rekawek.coffeegb.gpu.Display;
import eu.rekawek.coffeegb.gpu.Gpu;
import eu.rekawek.coffeegb.gpu.VRamTransfer;
import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;
import eu.rekawek.coffeegb.memory.*;
import eu.rekawek.coffeegb.memory.cart.Cartridge;
import eu.rekawek.coffeegb.memory.cart.Rom;
import eu.rekawek.coffeegb.memory.cart.battery.MemoryBattery;
import eu.rekawek.coffeegb.serial.SerialEndpoint;
import eu.rekawek.coffeegb.serial.SerialPort;
import eu.rekawek.coffeegb.sgb.Background;
import eu.rekawek.coffeegb.sgb.SuperGameboy;
import eu.rekawek.coffeegb.sound.Sound;
import eu.rekawek.coffeegb.timer.Timer;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Gameboy implements Runnable, Serializable, Originator<Gameboy>, Closeable {

    public static final int TICKS_PER_SEC = 4_194_304;

    // 60 frames per second
    public static final int TICKS_PER_FRAME = Gameboy.TICKS_PER_SEC / 60;

    private final Cartridge cartridge;

    private final BiosShadow biosShadow;

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

    private final SuperGameboy superGameboy;

    private final EventBus sgbBus;

    private final Background background;

    private final VRamTransfer vRamTransfer;

    private transient Console console;

    private transient volatile boolean doStop;

    private final List<Runnable> tickListeners = new ArrayList<>();

    private boolean requestedScreenRefresh;

    private boolean lcdDisabled;

    private transient volatile boolean doPause;

    private transient volatile boolean paused;

    public Gameboy(Rom rom) {
        this(new GameboyConfiguration(rom));
    }

    public Gameboy(GameboyConfiguration configuration) {
        boolean gbc = configuration.gameboyType == GameboyType.CGB;
        boolean sgb = configuration.gameboyType == GameboyType.SGB;

        speedMode = new SpeedMode(gbc);
        interruptManager = new InterruptManager(gbc);
        timer = new Timer(interruptManager, speedMode);
        mmu = new Mmu(gbc);
        display = new Display(gbc);

        sgbBus = new EventBusImpl();
        vRamTransfer = new VRamTransfer(sgbBus);
        superGameboy = new SuperGameboy(sgbBus);
        background = new Background(sgbBus);
        oamRam = new Ram(0xfe00, 0x00a0);
        dma = new Dma(mmu, oamRam, speedMode);
        gpu = new Gpu(display, interruptManager, dma, oamRam, vRamTransfer, gbc);
        hdma = new Hdma(mmu);
        sound = new Sound(gbc);
        joypad = new Joypad(interruptManager, sgbBus, sgb);
        serialPort = new SerialPort(interruptManager, gbc, speedMode);

        if (configuration.batteryData != null) {
            cartridge = new Cartridge(configuration.rom, new MemoryBattery(configuration.batteryData));
        } else {
            cartridge = new Cartridge(configuration.rom, configuration.supportBatterySave);
        }
        Bios bios = new Bios(configuration.gameboyType);
        biosShadow = new BiosShadow(bios, cartridge);

        mmu.addAddressSpace(biosShadow);
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
        if (configuration.bootstrapMode == BootstrapMode.FAST_FORWARD) {
            while (cpu.getRegisters().getPC() != 0x100) {
                tick();
            }
        } else if (configuration.bootstrapMode == BootstrapMode.SKIP) {
            biosShadow.setByte(0xff50, 0);
            var r = cpu.getRegisters();
            if (gbc) {
                r.setAF(0x1180);
                r.setBC(0x0000);
                r.setDE(0xff56);
                r.setHL(0x000d);
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
        background.init(eventBus);
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
            vRamTransfer.frameIsReady();
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
        return new GameboyMemento(biosShadow.saveToMemento(), cartridge.saveToMemento(), gpu.saveToMemento(), mmu.saveToMemento(), oamRam.saveToMemento(), cpu.saveToMemento(), interruptManager.saveToMemento(), timer.saveToMemento(), dma.saveToMemento(), hdma.saveToMemento(), display.saveToMemento(), sound.saveToMemento(), serialPort.saveToMemento(), joypad.saveToMemento(), speedMode.saveToMemento(), superGameboy.saveToMemento(), requestedScreenRefresh, lcdDisabled);
    }

    @Override
    public void restoreFromMemento(Memento<Gameboy> memento) {
        if (!(memento instanceof GameboyMemento mem)) {
            throw new IllegalArgumentException();
        }
        biosShadow.restoreFromMemento(mem.biosShadowMemento());
        cartridge.restoreFromMemento(mem.cartridgeMemento());
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
        superGameboy.restoreFromMemento(mem.superGameboyMemento());
        requestedScreenRefresh = mem.requestScreenRefresh();
        lcdDisabled = mem.lcdDisabled();
    }

    @Override
    public void close() {
        cartridge.flushBattery();
    }

    private record GameboyMemento(Memento<BiosShadow> biosShadowMemento, Memento<Cartridge> cartridgeMemento,
                                  Memento<Gpu> gpuMemento, Memento<Mmu> mmuMemento,
                                  Memento<Ram> oamRamMemento, Memento<Cpu> cpuMemento,
                                  Memento<InterruptManager> interruptManagerMemento, Memento<Timer> timerMemento,
                                  Memento<Dma> dmaMemento, Memento<Hdma> hdmaMemento, Memento<Display> displayMemento,
                                  Memento<Sound> soundMemento, Memento<SerialPort> serialPortMemento,
                                  Memento<Joypad> joypadMemento, Memento<SpeedMode> speedModeMemento,
                                  Memento<SuperGameboy> superGameboyMemento,
                                  boolean requestScreenRefresh, boolean lcdDisabled) implements Memento<Gameboy> {
    }

    public enum BootstrapMode {
        NORMAL, FAST_FORWARD, SKIP,
    }

    public static class GameboyConfiguration {

        private final Rom rom;

        private GameboyType gameboyType;

        private BootstrapMode bootstrapMode = BootstrapMode.SKIP;

        private byte[] batteryData;

        private boolean supportBatterySave = true;

        public GameboyConfiguration(File romFile) throws IOException {
            this(new Rom(romFile));
        }

        public GameboyConfiguration(Rom rom) {
            this.rom = rom;
            if (rom.getGameboyColorFlag() == Rom.GameboyColorFlag.NON_CGB) {
                gameboyType = GameboyType.DMG;
            } else {
                gameboyType = GameboyType.CGB;
            }
        }

        public GameboyConfiguration setGameboyType(GameboyType gameboyType) {
            this.gameboyType = gameboyType;
            return this;
        }

        public GameboyConfiguration setBootstrapMode(BootstrapMode bootstrapMode) {
            this.bootstrapMode = bootstrapMode;
            return this;
        }

        public GameboyConfiguration setBatteryData(byte[] batteryData) {
            this.batteryData = batteryData;
            return this;
        }

        public GameboyConfiguration setSupportBatterySave(boolean supportBatterySave) {
            this.supportBatterySave = supportBatterySave;
            return this;
        }

        public Gameboy build() {
            return new Gameboy(this);
        }
    }
}
