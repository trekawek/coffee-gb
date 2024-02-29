package eu.rekawek.coffeegb.gpu.phase;

import java.io.Serializable;

public class VBlankPhase implements GpuPhase, Serializable {

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
