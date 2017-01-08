package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.gpu.phase.GpuPhase;
import eu.rekawek.coffeegb.gpu.phase.HBlankPhase;
import eu.rekawek.coffeegb.gpu.phase.OamSearch;
import eu.rekawek.coffeegb.gpu.phase.PixelTransfer;
import eu.rekawek.coffeegb.gpu.phase.VBlankPhase;

public class Gpu implements AddressSpace {

    private enum Mode {
        HBlank, VBlank, OamSearch, PixelTransfer
    }

    private final AddressSpace ram;

    private final Display display;

    private int lcdc, scrollY, scrollX;

    private int line;

    private int ticksInLine;

    private Mode mode;

    private GpuPhase phase;

    public Gpu(AddressSpace ram, Display display) {
        this.ram = ram;
        this.phase = new OamSearch(line);
        this.mode = Mode.OamSearch;
        this.display = display;
    }

    @Override
    public void setByte(int address, int value) {
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
        }
    }

    @Override
    public int getByte(int address) {
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
                return line;
        }
        return 0xff;
    }

    public void tick() {
        boolean phaseInProgress = phase.tick();
        if (!phaseInProgress) {
            switch (mode) {
                case OamSearch:
                    mode = Mode.PixelTransfer;
                    phase = new PixelTransfer(line, ram, display, lcdc, scrollX, scrollY);
                    break;

                case PixelTransfer:
                    mode = Mode.HBlank;
                    phase = new HBlankPhase(line, ticksInLine);
                    break;

                case HBlank:
                    ticksInLine = 0;
                    if (++line == 144) {
                        mode = Mode.VBlank;
                        phase = new VBlankPhase(line);
                    } else {
                        mode = Mode.OamSearch;
                        phase = new OamSearch(line);
                    }
                    break;

                case VBlank:
                    ticksInLine = 0;
                    if (++line == 154) {
                        mode = Mode.OamSearch;
                        line = 0;
                        phase = new OamSearch(line);
                    } else {
                        phase = new VBlankPhase(line);
                    }
            }
        }
    }

    private int getStat() {
        return 0;
    }

    private void setStat(int value) {
    }
}
