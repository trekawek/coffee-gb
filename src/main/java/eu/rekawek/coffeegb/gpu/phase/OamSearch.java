package eu.rekawek.coffeegb.gpu.phase;

public class OamSearch implements GpuPhase {

    private final int line;

    private int ticks;

    public OamSearch(int line) {
        this.line = line;
    }

    @Override
    public boolean tick() {
        return ++ticks < 80;
    }

}
