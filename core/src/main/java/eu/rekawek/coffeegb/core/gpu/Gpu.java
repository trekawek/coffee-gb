package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.gpu.phase.*;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.DmaOamAddressSpace;
import eu.rekawek.coffeegb.core.memory.Ram;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static eu.rekawek.coffeegb.core.gpu.GpuRegister.*;

public class Gpu implements AddressSpace, Serializable, Originator<Gpu> {

    private static final int LCDC_ADDRESS = 0xff40;

    private static final int LAST_STANDARD_REGISTER_ADDRESS = 0xff4b;

    /** Pixel-domain synchronization delay for selected DMG register latches. */
    private static final int PPU_WRITE_DELAY_DOTS = 4;

    private final Ram videoRam0;

    private final Ram videoRam1;

    private final AddressSpace oamRam;

    private final Display display;

    private final Dma dma;

    private final Lcdc lcdc;

    private final boolean gbc;

    private final eu.rekawek.coffeegb.core.cpu.SpeedMode speedMode;

    private final ColorPalette bgPalette;

    private final ColorPalette oamPalette;

    private final OamSearch oamSearchPhase;

    private final PixelTransfer pixelTransferPhase;

    // second dot machine running one machine cycle behind the skeleton; it produces the
    // pixels (its reads land on the hardware dots) while pixelTransferPhase keeps the
    // calibrated CPU-visible mode/STAT/lock timing. They diverge only when a mid-line
    // write changes a stall length within the 4-tick skew.
    private final PixelTransfer pixelMachine;

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

    // Switching the CGB CPU clock remaps the PPU timestamp and rephases the CPU-side
    // STAT mode latch. Until that happens, the boot-time latch has its five-dot tail.
    private boolean statModeLatchRephasedBySpeedSwitch;

    // A line-scoped SCX write makes a no-window line follow the dynamic shifted
    // pipeline instead of the steady-line fixed STAT release.
    private boolean scxWrittenThisLine;

    // A double-speed mode-2 interrupt is accepted on an alternate CPU/PPU phase.
    // Its handler retains the object-free mode-3 prediction later in the line.
    private boolean doubleSpeedMode2DispatchStatTailThisLine;

    // A fine-SCX write that crosses the startup comparator on that captured phase
    // leaves the readable mode latch on the extended prediction path.
    private boolean earlyScxStatTailThisLine;

    // Distinguish a delayed-WY comparator race from LCDC/WX changes that made only
    // the shifted output machine start a window on this line.
    private boolean wyWrittenThisLine;

    // A CPU VRAM write holds its arbitration request through the immediately
    // following read cycle. This matters at the mode-3/mode-0 hand-off, where a
    // standalone read and a write-then-read sequence see different slots.
    private int lastCpuVramWriteTick = Integer.MIN_VALUE;

    private transient boolean cpuRetiringInstructionForHdma;

    /**
     * Coffee GB keeps a calibrated CPU-visible timing skeleton and a pixel-producing
     * dot machine four dots behind it. Selected DMG register slices cross into that
     * second clock domain through their own latches; CPU reads still see the bus value
     * immediately. This queue models that crossing without delaying the CPU or timer.
     */
    private final List<PendingPpuWrite> pendingPpuWrites = new ArrayList<>();

    private final int[] cpuVisiblePpuRegisters =
            new int[LAST_STANDARD_REGISTER_ADDRESS - LCDC_ADDRESS + 1];

    private boolean directOamReadCorruptionThisTick;

    private boolean suppressNextDirectOamReadCorruption;

    private boolean directOamWriteCorruptionThisTick;

    private boolean suppressNextDirectOamWriteCorruption;

    public Gpu(Display display, Dma dma, Ram oamRam, VRamTransfer vRamTransfer, StatRegister statRegister, boolean gbc, eu.rekawek.coffeegb.core.cpu.SpeedMode speedMode) {
        this.statRegister = statRegister;
        Arrays.fill(cpuVisiblePpuRegisters, -1);
        this.display = display;
        this.r = new GpuRegisterValues();
        this.lcdc = new Lcdc();
        this.gbc = gbc;
        this.speedMode = speedMode;
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
        AddressSpace ppuOam = new DmaOamAddressSpace(oamRam, dma);

        this.bgPalette = new ColorPalette(0xff68);
        this.oamPalette = new ColorPalette(0xff6a);
        if (gbc) {
            oamPalette.initializeCgbBootValues();
        }

        this.oamSearchPhase = new OamSearch(oamRam, dma, lcdc, r);
        this.pixelTransferPhase = new PixelTransfer(new Display(gbc), videoRam0, videoRam1, ppuOam, lcdc, r, gbc, bgPalette, oamPalette, oamSearchPhase.getSprites(), null, speedMode, 0);
        this.pixelMachine = new PixelTransfer(display, videoRam0, videoRam1, ppuOam, lcdc, r, gbc, bgPalette, oamPalette, oamSearchPhase.getSprites(), vRamTransfer, speedMode, 4);

        this.mode = Mode.OamSearch;
        this.phase = oamSearchPhase.start();
    }

    private AddressSpace getAddressSpace(int address) {
        if (videoRam0.accepts(address)) {
            return isVramAvailableForCpu() ? getVideoRam() : null;
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
        if (gbc && (r.get(VBK) & 1) == 1) {
            return videoRam1;
        } else {
            return videoRam0;
        }
    }

    public Ram getVideoRam0() {
        return videoRam0;
    }

    public Ram getVideoRam1() {
        return videoRam1;
    }

    @Override
    public boolean accepts(int address) {
        return videoRam0.accepts(address) || oamRam.accepts(address) || lcdc.accepts(address)
                || r.accepts(address) || (gbc && (bgPalette.accepts(address) || oamPalette.accepts(address)));
    }

    @Override
    public void setByte(int address, int value) {
        cancelPendingPpuWrites(address);
        cancelDelayedPixelWindowWrite(address);
        setByteImmediately(address, value);
    }

    @Override
    public void setByteFromCpu(int address, int value) {
        scheduleDmgPixelWindowWrite(address, value);
        if (address == SCX.getAddress() && lcdEnabled && line < 144) {
            boolean dmgStartupEdge = !gbc
                    && pixelTransferPhase.getPosition() == -16
                    && pixelMachine.getPosition() == -16;
            boolean doubleSpeedStartupEdge = gbc && speedMode.getSpeedMode() == 2
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
        pendingPpuWrites.add(new PendingPpuWrite(
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
                    : speedMode.getSpeedMode() == 2 ? 4 : 6;
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
                getVideoRam().setByte(address, value);
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
        if (address >= LCDC_ADDRESS && address <= LAST_STANDARD_REGISTER_ADDRESS) {
            int cpuVisible = cpuVisiblePpuRegisters[address - LCDC_ADDRESS];
            if (cpuVisible >= 0) {
                return cpuVisible;
            }
        }
        if (address == LY.getAddress()) {
            return getVisibleLy();
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
            // Before the CPU/PPU clock mux has moved, the palette latch closes with
            // the mode-3 skeleton. After a speed switch it follows the fetch-start
            // phase instead. At double speed the first internal transfer dot is
            // occupied, followed by one accessible CPU cycle before the steady lock.
            if (!statModeLatchRephasedBySpeedSwitch) {
                return false;
            }
            int position = pixelTransferPhase.getPosition();
            return speedMode.getSpeedMode() == 2
                    ? position > -16 && position < -4
                    : position < -4;
        }
        if (mode == Mode.OamSearch
                && speedMode.getSpeedMode() == 2
                && ticksInLine >= 79) {
            // A double-speed CPU can sample the closing latch one dot before the
            // internal mode-3 transition.
            return false;
        }
        if (!firstLine
                && speedMode.getSpeedMode() == 1
                && !pixelTransferPhase.hasObjectsOnLine()
                && !pixelTransferPhase.hasActivatedWindowOnLine()) {
            // On steady BG-only normal-speed lines, the release is quantized by fine
            // SCX rather than following the variable internal HBlank edge.
            return mode != Mode.HBlank
                    || ticksInLine >= 258 + ((r.get(SCX) & 0x04) != 0 ? 4 : 0);
        }
        // Other lines open eight dots after the mode-0 edge at normal speed and six
        // dots after it at double speed.
        int handoffDots = 4 + 4 / speedMode.getSpeedMode();
        return mode != Mode.HBlank || ticksInLine >= hblankIntFrom + handoffDots;
    }

    public Mode tick() {
        directOamReadCorruptionThisTick = false;
        suppressNextDirectOamReadCorruption = false;
        directOamWriteCorruptionThisTick = false;
        suppressNextDirectOamWriteCorruption = false;
        if (displayEnabledDelay > 0 && --displayEnabledDelay == 0) {
            display.enableLcd();
        }

        if (!lcdEnabled) {
            return null;
        }

        advancePendingPpuWrites();
        pixelMachine.advanceDelayedWindowWrites();

        // write-conflict mixes settle and the LCD output stage advances every tick,
        // in all modes (the last pixels of a line leave the delay line during HBlank)
        r.tickConflicts();
        lcdc.tickConflicts();
        boolean earlyWindowFrameEdge = !gbc || speedMode.getSpeedMode() == 1;
        if (earlyWindowFrameEdge && line == 153 && ticksInLine == 454) {
            pixelTransferPhase.resetWindowLineCounter();
            pixelMachine.resetWindowLineCounter();
        }
        pixelTransferPhase.checkWindowY(line, ticksInLine);
        pixelMachine.checkWindowY(line, ticksInLine);
        pixelMachine.outputTick();
        pixelMachine.machineTick();

        Mode oldMode = mode;
        ticksInLine++;
        int lineLength = firstLine ? 455 : 456;
        oamSearchPhase.trackDmaSource(ticksInLine == lineLength ? 0 : ticksInLine);
        // the line started by enabling the LCD is one tick shorter: its grid starts at
        // the LCDC write itself, while the machine-cycle-locked line grid starts one
        // tick later (lcdon_timing-GS vs the steady-state line phase)
        if (ticksInLine == lineLength) {
            ticksInLine = 0;
            lastCpuVramWriteTick = Integer.MIN_VALUE;
            firstLine = false;
            pixelTransferDone = false;
            hblankIntFrom = Integer.MAX_VALUE;
            mode0IntFrom = Integer.MAX_VALUE;
            scxWrittenThisLine = false;
            doubleSpeedMode2DispatchStatTailThisLine = false;
            earlyScxStatTailThisLine = false;
            wyWrittenThisLine = false;
            line++;
            if (line == 154) {
                line = 0;
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
                    }
                    break;

                case PixelTransfer:
                    if (pixelTransferDone) {
                        pixelTransferDone = false;
                        mode = Mode.HBlank;
                    } else {
                        int oldPosition = pixelTransferPhase.getPosition();
                        boolean terminalWindowAlreadyStarted =
                                pixelTransferPhase.hasCgbTerminalWindowStarted();
                        boolean active = phase.tick();
                        if (!terminalWindowAlreadyStarted
                                && pixelTransferPhase.hasCgbTerminalWindowStarted()
                                && pixelTransferPhase.hasSpriteAtTerminalPredictionEdge()
                                && mode0IntFrom != Integer.MAX_VALUE) {
                            // The X=166 M0 event is independent of the later X=167
                            // STAT/bus prediction. When both comparators collide, its
                            // CPU-visible event crosses two dots after Coffee's early
                            // right-edge prediction has been captured. That prediction
                            // always crosses X=158->159 one tick before this terminal
                            // X=159->160 commit, so mode0IntFrom is already finite.
                            mode0IntFrom += 2;
                        }
                        if (mode0IntFrom == Integer.MAX_VALUE
                                && pixelTransferPhase.hasSpriteAtMode0PredictionEdge()
                                && oldPosition <= 158
                                && pixelTransferPhase.getPosition() > 158) {
                            mode0IntFrom = ticksInLine + 3;
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

        if (oldMode == mode) {
            return null;
        } else {
            return mode;
        }
    }

    /** Rephases the CGB CPU-readable STAT latch when the CPU clock mux changes. */
    public void onSpeedSwitch() {
        statModeLatchRephasedBySpeedSwitch = true;
    }

    /** Captures the CPU/PPU phase selected when a double-speed mode-2 IRQ is accepted. */
    public void onDoubleSpeedMode2Dispatch() {
        doubleSpeedMode2DispatchStatTailThisLine = true;
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
            return 2 / speedMode.getSpeedMode() - 1;
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
            PendingPpuWrite pending = pendingPpuWrites.get(i);
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
                pendingPpuWrites.set(i, new PendingPpuWrite(
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
        if (!lcdEnabled) {
            return 0;
        }
        if (line == 153) {
            if (gbc) {
                int lastLyTicks = speedMode.getSpeedMode() == 2 ? 2 : 4;
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
     * PPU mode bits as visible in the STAT register.
     */
    public int getVisibleStatMode() {
        if (!lcdEnabled) {
            return 0;
        }
        if (gbc && !speedMode.isDmgCompat()) {
            // Gambatte's frame-tail getStat window: native CGB exposes a one-dot
            // mode-0 gap at normal speed, then the line-zero mode-2 latch. Double
            // speed has no mode-0 gap.
            if (line == 153) {
                if (speedMode.getSpeedMode() == 1
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
            if (firstLine && speedMode.getSpeedMode() == 2 && ticksInLine >= 453) {
                return Mode.OamSearch.ordinal();
            }
        }
        // The CGB's CPU-readable latch projects the next line's mode during the
        // final two dots, independently of compatibility or CPU speed.
        int nextLineModeTick = 454;
        if (gbc && ticksInLine >= nextLineModeTick) {
            if (line < 143 || (line == 153 && !speedMode.isDmgCompat())) {
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
        if (gbc && speedMode.isDmgCompat() && mode == Mode.PixelTransfer) {
            return Mode.PixelTransfer.ordinal();
        }
        if (gbc && speedMode.isDmgCompat() && mode == Mode.HBlank
                && ticksInLine >= 250 && lcdc.isBgAndWindowDisplay()) {
            return Mode.HBlank.ordinal();
        }
        if (gbc && !speedMode.isDmgCompat() && speedMode.getSpeedMode() == 1
                && mode == Mode.PixelTransfer && pixelTransferDone) {
            return Mode.HBlank.ordinal();
        }
        boolean dynamicWindowTail = pixelMachine.hasActivatedWindowOnLine()
                || (pixelTransferPhase.hasActivatedWindowOnLine()
                && !pixelTransferPhase.isWindowActive());
        int mode3ReadAhead = dynamicWindowTail ? 2 : 0;
        if (gbc && speedMode.getSpeedMode() == 1
                && mode == Mode.HBlank
                && ticksInLine + mode3ReadAhead < hblankIntFrom
                && !pixelTransferPhase.hasObjectsOnLine()) {
            // Gambatte's STAT mux compares cc+2 with the predicted mode-0 edge. The
            // steady background path already bakes that lookahead into its calibrated
            // latch; a dynamically started (or subsequently disabled) window exposes
            // the raw HBlank prediction and therefore needs it here.
            return Mode.PixelTransfer.ordinal();
        }
        if (gbc && speedMode.getSpeedMode() == 2
                && mode == Mode.HBlank
                && ticksInLine < hblankIntFrom
                && ticksInLine + 2 >= hblankIntFrom
                && pixelMachine.hasActivatedWindowOnLine()
                && !pixelMachine.isWindowActive()) {
            // A disabled window still owns the dynamic prediction, but double-speed
            // CPU reads release its mode-3 latch two dots before the mode-0 edge.
            return Mode.HBlank.ordinal();
        }
        if (gbc && speedMode.getSpeedMode() == 2
                && ((mode == Mode.PixelTransfer && pixelTransferDone)
                || (mode == Mode.HBlank && ticksInLine < hblankIntFrom))) {
            return Mode.PixelTransfer.ordinal();
        }
        // A double-speed mode-2 interrupt enters its handler on the alternate CPU
        // phase. Gambatte's cc+2 comparison then retains the object-free X=167
        // prediction through the final four dots, independently of the mode-0 IRQ.
        if (gbc && speedMode.getSpeedMode() == 2
                && doubleSpeedMode2DispatchStatTailThisLine
                && mode == Mode.HBlank
                && !pixelTransferPhase.hasObjectsOnLine()
                && !pixelTransferPhase.hasActivatedWindowOnLine()
                && ticksInLine < hblankIntFrom + 4) {
            return Mode.PixelTransfer.ordinal();
        }
        // A fine-SCX write at startup can move that captured prediction four dots
        // farther without changing the timing skeleton's already scheduled M0 edge.
        if (gbc && speedMode.getSpeedMode() == 2
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
        int terminalWindowReadTail = speedMode.getSpeedMode() == 2
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
        if (gbc && speedMode.getSpeedMode() == 1
                && mode == Mode.PixelTransfer
                && pixelTransferPhase.hasObjectsOnLine()
                && lcdc.isObjDisplayEffective()
                && pixelTransferPhase.getPosition() >= 160) {
            return Mode.HBlank.ordinal();
        }
        if (gbc && speedMode.getSpeedMode() == 1
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
        if (gbc && speedMode.getSpeedMode() == 1
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
            readablePixelEnd = speedMode.getSpeedMode() == 2
                    && !pixelTransferPhase.hasObjectsOnLine()
                    && pixelMachine.hasActivatedWindowOnLine()
                    ? 160
                    : 158;
        } else if (speedMode.getSpeedMode() == 1
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
                || (speedMode.getSpeedMode() == 2
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
        if (gbc && speedMode.getSpeedMode() == 2
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
        if (gbc && speedMode.getSpeedMode() == 2
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
        if (gbc && speedMode.isDmgCompat() && mode == Mode.HBlank
                && ticksInLine <= hblankIntFrom
                && lcdc.isObjDisplayEffective()
                && pixelTransferPhase.hasObjectsOnLine()
                && ((r.get(SCX) & 7) != 0 || lcdc.isWindowDisplay())) {
            return Mode.PixelTransfer.ordinal();
        }
        // A scanline containing enabled objects, combined with fractional scroll or the
        // window, can leave the readable mode-3 tail asserted through the mode-0
        // interrupt edge even after the internal phase released the VRAM/OAM locks
        // (Misc.-GB-Tests' NOP-shifted sprite timing variants). Keep this separate from
        // `mode`: the lock handoff and HBlank interrupt retain their calibrated timings.
        // Vertically inactive objects and sprite-disabled lines do not produce this tail
        // (GBMicrotest), while selected objects beyond the right edge still do.
        if (!gbc && mode == Mode.HBlank
                && ticksInLine <= hblankIntFrom
                && lcdc.isObjDisplayEffective()
                && pixelTransferPhase.hasObjectsOnLine()
                && ((r.get(SCX) & 7) != 0 || lcdc.isWindowDisplay())) {
            return Mode.PixelTransfer.ordinal();
        }
        return mode.ordinal();
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
        if (gbc && speedMode.getSpeedMode() == 1
                && !firstLine && mode == Mode.OamSearch && ticksInLine == 78) {
            return Mode.OamSearch.ordinal();
        }
        if (gbc && speedMode.getSpeedMode() == 2
                && statModeLatchRephasedBySpeedSwitch
                && mode == Mode.HBlank && ticksInLine >= hblankIntFrom
                && pixelMachine.hasActivatedWindowOnLine()
                && pixelMachine.getPosition() < 160) {
            return Mode.PixelTransfer.ordinal();
        }
        return -1;
    }

    /**
     * The mode-2 STAT source is a short pulse during the final machine cycle of the
     * preceding line. At the frame boundary native CGB exposes it in line 153's tail;
     * DMG and compatibility mode expose it during the first four ticks of line 0.
     */
    public boolean isMode2IntWindow() {
        if (!lcdEnabled) {
            return false;
        }
        return (line < 144 && ticksInLine >= getEarlyLineEdgeTick())
                || (gbc && !speedMode.isDmgCompat()
                && line == 153 && ticksInLine >= 454)
                || ((!gbc || speedMode.isDmgCompat())
                && !firstLine && line == 0 && ticksInLine < 4);
    }

    /**
     * The "mode 0" STAT interrupt condition rises with the visible mode 0, quantized to
     * 4-tick steps of the SCX scroll delay, and stays active until the end of the line.
     */
    public boolean isMode0IntWindow() {
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
        return lcdEnabled && line < 144 && ticksInLine == getMode0InterruptTick() + 2;
    }

    int getMode0InterruptTick() {
        // The predictive edge is a CGB timing feature. DMG mode 0 follows the
        // completed pixel-transfer latch, including its sprite-fetch tail.
        return gbc ? mode0IntFrom : hblankIntFrom;
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
        if (!lcdEnabled) {
            return true;
        }
        int firstLineOamOpenTicks = gbc && write && speedMode.getSpeedMode() == 2
                ? 77 : 79;
        if (firstLine && ticksInLine < firstLineOamOpenTicks) {
            return true;
        }
        if (gbc && speedMode.getSpeedMode() == 2
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
                        : speedMode.getSpeedMode() == 1 && !firstLine
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
        boolean cgbNormalSpeedLineEdgeLock = gbc && speedMode.getSpeedMode() == 1
                && !firstLine && ticksInLine >= cgbNormalSpeedLineEdgeTick;
        boolean cgbDoubleSpeedLineEdgeLock = gbc && speedMode.getSpeedMode() == 2
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
        return 4 / speedMode.getSpeedMode();
    }

    public int getCoincidenceReleaseTick() {
        if (firstLine) {
            // At the end of the shortened enable line Gambatte's getLycCmpLy has
            // entered its final non-readable comparison slot: dot 451 normal speed,
            // dot 453 double speed (the `> releaseTick` CGB rule below maps to 452).
            return speedMode.getSpeedMode() == 2 ? 452 : getEarlyLineEdgeTick();
        }
        if (!gbc) {
            return getEarlyLineEdgeTick();
        }
        if (speedMode.isDmgCompat()) {
            return 452;
        }
        // At double speed FF44 has already advanced, but the old coincidence
        // result remains readable through dot 454 in its separate STAT latch.
        return speedMode.getSpeedMode() == 2 ? 454 : getVisibleLyLineEdgeTick();
    }

    private int getVisibleLyLineEdgeTick() {
        if (!gbc || firstLine) {
            return getEarlyLineEdgeTick();
        }
        if (speedMode.isDmgCompat()) {
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
        if (!write && cpuRetiringInstructionForHdma && gbc
                && speedMode.getSpeedMode() == 2 && mode == Mode.HBlank) {
            return true;
        }
        if (firstLine && gbc && ticksInLine < 84) {
            // The display-enable VRAM latch closes one dot earlier at double speed;
            // it is distinct from both the CPU-readable STAT and palette latches.
            return ticksInLine < (speedMode.getSpeedMode() == 2 ? 79 : 80);
        }
        if (mode == Mode.PixelTransfer) {
            if (!gbc) {
                return false;
            }
            int position = pixelTransferPhase.getPosition();
            // The final VRAM fetch slot is returned to the CPU before the internal
            // mode transition. Fine SCX moves that slot by the corresponding number
            // of output dots.
            if (speedMode.getSpeedMode() == 1
                    && position >= 158 - (r.get(SCX) & 0x07)
                    && (write || followsCpuVramWrite())) {
                return true;
            }
            if (!statModeLatchRephasedBySpeedSwitch) {
                return false;
            }
            // Around fetch start, a rephased CPU clock can land in one otherwise-idle
            // VRAM arbitration slot. Normal and double speed expose different slots.
            return speedMode.getSpeedMode() == 2
                    ? position <= -16
                    : position >= -8 && position < -4;
        }
        if (gbc && !write && mode == Mode.HBlank) {
            if (followsCpuVramWrite()) {
                return true;
            }
            int handoffTick;
            if (pixelTransferPhase.hasObjectsOnLine()) {
                handoffTick = hblankIntFrom;
            } else if (speedMode.getSpeedMode() == 1 && !firstLine) {
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
            return speedMode.getSpeedMode() != 2 || ticksInLine < 79;
        }
        if (gbc && write && mode == Mode.OamSearch
                && speedMode.getSpeedMode() == 2 && ticksInLine >= 79) {
            return false;
        }
        return true;
    }

    private boolean followsCpuVramWrite() {
        int readDelay = speedMode.getSpeedMode() == 2 ? 4 : 8;
        return lastCpuVramWriteTick != Integer.MIN_VALUE
                && ticksInLine - lastCpuVramWriteTick == readDelay;
    }

    /** Keeps the VRAM slot owned by a CPU instruction that won HDMA arbitration. */
    public void setCpuRetiringInstructionForHdma(boolean retiring) {
        cpuRetiringInstructionForHdma = retiring;
    }

    private void setLcdc(int value) {
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
        this.pixelTransferDone = false;
        this.hblankIntFrom = Integer.MAX_VALUE;
        this.mode0IntFrom = Integer.MAX_VALUE;
        this.scxWrittenThisLine = false;
        this.doubleSpeedMode2DispatchStatTailThisLine = false;
        this.earlyScxStatTailThisLine = false;
        this.wyWrittenThisLine = false;
        this.lastCpuVramWriteTick = Integer.MIN_VALUE;
        this.mode = Mode.HBlank;
        this.lcdEnabled = false;
        this.displayEnabledDelay = 0;
        statRegister.onLcdDisabled();
        pixelMachine.clearOutput();
        pixelMachine.stop();
        display.disableLcd();
    }

    private void enableLcd() {
        if (lcdEnabled) {
            return;
        }
        this.line = 0;
        // the line grid is locked to the machine-cycle phase: enabling the LCD starts
        // the line one tick after the LCDC write, matching the power-on grid
        this.ticksInLine = -1;
        this.firstLine = true;
        this.lcdEnableClockPhase = true;
        this.pixelTransferDone = false;
        this.hblankIntFrom = Integer.MAX_VALUE;
        this.mode0IntFrom = Integer.MAX_VALUE;
        this.scxWrittenThisLine = false;
        this.doubleSpeedMode2DispatchStatTailThisLine = false;
        this.earlyScxStatTailThisLine = false;
        this.wyWrittenThisLine = false;
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
    }

    public boolean isLcdEnabled() {
        return lcdEnabled;
    }

    public Lcdc getLcdc() {
        return lcdc;
    }

    public GpuRegisterValues getRegisters() {
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
        return speedMode.isDmgCompat();
    }

    public ColorPalette getBgPalette() {
        return bgPalette;
    }

    public Mode getMode() {
        return mode;
    }

    @Override
    public Memento<Gpu> saveToMemento() {
        Memento<Ram> videoRam0Memento = videoRam0 instanceof Ram ? videoRam0.saveToMemento() : null;
        Memento<Ram> videoRam1Memento = videoRam1 instanceof Ram ? videoRam1.saveToMemento() : null;

        return new GpuMemento(videoRam0Memento, videoRam1Memento, display.saveToMemento(), lcdc.saveToMemento(), bgPalette.saveToMemento(), oamPalette.saveToMemento(), oamSearchPhase.saveToMemento(), pixelTransferPhase.saveToMemento(), pixelMachine.saveToMemento(), r.saveToMemento(), lcdEnabled, displayEnabledDelay, line, ticksInLine, firstLine, lcdEnableClockPhase, pixelTransferDone, hblankIntFrom, mode0IntFrom, statModeLatchRephasedBySpeedSwitch, scxWrittenThisLine, doubleSpeedMode2DispatchStatTailThisLine, earlyScxStatTailThisLine, wyWrittenThisLine, lastCpuVramWriteTick, mode, new ArrayList<>(pendingPpuWrites), cpuVisiblePpuRegisters.clone());
    }

    @Override
    public void restoreFromMemento(Memento<Gpu> memento) {
        if (!(memento instanceof GpuMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }

        if (videoRam0 instanceof Ram) {
            ((Ram) videoRam0).restoreFromMemento(mem.videoRam0Memento);
        }
        if (videoRam1 instanceof Ram) {
            ((Ram) videoRam1).restoreFromMemento(mem.videoRam1Memento);
        }

        display.restoreFromMemento(mem.displayMemento);
        lcdc.restoreFromMemento(mem.lcdcMemento);
        bgPalette.restoreFromMemento(mem.bgPaletteMemento);
        oamPalette.restoreFromMemento(mem.oamPaletteMemento);
        oamSearchPhase.restoreFromMemento(mem.oamSearchPhaseMemento);
        pixelTransferPhase.restoreFromMemento(mem.pixelTransferPhaseMemento);
        // snapshots from older versions carry only one dot machine; the pixel machine
        // then restarts from the skeleton's state (one partially wrong line at most)
        pixelMachine.restoreFromMemento(
                mem.pixelMachineMemento != null ? mem.pixelMachineMemento : mem.pixelTransferPhaseMemento);
        r.restoreFromMemento(mem.rMemento);

        this.lcdEnabled = mem.lcdEnabled;
        this.displayEnabledDelay = mem.displayEnabledDelay;
        this.line = mem.line;
        this.ticksInLine = mem.ticksInLine;
        this.firstLine = mem.firstLine;
        this.lcdEnableClockPhase = mem.lcdEnableClockPhase;
        this.pixelTransferDone = mem.pixelTransferDone;
        this.hblankIntFrom = mem.hblankIntFrom;
        this.mode0IntFrom = mem.mode0IntFrom;
        this.statModeLatchRephasedBySpeedSwitch = mem.statModeLatchRephasedBySpeedSwitch;
        this.scxWrittenThisLine = mem.scxWrittenThisLine;
        this.doubleSpeedMode2DispatchStatTailThisLine =
                mem.doubleSpeedMode2DispatchStatTailThisLine;
        this.earlyScxStatTailThisLine = mem.earlyScxStatTailThisLine;
        this.wyWrittenThisLine = mem.wyWrittenThisLine;
        this.lastCpuVramWriteTick = mem.lastCpuVramWriteTick;
        this.cpuRetiringInstructionForHdma = false;
        this.mode = mem.mode;
        pendingPpuWrites.clear();
        if (mem.pendingPpuWrites != null) {
            pendingPpuWrites.addAll(mem.pendingPpuWrites);
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
    }

    private record GpuMemento(Memento<Ram> videoRam0Memento, Memento<Ram> videoRam1Memento,
                              Memento<Display> displayMemento, Memento<Lcdc> lcdcMemento,
                              Memento<ColorPalette> bgPaletteMemento, Memento<ColorPalette> oamPaletteMemento,
                              Memento<OamSearch> oamSearchPhaseMemento,
                              Memento<PixelTransfer> pixelTransferPhaseMemento,
                              Memento<PixelTransfer> pixelMachineMemento,
                              Memento<GpuRegisterValues> rMemento, boolean lcdEnabled, int displayEnabledDelay,
                              int line, int ticksInLine, boolean firstLine,
                              boolean lcdEnableClockPhase, boolean pixelTransferDone,
                              int hblankIntFrom, int mode0IntFrom,
                              boolean statModeLatchRephasedBySpeedSwitch,
                              boolean scxWrittenThisLine,
                              boolean doubleSpeedMode2DispatchStatTailThisLine,
                              boolean earlyScxStatTailThisLine,
                              boolean wyWrittenThisLine,
                              int lastCpuVramWriteTick, Mode mode,
                              List<PendingPpuWrite> pendingPpuWrites,
                              int[] cpuVisiblePpuRegisters) implements Memento<Gpu> {
    }

    private record PendingPpuWrite(int address, int value, int mask,
                                   int remainingDots) implements Serializable {
    }
}
