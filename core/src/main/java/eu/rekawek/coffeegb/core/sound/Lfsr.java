package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public class Lfsr implements Serializable, Originator<Lfsr> {

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
            lfsr = lfsr | (x ? (1 << 6) : 0);
        }
        return 1 & ~lfsr;
    }

    int getValue() {
        return lfsr;
    }

    @Override
    public Memento<Lfsr> saveToMemento() {
        return new LfsrMemento(lfsr);
    }

    @Override
    public void restoreFromMemento(Memento<Lfsr> memento) {
        if (!(memento instanceof LfsrMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.lfsr = mem.lfsr;
    }

    public record LfsrMemento(int lfsr) implements Memento<Lfsr> {
    }
}
