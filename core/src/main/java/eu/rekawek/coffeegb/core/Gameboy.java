package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.debug.Console;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.genie.Genie;
import eu.rekawek.coffeegb.core.gpu.*;
import eu.rekawek.coffeegb.core.joypad.Joypad;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;
import eu.rekawek.coffeegb.core.memory.*;
import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.MemoryBattery;
import eu.rekawek.coffeegb.core.memory.cart.rtc.SystemTimeSource;
import eu.rekawek.coffeegb.core.memory.cart.rtc.TimeSource;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.core.serial.SerialPort;
import eu.rekawek.coffeegb.core.sgb.Background;
import eu.rekawek.coffeegb.core.sgb.SgbDisplay;
import eu.rekawek.coffeegb.core.sgb.SuperGameboy;
import eu.rekawek.coffeegb.core.sound.Sound;
import eu.rekawek.coffeegb.core.timer.Timer;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;

public class Gameboy implements Runnable, Serializable, Originator<Gameboy>, Closeable {

    public static final int TICKS_PER_SEC = 4_194_304;

    // 60 frames per second
    public static final int TICKS_PER_FRAME = Gameboy.TICKS_PER_SEC / 60;

    // Keep very short LCD-off VRAM rewrites on the previous panel image, but do not
    // hold a partial scanout until the next emulated refresh. Four scanlines are longer
    // than known sub-frame rewrites (A Bug's Life uses about 1100 ticks) while still
    // allowing a sustained LCD-off to replace a transition fragment before host paint.
    static final int LCD_OFF_BLANK_DELAY = 4 * 456;

    private final Cartridge cartridge;

    private final Cartridge slotCartridge;

    private final BiosShadow biosShadow;

    private final Gpu gpu;

    private final StatRegister statRegister;

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

    private final eu.rekawek.coffeegb.core.ir.InfraredPort infraredPort;

    private final Joypad joypad;

    private final SpeedMode speedMode;

    private final SuperGameboy superGameboy;

    private final EventBus sgbBus;

    private final Background background;

    private final VRamTransfer vRamTransfer;

    private final SgbDisplay sgbDisplay;

    private final Genie gameGenie;

    private transient Console console;

    private transient volatile boolean doStop;

    private boolean requestedScreenRefresh;

    private boolean lcdDisabled;

    private int lcdOffTicks;

    private boolean blankCgbBootTilePending;

    private transient volatile boolean doPause;

    private transient volatile boolean paused;

    public Gameboy(Rom rom) {
        this(new GameboyConfiguration(rom));
    }

    private final boolean gbc;

    public Gameboy(GameboyConfiguration configuration) {
        this.gbc = configuration.gameboyType == GameboyType.CGB;
        boolean gbc = this.gbc;
        boolean sgb = configuration.gameboyType == GameboyType.SGB;
        blankCgbBootTilePending = configuration.rom.requiresBlankCgbBootTile();

        boolean legacySpeedSwitchRequired = configuration.rom.isLegacySpeedSwitchRequired();
        speedMode = new SpeedMode(gbc, legacySpeedSwitchRequired);
        interruptManager = new InterruptManager(gbc);
        timer = new Timer(interruptManager, speedMode);
        mmu = new Mmu(gbc);
        display = new Display(gbc);
        gameGenie = new Genie(mmu, gbc);

        sgbBus = new EventBusImpl(null, null, false);
        sgbDisplay = new SgbDisplay(configuration.rom, sgbBus, sgb, configuration.displaySgbBorder);
        vRamTransfer = new VRamTransfer(sgbBus);
        superGameboy = new SuperGameboy(sgbBus);
        background = new Background(sgbBus);
        oamRam = new Ram(0xfe00, 0x00a0);
        dma = new Dma(getAddressSpace(), oamRam, speedMode);
        statRegister = new StatRegister(interruptManager);
        gpu = new Gpu(display, dma, oamRam, vRamTransfer, statRegister, gbc, speedMode);
        statRegister.init(gpu);
        hdma = new Hdma(getAddressSpace());
        sound = new Sound(timer, speedMode, gbc);
        joypad = new Joypad(interruptManager, sgbBus, sgb);
        serialPort = new SerialPort(interruptManager, timer, gbc, speedMode);
        infraredPort = new eu.rekawek.coffeegb.core.ir.InfraredPort(gbc, speedMode);

        if (configuration.batteryData != null) {
            cartridge = new Cartridge(configuration.rom, new MemoryBattery(configuration.batteryData),
                    configuration.rtcTimeSource);
        } else {
            cartridge = new Cartridge(configuration.rom, configuration.supportBatterySave,
                    configuration.rtcTimeSource);
        }
        if (configuration.slotRom != null && cartridge.getDatel() != null) {
            // the game cartridge in the Action Replay's pass-through slot
            slotCartridge = new Cartridge(configuration.slotRom, configuration.supportBatterySave);
            cartridge.getDatel().setSlotCartridge(slotCartridge.getMemoryController(),
                    configuration.slotRom.getGameboyColorFlag() == Rom.GameboyColorFlag.NON_CGB);
        } else {
            slotCartridge = null;
        }
        Bios bios = new Bios(configuration.gameboyType);
        biosShadow = new BiosShadow(bios, cartridge);
        speedMode.setBiosShadow(biosShadow);
        mmu.setSpeedMode(speedMode);

        mmu.addAddressSpace(biosShadow);
        mmu.addAddressSpace(gpu);
        mmu.addAddressSpace(statRegister);
        mmu.addAddressSpace(joypad);
        mmu.addAddressSpace(interruptManager);
        mmu.addAddressSpace(serialPort);
        mmu.addAddressSpace(timer);
        mmu.addAddressSpace(dma);
        mmu.addAddressSpace(sound);

        if (gbc || legacySpeedSwitchRequired) {
            mmu.addAddressSpace(speedMode);
        }
        if (gbc) {
            mmu.addAddressSpace(hdma);
            mmu.addAddressSpace(infraredPort);
        }
        mmu.indexSpaces();
        mmu.setBusListener(cartridge.getSachenMmc());

        cpu = new Cpu(new DmaCpuAddressSpace(getAddressSpace(), dma, gbc),
                interruptManager, gpu, speedMode, display);

        interruptManager.disableInterrupts(false);
        if (configuration.bootstrapMode != BootstrapMode.SKIP) {
            // at power-on the LCD is off; the boot ROM enables it, anchoring the PPU
            // line grid to that write; the CGB divider phase accounts for the boot
            // ROM's accurately paced HDMA, and revision 0 adds another 512 T
            // (boot_div-cgbABCDE, boot_div-cgb0)
            timer.presetDiv(gbc ? (configuration.cgb0Revision ? 518 : 0xfffa) : 4);
            gpu.setByte(0xff40, 0x00);
        }
        boolean bootTimedOut = false;
        if (configuration.bootstrapMode == BootstrapMode.FAST_FORWARD) {
            // ~30 frames covers the DMG boot (~23.5M ticks) and the CGB boot (~13.1M)
            // with a wide margin; carts with a bad logo (unlicensed hardware that
            // tricks the boot ROM, corrupt dumps) lock the boot ROM up forever, and
            // then we fall back to the SKIP presets like a flashcart menu would
            long limit = 40_000_000L;
            while (cpu.getRegisters().getPC() != 0x100 && limit-- > 0) {
                tick();
            }
            bootTimedOut = cpu.getRegisters().getPC() != 0x100;
        }
        if (bootTimedOut || configuration.bootstrapMode == BootstrapMode.SKIP) {
            // the Datel Action Replay's ASIC presents a valid CGB header to the console,
            // so the machine boots native-colour despite the dump's garbage flag byte
            applyPostBootState(configuration.rom.getGameboyColorFlag() == Rom.GameboyColorFlag.NON_CGB
                    && !Cartridge.isDatel(configuration.rom));
        }
        applyBootVramCompatibilityIfReady();
    }

    private void applyBootVramCompatibilityIfReady() {
        if (!blankCgbBootTilePending || !biosShadow.isBootFinished()) {
            return;
        }
        // This trainer treats tile 0x0A as blank but does not replace the CGB boot
        // logo residue in its 16 data bytes. Do not sanitize any other cartridge or
        // any other part of VRAM: boot-state-dependent software still sees hardware.
        for (int address = 0x80a0; address < 0x80b0; address++) {
            gpu.getVideoRam0().setByte(address, 0);
        }
        blankCgbBootTilePending = false;
    }

    /**
     * Puts the machine into the state the boot ROM hands over: post-boot register presets,
     * boot ROM unmapped, LCD enabled. Used by the SKIP/timed-out boot and by cartridge
     * hardware that pulses the console's reset line (the Datel Action Replay game launch).
     */
    private void applyPostBootState(boolean nonCgbCart) {
        speedMode.setDmgCompat(gbc && nonCgbCart);
        biosShadow.setByte(0xff50, 0);
        // DIV counter value at PC=0x0100 after the boot ROM (mooneye boot_div tests)
        timer.presetDiv(gbc ? 0xb644 : 0xabcc);
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

    // a cartridge-requested console reset (the Datel launch pulls the cart bus's /RES pin);
    // applied at the top of the next tick
    private transient volatile boolean warmResetNonCgbCart;

    private transient volatile boolean warmResetRequested;

    /** Cartridge hardware pulsing the console reset line (Datel Action Replay launch). */
    public void requestWarmReset(boolean nonCgbCart) {
        warmResetNonCgbCart = nonCgbCart;
        warmResetRequested = true;
    }

    private void applyWarmReset() {
        // the boot ROM leaves the LCD running with the DMG-compatible defaults
        interruptManager.disableInterrupts(false);
        mmu.setByte(0xffff, 0x00);
        mmu.setByte(0xff0f, 0xe1);
        gpu.setByte(0xff40, 0x91);
        gpu.setByte(0xff42, 0x00);
        gpu.setByte(0xff43, 0x00);
        gpu.setByte(0xff45, 0x00);
        gpu.setByte(0xff47, 0xfc);
        mmu.setByte(0xff4a, 0x00);
        mmu.setByte(0xff4b, 0x00);
        applyPostBootState(warmResetNonCgbCart);
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
        infraredPort.init(eventBus);
        background.init(eventBus);
        sgbDisplay.init(eventBus);
        gameGenie.init(eventBus);
        cartridge.init(eventBus);
        eventBus.register(
                e -> requestWarmReset(((eu.rekawek.coffeegb.core.memory.cart.type.Datel.LaunchEvent) e).nonCgbGame),
                eu.rekawek.coffeegb.core.memory.cart.type.Datel.LaunchEvent.class);
    }

    /**
     * Swaps the link-port device on a running emulation - e.g. plugging in the Game Boy
     * Printer without a reset. Safe to call between ticks (same thread as {@link #tick()}).
     */
    public void setSerialEndpoint(SerialEndpoint serialEndpoint) {
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

    /**
     * @return true if there was a new frame emitted in this tick
     */
    public boolean tick() {
        if (warmResetRequested) {
            warmResetRequested = false;
            applyWarmReset();
        }
        boolean result = false;

        Mode newMode = tickSubsystems();
        applyBootVramCompatibilityIfReady();
        if (newMode != null) {
            hdma.onGpuUpdate(newMode);
        }

        boolean stopFrameBlanked = cpu.consumeStopFrameBlankRequest();
        if (stopFrameBlanked) {
            display.blankFrameForStop();
            result = true;
        }

        if (!gpu.isLcdEnabled()) {
            if (!lcdDisabled) {
                lcdDisabled = true;
                hdma.onLcdSwitch(false);
                lcdOffTicks = 0;
            }
            // A very short LCD-off (a common way to squeeze in a VRAM rewrite, e.g. the
            // A Bug's Life intro) keeps the last panel image. Once the off period outlives
            // that settling window, publish the blank state immediately. Otherwise a
            // partial scanout immediately before LCD-off is held for a complete host frame
            // (Konami GB Collection Vol. 1, issue #127). Subsequent blank refreshes retain
            // the normal cadence.
            lcdOffTicks++;
            if (lcdOffTicks == LCD_OFF_BLANK_DELAY
                    || lcdOffTicks >= LCD_OFF_BLANK_DELAY + TICKS_PER_FRAME) {
                if (lcdOffTicks >= LCD_OFF_BLANK_DELAY + TICKS_PER_FRAME) {
                    lcdOffTicks = LCD_OFF_BLANK_DELAY;
                }
                display.blankFrame();
                result = true;
            }
        } else {
            if (lcdDisabled) {
                lcdDisabled = false;
                hdma.onLcdSwitch(true);
            }
            if (!stopFrameBlanked && newMode == Mode.VBlank) {
                requestedScreenRefresh = true;
                display.frameIsReady();
                vRamTransfer.frameIsReady();
                result = true;
            } else if (requestedScreenRefresh && newMode == Mode.OamSearch) {
                requestedScreenRefresh = false;
            }
        }
        if (console != null) {
            console.tick();
        }
        return result;
    }

    private Mode tickSubsystems() {
        boolean speedSwitching = cpu.isSpeedSwitching();
        if (!speedSwitching) {
            timer.tick();
        }
        sound.tickFrameSequencer();
        if (speedSwitching) {
            // A CGB speed switch pauses the CPU and DIV, but the PPU keeps running.
            cpu.tick();
            if (hdma.isTransferInProgress()) {
                hdma.tick();
            }
        } else if (hdma.isTransferInProgress()) {
            hdma.tick();
        } else {
            cpu.tick();
        }
        if (timer.isDivResetPending()) {
            sound.tickFrameSequencer();
        }
        dma.tick();
        sound.tick();
        serialPort.tick();
        infraredPort.tick();
        joypad.tick();
        Mode mode = gpu.tick();
        statRegister.tick();
        return mode;
    }

    public AddressSpace getAddressSpace() {
        return gameGenie;
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

    /**
     * Held-button state, snapshotted separately from the memento by rollback netplay so a held
     * button survives a rebase (the joypad deliberately keeps it out of the memento).
     */
    public java.util.Set<eu.rekawek.coffeegb.core.joypad.Button> getPressedButtons() {
        return joypad.getPressedButtons();
    }

    public void setPressedButtons(java.util.Collection<eu.rekawek.coffeegb.core.joypad.Button> pressed) {
        joypad.setPressedButtons(pressed);
    }

    @Override
    public Memento<Gameboy> saveToMemento() {
        return new GameboyMemento(biosShadow.saveToMemento(), cartridge.saveToMemento(), gpu.saveToMemento(), statRegister.saveToMemento(), mmu.saveToMemento(), oamRam.saveToMemento(), cpu.saveToMemento(), interruptManager.saveToMemento(), timer.saveToMemento(), dma.saveToMemento(), hdma.saveToMemento(), display.saveToMemento(), sound.saveToMemento(), serialPort.saveToMemento(), infraredPort.saveToMemento(), joypad.saveToMemento(), speedMode.saveToMemento(), superGameboy.saveToMemento(), background.saveToMemento(), vRamTransfer.saveToMemento(), sgbDisplay.saveToMemento(), gameGenie.saveToMemento(), requestedScreenRefresh, lcdDisabled, lcdOffTicks, blankCgbBootTilePending);
    }

    @Override
    public void restoreFromMemento(Memento<Gameboy> memento) {
        if (!(memento instanceof GameboyMemento mem)) {
            throw new IllegalArgumentException();
        }
        biosShadow.restoreFromMemento(mem.biosShadowMemento());
        cartridge.restoreFromMemento(mem.cartridgeMemento());
        gpu.restoreFromMemento(mem.gpuMemento());
        statRegister.restoreFromMemento(mem.statRegisterMemento());
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
        infraredPort.restoreFromMemento(mem.infraredPortMemento());
        joypad.restoreFromMemento(mem.joypadMemento());
        speedMode.restoreFromMemento(mem.speedModeMemento());
        superGameboy.restoreFromMemento(mem.superGameboyMemento());
        background.restoreFromMemento(mem.backgroundMemento());
        vRamTransfer.restoreFromMemento(mem.vRamTransferMemento());
        sgbDisplay.restoreFromMemento(mem.sgbDisplayMemento());
        gameGenie.restoreFromMemento(mem.genieMemento());
        requestedScreenRefresh = mem.requestScreenRefresh();
        lcdDisabled = mem.lcdDisabled();
        lcdOffTicks = mem.lcdOffTicks();
        blankCgbBootTilePending = mem.blankCgbBootTilePending();
    }

    @Override
    public void close() {
        cartridge.flushBattery();
        if (slotCartridge != null) {
            slotCartridge.flushBattery();
        }
        sgbBus.close();
    }

    private record GameboyMemento(Memento<BiosShadow> biosShadowMemento, Memento<Cartridge> cartridgeMemento,
                                  Memento<Gpu> gpuMemento, Memento<StatRegister> statRegisterMemento,
                                  Memento<Mmu> mmuMemento, Memento<Ram> oamRamMemento, Memento<Cpu> cpuMemento,
                                  Memento<InterruptManager> interruptManagerMemento, Memento<Timer> timerMemento,
                                  Memento<Dma> dmaMemento, Memento<Hdma> hdmaMemento, Memento<Display> displayMemento,
                                  Memento<Sound> soundMemento, Memento<SerialPort> serialPortMemento,
                                  Memento<eu.rekawek.coffeegb.core.ir.InfraredPort> infraredPortMemento,
                                  Memento<Joypad> joypadMemento, Memento<SpeedMode> speedModeMemento,
                                  Memento<SuperGameboy> superGameboyMemento, Memento<Background> backgroundMemento,
                                  Memento<VRamTransfer> vRamTransferMemento, Memento<SgbDisplay> sgbDisplayMemento,
                                  Memento<Genie> genieMemento, boolean requestScreenRefresh,
                                  boolean lcdDisabled, int lcdOffTicks,
                                  boolean blankCgbBootTilePending) implements Memento<Gameboy> {
    }

    public enum BootstrapMode {
        NORMAL, FAST_FORWARD, SKIP,
    }

    public static class GameboyConfiguration {

        private final Rom rom;

        private GameboyType gameboyType;

        private BootstrapMode bootstrapMode = BootstrapMode.SKIP;

        private Rom slotRom;

        private byte[] batteryData;

        private boolean supportBatterySave = true;

        private boolean displaySgbBorder = true;

        private boolean cgb0Revision;

        private TimeSource rtcTimeSource = new SystemTimeSource();

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

        /**
         * Emulates the CGB revision 0 boot timing instead of revisions A-E
         * (mooneye boot_div-cgb0).
         */
        public GameboyConfiguration setCgb0Revision(boolean cgb0Revision) {
            this.cgb0Revision = cgb0Revision;
            return this;
        }

        public GameboyType getGameboyType() {
            return gameboyType;
        }

        public GameboyConfiguration setDisplaySgbBorder(boolean displaySgbBorder) {
            this.displaySgbBorder = displaySgbBorder;
            return this;
        }

        public GameboyConfiguration setBootstrapMode(BootstrapMode bootstrapMode) {
            this.bootstrapMode = bootstrapMode;
            return this;
        }

        /** The game cartridge inserted in an Action Replay's pass-through slot. */
        public GameboyConfiguration setSlotRom(Rom slotRom) {
            this.slotRom = slotRom;
            return this;
        }

        public BootstrapMode getBootstrapMode() {
            return bootstrapMode;
        }

        public GameboyConfiguration setBatteryData(byte[] batteryData) {
            this.batteryData = batteryData;
            return this;
        }

        public GameboyConfiguration setSupportBatterySave(boolean supportBatterySave) {
            this.supportBatterySave = supportBatterySave;
            return this;
        }

        public GameboyConfiguration setRtcTimeSource(TimeSource rtcTimeSource) {
            this.rtcTimeSource = rtcTimeSource;
            return this;
        }

        /**
         * A copy of this configuration that skips the boot sequence, for building a
         * Gameboy whose state is immediately overwritten by a memento restore. With
         * {@link BootstrapMode#FAST_FORWARD} the constructor would emulate the whole
         * boot ROM (tens of milliseconds) only to have every bit of that state
         * discarded by the restore.
         */
        public GameboyConfiguration forRestore() {
            GameboyConfiguration copy = new GameboyConfiguration(rom);
            copy.gameboyType = gameboyType;
            copy.bootstrapMode = BootstrapMode.SKIP;
            copy.batteryData = batteryData;
            copy.supportBatterySave = supportBatterySave;
            copy.displaySgbBorder = displaySgbBorder;
            copy.cgb0Revision = cgb0Revision;
            copy.rtcTimeSource = rtcTimeSource;
            return copy;
        }

        public Gameboy build() {
            return new Gameboy(this);
        }

        public Rom getRom() {
            return rom;
        }
    }
}
