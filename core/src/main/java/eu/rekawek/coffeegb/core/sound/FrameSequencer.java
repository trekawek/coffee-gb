package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public class FrameSequencer implements Serializable, Originator<FrameSequencer> {

    static final int DIVIDER = Gameboy.TICKS_PER_SEC / 512;

    private int counter;

    private int step;

    public int tick() {
        if (++counter >= DIVIDER) {
            counter = 0;
            int firedStep = step;
            step = (step + 1) & 7;
            return firedStep;
        }
        return -1;
    }

    public boolean isFirstHalfOfLengthPeriod() {
        return (step & 1) == 0;
    }

    public void reset() {
        counter = 0;
        step = 0;
    }

    @Override
    public Memento<FrameSequencer> saveToMemento() {
        return new FrameSequencerMemento(counter, step);
    }

    @Override
    public void restoreFromMemento(Memento<FrameSequencer> memento) {
        if (!(memento instanceof FrameSequencerMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.counter = mem.counter;
        this.step = mem.step;
    }

    private record FrameSequencerMemento(int counter, int step) implements Memento<FrameSequencer> {
    }
}
