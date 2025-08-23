package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.cpu.InterruptManager.InterruptType;
import eu.rekawek.coffeegb.gpu.phase.*;
import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;
import eu.rekawek.coffeegb.memory.Dma;
import eu.rekawek.coffeegb.memory.Ram;

import java.io.Serializable;

import static eu.rekawek.coffeegb.gpu.GpuRegister.*;

public class Gpu implements AddressSpace, Serializable, Originator<Gpu> {

    public enum Mode {
        HBlank(3),
        VBlank(4),
        OamSearch(5),
        PixelTransfer(-1);

        private final int statBit;

        Mode(int statBit) {
            this.statBit = statBit;
        }
    }

    private final Ram videoRam0;

    private final Ram videoRam1;

    private final AddressSpace oamRam;

    private final Display display;

    private final InterruptManager interruptManager;

    private final Dma dma;

    private final Lcdc lcdc;

    private final boolean gbc;

    private final ColorPalette bgPalette;

    private final ColorPalette oamPalette;

    private final HBlankPhase hBlankPhase;

    private final OamSearch oamSearchPhase;

    private final PixelTransfer pixelTransferPhase;

    private final VBlankPhase vBlankPhase;

    private boolean lcdEnabled = true;

    private int lcdEnabledDelay;

    private final GpuRegisterValues r;

    private int ticksInLine;

    private Mode mode;

    private GpuPhase phase;

    private boolean previousStatRequested;

    public Gpu(Display display, InterruptManager interruptManager, Dma dma, Ram oamRam, VRamTransfer vRamTransfer, boolean gbc) {
        this.display = display;
        this.r = new GpuRegisterValues();
        this.lcdc = new Lcdc();
        this.interruptManager = interruptManager;
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
        this.pixelTransferPhase =
                new PixelTransfer(
                        display,
                        videoRam0,
                        videoRam1,
                        oamRam,
                        lcdc,
                        r,
                        gbc,
                        bgPalette,
                        oamPalette,
                        oamSearchPhase.getSprites(),
                        vRamTransfer);
        this.hBlankPhase = new HBlankPhase();
        this.vBlankPhase = new VBlankPhase();

        this.mode = Mode.OamSearch;
        this.phase = oamSearchPhase.start();
    }

    private AddressSpace getAddressSpace(int address) {
        if (videoRam0.accepts(address) /* && mode != Mode.PixelTransfer*/) {
            return getVideoRam();
        } else if (oamRam.accepts(address)
                && !dma.isOamBlocked() /* && mode != Mode.OamSearch && mode != Mode.PixelTransfer*/) {
            return oamRam;
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
        return getAddressSpace(address) != null;
    }

    @Override
    public void setByte(int address, int value) {
        if (address == STAT.getAddress()) {
            setStat(value);
        } else {
            AddressSpace space = getAddressSpace(address);
            if (space == lcdc) {
                setLcdc(value);
            } else if (space != null) {
                space.setByte(address, value);
            }
        }
    }

    @Override
    public int getByte(int address) {
        if (address == STAT.getAddress()) {
            return getStat();
        } else {
            AddressSpace space = getAddressSpace(address);
            if (space == null) {
                return 0xff;
            } else if (address == VBK.getAddress()) {
                return gbc ? 0xfe : 0xff;
            } else {
                return space.getByte(address);
            }
        }
    }

    public Mode tick() {
        if (!lcdEnabled) {
            if (lcdEnabledDelay != -1) {
                if (--lcdEnabledDelay == 0) {
                    display.enableLcd();
                    lcdEnabled = true;
                }
            }
        }

        if (!lcdEnabled) {
            previousStatRequested = false;
            return null;
        }

        boolean statRequested = false;
        Mode oldMode = mode;
        ticksInLine++;
        if (phase.tick()) {
            // switch line 153 to 0
            if (ticksInLine == 4 && mode == Mode.VBlank && r.get(LY) == 153) {
                r.put(LY, 0);
                pixelTransferPhase.resetWindowLineCounter();
            }
        } else {
            switch (oldMode) {
                case OamSearch:
                    mode = Mode.PixelTransfer;
                    phase = pixelTransferPhase.start();
                    break;

                case PixelTransfer:
                    mode = Mode.HBlank;
                    phase = hBlankPhase.start(ticksInLine);
                    break;

                case HBlank:
                    ticksInLine = 0;
                    if (r.get(WX) < 166
                            && r.get(WY) < 143
                            && r.get(LY) >= r.get(WY)
                            && lcdc.isWindowDisplay()) {
                        pixelTransferPhase.incrementWindowLineCounter();
                    }
                    if (r.preIncrement(LY) == 144) {
                        mode = Mode.VBlank;
                        phase = vBlankPhase.start();
                        interruptManager.requestInterrupt(InterruptType.VBlank);
                        // vblank_stat_intr-GS.s
                        statRequested |= isStatInterruptTriggeringForMode(Mode.OamSearch);
                    } else {
                        mode = Mode.OamSearch;
                        phase = oamSearchPhase.start();
                    }
                    break;

                case VBlank:
                    ticksInLine = 0;
                    if (r.preIncrement(LY) == 1) {
                        mode = Mode.OamSearch;
                        r.put(LY, 0);
                        pixelTransferPhase.resetWindowLineCounter();
                        phase = oamSearchPhase.start();
                    } else {
                        phase = vBlankPhase.start();
                    }
                    break;
            }
        }

        statRequested |= isStatInterruptTriggeringForMode(mode);
        statRequested |= isStatInterruptTriggeringForLyc();
        if (!previousStatRequested && statRequested) {
            interruptManager.requestInterrupt(InterruptType.LCDC);
        }
        previousStatRequested = statRequested;

        if (oldMode == mode) {
            return null;
        } else {
            return mode;
        }
    }

    public int getTicksInLine() {
        return ticksInLine;
    }

    private boolean isStatInterruptEnabled(int statBit) {
        return (r.get(STAT) & (1 << statBit)) != 0;
    }

    private boolean isStatInterruptTriggeringForMode(Mode mode) {
        return mode.statBit != -1 && isStatInterruptEnabled(mode.statBit);
    }

    private boolean isStatInterruptTriggeringForLyc() {
        return r.get(LYC) == r.get(LY) && isStatInterruptEnabled(6);
    }

    private int getStat() {
        return r.get(STAT) | mode.ordinal() | (r.get(LYC) == r.get(LY) ? (1 << 2) : 0) | 0x80;
    }

    private void setStat(int value) {
        r.put(STAT, value & 0b11111000); // last three bits are read-only
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
        r.put(LY, 0);
        pixelTransferPhase.resetWindowLineCounter();
        this.ticksInLine = 0;
        this.phase = hBlankPhase.start(250);
        this.mode = Mode.HBlank;
        this.lcdEnabled = false;
        this.lcdEnabledDelay = -1;
        display.disableLcd();
    }

    private void enableLcd() {
        lcdEnabledDelay = 244;
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

    public ColorPalette getOamPalette() {
        return oamPalette;
    }

    public Mode getMode() {
        return mode;
    }

    @Override
    public Memento<Gpu> saveToMemento() {
        Memento<Ram> videoRam0Memento =
                videoRam0 instanceof Ram ? ((Ram) videoRam0).saveToMemento() : null;
        Memento<Ram> videoRam1Memento =
                videoRam1 instanceof Ram ? ((Ram) videoRam1).saveToMemento() : null;

        return new GpuMemento(
                videoRam0Memento,
                videoRam1Memento,
                display.saveToMemento(),
                lcdc.saveToMemento(),
                bgPalette.saveToMemento(),
                oamPalette.saveToMemento(),
                hBlankPhase.saveToMemento(),
                oamSearchPhase.saveToMemento(),
                pixelTransferPhase.saveToMemento(),
                vBlankPhase.saveToMemento(),
                r.saveToMemento(),
                lcdEnabled,
                lcdEnabledDelay,
                ticksInLine,
                mode,
                previousStatRequested);
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
        hBlankPhase.restoreFromMemento(mem.hBlankPhaseMemento);
        oamSearchPhase.restoreFromMemento(mem.oamSearchPhaseMemento);
        pixelTransferPhase.restoreFromMemento(mem.pixelTransferPhaseMemento);
        vBlankPhase.restoreFromMemento(mem.vBlankPhaseMemento);
        r.restoreFromMemento(mem.rMemento);

        this.lcdEnabled = mem.lcdEnabled;
        this.lcdEnabledDelay = mem.lcdEnabledDelay;
        this.ticksInLine = mem.ticksInLine;
        this.mode = mem.mode;
        this.previousStatRequested = mem.previousStatRequested;

        switch (mode) {
            case OamSearch:
                phase = oamSearchPhase;
                break;
            case PixelTransfer:
                phase = pixelTransferPhase;
                break;
            case HBlank:
                phase = hBlankPhase;
                break;
            case VBlank:
                phase = vBlankPhase;
                break;
        }
    }

    private record GpuMemento(
            Memento<Ram> videoRam0Memento,
            Memento<Ram> videoRam1Memento,
            Memento<Display> displayMemento,
            Memento<Lcdc> lcdcMemento,
            Memento<ColorPalette> bgPaletteMemento,
            Memento<ColorPalette> oamPaletteMemento,
            Memento<HBlankPhase> hBlankPhaseMemento,
            Memento<OamSearch> oamSearchPhaseMemento,
            Memento<PixelTransfer> pixelTransferPhaseMemento,
            Memento<VBlankPhase> vBlankPhaseMemento,
            Memento<GpuRegisterValues> rMemento,
            boolean lcdEnabled,
            int lcdEnabledDelay,
            int ticksInLine,
            Mode mode,
            boolean previousStatRequested)
            implements Memento<Gpu> {
    }
}
