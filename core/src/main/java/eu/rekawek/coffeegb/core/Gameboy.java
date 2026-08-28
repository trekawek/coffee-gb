package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.debug.Console;
import eu.rekawek.coffeegb.core.debug.DebugApuState;
import eu.rekawek.coffeegb.core.debug.DebugAudioInspection;
import eu.rekawek.coffeegb.core.debug.DebugCpuState;
import eu.rekawek.coffeegb.core.debug.DebugExecutionState;
import eu.rekawek.coffeegb.core.debug.DebugFeatureState;
import eu.rekawek.coffeegb.core.debug.DebugGraphicsHardwareMode;
import eu.rekawek.coffeegb.core.debug.DebugGraphicsInspection;
import eu.rekawek.coffeegb.core.debug.DebugHardwareInspection;
import eu.rekawek.coffeegb.core.debug.DebugInterruptState;
import eu.rekawek.coffeegb.core.debug.DebugInstrumentation;
import eu.rekawek.coffeegb.core.debug.DebugInspectionSection;
import eu.rekawek.coffeegb.core.debug.DebugInspectionRequest;
import eu.rekawek.coffeegb.core.debug.DebugInspectionResult;
import eu.rekawek.coffeegb.core.debug.DebugMapperState;
import eu.rekawek.coffeegb.core.debug.DebugMemoryBlock;
import eu.rekawek.coffeegb.core.debug.DebugMemoryRequest;
import eu.rekawek.coffeegb.core.debug.DebugMemoryWrite;
import eu.rekawek.coffeegb.core.debug.DebugPpuMode;
import eu.rekawek.coffeegb.core.debug.DebugPpuState;
import eu.rekawek.coffeegb.core.debug.DebugRegisters;
import eu.rekawek.coffeegb.core.debug.DebugSnapshot;
import eu.rekawek.coffeegb.core.debug.DebugTimerState;
import eu.rekawek.coffeegb.core.debug.trace.TraceReadResult;
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
import eu.rekawek.coffeegb.core.joypad.InputTimelineObserver;
import eu.rekawek.coffeegb.core.joypad.Joypad;
import eu.rekawek.coffeegb.core.joypad.PlayerInputSource;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;
import eu.rekawek.coffeegb.core.memory.*;
import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryFlush;
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryStorage;
import eu.rekawek.coffeegb.core.memory.cart.battery.MemoryBattery;
import eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock;
import eu.rekawek.coffeegb.core.memory.cart.rtc.SystemTimeSource;
import eu.rekawek.coffeegb.core.memory.cart.rtc.TimeSource;
import eu.rekawek.coffeegb.core.rumble.CodeBreakerRumble;
import eu.rekawek.coffeegb.core.rumble.RumbleEvent;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.core.serial.SerialCompatibilityProfile;
import eu.rekawek.coffeegb.core.serial.SerialPort;
import eu.rekawek.coffeegb.core.sgb.Background;
import eu.rekawek.coffeegb.core.sgb.SgbDisplay;
import eu.rekawek.coffeegb.core.sgb.SuperGameboy;
import eu.rekawek.coffeegb.core.sound.Sound;
import eu.rekawek.coffeegb.core.timer.Timer;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Gameboy implements Runnable, StatefulComponent<Gameboy>, Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(Gameboy.class);

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

    /** Maximum settled-HALT packet horizon; Android's next input poll trims this to at most 63. */
    private static final int SETTLED_HALT_PERFORMANCE_MAX_SPAN = 64;

    // A granted HBlank burst can finish while the CPU speed-switch countdown is still
    // running. Its completed bus hand-off removes five ticks from the retained tail.
    static final int COMPLETED_HBLANK_SPEED_SWITCH_ADVANCE_TICKS = 5;

    private final Cartridge cartridge;

    private final Cartridge slotCartridge;

    private final boolean cartridgeClocked;

    private final boolean slotCartridgeClocked;

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

    private final boolean jantakuBoyFourPlayerPatch;

    private final boolean revengeGatorLinkRolePatch;

    private final boolean shikinjouLinkRolePatch;

    private final boolean volleyFireLinkRolePatch;

    private transient EventBus hostEventBus = EventBus.NULL_EVENT_BUS;

    private final Joypad joypad;

    private final SpeedMode speedMode;

    private final SuperGameboy superGameboy;

    private final EventBus sgbBus;

    private final Background background;

    private final VRamTransfer vRamTransfer;

    private final SgbDisplay sgbDisplay;

    private final Genie gameGenie;

    private transient volatile boolean doStop;

    /**
     * Host-only request sampled at the next physical VBlank edge. It intentionally is not part
     * of a machine snapshot: presentation pacing must not affect deterministic replay.
     */
    private transient volatile boolean requestedFrameRenderSuppression;

    /** Whether the visible frame currently being scanned out is host-suppressed. */
    private transient boolean frameRenderSuppressed;

    private boolean requestedScreenRefresh;

    private boolean lcdDisabled;

    private int lcdOffTicks;

    private int speedSwitchTailTicks;

    private boolean speedSwitchClockPhaseShifted;

    private boolean blankCgbBootTilePending;

    private boolean clearBootTilemapPending;

    private boolean clearCgbBootOamShadowPending;

    private transient boolean bootCompatibilityResolved;

    private transient volatile boolean doPause;

    private transient volatile boolean paused;

    private transient DebugInstrumentation debugInstrumentation;

    /** Session-only retirement observation state; deliberately absent from machine state. */
    private transient boolean debugRetirementTrackingActive;

    /** PERFORMANCE scheduler diagnostics; deliberately absent from portable machine state. */
    private transient long performanceBulkSpanCount;

    private transient long performanceBulkTicks;

    /** Largest settled-HALT PERFORMANCE packet in this session (diagnostic only). */
    private transient int performanceBulkMaxTicks;

    /** Bounded coarse CPU-epoch diagnostics; absent from portable state. */
    private transient long performanceEpochCount;

    private transient long performanceEpochTicks;

    private transient int performanceEpochMaxTicks;

    private transient long performanceEpochRasterFastTicks;

    private transient long performanceEpochMode2ReplayTicks;

    /** Subset of mode-2 epoch ticks committed by the allocation-free OAM transaction. */
    private transient long performanceEpochMode2BulkTicks;

    /** Native-CGB x1 epoch ticks committed while the LCD was stably disabled. */
    private transient long performanceEpochLcdOffTicks;

    /** CPU/STAT phase frozen at the entrance of the current fixed-x1 CPU epoch. */
    private transient int performanceEpochEntryStatReadPhaseFlags;

    /** Whether the first positive peripheral prefix captured the frozen CPU/STAT phase. */
    private transient boolean performanceEpochEntryStatReadPhaseCaptured;

    /** Raster transaction selected before the CPU observes its frozen peripheral view. */
    private transient PerformanceEpochPpuPlan performanceEpochPpuPlan =
            PerformanceEpochPpuPlan.NONE;

    private transient int performanceEpochPrefixCommitted;

    private transient boolean performanceEpochDirectRaster;

    private transient boolean performanceEpochSteadyRaster;

    private transient IntConsumer performanceEpochPrefixCommitter;

    private transient IntConsumer performancePhysicalDmgEpochPrefixCommitter;

    private transient IntConsumer performanceCgbNormalSpeedEpochPrefixCommitter;

    private enum PerformanceEpochPpuPlan {
        NONE,
        TRUSTED_RASTER,
        MODE2_BULK,
        MODE2_REPLAY,
        LCD_OFF
    }

    /** PPU commit selected for a normal-speed, phase-only PERFORMANCE packet. */
    private enum PerformancePhasePpuPlan {
        QUIET,
        PHYSICAL_DMG_MODE2,
        CGB_NORMAL_SPEED_MODE2
    }

    public Gameboy(Rom rom) {
        this(new GameboyConfiguration(rom));
    }

    private final boolean gbc;

    private final HardwareProfile hardwareProfile;

    private final ClockSpec clockSpec;

    /** Session metadata; deliberately excluded from the emulated machine state. */
    private final ExecutionMode executionMode;

    /** History replay keeps its service-free deterministic scheduler on the scalar path. */
    private final boolean debugHistoryReplay;

    public Gameboy(GameboyConfiguration configuration) {
        this.executionMode = Objects.requireNonNull(configuration.executionMode, "executionMode");
        this.debugHistoryReplay = configuration.debugHistoryReplay;
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
        jantakuBoyFourPlayerPatch = cartridgeProperties.has(
                CartridgeProperties.Feature.JANTAKU_BOY_FOUR_PLAYER_PATCH);
        revengeGatorLinkRolePatch = cartridgeProperties.has(
                CartridgeProperties.Feature.REVENGE_GATOR_LINK_ROLE_PATCH);
        shikinjouLinkRolePatch = cartridgeProperties.has(
                CartridgeProperties.Feature.SHIKINJOU_LINK_ROLE_PATCH);
        volleyFireLinkRolePatch = cartridgeProperties.has(
                CartridgeProperties.Feature.VOLLEY_FIRE_LINK_ROLE_PATCH);

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
                configuration.mealybugDmgBlob,
                cartridgeProperties.has(CartridgeProperties.Feature.EARLY_CGB_LY_READ_EDGE),
                executionMode, hardwareProfile, configuration.debugHistoryReplay);
        mmu.setGpu(gpu);
        statRegister.init(gpu);
        hdma = new Hdma(getAddressSpace(), speedMode);
        gpu.setHdma(hdma);
        sound = new Sound(timer, speedMode, gbc, clockSpec, executionMode);
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

        if (configuration.debugHistoryReplay) {
            cartridge = new Cartridge(
                    configuration.rom,
                    configuration.debugHistoryPrimaryBatteryShape.createServiceFreeBattery(),
                    configuration.rtcTimeSource,
                    clockSpec);
        } else if (configuration.batteryData != null) {
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
            if (configuration.debugHistoryReplay) {
                if (configuration.debugHistorySlotBatteryShape == null) {
                    throw new IllegalStateException(
                            "Debug-history replay is missing the live slot battery shape");
                }
                slotCartridge = new Cartridge(
                        configuration.slotRom,
                        configuration.debugHistorySlotBatteryShape.createServiceFreeBattery(),
                        configuration.rtcTimeSource,
                        clockSpec);
            } else if (configuration.slotBatteryData != null) {
                slotCartridge = new Cartridge(
                        configuration.slotRom,
                        new MemoryBattery(configuration.slotBatteryData),
                        configuration.rtcTimeSource,
                        clockSpec);
            } else {
                slotCartridge = new Cartridge(
                        configuration.slotRom,
                        configuration.supportBatterySave,
                        configuration.slotBatteryStorage,
                        configuration.rtcTimeSource,
                        clockSpec);
            }
            cartridge.getDatel().setSlotCartridge(slotCartridge.getMemoryController(),
                    configuration.slotRom.getGameboyColorFlag() == Rom.GameboyColorFlag.NON_CGB);
        } else {
            slotCartridge = null;
        }
        cartridgeClocked = cartridge.isClocked();
        slotCartridgeClocked = slotCartridge != null && slotCartridge.isClocked();
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
            // Component constructors retain the post-boot defaults used by direct fixtures
            // and SKIP bootstrap. An authentic boot starts before the boot ROM has asserted
            // VBlank IF or powered the APU, so drive those existing register boundaries to
            // raw reset before the first CPU tick.
            interruptManager.setByte(0xff0f, 0x00);
            sound.setByte(0xff26, 0x00);
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
            if (configuration.bootstrapMode == BootstrapMode.SKIP) {
                restoreSachenBootLogoForSkippedBoot();
            }
            cartridge.skipBoot();
            // the Datel Action Replay's ASIC presents a valid CGB header to the console,
            // so the machine boots native-colour despite the dump's garbage flag byte
            applyPostBootState(configuration.rom.getGameboyColorFlag() == Rom.GameboyColorFlag.NON_CGB
                    && !cartridgeProperties.has(CartridgeProperties.Feature.DATEL_CGB_HEADER));
        }
        applyBootCompatibilityIfReady();
    }

    private void restoreSachenBootLogoForSkippedBoot() {
        var sachen = cartridge.getSachenMmc();
        if (sachen == null) {
            return;
        }
        int address = 0x8010;
        for (int value : sachen.getBootLogoForSkippedBoot(gbc)) {
            address = writeDmgBootLogoNibble(address, value >> 4);
            address = writeDmgBootLogoNibble(address, value);
        }
    }

    private int writeDmgBootLogoNibble(int address, int value) {
        int expanded = 0;
        for (int bit = 3; bit >= 0; bit--) {
            expanded <<= 2;
            if ((value & (1 << bit)) != 0) {
                expanded |= 0x03;
            }
        }
        gpu.writeVideoRam0ForCore(address, expanded);
        gpu.writeVideoRam0ForCore(address + 1, 0);
        gpu.writeVideoRam0ForCore(address + 2, expanded);
        gpu.writeVideoRam0ForCore(address + 3, 0);
        return address + 4;
    }

    private void applyBootCompatibilityIfReady() {
        if (bootCompatibilityResolved) {
            return;
        }
        if (!biosShadow.isBootFinished()) {
            return;
        }
        if (blankCgbBootTilePending) {
            // This trainer treats tile 0x0A as blank but does not replace the CGB boot
            // logo residue in its 16 data bytes. Do not sanitize any other cartridge or
            // any other part of VRAM: boot-state-dependent software still sees hardware.
            for (int address = 0x80a0; address < 0x80b0; address++) {
                gpu.writeVideoRam0ForCore(address, 0);
            }
            blankCgbBootTilePending = false;
        }
        if (clearBootTilemapPending) {
            // This emulator-targeted music player replaces its font tiles and writes
            // the visible strings, but never clears the boot logo's tile-map entries.
            // Period emulators launched it from a zeroed map, which is its intended UI.
            for (int address = 0x9800; address < 0xa000; address++) {
                gpu.writeVideoRam0ForCore(address, 0);
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
        bootCompatibilityResolved = true;
        gpu.setBootCompatibilityResolved(true);
    }

    /**
     * Puts the machine into the state the boot ROM hands over: post-boot register presets,
     * boot ROM unmapped, LCD enabled. Used by the SKIP/timed-out boot and by cartridge
     * hardware that pulses the console's reset line (the Datel Action Replay game launch).
     */
    private void applyPostBootState(boolean nonCgbCart) {
        speedMode.setDmgCompat(gbc && nonCgbCart);
        gpu.prepareForTick();
        biosShadow.setByte(0xff50, 1);
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

    /** One-shot native-CGB PERFORMANCE scalar-prologue token; deliberately absent from mementos. */
    private transient boolean nativeCgbScalarOwner;

    /** Cartridge hardware pulsing the console reset line (Datel Action Replay launch). */
    public void requestWarmReset(boolean nonCgbCart) {
        warmResetNonCgbCart = nonCgbCart;
        warmResetRequested = true;
    }

    private void applyWarmReset() {
        nativeCgbScalarOwner = false;
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
        hostEventBus = eventBus;
        joypad.init(eventBus);
        display.init(eventBus);
        sound.init(eventBus);
        if (revengeGatorLinkRolePatch && serialEndpoint.linkPlayerIndex() == 1) {
            cartridge.enableRevengeGatorSecondaryLinkRole();
        }
        if (shikinjouLinkRolePatch && serialEndpoint.linkPlayerIndex() == 1) {
            cartridge.enableShikinjouSecondaryLinkRole();
        }
        if (volleyFireLinkRolePatch && serialEndpoint.linkPlayerIndex() == 1) {
            cartridge.enableVolleyFireSecondaryLinkRole();
        }
        if (jantakuBoyFourPlayerPatch) {
            serialEndpoint.enableCompatibilityProfile(
                    SerialCompatibilityProfile.JANTAKU_BOY_FOUR_PLAYER_CONTROL_PACKET);
        }
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
        DebugInstrumentation instrumentation = debugInstrumentation;
        if (instrumentation != null) {
            instrumentation.onMasterTickStarted();
        }
        if (warmResetRequested) {
            warmResetRequested = false;
            applyWarmReset();
            // applyWarmReset() clears the owner before its hardware writes. Keep the
            // scheduler-side invariant explicit for a reset observed in this tick.
            nativeCgbScalarOwner = false;
        }
        if (cartridgeClocked) {
            cartridge.tick();
        }
        if (slotCartridgeClocked) {
            slotCartridge.tick();
        }
        boolean result = false;

        Mode newMode = tickSubsystems();
        if (!bootCompatibilityResolved) {
            applyBootCompatibilityIfReady();
        }
        if (gbc && newMode != null) {
            hdma.onGpuUpdate(newMode);
        }
        if (gbc) {
            hdma.onGpuTiming(gpu.getLine(), gpu.getTicksInLine(),
                    gpu.isStatModeLatchRephasedBySpeedSwitch());
            cpu.latchHdmaHaltOpcode(hdma.isHaltRequestLatched());
        }

        boolean stopFrameBlanked = cpu.consumeStopFrameBlankRequest();
        if (stopFrameBlanked) {
            display.blankFrameForStop();
            result = true;
        }

        if (!gpu.isLcdEnabled()) {
            if (!lcdDisabled) {
                lcdDisabled = true;
                if (gbc) {
                    hdma.onLcdSwitch(false);
                }
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
                if (gbc) {
                    hdma.onLcdSwitch(true);
                }
            }
            if (!stopFrameBlanked && newMode == Mode.VBlank) {
                requestedScreenRefresh = true;
                // The request is deliberately not acted on at the controller's shorter
                // 69,905-tick cadence. At this PPU edge every visible dot of the current frame
                // has already advanced, so one whole physical frame is either published or held.
                if (!frameRenderSuppressed) {
                    display.frameIsReady();
                }
                // This is emulated SGB transfer timing, not host presentation. In particular,
                // it must remain available independently of a future presentation policy.
                vRamTransfer.frameIsReady();
                latchFrameRenderSuppressionAtVBlank();
                // A suppressed frame is still a real emulated VBlank. Keep the long-standing
                // tick result and requested-screen-refresh cadence for host callers.
                result = true;
            } else if (requestedScreenRefresh && newMode == Mode.OamSearch) {
                requestedScreenRefresh = false;
            }
        }
        return result;
    }

    /**
     * Advances a bounded number of master ticks on the owning emulation thread.
     *
     * <p>PERFORMANCE callers use this frame-sized seam so the controller does not pay a
     * Kotlin/Java callback and repeat-loop boundary for every dot. The method still routes
     * through the same {@link #tick()} state machine, including frame-ready events, while the
     * hot scheduler inside {@code tickSubsystems()} splits CPU phase-only ticks and defers
     * proven background raster spans. ACCURACY retains the exact scalar behavior.</p>
     *
     * @return the number of frame-ready events emitted while advancing the requested ticks
     */
    public int runTicks(int ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("ticks must be non-negative");
        }
        return Math.toIntExact(runTicks((long) ticks));
    }

    /** Long-count counterpart for headless benchmark and fast-forward callers. */
    public long runTicks(long ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("ticks must be non-negative");
        }
        if (executionMode == ExecutionMode.PERFORMANCE
                && bootCompatibilityResolved
                && debugInstrumentation == null
                && !debugRetirementTrackingActive
                && !debugHistoryReplay) {
            if (isNativeCgbPerformanceEpochTopology()) {
                return runNativeCgbPerformanceTicks(ticks);
            }
            if (isNormalSpeedPerformanceEpochTopology()) {
                return runNormalSpeedPerformanceTicks(ticks);
            }
            return runPerformanceTicks(ticks);
        }
        long frameEvents = 0;
        for (long i = 0; i < ticks; i++) {
            if (tick()) {
                frameEvents++;
            }
        }
        return frameEvents;
    }

    /**
     * Advances at most {@code ticks}, stopping before the first tick for which {@code stop}
     * returns true. This owner-thread seam is used by benchmark preconditioning to stop exactly
     * after a synchronous native-frame callback without changing PERFORMANCE raster semantics.
     *
     * @return the exact number of master ticks executed
     */
    public int runTicksUntilStop(int ticks, BooleanSupplier stop) {
        if (ticks < 0) {
            throw new IllegalArgumentException("ticks must be non-negative");
        }
        Objects.requireNonNull(stop, "stop");
        if (executionMode == ExecutionMode.PERFORMANCE
                && bootCompatibilityResolved
                && debugInstrumentation == null
                && !debugRetirementTrackingActive
                && !debugHistoryReplay) {
            // Benchmark preconditioning needs the PERFORMANCE compositor but must stop at the
            // exact synchronous frame callback. Keep this rare seam scalar so neither a coarse
            // CPU epoch nor the ordinary fused boundary can retire a post-endpoint tick.
            gpu.setPerformanceScanlineEnabled(true);
            try {
                int executed = 0;
                while (executed < ticks && !stop.getAsBoolean()) {
                    tick();
                    executed++;
                }
                return executed;
            } finally {
                sound.materializePendingPerformanceTicks();
                gpu.setPerformanceScanlineEnabled(false);
            }
        }
        int executed = 0;
        while (executed < ticks && !stop.getAsBoolean()) {
            tick();
            executed++;
        }
        return executed;
    }

    /**
     * Advances one measured benchmark window, preserving the PERFORMANCE scheduler while
     * allowing a synchronous native-frame callback to terminate the window immediately.
     *
     * <p>This is intentionally a separate seam from {@link #runTicksUntilStop(int,
     * BooleanSupplier)}.  Preconditioning and other callers retain the public stop-aware API;
     * only the benchmark's armed measurement uses this owner-thread boundary.  In PERFORMANCE
     * mode the same epoch/scanline bulk paths as {@link #runTicks(long)} are used, and the stop
     * predicate is checked at each scalar hand-off so a frame-600 callback cannot be followed by
     * another scalar tick or bulk packet.</p>
     *
     * @return the exact number of master ticks executed
     */
    public int runMeasuredTicksUntilStop(int ticks, BooleanSupplier stop) {
        if (ticks < 0) {
            throw new IllegalArgumentException("ticks must be non-negative");
        }
        Objects.requireNonNull(stop, "stop");
        if (executionMode == ExecutionMode.PERFORMANCE
                && bootCompatibilityResolved
                && debugInstrumentation == null
                && !debugRetirementTrackingActive
                && !debugHistoryReplay) {
            return Math.toIntExact(runPerformanceTicksUntilStop(ticks, stop));
        }
        int executed = 0;
        while (executed < ticks && !stop.getAsBoolean()) {
            tick();
            executed++;
        }
        return executed;
    }

    /** Stop-aware counterpart of the ordinary PERFORMANCE frame loop. */
    private long runPerformanceTicksUntilStop(long ticks, BooleanSupplier stop) {
        if (isNativeCgbPerformanceEpochTopology()) {
            return runNativeCgbPerformanceTicksUntilStop(ticks, stop);
        }
        if (isNormalSpeedPerformanceEpochTopology()) {
            return runNormalSpeedPerformanceTicksUntilStop(ticks, stop);
        }
        return runFallbackPerformanceTicksUntilStop(ticks, stop);
    }

    /** Fallback PERFORMANCE scheduler used by CGB compatibility, SGB, and other topologies. */
    private long runFallbackPerformanceTicksUntilStop(long ticks, BooleanSupplier stop) {
        gpu.setPerformanceScanlineEnabled(true);
        long remaining = ticks;
        try {
            while (remaining > 0 && !stop.getAsBoolean()) {
                if (cpu.getState() == Cpu.State.HALTED) {
                    int committed = tryPerformanceSettledHaltSpan(remaining);
                    if (committed > 0) {
                        remaining -= committed;
                        continue;
                    }
                }
                int cpuSpanLimit = cpu.performancePhaseOnlySpanLimit();
                int span = tryPerformancePhaseOnlySpan(remaining, cpuSpanLimit);
                boolean reachesCpuBoundary = span > 0 && span == cpuSpanLimit;
                if (span > 0) {
                    remaining -= span;
                    if (reachesCpuBoundary && remaining > 0) {
                        if (stop.getAsBoolean()) {
                            break;
                        }
                        tick();
                        remaining--;
                        if (stop.getAsBoolean()) {
                            break;
                        }
                    }
                    continue;
                }
                if (stop.getAsBoolean()) {
                    break;
                }
                tick();
                remaining--;
                // A synchronous frame callback may have changed the stop predicate during tick.
                if (stop.getAsBoolean()) {
                    break;
                }
            }
        } finally {
            sound.materializePendingPerformanceTicks();
            gpu.setPerformanceScanlineEnabled(false);
        }
        return ticks - remaining;
    }

    /** Stop-aware native-CGB epoch scheduler; bulk packets never publish frame callbacks. */
    private long runNativeCgbPerformanceTicksUntilStop(long ticks, BooleanSupplier stop) {
        gpu.setPerformanceScanlineEnabled(true);
        long remaining = ticks;
        try {
            while (remaining > 0 && !stop.getAsBoolean()
                    && isNativeCgbPerformanceEpochTopology()) {
                int committed = tryPerformanceEpoch(remaining);
                if (committed > 0) {
                    remaining -= committed;
                    continue;
                }
                if (committed < 0) {
                    int deadline = -committed;
                    do {
                        if (stop.getAsBoolean()) {
                            break;
                        }
                        nativeCgbScalarOwner = !warmResetRequested
                                && debugInstrumentation == null
                                && !debugRetirementTrackingActive
                                && !debugHistoryReplay;
                        tick();
                        remaining--;
                        deadline--;
                        if (stop.getAsBoolean()) {
                            break;
                        }
                    } while (deadline > 0 && remaining > 0
                            && canContinueNativeCgbNegativeStatLease());
                    if (remaining > 0 && !isNativeCgbPerformanceEpochTopology()) {
                        break;
                    }
                    continue;
                }
                if (cpu.getState() == Cpu.State.HALTED) {
                    committed = tryPerformanceSettledNativeCgbHaltSpan(remaining);
                    if (committed > 0) {
                        remaining -= committed;
                        continue;
                    }
                }
                if (stop.getAsBoolean()) {
                    break;
                }
                nativeCgbScalarOwner = !warmResetRequested
                        && debugInstrumentation == null
                        && !debugRetirementTrackingActive
                        && !debugHistoryReplay;
                tick();
                remaining--;
                if (stop.getAsBoolean()) {
                    break;
                }
            }
        } finally {
            nativeCgbScalarOwner = false;
            sound.materializePendingPerformanceTicks();
            gpu.setPerformanceScanlineEnabled(false);
        }
        if (remaining > 0 && !stop.getAsBoolean()
                && !isNativeCgbPerformanceEpochTopology()) {
            return (ticks - remaining) + runFallbackPerformanceTicksUntilStop(remaining, stop);
        }
        return ticks - remaining;
    }

    /** Stop-aware fixed-x1 scheduler; mode-3 packets retain their raster fast path. */
    private long runNormalSpeedPerformanceTicksUntilStop(long ticks, BooleanSupplier stop) {
        gpu.setPerformanceScanlineEnabled(true);
        long remaining = ticks;
        try {
            while (remaining > 0 && !stop.getAsBoolean()
                    && isNormalSpeedPerformanceEpochTopology()) {
                int committed = tryNormalSpeedPerformanceEpoch(remaining);
                if (committed > 0) {
                    remaining -= committed;
                    continue;
                }
                if (cpu.getState() == Cpu.State.HALTED) {
                    committed = tryPerformanceSettledHaltSpan(remaining);
                    if (committed > 0) {
                        remaining -= committed;
                        continue;
                    }
                }
                int cpuSpanLimit = cpu.performancePhaseOnlySpanLimit();
                int phaseSpan = tryPerformancePhaseOnlySpan(remaining, cpuSpanLimit);
                if (phaseSpan > 0) {
                    remaining -= phaseSpan;
                    continue;
                }
                if (stop.getAsBoolean()) {
                    break;
                }
                tick();
                remaining--;
                if (stop.getAsBoolean()) {
                    break;
                }
            }
        } finally {
            sound.materializePendingPerformanceTicks();
            gpu.setPerformanceScanlineEnabled(false);
        }
        if (remaining > 0 && !stop.getAsBoolean()
                && !isNormalSpeedPerformanceEpochTopology()) {
            return (ticks - remaining) + runFallbackPerformanceTicksUntilStop(remaining, stop);
        }
        return ticks - remaining;
    }

    /**
     * PERFORMANCE-only frame loop. At a normal-speed CPU boundary the scalar tick remains the
     * authority; between boundaries, a short span can advance the independent peripherals while
     * the CPU/PPU/STAT hot paths use their bulk phase methods. ACCURACY retains the exact scalar
     * behavior.
     */
    private long runPerformanceTicks(long ticks) {
        gpu.setPerformanceScanlineEnabled(true);
        try {
            long frameEvents = 0;
            long remaining = ticks;
            while (remaining > 0) {
                if (cpu.getState() == Cpu.State.HALTED) {
                    int committed = tryPerformanceSettledHaltSpan(remaining);
                    if (committed > 0) {
                        remaining -= committed;
                        continue;
                    }
                }
                int cpuSpanLimit = cpu.performancePhaseOnlySpanLimit();
                int span = tryPerformancePhaseOnlySpan(remaining, cpuSpanLimit);
                boolean reachesCpuBoundary = span > 0 && span == cpuSpanLimit;
                if (span > 0) {
                    remaining -= span;
                    // The fourth tick is still the scalar CPU-boundary authority. Fuse it here
                    // only when the bulk span consumed the whole phase distance; a peripheral
                    // horizon-shortened span must return through the normal preflight loop.
                    if (reachesCpuBoundary && remaining > 0) {
                        if (tick()) {
                            frameEvents++;
                        }
                        remaining--;
                    }
                    continue;
                }
                if (tick()) {
                    frameEvents++;
                }
                remaining--;
            }
            return frameEvents;
        } finally {
            // A caller may mix the frame-sized seam with scalar/debug/state operations. Sound's
            // PERFORMANCE scheduler can have a pending sub-sample span even when this method
            // exits at the caller's tick budget, so publish canonical channel state before the
            // next operation observes the machine.
            sound.materializePendingPerformanceTicks();
            // A caller may mix the frame-sized seam with scalar/debug operations. Suppress new
            // direct-line arms after this call; an already armed line is materialized at the
            // first scalar tick, while a subsequent PERFORMANCE call can resume it safely.
            gpu.setPerformanceScanlineEnabled(false);
        }
    }

    /**
     * Native-CGB double-speed scheduler. Transient event seams consume one scalar tick and then
     * retry the epoch; DMA, STAT, audio, and raster boundaries therefore cannot disable the lane
     * for the rest of a controller batch. If a STOP instruction leaves double speed, the
     * untouched ordinary scheduler owns the remainder.
     *
     * <p>This is the explicit coarse contract of PERFORMANCE mode: for at most 54 master dots,
     * ordinary CPU work observes a frozen peripheral view and peripheral IRQ visibility is
     * coalesced at the packet boundary. An actual external read flushes the completed prefix
     * before it is delegated, while an external write is published once after the current
     * machine-cycle packet. Thus an access can retain at most the current dot's pre-CPU skew;
     * mode/line, audio-sample, input-poll, timer-overflow, DMA, and frame callbacks remain scalar.
     * ACCURACY and every observation/history/stop-aware path bypass this scheduler.</p>
     */
    private long runNativeCgbPerformanceTicks(long ticks) {
        gpu.setPerformanceScanlineEnabled(true);
        long frameEvents = 0;
        long remaining = ticks;
        try {
            while (remaining > 0 && isNativeCgbPerformanceEpochTopology()) {
                int committed = tryPerformanceEpoch(remaining);
                if (committed > 0) {
                    remaining -= committed;
                    continue;
                }
                if (committed < 0) {
                    int deadline = -committed;
                    do {
                        nativeCgbScalarOwner = !warmResetRequested
                                && debugInstrumentation == null
                                && !debugRetirementTrackingActive
                                && !debugHistoryReplay;
                        if (tick()) {
                            frameEvents++;
                        }
                        remaining--;
                        deadline--;
                    } while (deadline > 0 && remaining > 0
                            && canContinueNativeCgbNegativeStatLease());
                    if (remaining > 0 && !isNativeCgbPerformanceEpochTopology()) {
                        break;
                    }
                    continue;
                }
                if (cpu.getState() == Cpu.State.HALTED) {
                    committed = tryPerformanceSettledNativeCgbHaltSpan(remaining);
                    if (committed > 0) {
                        remaining -= committed;
                        continue;
                    }
                }
                nativeCgbScalarOwner = !warmResetRequested
                        && debugInstrumentation == null
                        && !debugRetirementTrackingActive
                        && !debugHistoryReplay;
                if (tick()) {
                    frameEvents++;
                }
                remaining--;
            }
        } finally {
            nativeCgbScalarOwner = false;
            sound.materializePendingPerformanceTicks();
            gpu.setPerformanceScanlineEnabled(false);
        }
        if (remaining > 0) {
            frameEvents += runPerformanceTicks(remaining);
        }
        return frameEvents;
    }

    /**
     * Fixed-x1 normal-speed coarse scheduler. Trusted rendered raster spans may join every
     * supported CPU epoch; native CGB x1 may also use the exact mode-2 OAM transaction. STAT
     * and line edges, DMA, IRQ boundaries, and every rejected topology retain the established
     * normal-speed PERFORMANCE scheduler.
     */
    private long runNormalSpeedPerformanceTicks(long ticks) {
        gpu.setPerformanceScanlineEnabled(true);
        long frameEvents = 0;
        long remaining = ticks;
        try {
            while (remaining > 0 && isNormalSpeedPerformanceEpochTopology()) {
                int committed = tryNormalSpeedPerformanceEpoch(remaining);
                if (committed > 0) {
                    remaining -= committed;
                    continue;
                }

                // Preserve the pre-existing long settled-HALT side entrance before falling
                // back to the ordinary scalar/phase scheduler.
                if (cpu.getState() == Cpu.State.HALTED) {
                    committed = tryPerformanceSettledHaltSpan(remaining);
                    if (committed > 0) {
                        remaining -= committed;
                        continue;
                    }
                }

                // On a mode-2/checkpoint/event miss, retain the legacy 1-3-dot normal-speed
                // packet rather than regressing every non-boundary dot to the scalar forest.
                int cpuSpanLimit = cpu.performancePhaseOnlySpanLimit();
                int phaseSpan = tryPerformancePhaseOnlySpan(remaining, cpuSpanLimit);
                if (phaseSpan > 0) {
                    remaining -= phaseSpan;
                    continue;
                }

                if (tick()) {
                    frameEvents++;
                }
                remaining--;
            }
        } finally {
            sound.materializePendingPerformanceTicks();
            gpu.setPerformanceScanlineEnabled(false);
        }
        if (remaining > 0) {
            frameEvents += runPerformanceTicks(remaining);
        }
        return frameEvents;
    }

    /**
     * Attempts one non-owning normal-speed phase-only packet. The enclosing run loop owns Sound
     * materialization and the PERFORMANCE scanline-enable lifecycle.
     */
    private int tryPerformancePhaseOnlySpan(long remaining, int cpuSpanLimit) {
        boolean nativeCgbNormalSpeed = isNativeCgbNormalSpeedPerformanceEpochTopology();
        // Most rejected Crystal dots are CPU-state/STAT-phase misses. Keep those allocation-free
        // predicates ahead of the timer/audio/raster horizon walks; the identical checks remain
        // adjacent to commit below so a future volatile input cannot turn this into a lease.
        if (cpuSpanLimit <= 0
                || !cpu.performancePhaseOnlySpanEligible()
                || !(nativeCgbNormalSpeed
                        ? cpu.performanceNativeCgbNormalSpeedNoPendingPpuReadPhase()
                        : cpu.performanceNoPendingPpuReadPhase())) {
            return 0;
        }
        // SGB's JOYP packet receiver has no tick-driven state. When its cached input
        // eligibility is false, the span cannot commit anyway; reject before walking the
        // relatively expensive timer/PPU/STAT horizons. Stable SGB sessions remain eligible
        // through Joypad's exact write-clocked contract below.
        if (isSgbPerformanceTopology() && !joypad.isPerformanceQuietSpanStillEligible()) {
            return 0;
        }
        // Build one primitive packet plan. Every component horizon is evaluated once against
        // the already-shortened tail; trusted commits do not repeat the walks.
        int span = cpuSpanLimit;
        PerformancePhasePpuPlan ppuPlan = PerformancePhasePpuPlan.QUIET;
        boolean directRasterSpan = false;
        boolean steadyRasterSpan = false;
        if (span > 0) {
            span = Math.min(span, timer.performanceQuietSpanLimit(span));
            if (span <= 0) {
                return 0;
            }
            span = Math.min(span, serialPort.performanceQuietSpanLimit(span));
            if (span <= 0) {
                return 0;
            }
            span = Math.min(span, joypad.performanceQuietSpanLimit(span));
            if (span <= 0) {
                return 0;
            }
            span = Math.min(span, sound.performanceQuietSpanLimit(span));
            if (span <= 0) {
                return 0;
            }
            if (cartridgeClocked) {
                span = Math.min(span, cartridge.performanceQuietSpanLimit(span));
                if (span <= 0) {
                    return 0;
                }
            }
            if (slotCartridgeClocked) {
                span = Math.min(span, slotCartridge.performanceQuietSpanLimit(span));
                if (span <= 0) {
                    return 0;
                }
            }
            if (gbc) {
                span = Math.min(span, infraredPort.performanceQuietSpanLimit(span));
                if (span <= 0) {
                    return 0;
                }
            }
            int gpuSpanLimit = gpu.performanceQuietSpanLimit();
            if (gpuSpanLimit > 0) {
                span = Math.min(span, gpuSpanLimit);
                directRasterSpan = span > 0 && gpu.isPerformanceScanlineCursorActive();
                steadyRasterSpan = span > 0 && !directRasterSpan
                        && gpu.isPerformanceSteadyCursorActive();
            } else if (isCgbNormalSpeedPerformanceEpochTopology()) {
                // Ordinary CGB x1 keeps the color OAM-reader semantics in both native and
                // compatibility modes. Admit only the existing allocation-free CGB scan
                // transaction; a fixed-point or reader miss leaves the complete one-to-three-
                // dot phase on the scalar path.
                int mode2SpanLimit =
                        gpu.performanceCgbNormalSpeedMode2PhaseSpanLimit(span);
                if (mode2SpanLimit > 0) {
                    span = Math.min(span, mode2SpanLimit);
                    ppuPlan = PerformancePhasePpuPlan.CGB_NORMAL_SPEED_MODE2;
                } else {
                    span = 0;
                }
            } else if (isPhysicalDmgPerformanceEpochTopology()
                    || isSgbPerformanceTopology()) {
                // Physical-DMG and SGB mode 2 have no quiet-output horizon: the OAM reader
                // itself is the only PPU work in these one-to-three non-CPU dots. Keep this
                // explicit plan separate from the settled quiet path so CGB/compat sessions
                // cannot accidentally enter the DMG arithmetic lane.
                int mode2SpanLimit = gpu.performancePhysicalDmgMode2PhaseSpanLimit(span);
                if (mode2SpanLimit > 0) {
                    span = Math.min(span, mode2SpanLimit);
                    ppuPlan = PerformancePhasePpuPlan.PHYSICAL_DMG_MODE2;
                } else {
                    span = 0;
                }
            } else {
                span = 0;
            }
            span = Math.min(span, statRegister.performanceQuietSpanLimit(span));
        }
        if (remaining < span) {
            span = (int) remaining;
        }
        int entryStatReadPhaseFlags = cpu.getStatReadPhaseFlags();
        if (span <= 0
                || warmResetRequested
                || speedSwitchTailTicks != 0
                || !cpu.performancePhaseOnlySpanEligible()
                || !(nativeCgbNormalSpeed
                        ? cpu.performanceNativeCgbNormalSpeedNoPendingPpuReadPhase()
                        : cpu.performanceNoPendingPpuReadPhase())
                || dma.isTransferInProgress()
                || dma.requiresClockTick(cpu.getState() == Cpu.State.HALTED)
                || gbc && (hdma.hasActiveOrPendingTransfer()
                        || !hdma.isPerformanceInactiveRequestClockStable())
                // This is intentionally the one post-preflight volatile Joypad check.
                || !joypad.isPerformanceQuietSpanStillEligible()) {
            return 0;
        }
        tickPerformanceQuietSpan(span, ppuPlan, directRasterSpan, steadyRasterSpan,
                entryStatReadPhaseFlags);
        performanceBulkSpanCount++;
        performanceBulkTicks += span;
        return span;
    }

    /** Stable topology only; every tick-local blocker stays in {@link #canStartPerformanceEpoch()}. */
    private boolean isNativeCgbPerformanceEpochTopology() {
        return gbc && !speedMode.isDmgCompat() && speedMode.getSpeedMode() == 2;
    }

    private boolean isSgbPerformanceTopology() {
        return hardwareProfile.family() == HardwareProfile.Family.SGB;
    }

    /** CGB hardware running a non-color cartridge at its normal clock. */
    private boolean isCgbCompatibilityPerformanceTopology() {
        return gbc && speedMode.isDmgCompat() && speedMode.getSpeedMode() == 1;
    }

    /** Exact measured epoch row: ordinary CGB only (CGB0 compatibility remains scalar). */
    private boolean isCgbCompatibilityPerformanceEpochTopology() {
        return hardwareProfile == HardwareProfileRegistry.CGB
                && isCgbCompatibilityPerformanceTopology();
    }

    /** Exact native-color fixed-x1 epoch row: ordinary CGB only. */
    private boolean isNativeCgbNormalSpeedPerformanceEpochTopology() {
        return hardwareProfile == HardwareProfileRegistry.CGB
                && gbc && !speedMode.isDmgCompat() && speedMode.getSpeedMode() == 1;
    }

    /** Ordinary-CGB fixed-x1 CPU epochs retain the complete CGB peripheral plane. */
    private boolean isCgbNormalSpeedPerformanceEpochTopology() {
        return isCgbCompatibilityPerformanceEpochTopology()
                || isNativeCgbNormalSpeedPerformanceEpochTopology();
    }

    /** Fixed four-master-dot CPU epoch topologies; CGB software still owns CGB peripherals. */
    private boolean isNormalSpeedPerformanceEpochTopology() {
        return isPhysicalDmgPerformanceEpochTopology()
                || isCgbNormalSpeedPerformanceEpochTopology()
                || isSgbPerformanceTopology();
    }

    private boolean canContinueNativeCgbNegativeStatLease() {
        if (!isNativeCgbPerformanceEpochTopology()) {
            return false;
        }
        Cpu.State state = cpu.getState();
        if (state == Cpu.State.HALTED || state == Cpu.State.STOPPED
                || state == Cpu.State.SPEED_SWITCH || state == Cpu.State.LOCKED) {
            return false;
        }
        if (warmResetRequested
                || debugInstrumentation != null
                || debugHistoryReplay
                || debugRetirementTrackingActive
                || speedSwitchTailTicks != 0) {
            return false;
        }
        if (statRegister.performanceSettledHaltSpanLimit(1) == 0) {
            return true;
        }
        return !cpu.performanceEpochEntryEligible();
    }

    /** Physical DMG/MGB only; SGB/SGB2 join just the separately guarded mode-2 phase lane. */
    private boolean isPhysicalDmgPerformanceEpochTopology() {
        return hardwareProfile.family() == HardwareProfile.Family.DMG
                && speedMode.getSpeedMode() == 1;
    }

    private boolean canStartPerformanceEpoch() {
        return !warmResetRequested
                && speedSwitchTailTicks == 0
                && !debugHistoryReplay
                && debugInstrumentation == null
                && !debugRetirementTrackingActive
                && gpu.isLcdEnabled()
                && !dma.isTransferInProgress()
                && !dma.requiresClockTick(false)
                && !hdma.hasActiveOrPendingTransfer()
                && hdma.isPerformanceInactiveRequestClockStable()
                && !cartridgeClocked
                && !slotCartridgeClocked
                && serialPort.performanceEpochIdle(Cpu.PERFORMANCE_EPOCH_MAX_TICKS)
                && infraredPort.performanceEpochIdle(Cpu.PERFORMANCE_EPOCH_MAX_TICKS);
    }

    /**
     * Attempts one bounded native-CGB epoch.
     *
     * @return committed ticks when positive, zero for an ordinary rejection, or the negative
     *         uncommitted scalar deadline when only STAT rejected an otherwise valid plan
     */
    private int tryPerformanceEpoch(long remaining) {
        if (remaining <= 0 || !cpu.performanceEpochEntryEligible()
                || !canStartPerformanceEpoch()) {
            return 0;
        }
        int span = (int) Math.min((long) Cpu.PERFORMANCE_EPOCH_MAX_TICKS, remaining);
        span = Math.min(span, timer.performanceEpochSpanLimit(span));
        span = Math.min(span, sound.performanceEpochSpanLimit(span));
        span = Math.min(span, joypad.performanceSettledHaltSpanLimit(span));

        PerformanceEpochPpuPlan ppuPlan;
        int rasterSpan = gpu.performanceEpochSpanLimit(span);
        if (rasterSpan > 0) {
            span = Math.min(span, rasterSpan);
            ppuPlan = PerformanceEpochPpuPlan.TRUSTED_RASTER;
        } else {
            int mode2BulkSpan = gpu.performanceEpochMode2BulkSpanLimit(span);
            if (mode2BulkSpan > 0) {
                span = Math.min(span, mode2BulkSpan);
                ppuPlan = PerformanceEpochPpuPlan.MODE2_BULK;
            } else {
                span = Math.min(span, gpu.performanceEpochMode2ReplaySpanLimit(span));
                ppuPlan = PerformanceEpochPpuPlan.MODE2_REPLAY;
            }
        }
        if (span <= 0) {
            return 0;
        }

        int statSpan = statRegister.performanceSettledHaltSpanLimit(span);
        if (statSpan == 0) {
            if (warmResetRequested || !joypad.isPerformanceQuietSpanStillEligible()) {
                return 0;
            }
            return -span;
        }
        span = Math.min(span, statSpan);
        if (span <= 0) {
            return 0;
        }

        if (warmResetRequested || !joypad.isPerformanceQuietSpanStillEligible()) {
            return 0;
        }

        performanceEpochDirectRaster = gpu.isPerformanceScanlineCursorActive();
        performanceEpochSteadyRaster = !performanceEpochDirectRaster
                && gpu.isPerformanceSteadyCursorActive();
        performanceEpochPpuPlan = ppuPlan;
        performanceEpochPrefixCommitted = 0;
        if (performanceEpochPrefixCommitter == null) {
            performanceEpochPrefixCommitter = this::commitPerformanceEpochPrefix;
        }
        cpu.setPerformanceEpochPrefixCommitter(performanceEpochPrefixCommitter);
        int elapsed;
        try {
            elapsed = cpu.runPerformanceEpoch(span);
        } finally {
            cpu.setPerformanceEpochPrefixCommitter(null);
        }
        if (elapsed <= 0) {
            return 0;
        }
        int suffix = elapsed - performanceEpochPrefixCommitted;
        if (suffix > 0) {
            commitPerformanceEpochPeripherals(suffix);
        }
        // The observer defers an unsafe write.  Replaying after the complete old-state
        // prefix preserves the boundary semantics and guarantees one delegated write.
        cpu.replayPerformanceEpochJournal();
        boolean divReset = timer.consumeDivReset();
        if (divReset) {
            sound.tickFrameSequencer(true);
            sound.commitFrameSequencerClock();
            serialPort.onDivReset();
        }
        boolean halted = cpu.getState() == Cpu.State.HALTED;
        if (halted) {
            hdma.reconcilePerformanceRunningEpochHaltEntryTrusted();
        }
        hdma.onCpuHaltState(halted);
        boolean pendingPeripheralSample = cpu.hasPendingPeripheralSample();
        if (pendingPeripheralSample) {
            // The epoch can retire HALT immediately before the caller's budget ends. Publish
            // the otherwise-inactive OAM-DMA pause latch in the same rare branch which already
            // owns HALT's post-peripheral sample, so ordinary epochs gain no new hot check.
            if (halted && dma.requiresClockTick(true)) {
                dma.tick(true, true);
            }
        }
        if (halted) {
            // Peripheral commits have already published the final GPU dot. Replay the scalar
            // post-HALT timing seam so HDMA clears haltEnteredThisTick and the CPU observes the
            // same held-opcode latch at the exact epoch endpoint.
            hdma.onGpuTiming(gpu.getLine(), gpu.getTicksInLine(),
                    gpu.isStatModeLatchRephasedBySpeedSwitch());
            cpu.latchHdmaHaltOpcode(hdma.isHaltRequestLatched());
        }
        if (pendingPeripheralSample) {
            cpu.onPeripheralsTicked();
        }

        performanceEpochCount++;
        performanceEpochTicks += elapsed;
        performanceEpochMaxTicks = Math.max(performanceEpochMaxTicks, elapsed);
        return elapsed;
    }

    /**
     * Attempts one native-CGB double-speed settled-HALT packet. HALT has no CPU bus work once
     * its entry and wake samples have settled, but the packet still stops before every timer,
     * audio, input, PPU, STAT, DMA, and frame boundary. Unlike the ordinary epoch this path does
     * not borrow a ROM mapping or change the CPU instruction sequencer.
     */
    private int tryPerformanceSettledNativeCgbHaltSpan(long remaining) {
        if (remaining <= 0 || !cpu.performanceNativeCgbSettledHaltSpanEligible()
                || !canStartNativeCgbSettledHaltSpan()) {
            return 0;
        }
        int span = (int) Math.min((long) SETTLED_HALT_PERFORMANCE_MAX_SPAN, remaining);
        span = Math.min(span, timer.performanceEpochSpanLimit(span));
        // This is a settled no-bus HALT transaction, so ordinary compact samples cannot race a
        // deferred CPU sound-register write. Only the synchronous host callback stays scalar.
        span = Math.min(span, sound.performanceQuietSpanLimit(span));
        span = Math.min(span, joypad.performanceSettledHaltSpanLimit(span));
        if (span <= 0 || !serialPort.performanceEpochIdle(span)
                || !infraredPort.performanceEpochIdle(span)) {
            return 0;
        }
        PerformanceEpochPpuPlan ppuPlan;
        int rasterSpan = gpu.performanceEpochSpanLimit(span);
        if (rasterSpan > 0) {
            span = Math.min(span, rasterSpan);
            ppuPlan = PerformanceEpochPpuPlan.TRUSTED_RASTER;
        } else {
            int mode2BulkSpan = gpu.performanceEpochMode2BulkSpanLimit(span);
            if (mode2BulkSpan > 0) {
                span = Math.min(span, mode2BulkSpan);
                ppuPlan = PerformanceEpochPpuPlan.MODE2_BULK;
            } else {
                span = Math.min(span, gpu.performanceEpochMode2ReplaySpanLimit(span));
                ppuPlan = PerformanceEpochPpuPlan.MODE2_REPLAY;
            }
        }
        span = Math.min(span, statRegister.performanceSettledHaltSpanLimit(span));
        if (span <= 0) {
            return 0;
        }

        // Keep the final volatile reads adjacent to the commit. A host reset/input mutation
        // published before this point belongs to the scalar owner; one published afterwards is
        // visible to the next packet, just as in the ordinary native epoch.
        if (warmResetRequested
                || speedSwitchTailTicks != 0
                || !cpu.performanceNoPendingPpuReadPhase()
                || !serialPort.performanceEpochIdle(span)
                || !infraredPort.performanceEpochIdle(span)
                || !joypad.isPerformanceQuietSpanStillEligible()
                || !canStartNativeCgbSettledHaltSpan()) {
            return 0;
        }

        boolean directRaster = gpu.isPerformanceScanlineCursorActive();
        boolean steadyRaster = !directRaster && gpu.isPerformanceSteadyCursorActive();
        tickPerformanceSettledNativeCgbHaltSpan(span, ppuPlan, directRaster, steadyRaster);
        performanceBulkSpanCount++;
        performanceBulkTicks += span;
        performanceBulkMaxTicks = Math.max(performanceBulkMaxTicks, span);
        return span;
    }

    private boolean canStartNativeCgbSettledHaltSpan() {
        return isNativeCgbPerformanceEpochTopology()
                && !warmResetRequested
                && speedSwitchTailTicks == 0
                && !debugHistoryReplay
                && debugInstrumentation == null
                && !debugRetirementTrackingActive
                && gpu.isLcdEnabled()
                && !dma.isTransferInProgress()
                && !dma.requiresClockTick(true)
                && !hdma.hasActiveOrPendingTransfer()
                && !hdma.hasPendingHblankTransfer()
                && !hdma.isHaltRequestLatched()
                && !hdma.holdsHblankSpeedSwitchTail()
                && !hdma.pausesOamDmaForSpeedSwitchBurst()
                && !hdma.requiresCpuHdmaPhaseFlags()
                && hdma.isPerformanceInactiveRequestClockStable()
                && !cartridgeClocked
                && !slotCartridgeClocked;
    }

    private void tickPerformanceSettledNativeCgbHaltSpan(
            int ticks, PerformanceEpochPpuPlan ppuPlan, boolean directRaster,
            boolean steadyRaster) {
        timer.tickPerformanceEpochTrusted(ticks);
        sound.tickFrameSequencer(false);
        assert !sound.hasPendingFrameSequencerClock()
                : "frame sequencer edge crossed a native-CGB settled-HALT span";
        sound.commitFrameSequencerClock();
        cpu.advancePerformanceNativeCgbSettledHaltSpanTrusted(ticks);
        sound.tickPerformanceQuietSpan(ticks);
        serialPort.tickPerformanceEpochIdle(ticks);
        infraredPort.tickPerformanceEpochIdle(ticks);
        joypad.tickPerformanceQuietSpanTrusted(ticks);
        hdma.advancePerformanceInactiveRequestClockTrusted(ticks);

        switch (ppuPlan) {
            case TRUSTED_RASTER -> {
                gpu.advancePerformanceEpochQuietSpanTrusted(ticks, directRaster, steadyRaster);
                statRegister.tickPerformanceQuietSpanTrusted(ticks);
            }
            case MODE2_BULK -> {
                gpu.advancePerformanceMode2QuietSpanTrusted(ticks);
                statRegister.tickPerformanceQuietSpanTrusted(ticks);
            }
            case MODE2_REPLAY -> {
                for (int i = 0; i < ticks; i++) {
                    gpu.tick();
                    statRegister.tick();
                }
            }
            case NONE -> throw new IllegalStateException(
                    "native-CGB settled-HALT span has no PPU plan");
        }
        hdma.onGpuTiming(gpu.getLine(), gpu.getTicksInLine(),
                gpu.isStatModeLatchRephasedBySpeedSwitch());
        cpu.latchHdmaHaltOpcode(hdma.isHaltRequestLatched());
    }

    /** Routes the fixed-x1 epoch through the SGB raster gate before generic preflight. */
    private int tryNormalSpeedPerformanceEpoch(long remaining) {
        if (isSgbPerformanceTopology()) {
            return trySgbPerformanceEpoch(remaining);
        }
        return tryFixedNormalSpeedPerformanceEpoch(remaining);
    }

    /**
     * SGB/SGB2 running-CPU lane. The direct scanline horizon is cheap to reject while the PPU
     * is in mode 2 or a scalar mode-3 line, so those dots retain the existing scheduler without
     * walking the timer/audio/serial/CPU epoch preflight.
     */
    private int trySgbPerformanceEpoch(long remaining) {
        if (remaining <= 0) {
            return 0;
        }
        int requested = (int) Math.min((long) Cpu.PERFORMANCE_EPOCH_MAX_TICKS, remaining);
        if (gpu.performancePhysicalDmgEpochSpanLimit(requested) <= 0) {
            return 0;
        }
        return tryFixedNormalSpeedPerformanceEpoch(remaining);
    }

    /** Fixed-width normal-speed epoch implementation shared by SGB and CGB hardware. */
    private int tryFixedNormalSpeedPerformanceEpoch(long remaining) {
        boolean cgbHardware = isCgbNormalSpeedPerformanceEpochTopology();
        boolean nativeCgbNormalSpeed = isNativeCgbNormalSpeedPerformanceEpochTopology();
        boolean sgb = isSgbPerformanceTopology();
        boolean lcdOffEpoch = nativeCgbNormalSpeed && !gpu.isLcdEnabled();
        boolean cpuEntryEligible = nativeCgbNormalSpeed
                ? cpu.performanceNativeCgbNormalSpeedEpochEntryEligible()
                : cpu.performanceNormalSpeedEpochEntryEligible(cgbHardware);
        if (remaining <= 0 || !cpuEntryEligible
                || !canStartNormalSpeedPerformanceEpoch(
                        cgbHardware, nativeCgbNormalSpeed)) {
            return 0;
        }
        int span = (int) Math.min((long) Cpu.PERFORMANCE_EPOCH_MAX_TICKS, remaining);
        if (nativeCgbNormalSpeed && cartridgeClocked) {
            // Native x1 keeps every decoded mapper/RTC access on the scalar boundary.  A
            // clocked primary cartridge may therefore join only through its independently
            // proven arithmetic clock horizon; the conservative MemoryController default
            // rejects unknown clocked hardware here.
            span = Math.min(span, cartridge.performanceQuietSpanLimit(span));
        }
        span = Math.min(span, timer.performanceNormalSpeedEpochSpanLimit(span, cgbHardware));
        span = Math.min(span, nativeCgbNormalSpeed || sgb
                ? sound.performanceFencedEpochSpanLimit(span)
                : sound.performanceEpochSpanLimit(span));
        span = Math.min(span, joypad.performanceSettledHaltSpanLimit(span));
        if (cgbHardware) {
            span = Math.min(span, serialPort.performanceNormalSpeedEpochIdle(span, true)
                    ? span : 0);
            span = Math.min(span, infraredPort.performanceSettledHaltSpanLimit(span));
        } else {
            span = Math.min(span, serialPort.performanceNormalSpeedEpochIdle(span, false)
                    ? span : 0);
        }

        PerformanceEpochPpuPlan ppuPlan;
        if (lcdOffEpoch) {
            span = Math.min(span, performanceLcdOffEpochSpanLimit(span));
            span = Math.min(span,
                    gpu.performanceNativeCgbNormalSpeedLcdOffSpanLimit(span));
            if (span <= 0) {
                return 0;
            }
            ppuPlan = PerformanceEpochPpuPlan.LCD_OFF;
        } else if (nativeCgbNormalSpeed) {
            int rasterSpan = gpu.performanceEpochSpanLimit(span);
            if (rasterSpan > 0) {
                span = Math.min(span, rasterSpan);
                ppuPlan = PerformanceEpochPpuPlan.TRUSTED_RASTER;
            } else {
                int mode2Span = gpu.performanceCgbNormalSpeedMode2PhaseSpanLimit(span);
                if (mode2Span <= 0) {
                    return 0;
                }
                span = Math.min(span, mode2Span);
                ppuPlan = PerformanceEpochPpuPlan.MODE2_BULK;
            }
        } else {
            int rasterSpan = cgbHardware
                    ? gpu.performanceEpochSpanLimit(span)
                    : gpu.performancePhysicalDmgEpochSpanLimit(span);
            if (rasterSpan <= 0) {
                return 0;
            }
            span = Math.min(span, rasterSpan);
            ppuPlan = PerformanceEpochPpuPlan.TRUSTED_RASTER;
        }
        span = Math.min(span, statRegister.performanceSettledHaltSpanLimit(span));
        if (span <= 0) {
            return 0;
        }

        if (warmResetRequested || !joypad.isPerformanceQuietSpanStillEligible()) {
            return 0;
        }

        performanceEpochDirectRaster = gpu.isPerformanceScanlineCursorActive();
        performanceEpochSteadyRaster = !performanceEpochDirectRaster
                && gpu.isPerformanceSteadyCursorActive();
        performanceEpochPpuPlan = ppuPlan;
        performanceEpochPrefixCommitted = 0;
        performanceEpochEntryStatReadPhaseFlags = cpu.getStatReadPhaseFlags();
        performanceEpochEntryStatReadPhaseCaptured = false;
        IntConsumer prefixCommitter;
        if (cgbHardware) {
            if (performanceCgbNormalSpeedEpochPrefixCommitter == null) {
                performanceCgbNormalSpeedEpochPrefixCommitter =
                        this::commitCgbNormalSpeedPerformanceEpochPrefix;
            }
            prefixCommitter = performanceCgbNormalSpeedEpochPrefixCommitter;
        } else {
            if (performancePhysicalDmgEpochPrefixCommitter == null) {
                performancePhysicalDmgEpochPrefixCommitter =
                        this::commitPhysicalDmgPerformanceEpochPrefix;
            }
            prefixCommitter = performancePhysicalDmgEpochPrefixCommitter;
        }
        cpu.setPerformanceEpochPrefixCommitter(prefixCommitter);
        int elapsed;
        try {
            elapsed = cgbHardware
                    ? nativeCgbNormalSpeed
                            ? lcdOffEpoch
                                    ? cpu.runNativeCgbNormalSpeedLcdOffPerformanceEpoch(span)
                                    : cpu.runNativeCgbNormalSpeedPerformanceEpoch(span)
                            : cpu.runCgbCompatibilityPerformanceEpoch(span)
                    : sgb
                            ? cpu.runSgbPerformanceEpoch(span)
                            : cpu.runPhysicalDmgPerformanceEpoch(span);
        } finally {
            cpu.setPerformanceEpochPrefixCommitter(null);
        }
        if (elapsed <= 0) {
            return 0;
        }
        int suffix = elapsed - performanceEpochPrefixCommitted;
        if (suffix > 0) {
            if (cgbHardware) {
                commitCgbNormalSpeedPerformanceEpochPeripherals(suffix);
            } else {
                commitPhysicalDmgPerformanceEpochPeripherals(suffix);
            }
        }
        boolean journalReplayed = cpu.replayPerformanceEpochJournal();
        boolean divReset = timer.consumeDivReset();
        if (divReset) {
            sound.tickFrameSequencer(true);
            sound.commitFrameSequencerClock();
            serialPort.onDivReset();
        }
        Cpu.State finalCpuState = cpu.getState();
        boolean halted = finalCpuState == Cpu.State.HALTED;
        if (cgbHardware && halted) {
            hdma.reconcilePerformanceRunningEpochHaltEntryTrusted();
            hdma.onCpuHaltState(true);
        }
        if (halted || journalReplayed) {
            if (gbc) {
                // Keep the one-tick VRAM-DMA source sample seam in the same position as scalar
                // tick() when a journaled write or HALT transition needs the post-CPU seam.
                dma.setVramDmaBusSample(hdma.consumeSourceBusSample());
            }
            boolean dmaCpuClockPaused = halted || finalCpuState == Cpu.State.STOPPED
                    || finalCpuState == Cpu.State.SPEED_SWITCH
                    || speedSwitchTailTicks > 0
                    || gbc && hdma.pausesOamDmaForSpeedSwitchBurst();
            if (dma.requiresClockTick(dmaCpuClockPaused)) {
                dma.tick(dmaCpuClockPaused, halted);
            }
        }
        if (cgbHardware && halted) {
            // The scalar tick publishes the CPU HALT transition before the final GPU/HDMA
            // timing callback.  Epoch peripheral commits necessarily advance the GPU first,
            // so replay that post-GPU publication here to clear haltEnteredThisTick and keep
            // the CPU's held HDMA opcode latch in the same end-of-tick state.
            hdma.onGpuTiming(gpu.getLine(), gpu.getTicksInLine(),
                    gpu.isStatModeLatchRephasedBySpeedSwitch());
            cpu.latchHdmaHaltOpcode(hdma.isHaltRequestLatched());
        }
        if (cpu.hasPendingPeripheralSample()) {
            cpu.onPeripheralsTicked();
        }

        performanceEpochCount++;
        performanceEpochTicks += elapsed;
        performanceEpochMaxTicks = Math.max(performanceEpochMaxTicks, elapsed);
        return elapsed;
    }

    /** Stable normal-speed epoch topology; transient guards are evaluated by the caller. */
    private boolean canStartNormalSpeedPerformanceEpoch(
            boolean cgbHardware, boolean nativeCgbNormalSpeed) {
        boolean lcdEnabled = gpu.isLcdEnabled();
        return !warmResetRequested
                && speedSwitchTailTicks == 0
                && !debugHistoryReplay
                && debugInstrumentation == null
                && !debugRetirementTrackingActive
                && (lcdEnabled ? !lcdDisabled : nativeCgbNormalSpeed && lcdDisabled)
                && !dma.isTransferInProgress()
                && !dma.requiresClockTick(false)
                && (!cartridgeClocked || nativeCgbNormalSpeed)
                && !slotCartridgeClocked
                && (!cgbHardware || (!hdma.hasActiveOrPendingTransfer()
                        && !hdma.hasPendingHblankTransfer()
                        && !hdma.isHaltRequestLatched()
                        && !hdma.holdsHblankSpeedSwitchTail()
                        && !hdma.pausesOamDmaForSpeedSwitchBurst()
                        && !hdma.requiresCpuHdmaPhaseFlags()
                        && hdma.isPerformanceInactiveRequestClockStable()));
    }

    /** Leaves the exact host blank publication/reset tick on the scalar owner. */
    private int performanceLcdOffEpochSpanLimit(int requested) {
        if (requested <= 0 || !lcdDisabled || gpu.isLcdEnabled()) {
            return 0;
        }
        int target = lcdOffTicks < LCD_OFF_BLANK_DELAY
                ? LCD_OFF_BLANK_DELAY
                : LCD_OFF_BLANK_DELAY + clockSpec.controllerTicksPerFrame();
        int distance = target - lcdOffTicks;
        return Math.min(requested, Math.max(0, distance - 1));
    }

    private void commitPerformanceEpochPrefix(int ticks) {
        if (ticks <= performanceEpochPrefixCommitted) {
            return;
        }
        commitPerformanceEpochPeripherals(ticks - performanceEpochPrefixCommitted);
        performanceEpochPrefixCommitted = ticks;
    }

    /** Commits one old-state prefix without invoking the CPU synchronizer callback. */
    private void commitPerformanceEpochPeripherals(int ticks) {
        if (ticks <= 0) {
            return;
        }
        timer.tickPerformanceEpochTrusted(ticks);
        sound.tickFrameSequencer(false);
        assert !sound.hasPendingFrameSequencerClock()
                : "frame sequencer edge crossed a PERFORMANCE epoch";
        sound.commitFrameSequencerClock();
        sound.tickPerformanceQuietSpan(ticks);
        serialPort.tickPerformanceEpochIdle(ticks);
        infraredPort.tickPerformanceEpochIdle(ticks);
        joypad.tickPerformanceQuietSpanTrusted(ticks);
        hdma.advancePerformanceInactiveRequestClockTrusted(ticks);

        switch (performanceEpochPpuPlan) {
            case TRUSTED_RASTER -> {
                gpu.advancePerformanceEpochQuietSpanTrusted(
                        ticks, performanceEpochDirectRaster, performanceEpochSteadyRaster);
                statRegister.tickPerformanceQuietSpanTrusted(ticks);
                performanceEpochRasterFastTicks += ticks;
            }
            case MODE2_BULK -> {
                gpu.advancePerformanceMode2QuietSpanTrusted(ticks);
                statRegister.tickPerformanceQuietSpanTrusted(ticks);
                performanceEpochMode2ReplayTicks += ticks;
                performanceEpochMode2BulkTicks += ticks;
            }
            case MODE2_REPLAY -> {
                // The CPU and independently clocked peripherals have already consumed their
                // frozen-view packet. Preserve scalar PPU ordering inside mode 2 so OAM-reader
                // and STAT state are published dot-for-dot before the scalar hand-off tick.
                for (int i = 0; i < ticks; i++) {
                    gpu.tick();
                    statRegister.tick();
                }
                performanceEpochMode2ReplayTicks += ticks;
            }
            case NONE -> throw new IllegalStateException(
                    "PERFORMANCE epoch committed without a PPU plan");
        }
        hdma.onGpuTiming(gpu.getLine(), gpu.getTicksInLine(),
                gpu.isStatModeLatchRephasedBySpeedSwitch());
    }

    private void commitPhysicalDmgPerformanceEpochPrefix(int ticks) {
        if (ticks <= performanceEpochPrefixCommitted) {
            return;
        }
        commitPhysicalDmgPerformanceEpochPeripherals(
                ticks - performanceEpochPrefixCommitted);
        performanceEpochPrefixCommitted = ticks;
    }

    private void commitCgbNormalSpeedPerformanceEpochPrefix(int ticks) {
        if (ticks <= performanceEpochPrefixCommitted) {
            return;
        }
        commitCgbNormalSpeedPerformanceEpochPeripherals(
                ticks - performanceEpochPrefixCommitted);
        performanceEpochPrefixCommitted = ticks;
    }

    /** Physical-DMG prefix commit; CGB-only IR/HDMA state is deliberately absent. */
    private void commitPhysicalDmgPerformanceEpochPeripherals(int ticks) {
        commitNormalSpeedPerformanceEpochPeripherals(ticks, false);
    }

    /** Normal-speed CGB prefix commit through the selected raster or mode-2 PPU plane. */
    private void commitCgbNormalSpeedPerformanceEpochPeripherals(int ticks) {
        commitNormalSpeedPerformanceEpochPeripherals(ticks, true);
    }

    /** Shared fixed-x1 epoch peripheral commit; CGB hardware retains IR/HDMA/CGB PPU state. */
    private void commitNormalSpeedPerformanceEpochPeripherals(int ticks, boolean cgbHardware) {
        if (ticks <= 0) {
            return;
        }
        capturePerformanceEpochEntryStatReadPhase();
        if (cgbHardware && !speedMode.isDmgCompat() && cartridgeClocked) {
            // Scalar Gameboy.tick() clocks the cartridge before Timer and every other
            // subsystem.  Prefix flushes and the final suffix both pass through this method,
            // preserving that ordering before a fenced mapper/RTC boundary returns scalar.
            cartridge.tickPerformanceQuietSpanTrusted(ticks);
        }
        timer.tickPerformanceNormalSpeedEpochTrusted(ticks);
        sound.tickFrameSequencer(false);
        assert !sound.hasPendingFrameSequencerClock()
                : "frame sequencer edge crossed a fixed-x1 PERFORMANCE epoch";
        sound.commitFrameSequencerClock();
        sound.tickPerformanceQuietSpan(ticks);
        serialPort.tickPerformanceNormalSpeedEpochIdle(ticks);
        if (cgbHardware) {
            infraredPort.tickPerformanceQuietSpanTrusted(ticks);
        }
        joypad.tickPerformanceQuietSpanTrusted(ticks);
        if (cgbHardware) {
            // Scalar tick() advances the PPU-to-CPU request clocks after input and before GPU.
            // The preflight admits only inactive countdowns, so their zero-clock ages are the
            // complete arithmetic transaction skipped by this fixed-x1 packet.
            hdma.advancePerformanceInactiveRequestClockTrusted(ticks);
            switch (performanceEpochPpuPlan) {
                case TRUSTED_RASTER -> {
                    gpu.advancePerformanceEpochQuietSpanTrusted(
                            ticks, performanceEpochDirectRaster, performanceEpochSteadyRaster);
                    performanceEpochRasterFastTicks += ticks;
                }
                case MODE2_BULK -> {
                    gpu.advancePerformanceCgbNormalSpeedMode2PhaseSpanTrusted(ticks);
                    performanceEpochMode2ReplayTicks += ticks;
                    performanceEpochMode2BulkTicks += ticks;
                }
                case LCD_OFF -> {
                    gpu.advancePerformanceNativeCgbNormalSpeedLcdOffSpanTrusted(ticks);
                    performanceEpochLcdOffTicks += ticks;
                }
                default -> throw new IllegalStateException(
                        "normal-speed CGB epoch has no PPU plan");
            }
        } else {
            gpu.advancePhysicalDmgPerformanceEpochQuietSpanTrusted(
                    ticks, performanceEpochDirectRaster, performanceEpochSteadyRaster);
            performanceEpochRasterFastTicks += ticks;
        }
        statRegister.tickPerformanceQuietSpanTrusted(ticks);
        if (cgbHardware) {
            hdma.onGpuTiming(gpu.getLine(), gpu.getTicksInLine(),
                    gpu.isStatModeLatchRephasedBySpeedSwitch());
        }
        if (performanceEpochPpuPlan == PerformanceEpochPpuPlan.LCD_OFF) {
            lcdOffTicks += ticks;
        }
    }

    /** Selects the settled-HALT lane for the current normal-speed topology. */
    private int tryPerformanceSettledHaltSpan(long remaining) {
        if (isCgbCompatibilityPerformanceTopology()
                || isNativeCgbNormalSpeedPerformanceEpochTopology()) {
            return tryPerformanceSettledCgbHaltSpan(remaining);
        }
        if (!gbc) {
            return tryPerformanceSettledDmgHaltSpan(remaining);
        }
        return 0;
    }

    /**
     * Attempts a normal-speed CGB settled-HALT packet. This is deliberately separate from
     * the physical-DMG packet: CGB hardware still clocks the CGB infrared and
     * HDMA control planes, and their idle guards must remain part of the proof.
     */
    private int tryPerformanceSettledCgbHaltSpan(long remaining) {
        if (remaining <= 0 || !cpu.performanceSettledHaltSpanEligible()) {
            return 0;
        }
        int span = (int) Math.min((long) SETTLED_HALT_PERFORMANCE_MAX_SPAN, remaining);
        span = Math.min(span, timer.performanceSettledHaltSpanLimit(span));
        span = Math.min(span, serialPort.performanceSettledHaltSpanLimit(span));
        span = Math.min(span, joypad.performanceSettledHaltSpanLimit(span));
        span = Math.min(span, sound.performanceQuietSpanLimit(span));
        span = Math.min(span, infraredPort.performanceSettledHaltSpanLimit(span));
        if (cartridgeClocked) {
            span = Math.min(span, cartridge.performanceQuietSpanLimit(span));
        }
        if (slotCartridgeClocked) {
            span = Math.min(span, slotCartridge.performanceQuietSpanLimit(span));
        }
        PerformancePhasePpuPlan ppuPlan = PerformancePhasePpuPlan.QUIET;
        int gpuSpanLimit = gpu.performanceQuietSpanLimit();
        boolean directRasterSpan = false;
        boolean steadyRasterSpan = false;
        if (gpuSpanLimit > 0) {
            span = Math.min(span, gpuSpanLimit);
            directRasterSpan = span > 0 && gpu.isPerformanceScanlineCursorActive();
            steadyRasterSpan = span > 0 && !directRasterSpan
                    && gpu.isPerformanceSteadyCursorActive();
        } else if (isNativeCgbNormalSpeedPerformanceEpochTopology()) {
            int mode2SpanLimit = gpu.performanceCgbNormalSpeedMode2PhaseSpanLimit(span);
            if (mode2SpanLimit > 0) {
                span = Math.min(span, mode2SpanLimit);
                ppuPlan = PerformancePhasePpuPlan.CGB_NORMAL_SPEED_MODE2;
            } else {
                span = 0;
            }
        } else {
            span = 0;
        }
        span = Math.min(span, statRegister.performanceSettledHaltSpanLimit(span));
        if (span <= 3
                || warmResetRequested
                || speedSwitchTailTicks != 0
                || !cpu.performanceNoPendingPpuReadPhase()
                || dma.isTransferInProgress()
                || dma.requiresClockTick(true)
                || hdma.hasActiveOrPendingTransfer()
                || hdma.hasPendingHblankTransfer()
                || hdma.isHaltRequestLatched()
                || hdma.holdsHblankSpeedSwitchTail()
                || hdma.pausesOamDmaForSpeedSwitchBurst()
                || hdma.requiresCpuHdmaPhaseFlags()
                || !joypad.isPerformanceQuietSpanStillEligible()
                || !canStartCgbNormalSpeedSettledHaltSpan()) {
            return 0;
        }
        tickPerformanceSettledCgbHaltSpan(
                span, ppuPlan, directRasterSpan, steadyRasterSpan);
        performanceBulkSpanCount++;
        performanceBulkTicks += span;
        if (span > performanceBulkMaxTicks) {
            performanceBulkMaxTicks = span;
        }
        return span;
    }

    private boolean canStartCgbNormalSpeedSettledHaltSpan() {
        return (isCgbCompatibilityPerformanceTopology()
                || isNativeCgbNormalSpeedPerformanceEpochTopology())
                && !warmResetRequested
                && speedSwitchTailTicks == 0
                && !debugHistoryReplay
                && debugInstrumentation == null
                && !debugRetirementTrackingActive
                && gpu.isLcdEnabled()
                && !dma.isTransferInProgress()
                && !dma.requiresClockTick(true)
                && !hdma.hasActiveOrPendingTransfer()
                && !hdma.hasPendingHblankTransfer()
                && !hdma.isHaltRequestLatched()
                && !hdma.holdsHblankSpeedSwitchTail()
                && !hdma.pausesOamDmaForSpeedSwitchBurst()
                && !hdma.requiresCpuHdmaPhaseFlags()
                && hdma.isPerformanceInactiveRequestClockStable();
    }

    /**
     * Commits a normal-speed CGB settled-HALT packet through either the quiet raster plane or
     * the exact native-x1 mode-2 OAM transaction selected during preflight.
     */
    private void tickPerformanceSettledCgbHaltSpan(
            int ticks, PerformancePhasePpuPlan ppuPlan,
            boolean directRasterSpan, boolean steadyRasterSpan) {
        if (cartridgeClocked) {
            cartridge.tickPerformanceQuietSpanTrusted(ticks);
        }
        if (slotCartridgeClocked) {
            slotCartridge.tickPerformanceQuietSpanTrusted(ticks);
        }
        timer.tickPerformanceQuietSpanTrusted(ticks);
        sound.tickFrameSequencer(false);
        assert !sound.hasPendingFrameSequencerClock()
                : "frame sequencer edge crossed a normal-speed CGB settled-HALT span";
        sound.commitFrameSequencerClock();
        sound.tickPerformanceQuietSpan(ticks);
        serialPort.tickPerformanceQuietSpanTrusted(ticks);
        infraredPort.tickPerformanceQuietSpanTrusted(ticks);
        joypad.tickPerformanceQuietSpanTrusted(ticks);
        cpu.advancePerformanceSettledHaltSpanTrusted(ticks);
        hdma.advancePerformanceInactiveRequestClockTrusted(ticks);
        switch (ppuPlan) {
            case QUIET -> gpu.advancePerformanceQuietSpanTrusted(
                    ticks, directRasterSpan, steadyRasterSpan);
            case CGB_NORMAL_SPEED_MODE2 ->
                    gpu.advancePerformanceCgbNormalSpeedMode2PhaseSpanTrusted(ticks);
            default -> throw new IllegalStateException(
                    "normal-speed CGB settled HALT has no PPU plan");
        }
        statRegister.tickPerformanceQuietSpanTrusted(ticks);
        hdma.onGpuTiming(gpu.getLine(), gpu.getTicksInLine(),
                gpu.isStatModeLatchRephasedBySpeedSwitch());
        cpu.latchHdmaHaltOpcode(hdma.isHaltRequestLatched());
    }

    /**
     * Attempts the long settled-HALT packet used by the normal-speed monochrome side entrance.
     * Every horizon and volatile guard is read before the commit, so a zero result leaves the
     * machine untouched and lets the ordinary PERFORMANCE scheduler handle the next scalar phase.
     */
    private int tryPerformanceSettledDmgHaltSpan(long remaining) {
        if (remaining <= 0 || !cpu.performanceSettledHaltSpanEligible()) {
            return 0;
        }
        int span = (int) Math.min((long) SETTLED_HALT_PERFORMANCE_MAX_SPAN, remaining);
        span = Math.min(span, timer.performanceSettledHaltSpanLimit(span));
        span = Math.min(span, serialPort.performanceSettledHaltSpanLimit(span));
        span = Math.min(span, joypad.performanceSettledHaltSpanLimit(span));
        span = Math.min(span, sound.performanceQuietSpanLimit(span));
        if (cartridgeClocked) {
            span = Math.min(span, cartridge.performanceQuietSpanLimit(span));
        }
        if (slotCartridgeClocked) {
            span = Math.min(span, slotCartridge.performanceQuietSpanLimit(span));
        }
        int gpuSpanLimit = gpu.performanceQuietSpanLimit();
        span = Math.min(span, gpuSpanLimit);
        boolean directRasterSpan = span > 0 && gpu.isPerformanceScanlineCursorActive();
        boolean steadyRasterSpan = span > 0 && !directRasterSpan
                && gpu.isPerformanceSteadyCursorActive();
        span = Math.min(span, statRegister.performanceSettledHaltSpanLimit(span));
        if (span <= 3
                || warmResetRequested
                || speedSwitchTailTicks != 0
                || !cpu.performanceNoPendingPpuReadPhase()
                || dma.isTransferInProgress()
                || dma.requiresClockTick(true)
                // This is intentionally the one post-preflight volatile Joypad check.
                // A legacy/debug/input mutation published after the horizon walk must
                // fall back to one scalar tick; a PlayerInputHub snapshot update does not
                // clear its eligibility and remains invisible until the next poll.
                || !joypad.isPerformanceQuietSpanStillEligible()) {
            return 0;
        }
        tickPerformanceSettledDmgHaltSpan(span, directRasterSpan, steadyRasterSpan);
        performanceBulkSpanCount++;
        performanceBulkTicks += span;
        if (span > performanceBulkMaxTicks) {
            performanceBulkMaxTicks = span;
        }
        return span;
    }

    /** Commits a preflighted long DMG settled-HALT packet without CGB-only peripherals. */
    private void tickPerformanceSettledDmgHaltSpan(int ticks, boolean directRasterSpan,
                                                    boolean steadyRasterSpan) {
        if (cartridgeClocked) {
            cartridge.tickPerformanceQuietSpanTrusted(ticks);
        }
        if (slotCartridgeClocked) {
            slotCartridge.tickPerformanceQuietSpanTrusted(ticks);
        }
        timer.tickPerformanceQuietSpanTrusted(ticks);
        sound.tickFrameSequencer(false);
        assert !sound.hasPendingFrameSequencerClock()
                : "frame sequencer edge crossed a PERFORMANCE quiet span";
        sound.commitFrameSequencerClock();
        sound.tickPerformanceQuietSpan(ticks);
        serialPort.tickPerformanceQuietSpanTrusted(ticks);
        joypad.tickPerformanceQuietSpanTrusted(ticks);
        cpu.advancePerformanceSettledHaltSpanTrusted(ticks);
        gpu.advancePerformanceQuietSpanTrusted(ticks, directRasterSpan, steadyRasterSpan);
        statRegister.tickPerformanceQuietSpanTrusted(ticks);
    }

    /** Advances the non-CPU-bus peripherals for one preflighted quiet span. */
    private void tickPerformanceQuietSpan(int ticks, boolean directRasterSpan,
                                          boolean steadyRasterSpan) {
        tickPerformanceQuietSpan(
                ticks, PerformancePhasePpuPlan.QUIET, directRasterSpan, steadyRasterSpan,
                cpu.getStatReadPhaseFlags());
    }

    /** Advances the non-CPU-bus peripherals for one explicit phase-only PPU plan. */
    private void tickPerformanceQuietSpan(
            int ticks, PerformancePhasePpuPlan ppuPlan,
            boolean directRasterSpan, boolean steadyRasterSpan,
            int entryStatReadPhaseFlags) {
        statRegister.capturePerformanceNoCpuReadPhaseTrusted(entryStatReadPhaseFlags);
        if (cartridgeClocked) {
            cartridge.tickPerformanceQuietSpanTrusted(ticks);
        }
        if (slotCartridgeClocked) {
            slotCartridge.tickPerformanceQuietSpanTrusted(ticks);
        }

        // Timer preflight excludes every DIV/timer edge in this span. Advance its divider once,
        // then sample the final DIV value exactly once so the APU's rising-bit latch remains in
        // the same state as the scalar per-tick sequence.
        timer.tickPerformanceQuietSpanTrusted(ticks);
        sound.tickFrameSequencer(false);
        assert !sound.hasPendingFrameSequencerClock()
                : "frame sequencer edge crossed a PERFORMANCE quiet span";
        sound.commitFrameSequencerClock();
        sound.tickPerformanceQuietSpan(ticks);

        serialPort.tickPerformanceQuietSpanTrusted(ticks);
        if (gbc) {
            infraredPort.tickPerformanceQuietSpanTrusted(ticks);
        }
        joypad.tickPerformanceQuietSpanTrusted(ticks);

        // No CPU bus boundary exists inside this span. Advance the free-running phase once;
        // running state, or settled HALT, proves Cpu.onPeripheralsTicked() is a no-op here.
        cpu.advancePerformancePhaseOnlyTrusted(ticks);
        if (gbc) {
            hdma.advancePerformanceInactiveRequestClockTrusted(ticks);
        }
        switch (ppuPlan) {
            case QUIET -> gpu.advancePerformanceQuietSpanTrusted(
                    ticks, directRasterSpan, steadyRasterSpan);
            case PHYSICAL_DMG_MODE2 ->
                    gpu.advancePerformancePhysicalDmgMode2PhaseSpanTrusted(ticks);
            case CGB_NORMAL_SPEED_MODE2 ->
                    gpu.advancePerformanceCgbNormalSpeedMode2PhaseSpanTrusted(ticks);
        }
        statRegister.tickPerformanceQuietSpanTrusted(ticks);
        if (gbc) {
            // Scalar tick() publishes these post-GPU timing latches on every dot.  A packet
            // reaches the same final observable state by overwriting them once at its end.
            hdma.onGpuTiming(gpu.getLine(), gpu.getTicksInLine(),
                    gpu.isStatModeLatchRephasedBySpeedSwitch());
            cpu.latchHdmaHaltOpcode(hdma.isHaltRequestLatched());
        }
    }

    /** Captures the epoch's old-state CPU/STAT input at its first positive commit only. */
    private void capturePerformanceEpochEntryStatReadPhase() {
        if (performanceEpochEntryStatReadPhaseCaptured) {
            return;
        }
        statRegister.capturePerformanceNoCpuReadPhaseTrusted(
                performanceEpochEntryStatReadPhaseFlags);
        performanceEpochEntryStatReadPhaseCaptured = true;
    }

    /** Resets the session-only PERFORMANCE bulk counters at benchmark arm. */
    public void resetPerformanceBulkCounters() {
        performanceBulkSpanCount = 0L;
        performanceBulkTicks = 0L;
        performanceBulkMaxTicks = 0;
        performanceEpochCount = 0L;
        performanceEpochTicks = 0L;
        performanceEpochMaxTicks = 0;
        performanceEpochRasterFastTicks = 0L;
        performanceEpochMode2ReplayTicks = 0L;
        performanceEpochMode2BulkTicks = 0L;
        performanceEpochLcdOffTicks = 0L;
        performanceEpochPpuPlan = PerformanceEpochPpuPlan.NONE;
        cpu.resetPerformanceEpochTelemetry();
    }

    /** Number of all-subsystem PERFORMANCE bulk spans taken by the current session. */
    public long getPerformanceBulkSpanCount() {
        return performanceBulkSpanCount;
    }

    /** Number of master ticks covered by all-subsystem PERFORMANCE bulk spans. */
    public long getPerformanceBulkTicks() {
        return performanceBulkTicks;
    }

    /** Largest settled-HALT PERFORMANCE packet in the current session. */
    public int getPerformanceBulkMaxTicks() {
        return performanceBulkMaxTicks;
    }

    public long getPerformanceEpochCount() {
        return performanceEpochCount;
    }

    public long getPerformanceEpochTicks() {
        return performanceEpochTicks;
    }

    public int getPerformanceEpochMaxTicks() {
        return performanceEpochMaxTicks;
    }

    public long getPerformanceEpochRasterFastTicks() {
        return performanceEpochRasterFastTicks;
    }

    public long getPerformanceEpochMode2ReplayTicks() {
        return performanceEpochMode2ReplayTicks;
    }

    public long getPerformanceEpochMode2BulkTicks() {
        return performanceEpochMode2BulkTicks;
    }

    public long getPerformanceEpochLcdOffTicks() {
        return performanceEpochLcdOffTicks;
    }

    private Mode tickSubsystems() {
        boolean nativeCgbScalarOwnerTick = nativeCgbScalarOwner;
        int statReadPhaseFlags = cpu.getStatReadPhaseFlags();
        if (nativeCgbScalarOwnerTick) {
            nativeCgbScalarOwner = false;
            tickNativeCgbPerformanceStatPrologue(statReadPhaseFlags);
        } else {
            boolean mode0InterruptEdgeNextTick = statRegister.beginCpuReadPhase(statReadPhaseFlags);
            statRegister.finishCpuReadPhase(
                    cpu.getInterruptFlagReadMaskTicks(mode0InterruptEdgeNextTick),
                    cpu.isMode0InterruptDispatchPhased(mode0InterruptEdgeNextTick),
                    cpu.doesMode0InstructionWinInterruptAcceptance(
                            mode0InterruptEdgeNextTick));
        }
        boolean speedSwitching = cpu.isSpeedSwitching();
        boolean speedSwitchTail = speedSwitchTailTicks > 0;
        Cpu.State initialCpuState = cpu.getState();
        dma.setCpuInterruptStackWrite(initialCpuState == Cpu.State.IRQ_PUSH_1
                || initialCpuState == Cpu.State.IRQ_PUSH_2);
        // STOP's CGB speed-switch delay pauses instruction execution, not the
        // timer clock domain. DIV/TIMA continue advancing after STOP resets DIV.
        if (!speedSwitchTail) {
            timer.tick();
        }
        sound.tickFrameSequencer(false);
        boolean deferFrameSequencerClock = sound.isFrameSequencerClockAfterCpu();
        if (!deferFrameSequencerClock) {
            sound.commitFrameSequencerClock();
        }
        if (speedSwitchTail) {
            speedSwitchTailTicks--;
            if (speedSwitchTailTicks == 0) {
                if (gbc) {
                    hdma.onSpeedSwitchComplete();
                }
                gpu.onSpeedSwitchComplete();
            }
        } else if (speedSwitching) {
            // A CGB speed switch pauses instruction execution while the independent
            // timer and PPU clocks continue running. A granted HBlank burst also
            // advances unless an active OAM transfer owns the shared DMA clock.
            cpu.tick();
            if (gbc && hdma.pausesOamDmaForSpeedSwitchBurst()
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
                int completedHblankBurstAdvance = gbc && hdma.completedHblankSpeedSwitchBurst()
                        ? COMPLETED_HBLANK_SPEED_SWITCH_ADVANCE_TICKS : 0;
                int pendingHblankAlignment = 0;
                if (gbc && hdma.alignsPendingHblankSpeedSwitchTail()) {
                    pendingHblankAlignment = dma.isTransferInProgress()
                            ? OAM_DMA_HBLANK_SPEED_SWITCH_DELAY_TICKS
                            : -PENDING_HBLANK_SPEED_SWITCH_ADVANCE_TICKS;
                }
                speedSwitchTailTicks = baseSpeedSwitchTailTicks(longClockMuxPhase,
                        gbc && hdma.holdsHblankSpeedSwitchTail())
                        - completedHblankBurstAdvance
                        + (speedMode.getSpeedMode() == 2 && speedSwitchClockPhaseShifted ? 1 : 0)
                        + pendingHblankAlignment;
                if (speedMode.getSpeedMode() == 1) {
                    speedSwitchClockPhaseShifted = true;
                }
                if (speedSwitchTailTicks <= 0) {
                    if (gbc) {
                        hdma.onSpeedSwitchComplete();
                    }
                    gpu.onSpeedSwitchComplete();
                }
            }
        } else if (gbc && hdma.isTransferInProgress()) {
            Cpu.State dmaCpuState = cpu.getState();
            if (dmaCpuState == Cpu.State.HALTED
                    || dmaCpuState == Cpu.State.STOPPED) {
                // HBlank DMA is suspended while the CPU clock is halted or
                // stopped. Keep ticking the CPU so an asserted joypad line can wake
                // STOP; the separately calibrated CGB path can also accept an interrupt.
                // The HDMA request is restored according to the request level captured
                // when HALT was entered.
                if (dmaCpuState == Cpu.State.STOPPED) {
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
            boolean retiringIntoHdmaRequest = gbc && hdma.isHblankRequestArrivingAfterCpuTick()
                    && cpu.isInstructionRetiringForHdma();
            if (retiringIntoHdmaRequest) {
                gpu.setCpuRetiringInstructionForHdma(true);
                try {
                    cpu.tick();
                } finally {
                    gpu.setCpuRetiringInstructionForHdma(false);
                }
            } else {
                tickCpuPerformanceAware();
            }
        }
        if (!speedSwitching && cpu.isSpeedSwitching()) {
            sound.onSpeedSwitch();
            gpu.onSpeedSwitch();
            dma.onSpeedSwitch();
            if (gbc && hdma.onSpeedSwitch()) {
                cpu.replaySpeedSwitchPaddingByte();
            }
        }
        Cpu.State finalCpuState = cpu.getState();
        if (gbc) {
            hdma.onCpuHaltState(finalCpuState == Cpu.State.HALTED);
        }
        if (deferFrameSequencerClock) {
            sound.commitFrameSequencerClock();
        }
        boolean divReset = timer.consumeDivReset();
        if (divReset) {
            sound.tickFrameSequencer(true);
            sound.commitFrameSequencerClock();
            serialPort.onDivReset();
        }
        // OAM DMA is driven by the CPU clock domain. HALT pauses it after the
        // entry latency; STOP and a CGB speed switch pause it immediately.
        boolean halted = finalCpuState == Cpu.State.HALTED;
        if (gbc) {
            dma.setVramDmaBusSample(hdma.consumeSourceBusSample());
        }
        boolean dmaCpuClockPaused = halted || finalCpuState == Cpu.State.STOPPED
                        || finalCpuState == Cpu.State.SPEED_SWITCH || speedSwitchTail
                        || gbc && hdma.pausesOamDmaForSpeedSwitchBurst();
        if (dma.requiresClockTick(dmaCpuClockPaused)) {
            dma.tick(dmaCpuClockPaused, halted);
        }
        if (executionMode == ExecutionMode.PERFORMANCE && bootCompatibilityResolved) {
            sound.tickPerformanceBoundary(divReset);
        } else {
            sound.tick(divReset);
        }
        serialPort.tick();
        if (gbc) {
            infraredPort.tick();
        }
        joypad.tick();
        // The HBlank request crosses from the PPU to the CPU arbiter while the CPU is
        // still allowed to finish the current machine cycle.
        if (gbc && hdma.requiresCpuHdmaPhaseFlags()) {
            int hdmaPhaseFlags = cpu.getHdmaPhaseFlags();
            hdma.advanceHblankRequest(
                    (hdmaPhaseFlags & Cpu.HDMA_PHASE_IN_FLIGHT_WRITE_CYCLE) != 0,
                    (hdmaPhaseFlags & Cpu.HDMA_PHASE_CPU_REQUEST_SLOT_IN_PROGRESS) != 0,
                    (hdmaPhaseFlags & Cpu.HDMA_PHASE_INTERRUPT_CLAIMED) != 0);
        } else if (gbc) {
            hdma.advanceHblankRequest();
        }
        boolean performanceSteadyCursor = executionMode == ExecutionMode.PERFORMANCE
                && bootCompatibilityResolved
                && gpu.isPerformanceSteadyCursorActive();
        boolean performanceQuietRaster = performanceSteadyCursor
                && gpu.isPerformanceSteadyTickQuiet();
        Mode mode = performanceSteadyCursor ? gpu.tickPerformanceSteady() : gpu.tick();
        if (nativeCgbScalarOwnerTick) {
            // The native scalar owner has already run the pre-CPU STAT seam. Complete the
            // post-GPU phase with the allocation-free native timing facts; every other path
            // remains on the established generic evaluator.
            statRegister.tickNativeCgbPerformancePostGpu();
        } else if (performanceQuietRaster && mode == null) {
            statRegister.tickPerformanceQuietIfSafe();
        } else {
            statRegister.tick();
        }
        if (cpu.hasPendingPeripheralSample()) {
            cpu.onPeripheralsTicked();
        }
        return mode;
    }

    /** Specialized native-CGB CPU/STAT seam; called only by the one-shot scalar owner token. */
    private void tickNativeCgbPerformanceStatPrologue(int statReadPhaseFlags) {
        int phaseWord = gpu.getNativeCgbPerformancePhaseWord();
        boolean mode0InterruptEdgeNextTick =
                statRegister.beginNativeCgbPerformanceReadPhase(statReadPhaseFlags, phaseWord);
        statRegister.finishNativeCgbPerformanceReadPhase(
                mode0InterruptEdgeNextTick ? cpu.getInterruptFlagReadMaskTicks(true) : 0,
                phaseWord);
    }

    /**
     * Keeps the normal scheduler's exact bus work at machine-cycle boundaries while avoiding
     * the full CPU sequencer on the three intervening normal-speed master ticks. DMA, speed
     * switch, debugger and history paths deliberately stay on their scalar branches above.
     */
    private void tickCpuPerformanceAware() {
        if (executionMode != ExecutionMode.PERFORMANCE
                || !bootCompatibilityResolved
                || debugInstrumentation != null
                || debugRetirementTrackingActive
                || debugHistoryReplay) {
            cpu.tick();
            return;
        }
        if (!cpu.tickPhaseOnly()) {
            cpu.tickAtMachineCycle();
        }
    }

    public AddressSpace getAddressSpace() {
        return gameGenie;
    }

    public Cpu getCpu() {
        return cpu;
    }

    /** Starts a debugger-local retirement sequence without changing emulated state. */
    public void enableDebugRetirementTracking() {
        debugRetirementTrackingActive = true;
        updatePerformanceObservationBlocker();
        cpu.enableDebugRetirementTracking();
    }

    /** Removes the optional retirement hook from the CPU hot path. */
    public void disableDebugRetirementTracking() {
        cpu.disableDebugRetirementTracking();
        debugRetirementTrackingActive = false;
        updatePerformanceObservationBlocker();
    }

    public long getDebugRetirementSequence() {
        return cpu.getDebugRetirementSequence();
    }

    /**
     * Installs, updates, or removes owner-thread debug instrumentation between ticks.
     * Instrumentation and its trace history are deliberately absent from portable state.
     */
    public void updateDebugInstrumentation(
            DebugInstrumentation instrumentation, long completedMasterTick) {
        if (instrumentation != null) {
            instrumentation.alignMasterTick(completedMasterTick);
        }
        this.debugInstrumentation = instrumentation;
        updatePerformanceObservationBlocker();
        var cpuHooks = instrumentation != null && instrumentation.requiresCpuHooks()
                ? instrumentation : null;
        cpu.setDebugHooks(cpuHooks);
        interruptManager.setDebugHooks(
                instrumentation != null && instrumentation.requiresInterruptHooks()
                        ? instrumentation : null);
        if (instrumentation != null && instrumentation.requiresPpuHooks()) {
            instrumentation.alignPpuState(gpu.getLine(), toDebugPpuMode());
            gpu.setDebugHooks(instrumentation);
        } else {
            gpu.setDebugHooks(null);
        }
        var dmaHooks = instrumentation != null && instrumentation.requiresDmaHooks()
                ? instrumentation : null;
        dma.setDebugHooks(dmaHooks);
        hdma.setDebugHooks(dmaHooks);
        timer.setDebugHooks(
                instrumentation != null && instrumentation.requiresTimerHooks()
                        ? instrumentation : null);
        var serialIrHooks = instrumentation != null && instrumentation.requiresSerialIrHooks()
                ? instrumentation : null;
        serialPort.setDebugHooks(serialIrHooks);
        infraredPort.setDebugHooks(serialIrHooks);
        joypad.setDebugHooks(
                instrumentation != null && instrumentation.requiresInputHooks()
                        ? instrumentation : null);
        var mapperHooks = instrumentation != null && instrumentation.requiresMapperRtcHooks()
                ? instrumentation : null;
        cartridge.setDebugHooks(mapperHooks);
        if (slotCartridge != null) {
            slotCartridge.setDebugHooks(mapperHooks);
        }
        sound.setDebugHooks(
                instrumentation != null && instrumentation.requiresApuHooks()
                        ? instrumentation : null);
    }

    private void updatePerformanceObservationBlocker() {
        boolean blocked = debugInstrumentation != null || debugRetirementTrackingActive;
        if (blocked) {
            // A synchronous callback may install observation while runNative is between dots.
            // The following scalar tick must use the generic prologue and observation path.
            nativeCgbScalarOwner = false;
        }
        gpu.setPerformanceObservationBlocked(blocked);
    }

    /**
     * Captures a detached debugger view from scalar component state at the current completed-tick
     * safe point. The caller owns the session/controller counters so that no debugger metadata is
     * added to portable emulator state.
     */
    public DebugSnapshot captureDebugSnapshot(long sessionGeneration, long sequence,
                                              long masterTick, long frame, int framePosition,
                                              boolean debugPaused) {
        var registers = cpu.getRegisters();
        DebugRegisters debugRegisters = new DebugRegisters(
                registers.getA(), registers.getFlags().getFlagsByte(),
                registers.getB(), registers.getC(), registers.getD(), registers.getE(),
                registers.getH(), registers.getL(), registers.getSP(), registers.getPC());

        int interruptFlags = interruptManager.getDebugInterruptFlags();
        int interruptEnableFlags = interruptManager.getDebugInterruptEnableFlags();
        DebugInterruptState debugInterrupts = new DebugInterruptState(
                interruptManager.isIme(), interruptManager.isInterruptEnablePending(),
                interruptFlags, interruptEnableFlags,
                interruptManager.getDebugPendingInterruptFlags());

        DebugTimerState debugTimer = new DebugTimerState(
                timer.getDivCounter(), timer.getDebugTima(), timer.getDebugTma(),
                timer.getDebugTac(), timer.isDebugOverflowPending(),
                timer.getDebugOverflowDelayTicks());

        DebugPpuState debugPpu = new DebugPpuState(
                gpu.isLcdEnabled(), toDebugPpuMode(), gpu.getLine(),
                Math.max(0, gpu.getTicksInLine()), gpu.getLcdcValueForCore(),
                statRegister.getByte(0xff41),
                gpu.getRegisterValueForCore(GpuRegister.SCY),
                gpu.getRegisterValueForCore(GpuRegister.SCX),
                gpu.getRegisterValueForCore(GpuRegister.LYC),
                gpu.getRegisterValueForCore(GpuRegister.WY),
                gpu.getRegisterValueForCore(GpuRegister.WX));

        int nr50 = sound.getByte(0xff24);
        int nr51 = sound.getByte(0xff25);
        int nr52 = sound.getByte(0xff26);
        DebugApuState debugApu = new DebugApuState(
                (nr52 & 0x80) != 0, sound.getDebugFrameSequencerStep(),
                (nr52 & 0x01) != 0, (nr52 & 0x02) != 0,
                (nr52 & 0x04) != 0, (nr52 & 0x08) != 0,
                nr50, nr51, nr52);

        String mapperId = cartridge.getMemoryController().getClass().getSimpleName();
        if (mapperId.isEmpty()) {
            mapperId = cartridge.getMemoryController().getClass().getName();
        }
        DebugMapperState debugMapper = new DebugMapperState(
                mapperId, -1, -1, DebugFeatureState.UNKNOWN,
                DebugFeatureState.UNKNOWN, DebugFeatureState.UNKNOWN);

        DebugExecutionState debugExecution = new DebugExecutionState(
                toDebugCpuState(cpu.getState()), cpu.getDebugOpcode(),
                cpu.getDebugExtendedOpcode(), cpu.getDebugMachineCycle(),
                speedMode.getSpeedMode() == 2, cpu.isDebugHaltBugActive(),
                cpu.getDebugRetirementSequence());

        return new DebugSnapshot(sessionGeneration, sequence, masterTick, frame, framePosition,
                debugPaused, debugRegisters, debugInterrupts, debugTimer, debugPpu, debugApu,
                debugMapper, debugExecution);
    }

    /** Reads only side-effect-free physical ROM or MMU-owned RAM views. */
    public DebugMemoryBlock readDebugMemory(DebugMemoryRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Debug memory request must not be null");
        }
        int address = request.address();
        int end = request.endExclusive();
        switch (request.addressSpace()) {
            case SYSTEM_BUS:
                break;
            case ROM:
                return new DebugMemoryBlock(request.addressSpace(), address,
                        cartridge.readDebugRom(address, request.length()));
            case WORK_RAM:
                if (address < 0xc000 || end > 0xfe00) {
                    throw new IllegalArgumentException("WORK_RAM range must be within C000-FDFF");
                }
                break;
            case HIGH_RAM:
                if (address < 0xff80 || end > 0xffff) {
                    throw new IllegalArgumentException("HIGH_RAM range must be within FF80-FFFE");
                }
                break;
            default:
                throw new UnsupportedOperationException(
                        "Debug address space is not side-effect-free: " + request.addressSpace());
        }
        return new DebugMemoryBlock(request.addressSpace(), address,
                mmu.readDebugMemory(address, request.length()));
    }

    /** Writes one byte through a debugger-owned, side-effect-free RAM view. */
    public void writeDebugMemory(DebugMemoryWrite write) {
        if (write == null) {
            throw new IllegalArgumentException("Debug memory write must not be null");
        }
        int address = write.address();
        switch (write.addressSpace()) {
            case SYSTEM_BUS:
                break;
            case WORK_RAM:
                if (address < 0xc000 || address >= 0xfe00) {
                    throw new IllegalArgumentException("WORK_RAM address must be within C000-FDFF");
                }
                break;
            case HIGH_RAM:
                if (address < 0xff80 || address >= 0xffff) {
                    throw new IllegalArgumentException("HIGH_RAM address must be within FF80-FFFE");
                }
                break;
            default:
                throw new UnsupportedOperationException(
                        "Debug address space is not writable: " + write.addressSpace());
        }
        mmu.writeDebugMemory(address, write.value());
    }

    /** Copies requested memory and peripherals against one already-captured coherent snapshot. */
    public DebugInspectionResult inspectDebugMemory(
            DebugSnapshot snapshot, DebugInspectionRequest request) {
        return inspectDebugMemory(snapshot, request, null);
    }

    /** Copies requested memory and peripherals, pairing an optional owner-side trace page. */
    public DebugInspectionResult inspectDebugMemory(
            DebugSnapshot snapshot, DebugInspectionRequest request, TraceReadResult trace) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(request, "request");
        if (request.traceRequest().isPresent() != (trace != null)) {
            throw new IllegalArgumentException(
                    "Trace result presence must match the inspection request");
        }
        var anchoredBlocks = new ArrayList<DebugMemoryBlock>(request.anchoredRequests().size());
        for (var anchoredRequest : request.anchoredRequests()) {
            anchoredBlocks.add(readDebugMemory(anchoredRequest.resolve(snapshot)));
        }
        var memoryBlocks = new ArrayList<DebugMemoryBlock>(request.memoryRequests().size());
        for (var memoryRequest : request.memoryRequests()) {
            memoryBlocks.add(readDebugMemory(memoryRequest));
        }
        var graphics = request.sections().contains(DebugInspectionSection.GRAPHICS)
                ? Optional.of(gpu.captureDebugGraphicsInspection())
                : Optional.<DebugGraphicsInspection>empty();
        var audio = request.sections().contains(DebugInspectionSection.AUDIO)
                ? Optional.of(sound.captureDebugAudioInspection())
                : Optional.<DebugAudioInspection>empty();
        var hardware = request.sections().contains(DebugInspectionSection.HARDWARE)
                ? Optional.of(captureDebugHardwareInspection())
                : Optional.<DebugHardwareInspection>empty();
        return new DebugInspectionResult(snapshot, request, anchoredBlocks, memoryBlocks,
                graphics, audio, Optional.ofNullable(trace), hardware);
    }

    private DebugHardwareInspection captureDebugHardwareInspection() {
        DebugGraphicsHardwareMode hardwareMode = !gbc
                ? DebugGraphicsHardwareMode.DMG
                : speedMode.isDmgCompat()
                        ? DebugGraphicsHardwareMode.CGB_COMPATIBILITY
                        : DebugGraphicsHardwareMode.CGB_NATIVE;
        boolean cgbHardware = hardwareMode != DebugGraphicsHardwareMode.DMG;
        DebugHardwareInspection.VramDma vramDma = cgbHardware
                ? hdma.captureDebugVramDmaInspection()
                : new DebugHardwareInspection.VramDma(
                        false, -1, -1, -1, -1, -1,
                        false, false, -1, -1, -1);
        int key0 = cgbHardware ? speedMode.getByte(0xff4c) : -1;
        int key1 = cgbHardware ? speedMode.getByte(0xff4d) : -1;
        int vbk = cgbHardware
                ? 0xfe | (gpu.getRegisterValueForCore(GpuRegister.VBK) & 1) : -1;
        int svbk = cgbHardware ? mmu.getDebugSvbk() : -1;
        int opri = cgbHardware ? mmu.getDebugUndocumentedGbcRegister(0xff6c) : -1;
        int ff72 = cgbHardware ? mmu.getDebugUndocumentedGbcRegister(0xff72) : -1;
        int ff73 = cgbHardware ? mmu.getDebugUndocumentedGbcRegister(0xff73) : -1;
        int ff74 = cgbHardware ? mmu.getDebugUndocumentedGbcRegister(0xff74) : -1;
        int ff75 = cgbHardware ? mmu.getDebugUndocumentedGbcRegister(0xff75) : -1;
        int pcm12 = cgbHardware ? sound.getByte(0xff76) : -1;
        int pcm34 = cgbHardware ? sound.getByte(0xff77) : -1;
        var system = new DebugHardwareInspection.System(
                hardwareMode, key0, key1, vbk, svbk, !biosShadow.isBootFinished(),
                opri, ff72, ff73, ff74, ff75, pcm12, pcm34);
        return new DebugHardwareInspection(
                joypad.captureDebugJoypadInspection(
                        hardwareProfile.capabilities().superGameboyCommands()),
                serialPort.captureDebugSerialInspection(),
                infraredPort.captureDebugInfraredInspection(cgbHardware),
                dma.captureDebugOamDmaInspection(),
                vramDma,
                system);
    }

    /** Applies a debugger-owned mixer override without writing an emulated APU register. */
    public void setDebugAudioChannelEnabled(int channel, boolean enabled) {
        if (channel < 1 || channel > 4) {
            throw new IllegalArgumentException("Audio channel must be between 1 and 4");
        }
        sound.enableChannel(channel - 1, enabled);
    }

    private DebugPpuMode toDebugPpuMode() {
        if (!gpu.isLcdEnabled()) {
            return DebugPpuMode.DISABLED;
        }
        return switch (gpu.getMode()) {
            case HBlank -> DebugPpuMode.HBLANK;
            case VBlank -> DebugPpuMode.VBLANK;
            case OamSearch -> DebugPpuMode.OAM_SEARCH;
            case PixelTransfer -> DebugPpuMode.PIXEL_TRANSFER;
        };
    }

    private static DebugCpuState toDebugCpuState(Cpu.State state) {
        return switch (state) {
            case OPCODE -> DebugCpuState.OPCODE_FETCH;
            case EXT_OPCODE -> DebugCpuState.EXTENDED_OPCODE_FETCH;
            case OPERAND -> DebugCpuState.OPERAND_FETCH;
            case RUNNING -> DebugCpuState.EXECUTING;
            case IRQ_WAIT_1, IRQ_WAIT_2 -> DebugCpuState.INTERRUPT_WAIT;
            case IRQ_PUSH_1, IRQ_PUSH_2 -> DebugCpuState.INTERRUPT_PUSH;
            case IRQ_JUMP -> DebugCpuState.INTERRUPT_JUMP;
            case STOPPED -> DebugCpuState.STOPPED;
            case HALTED -> DebugCpuState.HALTED;
            case SPEED_SWITCH -> DebugCpuState.SPEED_SWITCH;
            case LOCKED -> DebugCpuState.LOCKED;
        };
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

    /**
     * Requests that a future visible frame omit host presentation work.
     *
     * <p>This is a host-only pacing control. The request is latched only when the PPU reaches
     * VBlank and never becomes serialized machine state. Super Game Boy profiles always retain
     * the complete DMG frame for emulated transfers; only their derived final host composite may
     * be omitted.</p>
     */
    public void requestFrameRenderSuppression(boolean suppress) {
        if (hardwareProfile.capabilities().superGameboyCommands()) {
            // SGB commands and VRAM transfers consume the complete DMG frame. Keep that emulated
            // input live and shed only SgbDisplay's derived host composite when pacing is behind.
            requestedFrameRenderSuppression = false;
            sgbDisplay.requestFrameRenderSuppression(suppress);
        } else {
            requestedFrameRenderSuppression = suppress;
        }
    }

    /**
     * Returns whether the current PPU frame is producing host-visible pixels.
     *
     * <p>This is host-only state used to choose coherent rewind capture points. It is deliberately
     * not part of serialized machine state.</p>
     */
    public boolean isCurrentVisibleFrameFullyRendering() {
        return !frameRenderSuppressed && sgbDisplay.isCurrentFrameRendering();
    }

    /**
     * Resumes normal output after restoring a rewind snapshot captured at a coherent frame point.
     *
     * <p>Manual state loads intentionally keep a partially restored scanout hidden until the next
     * physical frame edge. Rewind snapshots are selected only while full output is active, so the
     * controller can safely resume their restored output immediately.</p>
     */
    public void resumeFullFrameRenderingAfterRewindRestore() {
        requestedFrameRenderSuppression = false;
        frameRenderSuppressed = false;
        gpu.setRenderOutput(true);
        sgbDisplay.resetFrameRenderSuppression();
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

    /**
     * Rebinds primary and pass-through MBC3 clocks to current controller pause ownership after a
     * machine-state restore, without applying host time from the replaced timeline.
     */
    public void reanchorCartridgeRtcPause(boolean paused) {
        cartridge.reanchorRtcEmulationPause(paused);
        if (slotCartridge != null) {
            slotCartridge.reanchorRtcEmulationPause(paused);
        }
    }

    /** Captures MBC3 pause bookkeeping for each physical cartridge location. */
    public RtcRuntimeState captureRtcRuntimeState() {
        return new RtcRuntimeState(
                cartridge.captureRtcRuntimeState(),
                slotCartridge == null ? null : slotCartridge.captureRtcRuntimeState());
    }

    /** Captures RTC pause bookkeeping without consulting an external wall-clock service. */
    public RtcRuntimeState captureRtcRuntimeStateWithoutTimeSource() {
        return withStateTimeSourceAccessSuppressed(this::captureRtcRuntimeState);
    }

    /** Captures host-time boundaries for HuC3/TAMA5 without retaining their TimeSource. */
    public WallClockRuntimeState captureWallClockRuntimeState() {
        return new WallClockRuntimeState(
                cartridge.captureWallClockRuntimeState(),
                slotCartridge == null ? null : slotCartridge.captureWallClockRuntimeState());
    }

    /** Captures rollback wall-clock bookkeeping without consulting an external service. */
    public WallClockRuntimeState captureWallClockRuntimeStateWithoutTimeSource() {
        return withStateTimeSourceAccessSuppressed(this::captureWallClockRuntimeState);
    }

    /** Checks both cartridge RTC pause boundaries without mutating their wall-time state. */
    public boolean hasPausedCartridgeRtc() {
        return cartridge.isRtcEmulationPaused()
                || slotCartridge != null && slotCartridge.isRtcEmulationPaused();
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

    /**
     * Preflights an RTC supplement before the candidate cartridge mapper state has been restored.
     */
    public void validateRtcRuntimeStateForRestoreCandidate(RtcRuntimeState state) {
        if (state == null) {
            throw new IllegalArgumentException("Cartridge RTC runtime state is missing");
        }
        cartridge.validateRtcRuntimeStateForRestoreCandidate(state.primary());
        if (slotCartridge == null) {
            if (state.slot() != null) {
                throw new IllegalArgumentException("Slot RTC runtime state supplied without a slot cartridge");
            }
        } else {
            slotCartridge.validateRtcRuntimeStateForRestoreCandidate(state.slot());
        }
    }

    public void restoreRtcRuntimeState(RtcRuntimeState state) {
        validateRtcRuntimeState(state);
        cartridge.restoreRtcRuntimeState(state.primary());
        if (slotCartridge != null) {
            slotCartridge.restoreRtcRuntimeState(state.slot());
        }
    }

    public void validateWallClockRuntimeState(WallClockRuntimeState state) {
        if (state == null) {
            throw new IllegalArgumentException("Cartridge wall-clock runtime state is missing");
        }
        cartridge.validateWallClockRuntimeState(state.primary());
        if (slotCartridge == null) {
            if (state.slot() != null) {
                throw new IllegalArgumentException(
                        "Slot wall-clock runtime state supplied without a slot cartridge");
            }
        } else {
            slotCartridge.validateWallClockRuntimeState(state.slot());
        }
    }

    /** Advances restored mapper calendars only through their captured host-time boundaries. */
    public void restoreWallClockRuntimeState(WallClockRuntimeState state) {
        validateWallClockRuntimeState(state);
        cartridge.restoreWallClockRuntimeState(state.primary());
        if (slotCartridge != null) {
            slotCartridge.restoreWallClockRuntimeState(state.slot());
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

    /** Service-free HuC3/TAMA5 checkpoint boundaries for both physical cartridge locations. */
    public record WallClockRuntimeState(MemoryController.WallClockRuntimeState primary,
                                        MemoryController.WallClockRuntimeState slot) {
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

    /**
     * Returns the execution strategy selected when this session was created.
     *
     * <p>This value is session metadata and is unchanged by save-state capture or restore.</p>
     */
    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    /** Returns true once the BIOS-to-cartridge handoff has settled. */
    public boolean isBootstrapReady() {
        return bootCompatibilityResolved;
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

    /** Physical P1-P4 input latched at the most recent joypad sample boundary. */
    public eu.rekawek.coffeegb.core.joypad.PlayerInputSnapshot getSampledPlayerInput() {
        return joypad.getSampledInput();
    }

    /** Silently installs the external-input baseline paired with a replay checkpoint. */
    public void seedDeterministicReplayInput(
            java.util.Collection<eu.rekawek.coffeegb.core.joypad.Button> legacyButtons,
            eu.rekawek.coffeegb.core.joypad.PlayerInputSnapshot sampledPhysicalInput) {
        joypad.seedDeterministicReplayInput(legacyButtons, sampledPhysicalInput);
    }

    /** Silently applies one recorded absolute legacy-P1 replay transition. */
    public void applyDeterministicReplayLegacyInput(
            java.util.Collection<eu.rekawek.coffeegb.core.joypad.Button> legacyButtons) {
        joypad.applyDeterministicReplayLegacyInput(legacyButtons);
    }

    public void setPressedButtons(java.util.Collection<eu.rekawek.coffeegb.core.joypad.Button> pressed) {
        joypad.setPressedButtons(pressed);
    }

    /** Exclusively installs a source-local input observer without an alignment event. */
    public boolean attachInputTimelineObserver(InputTimelineObserver observer) {
        return joypad.attachInputTimelineObserver(observer);
    }

    /** Detaches a source-local input observer only while it still owns the seam. */
    public boolean detachInputTimelineObserver(InputTimelineObserver observer) {
        return joypad.detachInputTimelineObserver(observer);
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

    /** Captures rollback state without consulting an external wall-clock service. */
    public ComponentState<Gameboy> captureStateWithoutTimeSource() {
        return withStateTimeSourceAccessSuppressed(this::captureState);
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
        boolean previousRumble = isRumbleActive();
        if (!(state instanceof GameboyState mem)) {
            throw new IllegalArgumentException();
        }
        restoreMachineState(mem, true);
        resetHostFrameRenderingAfterRestore();
        synchronizeRumbleOutput(previousRumble);
    }

    /**
     * Applies emulated state without publishing host output or consulting an external RTC
     * TimeSource from a speculative transaction. Host-only presentation pacing is deliberately
     * preserved so the owning transaction can complete or roll back without changing it.
     */
    public void restoreStateSilently(ComponentState<Gameboy> state) {
        if (!(state instanceof GameboyState mem)) {
            throw new IllegalArgumentException();
        }
        withStateTimeSourceAccessSuppressed(() -> {
            restoreMachineState(mem, true);
            return null;
        });
    }

    private <R> R withStateTimeSourceAccessSuppressed(Supplier<R> action) {
        cartridge.setStateTimeSourceAccessSuppressed(true);
        try {
            return action.get();
        } finally {
            cartridge.setStateTimeSourceAccessSuppressed(false);
        }
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
        boolean previousRumble = isRumbleActive();
        restoreMachineState(bootState.state, false);
        synchronizeRumbleOutput(previousRumble);
    }

    private void restoreMachineState(GameboyState mem, boolean restoreCartridge) {
        nativeCgbScalarOwner = false;
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
        codeBreakerRumble.restoreStateSilently(mem.codeBreakerRumbleMemento());
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
        bootCompatibilityResolved = biosShadow.isBootFinished()
                && !blankCgbBootTilePending
                && !clearBootTilemapPending
                && !clearCgbBootOamShadowPending;
        gpu.setBootCompatibilityResolved(bootCompatibilityResolved);
    }

    /**
     * A snapshot can be restored in the middle of a scanout. Hold that partial host frame and
     * return to ordinary rendering at the following physical frame edge; otherwise the restored
     * tail could be published together with pixels from an abandoned host timeline.
     */
    private void resetHostFrameRenderingAfterRestore() {
        requestedFrameRenderSuppression = false;
        if (hardwareProfile.capabilities().superGameboyCommands()) {
            frameRenderSuppressed = false;
            gpu.setRenderOutput(true);
            sgbDisplay.resetFrameRenderSuppression();
            return;
        }
        frameRenderSuppressed = true;
        gpu.setRenderOutput(false);
    }

    /** Applies the most recent host request at one real PPU frame boundary. */
    private void latchFrameRenderSuppressionAtVBlank() {
        // Ordinary playback only performs this one host-request read and false branch. Do not
        // rewrite the output gate at every VBlank: it is already enabled and no host policy is
        // asking to change the following physical frame.
        if (!requestedFrameRenderSuppression) {
            if (frameRenderSuppressed) {
                resumeHostFrameRenderingAtVBlank();
            }
            return;
        }
        // Sustained catch-up pressure still presents every other physical LCD frame.
        if (frameRenderSuppressed) {
            resumeHostFrameRenderingAtVBlank();
        } else {
            frameRenderSuppressed = true;
            gpu.setRenderOutput(false);
        }
    }

    /** Starts a complete host scanout after any hidden partial frame. Called only at VBlank. */
    private void resumeHostFrameRenderingAtVBlank() {
        display.discardPartialFrame();
        frameRenderSuppressed = false;
        gpu.setRenderOutput(true);
    }

    /** Current aggregate emulated motor output, without invoking host services. */
    public boolean isRumbleActive() {
        return codeBreakerRumble.isMotorOn()
                || cartridge.isRumbleActive()
                || slotCartridge != null && slotCartridge.isRumbleActive();
    }

    /**
     * Reconciles aggregate motor state after a complete restore transaction commits.
     *
     * <p>Subscriber failures are contained here: emulated state is already committed and cannot
     * be rolled back merely because a host presentation callback rejected its reconciliation.
     * No synthetic event is sent when the aggregate output did not change.
     */
    public void synchronizeRumbleOutput(boolean previousActive) {
        boolean active = isRumbleActive();
        if (active == previousActive) {
            return;
        }
        try {
            hostEventBus.post(new RumbleEvent(active));
        } catch (RuntimeException failure) {
            LOG.warn("Unable to synchronize host rumble output after state restore", failure);
        }
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

        private ExecutionMode executionMode = ExecutionMode.ACCURACY;

        private BootstrapMode bootstrapMode = BootstrapMode.SKIP;

        private Rom slotRom;

        private byte[] batteryData;

        private byte[] slotBatteryData;

        private boolean supportBatterySave = true;

        private BatteryStorage batteryStorage;

        private BatteryStorage slotBatteryStorage;

        /** Selects service-free batteries while retaining live snapshot record ownership. */
        private boolean debugHistoryReplay;

        private Battery.DebugHistoryReplayShape debugHistoryPrimaryBatteryShape;

        private Battery.DebugHistoryReplayShape debugHistorySlotBatteryShape;

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

        /**
         * Selects the execution strategy for the session created by this configuration.
         * Accuracy is the default. The selected mode is not part of save-state data.
         */
        public GameboyConfiguration setExecutionMode(ExecutionMode executionMode) {
            this.executionMode = Objects.requireNonNull(executionMode, "executionMode");
            return this;
        }

        public ExecutionMode getExecutionMode() {
            return executionMode;
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

        /** Supplies an in-memory battery image for an Action Replay pass-through cartridge. */
        public GameboyConfiguration setSlotBatteryData(byte[] slotBatteryData) {
            this.slotBatteryData = slotBatteryData;
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

        /**
         * Service-free configuration for debugger history replay. Battery ownership is frozen
         * from the already-built live machine, rather than inferred from mutable save settings.
         * The supplied deterministic time and input sources replace live host services.
         */
        public GameboyConfiguration forDebugHistoryReplay(
                Gameboy liveGameboy,
                TimeSource rtcTimeSource,
                PlayerInputSource playerInputSource) {
            GameboyConfiguration copy = copy();
            Gameboy source = java.util.Objects.requireNonNull(
                    liveGameboy, "liveGameboy");
            copy.debugHistoryReplay = true;
            copy.debugHistoryPrimaryBatteryShape =
                    source.cartridge.debugHistoryReplayBatteryShape();
            copy.debugHistorySlotBatteryShape = source.slotCartridge == null
                    ? null : source.slotCartridge.debugHistoryReplayBatteryShape();
            copy.rtcTimeSource = java.util.Objects.requireNonNull(
                    rtcTimeSource, "rtcTimeSource");
            copy.playerInputSource = playerInputSource == null
                    ? PlayerInputSource.RELEASED : playerInputSource;
            return copy;
        }

        /** A boot-equivalent copy that cannot read or write a user's battery save. */
        public GameboyConfiguration forBootTemplate() {
            GameboyConfiguration copy = copy();
            copy.batteryData = null;
            copy.slotBatteryData = null;
            copy.supportBatterySave = false;
            return copy;
        }

        /**
         * Service-free copy for disposable real-game execution warmup. The caller has already
         * limited this to ordinary non-RTC cartridges, but a fixed clock source keeps that
         * boundary explicit should a future eligibility rule widen. No live input or writable
         * battery/storage service can reach the throwaway machine.
         */
        public GameboyConfiguration forRuntimeWarmup() {
            GameboyConfiguration copy = forBootTemplate();
            copy.bootstrapMode = BootstrapMode.SKIP;
            copy.batteryStorage = null;
            copy.slotBatteryStorage = null;
            copy.debugHistoryReplay = false;
            copy.debugHistoryPrimaryBatteryShape = null;
            copy.debugHistorySlotBatteryShape = null;
            copy.rtcTimeSource = () -> 0L;
            copy.playerInputSource = PlayerInputSource.RELEASED;
            return copy;
        }

        private GameboyConfiguration copy() {
            GameboyConfiguration copy = new GameboyConfiguration(rom);
            copy.hardwareProfile = hardwareProfile;
            copy.executionMode = executionMode;
            copy.bootstrapMode = bootstrapMode;
            copy.slotRom = slotRom;
            copy.batteryData = batteryData;
            copy.slotBatteryData = slotBatteryData;
            copy.supportBatterySave = supportBatterySave;
            copy.batteryStorage = batteryStorage;
            copy.slotBatteryStorage = slotBatteryStorage;
            copy.debugHistoryReplay = debugHistoryReplay;
            copy.debugHistoryPrimaryBatteryShape = debugHistoryPrimaryBatteryShape;
            copy.debugHistorySlotBatteryShape = debugHistorySlotBatteryShape;
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
