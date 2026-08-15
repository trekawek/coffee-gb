package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

/**
 * The frame sequencer taps the same divider chain as DIV: it advances on every falling edge
 * of bit 12 of the internal 16-bit divider (= bit 4 of the visible DIV register, 512 Hz).
 * A CPU write to FF04 while that bit is set therefore immediately advances the sequencer.
 * In CGB double speed the divider advances twice per tick, so the sequencer taps the next
 * bit up to stay at 512 Hz of real time (blargg interrupt_time's get_cpu_speed).
 */
public class FrameSequencer implements StatefulComponent<FrameSequencer> {

    private static final int DIV_BIT = 1 << 12;

    private static final int BLOCKED_STEP = -1;

    /** Next ripple phase; -1 represents the powered-high clock pulse blocked at the input. */
    private int rippleStep;

    /** BARA.q, sampling the selected DIV tap before it clocks the ripple. */
    private boolean bara;

    /**
     * @param divCounter the current value of the internal 16-bit divider
     * @param apuEnabled whether the APU is powered on
     * @param doubleSpeed whether the CGB double speed mode is active
     * @return the fired step, or -1
     */
    public int tick(int divCounter, boolean apuEnabled, boolean doubleSpeed) {
        boolean bit = (divCounter & (doubleSpeed ? DIV_BIT << 1 : DIV_BIT)) != 0;
        int firedStep = -1;
        if (bara && !bit && apuEnabled) {
            firedStep = rippleStep;
            rippleStep = (rippleStep + 1) & 7;
        }
        bara = bit;
        return firedStep;
    }

    /**
     * True when the next step to be executed is one that does not clock the length counters.
     * Writing NRx4 in this phase causes the extra length clocking.
     */
    public boolean isFirstHalfOfLengthPeriod() {
        return rippleStep == BLOCKED_STEP || (rippleStep & 1) == 1;
    }

    /** Owner-thread debugger view of the next sequencer step. */
    int getDebugStep() {
        return Math.max(rippleStep, 0);
    }

    public void reset() {
        rippleStep = 0;
    }

    /**
     * Powers the divider down/up. If the selected DIV bit is already high, CGB hardware
     * suppresses the first falling edge after power-on.
     */
    public void reset(int divCounter, boolean doubleSpeed) {
        int selectedBit = doubleSpeed ? DIV_BIT << 1 : DIV_BIT;
        int phase = divCounter & (selectedBit * 2 - 1);
        // NR52 is latched at the end of the CPU write cycle. If power is enabled in
        // the final four divider clocks before the selected DIV bit rises, the frame
        // sequencer has already crossed into step 1 by the time channel writes can
        // observe it. This changes both the NRx4 extra-length clock and which later
        // falling edges clock length (Gambatte ch2_late_reset_nr52_2b).
        int step = phase >= selectedBit - 4 && phase < selectedBit ? 1 : 0;
        bara = (divCounter & selectedBit) != 0;
        rippleStep = bara ? BLOCKED_STEP : step;
    }

    @Override
    public ComponentState<FrameSequencer> captureState() {
        return new FrameSequencerState(Math.max(rippleStep, 0), bara,
                rippleStep == BLOCKED_STEP);
    }

    @Override
    public void restoreState(ComponentState<FrameSequencer> state) {
        if (!(state instanceof FrameSequencerState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        this.rippleStep = mem.skipNextEdge ? BLOCKED_STEP : mem.step;
        this.bara = mem.previousBit;
    }

    private record FrameSequencerState(int step, boolean previousBit,
                                         boolean skipNextEdge) implements ComponentState<FrameSequencer> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record FrameSequencerMemento(int step, boolean previousBit,
                                         boolean skipNextEdge) implements Memento<FrameSequencer> {
    }
}
