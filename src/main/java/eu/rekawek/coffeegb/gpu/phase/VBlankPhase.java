package eu.rekawek.coffeegb.gpu.phase;

public class VBlankPhase implements GpuPhase {

    private final int line;

    private int ticks;

    public VBlankPhase(int line) {
        this.line = line;
    }

    @Override
    public boolean tick() {
        return ++ticks < 456;
    }
}
