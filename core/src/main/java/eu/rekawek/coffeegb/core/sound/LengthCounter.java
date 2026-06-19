package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public class LengthCounter implements Serializable, Originator<LengthCounter> {

    private final int fullLength;

    private final FrameSequencer frameSequencer;

    private int length;

    private boolean enabled;

    public LengthCounter(int fullLength, FrameSequencer frameSequencer) {
        this.fullLength = fullLength;
        this.frameSequencer = frameSequencer;
    }

    public void clockTick() {
        if (enabled && length > 0) {
            length--;
        }
    }

    public void setLength(int length) {
        if (length == 0) {
            this.length = fullLength;
        } else {
            this.length = length;
        }
    }

    public void setNr4(int value) {
        boolean enable = (value & (1 << 6)) != 0;
        boolean trigger = (value & (1 << 7)) != 0;

        boolean firstHalf = frameSequencer.isFirstHalfOfLengthPeriod();

        if (enabled) {
            if (length == 0 && trigger) {
                if (enable && firstHalf) {
                    setLength(fullLength - 1);
                } else {
                    setLength(fullLength);
                }
            }
        } else if (enable) {
            if (length > 0 && firstHalf) {
                length--;
            }
            if (length == 0 && trigger && firstHalf) {
                setLength(fullLength - 1);
            }
        } else {
            if (length == 0 && trigger) {
                setLength(fullLength);
            }
        }
        this.enabled = enable;
    }

    public int getValue() {
        return length;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String toString() {
        return String.format(
                "LengthCounter[l=%d,f=%d,%s]",
                length, fullLength, enabled ? "enabled" : "disabled");
    }

    void reset() {
        this.enabled = true;
        this.length = 0;
    }

    void start() {
    }

    @Override
    public Memento<LengthCounter> saveToMemento() {
        return new LengthCounterMemento(length, enabled);
    }

    @Override
    public void restoreFromMemento(Memento<LengthCounter> memento) {
        if (!(memento instanceof LengthCounterMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.length = mem.length;
        this.enabled = mem.enabled;
    }

    private record LengthCounterMemento(int length, boolean enabled) implements Memento<LengthCounter> {
    }
}
