package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.debug.Console;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.genie.Genie;
import eu.rekawek.coffeegb.core.gpu.*;
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint;
import eu.rekawek.coffeegb.core.ir.InfraredPort;
import eu.rekawek.coffeegb.core.joypad.Joypad;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;
import eu.rekawek.coffeegb.core.memory.*;
import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.MemoryBattery;
import eu.rekawek.coffeegb.core.memory.cart.rtc.SystemTimeSource;
import eu.rekawek.coffeegb.core.memory.cart.rtc.TimeSource;
import eu.rekawek.coffeegb.core.rumble.CodeBreakerRumble;
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

    // Once the CGB speed-switch countdown releases the CPU, the clock mux needs
    // two final master ticks to settle. The PPU and independently clocked
    // peripherals continue, but CPU, timer and DMA clocks remain held.
    static final int SPEED_SWITCH_TAIL_TICKS = 2;

    // Observed CGB timing uses the longer path on the other normal-speed
    // CPU/PPU half-phase.
    static final int LONG_SPEED_SWITCH_TAIL_TICKS = 8;

    // A retained HBlank VRAM-DMA mode latch takes an intermediate path on the
    // short clock-mux half-phase. The ordinary long half-phase still wins when
    // both conditions coincide.
    static final int HBLANK_SPEED_SWITCH_TAIL_TICKS = 7;

    // The native CGB boot ROM hands control to the cartridge before its final
    // peripheral-clock handoff has propagated. The CPU is already at $0100 while
    // the serial port and PPU finish these independently clocked ticks.
    static final int CGB_BOOT_HANDOFF_TICKS = 12;

    // A pending HBlank DMA request retains the prefetched STOP pipeline slot across
    // a speed switch. Its arbiter releases the CPU two master ticks after the normal
    // clock-mux tail, keeping the resumed instruction phase aligned with the PPU.
    static final int PENDING_HBLANK_SPEED_SWITCH_ALIGNMENT_TICKS = 2;

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

    private final InfraredPort infraredPort;

    private final CodeBreakerRumble codeBreakerRumble;

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

    private int speedSwitchTailTicks;

    private boolean speedSwitchClockPhaseShifted;

    private boolean blankCgbBootTilePending;

    private boolean clearBootTilemapPending;

    private boolean clearCgbBootOamShadowPending;

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
        CartridgeProperties cartridgeProperties = configuration.rom.getCartridgeProperties();
        blankCgbBootTilePending = cartridgeProperties.has(
                CartridgeProperties.Feature.BLANK_CGB_BOOT_TILE);
        clearBootTilemapPending = cartridgeProperties.has(
                CartridgeProperties.Feature.CLEAR_BOOT_TILEMAP);
        clearCgbBootOamShadowPending = cartridgeProperties.has(
                CartridgeProperties.Feature.CLEAR_CGB_BOOT_OAM_SHADOW);

        boolean legacySpeedSwitchRequired = cartridgeProperties.has(
                CartridgeProperties.Feature.LEGACY_SPEED_SWITCH);
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
        mmu.setGpu(gpu);
        statRegister.init(gpu);
        hdma = new Hdma(getAddressSpace(), speedMode);
        sound = new Sound(timer, speedMode, gbc);
        joypad = new Joypad(interruptManager, sgbBus, sgb);
        serialPort = new SerialPort(interruptManager, gbc, speedMode);
        infraredPort = new InfraredPort(gbc, speedMode);
        codeBreakerRumble = new CodeBreakerRumble();
        mmu.setCodeBreakerRumble(codeBreakerRumble);

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

        cpu = new Cpu(new DmaCpuAddressSpace(getAddressSpace(), dma, gbc,
                cartridgeProperties.has(CartridgeProperties.Feature.DMA_BLOCKED_READS_RETURN_FF)),
                interruptManager, gpu, speedMode, display, timer);

        interruptManager.disableInterrupts(false);
        if (configuration.bootstrapMode != BootstrapMode.SKIP) {
            // at power-on the LCD is off; the boot ROM enables it, anchoring the PPU
            // line grid to that write; the CGB divider phase accounts for the boot
            // ROM's accurately paced HDMA setup. Later revisions start 10 T into the
            // divider period. Revision 0 does not take the handoff path, so its
            // divider preset includes the equivalent 12-T offset: 536 T, which is
            // 512 T at its first test read after three fewer NOPs
            // (boot_div-cgbABCDE, boot_div-cgb0).
            timer.presetDiv(gbc ? (configuration.cgb0Revision
                    ? 524 + CGB_BOOT_HANDOFF_TICKS : 10) : 4);
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
            if (gbc && !configuration.cgb0Revision && cpu.getRegisters().getPC() == 0x100) {
                for (int i = 0; i < CGB_BOOT_HANDOFF_TICKS; i++) {
                    serialPort.tick();
                    gpu.tick();
                    statRegister.tick();
                }
            }
            bootTimedOut = cpu.getRegisters().getPC() != 0x100;
        }
        if (bootTimedOut || configuration.bootstrapMode == BootstrapMode.SKIP) {
            // the Datel Action Replay's ASIC presents a valid CGB header to the console,
            // so the machine boots native-colour despite the dump's garbage flag byte
            applyPostBootState(configuration.rom.getGameboyColorFlag() == Rom.GameboyColorFlag.NON_CGB
                    && !cartridgeProperties.has(CartridgeProperties.Feature.DATEL_CGB_HEADER));
        }
        applyBootCompatibilityIfReady();
    }

    private void applyBootCompatibilityIfReady() {
        if (!biosShadow.isBootFinished()) {
            return;
        }
        if (blankCgbBootTilePending) {
            // This trainer treats tile 0x0A as blank but does not replace the CGB boot
            // logo residue in its 16 data bytes. Do not sanitize any other cartridge or
            // any other part of VRAM: boot-state-dependent software still sees hardware.
            for (int address = 0x80a0; address < 0x80b0; address++) {
                gpu.getVideoRam0().setByte(address, 0);
            }
            blankCgbBootTilePending = false;
        }
        if (clearBootTilemapPending) {
            // This emulator-targeted music player replaces its font tiles and writes
            // the visible strings, but never clears the boot logo's tile-map entries.
            // Period emulators launched it from a zeroed map, which is its intended UI.
            for (int address = 0x9800; address < 0xa000; address++) {
                gpu.getVideoRam0().setByte(address, 0);
            }
            clearBootTilemapPending = false;
        }
        if (clearCgbBootOamShadowPending) {
            // This early demo uses C000-C09F as its OAM shadow without initializing the
            // unused entries. The authentic CGB boot leaves cartridge scratch data there,
            // while boot-skipping emulators leave zeroes; clear only that shadow once.
            for (int address = 0xc000; address < 0xc0a0; address++) {
                mmu.setByte(address, 0);
            }
            clearCgbBootOamShadowPending = false;
        }
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
        init(eventBus, serialEndpoint, InfraredEndpoint.NULL_ENDPOINT, console);
    }

    public void init(EventBus eventBus, SerialEndpoint serialEndpoint,
                     InfraredEndpoint infraredEndpoint, Console console) {
        this.console = console;
        if (console != null) {
            console.setGameboy(this);
        }

        joypad.init(eventBus);
        display.init(eventBus);
        sound.init(eventBus);
        serialPort.init(serialEndpoint);
        infraredPort.init(eventBus, infraredEndpoint);
        codeBreakerRumble.init(eventBus);
        background.init(eventBus);
        sgbDisplay.init(eventBus);
        gameGenie.init(eventBus);
        cartridge.init(eventBus);
        eventBus.register(
                e -> requestWarmReset(((eu.rekawek.coffeegb.core.memory.cart.type.Datel.LaunchEvent) e).nonCgbGame),
                eu.rekawek.coffeegb.core.memory.cart.type.Datel.LaunchEvent.class);
        eventBus.register(
                e -> requestWarmReset(((eu.rekawek.coffeegb.core.memory.cart.type.SlMulticart.ResetEvent) e).nonCgbGame()),
                eu.rekawek.coffeegb.core.memory.cart.type.SlMulticart.ResetEvent.class);
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
        applyBootCompatibilityIfReady();
        if (newMode != null) {
            hdma.onGpuUpdate(newMode);
        }
        hdma.onGpuTiming(gpu.getLine(), gpu.getTicksInLine());
        cpu.latchHdmaHaltOpcode(hdma.isHaltRequestLatched());

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
        boolean speedSwitchTail = speedSwitchTailTicks > 0;
        dma.setCpuInterruptStackWrite(cpu.getState() == Cpu.State.IRQ_PUSH_1
                || cpu.getState() == Cpu.State.IRQ_PUSH_2);
        // STOP's CGB speed-switch delay pauses instruction execution, not the
        // timer clock domain. DIV/TIMA continue advancing after STOP resets DIV.
        if (!speedSwitchTail) {
            timer.tick();
        }
        sound.tickFrameSequencer();
        boolean deferFrameSequencerClock = sound.isFrameSequencerClockAfterCpu();
        if (!deferFrameSequencerClock) {
            sound.commitFrameSequencerClock();
        }
        if (speedSwitchTail) {
            speedSwitchTailTicks--;
            if (speedSwitchTailTicks == 0) {
                hdma.onSpeedSwitchComplete();
            }
        } else if (speedSwitching) {
            // A CGB speed switch pauses instruction execution and VRAM DMA while
            // the independent timer and PPU clocks continue running.
            cpu.tick();
            if (!cpu.isSpeedSwitching()) {
                // The first normal-to-double switch can reach the mux on either
                // observed normal-speed half-phase. The $20000-clock countdown is
                // divisible by four, so the independently running PPU still exposes
                // the STOP entry phase here. With LCD off there is no phase to retain.
                boolean longClockMuxPhase = speedMode.getSpeedMode() == 2
                        && !speedSwitchClockPhaseShifted
                        && gpu.isLcdEnabled()
                        && (gpu.getTicksInLine() & 3) == 0;
                speedSwitchTailTicks = baseSpeedSwitchTailTicks(longClockMuxPhase,
                        hdma.holdsHblankSpeedSwitchTail())
                        + (speedMode.getSpeedMode() == 2 && speedSwitchClockPhaseShifted ? 1 : 0)
                        + (hdma.hasPendingHblankTransfer()
                        ? PENDING_HBLANK_SPEED_SWITCH_ALIGNMENT_TICKS : 0);
                if (speedMode.getSpeedMode() == 1) {
                    speedSwitchClockPhaseShifted = true;
                }
                if (speedSwitchTailTicks <= 0) {
                    hdma.onSpeedSwitchComplete();
                }
            }
        } else if (hdma.isTransferInProgress()) {
            if (cpu.getState() == Cpu.State.HALTED
                    || cpu.getState() == Cpu.State.STOPPED) {
                // HBlank DMA is suspended while the CPU clock is halted or
                // stopped. Keep ticking the CPU so an interrupt or asserted joypad
                // line can wake it; the HDMA request is restored according to the
                // request level captured when HALT was entered.
                if (cpu.getState() == Cpu.State.STOPPED) {
                    hdma.onStoppedCpuRequest();
                }
                cpu.tick();
            } else if (hdma.yieldsSpeedSwitchWakeRequestToCpu()) {
                // A mode-3-to-HBlank edge immediately after a speed switch can
                // rephase arbitration onto the CPU half-cycle. Let the opcode at
                // that boundary finish before granting the pending DMA burst.
                cpu.tick();
                if (!cpu.hasInFlightInstructionForHdma()) {
                    hdma.onSpeedSwitchWakeCpuInstructionFinished();
                }
            } else if (hdma.yieldsToInterruptEntry()
                    && cpu.advancesInterruptEntryForHdma()) {
                // Once interrupt acceptance has won the arbitration slot, its stack
                // pushes finish before HDMA takes the bus. If the request won during
                // the retiring instruction, first advance its pending acceptance at
                // the following opcode boundary. This ordering is visible when the
                // DMA source is the top of that same stack.
                cpu.tick();
            } else if (hdma.yieldsToFetchedCpuInstruction(
                    cpu.hasFetchedInstructionForHdma())) {
                // A fetched instruction owns the CPU/HDMA arbitration slot until its
                // next opcode boundary. Its final double-speed HBlank read also keeps
                // the VRAM slot that was granted with the instruction.
                gpu.setCpuRetiringInstructionForHdma(true);
                try {
                    cpu.tick();
                } finally {
                    gpu.setCpuRetiringInstructionForHdma(false);
                }
                if (!cpu.hasFetchedInstructionForHdma()) {
                    hdma.onFetchedCpuInstructionFinished();
                }
            } else {
                // VRAM is not connected as a CGB VRAM-DMA source. Its first invalid
                // read slots expose the instruction bus left at the CPU's next PC.
                hdma.setCpuBusValue(cpu.getBusValueForHdma());
                cpu.prefetchOpcodeForHdma();
                if (hdma.tick() && hdma.yieldsCpuAfterBlock()) {
                    cpu.releaseHdmaPrefetchedOpcode();
                }
            }
        } else {
            cpu.tick();
        }
        if (!speedSwitching && cpu.isSpeedSwitching()) {
            sound.onSpeedSwitch();
            gpu.onSpeedSwitch();
            if (hdma.onSpeedSwitch()) {
                cpu.replaySpeedSwitchPaddingByte();
            }
        }
        hdma.onCpuHaltState(cpu.getState() == Cpu.State.HALTED);
        if (deferFrameSequencerClock) {
            sound.commitFrameSequencerClock();
        }
        if (timer.isDivResetPending()) {
            sound.tickFrameSequencer();
            sound.commitFrameSequencerClock();
            serialPort.onDivReset();
        }
        // OAM DMA is driven by the CPU clock domain. HALT pauses it after the
        // entry latency; STOP and a CGB speed switch pause it immediately.
        boolean halted = cpu.getState() == Cpu.State.HALTED;
        dma.setVramDmaBusSample(hdma.consumeSourceBusSample());
        dma.tick(halted || cpu.getState() == Cpu.State.STOPPED
                        || cpu.getState() == Cpu.State.SPEED_SWITCH || speedSwitchTail
                        || hdma.pausesOamDmaForSpeedSwitchBurst(),
                halted);
        sound.tick();
        serialPort.tick();
        infraredPort.tick();
        joypad.tick();
        // The HBlank request crosses from the PPU to the CPU arbiter while the CPU is
        // still allowed to finish the current machine cycle.
        hdma.advanceHblankRequest(cpu.hasInFlightWriteCycleForHdma(),
                cpu.hasInFlightInstructionForHdma(),
                cpu.winsInterruptEntryArbitrationForHdma());
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

    boolean isSpeedSwitchTailActive() {
        return speedSwitchTailTicks > 0;
    }

    static int baseSpeedSwitchTailTicks(boolean longClockMuxPhase,
                                        boolean hblankSpeedSwitchTail) {
        return longClockMuxPhase
                ? LONG_SPEED_SWITCH_TAIL_TICKS
                : hblankSpeedSwitchTail
                ? HBLANK_SPEED_SWITCH_TAIL_TICKS
                : SPEED_SWITCH_TAIL_TICKS;
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
        return new GameboyMemento(biosShadow.saveToMemento(), cartridge.saveToMemento(), gpu.saveToMemento(), statRegister.saveToMemento(), mmu.saveToMemento(), oamRam.saveToMemento(), cpu.saveToMemento(), interruptManager.saveToMemento(), timer.saveToMemento(), dma.saveToMemento(), hdma.saveToMemento(), display.saveToMemento(), sound.saveToMemento(), serialPort.saveToMemento(), infraredPort.saveToMemento(), codeBreakerRumble.saveToMemento(), joypad.saveToMemento(), speedMode.saveToMemento(), superGameboy.saveToMemento(), background.saveToMemento(), vRamTransfer.saveToMemento(), sgbDisplay.saveToMemento(), gameGenie.saveToMemento(), requestedScreenRefresh, lcdDisabled, lcdOffTicks, speedSwitchTailTicks, speedSwitchClockPhaseShifted, blankCgbBootTilePending, clearBootTilemapPending, clearCgbBootOamShadowPending);
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
        codeBreakerRumble.restoreFromMemento(mem.codeBreakerRumbleMemento());
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
        speedSwitchTailTicks = mem.speedSwitchTailTicks();
        speedSwitchClockPhaseShifted = mem.speedSwitchClockPhaseShifted();
        blankCgbBootTilePending = mem.blankCgbBootTilePending();
        clearBootTilemapPending = mem.clearBootTilemapPending();
        clearCgbBootOamShadowPending = mem.clearCgbBootOamShadowPending();
    }

    @Override
    public void close() {
        codeBreakerRumble.close();
        infraredPort.close();
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
                                  Memento<InfraredPort> infraredPortMemento,
                                  Memento<CodeBreakerRumble> codeBreakerRumbleMemento,
                                  Memento<Joypad> joypadMemento, Memento<SpeedMode> speedModeMemento,
                                  Memento<SuperGameboy> superGameboyMemento, Memento<Background> backgroundMemento,
                                  Memento<VRamTransfer> vRamTransferMemento, Memento<SgbDisplay> sgbDisplayMemento,
                                  Memento<Genie> genieMemento, boolean requestScreenRefresh,
                                  boolean lcdDisabled, int lcdOffTicks, int speedSwitchTailTicks,
                                  boolean speedSwitchClockPhaseShifted,
                                  boolean blankCgbBootTilePending,
                                  boolean clearBootTilemapPending,
                                  boolean clearCgbBootOamShadowPending) implements Memento<Gameboy> {
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
