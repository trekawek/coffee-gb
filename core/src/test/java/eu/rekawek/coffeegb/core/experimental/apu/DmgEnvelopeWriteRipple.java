package eu.rekawek.coffeegb.core.experimental.apu;

import java.util.EnumSet;
import java.util.Set;

/**
 * Detached DMG envelope-volume ripple cone for active NR12 writes.
 *
 * <p>HAFO/HEMY/HOKO/HEVO are parallel-loaded TFFNL cells. Their clocks are
 * {@code K}, {@code D ? V0 : !V0}, {@code D ? V1 : !V1}, and
 * {@code D ? V2 : !V2}, where {@code K = EG_TICK | PERIOD_ZERO | EG_STOP} and
 * {@code D = FF12.3}. Reconnecting those live clocks explains the so-called zombie behavior;
 * there is no zombie-mode state bit in this model.
 */
final class DmgEnvelopeWriteRipple {

    enum Falsifier {
        WRITE_WHILE_EG_TICK_HIGH,
        RETAINED_PERIOD_TIMER_PHASE_AFTER_ZERO_TO_NONZERO,
        SUB_T_WRITE_APERTURE_TIMING,
        CGB_ENVELOPE_PROFILE
    }

    static Set<Falsifier> profileFalsifiers() {
        return Set.of(Falsifier.SUB_T_WRITE_APERTURE_TIMING,
                Falsifier.CGB_ENVELOPE_PROFILE);
    }

    /** Visible slave latch and hidden master latch from dmg-sim's TFFNL primitive. */
    record Tff(boolean q, boolean master) {}

    record Ripple(Tff v0, Tff v1, Tff v2, Tff v3) {

        static Ripple loaded(int value, boolean directionUp, boolean baseClockHigh) {
            Ripple loaded = new Ripple(
                    loadedBit(value, 0), loadedBit(value, 1),
                    loadedBit(value, 2), loadedBit(value, 3));
            return loaded.settle(directionUp, baseClockHigh);
        }

        int value() {
            return (v0.q() ? 1 : 0)
                    | (v1.q() ? 2 : 0)
                    | (v2.q() ? 4 : 0)
                    | (v3.q() ? 8 : 0);
        }

        /** Resolve the four transparent master/slave pairs to a fixed point. */
        Ripple settle(boolean directionUp, boolean baseClockHigh) {
            Ripple current = this;
            for (int iteration = 0; iteration < 32; iteration++) {
                boolean[] clocks = {
                        baseClockHigh,
                        directionUp ? current.v0.q() : !current.v0.q(),
                        directionUp ? current.v1.q() : !current.v1.q(),
                        directionUp ? current.v2.q() : !current.v2.q()
                };
                Ripple next = new Ripple(
                        settleCell(current.v0, clocks[0]),
                        settleCell(current.v1, clocks[1]),
                        settleCell(current.v2, clocks[2]),
                        settleCell(current.v3, clocks[3]));
                if (next.equals(current)) {
                    return next;
                }
                current = next;
            }
            throw new IllegalStateException("envelope ripple did not settle");
        }

        private static Tff loadedBit(int value, int bit) {
            boolean data = (value & (1 << bit)) != 0;
            return new Tff(data, data);
        }

        private static Tff settleCell(Tff old, boolean clockHigh) {
            // TFFNL: high clock makes the master transparent; low clock publishes the master.
            return clockHigh ? new Tff(old.q(), !old.q()) : new Tff(old.master(), old.master());
        }
    }

    record State(int nr12, Ripple volume, boolean stop) {

        State {
            nr12 &= 0xff;
        }

        static State loaded(int nr12, int volume, boolean stop) {
            boolean baseClockHigh = period(nr12) == 0 || stop;
            return new State(nr12,
                    Ripple.loaded(volume, directionUp(nr12), baseClockHigh), stop);
        }

        int visibleVolume() {
            return volume.value();
        }
    }

    record WriteSignals(
            boolean nr12Write,
            int nr12Data,
            boolean egTickHigh) {

        WriteSignals {
            nr12Data &= 0xff;
        }

        static WriteSignals activeWrite(int value) {
            return new WriteSignals(true, value, false);
        }
    }

    record Resolution(
            State next,
            boolean directionClockRewire,
            boolean periodZeroFallingEdge,
            boolean writeAperturePulse,
            Set<Falsifier> falsifiers) {

        Resolution {
            falsifiers = Set.copyOf(falsifiers);
        }
    }

    static Resolution resolveWrite(State old, WriteSignals signals) {
        if (!signals.nr12Write()) {
            return new Resolution(old, false, false, false, Set.of());
        }

        int nextNr12 = signals.nr12Data();
        EnumSet<Falsifier> falsifiers = EnumSet.noneOf(Falsifier.class);
        if (signals.egTickHigh()) {
            falsifiers.add(Falsifier.WRITE_WHILE_EG_TICK_HIGH);
        }

        boolean oldDirectionUp = directionUp(old.nr12());
        boolean nextDirectionUp = directionUp(nextNr12);
        boolean oldPeriodZero = period(old.nr12()) == 0;
        boolean nextPeriodZero = period(nextNr12) == 0;
        boolean nextBaseClockHigh = nextPeriodZero || old.stop();

        // First let the final register levels rewire the live ripple clocks.
        Ripple volume = old.volume().settle(nextDirectionUp, nextBaseClockHigh);
        boolean periodZeroFallingEdge = !old.stop() && oldPeriodZero && !nextPeriodZero;

        // With K remaining high, an NR12 write through the direction/volume cone supplies the
        // short JUFY low aperture seen by hardware. It clocks V0; the other bits ripple normally.
        boolean writeAperturePulse = !old.stop() && oldPeriodZero
                && nextPeriodZero && nextDirectionUp;
        if (writeAperturePulse) {
            volume = volume.settle(nextDirectionUp, false)
                    .settle(nextDirectionUp, true);
        }

        if (periodZeroFallingEdge) {
            // The immediate volume edge is modeled. The later edge belongs to the retained
            // JOVA/KENU/KERA timer phase, which this local cone deliberately does not guess.
            falsifiers.add(Falsifier.RETAINED_PERIOD_TIMER_PHASE_AFTER_ZERO_TO_NONZERO);
        }
        return new Resolution(new State(nextNr12, volume, old.stop()),
                oldDirectionUp != nextDirectionUp,
                periodZeroFallingEdge, writeAperturePulse, falsifiers);
    }

    static State trigger(State old) {
        return State.loaded(old.nr12(), old.nr12() >>> 4, false);
    }

    private static int period(int nr12) {
        return nr12 & 0x07;
    }

    private static boolean directionUp(int nr12) {
        return (nr12 & 0x08) != 0;
    }

    private DmgEnvelopeWriteRipple() {
    }
}
