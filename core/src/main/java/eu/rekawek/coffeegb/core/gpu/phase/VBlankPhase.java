package eu.rekawek.coffeegb.core.gpu.phase;

import eu.rekawek.coffeegb.core.gpu.GpuRegister;
import eu.rekawek.coffeegb.core.gpu.GpuRegisterValues;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

import static eu.rekawek.coffeegb.core.gpu.GpuRegister.LY;

public class VBlankPhase implements GpuPhase, Serializable, Originator<VBlankPhase> {

    private final GpuRegisterValues r;

    private int ticks;

    public VBlankPhase(GpuRegisterValues r) {
        this.r = r;
    }

    public VBlankPhase start() {
        ticks = 0;
        return this;
    }

    @Override
    public boolean tick() {
        ticks++;
        if (ticks == 456) {
            r.inc(LY);
        }
        return ticks < 456;
    }

    @Override
    public Memento<VBlankPhase> saveToMemento() {
        return new VBlankPhaseMemento(ticks);
    }

    @Override
    public void restoreFromMemento(Memento<VBlankPhase> memento) {
        if (!(memento instanceof VBlankPhaseMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.ticks = mem.ticks;
    }

    private record VBlankPhaseMemento(int ticks) implements Memento<VBlankPhase> {
    }
}