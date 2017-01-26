package eu.rekawek.coffeegb.gpu.phase;

import eu.rekawek.coffeegb.memory.MemoryRegisters;

public class HBlankPhase implements GpuPhase {

    private final MemoryRegisters r;

    private int ticks;

    public HBlankPhase(int ticksInLine, MemoryRegisters r) {
        this.ticks = ticksInLine;
        this.r = r;
    }

    @Override
    public boolean tick() {
        ticks++;
        return ticks < 456;
    }
}
