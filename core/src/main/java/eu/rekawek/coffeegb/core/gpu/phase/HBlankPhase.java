package eu.rekawek.coffeegb.core.gpu.phase;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.gpu.GpuRegister;
import eu.rekawek.coffeegb.core.gpu.GpuRegisterValues;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

import static eu.rekawek.coffeegb.core.gpu.GpuRegister.LY;

public class HBlankPhase implements GpuPhase, StatefulComponent<HBlankPhase> {

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
    public ComponentState<HBlankPhase> captureState() {
        return new HBlankPhaseState(ticks);
    }

    @Override
    public void restoreState(ComponentState<HBlankPhase> state) {
        if (!(state instanceof HBlankPhaseState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        this.ticks = mem.ticks;
    }

    private record HBlankPhaseState(int ticks) implements ComponentState<HBlankPhase> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record HBlankPhaseMemento(int ticks) implements Memento<HBlankPhase> {
    }
}