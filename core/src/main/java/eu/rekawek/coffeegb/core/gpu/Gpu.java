package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.debug.DebugAddressSpace;
import eu.rekawek.coffeegb.core.debug.DebugByteData;
import eu.rekawek.coffeegb.core.debug.DebugGraphicsHardwareMode;
import eu.rekawek.coffeegb.core.debug.DebugGraphicsInspection;
import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.DebugPpuMode;
import eu.rekawek.coffeegb.core.debug.trace.PpuTrace;
import eu.rekawek.coffeegb.core.gpu.phase.*;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.DmaOamAddressSpace;
import eu.rekawek.coffeegb.core.memory.Hdma;
import eu.rekawek.coffeegb.core.memory.Ram;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static eu.rekawek.coffeegb.core.gpu.GpuRegister.*;

public class Gpu implements AddressSpace, StatefulComponent<Gpu> {

    private static final int LCDC_ADDRESS = 0xff40;

    private static final int LAST_STANDARD_REGISTER_ADDRESS = 0xff4b;

    /** Pixel-domain synchronization delay for selected DMG register latches. */
    private static final int PPU_WRITE_DELAY_DOTS = 4;

    private final Ram videoRam0;

    private final Ram videoRam1;

    private final AddressSpace oamRam;

    private final AddressSpace ppuOam;

    private final Display display;

    private final Dma dma;

    // Optional because standalone GPU fixtures predate Gameboy-owned HDMA. Gameboy attaches
    // the real controller before emulation starts so the CGB timing cursor can fail closed on
    // both active and newly-started VRAM-DMA bursts.
    private transient Hdma hdma;

    private final Lcdc lcdc;

    private final boolean gbc;

    private final eu.rekawek.coffeegb.core.cpu.SpeedMode speedMode;

    // Refreshed synchronously when SpeedMode changes, so the PPU timing decision tree
    // never needs to repeat cross-object getter calls during a tick.
    private int speedModeValue = 1;
    private boolean dmgCompatValue;
    private boolean timingModeDirty = true;

    // Monotonic owner-thread generation for the transient STAT timing view.  The
    // PPU advances once per master tick, so StatRegister can reuse the snapshot it
    // captured after the preceding GPU tick and only recapture when GPU state has
    // actually moved in between.
    private long timingGeneration;

    private final boolean earlyCgbLyReadEdge;

    // Construction-time capability supplied by Gameboy.  This is deliberately a positive,
    // profile-filtered permission rather than a raw execution mode: only normal-speed DMG/MGB,
    // SGB/SGB2, ordinary CGB compatibility, and native CGB/CGB0 sessions without history/replay
    // may enter the timing-skeleton cursor.
    private final boolean performanceSteadyTiming;

    // The shifted output machine has a separate guarded span for DMG/MGB, ordinary CGB
    // compatibility, native CGB/CGB0, and both measured SGB rows.
    private final boolean performanceSteadyOutput;

    // DMG-compatibility timing has a separate required matrix row. Keep it scoped to the
    // ordinary CGB profile; CGB0 compatibility remains on the scalar reference path until its
    // revision-specific timing is measured independently.
    private final boolean performanceDmgCompatTiming;

    // The line-at-a-time renderer is a deliberately broader PERFORMANCE escape hatch than the
    // guarded steady-background cursor. It is enabled only by Gameboy.runTicks(), so existing
    // direct Gpu.tick() probes and ACCURACY remain on their calibrated dot pipelines.
    private final boolean performanceScanlineCapable;

    // Direct SGB/SGB2 lines still feed the host-visible raw DMG pixel stream into the
    // Super Game Boy VRAM transfer. Keep this alias profile-gated so every other direct
    // renderer path remains unchanged.
    private final VRamTransfer performanceSgbVramTransfer;

    // Exact construction-time permission for the short DMG-clocked mode-2 phase packet. Keep
    // this narrower than the monochrome pixel/timing cursors: only physical DMG/MGB and the
    // exact SGB/SGB2 profiles may reuse the same OAM-search arithmetic. Compatibility profiles
    // remain excluded even when their clock happens to be normal speed.
    private final boolean performancePhysicalDmgMode2;

    private final ColorPalette bgPalette;

    private final ColorPalette oamPalette;

    private final OamSearch oamSearchPhase;

    private final PixelTransfer pixelTransferPhase;

    // second dot machine running one machine cycle behind the skeleton; it produces the
    // pixels (its reads land on the hardware dots) while pixelTransferPhase keeps the
    // calibrated CPU-visible mode/STAT/lock timing. They diverge only when a mid-line
    // write changes a stall length within the 4-tick skew.
    private final PixelTransfer pixelMachine;

    private final PerformanceScanlineRenderer performanceScanlineRenderer;

    private final StatRegister statRegister;

    private boolean lcdEnabled = true;

    private int displayEnabledDelay;

    private final GpuRegisterValues r;

    private int line;

    // starts at 1 so the power-on line grid has the same machine-cycle phase as a
    // line grid started by an LCDC write (which is followed by a 455-tick first line)
    private int ticksInLine = 1;

    // the line started by enabling the LCD is special: no OAM scan, mode reads 0
    // until the pixel transfer starts, and OAM/VRAM stay accessible until then
    private boolean firstLine;

    // Enabling the LCD anchors the PPU grid one dot away from the power-on grid. The
    // phase survives frame rollover until the LCD is disabled again.
    private boolean lcdEnableClockPhase;

    // The first frame after LCD enable has a distinct native-CGB LY read phase at
    // line 153 dot zero. Later frames retain 153 there on the ordinary CPU bus.
    private boolean firstFrameAfterLcdEnable;

    private Mode mode;

    private GpuPhase phase;

    // tick at which the pixel transfer finished; the visible mode/locks change one tick later
    private boolean pixelTransferDone;

    // tick at which the hblank term of the STAT interrupt line rises; it precedes the
    // visible mode 0 and is quantized to 4-tick steps (hblank_ly_scx_timing-GS)
    private int hblankIntFrom = Integer.MAX_VALUE;

    // The mode-0 STAT source is predicted at output X=158 (Gambatte's PPU X=166).
    // It can rise while an object at X=167 is still extending the physical transfer.
    private int mode0IntFrom = Integer.MAX_VALUE;

    // Native-CGB scalar ticks use this compact read-phase word instead of materializing the
    // general STAT timing snapshot. The low byte is the current line; the two high bits are the
    // only native double-speed mode-0 lookahead facts consumed by StatRegister.
    static final int NATIVE_CGB_PHASE_LINE_MASK = 0xff;
    static final int NATIVE_CGB_PHASE_MODE0_EDGE_NEXT = 1 << 8;
    static final int NATIVE_CGB_PHASE_MODE0_READ_PREVIEW = 1 << 9;

    // Packed post-GPU STAT facts. The validity bit is deliberately derived from the committed
    // PPU state, so a CPU speed/compatibility transition during the owner tick fails closed.
    static final int NATIVE_CGB_POST_STAT_FACTS_VALID = 1 << 31;
    static final int NATIVE_CGB_POST_STAT_CHECKPOINT = 1 << 30;

    // Switching the CGB CPU clock remaps the PPU timestamp and rephases the CPU-side
    // STAT mode latch. Until that happens, the boot-time latch has its five-dot tail.
    private boolean statModeLatchRephasedBySpeedSwitch;

    // The clock mux retains its old STAT read phase for the remainder of the scanline
    // on which a speed-switch tail completes.
    private boolean speedSwitchCompletedThisLine;

    // The CPU-facing LY bus is rephased by the clock mux too, but restarting the
    // LCD realigns this latch with the new line grid while the STAT phase persists.
    private boolean lyReadLatchRephasedBySpeedSwitch;

    // A line-scoped SCX write makes a no-window line follow the dynamic shifted
    // pipeline instead of the steady-line fixed STAT release.
    private boolean scxWrittenThisLine;

    // A double-speed mode-2 interrupt is accepted on an alternate CPU/PPU phase.
    // Its handler retains the object-free mode-3 prediction later in the line.
    private boolean doubleSpeedMode2DispatchStatTailThisLine;

    // Preserve that handler phase for the first CPU slot after line rollover.
    private boolean doubleSpeedMode2DispatchCrossedLineEdge;

    // A fine-SCX write that crosses the startup comparator on that captured phase
    // leaves the readable mode latch on the extended prediction path.
    private boolean earlyScxStatTailThisLine;

    // Distinguish a delayed-WY comparator race from LCDC/WX changes that made only
    // the shifted output machine start a window on this line.
    private boolean wyWrittenThisLine;

    // A native double-speed LCDC.5 edge in the final line-zero CPU slot changes
    // which side of the following line's mode-3/mode-0 read mux is sampled.
    private boolean lateDoubleSpeedLineZeroWindowEnable;

    // A CPU VRAM write holds its arbitration request through the immediately
    // following read cycle. This matters at the mode-3/mode-0 hand-off, where a
    // standalone read and a write-then-read sequence see different slots.
    private int lastCpuVramWriteTick = Integer.MIN_VALUE;

    private transient boolean cpuRetiringInstructionForHdma;

    // CPU callbacks run before this dot's PPU clocks. An ordinary HALT wake can
    // sample the next native-CGB LY value at the end of that callback's bus cycle.
    private transient boolean cpuLyReadAcrossLineEdge;

    /**
     * Coffee GB keeps a calibrated CPU-visible timing skeleton and a pixel-producing
     * dot machine four dots behind it. Selected DMG register slices cross into that
     * second clock domain through their own latches; CPU reads still see the bus value
     * immediately. This queue models that crossing without delaying the CPU or timer.
     */
    private final List<PendingPpuWriteRuntime> pendingPpuWrites = new ArrayList<>();

    private final int[] cpuVisiblePpuRegisters =
            new int[LAST_STANDARD_REGISTER_ADDRESS - LCDC_ADDRESS + 1];

    private boolean directOamReadCorruptionThisTick;

    private boolean suppressNextDirectOamReadCorruption;

    private boolean directOamWriteCorruptionThisTick;

    private boolean suppressNextDirectOamWriteCorruption;

    private transient DebugHooks debugHooks;

    // Debugger/retirement observation requires the scalar PPU path. This is session metadata,
    // not emulated hardware state, and intentionally remains outside the canonical memento.
    private transient boolean performanceObservationBlocked;

    // A PERFORMANCE cursor is not allowed to run until Gameboy has resolved the boot-ROM
    // compatibility handoff. This is session metadata rather than hardware state: restoring a
    // mid-boot snapshot derives it again from BiosShadow, and SKIP boot notifies us at init end.
    private transient boolean bootCompatibilityResolved;

    // Deferred timing-skeleton work for one proven DMG background line.  These fields are
    // transient by design; capture/restore first materializes and therefore never serializes a
    // lazy cursor.
    private transient boolean steadyTimingCursor;
    private transient int steadyTimingTicks;
    private transient int steadyTimingEndTick;
    private transient boolean steadyOutputCursor;
    private transient long steadyTimingDmaGeneration;
    private transient long steadyTimingHdmaGeneration;

    // Session-only counters for the PERFORMANCE raster fast path. They are deliberately
    // transient: exposing how often a speculative cursor happened to arm must not alter a
    // portable machine snapshot or its restore behavior.
    private transient long performanceSteadyFastTicks;
    private transient long performanceSteadyFastFallbacks;

    private transient boolean performanceScanlineEnabled;
    private transient boolean performanceScanlineCursor;
    // Remains set through HBlank after the cursor hands off, because PixelTransfer was stopped
    // at mode-3 entry and its position/FIFO no longer describe the coarse line. CPU-visible
    // helpers use this line-scoped marker until the next OAM-to-mode-3 boundary.
    private transient boolean performanceScanlineLine;
    private transient int performanceScanlineEndTick;
    private transient long performanceScanlineFastTicks;
    private transient long performanceScanlineLines;
    private transient long performanceScanlineFallbacks;
    private transient boolean performanceRenderOutput = true;
    // PixelTransfer's fetcher is stopped after a direct line is composed, so its internal
    // window row cannot advance on later lines. Keep the coarse row as stable emulated state and
    // feed it to the line renderer at each mode-3 entry.
    private int performanceWindowLineCounter = -1;

    // Public mutable component accessors predate the performance executor. Once one of those
    // aliases escapes, a later write cannot be observed by Gpu, so this session stays scalar.
    // This is session metadata, not emulated hardware state, and deliberately survives restore.
    private transient boolean mutablePpuStateExposed;

    /** Monotonic physical frame-ready count used only by debugger observations. */
    private long debugPpuFrame;

    public Gpu(Display display, Dma dma, Ram oamRam, VRamTransfer vRamTransfer,
               StatRegister statRegister, boolean gbc,
               eu.rekawek.coffeegb.core.cpu.SpeedMode speedMode) {
        this(display, dma, oamRam, vRamTransfer, statRegister, gbc, speedMode, false);
    }

    public Gpu(Display display, Dma dma, Ram oamRam, VRamTransfer vRamTransfer,
               StatRegister statRegister, boolean gbc,
               eu.rekawek.coffeegb.core.cpu.SpeedMode speedMode,
               boolean mealybugDmgBlob) {
        this(display, dma, oamRam, vRamTransfer, statRegister, gbc, speedMode,
                mealybugDmgBlob, false);
    }

    public Gpu(Display display, Dma dma, Ram oamRam, VRamTransfer vRamTransfer,
               StatRegister statRegister, boolean gbc,
               eu.rekawek.coffeegb.core.cpu.SpeedMode speedMode,
               boolean mealybugDmgBlob, boolean earlyCgbLyReadEdge) {
        this(display, dma, oamRam, vRamTransfer, statRegister, gbc, speedMode,
                mealybugDmgBlob, earlyCgbLyReadEdge,
                ExecutionMode.ACCURACY, null, false);
    }

    public Gpu(Display display, Dma dma, Ram oamRam, VRamTransfer vRamTransfer,
               StatRegister statRegister, boolean gbc,
               eu.rekawek.coffeegb.core.cpu.SpeedMode speedMode,
               boolean mealybugDmgBlob, boolean earlyCgbLyReadEdge,
               ExecutionMode executionMode, HardwareProfile hardwareProfile,
               boolean debugHistoryReplay) {
        this.statRegister = statRegister;
        Arrays.fill(cpuVisiblePpuRegisters, -1);
        this.display = display;
        this.r = new GpuRegisterValues();
        this.lcdc = new Lcdc(mealybugDmgBlob);
        this.gbc = gbc;
        this.speedMode = speedMode;
        this.earlyCgbLyReadEdge = earlyCgbLyReadEdge;
        this.performanceSteadyTiming = executionMode == ExecutionMode.PERFORMANCE
                && !debugHistoryReplay
                && (hardwareProfile == HardwareProfileRegistry.DMG
                || hardwareProfile == HardwareProfileRegistry.MGB
                || hardwareProfile == HardwareProfileRegistry.CGB
                || hardwareProfile == HardwareProfileRegistry.CGB0
                || hardwareProfile == HardwareProfileRegistry.SGB
                || hardwareProfile == HardwareProfileRegistry.SGB2);
        this.performanceSteadyOutput = executionMode == ExecutionMode.PERFORMANCE
                && !debugHistoryReplay
                && (hardwareProfile == HardwareProfileRegistry.DMG
                || hardwareProfile == HardwareProfileRegistry.MGB
                || hardwareProfile == HardwareProfileRegistry.CGB
                || hardwareProfile == HardwareProfileRegistry.CGB0
                || hardwareProfile == HardwareProfileRegistry.SGB
                || hardwareProfile == HardwareProfileRegistry.SGB2);
        this.performanceDmgCompatTiming = executionMode == ExecutionMode.PERFORMANCE
                && !debugHistoryReplay
                && hardwareProfile == HardwareProfileRegistry.CGB;
        this.performanceScanlineCapable = executionMode == ExecutionMode.PERFORMANCE
                && !debugHistoryReplay
                && (hardwareProfile == HardwareProfileRegistry.DMG
                || hardwareProfile == HardwareProfileRegistry.MGB
                || hardwareProfile == HardwareProfileRegistry.CGB
                || hardwareProfile == HardwareProfileRegistry.CGB0
                || hardwareProfile == HardwareProfileRegistry.SGB
                || hardwareProfile == HardwareProfileRegistry.SGB2);
        this.performanceSgbVramTransfer = hardwareProfile == HardwareProfileRegistry.SGB
                || hardwareProfile == HardwareProfileRegistry.SGB2 ? vRamTransfer : null;
        this.performancePhysicalDmgMode2 = executionMode == ExecutionMode.PERFORMANCE
                && !debugHistoryReplay
                && (hardwareProfile == HardwareProfileRegistry.DMG
                || hardwareProfile == HardwareProfileRegistry.MGB
                || hardwareProfile == HardwareProfileRegistry.SGB
                || hardwareProfile == HardwareProfileRegistry.SGB2);
        this.r.setGbc(gbc);
        this.r.setSpeedMode(speedMode);
        this.lcdc.setGbc(gbc);
        this.videoRam0 = new Ram(0x8000, 0x2000);
        if (gbc) {
            this.videoRam1 = new Ram(0x8000, 0x2000);
        } else {
            this.videoRam1 = null;
        }
        this.oamRam = oamRam;
        this.dma = dma;
        this.ppuOam = new DmaOamAddressSpace(oamRam, dma);

        this.bgPalette = new ColorPalette(0xff68);
        this.oamPalette = new ColorPalette(0xff6a);
        if (gbc) {
            oamPalette.initializeCgbBootValues();
        }

        this.oamSearchPhase = new OamSearch(oamRam, dma, lcdc, r);
        this.performanceScanlineRenderer = new PerformanceScanlineRenderer(
                videoRam0, videoRam1, oamRam, lcdc, r, bgPalette, oamPalette,
                gbc, false, oamSearchPhase.getSprites(), performanceSgbVramTransfer);
        this.pixelTransferPhase = new PixelTransfer(new Display(gbc), videoRam0, videoRam1, ppuOam, lcdc, r, gbc, bgPalette, oamPalette, oamSearchPhase.getSprites(), null, speedMode, 0, true);
        this.pixelMachine = new PixelTransfer(display, videoRam0, videoRam1, ppuOam, lcdc, r, gbc, bgPalette, oamPalette, oamSearchPhase.getSprites(), vRamTransfer, speedMode, 4);
        this.pixelMachine.setOamReaderBus(oamSearchPhase);

        this.mode = Mode.OamSearch;
        this.phase = oamSearchPhase.start();
        prepareForTick();
        speedMode.setTimingStateListener(() -> {
            timingGeneration++;
            timingModeDirty = true;
            prepareForTick();
        });
    }

    /**
     * Enables or suppresses only the resolved panel output of the visible pixel machine.
     *
     * <p>The timing-only pixel machine always remains disabled. This host-facing switch leaves
     * every PPU timing, FIFO advance, and CPU-visible state transition intact while avoiding the
     * final palette/display work for a deliberately unpresented frame.</p>
     */
    public void setRenderOutput(boolean renderOutput) {
        materializeSteadyTiming();
        performanceRenderOutput = renderOutput;
        pixelMachine.setRenderOutput(renderOutput);
    }

    /**
     * Enables the approximate line-at-a-time raster path for a PERFORMANCE run loop.
     *
     * <p>The flag is intentionally separate from {@link #performanceSteadyTiming}: unit tests
     * and tools that call {@link #tick()} directly continue to exercise the calibrated scalar
     * or guarded steady cursor. A frame-sized {@code Gameboy.runTicks} call opts in explicitly.
     * Disabling the flag suppresses new arms; an already armed cursor remains resumable until
     * its predicted handoff, so a caller can safely mix frame-sized and scalar scheduling.</p>
     */
    public void setPerformanceScanlineEnabled(boolean enabled) {
        if (enabled && !performanceScanlineCapable) {
            return;
        }
        performanceScanlineEnabled = enabled;
    }

    /** Number of complete scanlines rendered by the approximate PERFORMANCE path. */
    public long getPerformanceScanlineLines() {
        return performanceScanlineLines;
    }

    /** Number of master dots skipped by the line-at-a-time raster path. */
    public long getPerformanceScanlineFastTicks() {
        return performanceScanlineFastTicks;
    }

    /** Number of line-at-a-time candidates that failed their safety predicate. */
    public long getPerformanceScanlineFallbacks() {
        return performanceScanlineFallbacks;
    }

    /** Current coarse window row used by the PERFORMANCE line compositor. */
    public int getPerformanceWindowLineCounter() {
        return performanceWindowLineCounter;
    }

    private AddressSpace getAddressSpace(int address) {
        if (videoRam0.accepts(address)) {
            return isVramAvailableForCpu() ? selectedVideoRam() : null;
        } else if (oamRam.accepts(address)) {
            return !dma.isOamBlocked() && isOamAvailableForCpu() ? oamRam : null;
        } else if (lcdc.accepts(address)) {
            return lcdc;
        } else if (r.accepts(address)) {
            return r;
        } else if (gbc && bgPalette.accepts(address)) {
            return bgPalette;
        } else if (gbc && oamPalette.accepts(address)) {
            return oamPalette;
        } else {
            return null;
        }
    }

    public Ram getVideoRam() {
        exposeMutablePpuState();
        return selectedVideoRam();
    }

    private Ram selectedVideoRam() {
        if (gbc && (r.get(VBK) & 1) == 1) {
            return videoRam1;
        } else {
            return videoRam0;
        }
    }

    public Ram getVideoRam0() {
        exposeMutablePpuState();
        return videoRam0;
    }

    public Ram getVideoRam1() {
        exposeMutablePpuState();
        return videoRam1;
    }

    /** Core boot-state helper that does not expose a retained mutable RAM alias. */
    public void writeVideoRam0ForCore(int address, int value) {
        materializeSteadyTiming();
        videoRam0.setByte(address, value);
    }

    /** Core HDMA helper that reads the selected physical bank without exposing its RAM object. */
    public int readSelectedVideoRamForCore(int address) {
        materializeSteadyTiming();
        return selectedVideoRam().getByte(address);
    }

    @Override
    public boolean accepts(int address) {
        return videoRam0.accepts(address) || oamRam.accepts(address) || lcdc.accepts(address)
                || r.accepts(address) || (gbc && (bgPalette.accepts(address) || oamPalette.accepts(address)));
    }

    @Override
    public void setByte(int address, int value) {
        materializeSteadyTiming();
        timingGeneration++;
        cancelPendingPpuWrites(address);
        cancelDelayedPixelWindowWrite(address);
        setByteImmediately(address, value);
    }

    @Override
    public void setByteFromCpu(int address, int value) {
        materializeSteadyTiming();
        timingGeneration++;
        scheduleDmgPixelWindowWrite(address, value);
        if (address == LCDC_ADDRESS && gbc && lcdEnabled && mode == Mode.PixelTransfer) {
            int changedTileSelect = (lcdc.get() ^ value) & 0x10;
            boolean fallingEdge = (lcdc.get() & 0x10) != 0 && (value & 0x10) == 0;
            if ((speedModeValue == 1 && fallingEdge)
                    || (speedModeValue == 2 && changedTileSelect != 0)) {
                lcdc.triggerTileSelectGlitch();
            }
        }
        if (address == SCX.getAddress() && lcdEnabled && line < 144) {
            boolean dmgStartupEdge = !gbc
                    && pixelTransferPhase.getPosition() == -16
                    && pixelMachine.getPosition() == -16;
            boolean doubleSpeedStartupEdge = gbc && speedModeValue == 2
                    && doubleSpeedMode2DispatchStatTailThisLine
                    && pixelMachine.getPosition() == -6;
            if (((r.get(SCX) ^ value) & 0x07) != 0
                    && mode == Mode.PixelTransfer
                    && (dmgStartupEdge || doubleSpeedStartupEdge)) {
                earlyScxStatTailThisLine = true;
            }
            scxWrittenThisLine = true;
            statRegister.onScxWrite();
        }
        if (!shouldDelayPpuWrite(address, value)) {
            cancelPendingPpuWrites(address);
            setByteImmediately(address, value);
            return;
        }

        int mask = getDelayedPpuWriteMask(address);
        int current = getCurrentPpuWriteValue(address);
        cpuVisiblePpuRegisters[address - LCDC_ADDRESS] = value;

        // Non-delayed bits take effect on the CPU write edge. Writing the retained
        // value also preserves the DMG's separate write-strobe effects (notably the
        // immediate WX "just changed" pulse) while the synchronized value is pending.
        int immediateValue = (value & ~mask) | (current & mask);
        setByteImmediately(address, immediateValue);
        pendingPpuWrites.add(new PendingPpuWriteRuntime(
                address, value, mask, getPpuWriteDelayDots(address)));
    }

    private void setByteImmediately(int address, int value) {
        if (address == LYC.getAddress()) {
            statRegister.onLycWrite(r.get(LYC), value);
        }
        if (address == WY.getAddress()) {
            // The CGB's secondary WY comparator trails a CPU write. At double speed
            // the same delay occupies fewer PPU dots; DMG writes already arrive on
            // the comparator's clock edge in this scheduler.
            int comparatorDelay = !lcdEnabled || !gbc
                    ? 0
                    : speedModeValue == 2 ? 4 : 6;
            pixelTransferPhase.scheduleWindowYWrite(value, comparatorDelay);
            pixelMachine.scheduleWindowYWrite(value, comparatorDelay);
            if (lcdEnabled && line < 144) {
                wyWrittenThisLine = true;
            }
        }
        if (oamRam.accepts(address)) {
            if (!gbc) {
                int accessedRow = getDirectOamWriteRow();
                if (accessedRow >= 0) {
                    if (suppressNextDirectOamWriteCorruption) {
                        suppressNextDirectOamWriteCorruption = false;
                    } else {
                        SpriteBug.corruptOamWrite(oamRam, accessedRow);
                        directOamWriteCorruptionThisTick = true;
                    }
                }
            }
            if (!dma.isOamBlocked() && isOamAvailableForCpu(true)) {
                oamRam.setByte(address, value);
            }
            return;
        }
        if (videoRam0.accepts(address)) {
            lastCpuVramWriteTick = ticksInLine;
            if (isVramAvailableForCpu(true)) {
                selectedVideoRam().setByte(address, value);
            }
            return;
        }
        AddressSpace space = getAddressSpace(address);
        if (space == lcdc) {
            setLcdc(value);
        } else if ((space == bgPalette || space == oamPalette)
                && ((ColorPalette) space).isDataAddress(address)
                && !isPaletteAccessibleForCpu()) {
            // CGB palette RAM is locked during mode 3: the data write is dropped but the
            // index still auto-increments. A game streaming a per-scanline palette (Ken
            // Griffey's Slugfest) writes past the mode-3 boundary and relies on those
            // writes being ignored; applying them scrambles the colours.
            ((ColorPalette) space).blockedDataWrite();
        } else if (space != null) {
            space.setByte(address, value);
        }
    }

    @Override
    public int getByte(int address) {
        materializeSteadyTiming();
        if (address >= LCDC_ADDRESS && address <= LAST_STANDARD_REGISTER_ADDRESS) {
            int cpuVisible = cpuVisiblePpuRegisters[address - LCDC_ADDRESS];
            if (cpuVisible >= 0) {
                return cpuVisible;
            }
        }
        if (address == LY.getAddress()) {
            return getCpuVisibleLy();
        }
        if (!gbc && oamRam.accepts(address)) {
            int accessedRow = getDirectOamReadRow();
            if (accessedRow >= 0) {
                if (suppressNextDirectOamReadCorruption) {
                    suppressNextDirectOamReadCorruption = false;
                } else {
                    SpriteBug.corruptOamRead(oamRam, address, accessedRow);
                    directOamReadCorruptionThisTick = true;
                }
            }
        }
        AddressSpace space = getAddressSpace(address);
        if (space == null) {
            return 0xff;
        } else if (address == VBK.getAddress()) {
            return gbc ? (0xfe | (space.getByte(address) & 1)) : 0xff;
        } else if ((space == bgPalette || space == oamPalette)
                && ((ColorPalette) space).isDataAddress(address)
                && !isPaletteAccessibleForCpu()) {
            // palette RAM reads back 0xff while locked during mode 3
            return 0xff;
        } else {
            return space.getByte(address);
        }
    }

    private boolean isPaletteAccessibleForCpu() {
        if (!lcdEnabled) {
            return true;
        }
        if (firstLine && ticksInLine < 84) {
            // The CGB palette bus has its own display-enable latch: it remains open
            // through dot 79 at either CPU speed, then closes before the fetcher does.
            return ticksInLine < 80;
        }
        if (mode == Mode.PixelTransfer) {
            if (performanceScanlineLine) {
                // The direct renderer snapshots palettes at mode-3 entry and treats later
                // writes as next-line updates; CPU palette reads remain locked for the whole
                // coarse transfer rather than observing the entry position left in PixelTransfer.
                return false;
            }
            // Before the CPU/PPU clock mux has moved, the palette latch closes with
            // the mode-3 skeleton. After a speed switch it follows the fetch-start
            // phase instead. At double speed the first internal transfer dot is
            // occupied, followed by one accessible CPU cycle before the steady lock.
            if (!statModeLatchRephasedBySpeedSwitch) {
                return false;
            }
            int position = pixelTransferPhase.getPosition();
            return speedModeValue == 2
                    ? position > -16 && position < -4
                    : position < -4;
        }
        if (mode == Mode.OamSearch
                && speedModeValue == 2
                && ticksInLine >= 79) {
            // A double-speed CPU can sample the closing latch one dot before the
            // internal mode-3 transition.
            return false;
        }
        if (performanceScanlineLine && mode == Mode.HBlank) {
            // The direct line was fully composed at mode-3 entry; use the coarse output-latch
            // tail rather than the frozen PixelTransfer position when reopening CGB palettes.
            return !gbc || ticksInLine >= hblankIntFrom + 8;
        }
        if (!firstLine
                && speedModeValue == 1
                && !pixelTransferPhase.hasObjectsOnLine()
                && !pixelTransferPhase.hasActivatedWindowOnLine()) {
            // On steady BG-only normal-speed lines, the release is quantized by fine
            // SCX rather than following the variable internal HBlank edge.
            return mode != Mode.HBlank
                    || ticksInLine >= 258 + ((r.get(SCX) & 0x04) != 0 ? 4 : 0);
        }
        // Other lines open eight dots after the mode-0 edge at normal speed and six
        // dots after it at double speed.
        int handoffDots = 4 + 4 / speedModeValue;
        return mode != Mode.HBlank || ticksInLine >= hblankIntFrom + handoffDots;
    }

    public Mode tick() {
        timingGeneration++;
        cpuLyReadAcrossLineEdge = false;
        if (!gbc) {
            directOamReadCorruptionThisTick = false;
            suppressNextDirectOamReadCorruption = false;
            directOamWriteCorruptionThisTick = false;
            suppressNextDirectOamWriteCorruption = false;
        }
        if (displayEnabledDelay > 0 && --displayEnabledDelay == 0) {
            display.enableLcd();
        }

        if (!lcdEnabled) {
            return null;
        }

        // The scheduler flag only gates new arms. An already rendered line remains a valid
        // coarse cursor after a runTicks call returns, so a mixed scalar caller can finish the
        // predicted handoff without shortening the line or reviving the stopped FIFOs. PPU
        // writes, observation, DMA, and other explicit invalidators still call
        // disablePerformanceScanlineCursor() synchronously.
        boolean performanceScanlineTick = performanceScanlineCursor;
        if (!pendingPpuWrites.isEmpty()) {
            advancePendingPpuWrites();
        }

        // A line-at-a-time render has already produced all visible pixels from the mode-3 entry
        // snapshot. Keep register write/conflict latches and delayed write queues alive, but skip
        // the two FIFO machines and their window checkpoints until the coarse handoff.
        boolean earlyWindowFrameEdge = !gbc || speedModeValue == 1;
        boolean timingTickDeferred = false;
        if (!performanceScanlineTick) {
            pixelMachine.advanceDelayedWindowWrites();

            // write-conflict mixes settle and the LCD output stage advances every tick,
            // in all modes (the last pixels of a line leave the delay line during HBlank)
            r.tickConflicts();
            lcdc.tickConflicts();
            if (earlyWindowFrameEdge && line == 153 && ticksInLine == 454) {
                pixelTransferPhase.resetWindowLineCounter();
                pixelMachine.resetWindowLineCounter();
            }
            int windowYCheckpoint = PixelTransfer.windowYCheckpoint(
                    gbc, speedModeValue, line, ticksInLine);
            int timingWindowWy = pixelTransferPhase.advanceWindowYDelay();
            int outputWindowWy = pixelMachine.advanceWindowYDelay();
            if (windowYCheckpoint != 0) {
                pixelTransferPhase.sampleWindowY(windowYCheckpoint, timingWindowWy);
                pixelMachine.sampleWindowY(windowYCheckpoint, outputWindowWy);
            }
            // Both dot machines enqueue popped pixels into an eight-slot LCD delay line.
            // The timing-only skeleton has a throwaway Display, but its delay line still
            // participates in window rewind/refresh bookkeeping and must advance as a
            // bounded ring just like the shifted output machine.
            boolean candidateTimingTickDeferred = deferSteadyTimingDot();
            timingTickDeferred = candidateTimingTickDeferred;
            if (!timingTickDeferred) {
                pixelTransferPhase.outputTick();
            }
            if (!(timingTickDeferred && steadyOutputCursor)) {
                pixelMachine.outputAndMachineTick();
            }
        } else {
            // The direct path still settles one-tick DMG/CGB conflict latches. It intentionally
            // leaves the PixelTransfer positions at mode-3 entry; CPU-visible locks use the
            // coarse mode and the line handoff below publishes the predicted endpoint.
            r.tickConflicts();
            lcdc.tickConflicts();
        }

        Mode oldMode = mode;
        int oldLine = line;
        ticksInLine++;
        int lineLength = firstLine ? 455 : 456;
        int oamReaderPosition = ticksInLine == lineLength ? 0 : ticksInLine;
        if (oamReaderPosition < 80
                || dma.hasPpuOamOwnershipTransitionThisTick()
                || !oamSearchPhase.isOamReaderInitialized()) {
            oamSearchPhase.trackDmaSource(oamReaderPosition);
        }
        // the line started by enabling the LCD is one tick shorter: its grid starts at
        // the LCDC write itself, while the machine-cycle-locked line grid starts one
        // tick later (lcdon_timing-GS vs the steady-state line phase)
        if (ticksInLine == lineLength) {
            performanceScanlineLine = false;
            ticksInLine = 0;
            lastCpuVramWriteTick = Integer.MIN_VALUE;
            firstLine = false;
            pixelTransferDone = false;
            hblankIntFrom = Integer.MAX_VALUE;
            mode0IntFrom = Integer.MAX_VALUE;
            speedSwitchCompletedThisLine = false;
            scxWrittenThisLine = false;
            doubleSpeedMode2DispatchCrossedLineEdge =
                    doubleSpeedMode2DispatchStatTailThisLine;
            doubleSpeedMode2DispatchStatTailThisLine = false;
            earlyScxStatTailThisLine = false;
            wyWrittenThisLine = false;
            line++;
            if (line == 154) {
                line = 0;
                // PixelTransfer resets its own window master at the physical frame edge. Keep
                // the coarse compositor's independent counter on that same edge, including
                // frames that contained scalar/deoptimized lines.
                performanceWindowLineCounter = -1;
                firstFrameAfterLcdEnable = false;
                lateDoubleSpeedLineZeroWindowEnable = false;
                if (!earlyWindowFrameEdge) {
                    pixelTransferPhase.resetWindowLineCounter();
                    pixelMachine.resetWindowLineCounter();
                }
            }
            r.put(LY, line);
            if (line == 144) {
                mode = Mode.VBlank;
            } else if (line < 144) {
                mode = Mode.OamSearch;
                phase = oamSearchPhase.start();
            }
        } else {
            switch (mode) {
                case OamSearch:
                    if (!phase.tick()) {
                        mode = Mode.PixelTransfer;
                        phase = pixelTransferPhase.start(0, firstLine);
                        // the pixel pipeline of line 0 runs one machine cycle later
                        // relative to the CPU-visible timings than on other lines
                        // (mealybug row-0: the tests' per-line writes land one
                        // machine cycle earlier in the line-0 picture; the STAT
                        // interrupt itself is NOT shifted - intr_1_2_timing-GS)
                        pixelMachine.start(line == 0 ? -4 : 0, firstLine);
                        synchronizePerformanceWindowLineCounter();
                        if (canStartPerformanceScanline()) {
                            armPerformanceScanline();
                        }
                    }
                    break;

                case PixelTransfer:
                    if (performanceScanlineCursor) {
                        if (ticksInLine >= performanceScanlineEndTick) {
                            finishPerformanceScanlineHandoff();
                        }
                    } else if (pixelTransferDone) {
                        pixelTransferDone = false;
                        mode = Mode.HBlank;
                    } else {
                        boolean active;
                        if (timingTickDeferred) {
                            if (ticksInLine < steadyTimingEndTick) {
                                break;
                            }
                            // Include the current dot in one exact, specialized replay. This
                            // restores the canonical Fetcher/FIFO endpoint before the normal
                            // mode-3/HBlank handoff below.
                            materializeSteadyTiming();
                            active = false;
                        } else {
                            int oldPosition = pixelTransferPhase.getPosition();
                            boolean terminalWindowAlreadyStarted =
                                    pixelTransferPhase.hasCgbTerminalWindowStarted();
                            active = phase.tick();
                            if (mode0IntFrom != Integer.MAX_VALUE
                                    && !terminalWindowAlreadyStarted
                                    && pixelTransferPhase.hasCgbTerminalWindowStarted()
                                    && pixelTransferPhase.hasSpriteAtTerminalPredictionEdge()) {
                                // The X=166 M0 event is independent of the later X=167
                                // STAT/bus prediction. When both comparators collide, its
                                // CPU-visible event crosses two dots after Coffee's early
                                // right-edge prediction has been captured. That prediction
                                // always crosses X=158->159 one tick before this terminal
                                // X=159->160 commit, so mode0IntFrom is already finite.
                                mode0IntFrom += 2;
                            }
                            if (oldPosition <= 158
                                    && mode0IntFrom == Integer.MAX_VALUE
                                    && pixelTransferPhase.getPosition() > 158
                                    && pixelTransferPhase.hasSpriteAtMode0PredictionEdge()) {
                                mode0IntFrom = ticksInLine + 3;
                            }
                        }
                        if (active) {
                            break;
                        }
                        // DMG raises the internal HBlank request on the following dot.
                        // CGB exposes it immediately; VRAM DMA relies on that internal
                        // edge for its normal per-line request cadence.
                        if (gbc && !firstLine) {
                            mode = Mode.HBlank;
                        } else {
                            pixelTransferDone = true;
                        }
                        // The DMG's object fetch path has an additional two-dot
                        // output-latch tail before STAT mode 0 rises. BG-only steady
                        // lines use the shorter latch; the LCD-enable line always uses
                        // the full four-dot settling path.
                        hblankIntFrom = ticksInLine
                                + (firstLine || (!gbc && pixelTransferPhase.hasObjectsOnLine())
                                ? 4 : 2);
                        if (mode0IntFrom == Integer.MAX_VALUE) {
                            mode0IntFrom = hblankIntFrom;
                        }
                    }
                    break;

                default:
                    break;
            }
        }

        DebugHooks hooks = debugHooks;
        boolean scanlineStarted = oldLine != line;
        boolean frameReady = scanlineStarted && line == 144;
        if (frameReady) {
            debugPpuFrame = Math.addExact(debugPpuFrame, 1L);
        }
        if (hooks != null && scanlineStarted) {
            hooks.onPpuEvent(
                    PpuTrace.Kind.SCANLINE_STARTED,
                    debugPpuFrame,
                    line,
                    0,
                    toDebugPpuMode(mode));
        }
        if (hooks != null && oldMode != mode) {
            hooks.onPpuEvent(
                    PpuTrace.Kind.MODE_CHANGED,
                    debugPpuFrame,
                    line,
                    ticksInLine,
                    toDebugPpuMode(mode));
        }
        if (hooks != null && frameReady) {
            hooks.onPpuEvent(
                    PpuTrace.Kind.FRAME_READY,
                    debugPpuFrame,
                    line,
                    0,
                    DebugPpuMode.VBLANK);
        }

        // Scalar/deoptimized lines still advance PixelTransfer's hardware window master. Keep
        // the coarse counter monotonic with that machine so a later direct line starts from the
        // same row after a write/DMA/debug fallback. Direct lines deliberately stop both
        // machines after rendering and publish their row to both machines at arm time.
        if (!performanceScanlineCursor) {
            synchronizePerformanceWindowLineCounter();
        }

        if (oldMode == mode) {
            return null;
        } else {
            return mode;
        }
    }

    private boolean canStartPerformanceScanline() {
        return performanceScanlineEnabled
                && performanceScanlineCapable
                && bootCompatibilityResolved
                && !performanceObservationBlocked
                && !mutablePpuStateExposed
                && debugHooks == null
                && (speedModeValue == 1 || gbc && speedModeValue == 2)
                && lcdEnabled
                && !firstLine
                && mode == Mode.PixelTransfer
                && phase == pixelTransferPhase
                && dma != null
                && !dma.isTransferInProgress()
                && !dma.ownsOamForPpu()
                && !dma.hasPpuOamOwnershipTransitionThisTick()
                && pendingPpuWrites.isEmpty()
                && !r.hasPendingConflictLatches()
                && !lcdc.hasPendingConflictLatches();
    }

    private void armPerformanceScanline() {
        performanceScanlineRenderer.setDmgCompat(dmgCompatValue);
        int predictedEnd = performanceScanlineRenderer.predictMode3End(line);
        int lineLength = firstLine ? 455 : 456;
        // The renderer's predictor is an absolute line tick. Keep one dot in reserve for the
        // visible mode handoff and fail closed if an unusual profile/register combination would
        // predict past the line boundary.
        if (predictedEnd <= ticksInLine || predictedEnd >= lineLength) {
            performanceScanlineFallbacks++;
            return;
        }
        performanceScanlineCursor = true;
        performanceScanlineLine = true;
        performanceScanlineEndTick = predictedEnd;
        boolean windowCounterAdvances = performanceWindowCounterAdvancesOnLine();
        int windowLine = windowCounterAdvances ? ++performanceWindowLineCounter : -1;
        if (windowCounterAdvances) {
            // Both PixelTransfer instances are stopped below. Publish the same row to each
            // before stopping them so a scalar fallback on the next line cannot render from an
            // older window master after the direct line has already advanced it.
            pixelTransferPhase.setWindowLineCounterForPerformance(performanceWindowLineCounter);
            pixelMachine.setWindowLineCounterForPerformance(performanceWindowLineCounter);
        }
        if (performanceRenderOutput) {
            performanceScanlineRenderer.renderLinePerformanceBoundary(display, line, windowLine);
        }
        // The ordinary mode-3 starts above have initialized both machines for their state
        // shape. They must not remain active after the direct composition or their delayed
        // output would append a duplicate line during HBlank.
        pixelTransferPhase.finishPerformanceLine();
        pixelMachine.finishPerformanceLine();
    }

    private void finishPerformanceScanlineHandoff() {
        performanceScanlineCursor = false;
        performanceScanlineEndTick = 0;
        performanceScanlineLines++;
        if (gbc && !firstLine) {
            mode = Mode.HBlank;
        } else {
            pixelTransferDone = true;
        }
        // The broad path intentionally uses a conservative mode-0 tail for lines whose exact
        // object/window fetch schedule was not replayed. STAT remains scalar at this boundary.
        int hblankTail = !gbc && (lcdc.isObjDisplay() || lcdc.isWindowDisplay()) ? 4 : 2;
        hblankIntFrom = ticksInLine + hblankTail;
        if (mode0IntFrom == Integer.MAX_VALUE) {
            mode0IntFrom = hblankIntFrom;
        }
    }

    private boolean performanceWindowCounterAdvancesOnLine() {
        int wx = r.get(WX) & 0xff;
        return lcdc.isWindowDisplay()
                && (gbc || lcdc.isBgAndWindowDisplay())
                && line >= (r.get(WY) & 0xff)
                // DMG WX=166 still advances the internal window row at the terminal
                // comparator even though it produces no visible window pixel. CGB accepts
                // the same terminal comparator as a one-pixel HBlank-side start.
                && wx <= 166;
    }

    private void synchronizePerformanceWindowLineCounter() {
        // The counter is host-side PERFORMANCE metadata.  Accuracy/SGB scalar rendering must
        // leave it at its historical sentinel so portable state/replay encodings stay stable;
        // a capable PERFORMANCE session keeps tracking it even while the cursor is temporarily
        // disabled by a fallback or invalidator.
        if (!performanceScanlineCapable) {
            return;
        }
        int scalarCounter = Math.max(pixelTransferPhase.getWindowLineCounter(),
                pixelMachine.getWindowLineCounter());
        if (scalarCounter > performanceWindowLineCounter) {
            performanceWindowLineCounter = scalarCounter;
        }
    }

    private void disablePerformanceScanlineCursor() {
        if (!performanceScanlineCursor) {
            performanceScanlineEndTick = 0;
            return;
        }
        performanceScanlineCursor = false;
        performanceScanlineLine = true;
        performanceScanlineEndTick = 0;
        // Explicit invalidators publish the next scalar handoff so a PPU write, mutable
        // observer, DMA, or debugger attachment cannot retain a half-rendered line. Ordinary
        // capture does not call this method: an already rendered cursor is serialized intact.
        if (mode == Mode.PixelTransfer && !pixelTransferDone) {
            pixelTransferDone = true;
            if (hblankIntFrom == Integer.MAX_VALUE) {
                hblankIntFrom = ticksInLine + 1;
            }
            if (mode0IntFrom == Integer.MAX_VALUE) {
                mode0IntFrom = hblankIntFrom;
            }
        }
    }

    /** Number of following dots that stay inside a direct line-at-a-time mode-3 span. */
    public int performanceScanlineQuietSpanLimit() {
        if (!performanceScanlineCursor
                || ticksInLine + 1 >= performanceScanlineEndTick
                || !pendingPpuWrites.isEmpty()
                || r.hasPendingConflictLatches()
                || lcdc.hasPendingConflictLatches()
                || !lcdc.isPerformanceQuietSpanFixedPoint()
                || dma == null
                || dma.isTransferInProgress()
                || dma.ownsOamForPpu()
                || dma.hasPpuOamOwnershipTransitionThisTick()
                || gbc && (hdma == null || hdma.hasActiveOrPendingTransfer())) {
            return 0;
        }
        return performanceScanlineEndTick - ticksInLine - 1;
    }

    /** Number of following dots available to either PERFORMANCE raster fast path. */
    public int performanceRasterQuietSpanLimit() {
        int scanline = performanceScanlineQuietSpanLimit();
        return scanline > 0 ? scanline : performanceSteadyQuietSpanLimit();
    }

    /**
     * Returns a counter-only PERFORMANCE span in direct mode 3, its HBlank tail, or VBlank.
     * OAM search deliberately remains scalar so the next line's selected sprite list is still
     * produced for the scanline compositor. The returned span never crosses a line boundary.
     */
    public int performanceQuietSpanLimit() {
        int raster = performanceRasterQuietSpanLimit();
        if (raster > 0) {
            return raster;
        }
        if (!performanceScanlineEnabled
                || !performanceScanlineCapable
                || performanceObservationBlocked
                || mutablePpuStateExposed
                || debugHooks != null
                || !pendingPpuWrites.isEmpty()
                || r.hasPendingConflictLatches()
                || lcdc.hasPendingConflictLatches()
                || !lcdc.isPerformanceQuietSpanFixedPoint()
                || mode != Mode.HBlank && mode != Mode.VBlank
                // A scalar/steady PixelTransfer line can still have delayed output pixels in
                // its HBlank tail. Only a line rendered by the direct compositor has proven
                // that both machines were abandoned with an empty output tail.
                || mode == Mode.HBlank && !performanceScanlineLine
                || !gbc && !isPerformanceDmgIdleOutput()
                || gbc && !isPerformanceNativeCgbIdleOutput()
                || !lcdEnabled
                || !bootCompatibilityResolved
                || dma == null
                || dma.isTransferInProgress()
                || dma.ownsOamForPpu()
                || dma.hasPpuOamOwnershipTransitionThisTick()
                || gbc && (hdma == null || hdma.hasActiveOrPendingTransfer())) {
            return 0;
        }
        int lineLength = firstLine ? 455 : 456;
        int limit = Math.max(0, lineLength - ticksInLine - 1);
        if (mode == Mode.VBlank && line == 153 && (!gbc || speedModeValue == 1)) {
            // Scalar tick() samples the frame-window checkpoint on old dot 454 before
            // publishing the line edge. Leave that dot to the exact path.
            limit = Math.min(limit, 454 - ticksInLine);
        }
        return Math.max(0, limit);
    }

    /** Native-CGB coarse epoch horizon; HDMA is unconditionally part of this contract. */
    public int performanceEpochSpanLimit(int requested) {
        if (requested <= 0 || !lcdEnabled || dma == null || dma.isTransferInProgress()
                || dma.ownsOamForPpu() || dma.hasPpuOamOwnershipTransitionThisTick()
                || hdma == null || hdma.hasActiveOrPendingTransfer()) {
            return 0;
        }
        if (!performanceScanlineEnabled || !performanceScanlineCapable
                || performanceObservationBlocked || mutablePpuStateExposed
                || debugHooks != null || !pendingPpuWrites.isEmpty()
                || r.hasPendingConflictLatches() || lcdc.hasPendingConflictLatches()
                || !lcdc.isPerformanceQuietSpanFixedPoint()
                || !bootCompatibilityResolved) {
            return 0;
        }
        int limit;
        if (mode == Mode.PixelTransfer) {
            // Direct rendered mode 3 is the only mode-3 cursor admitted here.  Unlike the
            // older scheduler horizon, this coarse transaction cannot yet advance HDMA's
            // HBlank-request synchronizer, so even an armed transfer remains fail-closed.
            if (!performanceScanlineCursor
                    || ticksInLine + 1 >= performanceScanlineEndTick) {
                return 0;
            }
            limit = performanceScanlineEndTick - ticksInLine - 1;
        } else if (mode == Mode.HBlank || mode == Mode.VBlank) {
            if (mode == Mode.HBlank && !performanceScanlineLine
                    || !isPerformanceNativeCgbIdleOutput()) {
                return 0;
            }
            int lineLength = firstLine ? 455 : 456;
            limit = Math.max(0, lineLength - ticksInLine - 1);
            if (mode == Mode.VBlank && line == 153 && (!gbc || speedModeValue == 1)) {
                limit = Math.min(limit, 454 - ticksInLine);
            }
        } else {
            return 0;
        }
        return Math.min(requested, Math.max(0, limit));
    }

    /**
     * Physical-DMG counterpart of {@link #performanceEpochSpanLimit(int)}. The empty output
     * clocks are part of the proof in HBlank/VBlank; IR, HDMA, mode 2 and scalar mode 3 are not.
     */
    public int performancePhysicalDmgEpochSpanLimit(int requested) {
        if (requested <= 0 || !lcdEnabled || dma == null || dma.isTransferInProgress()
                || dma.ownsOamForPpu() || dma.hasPpuOamOwnershipTransitionThisTick()) {
            return 0;
        }
        if (!performanceScanlineEnabled || !performanceScanlineCapable
                || performanceObservationBlocked || mutablePpuStateExposed
                || debugHooks != null || !pendingPpuWrites.isEmpty()
                || r.hasPendingConflictLatches() || lcdc.hasPendingConflictLatches()
                || !lcdc.isPerformanceQuietSpanFixedPoint()
                || !bootCompatibilityResolved) {
            return 0;
        }
        int limit;
        if (mode == Mode.PixelTransfer) {
            if (!performanceScanlineCursor
                    || ticksInLine + 1 >= performanceScanlineEndTick) {
                return 0;
            }
            limit = performanceScanlineEndTick - ticksInLine - 1;
        } else if (mode == Mode.HBlank || mode == Mode.VBlank) {
            if (mode == Mode.HBlank && !performanceScanlineLine
                    || !isPerformanceDmgIdleOutput()) {
                return 0;
            }
            int lineLength = firstLine ? 455 : 456;
            limit = Math.max(0, lineLength - ticksInLine - 1);
            if (mode == Mode.VBlank && line == 153) {
                limit = Math.min(limit, 454 - ticksInLine);
            }
        } else {
            return 0;
        }
        return Math.min(requested, Math.max(0, limit));
    }

    /**
     * Horizon for exact per-dot OAM-search replay inside a native-CGB coarse CPU epoch.
     * The caller must additionally intersect this with STAT's checkpoint horizon. The last
     * mode-2 dot remains scalar because its OAM-search tick performs the mode-3 hand-off and
     * can arm the direct scanline compositor.
     */
    public int performanceEpochMode2ReplaySpanLimit(int requested) {
        if (requested <= 0 || !gbc || dmgCompatValue || speedModeValue != 2
                || !lcdEnabled || firstLine || line >= 144 || mode != Mode.OamSearch
                || phase != oamSearchPhase || performanceScanlineCursor
                || performanceScanlineLine || dma == null || dma.isTransferInProgress()
                || dma.ownsOamForPpu() || dma.hasPpuOamOwnershipTransitionThisTick()
                || hdma == null || hdma.hasActiveOrPendingTransfer()) {
            return 0;
        }
        if (!performanceScanlineEnabled || !performanceScanlineCapable
                || performanceObservationBlocked || mutablePpuStateExposed
                || debugHooks != null || !pendingPpuWrites.isEmpty()
                || r.hasPendingConflictLatches() || lcdc.hasPendingConflictLatches()
                || !bootCompatibilityResolved) {
            return 0;
        }
        return Math.min(requested, Math.max(0, 79 - ticksInLine));
    }

    /**
     * Horizon for the allocation-free native-CGB OAM-search transaction.
     *
     * <p>The broad mode-2 proof above remains the authoritative DMA/HDMA, write, observation,
     * and dot-79 cap.  This narrower proof additionally requires every otherwise per-dot PPU
     * component to be at a fixed point.  A recent LCDC/window write or a non-canonical restored
     * reader state therefore retains the exact {@link #tick()} replay.</p>
     */
    public int performanceEpochMode2BulkSpanLimit(int requested) {
        int limit = performanceEpochMode2ReplaySpanLimit(requested);
        if (limit > 0 && line == 0 && ticksInLine <= 1) {
            // The scalar mode-2 tick samples the frame-window checkpoint at old dot 1.
            // Keep the exact replay lane through that edge before admitting bulk mode 2.
            return 0;
        }
        if (limit <= 0 || displayEnabledDelay != 0 || steadyTimingCursor
                || !lcdc.isPerformanceMode2FixedPoint()
                || !pixelTransferPhase.isPerformanceNativeCgbMode2IdleOutput()
                || !pixelMachine.isPerformanceNativeCgbMode2IdleOutput()
                || !oamSearchPhase.isPerformanceNoDmaStableSpanEligible(
                ticksInLine, lcdc.getSpriteHeight())) {
            return 0;
        }
        return limit;
    }

    /**
     * Advances a preflighted mode-2 prefix without entering the general GPU or STAT dot loops.
     * The caller retains the scalar dot-80 handoff, where the last sprite is committed and the
     * direct scanline renderer may arm.
     */
    public void advancePerformanceMode2QuietSpanTrusted(int ticks) {
        if (ticks <= 0) {
            return;
        }
        int startTick = ticksInLine;
        if (startTick + ticks > 79) {
            throw new IllegalStateException("GPU is not eligible for a PERFORMANCE mode-2 span");
        }
        assert performanceEpochMode2BulkSpanLimit(ticks) >= ticks
                : "trusted PERFORMANCE mode-2 proof changed before commit";

        lcdc.advancePerformanceMode2FixedPointSpanTrusted(ticks);
        pixelTransferPhase.advancePerformanceNativeCgbMode2IdleOutputSpanTrusted(ticks);
        pixelMachine.advancePerformanceNativeCgbMode2IdleOutputSpanTrusted(ticks);
        oamSearchPhase.advancePerformanceNoDmaStableSpanTrusted(
                startTick, ticks, lcdc.getSpriteHeight());

        ticksInLine += ticks;
        timingGeneration += ticks;
        cpuLyReadAcrossLineEdge = false;
        synchronizePerformanceWindowLineCounter();
    }

    /**
     * Horizon for a short physical-DMG/SGB mode-2 phase packet. Only the non-CPU dots before
     * the next normal-speed machine-cycle boundary use this lane; the dot-79-to-80 handoff
     * remains scalar so the existing OAM/PixelTransfer transition and renderer arm stay
     * authoritative.
     */
    public int performancePhysicalDmgMode2PhaseSpanLimit(int requested) {
        if (!performancePhysicalDmgMode2 || requested <= 0 || gbc || speedModeValue != 1
                || !lcdEnabled || firstLine
                || line >= 144 || mode != Mode.OamSearch || phase != oamSearchPhase
                || performanceScanlineCursor || performanceScanlineLine || dma == null
                || dma.isTransferInProgress() || dma.ownsOamForPpu()
                || dma.hasPpuOamOwnershipTransitionThisTick()) {
            return 0;
        }
        if (!performanceScanlineEnabled || !performanceScanlineCapable
                || performanceObservationBlocked || mutablePpuStateExposed
                || debugHooks != null || !pendingPpuWrites.isEmpty()
                || r.hasPendingConflictLatches() || lcdc.hasPendingConflictLatches()
                || !bootCompatibilityResolved || displayEnabledDelay != 0 || steadyTimingCursor
                || !lcdc.isPerformanceQuietSpanFixedPoint()
                || !pixelTransferPhase.isPerformanceDmgIdleOutput()
                || !pixelMachine.isPerformanceDmgIdleOutput()
                || !oamSearchPhase.isPerformancePhysicalDmgMode2SpanEligible(
                ticksInLine, lcdc.getSpriteHeight())) {
            return 0;
        }
        return Math.min(requested, Math.max(0, 79 - ticksInLine));
    }

    /** Advances a preflighted physical-DMG mode-2 prefix without entering the dot loops. */
    public void advancePerformancePhysicalDmgMode2PhaseSpanTrusted(int ticks) {
        if (ticks <= 0) {
            return;
        }
        int startTick = ticksInLine;
        if (startTick + ticks > 79) {
            throw new IllegalStateException(
                    "GPU is not eligible for a physical-DMG PERFORMANCE mode-2 span");
        }
        assert performancePhysicalDmgMode2PhaseSpanLimit(ticks) >= ticks
                : "trusted physical-DMG mode-2 proof changed before commit";

        lcdc.advancePerformancePhysicalDmgMode2FixedPointSpanTrusted(ticks);
        pixelTransferPhase.advancePerformanceDmgIdleOutputSpanTrusted(ticks);
        pixelMachine.advancePerformanceDmgIdleOutputSpanTrusted(ticks);
        oamSearchPhase.advancePerformancePhysicalDmgMode2SpanTrusted(
                startTick, ticks, lcdc.getSpriteHeight());

        ticksInLine += ticks;
        timingGeneration += ticks;
        cpuLyReadAcrossLineEdge = false;
        directOamReadCorruptionThisTick = false;
        suppressNextDirectOamReadCorruption = false;
        directOamWriteCorruptionThisTick = false;
        suppressNextDirectOamWriteCorruption = false;
        synchronizePerformanceWindowLineCounter();
    }

    /** Advances a preflighted raster span without entering either PixelTransfer machine. */
    public boolean advancePerformanceRasterQuietSpan(int ticks) {
        if (ticks <= 0) {
            return ticks == 0;
        }
        int scanlineLimit = performanceScanlineQuietSpanLimit();
        if (scanlineLimit > 0) {
            if (ticks > scanlineLimit) {
                return false;
            }
            ticksInLine += ticks;
            timingGeneration += ticks;
            performanceScanlineFastTicks += ticks;
            if (!gbc) {
                directOamReadCorruptionThisTick = false;
                suppressNextDirectOamReadCorruption = false;
                directOamWriteCorruptionThisTick = false;
                suppressNextDirectOamWriteCorruption = false;
            }
            return true;
        }
        return advancePerformanceSteadyQuietSpan(ticks);
    }

    /** Advances a preflighted direct/HBlank/VBlank span without touching the dot machines. */
    public boolean advancePerformanceQuietSpan(int ticks) {
        if (ticks <= 0) {
            return ticks == 0;
        }
        int raster = performanceRasterQuietSpanLimit();
        if (raster > 0) {
            return advancePerformanceRasterQuietSpan(ticks);
        }
        int limit = performanceQuietSpanLimit();
        if (limit <= 0 || ticks > limit) {
            return false;
        }
        if (!gbc) {
            advancePerformanceDmgIdleOutputSpanTrusted(ticks);
        } else {
            advancePerformanceNativeCgbIdleOutputSpanTrusted(ticks);
        }
        if (mode == Mode.VBlank && ticksInLine < 79) {
            replayPerformanceOamReaderPrefix(ticks);
        }
        ticksInLine += ticks;
        timingGeneration += ticks;
        performanceScanlineFastTicks += ticks;
        if (!gbc) {
            directOamReadCorruptionThisTick = false;
            suppressNextDirectOamReadCorruption = false;
            directOamWriteCorruptionThisTick = false;
            suppressNextDirectOamWriteCorruption = false;
        }
        return true;
    }

    /**
     * Advances a span after Gameboy has already selected and preflighted its cursor kind.
     *
     * <p>The owner-thread scheduler is the only caller. Peripheral callbacks in the span do
     * not mutate PPU/DMA state, so repeating the full raster eligibility scan here only burns
     * the gain on every three-dot CPU phase. The booleans are captured immediately before the
     * preflight and fail closed if an unexpected lifecycle callback cleared a cursor.</p>
     */
    public void advancePerformanceQuietSpanTrusted(int ticks, boolean directRaster,
                                                    boolean steadyRaster) {
        if (ticks <= 0) {
            return;
        }
        if (directRaster && steadyRaster) {
            throw new IllegalStateException("conflicting PERFORMANCE raster cursor kinds");
        }
        if (directRaster) {
            if (!performanceScanlineCursor) {
                throw new IllegalStateException("direct PERFORMANCE cursor changed in quiet span");
            }
            ticksInLine += ticks;
            timingGeneration += ticks;
            performanceScanlineFastTicks += ticks;
        } else if (steadyRaster) {
            if (!steadyTimingCursor) {
                throw new IllegalStateException("steady PERFORMANCE cursor changed in quiet span");
            }
            steadyTimingTicks += ticks;
            ticksInLine += ticks;
            timingGeneration += ticks;
            performanceSteadyFastTicks += ticks;
        } else {
            if (!gbc) {
                advancePerformanceDmgIdleOutputSpanTrusted(ticks);
            } else {
                advancePerformanceNativeCgbIdleOutputSpanTrusted(ticks);
            }
            if (mode == Mode.VBlank && ticksInLine < 79) {
                replayPerformanceOamReaderPrefix(ticks);
            }
            ticksInLine += ticks;
            timingGeneration += ticks;
            performanceScanlineFastTicks += ticks;
        }
        if (!gbc) {
            directOamReadCorruptionThisTick = false;
            suppressNextDirectOamReadCorruption = false;
            directOamWriteCorruptionThisTick = false;
            suppressNextDirectOamWriteCorruption = false;
        }
    }

    /** Native-CGB trusted epoch commit with no physical-DMG output/OAM branches. */
    public void advancePerformanceEpochQuietSpanTrusted(
            int ticks, boolean directRaster, boolean steadyRaster) {
        if (ticks <= 0) {
            return;
        }
        if (directRaster && steadyRaster) {
            throw new IllegalStateException("conflicting PERFORMANCE raster cursor kinds");
        }
        if (directRaster) {
            if (!performanceScanlineCursor) {
                throw new IllegalStateException("direct PERFORMANCE cursor changed in epoch");
            }
            ticksInLine += ticks;
            timingGeneration += ticks;
            performanceScanlineFastTicks += ticks;
        } else if (steadyRaster) {
            if (!steadyTimingCursor) {
                throw new IllegalStateException("steady PERFORMANCE cursor changed in epoch");
            }
            steadyTimingTicks += ticks;
            ticksInLine += ticks;
            timingGeneration += ticks;
            performanceSteadyFastTicks += ticks;
        } else {
            advancePerformanceNativeCgbIdleOutputSpanTrusted(ticks);
            if (mode == Mode.VBlank && ticksInLine < 79) {
                replayPerformanceOamReaderPrefix(ticks);
            }
            ticksInLine += ticks;
            timingGeneration += ticks;
            performanceScanlineFastTicks += ticks;
        }
    }

    /** Physical-DMG trusted epoch commit with canonical empty output-clock advancement. */
    public void advancePhysicalDmgPerformanceEpochQuietSpanTrusted(
            int ticks, boolean directRaster, boolean steadyRaster) {
        if (ticks <= 0) {
            return;
        }
        if (directRaster && steadyRaster) {
            throw new IllegalStateException("conflicting physical-DMG raster cursor kinds");
        }
        if (directRaster) {
            if (!performanceScanlineCursor) {
                throw new IllegalStateException("direct physical-DMG cursor changed in epoch");
            }
            ticksInLine += ticks;
            timingGeneration += ticks;
            performanceScanlineFastTicks += ticks;
        } else if (steadyRaster) {
            if (!steadyTimingCursor) {
                throw new IllegalStateException("steady physical-DMG cursor changed in epoch");
            }
            steadyTimingTicks += ticks;
            ticksInLine += ticks;
            timingGeneration += ticks;
            performanceSteadyFastTicks += ticks;
        } else {
            advancePerformanceDmgIdleOutputSpanTrusted(ticks);
            if (mode == Mode.VBlank && ticksInLine < 79) {
                replayPerformanceOamReaderPrefix(ticks);
            }
            ticksInLine += ticks;
            timingGeneration += ticks;
            performanceScanlineFastTicks += ticks;
        }
        directOamReadCorruptionThisTick = false;
        suppressNextDirectOamReadCorruption = false;
        directOamWriteCorruptionThisTick = false;
        suppressNextDirectOamWriteCorruption = false;
    }

    /** Whether the broad line-at-a-time cursor is active. */
    public boolean isPerformanceScanlineCursorActive() {
        return performanceScanlineCursor;
    }

    private boolean isPerformanceDmgIdleOutput() {
        return pixelTransferPhase.isPerformanceDmgIdleOutput()
                && pixelMachine.isPerformanceDmgIdleOutput();
    }

    private boolean isPerformanceNativeCgbIdleOutput() {
        return gbc
                && pixelTransferPhase.isPerformanceNativeCgbMode2IdleOutput()
                && pixelMachine.isPerformanceNativeCgbMode2IdleOutput();
    }

    /** Replays the persistent OAM reader's early-line prefix before a trusted span advances. */
    private void replayPerformanceOamReaderPrefix(int ticks) {
        if (ticks <= 0 || mode != Mode.VBlank || ticksInLine >= 79) {
            return;
        }
        int end = Math.min(80, ticksInLine + ticks + 1);
        for (int position = ticksInLine + 1; position < end; position++) {
            oamSearchPhase.trackDmaSource(position);
        }
    }

    private void advancePerformanceDmgIdleOutputSpanTrusted(int ticks) {
        pixelTransferPhase.advancePerformanceDmgIdleOutputSpanTrusted(ticks);
        pixelMachine.advancePerformanceDmgIdleOutputSpanTrusted(ticks);
    }

    private void advancePerformanceNativeCgbIdleOutputSpanTrusted(int ticks) {
        pixelTransferPhase.advancePerformanceNativeCgbMode2IdleOutputSpanTrusted(ticks);
        pixelMachine.advancePerformanceNativeCgbMode2IdleOutputSpanTrusted(ticks);
    }

    /**
     * Advances one dot of an already armed, sprite/window-free PERFORMANCE span.
     *
     * <p>The regular {@link #tick()} method has to revisit pending writes, conflict latches,
     * window checkpoints, the shifted output machine, OAM ownership and the general phase
     * state on every dot.  Once {@link #canStartSteadyTiming()} has proven that none of those
     * inputs can change, the background span methods can advance both pixel machines and the
     * raster counters directly.  Any invalidation falls back to the scalar method after
     * materializing the deferred prefix.</p>
     */
    public Mode tickPerformanceSteady() {
        if (!steadyTimingCursor || !steadyTimingStillEligible()
                || performanceObservationBlocked || debugHooks != null
                || !pendingPpuWrites.isEmpty()
                || r.hasPendingConflictLatches() || lcdc.hasPendingConflictLatches()) {
            performanceSteadyFastFallbacks++;
            return tick();
        }

        performanceSteadyFastTicks++;
        timingGeneration++;
        cpuLyReadAcrossLineEdge = false;
        if (!gbc) {
            directOamReadCorruptionThisTick = false;
            suppressNextDirectOamReadCorruption = false;
            directOamWriteCorruptionThisTick = false;
            suppressNextDirectOamWriteCorruption = false;
        }

        // The cursor is only armed in an enabled normal-speed mode-3 span. A CPU write to
        // LCDC/SCX/WX/BGP materializes it synchronously before reaching this method, and DMA
        // generation changes are checked above, so the per-dot conflict/window work is empty.
        steadyTimingTicks++;
        ticksInLine++;
        if (ticksInLine < steadyTimingEndTick) {
            return null;
        }

        // Include the boundary's preceding dots in the exact replay before publishing the
        // mode-3 endpoint. The scalar path uses the same hblank/mode-0 latch values below.
        int ticks = steadyTimingTicks;
        boolean output = steadyOutputCursor;
        steadyTimingCursor = false;
        steadyTimingTicks = 0;
        steadyTimingEndTick = 0;
        steadyOutputCursor = false;
        steadyTimingDmaGeneration = 0;
        steadyTimingHdmaGeneration = 0;
        if (ticks > 0) {
            pixelTransferPhase.advanceSteadyBackgroundSpan(ticks);
            if (output) {
                pixelMachine.advanceSteadyBackgroundOutputSpan(ticks);
            }
        }

        Mode oldMode = mode;
        if (gbc && !firstLine) {
            mode = Mode.HBlank;
        } else {
            pixelTransferDone = true;
        }
        hblankIntFrom = ticksInLine
                + (firstLine || (!gbc && pixelTransferPhase.hasObjectsOnLine()) ? 4 : 2);
        if (mode0IntFrom == Integer.MAX_VALUE) {
            mode0IntFrom = hblankIntFrom;
        }
        return oldMode == mode ? null : mode;
    }

    /** Whether the next dot can use the branch-free steady raster update. */
    public boolean isPerformanceSteadyTickQuiet() {
        return steadyTimingCursor && steadyTimingStillEligible()
                && ticksInLine + 1 < steadyTimingEndTick
                && !performanceObservationBlocked
                && debugHooks == null
                && pendingPpuWrites.isEmpty()
                && !r.hasPendingConflictLatches()
                && !lcdc.hasPendingConflictLatches()
                && lcdc.isPerformanceQuietSpanFixedPoint();
    }

    /** Number of following dots that remain strictly inside the deferred steady span. */
    public int performanceSteadyQuietSpanLimit() {
        if (!isPerformanceSteadyTickQuiet()) {
            return 0;
        }
        return steadyTimingEndTick - ticksInLine - 1;
    }

    /**
     * Advances several invariant background dots in one raster call. The span excludes the
     * predicted handoff dot; the caller returns to the scalar scheduler for that boundary so
     * STAT/host frame events retain their ordinary publication ordering. Pixel machines are
     * deliberately not advanced here: the cursor is a deferred replay, and
     * {@link #tickPerformanceSteady()} materializes the accumulated span exactly once at the
     * handoff. Advancing them here as well would replay the same dots a second time.
     */
    public boolean advancePerformanceSteadyQuietSpan(int ticks) {
        if (ticks < 0 || ticks > performanceSteadyQuietSpanLimit()) {
            return false;
        }
        if (ticks == 0) {
            return true;
        }
        steadyTimingTicks += ticks;
        ticksInLine += ticks;
        timingGeneration += ticks;
        performanceSteadyFastTicks += ticks;
        if (!gbc) {
            directOamReadCorruptionThisTick = false;
            suppressNextDirectOamReadCorruption = false;
            directOamWriteCorruptionThisTick = false;
            suppressNextDirectOamWriteCorruption = false;
        }
        return true;
    }

    /** Whether the speculative background cursor is currently holding a deferred span. */
    public boolean isPerformanceSteadyCursorActive() {
        return steadyTimingCursor;
    }

    /** Number of dots advanced by the PERFORMANCE raster fast path. */
    public long getPerformanceSteadyFastTicks() {
        return performanceSteadyFastTicks;
    }

    /** Number of speculative raster dots that deoptimized to the scalar path. */
    public long getPerformanceSteadyFastFallbacks() {
        return performanceSteadyFastFallbacks;
    }

    /** Rephases the CGB CPU-readable PPU latches when the CPU clock mux changes. */
    public void onSpeedSwitch() {
        disablePerformanceScanlineCursor();
        materializeSteadyTiming();
        timingGeneration++;
        prepareForTick();
        statModeLatchRephasedBySpeedSwitch = true;
        lyReadLatchRephasedBySpeedSwitch = lcdEnabled;
    }

    /** Refreshes cached PPU timing mode after an owner-thread source change. */
    public void prepareForTick() {
        // A restored direct cursor is still valid when SpeedMode merely replays the same
        // serialized clock mode and invokes its listener. Only an actual speed/compatibility
        // change invalidates the line-start snapshot; preserving the cursor keeps restore
        // observationally equivalent to an uninterrupted PERFORMANCE run.
        if (performanceScanlineCursor && timingModeDirty
                && (speedModeValue != speedMode.getSpeedMode()
                || dmgCompatValue != speedMode.isDmgCompat())) {
            disablePerformanceScanlineCursor();
        }
        materializeSteadyTiming();
        if (timingModeDirty) {
            int newSpeedModeValue = speedMode.getSpeedMode();
            boolean newDmgCompatValue = speedMode.isDmgCompat();
            speedModeValue = newSpeedModeValue;
            dmgCompatValue = newDmgCompatValue;
            performanceScanlineRenderer.setDmgCompat(newDmgCompatValue);
            pixelTransferPhase.prepareForTick(newSpeedModeValue, newDmgCompatValue);
            pixelMachine.prepareForTick(newSpeedModeValue, newDmgCompatValue);
            timingModeDirty = false;
        }
    }

    /** Retains the old CPU-readable STAT phase until the current scanline ends. */
    public void onSpeedSwitchComplete() {
        disablePerformanceScanlineCursor();
        materializeSteadyTiming();
        timingGeneration++;
        speedSwitchCompletedThisLine = true;
    }

    /** Captures the CPU/PPU phase selected when a double-speed mode-2 IRQ is accepted. */
    public void onDoubleSpeedMode2Dispatch() {
        disablePerformanceScanlineCursor();
        materializeSteadyTiming();
        timingGeneration++;
        doubleSpeedMode2DispatchStatTailThisLine = true;
    }

    long getTimingGeneration() {
        return timingGeneration;
    }

    private boolean deferSteadyTimingDot() {
        if (steadyTimingCursor) {
            if (!steadyTimingStillEligible()) {
                materializeSteadyTiming();
                return false;
            }
            steadyTimingTicks++;
            return true;
        }
        // Most dots cannot arm a steady span. Keep the full predicate off this hot path;
        // only the exact fetch-start boundary can begin one.
        if (!performanceSteadyTiming || mode != Mode.PixelTransfer || ticksInLine != 80) {
            return false;
        }
        if (!canStartSteadyTiming()) {
            return false;
        }
        steadyTimingCursor = true;
        steadyTimingTicks = 1;
        steadyTimingEndTick = 248 + (r.get(SCX) & 7);
        steadyTimingDmaGeneration = dma == null ? 0 : dma.getPpuBusGeneration();
        steadyTimingHdmaGeneration = hdma == null ? 0 : hdma.getPpuBusGeneration();
        // The ordinary CGB compatibility row is independently gated by canStartSteadyTiming()
        // (CGB profile, normal speed, resolved boot handoff, and no mutable/observable state).
        // Its shifted ColorPixelFifo owns the DMG BGP/OBP remap and CGB palette lookup, so it
        // can share the same output span as native color. CGB0 compatibility remains scalar
        // because its timing cursor is not eligible in the first place.
        steadyOutputCursor = performanceSteadyOutput;
        return true;
    }

    private boolean canStartSteadyTiming() {
        return performanceSteadyTiming
                && !mutablePpuStateExposed
                && bootCompatibilityResolved
                && (!dmgCompatValue || performanceDmgCompatTiming)
                && speedModeValue == 1
                && !performanceObservationBlocked
                && debugHooks == null
                && dma != null
                && !dma.isTransferInProgress()
                && !dma.ownsOamForPpu()
                && !dma.hasPpuOamOwnershipTransitionThisTick()
                && (!gbc || (hdma != null && !hdma.hasActiveOrPendingTransfer()))
                && mode == Mode.PixelTransfer
                && phase == pixelTransferPhase
                && line > 0
                && !firstLine
                && ticksInLine == 80
                && !pixelTransferDone
                && pendingPpuWrites.isEmpty()
                && lastCpuVramWriteTick == Integer.MIN_VALUE
                && !scxWrittenThisLine
                && !wyWrittenThisLine
                && !r.hasPendingConflictLatches()
                && !lcdc.hasPendingConflictLatches()
                && !lcdc.isTileSelectGlitch()
                && !r.isWxJustChanged()
                && !lcdc.isWindowDisplay()
                && !pixelTransferPhase.isWindowActive()
                && !pixelTransferPhase.isWindowBeingFetched()
                && !pixelTransferPhase.isWindowYTriggered()
                && !pixelTransferPhase.isObjectFetchInProgress()
                && !pixelTransferPhase.hasObjectsOnLine()
                && pixelTransferPhase.usesScalarTimingFifo()
                && !pixelTransferPhase.hasDelayedWindowDisplayWrite()
                && !pixelTransferPhase.hasDelayedWindowXWrite()
                && pixelTransferPhase.getPosition() == -16
                && !pixelMachine.isWindowDisplayVisible()
                && !pixelMachine.isWindowActive()
                && !pixelMachine.isWindowBeingFetched()
                && !pixelMachine.isWindowYTriggered()
                && !pixelMachine.isObjectFetchInProgress()
                && !pixelMachine.hasObjectsOnLine()
                && !pixelMachine.hasDelayedWindowDisplayWrite()
                && !pixelMachine.hasDelayedWindowXWrite()
                && pixelMachine.getPosition() == -16;
    }

    private boolean steadyTimingStillEligible() {
        // All emulator-owned invalidators materialize synchronously. The two DMA engines are
        // the only owners that can change bus state without entering Gpu, so compare their
        // cheap transient generations after paying the full predicates at arm time.
        return (dma == null || dma.getPpuBusGeneration() == steadyTimingDmaGeneration)
                && (hdma == null || hdma.getPpuBusGeneration() == steadyTimingHdmaGeneration);
    }

    /** Materializes the transient cursor before any observer, write, or state boundary. */
    private void materializeSteadyTiming() {
        if (!steadyTimingCursor) {
            return;
        }
        int ticks = steadyTimingTicks;
        boolean output = steadyOutputCursor;
        steadyTimingCursor = false;
        steadyTimingTicks = 0;
        steadyTimingEndTick = 0;
        steadyOutputCursor = false;
        steadyTimingDmaGeneration = 0;
        steadyTimingHdmaGeneration = 0;
        if (ticks > 0) {
            pixelTransferPhase.advanceSteadyBackgroundSpan(ticks);
            if (output) {
                pixelMachine.advanceSteadyBackgroundOutputSpan(ticks);
            }
        }
    }

    private void exposeMutablePpuState() {
        disablePerformanceScanlineCursor();
        materializeSteadyTiming();
        mutablePpuStateExposed = true;
    }

    public boolean isStatModeLatchRephasedBySpeedSwitch() {
        return statModeLatchRephasedBySpeedSwitch;
    }

    private boolean shouldDelayPpuWrite(int address, int value) {
        if (address == LCDC_ADDRESS) {
            // Native CGB synchronizes LCDC.5 before either PPU machine sees it. DMG's
            // timing skeleton sees the CPU edge immediately; only the shifted pixel
            // machine uses the separate four-dot hold scheduled above.
            return gbc && lcdEnabled && (value & 0x80) != 0
                    && ((lcdc.get() ^ value) & 0x20) != 0;
        }
        if (gbc || !lcdEnabled || line == 0 || mode != Mode.PixelTransfer) {
            return false;
        }
        if (address == SCX.getAddress()) {
            // Coarse tile selection sees SCX directly; only the fine-scroll counter is
            // synchronized into the shifted pixel domain.
            return ((r.get(SCX) ^ value) & 0x07) != 0;
        }
        if (address == BGP.getAddress()) {
            // The object/window pixel paths cross the delayed LCD output pipeline. An
            // actual object fetch contributes that skew itself; otherwise their enabled
            // path needs the palette latch. With both paths disabled, BGP feeds the pure
            // background scanner directly (Daid's scanline palette capture).
            return (lcdc.isObjDisplay() || pixelMachine.isWindowDisplayVisible())
                    && !pixelTransferPhase.hasObjectsOnLine();
        }
        return false;
    }

    private void scheduleDmgPixelWindowWrite(int address, int value) {
        if (gbc) {
            return;
        }
        if (address == LCDC_ADDRESS) {
            if (lcdEnabled && line != 0 && (value & 0x80) != 0
                    && ((lcdc.get() ^ value) & 0x20) != 0) {
                pixelMachine.scheduleWindowDisplayWrite(
                        (value & 0x20) != 0, PPU_WRITE_DELAY_DOTS);
            } else if ((value & 0x80) == 0 || line == 0) {
                pixelMachine.cancelDelayedWindowDisplayWrite();
            }
        } else if (address == WX.getAddress()) {
            if (lcdEnabled && line != 0 && mode == Mode.PixelTransfer) {
                pixelMachine.scheduleWindowXWrite(value, PPU_WRITE_DELAY_DOTS);
            } else {
                pixelMachine.cancelDelayedWindowXWrite();
            }
        }
    }

    private void cancelDelayedPixelWindowWrite(int address) {
        if (address == LCDC_ADDRESS) {
            pixelMachine.cancelDelayedWindowDisplayWrite();
        } else if (address == WX.getAddress()) {
            pixelMachine.cancelDelayedWindowXWrite();
        }
    }

    private int getPpuWriteDelayDots(int address) {
        if (gbc && address == LCDC_ADDRESS) {
            // Pending writes are advanced before the PPU edge of the CPU write tick,
            // so a remaining count of N reaches the PPU N+1 dots later.
            return 2 / speedModeValue - 1;
        }
        return PPU_WRITE_DELAY_DOTS;
    }

    private static int getDelayedPpuWriteMask(int address) {
        if (address == LCDC_ADDRESS) {
            return 0x20;
        }
        if (address == SCX.getAddress()) {
            return 0x07;
        }
        return 0xff;
    }

    private int getCurrentPpuWriteValue(int address) {
        if (address == LCDC_ADDRESS) {
            return lcdc.get();
        }
        if (address == SCX.getAddress()) {
            return r.get(SCX);
        }
        if (address == BGP.getAddress()) {
            return r.get(BGP);
        }
        if (address == WX.getAddress()) {
            return r.get(WX);
        }
        throw new IllegalArgumentException("Unsupported delayed PPU register: "
                + Integer.toHexString(address));
    }

    private void advancePendingPpuWrites() {
        for (int i = 0; i < pendingPpuWrites.size(); ) {
            PendingPpuWriteRuntime pending = pendingPpuWrites.get(i);
            if (pending.remainingDots() == 0) {
                pendingPpuWrites.remove(i);
                int current = getCurrentPpuWriteValue(pending.address());
                setByteImmediately(pending.address(),
                        (pending.value() & pending.mask()) | (current & ~pending.mask()));
                if (pendingPpuWrites.stream()
                        .noneMatch(p -> p.address() == pending.address())) {
                    cpuVisiblePpuRegisters[pending.address() - LCDC_ADDRESS] = -1;
                }
            } else {
                pendingPpuWrites.set(i, new PendingPpuWriteRuntime(
                        pending.address(), pending.value(), pending.mask(),
                        pending.remainingDots() - 1));
                i++;
            }
        }
    }

    private void cancelPendingPpuWrites(int address) {
        if (address < LCDC_ADDRESS || address > LAST_STANDARD_REGISTER_ADDRESS) {
            return;
        }
        pendingPpuWrites.removeIf(pending -> pending.address() == address);
        cpuVisiblePpuRegisters[address - LCDC_ADDRESS] = -1;
    }

    private void clearPendingPpuWrites() {
        pendingPpuWrites.clear();
        Arrays.fill(cpuVisiblePpuRegisters, -1);
    }

    public int getTicksInLine() {
        return ticksInLine;
    }

    void captureStatTiming(GpuTimingSnapshot target) {
        materializeSteadyTiming();
        captureStatTimingForTick(target);
    }

    /** Scheduler-only STAT snapshot; the owner has already advanced this GPU dot. */
    void captureStatTimingForTick(GpuTimingSnapshot target) {
        target.line = line;
        target.ticksInLine = ticksInLine;
        target.lcdEnabled = lcdEnabled;
        target.firstLine = firstLine;
        target.dmgCompat = dmgCompatValue;
        target.statModeLatchRephasedBySpeedSwitch = statModeLatchRephasedBySpeedSwitch;
        target.earlyLineEdgeTick = firstLine ? 451 : gbc ? 448 : 452;
        target.cpuMachineCycleDots = 4 / speedModeValue;
        target.doubleSpeed = target.cpuMachineCycleDots == 2;
        target.nativeDoubleSpeed = gbc && !target.dmgCompat && target.doubleSpeed;

        int visibleLyLineEdgeTick;
        if (!gbc || firstLine) {
            visibleLyLineEdgeTick = target.earlyLineEdgeTick;
        } else if (target.dmgCompat) {
            visibleLyLineEdgeTick = 450;
        } else {
            visibleLyLineEdgeTick = target.doubleSpeed ? 454 : 452;
        }
        if (!lcdEnabled) {
            target.visibleLy = 0;
        } else if (line == 153) {
            if (gbc) {
                target.visibleLy = ticksInLine < (target.doubleSpeed ? 2 : 4) ? 153 : 0;
            } else {
                target.visibleLy = 0;
            }
        } else {
            target.visibleLy = ticksInLine >= visibleLyLineEdgeTick ? line + 1 : line;
        }

        target.mode0InterruptTick = gbc
                || (!lcdc.isWindowDisplay() && pixelTransferPhase.hasSpriteAtMode0PredictionEdge())
                ? mode0IntFrom : hblankIntFrom;
        target.mode0HaltWakeTick = lcdEnabled && line < 144
                && ticksInLine == target.mode0InterruptTick + 2;
        target.mode0IntWindow = lcdEnabled && line < 144
                && ticksInLine >= target.mode0InterruptTick;
        target.mode1IntWindow = lcdEnabled
                && (mode == Mode.VBlank || gbc && line == 143 && ticksInLine >= 448);
        target.mode2IntWindow = lcdEnabled
                && ((line < 144 && ticksInLine >= target.earlyLineEdgeTick)
                || (gbc && !target.dmgCompat && line == 153 && ticksInLine >= 454)
                || ((!gbc || target.dmgCompat) && !firstLine && line == 0
                && ticksInLine < 4));
    }

    /**
     * Captures the post-GPU STAT facts for the native-CGB PERFORMANCE scalar owner.
     *
     * <p>This is intentionally the same formula used by the ordinary scheduler snapshot. The
     * native owner calls it after the PPU dot has committed, so StatRegister can avoid the
     * timing-generation probe and the generic cross-object capture dispatch while retaining
     * exactly the same values at every raster position (including LCD-off and first-line
     * states).</p>
     */
    long captureNativeCgbPerformancePostStatTiming(GpuTimingSnapshot target) {
        int currentLine = line;
        int currentTicksInLine = ticksInLine;
        boolean currentLcdEnabled = lcdEnabled;
        boolean currentFirstLine = firstLine;
        int earlyLineEdgeTick = currentFirstLine ? 451 : 448;
        boolean visibleVblankTail = currentLine == 153;
        boolean visibleLine = currentLine < 144;

        target.line = currentLine;
        target.ticksInLine = currentTicksInLine;
        target.lcdEnabled = currentLcdEnabled;
        target.firstLine = currentFirstLine;
        target.statModeLatchRephasedBySpeedSwitch = statModeLatchRephasedBySpeedSwitch;
        target.earlyLineEdgeTick = earlyLineEdgeTick;
        target.setNativeCgbDoubleSpeed();

        int visibleLyLineEdgeTick = currentFirstLine ? 451 : 454;
        if (!currentLcdEnabled) {
            target.visibleLy = 0;
        } else if (visibleVblankTail) {
            target.visibleLy = currentTicksInLine < 2 ? 153 : 0;
        } else {
            target.visibleLy = currentTicksInLine >= visibleLyLineEdgeTick
                    ? currentLine + 1 : currentLine;
        }

        target.mode0InterruptTick = mode0IntFrom;
        target.mode0HaltWakeTick = currentLcdEnabled && visibleLine
                && currentTicksInLine == target.mode0InterruptTick + 2;
        target.mode0IntWindow = currentLcdEnabled && visibleLine
                && currentTicksInLine >= target.mode0InterruptTick;
        target.mode1IntWindow = currentLcdEnabled
                && (mode == Mode.VBlank || currentLine == 143 && currentTicksInLine >= 448);
        target.mode2IntWindow = currentLcdEnabled
                && ((visibleLine && currentTicksInLine >= earlyLineEdgeTick)
                || (visibleVblankTail && currentTicksInLine >= 454));
        return timingGeneration;
    }

    /**
     * Packs the native post-GPU facts directly from the committed PPU state. The VALID bit
     * fails closed if a CPU instruction changed speed/compatibility during this scalar dot.
     */
    int getNativeCgbPerformancePostStatFacts() {
        if (!gbc || dmgCompatValue || speedModeValue != 2) {
            return 0;
        }
        int mode0InterruptTick = mode0IntFrom;
        int facts = NATIVE_CGB_POST_STAT_FACTS_VALID;
        boolean checkpoint = lcdEnabled
                && (ticksInLine < 13 || ticksInLine >= 448
                || ticksInLine == mode0InterruptTick
                || (line < 144 && mode0InterruptTick != Integer.MAX_VALUE
                && ticksInLine == mode0InterruptTick + 2));
        if (checkpoint) {
            facts |= NATIVE_CGB_POST_STAT_CHECKPOINT;
        }
        return facts;
    }

    /**
     * Returns the allocation-free native-CGB CPU/STAT phase facts needed before this dot's CPU
     * bus callback. Native PERFORMANCE scalar misses are already topology-checked by Gameboy;
     * keeping this word local to the GPU avoids taking the general timing snapshot on that path.
     */
    public int getNativeCgbPerformancePhaseWord() {
        // Native double-speed scalar CPU callbacks never take the normal-speed line-edge
        // handoff. Capture the same settled value as captureCpuLyReadPhase() would.
        cpuLyReadAcrossLineEdge = false;
        int phaseWord = line & NATIVE_CGB_PHASE_LINE_MASK;
        if (line < 144 && ticksInLine + 1 == mode0IntFrom) {
            phaseWord |= NATIVE_CGB_PHASE_MODE0_EDGE_NEXT;
        }
        if (line < 144 && ticksInLine + 2 == mode0IntFrom) {
            phaseWord |= NATIVE_CGB_PHASE_MODE0_READ_PREVIEW;
        }
        return phaseWord;
    }

    /**
     * Returns whether the current PPU dot can change a STAT source or one of
     * its closely coupled latches. This is intentionally a live, allocation-
     * free counterpart of the checkpoint test historically applied to
     * {@link GpuTimingSnapshot} in {@link StatRegister}.
     */
    boolean isStatEventCheckpoint() {
        materializeSteadyTiming();
        return isStatEventCheckpointForTick();
    }

    /** Scheduler-only checkpoint query that preserves the deferred timing span. */
    boolean isStatEventCheckpointForTick() {
        if (!lcdEnabled) {
            return false;
        }
        int mode0InterruptTick = getMode0InterruptTickForTick();
        return ticksInLine < 13
                || ticksInLine >= 448
                || ticksInLine == mode0InterruptTick
                || (line < 144 && mode0InterruptTick != Integer.MAX_VALUE
                && ticksInLine == mode0InterruptTick + 2);
    }

    /**
     * Scheduler-only lookahead for a short quiet span. The one-dot checkpoint query is useful
     * at a scalar boundary, but a three-dot CPU phase must not jump over a future mode/STAT
     * edge (notably dot 448, the mode-0 edge, or its +2 HALT synchronizer). Keep this deliberately
     * conservative: callers already cap spans before a line edge, and rejecting the few dots
     * around every possible checkpoint is much cheaper than replaying a mutated STAT latch.
     */
    boolean isStatEventCheckpointWithin(int ticks) {
        if (ticks <= 0 || !lcdEnabled) {
            return false;
        }
        int lineLength = firstLine ? 455 : 456;
        int mode0InterruptTick = getMode0InterruptTickForTick();
        for (int offset = 1; offset <= ticks; offset++) {
            int candidate = ticksInLine + offset;
            if (candidate >= lineLength
                    || candidate < 13
                    || candidate >= 448
                    || candidate == mode0InterruptTick
                    || (line < 144 && mode0InterruptTick != Integer.MAX_VALUE
                    && candidate == mode0InterruptTick + 2)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Distance in dots to the next future STAT checkpoint. The STAT caller rejects a checkpoint
     * on the current dot before consulting this method. This is the scalar counterpart used by
     * the settled-HALT horizon and intentionally avoids walking every candidate offset.
     */
    int performanceStatCheckpointDistance() {
        if (!lcdEnabled) {
            return Integer.MAX_VALUE;
        }
        int lineLength = firstLine ? 455 : 456;
        int distance = Integer.MAX_VALUE;
        if (ticksInLine < 13) {
            distance = Math.min(distance, 13 - ticksInLine);
        }
        if (ticksInLine < 448) {
            distance = Math.min(distance, 448 - ticksInLine);
        } else {
            distance = 1;
        }
        if (ticksInLine < lineLength) {
            distance = Math.min(distance, lineLength - ticksInLine);
        } else {
            distance = 1;
        }
        int mode0InterruptTick = getMode0InterruptTickForTick();
        if (mode0InterruptTick != Integer.MAX_VALUE) {
            if (ticksInLine < mode0InterruptTick) {
                distance = Math.min(distance, mode0InterruptTick - ticksInLine);
            }
            int mode0WakeTick = mode0InterruptTick + 2;
            if (ticksInLine < mode0WakeTick) {
                distance = Math.min(distance, mode0WakeTick - ticksInLine);
            }
        }
        return distance;
    }

    /**
     * Applies the DMG OAM corruption bug if the PPU is currently scanning the OAM.
     */
    public void corruptOam(SpriteBug.CorruptionType type) {
        if (gbc || !lcdEnabled) {
            return;
        }
        if (type == SpriteBug.CorruptionType.POP_1
                || type == SpriteBug.CorruptionType.POP_2) {
            if (directOamReadCorruptionThisTick) {
                directOamReadCorruptionThisTick = false;
                return;
            }
            suppressNextDirectOamReadCorruption = true;
        }
        if (type == SpriteBug.CorruptionType.PUSH_1
                || type == SpriteBug.CorruptionType.PUSH_2) {
            if (directOamWriteCorruptionThisTick) {
                directOamWriteCorruptionThisTick = false;
                return;
            }
            suppressNextDirectOamWriteCorruption = true;
        }
        if (type == SpriteBug.CorruptionType.LD_HL
                && (directOamReadCorruptionThisTick || directOamWriteCorruptionThisTick)) {
            directOamReadCorruptionThisTick = false;
            directOamWriteCorruptionThisTick = false;
            return;
        }
        // The OAM scan accesses rows 1..19, starting 4 ticks before the end of the
        // preceding line and finishing at tick 72 (blargg oam_bug 4-scanline_timing,
        // 5-timing_bug, 6-timing_no_bug). The INC/DEC bug check runs one machine cycle
        // before the actual bus event, while the pop/push/ldi/ldd checks run on their
        // memory cycle, so their tick is shifted back accordingly (8-instr_effect).
        int t = type == SpriteBug.CorruptionType.INC_DEC ? ticksInLine : ticksInLine - 4;
        if (t >= (firstLine ? 451 : 452) && (line < 143 || line == 153)) {
            SpriteBug.corruptOam(oamRam, type, 1);
        } else if (mode == Mode.OamSearch && t >= -4 && t < 72) {
            int row = t < 0 ? 1 : t / 4 + 2;
            SpriteBug.corruptOam(oamRam, type, row);
        }
    }

    private int getDirectOamReadRow() {
        if (!lcdEnabled || firstLine) {
            return -1;
        }
        if (ticksInLine >= getEarlyLineEdgeTick()
                && (line < 143 || line == 153)) {
            return 0;
        }
        if (mode != Mode.OamSearch) {
            return -1;
        }
        int scanTick = ticksInLine - 4;
        if (scanTick >= -4 && scanTick < 72) {
            return scanTick < 0 ? 1 : scanTick / 4 + 2;
        }
        return scanTick < 76 ? 20 : -1;
    }

    private int getDirectOamWriteRow() {
        if (!lcdEnabled || firstLine || mode != Mode.OamSearch
                || isOamAvailableForCpu(true)) {
            return -1;
        }
        int scanTick = ticksInLine - 4;
        if (scanTick < -4 || scanTick >= 72) {
            return -1;
        }
        return scanTick < 0 ? 1 : scanTick / 4 + 2;
    }

    public int getLine() {
        return line;
    }

    public boolean isFirstLine() {
        return firstLine;
    }

    /**
     * LY value as visible to the CPU. Native CGB mode uses the steady-state DMG line
     * edge, while CGB DMG-compatibility mode has its own intermediate latch timing.
     * CGB retains 153 briefly after line 153 starts: four dots at normal speed
     * (native or compatibility mode), and two dots at double speed.
     */
    public int getVisibleLy() {
        materializeSteadyTiming();
        if (!lcdEnabled) {
            return 0;
        }
        if (line == 153) {
            if (gbc) {
                int lastLyTicks = speedModeValue == 2 ? 2 : 4;
                return ticksInLine < lastLyTicks ? 153 : 0;
            }
            return 0;
        }
        if (ticksInLine >= getVisibleLyLineEdgeTick()) {
            return line + 1;
        }
        return line;
    }

    /**
     * LY as sampled by a native-CGB CPU bus read after the clock mux has been
     * rephased. The PPU's registered LY value and its LYC comparator keep using
     * {@link #getVisibleLy()}; only the CPU bus can observe the ripple-counter
     * hand-off between consecutive LY values.
     */
    private int getCpuVisibleLy() {
        if (cpuLyReadAcrossLineEdge) {
            return line + 1;
        }
        int visibleLy = getVisibleLy();
        if (earlyCgbLyReadEdge && gbc && !dmgCompatValue
                && speedModeValue == 1 && line < 153 && ticksInLine >= 448) {
            // Mighty Mix polls LY in a 32-dot loop while its VBlank handler spends
            // two lines in OAM DMA. The original cart observes the next LY value in
            // the final CPU read slot, preserving 144 across the interrupt. Keep the
            // calibrated PPU/LYC edge at dot 452 and advance only this CPU-bus latch.
            return line + 1;
        }
        if (gbc && !dmgCompatValue && speedModeValue == 1
                && lcdEnableClockPhase && !lyReadLatchRephasedBySpeedSwitch
                && line == 153 && ticksInLine < 4
                && (ticksInLine != 0 || firstFrameAfterLcdEnable)) {
            // Restarting the LCD leaves the normal-speed CPU read phase just past
            // line 153's transient LY latch. The PPU comparator and an ordinary
            // power-on grid continue to retain 153 for all four dots.
            return 0;
        }
        if (!gbc || dmgCompatValue || !lyReadLatchRephasedBySpeedSwitch) {
            return visibleLy;
        }
        if (line == 153) {
            int resetTransitionTick = speedModeValue == 2 ? 1 : 2;
            if (ticksInLine == resetTransitionTick) {
                return 0;
            }
        } else if ((speedModeValue == 1 && ticksInLine == 455)
                || (speedModeValue == 2 && ticksInLine == 451)) {
            return line & (line + 1);
        }
        return visibleLy;
    }

    /** Captures the native-CGB LY mux at the end of an ordinary HALT-wake read. */
    void captureCpuLyReadPhase(boolean ordinaryHaltWakePhase) {
        cpuLyReadAcrossLineEdge = gbc && !dmgCompatValue
                && speedModeValue == 1
                && ordinaryHaltWakePhase && mode == Mode.VBlank
                && line >= 144 && line < 153
                && ticksInLine < getVisibleLyLineEdgeTick()
                && ticksInLine + getCpuMachineCycleDots() >= getVisibleLyLineEdgeTick();
    }

    /**
     * PPU mode bits as visible in the STAT register.
     */
    public int getVisibleStatMode() {
        materializeSteadyTiming();
        if (!lcdEnabled) {
            return 0;
        }
        if (performanceScanlineLine && (mode == Mode.PixelTransfer || mode == Mode.HBlank)) {
            return mode == Mode.PixelTransfer
                    ? Mode.PixelTransfer.ordinal() : Mode.HBlank.ordinal();
        }
        if (gbc && !dmgCompatValue) {
            // Gambatte's frame-tail getStat window: native CGB exposes a one-dot
            // mode-0 gap at normal speed, then the line-zero mode-2 latch. Double
            // speed has no mode-0 gap.
            if (line == 153) {
                if (speedModeValue == 1
                        && (ticksInLine == 452
                        || (lcdEnableClockPhase && ticksInLine >= 452
                        && ticksInLine <= 454))) {
                    return Mode.HBlank.ordinal();
                }
                if (ticksInLine >= 453) {
                    return Mode.OamSearch.ordinal();
                }
            }
            // The shortened enable line reaches that projected mode-2 latch one dot
            // earlier at double speed.
            if (firstLine && speedModeValue == 2 && ticksInLine >= 453) {
                return Mode.OamSearch.ordinal();
            }
        }
        // The CGB's CPU-readable latch projects the next line's mode during the
        // final two dots, independently of compatibility or CPU speed.
        int nextLineModeTick = 454;
        if (gbc && ticksInLine >= nextLineModeTick) {
            if (line < 143 || (line == 153 && !dmgCompatValue)) {
                return Mode.OamSearch.ordinal();
            } else if (line == 143) {
                return Mode.VBlank.ordinal();
            }
        }
        // The last VBlank line briefly exposes mode 0 before line 0 enters
        // mode 2 (Wilbert Pol's ly00_mode1_0/ly00_mode0_2 tests).
        if (!gbc && line == 153 && ticksInLine >= 452) {
            return 0;
        }
        if (firstLine && ticksInLine < 79) {
            return 0;
        }
        // The CGB's CPU-readable mode latch changes at dot 78, just before the
        // internal OAM scan hands the pixel pipeline over to mode 3 at dot 80.
        int pixelTransferModeTick = gbc ? 78 : 80;
        if (gbc && mode == Mode.OamSearch && ticksInLine >= pixelTransferModeTick) {
            return Mode.PixelTransfer.ordinal();
        }
        if (gbc && mode == Mode.PixelTransfer && ticksInLine < 250) {
            return Mode.PixelTransfer.ordinal();
        }
        if (gbc && !dmgCompatValue && speedModeValue == 2
                && mode == Mode.HBlank
                && pixelTransferPhase.hasFineScxRephaseOnLine()
                && !pixelTransferPhase.hasObjectsOnLine()
                && !pixelTransferPhase.hasActivatedWindowOnLine()
                && pixelTransferPhase.getPosition() >= 160
                && pixelMachine.getPosition() >= 158) {
            // Repeated fine-SCX rewinds can leave the predicted mode-0 interrupt two
            // dots behind the physical transfer handoff. Once the shifted pipeline
            // reaches its terminal pair, the readable latch has entered mode 0.
            return Mode.HBlank.ordinal();
        }
        if (gbc && !dmgCompatValue && speedModeValue == 1
                && mode == Mode.PixelTransfer
                && pixelTransferPhase.isWindowActive()
                && pixelTransferPhase.hasActivatedWindowOnLine()
                && !pixelMachine.isWindowActive()
                && !pixelMachine.hasActivatedWindowOnLine()
                && pixelTransferPhase.getPosition() >= 159
                && pixelMachine.getPosition() >= 160
                && r.get(WX) >= 167) {
            // A late WX write can land after the timing skeleton has captured
            // StartWindowDraw but before the shifted CPU-visible pipeline does. In
            // that split state the CGB mode latch follows the pipeline that rejected
            // the stale comparator match, rather than retaining mode 3 for the
            // skeleton's otherwise unobservable startup tail.
            return Mode.HBlank.ordinal();
        }
        if (!gbc && mode == Mode.PixelTransfer
                && pixelTransferPhase.hasActivatedWindowOnLine()
                && !pixelTransferPhase.isWindowActive()
                && (pixelTransferPhase.getPosition()
                == (pixelTransferPhase.hasObjectsOnLine()
                ? 155 : 156 - (r.get(SCX) & 0x07))
                || pixelTransferPhase.getPosition()
                == (pixelTransferPhase.hasObjectsOnLine()
                ? 159 : 160 - (r.get(SCX) & 0x07)))) {
            // Clearing LCDC.5 exposes a one-dot DMG mode-0 pulse when the cancelled
            // window tile hands back to the background fetcher. CPU reads sample
            // that pulse on either adjacent machine-cycle boundary; intermediate
            // half-phases still see mode 3 while the fetcher drains its tail. Fine
            // SCX selects the pulse phase; an object fetch owns the X=155 hand-off.
            return Mode.HBlank.ordinal();
        }
        if (gbc && !dmgCompatValue && speedModeValue == 1
                && mode == Mode.PixelTransfer
                && pixelTransferPhase.hasActivatedWindowOnLine()
                && !pixelTransferPhase.isWindowActive()
                && !pixelMachine.hasActivatedWindowOnLine()
                && pixelTransferPhase.hasObjectsOnLine()
                && pixelMachine.getPosition() >= 155) {
            // When an object fetch and a cancelled window start diverge, native
            // CGB's CPU latch follows the shifted machine at its X=155 hand-off.
            return Mode.HBlank.ordinal();
        }
        if (gbc && dmgCompatValue && mode == Mode.PixelTransfer) {
            return Mode.PixelTransfer.ordinal();
        }
        if (gbc && dmgCompatValue && mode == Mode.HBlank
                && ticksInLine >= 250 && lcdc.isBgAndWindowDisplay()) {
            return Mode.HBlank.ordinal();
        }
        if (gbc && !dmgCompatValue && speedModeValue == 1
                && mode == Mode.PixelTransfer && pixelTransferDone) {
            return Mode.HBlank.ordinal();
        }
        if (gbc && !dmgCompatValue && speedModeValue == 1
                && mode == Mode.HBlank
                && line > 0
                && wyWrittenThisLine
                && !pixelTransferPhase.hasActivatedWindowOnLine()
                && !pixelMachine.hasActivatedWindowOnLine()
                && ticksInLine + 2 >= hblankIntFrom) {
            // A WY write that misses both window comparators leaves the normal-speed
            // CPU mux on the retiring mode-0 side once the timing skeleton has ended,
            // even though the ordinary fixed-background read-ahead still says mode 3.
            return Mode.HBlank.ordinal();
        }
        if (gbc && !dmgCompatValue && speedModeValue == 2
                && lateDoubleSpeedLineZeroWindowEnable
                && line == 1 && mode == Mode.HBlank
                && pixelMachine.isWindowActive()
                && ticksInLine + 2 >= hblankIntFrom) {
            // Enabling LCDC.5 in line zero's final double-speed bus slot selects the
            // following line's early mode-0 read phase. The adjacent earlier slot
            // retains mode 3 through the same physical HBlank hand-off.
            return Mode.HBlank.ordinal();
        }
        boolean dynamicWindowTail = pixelMachine.hasActivatedWindowOnLine()
                || (pixelTransferPhase.hasActivatedWindowOnLine()
                && !pixelTransferPhase.isWindowActive());
        int mode3ReadAhead = dynamicWindowTail
                || (!statModeLatchRephasedBySpeedSwitch
                && !firstLine && !lcdc.isWindowDisplay()
                && (r.get(SCX) & 7) != 0) ? 2 : 0;
        if (gbc && speedModeValue == 1
                && mode == Mode.HBlank
                && ticksInLine + mode3ReadAhead < hblankIntFrom
                && !pixelTransferPhase.hasObjectsOnLine()) {
            // Gambatte's STAT mux compares cc+2 with the predicted mode-0 edge. The
            // SCX=0 background path and the fixed first-line/window latch already bake
            // that lookahead into their calibration. A dynamic window or unrephased
            // fractional background line exposes the raw HBlank prediction here.
            return Mode.PixelTransfer.ordinal();
        }
        if (gbc && speedModeValue == 2
                && mode == Mode.HBlank
                && ticksInLine < hblankIntFrom
                && ticksInLine + 2 >= hblankIntFrom
                && pixelMachine.hasActivatedWindowOnLine()
                && !pixelMachine.isWindowActive()) {
            // A disabled window still owns the dynamic prediction, but double-speed
            // CPU reads release its mode-3 latch two dots before the mode-0 edge.
            return Mode.HBlank.ordinal();
        }
        if (gbc && !dmgCompatValue && speedModeValue == 2
                && mode == Mode.HBlank
                && pixelTransferPhase.hasObjectsOnLine()
                && pixelMachine.isWindowActive()
                && pixelMachine.getPosition() >= 157
                && (ticksInLine >= hblankIntFrom
                || (pixelTransferPhase.getObjectCountOnLine() == 10
                && ticksInLine + 1 >= hblankIntFrom))) {
            // Once an object-heavy window line reaches the shifted pipeline's final
            // pixel pair, the double-speed CPU latch follows the physical HBlank edge;
            // a full ten-object scan reaches that latch one dot before the predicted
            // mode-0 interrupt.
            return Mode.HBlank.ordinal();
        }
        if (gbc && !dmgCompatValue && speedModeValue == 2
                && mode == Mode.HBlank
                && scxWrittenThisLine
                && pixelTransferPhase.hasObjectsOnLine()
                && !pixelTransferPhase.hasActivatedWindowOnLine()
                && pixelTransferPhase.getPosition() >= 160
                && pixelMachine.getPosition() >= 160
                && pixelTransferPhase.getObjectTimingPenalty()
                != pixelMachine.getObjectTimingPenalty()) {
            // A late SCX write can make the timing and shifted output pipelines see
            // different object-fetch schedules. At double speed the CPU mode latch
            // follows the output pipeline once it has completed the line.
            return Mode.HBlank.ordinal();
        }
        if (gbc && speedModeValue == 2
                && ((mode == Mode.PixelTransfer && pixelTransferDone)
                || (mode == Mode.HBlank && ticksInLine < hblankIntFrom))) {
            return Mode.PixelTransfer.ordinal();
        }
        // A double-speed mode-2 interrupt enters its handler on the alternate CPU
        // phase. Gambatte's cc+2 comparison then retains the object-free X=167
        // prediction through the final four dots, independently of the mode-0 IRQ.
        if (gbc && speedModeValue == 2
                && doubleSpeedMode2DispatchStatTailThisLine
                && mode == Mode.HBlank
                && !pixelTransferPhase.hasObjectsOnLine()
                && !pixelTransferPhase.hasActivatedWindowOnLine()
                && ticksInLine < hblankIntFrom + 4) {
            return Mode.PixelTransfer.ordinal();
        }
        // A fine-SCX write at startup can move that captured prediction four dots
        // farther without changing the timing skeleton's already scheduled M0 edge.
        if (gbc && speedModeValue == 2
                && doubleSpeedMode2DispatchStatTailThisLine
                && earlyScxStatTailThisLine
                && mode == Mode.HBlank
                && ticksInLine < hblankIntFrom + 8) {
            return Mode.PixelTransfer.ordinal();
        }
        // Gambatte's terminal prediction targets PPU X=167 rather than the physical
        // end of mode 3. WX=166 contributes the remaining StartWindowDraw states; an
        // object exactly at X=167 then restarts the tile phase and contributes ten more
        // predicted dots. Double speed has its own CPU sampling phase.
        int terminalWindowReadTail = speedModeValue == 2
                ? 5
                : pixelTransferPhase.hasSpriteAtTerminalPredictionEdge() ? 12 : 2;
        if (gbc && (mode == Mode.PixelTransfer
                || (mode == Mode.HBlank
                && ticksInLine <= hblankIntFrom + terminalWindowReadTail))
                && ((mode == Mode.PixelTransfer
                && pixelTransferPhase.willStartCgbTerminalWindow())
                || pixelTransferPhase.hasCgbTerminalWindowStarted()
                || (pixelTransferPhase.getPosition() >= 160
                && pixelTransferPhase.isCgbWindowStartActive()))) {
            // WX=166 starts the CGB window machine after the last visible pixel. Its
            // physical transfer tail ends first; the CPU-readable mode-3 latch remains
            // through the independently predicted X=167 edge. The mode-0 interrupt
            // continues to use its separate X=166 event above.
            return Mode.PixelTransfer.ordinal();
        }
        if (gbc && speedModeValue == 1
                && mode == Mode.PixelTransfer
                && pixelTransferPhase.hasObjectsOnLine()
                && lcdc.isObjDisplayEffective()
                && pixelTransferPhase.getPosition() >= 160) {
            return Mode.HBlank.ordinal();
        }
        if (gbc && speedModeValue == 1
                && mode == Mode.PixelTransfer && pixelTransferDone
                && (pixelTransferPhase.hasObjectsOnLine() || lcdc.isWindowDisplay())
                && !(firstLine && oamSearchPhase.hadSpriteCandidate())) {
            return Mode.HBlank.ordinal();
        }
        // Gambatte's CGB STAT read tests `cc + 2 < predictedM0(X=166)`. Coffee's
        // shifted pixel machine supplies dynamic X; fine SCX advances that edge,
        // while the CPU read itself contributes the minimum two-dot lookahead.
        int shiftedStatX = pixelMachine.getPosition() + Math.max(2, r.get(SCX) & 7);
        boolean fixedBackgroundModeLatch = lcdc.isWindowDisplay() || firstLine
                || (mode == Mode.PixelTransfer
                && pixelTransferPhase.getPosition() < 159
                && pixelMachine.getPosition() >= 155
                && (ticksInLine & 3) == 2);
        if (gbc && speedModeValue == 1
                && (!statModeLatchRephasedBySpeedSwitch
                || (mode == Mode.HBlank && lcdc.isWindowDisplay()))
                && (mode == Mode.PixelTransfer || mode == Mode.HBlank)
                && !pixelTransferPhase.hasObjectsOnLine()
                && !(firstLine && oamSearchPhase.hadSpriteCandidate())
                && !pixelTransferPhase.isWindowActive()
                && !pixelTransferPhase.hasFineScxRephaseOnLine()
                && (pixelTransferPhase.hasActivatedWindowOnLine()
                ? (shiftedStatX >= 161
                || (shiftedStatX >= 160
                && ticksInLine >= hblankIntFrom - 1))
                : !scxWrittenThisLine
                && (!pixelMachine.isWindowActive() || wyWrittenThisLine)
                && (fixedBackgroundModeLatch
                ? ticksInLine >= 243 + (firstLine ? 4 : 0)
                + ((r.get(SCX) & 0x04) != 0 ? 4 : 0)
                : shiftedStatX >= 161
                && ticksInLine >= hblankIntFrom))) {
            // Window/enable lines use the fixed boot-phase latch. During an ordinary
            // transfer, that latch is also sampled by normal-speed CPU reads landing
            // on phase two before the final output stage; other phases follow shifted
            // output only after the internal HBlank edge. A window that started and
            // was then disabled has paid its dynamic startup cost, but its readable
            // latch still leads the final two pixels.
            return Mode.HBlank.ordinal();
        }
        int readablePixelEnd;
        if (statModeLatchRephasedBySpeedSwitch) {
            readablePixelEnd = speedModeValue == 2
                    && !pixelTransferPhase.hasObjectsOnLine()
                    && pixelMachine.hasActivatedWindowOnLine()
                    ? 160
                    : 158;
        } else if (speedModeValue == 1
                && pixelTransferPhase.hasObjectsOnLine()) {
            // On object lines the CPU mode latch is three pixels ahead of the shifted
            // LCD-output machine. The timing skeleton has already handed off to
            // HBlank when the output machine reaches position 157.
            readablePixelEnd = 157;
        } else {
            // Before a speed switch, the normal-speed CGB mode mux still predicts the
            // HBlank edge two dots ahead of the shifted LCD output machine when the
            // window path owns that mux. Background-only timing retains its separately
            // calibrated output threshold.
            readablePixelEnd = lcdc.isWindowDisplay() ? 158 : 160;
        }
        if (gbc && mode == Mode.HBlank
                && (pixelMachine.getPosition() < readablePixelEnd
                || (speedModeValue == 2
                && ticksInLine < hblankIntFrom
                && pixelMachine.isObjectFetchInProgress()))) {
            // Internal HBlank, its STAT interrupt source, and the CPU-readable mode
            // latch are separate signals. Follow the shifted pixel machine's dynamic
            // tail. Once the clock mux has been switched, the ordinary CPU latch
            // releases two positions early, but an object-free activated double-speed
            // window retains it through the last output pixel. Object lines retain
            // their separately predicted tail. A right-edge object fetch can outlive
            // both position counters, so its active state holds the latch.
            return Mode.PixelTransfer.ordinal();
        }
        if (gbc && speedModeValue == 2
                && mode == Mode.HBlank
                && pixelMachine.isWindowActive()
                && ((r.get(SCX) & 7) == 5
                ? ticksInLine <= hblankIntFrom + 2
                + (!pixelTransferPhase.hasActivatedWindowOnLine()
                && pixelMachine.hasActivatedWindowOnLine() ? 7 : 0)
                : ticksInLine < hblankIntFrom)) {
            // Window startup leaves the double-speed CPU's readable mode latch
            // asserted through the internal HBlank edge, even after both output
            // counters have emitted their final pixel.
            return Mode.PixelTransfer.ordinal();
        }
        if (gbc && speedModeValue == 2
                && mode == Mode.PixelTransfer && pixelTransferDone) {
            return Mode.PixelTransfer.ordinal();
        }
        // A late DMG OBJ enable/disable, or an SCX write caught before the timing
        // skeleton starts, can make the CPU timing skeleton and shifted LCD output
        // pipeline take different paths. Only use the output pipeline for that
        // divergent tail; ordinary lines retain the calibrated timing below.
        if (!gbc
                && (mode == Mode.PixelTransfer || mode == Mode.HBlank)
                && pixelTransferPhase.getPosition() >= 159
                && !pixelTransferPhase.isObjectFetchInProgress()
                && !pixelMachine.isObjectFetchInProgress()
                && (pixelTransferPhase.getObjectTimingPenalty()
                != pixelMachine.getObjectTimingPenalty()
                || earlyScxStatTailThisLine)) {
            return pixelMachine.getPosition() < 158
                    ? Mode.PixelTransfer.ordinal()
                    : Mode.HBlank.ordinal();
        }
        if (gbc && dmgCompatValue && mode == Mode.HBlank
                && ticksInLine <= hblankIntFrom
                && lcdc.isObjDisplayEffective()
                && pixelTransferPhase.hasObjectsOnLine()
                && ((r.get(SCX) & 7) != 0 || lcdc.isWindowDisplay())) {
            return Mode.PixelTransfer.ordinal();
        }
        // A scanline containing enabled objects, combined with fractional scroll or the
        // window, can leave the readable mode-3 tail asserted after the internal phase
        // releases the VRAM/OAM locks (Misc.-GB-Tests' NOP-shifted sprite variants).
        // The window and high fine-SCX phases retain the latch through the mode-0
        // edge; fine SCX 1..4 without a window release it two dots earlier. Keep this
        // separate from `mode`: the lock handoff and HBlank interrupt retain their
        // calibrated timings. Vertically inactive objects and sprite-disabled lines do
        // not produce this tail (GBMicrotest), while selected objects beyond the right
        // edge still do.
        if (!gbc && mode == Mode.HBlank
                && (lcdc.isWindowDisplay() || (r.get(SCX) & 7) >= 5
                ? ticksInLine <= hblankIntFrom
                : ticksInLine < hblankIntFrom - 2)
                && lcdc.isObjDisplayEffective()
                && pixelTransferPhase.hasObjectsOnLine()
                && ((r.get(SCX) & 7) != 0 || lcdc.isWindowDisplay())) {
            return Mode.PixelTransfer.ordinal();
        }
        return mode.ordinal();
    }

    /** Returns the normal-speed STAT mode sampled at the end of a rephased CPU read. */
    int getCpuVisibleStatMode() {
        int visibleMode = getVisibleStatMode();
        if (performanceScanlineLine) {
            return visibleMode;
        }
        if (gbc && !dmgCompatValue && speedModeValue == 1
                && mode == Mode.HBlank && dma.ownsOamForPpu()
                && pixelTransferPhase.hasObjectsOnLine()
                && ticksInLine == hblankIntFrom) {
            // An object-owned OAM-DMA scan can put the CPU read exactly on the
            // predicted mode-0 edge. That bus phase still sees the retiring mode-3
            // latch even though the internal timing skeleton has entered HBlank.
            return Mode.PixelTransfer.ordinal();
        }
        if (!gbc || dmgCompatValue || !statModeLatchRephasedBySpeedSwitch
                || speedSwitchCompletedThisLine || speedModeValue != 1
                || scxWrittenThisLine
                || firstLine || line >= 144) {
            return visibleMode;
        }
        if (mode == Mode.OamSearch && ticksInLine >= 74) {
            return Mode.PixelTransfer.ordinal();
        }
        int readAhead = 4 - (ticksInLine & 3);
        if ((mode == Mode.PixelTransfer || mode == Mode.HBlank)
                && pixelTransferPhase.getPosition() + readAhead
                >= 160 + (r.get(SCX) & 7)) {
            return Mode.HBlank.ordinal();
        }
        return visibleMode;
    }

    boolean isUnrephasedLineZeroStatTail() {
        return gbc && !dmgCompatValue && speedModeValue == 1
                && lcdEnableClockPhase && !statModeLatchRephasedBySpeedSwitch
                && !firstLine && line == 0 && mode == Mode.HBlank
                && !pixelTransferPhase.hasObjectsOnLine()
                && !pixelTransferPhase.hasActivatedWindowOnLine()
                && ticksInLine <= hblankIntFrom + 4;
    }

    /**
     * Returns the mode sampled by the CGB CPU bus before this dot's PPU clocks have
     * settled, or {@code -1} when the ordinary readable STAT latch is visible.
     *
     * <p>This deliberately does not alter {@link #getVisibleStatMode()}: direct PPU
     * observers see the mode-3 latch change at dot 78. An object-free double-speed
     * window follows output through position 159; other rephased tails can release
     * earlier. A CPU memory callback can still sample the old side of either
     * transition for its current bus phase.</p>
     */
    int getCpuStatModeOverride() {
        int mode2Handoff = getCpuMode2HandoffStatOverride();
        if (mode2Handoff >= 0) {
            return mode2Handoff;
        }
        if (gbc && speedModeValue == 2
                && statModeLatchRephasedBySpeedSwitch
                && mode == Mode.HBlank && ticksInLine >= hblankIntFrom
                && pixelMachine.hasActivatedWindowOnLine()
                && pixelMachine.getPosition() < 160) {
            return Mode.PixelTransfer.ordinal();
        }
        return -1;
    }

    /**
     * Returns the old side of native CGB's normal-speed mode-2-to-mode-3 CPU mux
     * at stored dot 78, or {@code -1} outside that single hand-off phase.
     */
    int getCpuMode2HandoffStatOverride() {
        if (gbc && !dmgCompatValue && speedModeValue == 1
                && !firstLine && mode == Mode.OamSearch && ticksInLine == 78) {
            return Mode.OamSearch.ordinal();
        }
        return -1;
    }

    /**
     * Returns the mode captured immediately before this tick's CPU memory callback,
     * or {@code -1} when that callback uses the ordinary readable STAT latch.
     */
    int getCpuReadStatModeOverride(boolean synchronousHaltEntryPhase,
                                   boolean asynchronousHaltEntryPhase,
                                   boolean ordinaryHaltWakePhase) {
        return getCpuReadStatModeOverride(synchronousHaltEntryPhase,
                asynchronousHaltEntryPhase, ordinaryHaltWakePhase, false);
    }

    int getCpuReadStatModeOverride(boolean synchronousHaltEntryPhase,
                                   boolean asynchronousHaltEntryPhase,
                                   boolean ordinaryHaltWakePhase,
                                   boolean oneCycleOrdinaryHaltWakePhase) {
        return getCpuReadStatModeOverride(synchronousHaltEntryPhase,
                asynchronousHaltEntryPhase, ordinaryHaltWakePhase,
                oneCycleOrdinaryHaltWakePhase, ordinaryHaltWakePhase);
    }

    int getCpuReadStatModeOverride(boolean synchronousHaltEntryPhase,
                                   boolean asynchronousHaltEntryPhase,
                                   boolean ordinaryHaltWakePhase,
                                   boolean oneCycleOrdinaryHaltWakePhase,
                                   boolean recentOrdinaryHaltWakePhase) {
        if (gbc && !dmgCompatValue
                && statRegister.isMode2InterruptSourceOnly()) {
            if (speedModeValue == 1
                    && (!ordinaryHaltWakePhase || recentOrdinaryHaltWakePhase)
                    && !firstLine
                    && mode == Mode.OamSearch && ticksInLine == 78) {
                // A mode-2 handler reading on the hand-off bus cycle retains the
                // source latch even though the ordinary FF41 mux has entered mode 3.
                return Mode.OamSearch.ordinal();
            }
            if (speedModeValue == 2
                    && doubleSpeedMode2DispatchStatTailThisLine
                    && mode == Mode.PixelTransfer && ticksInLine == 80) {
                // Double speed accepts the mode-2 IRQ on the alternate CPU phase;
                // its matching handler read keeps mode 2 for the first mode-3 slot.
                return Mode.OamSearch.ordinal();
            }
            if (mode == Mode.HBlank && line < 143 && !firstLine
                    && (!ordinaryHaltWakePhase || recentOrdinaryHaltWakePhase)
                    && ticksInLine == 454) {
                // The final normal-speed handler read still sees retiring mode 0,
                // before the mode-2 source selects the next-line projection.
                return Mode.HBlank.ordinal();
            }
            if (speedModeValue == 2
                    && doubleSpeedMode2DispatchCrossedLineEdge
                    && mode == Mode.OamSearch && ticksInLine == 0) {
                // A double-speed read split by rollover completes on line dot zero,
                // but its bus sample belongs to the previous line's mode-0 slot.
                return Mode.HBlank.ordinal();
            }
        }
        if (gbc && !dmgCompatValue && speedModeValue == 1
                && !firstLine && (line == 0
                || recentOrdinaryHaltWakePhase)
                && mode == Mode.OamSearch
                && ticksInLine == 78) {
            // The CPU-facing mux retains the frame-start mode-2 latch for the bus
            // callback at dot 78. The ordinary per-line latch has already moved to
            // mode 3 by this phase; frame rollover and an ordinary HALT wake retain
            // the old side of the mux for this bus slot.
            return Mode.OamSearch.ordinal();
        }
        if (gbc && !dmgCompatValue && speedModeValue == 2
                && statModeLatchRephasedBySpeedSwitch
                && lyReadLatchRephasedBySpeedSwitch
                && !ordinaryHaltWakePhase
                && !firstLine && line < 144
                && mode == Mode.OamSearch
                && ticksInLine < 78
                && ticksInLine + getCpuMachineCycleDots() >= 78) {
            // A rephased double-speed CPU read samples the mode latch at the end
            // of its two-dot bus cycle. Direct PPU observers still see mode 2 at
            // stored dot 76; the CPU callback already sees the dot-78 mode-3 side.
            return Mode.PixelTransfer.ordinal();
        }
        if (mode == Mode.HBlank && line < 143 && !firstLine) {
            if (gbc && !dmgCompatValue && speedModeValue == 1
                    && synchronousHaltEntryPhase && ticksInLine == 454) {
                return Mode.HBlank.ordinal();
            }
            if (!gbc && oneCycleOrdinaryHaltWakePhase
                    && statRegister.isMode0InterruptSourceOnly()
                    && (r.get(GpuRegister.SCX) & 0x07) == 3
                    && ticksInLine == 452) {
                return Mode.OamSearch.ordinal();
            }
            if (!gbc && asynchronousHaltEntryPhase && ticksInLine >= 455) {
                return Mode.OamSearch.ordinal();
            }
            if (gbc && !dmgCompatValue
                    && statRegister.isMode0InterruptSourceOnly()
                    && !synchronousHaltEntryPhase
                    && !asynchronousHaltEntryPhase
                    && !ordinaryHaltWakePhase
                    && (speedModeValue == 2
                    || (r.get(GpuRegister.SCX) & 0x07) == 3)
                    && ticksInLine + getCpuMachineCycleDots() == 454) {
                // The final STAT read cycle on an active line samples the upcoming
                // mode-2 latch. At normal speed this bus phase occurs with fine
                // scroll phase 3; double speed exposes it for every fine-scroll
                // phase. Direct PPU observers retain mode 0 until dot 454.
                return Mode.OamSearch.ordinal();
            }
        }
        if (gbc && !dmgCompatValue
                && statRegister.isMode0InterruptSourceOnly()
                && mode == Mode.HBlank
                && !pixelTransferPhase.hasObjectsOnLine()
                && !pixelTransferPhase.hasActivatedWindowOnLine()
                && !scxWrittenThisLine
                && ticksInLine + (speedModeValue == 2 ? 2 : 0)
                == hblankIntFrom) {
            // The mode-0 source samples its own CPU-facing mode latch when the
            // predicted event is accepted. A double-speed read starts two PPU dots
            // before its bus sample; at normal speed the callback already lands on
            // the event dot. The direct PPU latch can still expose mode 3 there.
            return Mode.HBlank.ordinal();
        }
        if (gbc && !dmgCompatValue && speedModeValue == 1
                && firstLine && mode == Mode.HBlank
                && !pixelTransferPhase.hasObjectsOnLine()
                && ticksInLine + 1 >= hblankIntFrom) {
            // On the LCD-enable line, an object-free normal-speed CPU read samples
            // the mode-0 side of the mux one dot before the ordinary STAT latch.
            return Mode.HBlank.ordinal();
        }
        return -1;
    }

    /**
     * The mode-2 STAT source is a short pulse during the final machine cycle of the
     * preceding line. At the frame boundary native CGB exposes it in line 153's tail;
     * DMG and compatibility mode expose it during the first four ticks of line 0.
     */
    public boolean isMode2IntWindow() {
        materializeSteadyTiming();
        if (!lcdEnabled) {
            return false;
        }
        return (line < 144 && ticksInLine >= getEarlyLineEdgeTick())
                || (gbc && !dmgCompatValue
                && line == 153 && ticksInLine >= 454)
                || ((!gbc || dmgCompatValue)
                && !firstLine && line == 0 && ticksInLine < 4);
    }

    /**
     * The "mode 0" STAT interrupt condition rises with the visible mode 0, quantized to
     * 4-tick steps of the SCX scroll delay, and stays active until the end of the line.
     */
    public boolean isMode0IntWindow() {
        materializeSteadyTiming();
        return lcdEnabled && line < 144 && ticksInLine >= getMode0InterruptTick();
    }

    boolean hasObjectsOnLine() {
        return pixelTransferPhase.hasObjectsOnLine();
    }

    /**
     * The mode-0 edge reaches the HALT wake input two T-cycles after it becomes
     * visible in IF. A running CPU can sample IF immediately.
     */
    public boolean isMode0HaltWakeTick() {
        materializeSteadyTiming();
        return lcdEnabled && line < 144 && ticksInLine == getMode0InterruptTick() + 2;
    }

    int getMode0InterruptTick() {
        materializeSteadyTiming();
        return getMode0InterruptTickForTick();
    }

    private int getMode0InterruptTickForTick() {
        // DMG normally follows the completed transfer latch. A selected object on
        // the X=166/167 prediction boundary keeps its physical fetch tail separate
        // from the already captured mode-0 interrupt edge, just as on CGB.
        return gbc || (!lcdc.isWindowDisplay()
                && pixelTransferPhase.hasSpriteAtMode0PredictionEdge())
                ? mode0IntFrom : hblankIntFrom;
    }

    /**
     * The DMG's terminal WX=166 comparator and an X=167 object leave the mode-0
     * prediction and physical HBlank latches eight dots apart. In that collision,
     * the final CPU IF read phase samples the already scheduled edge two dots before
     * the stored IF latch changes.
     */
    boolean isDmgTerminalWindowMode0ReadPreviewPhase() {
        materializeSteadyTiming();
        return isDmgTerminalWindowMode0ReadPreviewPhaseForTick();
    }

    boolean isDmgTerminalWindowMode0ReadPreviewPhaseForTick() {
        return !gbc && lcdEnabled && line < 144
                && ticksInLine + 2 == getMode0InterruptTickForTick()
                && lcdc.isBgAndWindowDisplay()
                && lcdc.isWindowDisplay()
                && lcdc.isObjDisplay()
                && r.get(WX) == 166
                && pixelTransferPhase.hasSpriteAtTerminalPredictionEdge()
                && mode0IntFrom < hblankIntFrom;
    }

    /**
     * The mode-1 STAT source follows the PPU's internal VBlank state. On DMG the readable
     * STAT mode briefly becomes 0 at the end of line 153, but the interrupt source remains
     * asserted until the line-0 mode-2 source takes over.
     */
    public boolean isMode1IntWindow() {
        return lcdEnabled && (mode == Mode.VBlank
                || (gbc && line == 143 && ticksInLine >= 448));
    }

    /**
     * Applies the model-specific CPU-side OAM read and write bus gates. The CGB latches
     * do not share the DMG write opening at the mode-2/mode-3 boundary, and their line
     * edge and mode-0 hand-offs happen on separate CPU clock phases.
     */
    private boolean isOamAvailableForCpu() {
        return isOamAvailableForCpu(false);
    }

    public boolean isOamAvailableForCpu(boolean write) {
        materializeSteadyTiming();
        if (!lcdEnabled) {
            return true;
        }
        int firstLineOamOpenTicks = gbc && write && speedModeValue == 2
                ? 77 : 79;
        if (firstLine && ticksInLine < firstLineOamOpenTicks) {
            return true;
        }
        if (gbc && speedModeValue == 2
                && !write && mode == Mode.OamSearch && ticksInLine == 0) {
            // At double speed the read latch closes one CPU read phase after the line
            // rolls over. The write latch is already closed on dot 0.
            return true;
        }
        if (mode == Mode.OamSearch) {
            // Only DMG releases the OAM write bus between the end of the scan and the
            // start of pixel transfer (lcdon_write_timing-GS).
            return !gbc && write && ticksInLine >= 76 && ticksInLine < 80;
        }
        if (mode == Mode.PixelTransfer) {
            return gbc && pixelTransferDone;
        }
        if (performanceScanlineLine && mode == Mode.HBlank) {
            return !gbc || write || ticksInLine >= hblankIntFrom + 4;
        }
        if (gbc && mode == Mode.HBlank) {
            if (pixelTransferPhase.hasObjectsOnLine()) {
                // The object fetch path releases the read latch one dot after the
                // internal mode transition; its write latch opens at the hand-off.
                if (!write && ticksInLine < hblankIntFrom - 1) {
                    return false;
                }
            } else {
                // BG-only lines release both CGB OAM latches with the final mode-3
                // output stage. An OAM DMA that owned the scan leaves the read bus
                // released at the internal edge instead. Otherwise normal-speed
                // release is quantized by fine SCX and double speed follows the
                // internal hand-off. Terminal StartWindowDraw retains ownership for
                // four additional dots without extending physical mode 3.
                boolean dmaReadHandoff = !write && oamSearchPhase.wasDmaBlockedThisLine();
                int handoffTick = dmaReadHandoff
                        ? hblankIntFrom
                        : speedModeValue == 1 && !firstLine
                                ? 254 + ((r.get(SCX) & 0x04) != 0 ? 4 : 0)
                                : hblankIntFrom + 4;
                if (!write && !dmaReadHandoff
                        && pixelTransferPhase.hasCgbTerminalWindowStarted()) {
                    handoffTick += 4;
                }
                if (ticksInLine < handoffTick) {
                    return false;
                }
            }
        }

        // DMG writes still pass during its early read-lock window. A normal-speed CGB
        // scan that selected objects or was owned by OAM DMA reclaims both latches at
        // the early edge; an idle BG-only scan holds them until dot 454. At double
        // speed the transition occupies dots 452-453, with the separate dot-0 read
        // release above.
        boolean dmgEarlyReadLock = !gbc && !write
                && ticksInLine >= getEarlyLineEdgeTick();
        boolean cgbOamScanOwnedLine = pixelTransferPhase.hasObjectsOnLine()
                || oamSearchPhase.wasDmaBlockedThisLine();
        int cgbNormalSpeedLineEdgeTick = cgbOamScanOwnedLine
                ? getEarlyLineEdgeTick() : 454;
        boolean cgbNormalSpeedLineEdgeLock = gbc && speedModeValue == 1
                && !firstLine && ticksInLine >= cgbNormalSpeedLineEdgeTick;
        boolean cgbDoubleSpeedLineEdgeLock = gbc && speedModeValue == 2
                && ticksInLine >= 452 && ticksInLine < 454;
        if ((dmgEarlyReadLock || cgbNormalSpeedLineEdgeLock || cgbDoubleSpeedLineEdgeLock)
                && (line < 143 || line == 153)) {
            return false;
        }
        return true;
    }

    public int getEarlyLineEdgeTick() {
        if (firstLine) {
            return 451;
        }
        return gbc ? 448 : 452;
    }

    public int getCpuMachineCycleDots() {
        return 4 / speedModeValue;
    }

    public int getCoincidenceReleaseTick() {
        if (firstLine) {
            // At the end of the shortened enable line Gambatte's getLycCmpLy has
            // entered its final non-readable comparison slot: dot 451 normal speed,
            // dot 453 double speed (the `> releaseTick` CGB rule below maps to 452).
            return speedModeValue == 2 ? 452 : getEarlyLineEdgeTick();
        }
        if (line == 0 && lcdEnableClockPhase && gbc && !dmgCompatValue
                && speedModeValue == 1
                && !statModeLatchRephasedBySpeedSwitch) {
            // On the LCD-restart grid, stored dot 452 is already the comparator's
            // non-readable tail slot. A speed switch replaces this phase with its
            // separately modelled CPU-facing mux.
            return 451;
        }
        if (!gbc) {
            return getEarlyLineEdgeTick();
        }
        if (dmgCompatValue) {
            return 452;
        }
        // At double speed FF44 has already advanced, but the old coincidence
        // result remains readable through dot 454 in its separate STAT latch.
        return speedModeValue == 2 ? 454 : getVisibleLyLineEdgeTick();
    }

    private int getVisibleLyLineEdgeTick() {
        if (!gbc || firstLine) {
            return getEarlyLineEdgeTick();
        }
        if (dmgCompatValue) {
            return 450;
        }
        return 452;
    }

    /**
     * VRAM reads are locked from 4 ticks before the pixel transfer starts until it ends;
     * writes are only blocked during the pixel transfer itself. On the first line after
     * enabling the LCD, it is locked when the pixel transfer starts.
     */
    private boolean isVramAvailableForCpu() {
        return isVramAvailableForCpu(false);
    }

    private boolean isVramAvailableForCpu(boolean write) {
        if (!lcdEnabled) {
            return true;
        }
        if (!write && cpuRetiringInstructionForHdma && gbc && mode == Mode.HBlank) {
            return true;
        }
        if (firstLine && gbc && ticksInLine < 84) {
            // The display-enable VRAM latch closes one dot earlier at double speed;
            // it is distinct from both the CPU-readable STAT and palette latches.
            return ticksInLine < (speedModeValue == 2 ? 79 : 80);
        }
        if (mode == Mode.PixelTransfer) {
            if (!gbc) {
                return false;
            }
            int position = pixelTransferPhase.getPosition();
            // The final normal-speed CPU slot is released once the fetcher has
            // committed X=159. Reads use the request retained by the immediately
            // preceding write; fine SCX changes when X=159 is reached, not the
            // comparator itself (vramw_m3end).
            if (speedModeValue == 1 && position >= 159
                    && (write || followsCpuVramWrite())) {
                return true;
            }
            if (!statModeLatchRephasedBySpeedSwitch) {
                return false;
            }
            // Around fetch start, a rephased CPU clock can land in one otherwise-idle
            // VRAM arbitration slot. Normal and double speed expose different slots.
            return speedModeValue == 2
                    ? position <= -16
                    : position >= -8 && position < -4;
        }
        if (performanceScanlineLine && mode == Mode.HBlank) {
            return !gbc || write || ticksInLine >= hblankIntFrom + 4;
        }
        if (gbc && !write && mode == Mode.HBlank) {
            if (followsCpuVramWrite()) {
                return true;
            }
            int handoffTick;
            if (pixelTransferPhase.hasObjectsOnLine()) {
                handoffTick = hblankIntFrom;
            } else if (speedModeValue == 1 && !firstLine) {
                handoffTick = 254 + ((r.get(SCX) & 0x04) != 0 ? 4 : 0);
            } else {
                handoffTick = hblankIntFrom + 4;
            }
            if (!pixelTransferPhase.hasObjectsOnLine()
                    && pixelTransferPhase.hasCgbTerminalWindowStarted()) {
                // Terminal StartWindowDraw keeps the idle CGB read arbiter occupied
                // after the physical transfer has already entered HBlank.
                handoffTick += 4;
            }
            return ticksInLine >= handoffTick;
        }
        if (!write && mode == Mode.OamSearch) {
            if (!gbc) {
                return firstLine || ticksInLine < 76;
            }
            return speedModeValue != 2 || ticksInLine < 79;
        }
        if (gbc && write && mode == Mode.OamSearch
                && speedModeValue == 2 && ticksInLine >= 79) {
            return false;
        }
        return true;
    }

    private boolean followsCpuVramWrite() {
        int readDelay = speedModeValue == 2 ? 4 : 8;
        return lastCpuVramWriteTick != Integer.MIN_VALUE
                && ticksInLine - lastCpuVramWriteTick == readDelay;
    }

    /** Keeps the VRAM slot owned by a CPU instruction that won HDMA arbitration. */
    public void setCpuRetiringInstructionForHdma(boolean retiring) {
        disablePerformanceScanlineCursor();
        materializeSteadyTiming();
        cpuRetiringInstructionForHdma = retiring;
    }

    private void setLcdc(int value) {
        int previousLcdc = lcdc.get();
        if (gbc && !dmgCompatValue && speedModeValue == 2
                && lcdEnabled && line == 0 && mode == Mode.HBlank
                && (previousLcdc & 0x20) == 0 && (value & 0x20) != 0) {
            lateDoubleSpeedLineZeroWindowEnable = ticksInLine >= 449;
        }
        // SameBoy's DMG_LCDC position_in_line == 0 special: hardware's position at the
        // write sits 3 dots behind our +4-shifted machine's, so the gate is position 3
        boolean dropObjEnInMix = !gbc
                && (value & 0x02) == 0
                && (lcdc.get() & 0x02) != 0
                && mode == Mode.PixelTransfer
                && (pixelMachine.isObjectFetchInProgress() || pixelMachine.getPosition() == 3);
        // disabling the window while it is being fetched suppresses the DMG
        // window-insertion glitch for the rest of the line (SameBoy DMG_LCDC)
        if (!gbc && (lcdc.get() & 0x20) != 0 && (value & 0x20) == 0) {
            if (pixelTransferPhase.isWindowBeingFetched()) {
                pixelTransferPhase.disableWindowInsertionGlitch();
            }
            if (!pixelMachine.hasDelayedWindowDisplayWrite()
                    && pixelMachine.isWindowBeingFetched()) {
                pixelMachine.disableWindowInsertionGlitch();
            }
        }
        lcdc.set(value, dropObjEnInMix);
        if ((value & (1 << 7)) == 0) {
            disableLcd();
        } else {
            enableLcd();
        }
    }

    private void disableLcd() {
        disablePerformanceScanlineCursor();
        performanceScanlineLine = false;
        performanceWindowLineCounter = -1;
        if (!lcdEnabled) {
            return;
        }
        clearPendingPpuWrites();
        pixelMachine.cancelDelayedWindowDisplayWrite();
        pixelMachine.cancelDelayedWindowXWrite();
        r.put(LY, 0);
        pixelTransferPhase.resetWindowLineCounter();
        pixelMachine.resetWindowLineCounter();
        this.line = 0;
        this.ticksInLine = 0;
        this.firstLine = false;
        this.firstFrameAfterLcdEnable = false;
        this.pixelTransferDone = false;
        this.hblankIntFrom = Integer.MAX_VALUE;
        this.mode0IntFrom = Integer.MAX_VALUE;
        this.speedSwitchCompletedThisLine = false;
        this.scxWrittenThisLine = false;
        this.doubleSpeedMode2DispatchStatTailThisLine = false;
        this.doubleSpeedMode2DispatchCrossedLineEdge = false;
        this.earlyScxStatTailThisLine = false;
        this.wyWrittenThisLine = false;
        this.lateDoubleSpeedLineZeroWindowEnable = false;
        this.lastCpuVramWriteTick = Integer.MIN_VALUE;
        this.mode = Mode.HBlank;
        this.lcdEnabled = false;
        this.displayEnabledDelay = 0;
        statRegister.onLcdDisabled();
        pixelMachine.clearOutput();
        pixelMachine.stop();
        display.disableLcd();
        DebugHooks hooks = debugHooks;
        if (hooks != null) {
            hooks.onPpuEvent(
                    PpuTrace.Kind.LCD_DISABLED,
                    debugPpuFrame,
                    0,
                    0,
                    DebugPpuMode.DISABLED);
        }
    }

    private void enableLcd() {
        disablePerformanceScanlineCursor();
        performanceScanlineLine = false;
        if (lcdEnabled) {
            return;
        }
        performanceWindowLineCounter = -1;
        this.line = 0;
        // the line grid is locked to the machine-cycle phase: enabling the LCD starts
        // the line one tick after the LCDC write, matching the power-on grid
        this.ticksInLine = -1;
        this.firstLine = true;
        this.lcdEnableClockPhase = true;
        this.firstFrameAfterLcdEnable = true;
        this.lyReadLatchRephasedBySpeedSwitch = false;
        this.pixelTransferDone = false;
        this.hblankIntFrom = Integer.MAX_VALUE;
        this.mode0IntFrom = Integer.MAX_VALUE;
        this.speedSwitchCompletedThisLine = false;
        this.scxWrittenThisLine = false;
        this.doubleSpeedMode2DispatchStatTailThisLine = false;
        this.doubleSpeedMode2DispatchCrossedLineEdge = false;
        this.earlyScxStatTailThisLine = false;
        this.wyWrittenThisLine = false;
        this.lateDoubleSpeedLineZeroWindowEnable = false;
        this.lastCpuVramWriteTick = Integer.MIN_VALUE;
        r.put(LY, 0);
        // Enabling the LCD samples the line-zero window master immediately. Later
        // WY writes must not undo that sample (enable_display_ly0_wemaster).
        pixelTransferPhase.checkWindowY();
        pixelMachine.checkWindowY();
        // The first shortened line has no sprite-selection scan. Keep advancing the
        // OAM phase for its timing grid, but do not expose candidates to mode 3; CPU
        // OAM access remains open during this interval.
        this.mode = Mode.OamSearch;
        oamSearchPhase.onLcdEnabled();
        this.phase = oamSearchPhase.start(false);
        this.lcdEnabled = true;
        this.displayEnabledDelay = 244;
        statRegister.onLcdEnabled();
        DebugHooks hooks = debugHooks;
        if (hooks != null) {
            hooks.onPpuEvent(
                    PpuTrace.Kind.LCD_ENABLED,
                    debugPpuFrame,
                    0,
                    0,
                    DebugPpuMode.OAM_SEARCH);
        }
    }

    public void setDebugHooks(DebugHooks hooks) {
        materializeSteadyTiming();
        debugHooks = hooks;
        if (hooks != null && hooks.requiresPpuMemoryAccessHooks()) {
            AddressSpace observedVideoRam0 = new DebugPpuAddressSpace(
                    videoRam0, DebugAddressSpace.VIDEO_RAM, hooks);
            AddressSpace observedVideoRam1 = videoRam1 == null ? null : new DebugPpuAddressSpace(
                    videoRam1, DebugAddressSpace.VIDEO_RAM, hooks);
            AddressSpace observedPixelOam = new DebugPpuAddressSpace(
                    ppuOam, DebugAddressSpace.OAM, hooks);
            AddressSpace observedSearchOam = new DebugPpuAddressSpace(
                    oamRam, DebugAddressSpace.OAM, hooks);
            // The shifted pixel machine owns the physical fetch dots. The timing skeleton
            // performs parallel predictive reads and deliberately remains on raw delegates.
            pixelMachine.setDebugAddressSpaces(
                    observedVideoRam0, observedVideoRam1, observedPixelOam);
            oamSearchPhase.setDebugAddressSpace(observedSearchOam);
        } else {
            pixelMachine.setDebugAddressSpaces(videoRam0, videoRam1, ppuOam);
            oamSearchPhase.setDebugAddressSpace(oamRam);
        }
    }

    /**
     * Prevents the performance timing cursor while debugger-local observation is attached.
     * Materialization is synchronous so an observer never sees a partially deferred PPU span.
     */
    public void setPerformanceObservationBlocked(boolean blocked) {
        disablePerformanceScanlineCursor();
        materializeSteadyTiming();
        performanceObservationBlocked = blocked;
    }

    /**
     * Publishes the Gameboy boot/compatibility boundary to the performance guard. A false value
     * is deliberately fail-closed and materializes any cursor already armed before the boundary.
     */
    public void setBootCompatibilityResolved(boolean resolved) {
        if (!resolved && bootCompatibilityResolved) {
            disablePerformanceScanlineCursor();
            materializeSteadyTiming();
        }
        bootCompatibilityResolved = resolved;
    }

    /**
     * Attaches the Gameboy-owned CGB VRAM-DMA controller. The timing cursor remains disabled
     * while a transfer owns or is about to own the PPU/CPU bus; standalone GPU fixtures leave
     * this unset and therefore retain their historical scalar behavior.
     */
    public void setHdma(Hdma hdma) {
        disablePerformanceScanlineCursor();
        materializeSteadyTiming();
        this.hdma = hdma;
    }

    public long getDebugPpuFrame() {
        return debugPpuFrame;
    }

    private static DebugPpuMode toDebugPpuMode(Mode mode) {
        return switch (mode) {
            case HBlank -> DebugPpuMode.HBLANK;
            case VBlank -> DebugPpuMode.VBLANK;
            case OamSearch -> DebugPpuMode.OAM_SEARCH;
            case PixelTransfer -> DebugPpuMode.PIXEL_TRANSFER;
        };
    }

    public boolean isLcdEnabled() {
        return lcdEnabled;
    }

    public Lcdc getLcdc() {
        exposeMutablePpuState();
        return lcdc;
    }

    public GpuRegisterValues getRegisters() {
        exposeMutablePpuState();
        return r;
    }

    /** Core observation helper that returns a value rather than a retained register alias. */
    public int getRegisterValueForCore(GpuRegister register) {
        materializeSteadyTiming();
        return r.get(register);
    }

    /** Core observation helper that returns LCDC by value rather than exposing its object. */
    public int getLcdcValueForCore() {
        materializeSteadyTiming();
        return lcdc.get();
    }

    /** Internal read-only alias retained by STAT; it never mutates PPU register storage. */
    GpuRegisterValues getRegistersForStat() {
        return r;
    }

    boolean isPixelWindowDisplayVisible() {
        return pixelMachine.isWindowDisplayVisible();
    }

    int getPixelWindowXVisible() {
        return pixelMachine.getWindowXVisible();
    }

    public boolean isGbc() {
        return gbc;
    }

    public boolean isDmgCompatMode() {
        return dmgCompatValue;
    }

    /** Captures raw physical PPU memories without applying CPU bus locks or DMA corruption. */
    public DebugGraphicsInspection captureDebugGraphicsInspection() {
        materializeSteadyTiming();
        DebugGraphicsHardwareMode hardwareMode = !gbc
                ? DebugGraphicsHardwareMode.DMG
                : dmgCompatValue
                        ? DebugGraphicsHardwareMode.CGB_COMPATIBILITY
                        : DebugGraphicsHardwareMode.CGB_NATIVE;
        return new DebugGraphicsInspection(
                hardwareMode,
                gbc ? r.get(VBK) & 1 : 0,
                lcdc.get(),
                r.get(BGP),
                r.get(OBP0),
                r.get(OBP1),
                gbc ? bgPalette.getByte(0xff68) : -1,
                gbc ? oamPalette.getByte(0xff6a) : -1,
                new DebugByteData(copyRam(videoRam0)),
                new DebugByteData(videoRam1 == null ? new byte[0] : copyRam(videoRam1)),
                new DebugByteData(copyAddressSpace(
                        oamRam, 0xfe00, DebugGraphicsInspection.OAM_LENGTH)),
                new DebugByteData(gbc ? copyPalette(bgPalette) : new byte[0]),
                new DebugByteData(gbc ? copyPalette(oamPalette) : new byte[0]));
    }

    private static byte[] copyRam(Ram ram) {
        int[] source = ram.getSpace();
        byte[] result = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = (byte) source[i];
        }
        return result;
    }

    private static byte[] copyAddressSpace(AddressSpace source, int address, int length) {
        byte[] result = new byte[length];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) source.getByte(address + i);
        }
        return result;
    }

    private static byte[] copyPalette(ColorPalette palette) {
        byte[] result = new byte[DebugGraphicsInspection.CGB_PALETTE_LENGTH];
        int offset = 0;
        for (int paletteIndex = 0; paletteIndex < 8; paletteIndex++) {
            for (int color : palette.getPalette(paletteIndex)) {
                result[offset++] = (byte) color;
                result[offset++] = (byte) (color >>> 8);
            }
        }
        return result;
    }

    /**
     * Captures the two DMG FIFO supplements without changing the pinned legacy mementos.
     * Native CGB hardware uses {@link ColorPixelFifo} and therefore has no supplement.
     */
    public DmgFifoRuntimeState captureDmgFifoRuntimeState() {
        materializeSteadyTiming();
        if (gbc) {
            return null;
        }
        return new DmgFifoRuntimeState(
                pixelTransferPhase.captureDmgFifoRuntimeState(),
                pixelMachine.captureDmgFifoRuntimeState());
    }

    public void validateDmgFifoRuntimeState(DmgFifoRuntimeState state) {
        if (gbc) {
            if (state != null) {
                throw new IllegalArgumentException("DMG FIFO state supplied for CGB hardware");
            }
            return;
        }
        if (state == null) {
            throw new IllegalArgumentException("DMG FIFO state is missing");
        }
        pixelTransferPhase.validateDmgFifoRuntimeState(state.timing());
        pixelMachine.validateDmgFifoRuntimeState(state.output());
    }

    public void restoreDmgFifoRuntimeState(DmgFifoRuntimeState state) {
        validateDmgFifoRuntimeState(state);
        materializeSteadyTiming();
        if (!gbc) {
            pixelTransferPhase.restoreDmgFifoRuntimeState(state.timing());
            pixelMachine.restoreDmgFifoRuntimeState(state.output());
        }
    }

    /** Service-free state for the timing and pixel-producing DMG FIFOs. */
    public record DmgFifoRuntimeState(
            DmgPixelFifo.RuntimeState timing,
            DmgPixelFifo.RuntimeState output) {
    }

    public ColorPalette getBgPalette() {
        exposeMutablePpuState();
        return bgPalette;
    }

    public Mode getMode() {
        return mode;
    }

    @Override
    public ComponentState<Gpu> captureState() {
        // Capture is observationally pure. In particular, RewindManager captures can happen
        // between any two master dots while the approximate renderer has already published a
        // complete line. Do not deopt the live cursor (which would shorten mode 3 and mutate
        // STAT/lock state); retain the coarse cursor and marker in the memento instead.
        materializeSteadyTiming();
        ComponentState<Ram> videoRam0Memento = videoRam0 instanceof Ram ? videoRam0.captureState() : null;
        ComponentState<Ram> videoRam1Memento = videoRam1 instanceof Ram ? videoRam1.captureState() : null;

        return new GpuState(videoRam0Memento, videoRam1Memento, display.captureState(), lcdc.captureState(), bgPalette.captureState(), oamPalette.captureState(), oamSearchPhase.captureState(), pixelTransferPhase.captureState(), pixelMachine.captureState(), r.captureState(), lcdEnabled, displayEnabledDelay, line, ticksInLine, firstLine, lcdEnableClockPhase, firstFrameAfterLcdEnable, pixelTransferDone, hblankIntFrom, mode0IntFrom, statModeLatchRephasedBySpeedSwitch, speedSwitchCompletedThisLine, lyReadLatchRephasedBySpeedSwitch, scxWrittenThisLine, doubleSpeedMode2DispatchStatTailThisLine, doubleSpeedMode2DispatchCrossedLineEdge, earlyScxStatTailThisLine, wyWrittenThisLine, lateDoubleSpeedLineZeroWindowEnable, lastCpuVramWriteTick, mode, capturePendingPpuWrites(), cpuVisiblePpuRegisters.clone(), performanceWindowLineCounter, performanceScanlineCursor, performanceScanlineLine, performanceScanlineEndTick);
    }

    @Override
    public ComponentState<Gpu> captureState(MachineStateCapture capture) {
        materializeSteadyTiming();
        ComponentState<Ram> videoRam0Memento =
                videoRam0 instanceof Ram ? videoRam0.captureState(capture) : null;
        ComponentState<Ram> videoRam1Memento =
                videoRam1 instanceof Ram ? videoRam1.captureState(capture) : null;

        return new GpuState(
                videoRam0Memento,
                videoRam1Memento,
                display.captureState(capture),
                lcdc.captureState(capture),
                bgPalette.captureState(capture),
                oamPalette.captureState(capture),
                oamSearchPhase.captureState(capture),
                pixelTransferPhase.captureState(capture),
                pixelMachine.captureState(capture),
                r.captureState(capture),
                lcdEnabled,
                displayEnabledDelay,
                line,
                ticksInLine,
                firstLine,
                lcdEnableClockPhase,
                firstFrameAfterLcdEnable,
                pixelTransferDone,
                hblankIntFrom,
                mode0IntFrom,
                statModeLatchRephasedBySpeedSwitch,
                speedSwitchCompletedThisLine,
                lyReadLatchRephasedBySpeedSwitch,
                scxWrittenThisLine,
                doubleSpeedMode2DispatchStatTailThisLine,
                doubleSpeedMode2DispatchCrossedLineEdge,
                earlyScxStatTailThisLine,
                wyWrittenThisLine,
                lateDoubleSpeedLineZeroWindowEnable,
                lastCpuVramWriteTick,
                mode,
                capturePendingPpuWrites(),
                capture.ints(cpuVisiblePpuRegisters),
                performanceWindowLineCounter,
                performanceScanlineCursor,
                performanceScanlineLine,
                performanceScanlineEndTick);
    }

    private List<PendingPpuWriteState> capturePendingPpuWrites() {
        return pendingPpuWrites.stream().map(PendingPpuWriteRuntime::captureState).toList();
    }

    @Override
    public void declareMachineStatePayloads(MachineStateCapture capture) {
        if (videoRam0 instanceof Ram) {
            videoRam0.declareMachineStatePayloads(capture);
        }
        if (videoRam1 instanceof Ram) {
            videoRam1.declareMachineStatePayloads(capture);
        }
        display.declareMachineStatePayloads(capture);
    }

    @Override
    public void restoreState(ComponentState<Gpu> state) {
        if (!(state instanceof GpuState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }

        // Preflight both dot machines before touching RAM, palettes, display, or the timing
        // skeleton.  The null output component is the released one-machine snapshot shape and
        // is valid only when the timing component itself carries a same-family full FIFO; the
        // PixelTransfer role check rejects a scalar fallback before any live mutation.
        pixelTransferPhase.validateStateForRestore(mem.pixelTransferPhaseMemento);
        pixelMachine.validateStateForRestore(
                mem.pixelMachineMemento != null
                        ? mem.pixelMachineMemento
                        : mem.pixelTransferPhaseMemento);
        validatePerformanceCursorState(mem);

        // Candidate dot-machine shapes are now proven. Canonicalize the current private cursor
        // only after that preflight; the incoming state will replace both machines below.
        disablePerformanceScanlineCursor();
        // Canonicalize only the current target cursor. The incoming direct-line marker and
        // cursor are restored below after the component state has been installed.
        performanceScanlineLine = false;
        materializeSteadyTiming();

        if (videoRam0 instanceof Ram) {
            ((Ram) videoRam0).restoreState(mem.videoRam0Memento);
        }
        if (videoRam1 instanceof Ram) {
            ((Ram) videoRam1).restoreState(mem.videoRam1Memento);
        }

        display.restoreState(mem.displayMemento);
        lcdc.restoreState(mem.lcdcMemento);
        bgPalette.restoreState(mem.bgPaletteMemento);
        oamPalette.restoreState(mem.oamPaletteMemento);
        oamSearchPhase.restoreState(mem.oamSearchPhaseMemento);
        pixelTransferPhase.restoreState(mem.pixelTransferPhaseMemento);
        // snapshots from older versions carry only one dot machine; the pixel machine
        // then restarts from the skeleton's state (one partially wrong line at most)
        pixelMachine.restoreState(
                mem.pixelMachineMemento != null ? mem.pixelMachineMemento : mem.pixelTransferPhaseMemento);
        r.restoreState(mem.rMemento);

        this.lcdEnabled = mem.lcdEnabled;
        this.displayEnabledDelay = mem.displayEnabledDelay;
        this.line = mem.line;
        this.ticksInLine = mem.ticksInLine;
        this.firstLine = mem.firstLine;
        this.lcdEnableClockPhase = mem.lcdEnableClockPhase;
        this.firstFrameAfterLcdEnable = mem.firstFrameAfterLcdEnable;
        this.pixelTransferDone = mem.pixelTransferDone;
        this.hblankIntFrom = mem.hblankIntFrom;
        this.mode0IntFrom = mem.mode0IntFrom;
        this.statModeLatchRephasedBySpeedSwitch = mem.statModeLatchRephasedBySpeedSwitch;
        this.speedSwitchCompletedThisLine = mem.speedSwitchCompletedThisLine;
        this.lyReadLatchRephasedBySpeedSwitch = mem.lyReadLatchRephasedBySpeedSwitch;
        this.scxWrittenThisLine = mem.scxWrittenThisLine;
        this.doubleSpeedMode2DispatchStatTailThisLine =
                mem.doubleSpeedMode2DispatchStatTailThisLine;
        this.doubleSpeedMode2DispatchCrossedLineEdge =
                mem.doubleSpeedMode2DispatchCrossedLineEdge;
        this.earlyScxStatTailThisLine = mem.earlyScxStatTailThisLine;
        this.wyWrittenThisLine = mem.wyWrittenThisLine;
        this.lateDoubleSpeedLineZeroWindowEnable =
                mem.lateDoubleSpeedLineZeroWindowEnable;
        this.lastCpuVramWriteTick = mem.lastCpuVramWriteTick;
        this.cpuRetiringInstructionForHdma = false;
        this.cpuLyReadAcrossLineEdge = false;
        this.mode = mem.mode;
        performanceScanlineCursor = mem.performanceScanlineCursor;
        performanceScanlineEndTick = mem.performanceScanlineEndTick;
        performanceScanlineLine = mem.performanceScanlineLine;
        this.performanceWindowLineCounter = mem.performanceWindowLineCounter;
        pendingPpuWrites.clear();
        if (mem.pendingPpuWrites != null) {
            mem.pendingPpuWrites.stream()
                    .map(PendingPpuWriteRuntime::restoreState)
                    .forEach(pendingPpuWrites::add);
        }
        Arrays.fill(cpuVisiblePpuRegisters, -1);
        if (mem.cpuVisiblePpuRegisters != null) {
            System.arraycopy(mem.cpuVisiblePpuRegisters, 0, cpuVisiblePpuRegisters, 0,
                    Math.min(mem.cpuVisiblePpuRegisters.length,
                            cpuVisiblePpuRegisters.length));
        }

        if (mode == Mode.PixelTransfer) {
            phase = pixelTransferPhase;
        } else {
            phase = oamSearchPhase;
        }
        // The generation is deliberately transient: it only invalidates the derived
        // STAT timing snapshot and is not part of emulated state. In a full-machine
        // restore, the later SpeedMode restore synchronously refreshes this GPU's timing
        // mode; for a standalone GPU restore, the external SpeedMode remains authoritative.
        timingGeneration++;
    }

    private void validatePerformanceCursorState(GpuState state) {
        if (state.performanceWindowLineCounter < -1) {
            throw new IllegalArgumentException("Invalid PERFORMANCE window line counter");
        }
        int lineLength = state.firstLine ? 455 : 456;
        if (state.performanceScanlineCursor) {
            if (state.mode != Mode.PixelTransfer || !state.performanceScanlineLine
                    || state.performanceScanlineEndTick <= state.ticksInLine
                    || state.performanceScanlineEndTick >= lineLength) {
                throw new IllegalArgumentException("Invalid active PERFORMANCE scanline cursor");
            }
        } else if (state.performanceScanlineEndTick != 0) {
            throw new IllegalArgumentException("Inactive PERFORMANCE cursor retains an endpoint");
        }
        if (state.performanceScanlineLine
                && state.mode != Mode.PixelTransfer && state.mode != Mode.HBlank) {
            throw new IllegalArgumentException("Invalid PERFORMANCE line marker");
        }
    }

    private record GpuState(ComponentState<Ram> videoRam0Memento, ComponentState<Ram> videoRam1Memento,
                              ComponentState<Display> displayMemento, ComponentState<Lcdc> lcdcMemento,
                              ComponentState<ColorPalette> bgPaletteMemento, ComponentState<ColorPalette> oamPaletteMemento,
                              ComponentState<OamSearch> oamSearchPhaseMemento,
                              ComponentState<PixelTransfer> pixelTransferPhaseMemento,
                              ComponentState<PixelTransfer> pixelMachineMemento,
                              ComponentState<GpuRegisterValues> rMemento, boolean lcdEnabled, int displayEnabledDelay,
                              int line, int ticksInLine, boolean firstLine,
                              boolean lcdEnableClockPhase, boolean firstFrameAfterLcdEnable,
                              boolean pixelTransferDone,
                              int hblankIntFrom, int mode0IntFrom,
                              boolean statModeLatchRephasedBySpeedSwitch,
                              boolean speedSwitchCompletedThisLine,
                              boolean lyReadLatchRephasedBySpeedSwitch,
                              boolean scxWrittenThisLine,
                              boolean doubleSpeedMode2DispatchStatTailThisLine,
                              boolean doubleSpeedMode2DispatchCrossedLineEdge,
                              boolean earlyScxStatTailThisLine,
                              boolean wyWrittenThisLine,
                              boolean lateDoubleSpeedLineZeroWindowEnable,
                              int lastCpuVramWriteTick, Mode mode,
                              List<PendingPpuWriteState> pendingPpuWrites,
                              int[] cpuVisiblePpuRegisters,
                              int performanceWindowLineCounter,
                              boolean performanceScanlineCursor,
                              boolean performanceScanlineLine,
                              int performanceScanlineEndTick) implements ComponentState<Gpu> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record GpuMemento(Memento<Ram> videoRam0Memento, Memento<Ram> videoRam1Memento,
                              Memento<Display> displayMemento, Memento<Lcdc> lcdcMemento,
                              Memento<ColorPalette> bgPaletteMemento, Memento<ColorPalette> oamPaletteMemento,
                              Memento<OamSearch> oamSearchPhaseMemento,
                              Memento<PixelTransfer> pixelTransferPhaseMemento,
                              Memento<PixelTransfer> pixelMachineMemento,
                              Memento<GpuRegisterValues> rMemento, boolean lcdEnabled, int displayEnabledDelay,
                              int line, int ticksInLine, boolean firstLine,
                              boolean lcdEnableClockPhase, boolean firstFrameAfterLcdEnable,
                              boolean pixelTransferDone,
                              int hblankIntFrom, int mode0IntFrom,
                              boolean statModeLatchRephasedBySpeedSwitch,
                              boolean speedSwitchCompletedThisLine,
                              boolean lyReadLatchRephasedBySpeedSwitch,
                              boolean scxWrittenThisLine,
                              boolean doubleSpeedMode2DispatchStatTailThisLine,
                              boolean doubleSpeedMode2DispatchCrossedLineEdge,
                              boolean earlyScxStatTailThisLine,
                              boolean wyWrittenThisLine,
                              boolean lateDoubleSpeedLineZeroWindowEnable,
                              int lastCpuVramWriteTick, Mode mode,
                              List<PendingPpuWrite> pendingPpuWrites,
                              int[] cpuVisiblePpuRegisters) implements Memento<Gpu> {
    }

    private record PendingPpuWriteRuntime(int address, int value, int mask,
                                          int remainingDots) {

        private PendingPpuWriteState captureState() {
            return new PendingPpuWriteState(address, value, mask, remainingDots);
        }

        private static PendingPpuWriteRuntime restoreState(PendingPpuWriteState state) {
            return new PendingPpuWriteRuntime(
                    state.address(), state.value(), state.mask(), state.remainingDots());
        }
    }

    private record PendingPpuWriteState(int address, int value, int mask,
                                        int remainingDots) {
    }

    /** Importer-only compatibility leaf record for released local snapshots. */
    private record PendingPpuWrite(int address, int value, int mask,
                                   int remainingDots) implements Serializable {
    }
}
