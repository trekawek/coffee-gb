package eu.rekawek.coffeegb.core.gpu.phase;

import eu.rekawek.coffeegb.core.gpu.GpuRegister;
import eu.rekawek.coffeegb.core.gpu.GpuRegisterValues;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

import static eu.rekawek.coffeegb.core.gpu.GpuRegister.LY;

public class HBlankPhase implements GpuPhase, Serializable, Originator<HBlankPhase> {

    private final GpuRegisterValues r;

    private int ticks;

    public HBlankPhase(GpuRegisterValues r) {
        this.r = r;
    }

    public HBlankPhase start(int ticksInLine) {
        this.ticks = ticksInLine;
        return this;
    }

    @Override
    public boolean tick() {
        ticks++;
        if (ticks == 456 - 1) {
            r.inc(LY);
        }
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