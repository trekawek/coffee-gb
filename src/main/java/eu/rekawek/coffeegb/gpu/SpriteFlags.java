package eu.rekawek.coffeegb.gpu;

public class SpriteFlags {

    private final int flags;

    public SpriteFlags(int flags) {
        this.flags = flags;
    }

    public boolean isPriority() {
        return (flags & (1 << 7)) != 0;
    }

    public boolean isYflip() {
        return (flags & (1 << 6)) != 0;
    }

    public boolean isXflip() {
        return (flags & (1 << 5)) != 0;
    }

    public GpuRegister getPalette() {
        return (flags & (1 << 4)) == 0 ? GpuRegister.OBP0 : GpuRegister.OBP1;
    }
}
