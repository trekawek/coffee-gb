package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.debug.Console;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.genie.Genie;
import eu.rekawek.coffeegb.core.gpu.*;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileIdentity;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.ir.InfraredEndpoint;
import eu.rekawek.coffeegb.core.ir.InfraredPort;
import eu.rekawek.coffeegb.core.joypad.Joypad;
import eu.rekawek.coffeegb.core.joypad.PlayerInputSource;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;
import eu.rekawek.coffeegb.core.memory.*;
import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryFlush;
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryStorage;
import eu.rekawek.coffeegb.core.memory.cart.battery.MemoryBattery;
import eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock;
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
import java.util.concurrent.CancellationException;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

public class Gameboy implements Runnable, StatefulComponent<Gameboy>, Closeable {

    /** @deprecated Use the owning Gameboy's {@link #getClockSpec()}. */
    @Deprecated
    public static final int TICKS_PER_SEC = 4_194_304;

    /** @deprecated Use the owning Gameboy's {@link #getClockSpec()}. */
    @Deprecated
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

    // A pending HBlank request advances the resumed CPU phase when no OAM transfer
    // owns the shared DMA clock. An active OAM transfer instead retains the delayed
    // clock-mux hand-off used before the switch.
    static final int PENDING_HBLANK_SPEED_SWITCH_ADVANCE_TICKS = 4;

    static final int OAM_DMA_HBLANK_SPEED_SWITCH_DELAY_TICKS = 2;

    // A granted HBlank burst can finish while the CPU speed-switch countdown is still
    // running. Its completed bus hand-off removes five ticks from the retained tail.
    static final int COMPLETED_HBLANK_SPEED_SWITCH_ADVANCE_TICKS = 5;

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

    private final HardwareProfile hardwareProfile;

    private final ClockSpec clockSpec;

    public Gameboy(GameboyConfiguration configuration) {
        this.hardwareProfile = HardwareProfileRegistry.requireRegistered(configuration.hardwareProfile);
        if (configuration.bootstrapMode != BootstrapMode.SKIP
                && !Bios.hasBundledBootRom(hardwareProfile)) {
            throw new IllegalArgumentException(
                    "Profile " + hardwareProfile.id()
                            + " has no bundled boot ROM; select skip bootstrap before starting the session");
        }
        this.clockSpec = hardwareProfile.clockSpec();
        this.gbc = hardwareProfile.capabilities().cgbMode();
        boolean gbc = this.gbc;
        boolean sgb = hardwareProfile.capabilities().superGameboyCommands();
        boolean cgb0Revision = hardwareProfile == HardwareProfileRegistry.CGB0;
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
        mmu = new Mmu(gbc, cgb0Revision);
        display = new Display(gbc);
        gameGenie = new Genie(mmu, gbc);

        sgbBus = new EventBusImpl(null, null, false);
        sgbDisplay = new SgbDisplay(
                configuration.rom, sgbBus, sgb, configuration.isDisplaySgbBorder());
        vRamTransfer = new VRamTransfer(sgbBus);
        superGameboy = new SuperGameboy(sgbBus);
        background = new Background(sgbBus);
        oamRam = new Ram(0xfe00, 0x00a0);
        dma = new Dma(getAddressSpace(), oamRam, speedMode);
        statRegister = new StatRegister(interruptManager);
        gpu = new Gpu(display, dma, oamRam, vRamTransfer, statRegister, gbc, speedMode,
                configuration.mealybugDmgBlob);
        mmu.setGpu(gpu);
        statRegister.init(gpu);
        hdma = new Hdma(getAddressSpace(), speedMode);
        sound = new Sound(timer, speedMode, gbc, clockSpec);
        joypad = new Joypad(interruptManager, sgbBus, sgb, configuration.playerInputSource);
        serialPort = new SerialPort(interruptManager, gbc, speedMode);
        infraredPort = new InfraredPort(gbc, speedMode);
        codeBreakerRumble = new CodeBreakerRumble();
        if (configuration.codeBreakerRumble) {
            // The CodeBreaker is an external pass-through accessory, not part of every
            // cartridge. Watching FFFE globally makes ordinary HRAM initialization look
            // like a motor request and causes false host rumble.
            mmu.setCodeBreakerRumble(codeBreakerRumble);
        }

        if (configuration.batteryData != null) {
            cartridge = new Cartridge(configuration.rom, new MemoryBattery(configuration.batteryData),
                    configuration.rtcTimeSource, clockSpec);
        } else {
            cartridge = new Cartridge(
                    configuration.rom,
                    configuration.supportBatterySave,
                    configuration.batteryStorage,
                    configuration.rtcTimeSource,
                    clockSpec);
        }
        if (configuration.slotRom != null && cartridge.getDatel() != null) {
            // the game cartridge in the Action Replay's pass-through slot
            slotCartridge = new Cartridge(
                    configuration.slotRom,
                    configuration.supportBatterySave,
                    configuration.slotBatteryStorage,
                    configuration.rtcTimeSource,
                    clockSpec);
            cartridge.getDatel().setSlotCartridge(slotCartridge.getMemoryController(),
                    configuration.slotRom.getGameboyColorFlag() == Rom.GameboyColorFlag.NON_CGB);
        } else {
            slotCartridge = null;
        }
        Bios bios = new Bios(hardwareProfile, configuration.bootstrapMode != BootstrapMode.SKIP);
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
        if (cartridgeProperties.has(
                CartridgeProperties.Feature.CLEAR_DUNGEON_WARRIOR_RENDERER_COUNT)) {
            // This prototype assumes emulator-style zeroed WRAM for its renderer-record
            // count at C0BC. With real power-on garbage its record walker mutates stale
            // entries and adjacent renderer work data, producing persistent wall/floor
            // seams. Keep the hardware-like WRAM pattern everywhere else.
            mmu.setByte(0xc0bc, 0);
        }
        if (cartridgeProperties.has(
                CartridgeProperties.Feature.CLEAR_DICTIONARY_JOYPAD_STATE)) {
            // This title leaves its five-byte joypad edge/repeat state uninitialized at
            // D8D0-D8D4. Hardware-like WRAM garbage can therefore look like a held Start
            // button and skip the title screen. Clear only the private input state the ROM
            // assumes starts at zero, retaining the power-on pattern everywhere else.
            for (int address = 0xd8d0; address <= 0xd8d4; address++) {
                mmu.setByte(address, 0);
            }
        }
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
            timer.presetDiv(hardwareProfile.bootSpec().authenticDivPreset());
            gpu.setByte(0xff40, 0x00);
        }
        boolean bootTimedOut = false;
        if (configuration.bootstrapMode == BootstrapMode.FAST_FORWARD) {
            // ~30 frames covers the DMG boot (~23.5M ticks) and the CGB boot (~13.1M)
            // with a wide margin; carts with a bad logo (unlicensed hardware that
            // tricks the boot ROM, corrupt dumps) lock the boot ROM up forever, and
            // then we fall back to the SKIP presets like a flashcart menu would
            long limit = 40_000_000L;
            if (configuration.bootCancellation.getAsBoolean()) {
                throw new CancellationException("Boot cancelled");
            }
            while (cpu.getRegisters().getPC() != 0x100 && limit-- > 0) {
                tick();
                // Controller ROM preparation may be superseded by a newer load request.
                // Poll sparsely so cancellation is prompt without adding a branch to every
                // one of the 13-24 million boot ticks.
                if ((limit & 0x3fff) == 0 && configuration.bootCancellation.getAsBoolean()) {
                    throw new CancellationException("Boot cancelled");
                }
            }
            if (hardwareProfile.bootSpec().cgbBootHandoffTicks() > 0
                    && cpu.getRegisters().getPC() == 0x100) {
                for (int i = 0; i < hardwareProfile.bootSpec().cgbBootHandoffTicks(); i++) {
                    serialPort.tick();
                    gpu.tick();
                    statRegister.tick();
                }
            }
            bootTimedOut = cpu.getRegisters().getPC() != 0x100;
        }
        if (bootTimedOut || configuration.bootstrapMode == BootstrapMode.SKIP) {
            // Some unlicensed mappers transform the header only while the console boot ROM is
            // reading it. Bypassing (or abandoning) that ROM must complete the mapper-side
            // handshake before the CPU starts at the cartridge entry point.
            cartridge.skipBoot();
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
        timer.presetDiv(hardwareProfile.bootSpec().postBootDivPreset());
        var r = cpu.getRegisters();
        r.setAF(hardwareProfile.bootSpec().postBootAf());
        r.setBC(hardwareProfile.bootSpec().postBootBc());
        r.setDE(hardwareProfile.bootSpec().postBootDe());
        r.setHL(hardwareProfile.bootSpec().postBootHl());
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
        attachConsole(console);

        joypad.init(eventBus);
        display.init(eventBus);
        sound.init(eventBus);
        serialPort.init(serialEndpoint);
        infraredPort.setSerialEndpoint(serialEndpoint);
        infraredPort.init(eventBus, infraredEndpoint);
        codeBreakerRumble.init(eventBus);
        background.init(eventBus);
        sgbDisplay.init(eventBus);
        gameGenie.init(eventBus);
        cartridge.init(eventBus);
        if (slotCartridge != null) {
            slotCartridge.initBattery(eventBus);
        }
        eventBus.register(
                e -> requestWarmReset(((eu.rekawek.coffeegb.core.memory.cart.type.Datel.LaunchEvent) e).nonCgbGame),
                eu.rekawek.coffeegb.core.memory.cart.type.Datel.LaunchEvent.class);
        eventBus.register(
                e -> requestWarmReset(((eu.rekawek.coffeegb.core.memory.cart.type.SlMulticart.ResetEvent) e).nonCgbGame()),
                eu.rekawek.coffeegb.core.memory.cart.type.SlMulticart.ResetEvent.class);
    }

    /**
     * Attaches the optional debugger console after a staged session becomes the live owner.
     *
     * <p>Candidate sessions initialize with no console so a failed or not-yet-committed
     * replacement cannot temporarily steal commands from the running machine.
     */
    public void attachConsole(Console console) {
        this.console = console;
        if (console != null) {
            console.setGameboy(this);
        }
    }

    /**
     * Swaps the link-port device on a running emulation - e.g. plugging in the Game Boy
     * Printer without a reset. Safe to call between ticks (same thread as {@link #tick()}).
     */
    public void setSerialEndpoint(SerialEndpoint serialEndpoint) {
        serialPort.init(serialEndpoint);
        infraredPort.setSerialEndpoint(serialEndpoint);
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
        setCartridgeClockPaused(true);
        paused = true;
        notifyAll();
        try {
            while (doPause && !doStop) {
                wait(10);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            paused = false;
            setCartridgeClockPaused(false);
        }
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
        cartridge.tick();
        if (slotCartridge != null) {
            slotCartridge.tick();
        }
        boolean result = false;

        Mode newMode = tickSubsystems();
        applyBootCompatibilityIfReady();
        if (newMode != null) {
            hdma.onGpuUpdate(newMode);
        }
        hdma.onGpuTiming(gpu.getLine(), gpu.getTicksInLine(),
                gpu.isStatModeLatchRephasedBySpeedSwitch());
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
                    || lcdOffTicks >= LCD_OFF_BLANK_DELAY + clockSpec.controllerTicksPerFrame()) {
                if (lcdOffTicks >= LCD_OFF_BLANK_DELAY + clockSpec.controllerTicksPerFrame()) {
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
        statRegister.captureCpuStatReadPhase(cpu.isSynchronousHaltEntryStatPhase(),
                cpu.isAsynchronousHaltEntryStatPhase(),
                cpu.isOrdinaryHaltWakeStatPhase(),
                cpu.isOneCycleOrdinaryHaltWakeStatPhase());
        boolean mode0InterruptEdgeNextTick =
                statRegister.isMode0InterruptEdgeNextTick();
        statRegister.captureCpuInterruptReadPhase(
                cpu.getInterruptFlagReadMaskTicks(mode0InterruptEdgeNextTick),
                cpu.isMode0InterruptDispatchPhased(mode0InterruptEdgeNextTick),
                cpu.doesMode0InstructionWinInterruptAcceptance(
                        mode0InterruptEdgeNextTick));
        statRegister.publishFrameLyc0Mode2HandoffBeforeCpu();
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
                gpu.onSpeedSwitchComplete();
            }
        } else if (speedSwitching) {
            // A CGB speed switch pauses instruction execution while the independent
            // timer and PPU clocks continue running. A granted HBlank burst also
            // advances unless an active OAM transfer owns the shared DMA clock.
            cpu.tick();
            if (hdma.pausesOamDmaForSpeedSwitchBurst()
                    && !dma.isTransferInProgress()) {
                hdma.tick();
            }
            if (!cpu.isSpeedSwitching()) {
                // The first normal-to-double switch can reach the mux on either
                // observed normal-speed half-phase. The $20000-clock countdown is
                // divisible by four, so the independently running PPU still exposes
                // the STOP entry phase here. With LCD off there is no phase to retain.
                boolean longClockMuxPhase = speedMode.getSpeedMode() == 2
                        && !speedSwitchClockPhaseShifted
                        && gpu.isLcdEnabled()
                        && (gpu.getTicksInLine() & 3) == 0;
                int completedHblankBurstAdvance = hdma.completedHblankSpeedSwitchBurst()
                        ? COMPLETED_HBLANK_SPEED_SWITCH_ADVANCE_TICKS : 0;
                int pendingHblankAlignment = 0;
                if (hdma.alignsPendingHblankSpeedSwitchTail()) {
                    pendingHblankAlignment = dma.isTransferInProgress()
                            ? OAM_DMA_HBLANK_SPEED_SWITCH_DELAY_TICKS
                            : -PENDING_HBLANK_SPEED_SWITCH_ADVANCE_TICKS;
                }
                speedSwitchTailTicks = baseSpeedSwitchTailTicks(longClockMuxPhase,
                        hdma.holdsHblankSpeedSwitchTail())
                        - completedHblankBurstAdvance
                        + (speedMode.getSpeedMode() == 2 && speedSwitchClockPhaseShifted ? 1 : 0)
                        + pendingHblankAlignment;
                if (speedMode.getSpeedMode() == 1) {
                    speedSwitchClockPhaseShifted = true;
                }
                if (speedSwitchTailTicks <= 0) {
                    hdma.onSpeedSwitchComplete();
                    gpu.onSpeedSwitchComplete();
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
                if (!cpu.isCpuRequestSlotInProgressForHdma()) {
                    hdma.onSpeedSwitchWakeCpuInstructionFinished();
                }
            } else if (hdma.isInterruptEntryRequestOwner()
                    && cpu.canAdvanceInterruptEntryForHdma()) {
                // Once interrupt acceptance has won the arbitration slot, its stack
                // pushes finish before HDMA takes the bus. If the request won during
                // the retiring instruction, first advance its pending acceptance at
                // the following opcode boundary. This ordering is visible when the
                // DMA source is the top of that same stack.
                cpu.tick();
            } else {
                if (hdma.isCpuRequestUnresolved()) {
                    boolean cpuClaimedSlot = cpu.claimCpuRequestSlotForHdma();
                    hdma.resolveCpuRequest(cpuClaimedSlot,
                            cpu.hasPendingInterruptForHdmaArbitration());
                }
                if (hdma.isCpuInstructionRequestOwner()) {
                    // A CPU-fetched instruction owns the request slot until its next
                    // opcode boundary. Its final HBlank read also keeps the VRAM slot
                    // that was granted with the instruction.
                    gpu.setCpuRetiringInstructionForHdma(true);
                    try {
                        cpu.tick();
                    } finally {
                        gpu.setCpuRetiringInstructionForHdma(false);
                    }
                    if (cpu.isInterruptEntryBusSequenceActiveForHdma()) {
                        hdma.onInterruptEntryAcceptedByCpu();
                    } else if (!cpu.isCpuRequestSlotInProgressForHdma()) {
                        hdma.onCpuRequestSlotRetired();
                    }
                } else {
                    // VRAM is not connected as a CGB VRAM-DMA source. Its first invalid
                    // read slots expose the instruction bus left at the CPU's next PC.
                    // A late arbitration fetch is already latched, so both operations
                    // below reuse that sample without another opcode-bus read.
                    hdma.setCpuBusValue(cpu.getBusValueForHdma());
                    cpu.prefetchOpcodeForHdma();
                    if (hdma.tick() && hdma.yieldsCpuAfterBlock()) {
                        cpu.releaseHdmaPrefetchedOpcode();
                    }
                }
            }
        } else {
            // A retiring instruction can issue its final VRAM read on the CPU half
            // of the edge immediately before an HBlank request reaches arbitration.
            boolean retiringIntoHdmaRequest = cpu.isInstructionRetiringForHdma()
                    && hdma.isHblankRequestArrivingAfterCpuTick();
            gpu.setCpuRetiringInstructionForHdma(retiringIntoHdmaRequest);
            try {
                cpu.tick();
            } finally {
                gpu.setCpuRetiringInstructionForHdma(false);
            }
        }
        if (!speedSwitching && cpu.isSpeedSwitching()) {
            sound.onSpeedSwitch();
            gpu.onSpeedSwitch();
            dma.onSpeedSwitch();
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
                cpu.isCpuRequestSlotInProgressForHdma(),
                cpu.isInterruptClaimedAtHdmaSample());
        Mode mode = gpu.tick();
        statRegister.tick();
        cpu.onPeripheralsTicked();
        return mode;
    }

    public AddressSpace getAddressSpace() {
        return gameGenie;
    }

    public Cpu getCpu() {
        return cpu;
    }

    Hdma getHdma() {
        return hdma;
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
     * Notifies wall-clock cartridge hardware when an external controller stops calling
     * {@link #tick()}. This does not itself pause CPU execution.
     */
    public void setCartridgeClockPaused(boolean paused) {
        cartridge.setClockPaused(paused);
        if (slotCartridge != null) {
            slotCartridge.setClockPaused(paused);
        }
    }

    /** Captures MBC3 pause bookkeeping for each physical cartridge location. */
    public RtcRuntimeState captureRtcRuntimeState() {
        return new RtcRuntimeState(
                cartridge.captureRtcRuntimeState(),
                slotCartridge == null ? null : slotCartridge.captureRtcRuntimeState());
    }

    public void validateRtcRuntimeState(RtcRuntimeState state) {
        if (state == null) {
            throw new IllegalArgumentException("Cartridge RTC runtime state is missing");
        }
        cartridge.validateRtcRuntimeState(state.primary());
        if (slotCartridge == null) {
            if (state.slot() != null) {
                throw new IllegalArgumentException("Slot RTC runtime state supplied without a slot cartridge");
            }
        } else {
            slotCartridge.validateRtcRuntimeState(state.slot());
        }
    }

    public void restoreRtcRuntimeState(RtcRuntimeState state) {
        validateRtcRuntimeState(state);
        cartridge.restoreRtcRuntimeState(state.primary());
        if (slotCartridge != null) {
            slotCartridge.restoreRtcRuntimeState(state.slot());
        }
    }

    /** Captures DMG FIFO fields kept outside the pinned legacy Java memento. */
    public Gpu.DmgFifoRuntimeState captureDmgFifoRuntimeState() {
        return gpu.captureDmgFifoRuntimeState();
    }

    public void validateDmgFifoRuntimeState(Gpu.DmgFifoRuntimeState state) {
        gpu.validateDmgFifoRuntimeState(state);
    }

    public void restoreDmgFifoRuntimeState(Gpu.DmgFifoRuntimeState state) {
        gpu.restoreDmgFifoRuntimeState(state);
    }

    /** Service-free runtime state that is intentionally outside the pinned legacy memento. */
    public record RtcRuntimeState(RealTimeClock.RuntimeState primary,
                                  RealTimeClock.RuntimeState slot) {
    }

    public HardwareProfile getHardwareProfile() {
        return hardwareProfile;
    }

    public HardwareProfileIdentity getHardwareProfileIdentity() {
        return hardwareProfile.identity();
    }

    public ClockSpec getClockSpec() {
        return clockSpec;
    }

    /** @deprecated Use {@link #getHardwareProfile()}. */
    @Deprecated
    public GameboyType getGameboyType() {
        return GameboyType.fromHardwareProfile(hardwareProfile);
    }

    /** Flushes persistent cartridge state without closing the running machine. */
    public void flushCartridge() {
        cartridge.flushBattery();
        if (slotCartridge != null) {
            slotCartridge.flushBattery();
        }
    }

    /**
     * Captures all cartridge RAM/RTC at the current emulation safe point. The returned I/O phase
     * is service-free and can run on a persistence worker.
     */
    public BatteryFlush prepareCartridgeFlush() {
        return BatteryFlush.combine(
                cartridge.prepareBatteryFlush(),
                slotCartridge == null ? BatteryFlush.none() : slotCartridge.prepareBatteryFlush());
    }

    /** Changes only future file-backed battery writes; no filesystem work runs on this thread. */
    public void setBatteryStorage(BatteryStorage primary, BatteryStorage slot) {
        if (primary != null) {
            cartridge.setBatteryStorage(primary);
        }
        if (slot != null && slotCartridge != null) {
            slotCartridge.setBatteryStorage(slot);
        }
    }

    /**
     * Held-button state, snapshotted separately from machine state by rollback netplay so a held
     * button survives a rebase (the joypad deliberately keeps it out of component state).
     */
    public java.util.Set<eu.rekawek.coffeegb.core.joypad.Button> getPressedButtons() {
        return joypad.getPressedButtons();
    }

    /** Session-owned protocol/replay P1 state, excluding the live four-slot input service. */
    public java.util.Set<eu.rekawek.coffeegb.core.joypad.Button> getLegacyPressedButtons() {
        return joypad.getLegacyPressedButtons();
    }

    public void setPressedButtons(java.util.Collection<eu.rekawek.coffeegb.core.joypad.Button> pressed) {
        joypad.setPressedButtons(pressed);
    }

    /** Platform-neutral SGB controller status for diagnostics and conformance tests. */
    public Joypad.SgbMultiplayerStatus getSgbMultiplayerStatus() {
        return joypad.getSgbMultiplayerStatus();
    }

    @Override
    public ComponentState<Gameboy> captureState() {
        // Gpu owns the sole display snapshot. Keep the nullable root component in the stable
        // detached layout so imported fixtures and StateFile v1 retain their existing shape, but
        // do not clone the two 160x144 buffers a second time for new captures.
        return new GameboyState(biosShadow.captureState(), cartridge.captureState(), gpu.captureState(), statRegister.captureState(), mmu.captureState(), oamRam.captureState(), cpu.captureState(), interruptManager.captureState(), timer.captureState(), dma.captureState(), hdma.captureState(), null, sound.captureState(), serialPort.captureState(), infraredPort.captureState(), codeBreakerRumble.captureState(), joypad.captureState(), speedMode.captureState(), superGameboy.captureState(), background.captureState(), vRamTransfer.captureState(), sgbDisplay.captureState(), gameGenie.captureState(), requestedScreenRefresh, lcdDisabled, lcdOffTicks, speedSwitchTailTicks, speedSwitchClockPhaseShifted, blankCgbBootTilePending, clearBootTilemapPending, clearCgbBootOamShadowPending);
    }

    @Override
    public ComponentState<Gameboy> captureState(MachineStateCapture capture) {
        return new GameboyState(
                biosShadow.captureState(capture),
                cartridge.captureState(capture),
                gpu.captureState(capture),
                statRegister.captureState(capture),
                mmu.captureState(capture),
                oamRam.captureState(capture),
                cpu.captureState(capture),
                interruptManager.captureState(capture),
                timer.captureState(capture),
                dma.captureState(capture),
                hdma.captureState(capture),
                null,
                sound.captureState(capture),
                serialPort.captureState(capture),
                infraredPort.captureState(capture),
                codeBreakerRumble.captureState(capture),
                joypad.captureState(capture),
                speedMode.captureState(capture),
                superGameboy.captureState(capture),
                background.captureState(capture),
                vRamTransfer.captureState(capture),
                sgbDisplay.captureState(capture),
                gameGenie.captureState(capture),
                requestedScreenRefresh,
                lcdDisabled,
                lcdOffTicks,
                speedSwitchTailTicks,
                speedSwitchClockPhaseShifted,
                blankCgbBootTilePending,
                clearBootTilemapPending,
                clearCgbBootOamShadowPending);
    }

    @Override
    public void declareMachineStatePayloads(MachineStateCapture capture) {
        biosShadow.declareMachineStatePayloads(capture);
        cartridge.declareMachineStatePayloads(capture);
        gpu.declareMachineStatePayloads(capture);
        statRegister.declareMachineStatePayloads(capture);
        mmu.declareMachineStatePayloads(capture);
        oamRam.declareMachineStatePayloads(capture);
        cpu.declareMachineStatePayloads(capture);
        interruptManager.declareMachineStatePayloads(capture);
        timer.declareMachineStatePayloads(capture);
        dma.declareMachineStatePayloads(capture);
        hdma.declareMachineStatePayloads(capture);
        sound.declareMachineStatePayloads(capture);
        serialPort.declareMachineStatePayloads(capture);
        infraredPort.declareMachineStatePayloads(capture);
        codeBreakerRumble.declareMachineStatePayloads(capture);
        joypad.declareMachineStatePayloads(capture);
        speedMode.declareMachineStatePayloads(capture);
        superGameboy.declareMachineStatePayloads(capture);
        background.declareMachineStatePayloads(capture);
        vRamTransfer.declareMachineStatePayloads(capture);
        sgbDisplay.declareMachineStatePayloads(capture);
        gameGenie.declareMachineStatePayloads(capture);
    }

    /**
     * Exposes a transient, service-free machine view only for the duration of {@code consumer}.
     *
     * <p>The owning emulator thread must be stopped at its documented frame boundary. The view
     * contains borrowed live primitive arrays and must not escape the callback.
     */
    public <R> R withMachineStateCapture(
            BiFunction<ComponentState<Gameboy>, MachineStateCapture, R> consumer) {
        if (consumer == null) {
            throw new IllegalArgumentException("Machine-state consumer is required");
        }
        return MachineStateCapture.withVerifiedView(
                this::declareMachineStatePayloads, this::captureState, consumer);
    }

    @Override
    public void restoreState(ComponentState<Gameboy> state) {
        if (!(state instanceof GameboyState mem)) {
            throw new IllegalArgumentException();
        }
        restoreMachineState(mem, true);
    }

    /**
     * Captures the authentic boot-ROM handoff for reuse by another machine with the same ROM
     * and hardware configuration. Production boot templates are created without file-backed
     * battery data; restoring one deliberately keeps the receiving cartridge's freshly loaded
     * RAM, RTC and mapper state.
     */
    public BootState saveBootState() {
        return new BootState((GameboyState) captureState());
    }

    public void restoreBootState(BootState bootState) {
        if (bootState == null) {
            throw new IllegalArgumentException("Boot state is required");
        }
        restoreMachineState(bootState.state, false);
    }

    private void restoreMachineState(GameboyState mem, boolean restoreCartridge) {
        biosShadow.restoreState(mem.biosShadowMemento());
        if (restoreCartridge) {
            cartridge.restoreState(mem.cartridgeMemento());
        }
        gpu.restoreState(mem.gpuMemento());
        statRegister.restoreState(mem.statRegisterMemento());
        mmu.restoreState(mem.mmuMemento());
        oamRam.restoreState(mem.oamRamMemento());
        cpu.restoreState(mem.cpuMemento());
        interruptManager.restoreState(mem.interruptManagerMemento());
        timer.restoreState(mem.timerMemento());
        dma.restoreState(mem.dmaMemento());
        hdma.restoreState(mem.hdmaMemento());
        // Older snapshots contain the former duplicate. Gpu has already restored the same
        // display state; applying this copy preserves byte-for-byte legacy behavior.
        if (mem.displayMemento() != null) {
            display.restoreState(mem.displayMemento());
        }
        sound.restoreState(mem.soundMemento());
        serialPort.restoreState(mem.serialPortMemento());
        infraredPort.restoreState(mem.infraredPortMemento());
        codeBreakerRumble.restoreState(mem.codeBreakerRumbleMemento());
        joypad.restoreState(mem.joypadMemento());
        speedMode.restoreState(mem.speedModeMemento());
        superGameboy.restoreState(mem.superGameboyMemento());
        background.restoreState(mem.backgroundMemento());
        vRamTransfer.restoreState(mem.vRamTransferMemento());
        sgbDisplay.restoreState(mem.sgbDisplayMemento());
        gameGenie.restoreState(mem.genieMemento());
        requestedScreenRefresh = mem.requestScreenRefresh();
        lcdDisabled = mem.lcdDisabled();
        lcdOffTicks = mem.lcdOffTicks();
        speedSwitchTailTicks = mem.speedSwitchTailTicks();
        speedSwitchClockPhaseShifted = mem.speedSwitchClockPhaseShifted();
        blankCgbBootTilePending = mem.blankCgbBootTilePending();
        clearBootTilemapPending = mem.clearBootTilemapPending();
        clearCgbBootOamShadowPending = mem.clearCgbBootOamShadowPending();
    }

    /** Releases an asynchronously prepared machine that was never attached to a session. */
    public void discardUnstarted() {
        codeBreakerRumble.quiesce();
        infraredPort.close();
        sgbBus.close();
    }

    @Override
    public void close() {
        closeResources(true, true);
    }

    /**
     * Closes a session after a successful {@link #prepareCartridgeFlush()} barrier.
     *
     * <p>Callers must not use this to bypass a failed or cancelled persistence result.
     */
    public void closeAfterCartridgeFlush() {
        closeResources(false, true);
    }

    /**
     * Releases a machine whose owner has already quiesced its event bus and reset UI outputs.
     *
     * <p>Unlike {@link #close()}, this method emits no final hardware-output events.
     */
    public void closeSilently() {
        closeResources(true, false);
    }

    /**
     * Releases an already-persisted machine after its event bus and UI outputs are quiescent.
     */
    public void closeAfterCartridgeFlushSilently() {
        closeResources(false, false);
    }

    private void closeResources(boolean flushCartridge, boolean publishOutputReset) {
        if (publishOutputReset) {
            codeBreakerRumble.close();
        } else {
            codeBreakerRumble.quiesce();
        }
        infraredPort.close();
        if (flushCartridge) {
            flushCartridge();
        }
        sgbBus.close();
    }

    public static final class BootState {

        private final GameboyState state;

        private BootState(GameboyState state) {
            this.state = state;
        }
    }

    private record GameboyState(ComponentState<BiosShadow> biosShadowMemento, ComponentState<Cartridge> cartridgeMemento,
                                  ComponentState<Gpu> gpuMemento, ComponentState<StatRegister> statRegisterMemento,
                                  ComponentState<Mmu> mmuMemento, ComponentState<Ram> oamRamMemento, ComponentState<Cpu> cpuMemento,
                                  ComponentState<InterruptManager> interruptManagerMemento, ComponentState<Timer> timerMemento,
                                  ComponentState<Dma> dmaMemento, ComponentState<Hdma> hdmaMemento, ComponentState<Display> displayMemento,
                                  ComponentState<Sound> soundMemento, ComponentState<SerialPort> serialPortMemento,
                                  ComponentState<InfraredPort> infraredPortMemento,
                                  ComponentState<CodeBreakerRumble> codeBreakerRumbleMemento,
                                  ComponentState<Joypad> joypadMemento, ComponentState<SpeedMode> speedModeMemento,
                                  ComponentState<SuperGameboy> superGameboyMemento, ComponentState<Background> backgroundMemento,
                                  ComponentState<VRamTransfer> vRamTransferMemento, ComponentState<SgbDisplay> sgbDisplayMemento,
                                  ComponentState<Genie> genieMemento, boolean requestScreenRefresh,
                                  boolean lcdDisabled, int lcdOffTicks, int speedSwitchTailTicks,
                                  boolean speedSwitchClockPhaseShifted,
                                  boolean blankCgbBootTilePending,
                                  boolean clearBootTilemapPending,
                                  boolean clearCgbBootOamShadowPending) implements ComponentState<Gameboy> {
    }

    /** Importer-only compatibility record for released local snapshots. */
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

        private HardwareProfile hardwareProfile;

        private BootstrapMode bootstrapMode = BootstrapMode.SKIP;

        private Rom slotRom;

        private byte[] batteryData;

        private boolean supportBatterySave = true;

        private BatteryStorage batteryStorage;

        private BatteryStorage slotBatteryStorage;

        private boolean displaySgbBorder = true;

        private boolean mealybugDmgBlob;

        private boolean codeBreakerRumble;

        private TimeSource rtcTimeSource = new SystemTimeSource();

        /** Live platform input service; deliberately excluded from every machine-state model. */
        private PlayerInputSource playerInputSource = PlayerInputSource.RELEASED;

        private BooleanSupplier bootCancellation = () -> false;

        public GameboyConfiguration(File romFile) throws IOException {
            this(new Rom(romFile));
        }

        public GameboyConfiguration(Rom rom) {
            this.rom = rom;
            boolean cgb0Revision = rom.getCartridgeProperties().has(
                    CartridgeProperties.Feature.CGB0_REVISION);
            mealybugDmgBlob = rom.getCartridgeProperties().has(
                    CartridgeProperties.Feature.MEALYBUG_DMG_BLOB);
            codeBreakerRumble = rom.getCartridgeProperties().has(
                    CartridgeProperties.Feature.CODEBREAKER_RUMBLE);
            if (rom.getGameboyColorFlag() == Rom.GameboyColorFlag.NON_CGB) {
                hardwareProfile = HardwareProfileRegistry.DMG;
            } else {
                hardwareProfile = cgb0Revision
                        ? HardwareProfileRegistry.CGB0 : HardwareProfileRegistry.CGB;
            }
        }

        /** @deprecated Use {@link #setHardwareProfile(HardwareProfile)}. */
        @Deprecated
        public GameboyConfiguration setGameboyType(GameboyType gameboyType) {
            HardwareProfile selected = HardwareProfileRegistry.fromGameboyType(gameboyType);
            // Historical callers set CGB after the cartridge-derived CGB0 bit was initialized.
            // Preserve that exact order-dependent compatibility behavior.
            if (gameboyType == GameboyType.CGB && hardwareProfile == HardwareProfileRegistry.CGB0) {
                return this;
            }
            this.hardwareProfile = selected;
            return this;
        }

        public GameboyConfiguration setHardwareProfile(HardwareProfile hardwareProfile) {
            this.hardwareProfile = HardwareProfileRegistry.requireRegistered(hardwareProfile);
            return this;
        }

        /**
         * Emulates CGB revision 0 behavior where it differs from the default CGB-D
         * model, including boot timing (mooneye boot_div-cgb0) and prohibited-area
         * decoding.
         */
        public GameboyConfiguration setCgb0Revision(boolean cgb0Revision) {
            if (cgb0Revision) {
                if (hardwareProfile.family() != HardwareProfile.Family.CGB) {
                    throw new IllegalArgumentException("CGB0 revision requires a CGB hardware profile");
                }
                hardwareProfile = HardwareProfileRegistry.CGB0;
            } else if (hardwareProfile == HardwareProfileRegistry.CGB0) {
                hardwareProfile = HardwareProfileRegistry.CGB;
            }
            return this;
        }

        /** @deprecated Use {@link #getHardwareProfile()}. */
        @Deprecated
        public GameboyType getGameboyType() {
            return GameboyType.fromHardwareProfile(hardwareProfile);
        }

        public boolean isCgb0Revision() {
            return hardwareProfile == HardwareProfileRegistry.CGB0;
        }

        public HardwareProfile getHardwareProfile() {
            return hardwareProfile;
        }

        public ClockSpec getClockSpec() {
            return hardwareProfile.clockSpec();
        }

        /** Selects the DMG-blob timing expected by the Mealybug Shootout references. */
        public GameboyConfiguration setMealybugDmgBlob(boolean mealybugDmgBlob) {
            this.mealybugDmgBlob = mealybugDmgBlob;
            return this;
        }

        public boolean isMealybugDmgBlob() {
            return mealybugDmgBlob;
        }

        /** Selects the optional CodeBreaker pass-through rumble accessory. */
        public GameboyConfiguration setCodeBreakerRumble(boolean codeBreakerRumble) {
            this.codeBreakerRumble = codeBreakerRumble;
            return this;
        }

        public boolean isCodeBreakerRumble() {
            return codeBreakerRumble;
        }

        public GameboyConfiguration setDisplaySgbBorder(boolean displaySgbBorder) {
            this.displaySgbBorder = displaySgbBorder;
            return this;
        }

        public boolean isDisplaySgbBorder() {
            return hardwareProfile.capabilities().superGameboyBorder() && displaySgbBorder;
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

        public Rom getSlotRom() {
            return slotRom;
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

        public boolean isSupportBatterySave() {
            return supportBatterySave;
        }

        public GameboyConfiguration setBatteryStorage(
                BatteryStorage batteryStorage,
                BatteryStorage slotBatteryStorage) {
            this.batteryStorage = batteryStorage;
            this.slotBatteryStorage = slotBatteryStorage;
            return this;
        }

        public BatteryStorage getBatteryStorage() {
            return batteryStorage;
        }

        public BatteryStorage getSlotBatteryStorage() {
            return slotBatteryStorage;
        }

        public GameboyConfiguration setBootCancellation(BooleanSupplier bootCancellation) {
            this.bootCancellation = bootCancellation == null ? () -> false : bootCancellation;
            return this;
        }

        /**
         * Sets the wall clock used for battery offline catch-up and explicit emulator
         * pauses. During active emulation the MBC3 RTC advances from Game Boy ticks.
         */
        public GameboyConfiguration setRtcTimeSource(TimeSource rtcTimeSource) {
            this.rtcTimeSource = rtcTimeSource;
            return this;
        }

        public GameboyConfiguration setPlayerInputSource(PlayerInputSource playerInputSource) {
            this.playerInputSource = playerInputSource == null
                    ? PlayerInputSource.RELEASED : playerInputSource;
            return this;
        }

        public PlayerInputSource getPlayerInputSource() {
            return playerInputSource;
        }

        /**
         * A copy of this configuration that skips the boot sequence, for building a
         * Gameboy whose state is immediately overwritten by an explicit state restore. With
         * {@link BootstrapMode#FAST_FORWARD} the constructor would emulate the whole
         * boot ROM (tens of milliseconds) only to have every bit of that state
         * discarded by the restore.
         */
        public GameboyConfiguration forRestore() {
            GameboyConfiguration copy = copy();
            copy.bootstrapMode = BootstrapMode.SKIP;
            return copy;
        }

        /**
         * Service-free configuration for deterministic rollback replay. All linked input is
         * replayed from the controller's frame-owned input log; protocol v8 has no representation
         * for local SGB P2-P4 input, so no physical input service may enter a replay machine.
         */
        public GameboyConfiguration forStateHistoryReplay() {
            GameboyConfiguration copy = copy();
            copy.playerInputSource = PlayerInputSource.RELEASED;
            return copy;
        }

        /** A boot-equivalent copy that cannot read or write a user's battery save. */
        public GameboyConfiguration forBootTemplate() {
            GameboyConfiguration copy = copy();
            copy.batteryData = null;
            copy.supportBatterySave = false;
            return copy;
        }

        private GameboyConfiguration copy() {
            GameboyConfiguration copy = new GameboyConfiguration(rom);
            copy.hardwareProfile = hardwareProfile;
            copy.bootstrapMode = bootstrapMode;
            copy.slotRom = slotRom;
            copy.batteryData = batteryData;
            copy.supportBatterySave = supportBatterySave;
            copy.batteryStorage = batteryStorage;
            copy.slotBatteryStorage = slotBatteryStorage;
            copy.displaySgbBorder = displaySgbBorder;
            copy.mealybugDmgBlob = mealybugDmgBlob;
            copy.codeBreakerRumble = codeBreakerRumble;
            copy.rtcTimeSource = rtcTimeSource;
            copy.playerInputSource = playerInputSource;
            copy.bootCancellation = bootCancellation;
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
