package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.memory.MemoryRegisters;

import static eu.rekawek.coffeegb.gpu.GpuRegister.LCDC;

public class Lcdc {

    private final int lcdc;

    private final MemoryRegisters r;

    public Lcdc(int lcdc) {
        this.r = null;
        this.lcdc = lcdc;
    }

    public Lcdc(MemoryRegisters registers) {
        this.r = registers;
        this.lcdc = 0;
    }

    private int getValue() {
        return r == null ? lcdc : r.get(LCDC);
    }

    public boolean isBgAndWindowDisplay() {
        return (getValue() & (1 << 0)) != 0;
    }

    public boolean isObjDisplay() {
        return (getValue() & (1 << 1)) != 0;
    }

    public int getSpriteHeight() {
        return (getValue() & (1 << 2)) == 0 ? 8 : 16;
    }

    public int getBgTileMapDisplay() {
        return (getValue() & (1 << 3)) == 0 ? 0x9800 : 0x9c00;
    }

    public int getBgWindowTileData() {
        return (getValue() & (1 << 4)) == 0 ? 0x9000 : 0x8000;
    }

    public boolean isBgWindowTileDataSigned() {
        return (getValue() & (1 << 4)) == 0;
    }

    public boolean isWindowDisplay() {
        return (getValue() & (1 << 5)) != 0;
    }

    public int getWindowTileMapDisplay() {
        return (getValue() & (1 << 6)) == 0 ? 0x9800 : 0x9c00;
    }

    public boolean isLcdEnabled() {
        return (getValue() & (1 << 7)) != 0;
    }
}
