package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

/**
 * The frame sequencer taps the same divider chain as DIV: it advances on every falling edge
 * of bit 12 of the internal 16-bit divider (= bit 4 of the visible DIV register, 512 Hz).
 * A CPU write to FF04 while that bit is set therefore immediately advances the sequencer.
 * In CGB double speed the divider advances twice per tick, so the sequencer taps the next
 * bit up to stay at 512 Hz of real time (blargg interrupt_time's get_cpu_speed).
 */
public class FrameSequencer implements Serializable, Originator<FrameSequencer> {

    private static final int DIV_BIT = 1 << 12;

    private int step;

    private boolean previousBit;

    /**
     * @param divCounter the current value of the internal 16-bit divider
     * @param apuEnabled whether the APU is powered on
     * @param doubleSpeed whether the CGB double speed mode is active
     * @return the fired step, or -1
     */
    public int tick(int divCounter, boolean apuEnabled, boolean doubleSpeed) {
        boolean bit = (divCounter & (doubleSpeed ? DIV_BIT << 2 : DIV_BIT)) != 0;
        int firedStep = -1;
        if (previousBit && !bit && apuEnabled) {
            firedStep = step;
            step = (step + 1) & 7;
        }
        previousBit = bit;
        return firedStep;
    }

    /**
     * True when the next step to be executed is one that does not clock the length counters.
     * Writing NRx4 in this phase causes the extra length clocking.
     */
    public boolean isFirstHalfOfLengthPeriod() {
        return (step & 1) == 1;
    }

    public void reset() {
        step = 0;
    }

    @Override
    public Memento<FrameSequencer> saveToMemento() {
        return new FrameSequencerMemento(step, previousBit);
    }

    @Override
    public void restoreFromMemento(Memento<FrameSequencer> memento) {
        if (!(memento instanceof FrameSequencerMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.step = mem.step;
        this.previousBit = mem.previousBit;
    }

    private record FrameSequencerMemento(int step, boolean previousBit) implements Memento<FrameSequencer> {
    }
}
