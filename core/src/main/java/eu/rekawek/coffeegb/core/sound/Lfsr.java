package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

public class Lfsr implements StatefulComponent<Lfsr> {

    private int lfsr;

    public Lfsr() {
        reset();
    }

    public void start() {
        reset();
    }

    public void reset() {
        lfsr = 0x7fff;
    }

    public int nextBit(boolean widthMode7) {
        boolean x = ((lfsr & 1) ^ ((lfsr & 2) >> 1)) != 0;
        lfsr = lfsr >> 1;
        lfsr = lfsr | (x ? (1 << 14) : 0);
        if (widthMode7) {
            lfsr = (lfsr & ~(1 << 6)) | (x ? (1 << 6) : 0);
        }
        return 1 & ~lfsr;
    }

    int getValue() {
        return lfsr;
    }

    @Override
    public ComponentState<Lfsr> captureState() {
        return new LfsrState(lfsr);
    }

    @Override
    public void restoreState(ComponentState<Lfsr> state) {
        if (!(state instanceof LfsrState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        this.lfsr = mem.lfsr;
    }

    public record LfsrState(int lfsr) implements ComponentState<Lfsr> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    public record LfsrMemento(int lfsr) implements Memento<Lfsr> {
    }
}
