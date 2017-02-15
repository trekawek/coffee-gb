package eu.rekawek.coffeegb.gpu.phase;

public class VBlankPhase implements GpuPhase {

    private int ticks;

    public VBlankPhase start() {
        ticks = 0;
        return this;
    }

    @Override
    public boolean tick() {
        return ++ticks < 456;
    }
}
