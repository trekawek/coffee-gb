package eu.rekawek.coffeegb.core.experimental.apu;

import java.util.EnumSet;
import java.util.Set;

/**
 * Settled DMG channel-1 control decomposition.
 *
 * <p>This experiment models register and frame-clock effects as signals feeding four retained
 * cells. It intentionally omits the waveform timer and the time taken by the serial sweep adder;
 * observations are made after that adder has settled. There are no opcode, ROM-test, or callback
 * identities in the model.
 *
 * <p><strong>Evidence label: behavioral decomposition plus production differential.</strong>
 * {@code resolveLength}, {@code resolveSweep}, and {@code resolveEnvelope} directly encode the
 * settled feature truth tables with semantic branches. Signal-shaped inputs and retained records
 * make responsibilities clearer, but do not by themselves show that the quirks emerge from gate
 * connectivity. The separate serial-adder and envelope-ripple experiments carry their own, more
 * circuit-specific evidence boundaries.
 */
final class Pulse1GateTopology {

    enum Falsifier {
        OBSERVATION_DURING_SERIAL_SWEEP_CALCULATION,
        ACTIVE_RETRIGGER_ON_PULSE_RELOAD_EDGE,
        ACTIVE_ENVELOPE_REGISTER_WRITE,
        CGB_SWEEP_RESTART_HOLD,
        CGB_POWER_ON_LENGTH_RESET,
        DMG_POWER_OFF_LENGTH_WRITE,
        DUTY_AND_FREQUENCY_TIMER_PHASE,
        ANALOG_DAC_TRANSIENT
    }

    static Set<Falsifier> falsifiers() {
        return Set.copyOf(EnumSet.allOf(Falsifier.class));
    }

    record LengthState(int value, boolean enabled) {

        LengthState {
            if (value < 0 || value > 64) {
                throw new IllegalArgumentException("length outside 0..64: " + value);
            }
        }
    }

    record LengthSignals(
            boolean nr14Write,
            int nr14Data,
            boolean firstHalf,
            boolean frameLengthClock) {

        LengthSignals {
            nr14Data &= 0xff;
        }
    }

    record LengthResolution(
            LengthState next,
            boolean enableGatePulse,
            boolean triggerLoadPulse,
            boolean lengthStopPulse) {}

    /**
     * Two transparent phases explain the length quirks. The enable write first opens the length
     * clock gate. A trigger then parallel-loads a zero counter; if that gate is still high, the
     * freshly loaded value advances once before the latch closes. A frame edge, when present,
     * follows the CPU write phase.
     */
    static LengthResolution resolveLength(LengthState old, LengthSignals signals) {
        boolean oldEnable = old.enabled();
        boolean newEnable = signals.nr14Write()
                ? (signals.nr14Data() & 0x40) != 0
                : oldEnable;
        boolean trigger = signals.nr14Write() && (signals.nr14Data() & 0x80) != 0;
        boolean enableGatePulse = signals.nr14Write()
                && signals.firstHalf() && !oldEnable && newEnable;

        int afterEnableGate = enableGatePulse ? decrement(old.value()) : old.value();
        boolean triggerLoad = trigger && afterEnableGate == 0;
        int afterLoad = triggerLoad ? 64 : afterEnableGate;
        // The same level which produced the enable-edge pulse remains visible to the newly
        // parallel-loaded counter for the second transparent phase.
        if (triggerLoad && signals.firstHalf() && newEnable) {
            afterLoad = decrement(afterLoad);
        }

        boolean stopFromEnableGate = enableGatePulse && old.value() > 0
                && afterEnableGate == 0 && !trigger;
        boolean stopFromFrame = false;
        if (signals.frameLengthClock() && newEnable && afterLoad > 0) {
            afterLoad = decrement(afterLoad);
            stopFromFrame = afterLoad == 0;
        }
        return new LengthResolution(
                new LengthState(afterLoad, newEnable),
                enableGatePulse,
                triggerLoad,
                stopFromEnableGate || stopFromFrame);
    }

    private static int decrement(int value) {
        return value == 0 ? 0 : value - 1;
    }

    record SweepState(
            int nr10,
            int nr13,
            int nr14Low,
            int shadow,
            int timer,
            boolean timerEnabled,
            boolean negateUsed,
            boolean overflow) {

        SweepState {
            nr10 &= 0x7f;
            nr13 &= 0xff;
            nr14Low &= 0x07;
            shadow &= 0x7ff;
        }

        static SweepState reset() {
            return new SweepState(0, 0, 0, 0, 0, false, false, false);
        }

        int visibleFrequency() {
            return nr13 | (nr14Low << 8);
        }
    }

    record SweepSignals(
            boolean nr10Write,
            int nr10Data,
            boolean nr13Write,
            int nr13Data,
            boolean nr14Write,
            int nr14Data,
            boolean sweepClock) {

        SweepSignals {
            nr10Data &= 0xff;
            nr13Data &= 0xff;
            nr14Data &= 0xff;
        }

        static SweepSignals writeNr10(int value) {
            return new SweepSignals(true, value, false, 0, false, 0, false);
        }

        static SweepSignals writeNr13(int value) {
            return new SweepSignals(false, 0, true, value, false, 0, false);
        }

        static SweepSignals writeNr14(int value) {
            return new SweepSignals(false, 0, false, 0, true, value, false);
        }

        static SweepSignals clock() {
            return new SweepSignals(false, 0, false, 0, false, 0, true);
        }
    }

    record SweepResolution(
            SweepState next,
            boolean triggerLoadPulse,
            boolean calculationPulse,
            boolean frequencyLoadPulse,
            boolean secondOverflowCheckPulse) {}

    /**
     * Register strobes feed the field latches, trigger feeds the shadow/timer parallel loads, and
     * the terminal timer pulse feeds the serial-adder latch. The second overflow calculation is
     * simply feedback from the frequency-load strobe to the adder's check input.
     */
    static SweepResolution resolveSweep(SweepState old, SweepSignals signals) {
        int nr10 = signals.nr10Write() ? signals.nr10Data() & 0x7f : old.nr10();
        int nr13 = signals.nr13Write() ? signals.nr13Data() : old.nr13();
        int nr14Low = signals.nr14Write() ? signals.nr14Data() & 0x07 : old.nr14Low();
        int shadow = old.shadow();
        int timer = old.timer();
        boolean timerEnabled = old.timerEnabled();
        boolean negateUsed = old.negateUsed();
        boolean overflow = old.overflow();

        boolean trigger = signals.nr14Write() && (signals.nr14Data() & 0x80) != 0;
        boolean negateBreakPulse = signals.nr10Write()
                && old.negateUsed() && !negate(nr10);
        if (negateBreakPulse) {
            overflow = true;
        }

        boolean calculationPulse = false;
        boolean frequencyLoadPulse = false;
        boolean secondCheckPulse = false;

        if (trigger) {
            shadow = nr13 | (nr14Low << 8);
            timer = timerLoad(nr10);
            timerEnabled = period(nr10) != 0 || shift(nr10) != 0;
            negateUsed = false;
            overflow = false;

            if (shift(nr10) != 0) {
                calculationPulse = true;
                Calculation calculation = calculate(shadow, nr10);
                negateUsed |= calculation.usedNegate();
                overflow |= calculation.overflow();
            }
        }

        if (signals.sweepClock() && timerEnabled) {
            timer--;
            if (timer == 0) {
                timer = timerLoad(nr10);
                if (period(nr10) != 0) {
                    calculationPulse = true;
                    Calculation first = calculate(shadow, nr10);
                    negateUsed |= first.usedNegate();
                    overflow |= first.overflow();
                    if (!overflow && shift(nr10) != 0) {
                        frequencyLoadPulse = true;
                        shadow = first.result();
                        nr13 = shadow & 0xff;
                        nr14Low = shadow >>> 8;

                        secondCheckPulse = true;
                        Calculation second = calculate(shadow, nr10);
                        negateUsed |= second.usedNegate();
                        overflow |= second.overflow();
                    }
                }
            }
        }

        return new SweepResolution(
                new SweepState(nr10, nr13, nr14Low, shadow, timer,
                        timerEnabled, negateUsed, overflow),
                trigger,
                calculationPulse,
                frequencyLoadPulse,
                secondCheckPulse);
    }

    private record Calculation(int result, boolean overflow, boolean usedNegate) {}

    private static Calculation calculate(int shadow, int nr10) {
        int delta = shadow >>> shift(nr10);
        boolean negate = negate(nr10);
        int result = negate ? shadow - delta : shadow + delta;
        return new Calculation(result, result > 0x7ff, negate);
    }

    private static int period(int nr10) {
        return (nr10 >>> 4) & 0x07;
    }

    private static boolean negate(int nr10) {
        return (nr10 & 0x08) != 0;
    }

    private static int shift(int nr10) {
        return nr10 & 0x07;
    }

    private static int timerLoad(int nr10) {
        int period = period(nr10);
        return period == 0 ? 8 : period;
    }

    record EnvelopeState(int nr12, int volume, int timer, boolean finished) {

        EnvelopeState {
            nr12 &= 0xff;
            volume &= 0x0f;
        }

        static EnvelopeState reset() {
            return new EnvelopeState(0, 0, 0, true);
        }
    }

    record EnvelopeSignals(
            boolean nr12Write,
            int nr12Data,
            boolean trigger,
            boolean envelopeClock,
            boolean channelActive) {

        EnvelopeSignals {
            nr12Data &= 0xff;
        }
    }

    record EnvelopeResolution(EnvelopeState next, Set<Falsifier> falsifiers) {

        EnvelopeResolution {
            falsifiers = Set.copyOf(falsifiers);
        }
    }

    /** Trigger is the only parallel load included here; active NR12 writes are a named boundary. */
    static EnvelopeResolution resolveEnvelope(EnvelopeState old, EnvelopeSignals signals) {
        EnumSet<Falsifier> falsifiers = EnumSet.noneOf(Falsifier.class);
        int nr12 = old.nr12();
        int volume = old.volume();
        int timer = old.timer();
        boolean finished = old.finished();

        if (signals.nr12Write()) {
            if (signals.channelActive()) {
                falsifiers.add(Falsifier.ACTIVE_ENVELOPE_REGISTER_WRITE);
            } else {
                nr12 = signals.nr12Data();
            }
        }
        if (signals.trigger()) {
            volume = nr12 >>> 4;
            timer = envelopeTimerLoad(nr12);
            finished = false;
        }
        if (signals.envelopeClock() && !finished) {
            int direction = (nr12 & 0x08) == 0 ? -1 : 1;
            boolean boundary = volume == 0 && direction < 0
                    || volume == 15 && direction > 0;
            if (boundary) {
                finished = true;
            } else if ((nr12 & 0x07) != 0 && --timer <= 0) {
                timer = envelopeTimerLoad(nr12);
                volume += direction;
            }
        }
        return new EnvelopeResolution(
                new EnvelopeState(nr12, volume, timer, finished), falsifiers);
    }

    private static int envelopeTimerLoad(int nr12) {
        int period = nr12 & 0x07;
        return period == 0 ? 8 : period;
    }

    record StatusState(boolean enabled) {

        static StatusState off() {
            return new StatusState(false);
        }
    }

    record StatusSignals(
            boolean trigger,
            boolean dacOn,
            boolean lengthStop,
            boolean sweepOverflow,
            boolean apuReset) {}

    /** The channel status NAND latch is reset-dominant. */
    static StatusState resolveStatus(StatusState old, StatusSignals signals) {
        boolean set = signals.trigger() && signals.dacOn();
        boolean reset = signals.apuReset() || !signals.dacOn()
                || signals.lengthStop() || signals.sweepOverflow();
        return new StatusState((old.enabled() || set) && !reset);
    }

    private Pulse1GateTopology() {
    }
}
