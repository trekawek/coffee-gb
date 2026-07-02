package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.gpu.phase.*;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Ram;

import java.io.Serializable;

import static eu.rekawek.coffeegb.core.gpu.GpuRegister.*;

public class Gpu implements AddressSpace, Serializable, Originator<Gpu> {

    private final Ram videoRam0;

    private final Ram videoRam1;

    private final AddressSpace oamRam;

    private final Display display;

    private final Dma dma;

    private final Lcdc lcdc;

    private final boolean gbc;

    private final ColorPalette bgPalette;

    private final ColorPalette oamPalette;

    private final OamSearch oamSearchPhase;

    private final PixelTransfer pixelTransferPhase;

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

    private Mode mode;

    private GpuPhase phase;

    // tick at which the pixel transfer finished; the visible mode/locks change one tick later
    private boolean pixelTransferDone;

    // tick at which the hblank term of the STAT interrupt line rises; it precedes the
    // visible mode 0 and is quantized to 4-tick steps (hblank_ly_scx_timing-GS)
    private int hblankIntFrom = Integer.MAX_VALUE;

    public Gpu(Display display, Dma dma, Ram oamRam, VRamTransfer vRamTransfer, StatRegister statRegister, boolean gbc, eu.rekawek.coffeegb.core.cpu.SpeedMode speedMode) {
        this.statRegister = statRegister;
        this.display = display;
        this.r = new GpuRegisterValues();
        this.lcdc = new Lcdc();
        this.gbc = gbc;
        this.videoRam0 = new Ram(0x8000, 0x2000);
        if (gbc) {
            this.videoRam1 = new Ram(0x8000, 0x2000);
        } else {
            this.videoRam1 = null;
        }
        this.oamRam = oamRam;
        this.dma = dma;

        this.bgPalette = new ColorPalette(0xff68);
        this.oamPalette = new ColorPalette(0xff6a);
        oamPalette.fillWithFF();

        this.oamSearchPhase = new OamSearch(oamRam, lcdc, r);
        this.pixelTransferPhase = new PixelTransfer(display, videoRam0, videoRam1, oamRam, lcdc, r, gbc, bgPalette, oamPalette, oamSearchPhase.getSprites(), vRamTransfer, speedMode);

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
        if (oamRam.accepts(address)) {
            if (!dma.isOamBlocked() && isOamAvailableForCpu(true)) {
                oamRam.setByte(address, value);
            }
            return;
        }
        if (videoRam0.accepts(address)) {
            if (isVramAvailableForCpu(true)) {
                getVideoRam().setByte(address, value);
            }
            return;
        }
        AddressSpace space = getAddressSpace(address);
        if (space == lcdc) {
            setLcdc(value);
        } else if (space != null) {
            space.setByte(address, value);
        }
    }

    @Override
    public int getByte(int address) {
        if (address == LY.getAddress()) {
            return getVisibleLy();
        }
        AddressSpace space = getAddressSpace(address);
        if (space == null) {
            return 0xff;
        } else if (address == VBK.getAddress()) {
            return gbc ? (0xfe | (space.getByte(address) & 1)) : 0xff;
        } else {
            return space.getByte(address);
        }
    }

    public Mode tick() {
        if (displayEnabledDelay > 0 && --displayEnabledDelay == 0) {
            display.enableLcd();
        }

        if (!lcdEnabled) {
            return null;
        }

        // write-conflict mixes settle and the LCD output stage advances every tick,
        // in all modes (the last pixels of a line leave the delay line during HBlank)
        r.tickConflicts();
        lcdc.tickConflicts();
        pixelTransferPhase.outputTick();

        Mode oldMode = mode;
        ticksInLine++;
        // the line started by enabling the LCD is one tick shorter: its grid starts at
        // the LCDC write itself, while the machine-cycle-locked line grid starts one
        // tick later (lcdon_timing-GS vs the steady-state line phase)
        if (ticksInLine == (firstLine ? 455 : 456)) {
            ticksInLine = 0;
            firstLine = false;
            pixelTransferDone = false;
            hblankIntFrom = Integer.MAX_VALUE;
            line++;
            if (line == 154) {
                line = 0;
                pixelTransferPhase.resetWindowLineCounter();
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
                        phase = pixelTransferPhase.start();
                    }
                    break;

                case PixelTransfer:
                    if (pixelTransferDone) {
                        pixelTransferDone = false;
                        mode = Mode.HBlank;
                    } else if (!phase.tick()) {
                        // the visible mode/locks change one tick after the last pixel
                        // (intr_2_mode0_timing_sprites); the interrupt line lags the
                        // visible mode by 3 ticks (hblank_ly_scx_timing-GS,
                        // intr_2_0_timing)
                        pixelTransferDone = true;
                        hblankIntFrom = ticksInLine + 4;
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
        // The OAM scan accesses rows 1..19, starting 4 ticks before the end of the
        // preceding line and finishing at tick 72 (blargg oam_bug 4-scanline_timing,
        // 5-timing_bug, 6-timing_no_bug). The INC/DEC bug check runs one machine cycle
        // before the actual bus event, while the pop/push/ldi/ldd checks run on their
        // memory cycle, so their tick is shifted back accordingly (8-instr_effect).
        int t = type == SpriteBug.CorruptionType.INC_DEC ? ticksInLine : ticksInLine - 4;
        if (t >= (firstLine ? 451 : 452) && (line < 143 || line == 153)) {
            SpriteBug.corruptOam(oamRam, type, 1);
        } else if (mode == Mode.OamSearch && t >= -4 && t < 72) {
            SpriteBug.corruptOam(oamRam, type, t < 0 ? 1 : t / 4 + 2);
        }
    }

    public int getLine() {
        return line;
    }

    public boolean isFirstLine() {
        return firstLine;
    }

    /**
     * LY value as visible to the CPU: it increments 4 ticks before the end of the line, and
     * on line 153 it reads 153 only for the first 4 ticks, then 0.
     */
    public int getVisibleLy() {
        if (!lcdEnabled) {
            return 0;
        }
        if (line == 153) {
            return ticksInLine < 4 ? 153 : 0;
        }
        if (ticksInLine >= (firstLine ? 451 : 452)) {
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
        if (firstLine && ticksInLine < 79) {
            return 0;
        }
        return mode.ordinal();
    }

    /**
     * The "mode 2" STAT interrupt condition is a short pulse at the beginning of each visible
     * line (and also at the beginning of line 144).
     */
    public boolean isMode2IntWindow() {
        return lcdEnabled && !firstLine && line <= 144 && ticksInLine < 4;
    }

    /**
     * The "mode 0" STAT interrupt condition rises with the visible mode 0, quantized to
     * 4-tick steps of the SCX scroll delay, and stays active until the end of the line.
     */
    public boolean isMode0IntWindow() {
        return lcdEnabled && line < 144 && ticksInLine >= hblankIntFrom;
    }

    /**
     * OAM is locked from 4 ticks before the end of the preceding line until the end of the
     * pixel transfer. On the first line after enabling the LCD, it is locked when the pixel
     * transfer starts.
     */
    private boolean isOamAvailableForCpu() {
        return isOamAvailableForCpu(false);
    }

    private boolean isOamAvailableForCpu(boolean write) {
        if (!lcdEnabled) {
            return true;
        }
        if (firstLine && ticksInLine < 79) {
            return true;
        }
        if (mode == Mode.OamSearch) {
            // the OAM write bus is released between the end of the OAM scan and the
            // start of the pixel transfer (lcdon_write_timing-GS)
            return write && ticksInLine >= 76;
        }
        if (mode == Mode.PixelTransfer) {
            return false;
        }
        // reads are blocked from 4 ticks before the end of the preceding line, but
        // writes still pass (lcdon_write_timing-GS)
        if (!write && ticksInLine >= (firstLine ? 451 : 452) && (line < 143 || line == 153)) {
            return false;
        }
        return true;
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
        if (mode == Mode.PixelTransfer) {
            return false;
        }
        if (!write && !firstLine && mode == Mode.OamSearch && ticksInLine >= 76) {
            return false;
        }
        return true;
    }

    private void setLcdc(int value) {
        lcdc.set(value);
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
        r.put(LY, 0);
        pixelTransferPhase.resetWindowLineCounter();
        this.line = 0;
        this.ticksInLine = 0;
        this.firstLine = false;
        this.pixelTransferDone = false;
        this.hblankIntFrom = Integer.MAX_VALUE;
        this.mode = Mode.HBlank;
        this.lcdEnabled = false;
        this.displayEnabledDelay = 0;
        pixelTransferPhase.clearOutput();
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
        this.pixelTransferDone = false;
        this.hblankIntFrom = Integer.MAX_VALUE;
        r.put(LY, 0);
        // there is no OAM scan on the first line, but running it is harmless as the CPU
        // can still write to OAM at this point
        this.mode = Mode.OamSearch;
        this.phase = oamSearchPhase.start();
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

    public boolean isGbc() {
        return gbc;
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

        return new GpuMemento(videoRam0Memento, videoRam1Memento, display.saveToMemento(), lcdc.saveToMemento(), bgPalette.saveToMemento(), oamPalette.saveToMemento(), oamSearchPhase.saveToMemento(), pixelTransferPhase.saveToMemento(), r.saveToMemento(), lcdEnabled, displayEnabledDelay, line, ticksInLine, firstLine, pixelTransferDone, hblankIntFrom, mode);
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
        r.restoreFromMemento(mem.rMemento);

        this.lcdEnabled = mem.lcdEnabled;
        this.displayEnabledDelay = mem.displayEnabledDelay;
        this.line = mem.line;
        this.ticksInLine = mem.ticksInLine;
        this.firstLine = mem.firstLine;
        this.pixelTransferDone = mem.pixelTransferDone;
        this.hblankIntFrom = mem.hblankIntFrom;
        this.mode = mem.mode;

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
                              Memento<GpuRegisterValues> rMemento, boolean lcdEnabled, int displayEnabledDelay,
                              int line, int ticksInLine, boolean firstLine, boolean pixelTransferDone,
                              int hblankIntFrom, Mode mode) implements Memento<Gpu> {
    }
}
