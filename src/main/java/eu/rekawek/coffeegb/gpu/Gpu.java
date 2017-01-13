package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.cpu.InterruptManager.InterruptType;
import eu.rekawek.coffeegb.gpu.phase.GpuPhase;
import eu.rekawek.coffeegb.gpu.phase.HBlankPhase;
import eu.rekawek.coffeegb.gpu.phase.OamSearch;
import eu.rekawek.coffeegb.gpu.phase.PixelTransfer;
import eu.rekawek.coffeegb.gpu.phase.VBlankPhase;
import eu.rekawek.coffeegb.memory.MemoryRegisters;
import eu.rekawek.coffeegb.memory.Ram;

import static eu.rekawek.coffeegb.gpu.GpuRegister.*;

public class Gpu implements AddressSpace {

    private enum Mode {
        HBlank, VBlank, OamSearch, PixelTransfer
    }

    private final AddressSpace videoRam;

    private final AddressSpace oemRam;

    private final Display display;

    private final InterruptManager interruptManager;

    private final Lcdc lcdc;

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
        return getAddressSpace(address) != null;
    }

    @Override
    public void setByte(int address, int value) {
        if (address == STAT.getAddress()) {
            setStat(value);
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
        } else {
            AddressSpace space = getAddressSpace(address);
            if (space == null) {
                return 0xff;
            } else {
                return space.getByte(address);
            }
        }
    }

    public void tick() {
        boolean phaseInProgress = phase.tick();
        if (phaseInProgress) {
            if (mode == Mode.VBlank) {
                if (lcdc.isLcdEnabled()) {
                    display.enableLcd();
                } else {
                    display.disableLcd();
                }
            }
        } else {
            switch (mode) {
                case OamSearch:
                    mode = Mode.PixelTransfer;
                    phase = new PixelTransfer(videoRam, display, r, ((OamSearch) phase).getSprites());
                    break;

                case PixelTransfer:
                    mode = Mode.HBlank;
                    phase = new HBlankPhase(r.get(LY), ticksInLine);
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
                        display.refresh();
                    } else {
                        phase = new VBlankPhase(r.get(LY));
                    }
                    requestLycEqualsLyInterrupt();
                    break;
            }
        }
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
}
