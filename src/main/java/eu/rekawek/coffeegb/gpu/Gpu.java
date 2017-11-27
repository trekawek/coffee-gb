package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.cpu.InterruptManager.InterruptType;
import eu.rekawek.coffeegb.cpu.SpeedMode;
import eu.rekawek.coffeegb.gpu.phase.GpuPhase;
import eu.rekawek.coffeegb.gpu.phase.HBlankPhase;
import eu.rekawek.coffeegb.gpu.phase.OamSearch;
import eu.rekawek.coffeegb.gpu.phase.PixelTransfer;
import eu.rekawek.coffeegb.gpu.phase.VBlankPhase;
import eu.rekawek.coffeegb.memory.Dma;
import eu.rekawek.coffeegb.memory.MemoryRegisters;
import eu.rekawek.coffeegb.memory.Ram;

import static eu.rekawek.coffeegb.gpu.GpuRegister.*;

public class Gpu implements AddressSpace {

    public enum Mode {
        HBlank, VBlank, OamSearch, PixelTransfer
    }

    private final AddressSpace videoRam0;

    private final AddressSpace videoRam1;

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

    private MemoryRegisters r;

    private int ticksInLine;

    private Mode mode;

    private GpuPhase phase;

    public Gpu(Display display, InterruptManager interruptManager, Dma dma, Ram oamRam, boolean gbc) {
        this.r = new MemoryRegisters(GpuRegister.values());
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
        this.pixelTransferPhase = new PixelTransfer(videoRam0, videoRam1, oamRam, display, lcdc, r, gbc, bgPalette, oamPalette);
        this.hBlankPhase = new HBlankPhase();
        this.vBlankPhase = new VBlankPhase();

        this.mode = Mode.OamSearch;
        this.phase = oamSearchPhase.start();

        this.display = display;
    }

    private AddressSpace getAddressSpace(int address) {
        if (videoRam0.accepts(address)/* && mode != Mode.PixelTransfer*/) {
            return getVideoRam();
        } else if (oamRam.accepts(address) && !dma.isOamBlocked()/* && mode != Mode.OamSearch && mode != Mode.PixelTransfer*/) {
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

    private AddressSpace getVideoRam() {
        if (gbc && (r.get(VBK) & 1) == 1) {
            return videoRam1;
        } else {
            return videoRam0;
        }
    }

    public AddressSpace getVideoRam0() {
        return videoRam0;
    }

    public AddressSpace getVideoRam1() {
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
            return null;
        }

        Mode oldMode = mode;
        ticksInLine++;
        if (phase.tick()) {
            // switch line 153 to 0
            if (ticksInLine == 4 && mode == Mode.VBlank && r.get(LY) == 153) {
                r.put(LY, 0);
                requestLycEqualsLyInterrupt();
            }
        } else {
            switch (oldMode) {
                case OamSearch:
                    mode = Mode.PixelTransfer;
                    phase = pixelTransferPhase.start(oamSearchPhase.getSprites());
                    break;

                case PixelTransfer:
                    mode = Mode.HBlank;
                    phase = hBlankPhase.start(ticksInLine);
                    requestLcdcInterrupt(3);
                    break;

                case HBlank:
                    ticksInLine = 0;
                    if (r.preIncrement(LY) == 144) {
                        mode = Mode.VBlank;
                        phase = vBlankPhase.start();
                        interruptManager.requestInterrupt(InterruptType.VBlank);
                        requestLcdcInterrupt(4);
                    } else {
                        mode = Mode.OamSearch;
                        phase = oamSearchPhase.start();
                    }
                    requestLcdcInterrupt(5);
                    requestLycEqualsLyInterrupt();
                    break;

                case VBlank:
                    ticksInLine = 0;
                    if (r.preIncrement(LY) == 1) {
                        mode = Mode.OamSearch;
                        r.put(LY, 0);
                        phase = oamSearchPhase.start();
                        requestLcdcInterrupt(5);
                    } else {
                        phase = vBlankPhase.start();
                    }
                    requestLycEqualsLyInterrupt();
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

    private void requestLcdcInterrupt(int statBit) {
        if ((r.get(STAT) & (1 << statBit)) != 0) {
            interruptManager.requestInterrupt(InterruptType.LCDC);
        }
    }

    private void requestLycEqualsLyInterrupt() {
        if (r.get(LYC) == r.get(LY)) {
            requestLcdcInterrupt(6);
        }
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

    public MemoryRegisters getRegisters() {
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
}
