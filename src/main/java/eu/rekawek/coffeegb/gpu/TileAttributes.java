package eu.rekawek.coffeegb.gpu;

public class TileAttributes {

    public static final TileAttributes EMPTY = new TileAttributes(0);

    private final int value;

    public TileAttributes(int flags) {
        this.value = flags;
    }

    public boolean isPriority() {
        return (value & (1 << 7)) != 0;
    }

    public boolean isYflip() {
        return (value & (1 << 6)) != 0;
    }

    public boolean isXflip() {
        return (value & (1 << 5)) != 0;
    }

    public GpuRegister getDmgPalette() {
        return (value & (1 << 4)) == 0 ? GpuRegister.OBP0 : GpuRegister.OBP1;
    }

    public int getBank() {
        return (value & (1 << 3)) == 0 ? 0 : 1;
    }

    public int getColorPaletteIndex() {
        return value & 0x07;
    }
}
