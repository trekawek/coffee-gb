package eu.rekawek.coffeegb.gpu.phase;

public class HBlankPhase implements GpuPhase {

    private int ticks;

    public HBlankPhase start(int ticksInLine) {
        this.ticks = ticksInLine;
        return this;
    }

    @Override
    public boolean tick() {
        ticks++;
        return ticks < 456;
    }

}
