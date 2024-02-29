package eu.rekawek.coffeegb.gpu.phase;

import java.io.Serializable;

public class HBlankPhase implements GpuPhase, Serializable {

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
