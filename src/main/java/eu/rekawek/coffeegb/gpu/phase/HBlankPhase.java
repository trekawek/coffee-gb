package eu.rekawek.coffeegb.gpu.phase;

public class HBlankPhase implements GpuPhase {

    private final int line;

    private int ticks;

    public HBlankPhase(int line, int ticksInLine) {
        this.line = line;
        this.ticks = ticksInLine;
    }

    @Override
    public boolean tick() {
        return ++ticks < 456;
    }
}
