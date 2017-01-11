package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.cpu.InterruptManager.InterruptType;
import eu.rekawek.coffeegb.gpu.phase.GpuPhase;
import eu.rekawek.coffeegb.gpu.phase.HBlankPhase;
import eu.rekawek.coffeegb.gpu.phase.OamSearch;
import eu.rekawek.coffeegb.gpu.phase.PixelTransfer;
import eu.rekawek.coffeegb.gpu.phase.VBlankPhase;
import eu.rekawek.coffeegb.memory.Ram;

public class Gpu implements AddressSpace {

    private enum Mode {
        HBlank, VBlank, OamSearch, PixelTransfer
    }

    private final AddressSpace videoRam;

    private final AddressSpace oemRam;

    private final Display display;

    private final InterruptManager interruptManager;

    private int lcdc, stat, scrollY, scrollX, ly, lyc;

    private int ticksInLine;

    private Mode mode;

    private GpuPhase phase;

    public Gpu(Display display, InterruptManager interruptManager) {
        this.interruptManager = interruptManager;
        this.videoRam = new Ram(0x8000, 0x2000);
        this.oemRam = new Ram(0xfe00, 0x00a0);
        this.phase = new OamSearch(ly);
        this.mode = Mode.OamSearch;
        this.display = display;
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x8000 && address < 0xa000) ||
               (address >= 0xfe00 && address < 0xfea0) ||
               (address >= 0xff40 && address <= 0xff4b);
    }

    @Override
    public void setByte(int address, int value) {
        if (videoRam.accepts(address)) {
            videoRam.setByte(address, value);
        } else if (oemRam.accepts(address)) {
            oemRam.setByte(address, value);
        } else {
            switch (address) {
                case 0xff40:
                    lcdc = value;
                    break;

                case 0xff41:
                    setStat(value);
                    break;

                case 0xff42:
                    scrollY = value;
                    break;

                case 0xff43:
                    scrollX = value;
                    break;

                case 0xff45:
                    lyc = value;
                    break;
            }
        }
    }

    @Override
    public int getByte(int address) {
        if (videoRam.accepts(address)) {
            return videoRam.getByte(address);
        } else if (oemRam.accepts(address)) {
            return oemRam.getByte(address);
        } else {
            switch (address) {
                case 0xff40:
                    return lcdc;

                case 0xff41:
                    return getStat();

                case 0xff42:
                    return scrollY;

                case 0xff43:
                    return scrollX;

                case 0xff44:
                    return ly;

                case 0xff45:
                    return lyc;
            }
        }
        return 0xff;
    }

    public void tick() {
        boolean phaseInProgress = phase.tick();
        if (!phaseInProgress) {
            switch (mode) {
                case OamSearch:
                    mode = Mode.PixelTransfer;
                    phase = new PixelTransfer(ly, videoRam, display, lcdc, scrollX, scrollY);
                    break;

                case PixelTransfer:
                    mode = Mode.HBlank;
                    phase = new HBlankPhase(ly, ticksInLine);
                    requestLcdcInterrupt(3);
                    break;

                case HBlank:
                    ticksInLine = 0;
                    if (++ly == 144) {
                        mode = Mode.VBlank;
                        phase = new VBlankPhase(ly);
                        interruptManager.requestInterrupt(InterruptType.VBlank);
                        requestLcdcInterrupt(4);
                    } else {
                        mode = Mode.OamSearch;
                        phase = new OamSearch(ly);
                        requestLcdcInterrupt(5);
                    }
                    requestLycEqualsLyInterrupt();
                    break;

                case VBlank:
                    ticksInLine = 0;
                    if (++ly == 154) {
                        mode = Mode.OamSearch;
                        ly = 0;
                        phase = new OamSearch(ly);
                        requestLcdcInterrupt(5);
                    } else {
                        phase = new VBlankPhase(ly);
                    }
                    requestLycEqualsLyInterrupt();
                    break;
            }
        }
    }

    private void requestLcdcInterrupt(int statBit) {
        if ((stat & (1 << statBit)) != 0) {
            interruptManager.requestInterrupt(InterruptType.LCDC);
        }
    }

    private void requestLycEqualsLyInterrupt() {
        if (lyc == ly) {
            requestLcdcInterrupt(6);
        }
    }

    private int getStat() {
        return stat | mode.ordinal() | (ly == lyc ? (1 << 2) : 0);
    }

    private void setStat(int value) {
        this.stat = value & 0b11111000; // last three bits are read-only
    }
}
