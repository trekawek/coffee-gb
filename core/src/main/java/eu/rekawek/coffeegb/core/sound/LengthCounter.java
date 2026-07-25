package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

public class LengthCounter implements StatefulComponent<LengthCounter> {

    private final int fullLength;

    private final FrameSequencer frameSequencer;

    private int length;

    private boolean enabled;

    public LengthCounter(int fullLength, FrameSequencer frameSequencer) {
        this.fullLength = fullLength;
        this.frameSequencer = frameSequencer;
    }

    /**
     * @return true when the counter just reached 0 (the channel gets disabled)
     */
    public boolean clockTick() {
        if (enabled && length > 0) {
            length--;
            return length == 0;
        }
        return false;
    }

    public void setLength(int length) {
        if (length == 0) {
            this.length = fullLength;
        } else {
            this.length = length;
        }
    }

    /**
     * Handles the length-related part of an NRx4 write, including the extra length clock
     * that happens when the enable bit rises while the frame sequencer's next step is one
     * that does not clock length.
     *
     * @return true when the write disabled the channel (extra clock made the counter reach
     * 0 and the trigger bit was not set)
     */
    public boolean setNr4(int value) {
        boolean enable = (value & (1 << 6)) != 0;
        boolean trigger = (value & (1 << 7)) != 0;
        boolean firstHalf = frameSequencer.isFirstHalfOfLengthPeriod();

        boolean zeroed = false;
        if (firstHalf && !enabled && enable && length > 0) {
            length--;
            zeroed = length == 0;
        }
        this.enabled = enable;

        if (trigger && length == 0) {
            length = (firstHalf && enable) ? fullLength - 1 : fullLength;
            zeroed = false;
        }
        return zeroed && !trigger;
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
        this.enabled = false;
        this.length = 0;
    }

    @Override
    public ComponentState<LengthCounter> captureState() {
        return new LengthCounterState(length, enabled);
    }

    @Override
    public void restoreState(ComponentState<LengthCounter> state) {
        if (!(state instanceof LengthCounterState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        this.length = mem.length;
        this.enabled = mem.enabled;
    }

    private record LengthCounterState(int length, boolean enabled) implements ComponentState<LengthCounter> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record LengthCounterMemento(int length, boolean enabled) implements Memento<LengthCounter> {
    }
}
