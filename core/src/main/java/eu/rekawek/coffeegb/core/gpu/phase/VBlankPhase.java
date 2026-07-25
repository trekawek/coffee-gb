package eu.rekawek.coffeegb.core.gpu.phase;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.gpu.GpuRegister;
import eu.rekawek.coffeegb.core.gpu.GpuRegisterValues;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

import static eu.rekawek.coffeegb.core.gpu.GpuRegister.LY;

public class VBlankPhase implements GpuPhase, StatefulComponent<VBlankPhase> {

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
    public ComponentState<VBlankPhase> captureState() {
        return new VBlankPhaseState(ticks);
    }

    @Override
    public void restoreState(ComponentState<VBlankPhase> state) {
        if (!(state instanceof VBlankPhaseState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        this.ticks = mem.ticks;
    }

    private record VBlankPhaseState(int ticks) implements ComponentState<VBlankPhase> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record VBlankPhaseMemento(int ticks) implements Memento<VBlankPhase> {
    }
}