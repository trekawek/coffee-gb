package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public class FrequencySweep implements Serializable, Originator<FrequencySweep> {

    private int period;

    private boolean negate;

    private int shift;

    private int timer;

    private int shadowFreq;

    private int nr13, nr14;

    private boolean overflow;

    private boolean counterEnabled;

    private boolean negging;

    /**
     * The overflow check following a sweep calculation is performed by a 1 MHz
     * ripple counter, rather than in the frame-sequencer callback itself.
     */
    private int calculationDelay;

    private boolean unshiftedCalculation;

    private int restartHold;

    public void start() {
        counterEnabled = false;
        calculationDelay = 0;
        unshiftedCalculation = false;
        restartHold = 0;
    }

    public void trigger(boolean wasActive, boolean lowFrequencyPhase, boolean gbc) {
        this.negging = false;
        this.overflow = false;

        this.shadowFreq = nr13 | ((nr14 & 0b111) << 8);
        this.timer = period == 0 ? 8 : period;
        this.counterEnabled = period != 0 || shift != 0;
        // The sweep adder holds its pre-restart input while the trigger pipeline drains.
        // CGB hardware keeps that hold for two additional stages.
        restartHold = (4 - (lowFrequencyPhase ? 1 : 0) + (gbc ? 2 : 0)) * 2;
        if (shift == 0) {
            cancelCalculation();
        } else {
            scheduleCalculation(shift + (wasActive ? 2 : 3), false);
        }
    }

    public void setNr10(int value) {
        this.period = (value >> 4) & 0b111;
        this.negate = (value & (1 << 3)) != 0;
        this.shift = value & 0b111;
        if (negging && !negate) {
            overflow = true;
        }
    }

    public void setNr13(int value) {
        this.nr13 = value;
    }

    public void setNr14(int value) {
        this.nr14 = value;
    }

    public int getNr13() {
        return nr13;
    }

    public int getNr14() {
        return nr14;
    }

    public void clockTick() {
        if (!counterEnabled) {
            return;
        }
        if (--timer == 0) {
            timer = period == 0 ? 8 : period;
            if (period != 0) {
                if (shift == 0) {
                    if (restartHold == 0) {
                        scheduleCalculation(1, true);
                    } else {
                        cancelCalculation();
                    }
                } else {
                    int newFreq = calculate();
                    if (!overflow) {
                        shadowFreq = newFreq;
                        nr13 = shadowFreq & 0xff;
                        nr14 = (shadowFreq & 0x700) >> 8;
                        scheduleCalculation(shift + 1, false);
                    }
                }
            }
        }
    }

    /** Advances the delayed sweep calculation by one CPU T-cycle. */
    public void tick() {
        if (restartHold > 0) {
            restartHold--;
        }
        // A zero shift disconnects the calculation counter until NR10 enables it again.
        if (calculationDelay == 0 || (shift == 0 && !unshiftedCalculation)) {
            return;
        }
        if (--calculationDelay == 0) {
            calculate();
            unshiftedCalculation = false;
        }
    }

    private void scheduleCalculation(int oneMhzTicks, boolean unshifted) {
        calculationDelay = oneMhzTicks * 4;
        unshiftedCalculation = unshifted;
    }

    private void cancelCalculation() {
        calculationDelay = 0;
        unshiftedCalculation = false;
    }

    private int calculate() {
        int freq = shadowFreq >> shift;
        if (negate) {
            freq = shadowFreq - freq;
            negging = true;
        } else {
            freq = shadowFreq + freq;
        }
        if (freq > 2047) {
            overflow = true;
        }
        return freq;
    }

    public boolean isEnabled() {
        return !overflow;
    }

    @Override
    public Memento<FrequencySweep> saveToMemento() {
        return new FrequencySweepMemento(period, negate, shift, timer, shadowFreq, nr13, nr14, overflow,
                counterEnabled, negging, calculationDelay, unshiftedCalculation, restartHold);
    }

    @Override
    public void restoreFromMemento(Memento<FrequencySweep> memento) {
        if (!(memento instanceof FrequencySweepMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.period = mem.period;
        this.negate = mem.negate;
        this.shift = mem.shift;
        this.timer = mem.timer;
        this.shadowFreq = mem.shadowFreq;
        this.nr13 = mem.nr13;
        this.nr14 = mem.nr14;
        this.overflow = mem.overflow;
        this.counterEnabled = mem.counterEnabled;
        this.negging = mem.negging;
        this.calculationDelay = mem.calculationDelay;
        this.unshiftedCalculation = mem.unshiftedCalculation;
        this.restartHold = mem.restartHold;
    }

    private record FrequencySweepMemento(int period, boolean negate, int shift, int timer, int shadowFreq, int nr13,
                                         int nr14, boolean overflow, boolean counterEnabled,
                                         boolean negging, int calculationDelay,
                                         boolean unshiftedCalculation, int restartHold) implements Memento<FrequencySweep> {
    }
}
