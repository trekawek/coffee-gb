package eu.rekawek.coffeegb.core.gpu.phase;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public class HBlankPhase implements GpuPhase, Serializable, Originator<HBlankPhase> {

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

    @Override
    public Memento<HBlankPhase> saveToMemento() {
        return new HBlankPhaseMemento(ticks);
    }

    @Override
    public void restoreFromMemento(Memento<HBlankPhase> memento) {
        if (!(memento instanceof HBlankPhaseMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.ticks = mem.ticks;
    }

    private record HBlankPhaseMemento(int ticks) implements Memento<HBlankPhase> {
    }
}