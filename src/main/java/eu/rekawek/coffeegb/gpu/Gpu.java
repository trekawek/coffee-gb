package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.Gameboy;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.cpu.InterruptManager.InterruptType;
import eu.rekawek.coffeegb.gpu.phase.GpuPhase;
import eu.rekawek.coffeegb.gpu.phase.HBlankPhase;
import eu.rekawek.coffeegb.gpu.phase.OamSearch;
import eu.rekawek.coffeegb.gpu.phase.PixelTransfer;
import eu.rekawek.coffeegb.gpu.phase.VBlankPhase;
import eu.rekawek.coffeegb.memory.MemoryRegisters;
import eu.rekawek.coffeegb.memory.Ram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static eu.rekawek.coffeegb.gpu.GpuRegister.*;

public class Gpu implements AddressSpace {

    private static final Logger LOG = LoggerFactory.getLogger(Gpu.class);

    public enum Mode {
        HBlank, VBlank, OamSearch, PixelTransfer
    }

    private final AddressSpace videoRam;

    private final AddressSpace oemRam;

    private final Display display;

    private final InterruptManager interruptManager;

    private final Lcdc lcdc;

    private boolean lcdEnabled = true;

    private int lcdEnabledDelay;

    private MemoryRegisters r;

    private int ticksInLine;

    private Mode mode;

    private GpuPhase phase;

    public Gpu(Display display, InterruptManager interruptManager) {
        this.r = new MemoryRegisters(GpuRegister.values());
        this.interruptManager = interruptManager;
        this.videoRam = new Ram(0x8000, 0x2000);
        this.oemRam = new Ram(0xfe00, 0x00a0);
        this.phase = new OamSearch(oemRam, r);
        this.mode = Mode.OamSearch;
        this.display = display;
        this.lcdc = new Lcdc(r);
    }

    private AddressSpace getAddressSpace(int address) {
        if (videoRam.accepts(address)) {
            return videoRam;
        } else if (oemRam.accepts(address)) {
            return oemRam;
        } else if (r.accepts(address)) {
            return r;
        } else {
            return null;
        }
    }

    @Override
    public boolean accepts(int address) {
        return videoRam.accepts(address) || oemRam.accepts(address) || r.accepts(address);
    }

    @Override
    public void setByte(int address, int value) {
        if (address == STAT.getAddress()) {
            setStat(value);
        } else if (address == LCDC.getAddress()) {
            setLcdc(value);
        } else {
            AddressSpace space = getAddressSpace(address);
            if (space != null) {
                space.setByte(address, value);
            }
        }
    }

    @Override
    public int getByte(int address) {
        if (address == STAT.getAddress()) {
            return getStat();
        } else if (address == LCDC.getAddress()) {
            return getLcdc();
        } else {
            AddressSpace space = getAddressSpace(address);
            if (space == null) {
                return 0xff;
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
        if (!phase.tick()) {
            switch (mode) {
                case OamSearch:
                    mode = Mode.PixelTransfer;
                    phase = new PixelTransfer(videoRam, oemRam, display, r, ((OamSearch) phase).getSprites());
                    break;

                case PixelTransfer:
                    mode = Mode.HBlank;
                    phase = new HBlankPhase(ticksInLine, r);
                    requestLcdcInterrupt(3);
                    break;

                case HBlank:
                    ticksInLine = 0;
                    if (r.preIncrement(LY) == 144) {
                        mode = Mode.VBlank;
                        phase = new VBlankPhase(r.get(LY));
                        interruptManager.requestInterrupt(InterruptType.VBlank);
                        requestLcdcInterrupt(4);
                    } else {
                        mode = Mode.OamSearch;
                        phase = new OamSearch(oemRam, r);
                        requestLcdcInterrupt(5);
                    }
                    requestLycEqualsLyInterrupt();
                    break;

                case VBlank:
                    ticksInLine = 0;
                    if (r.preIncrement(LY) == 154) {
                        mode = Mode.OamSearch;
                        r.put(LY, 0);
                        phase = new OamSearch(oemRam, r);
                        requestLcdcInterrupt(5);
                    } else {
                        phase = new VBlankPhase(r.get(LY));
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
        return r.get(STAT) | mode.ordinal() | (r.get(LYC) == r.get(LY) ? (1 << 2) : 0);
    }

    private void setStat(int value) {
        r.put(STAT, value & 0b11111000); // last three bits are read-only
    }

    private int getLcdc() {
        return (r.get(LCDC) & 0x7f) | (lcdEnabled ? (1 << 7) : 0);
    }

    private void setLcdc(int value) {
        r.put(LCDC, value & 0x7f);
        if ((value & (1 << 7)) == 0) {
            disableLcd();
        } else {
            enableLcd();
        }
    }

    private void disableLcd() {
        r.put(LY, 0);
        this.ticksInLine = 0;
        this.phase = new HBlankPhase(250, r);
        this.mode = Mode.HBlank;
        this.lcdEnabled = false;
        this.lcdEnabledDelay = -1;
        display.disableLcd();
    }

    private void enableLcd() {
        lcdEnabledDelay = 244;
    }
}
