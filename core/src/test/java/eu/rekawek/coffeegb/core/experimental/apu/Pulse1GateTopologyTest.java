package eu.rekawek.coffeegb.core.experimental.apu;

import eu.rekawek.coffeegb.core.sound.FrameSequencer;
import eu.rekawek.coffeegb.core.sound.FrequencySweep;
import eu.rekawek.coffeegb.core.sound.LengthCounter;
import eu.rekawek.coffeegb.core.sound.SoundMode1;
import eu.rekawek.coffeegb.core.sound.VolumeEnvelope;
import org.junit.Test;

import java.util.EnumSet;

import static eu.rekawek.coffeegb.core.experimental.apu.Pulse1GateTopology.Falsifier.ACTIVE_ENVELOPE_REGISTER_WRITE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Differential evidence for the settled DMG pulse-1 control cells. Production is an oracle for
 * externally visible state; independent signal assertions pin why each quirk occurs.
 */
public class Pulse1GateTopologyTest {

    private static final int[] REPRESENTATIVE_FREQUENCIES = {
            0x000, 0x001, 0x155, 0x3ff, 0x400, 0x555, 0x600, 0x7fe, 0x7ff
    };

    @Test
    public void twoPhaseLengthCellMatchesEveryNr14InputAndFramePhase() {
        for (int value = 0; value <= 64; value++) {
            for (boolean oldEnable : booleans()) {
                for (boolean newEnable : booleans()) {
                    for (boolean trigger : booleans()) {
                        for (boolean firstHalf : booleans()) {
                            for (boolean frameClock : booleans()) {
                                assertLengthDifferential(value, oldEnable, newEnable,
                                        trigger, firstHalf, frameClock);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void maxMinusOneReloadIsTheSameGatePulseSeenAcrossTheParallelLoad() {
        var resolution = Pulse1GateTopology.resolveLength(
                new Pulse1GateTopology.LengthState(1, false),
                new Pulse1GateTopology.LengthSignals(true, 0xc0, true, false));

        assertTrue(resolution.enableGatePulse());
        assertTrue(resolution.triggerLoadPulse());
        assertFalse(resolution.lengthStopPulse());
        assertEquals(new Pulse1GateTopology.LengthState(63, true), resolution.next());
    }

    @Test
    public void triggerLoadsEnvelopeCellsForEveryNr12Value() {
        for (int nr12 = 0; nr12 <= 0xff; nr12++) {
            VolumeEnvelope production = new VolumeEnvelope();
            production.start();
            production.setNr2(nr12, false);
            production.trigger();

            var topology = Pulse1GateTopology.resolveEnvelope(
                    Pulse1GateTopology.EnvelopeState.reset(),
                    new Pulse1GateTopology.EnvelopeSignals(
                            true, nr12, false, false, false)).next();
            topology = Pulse1GateTopology.resolveEnvelope(
                    topology,
                    new Pulse1GateTopology.EnvelopeSignals(
                            false, 0, true, false, false)).next();
            assertEquals(label(nr12, 0), production.getVolume(), topology.volume());

            for (int clock = 1; clock <= 40; clock++) {
                production.clockTick();
                topology = Pulse1GateTopology.resolveEnvelope(
                        topology,
                        new Pulse1GateTopology.EnvelopeSignals(
                                false, 0, false, true, false)).next();
                assertEquals(label(nr12, clock), production.getVolume(), topology.volume());
            }
        }
    }

    @Test
    public void activeEnvelopeWritesAreRejectedInsteadOfBeingApproximated() {
        var resolution = Pulse1GateTopology.resolveEnvelope(
                new Pulse1GateTopology.EnvelopeState(0x81, 8, 1, false),
                new Pulse1GateTopology.EnvelopeSignals(true, 0x19,
                        false, false, true));

        assertTrue(resolution.falsifiers().contains(ACTIVE_ENVELOPE_REGISTER_WRITE));
        assertEquals(0x81, resolution.next().nr12());
        assertEquals(8, resolution.next().volume());
    }

    @Test
    public void triggerSweepLoadsAndInitialOverflowMatchEveryFieldCombination() {
        for (int period = 0; period < 8; period++) {
            for (boolean negate : booleans()) {
                for (int shift = 0; shift < 8; shift++) {
                    int nr10 = period << 4 | (negate ? 0x08 : 0) | shift;
                    for (int frequency : REPRESENTATIVE_FREQUENCIES) {
                        SweepDifferential differential = new SweepDifferential();
                        differential.writeNr10(nr10);
                        differential.writeFrequencyAndTrigger(frequency);
                        differential.assertEquivalent("trigger");
                    }
                }
            }
        }
    }

    @Test
    public void sweepTimerWritebackAndSecondCheckMatchSettledProduction() {
        int[] configurations = {
                0x00, 0x01, 0x08, 0x10, 0x11, 0x12, 0x17,
                0x18, 0x19, 0x31, 0x37, 0x71, 0x77
        };
        for (int initialNr10 : configurations) {
            for (int frequency : REPRESENTATIVE_FREQUENCIES) {
                SweepDifferential differential = new SweepDifferential();
                differential.writeNr10(initialNr10);
                differential.writeFrequencyAndTrigger(frequency);
                for (int clock = 1; clock <= 20; clock++) {
                    // NR10 fields are live, but no write strobe reaches the timer's load input.
                    if (clock == 3) {
                        differential.writeNr10((initialNr10 + 0x21) & 0x7f);
                    } else if (clock == 11) {
                        differential.writeNr10((initialNr10 ^ 0x19) & 0x7f);
                    }
                    differential.sweepClock();
                    differential.assertEquivalent("clock " + clock);
                }
            }
        }
    }

    @Test
    public void nr10WriteAndSweepClockUseTheNewFieldLatchesWithoutReloadingTimer() {
        SweepDifferential differential = new SweepDifferential();
        differential.writeNr10(0x31); // timer loads 3, add, shift 1
        differential.writeFrequencyAndTrigger(0x200);
        differential.sweepClock();

        differential.writeNr10AndClock(0x11); // timer is still at 2, not reloaded to 1
        differential.assertEquivalent("write+clock before terminal");
        assertFalse(differential.lastResolution.frequencyLoadPulse());

        differential.sweepClock();
        differential.assertEquivalent("terminal uses new fields");
        assertTrue(differential.lastResolution.frequencyLoadPulse());
        assertEquals(0x300, differential.topology.visibleFrequency());
    }

    @Test
    public void negateClearIsAResetInputFedByTheNegateUsedLatch() {
        SweepDifferential harmless = new SweepDifferential();
        harmless.writeNr10(0x18); // negate selected, but shift 0 performs no trigger calculation
        harmless.writeFrequencyAndTrigger(0x200);
        harmless.writeNr10(0x10);
        harmless.assertEquivalent("clear without negate calculation");
        assertFalse(harmless.topology.overflow());

        SweepDifferential breaksLatch = new SweepDifferential();
        breaksLatch.writeNr10(0x19); // period 1, negate, shift 1
        breaksLatch.writeFrequencyAndTrigger(0x200);
        assertTrue(breaksLatch.topology.negateUsed());
        breaksLatch.writeNr10(0x11); // clear negate, keep period/shift
        breaksLatch.assertEquivalent("clear after negate calculation");
        assertTrue(breaksLatch.topology.overflow());
    }

    @Test
    public void periodZeroUsesTheTimerLoadMuxButClosesTheCalculationGate() {
        SweepDifferential differential = new SweepDifferential();
        differential.writeNr10(0x01); // period 0, shift 1 enables the timer
        differential.writeFrequencyAndTrigger(0x600);
        assertEquals(8, differential.topology.timer());

        for (int i = 0; i < 8; i++) {
            differential.sweepClock();
        }
        differential.assertEquivalent("period-zero terminal");
        assertEquals(8, differential.topology.timer());
        assertEquals(0x600, differential.topology.visibleFrequency());
        assertFalse(differential.lastResolution.calculationPulse());
    }

    @Test
    public void statusLatchResetDominatesTriggerSetAndOldState() {
        for (boolean old : booleans()) {
            for (boolean trigger : booleans()) {
                for (boolean dac : booleans()) {
                    for (boolean lengthStop : booleans()) {
                        for (boolean overflow : booleans()) {
                            var next = Pulse1GateTopology.resolveStatus(
                                    new Pulse1GateTopology.StatusState(old),
                                    new Pulse1GateTopology.StatusSignals(
                                            trigger, dac, lengthStop, overflow, false));
                            boolean expected = (old || trigger && dac)
                                    && dac && !lengthStop && !overflow;
                            assertEquals(expected, next.enabled());
                        }
                    }
                }
            }
        }
    }

    @Test
    public void settledTriggerStatusMatchesProductionForDacAndSweepOverflow() {
        int[] nr12Values = {0x00, 0x07, 0x08, 0x80, 0xf0};
        int[] frequencies = {0x200, 0x600, 0x7ff};
        for (int nr12 : nr12Values) {
            for (int frequency : frequencies) {
                SoundMode1 production = new SoundMode1(new FrameSequencer(), false);
                production.start();
                production.setByte(0xff10, 0x01); // positive, shift 1
                production.setByte(0xff12, nr12);
                production.setByte(0xff13, frequency & 0xff);
                production.setByte(0xff14, 0x80 | frequency >>> 8);
                for (int i = 0; i < 64; i++) {
                    production.tick();
                }

                var sweep = Pulse1GateTopology.resolveSweep(
                        Pulse1GateTopology.SweepState.reset(),
                        Pulse1GateTopology.SweepSignals.writeNr10(0x01)).next();
                sweep = Pulse1GateTopology.resolveSweep(sweep,
                        Pulse1GateTopology.SweepSignals.writeNr13(frequency)).next();
                sweep = Pulse1GateTopology.resolveSweep(sweep,
                        Pulse1GateTopology.SweepSignals.writeNr14(
                                0x80 | frequency >>> 8)).next();
                boolean dac = (nr12 & 0xf8) != 0;
                var status = Pulse1GateTopology.resolveStatus(
                        Pulse1GateTopology.StatusState.off(),
                        new Pulse1GateTopology.StatusSignals(
                                true, dac, false, sweep.overflow(), false));
                assertEquals("NR12=" + nr12 + ", freq=" + frequency,
                        production.isEnabled(), status.enabled());
            }
        }
    }

    @Test
    public void profileBoundariesArePinnedAsFalsifiers() {
        assertEquals(EnumSet.of(
                        Pulse1GateTopology.Falsifier.OBSERVATION_DURING_SERIAL_SWEEP_CALCULATION,
                        Pulse1GateTopology.Falsifier.ACTIVE_RETRIGGER_ON_PULSE_RELOAD_EDGE,
                        Pulse1GateTopology.Falsifier.ACTIVE_ENVELOPE_REGISTER_WRITE,
                        Pulse1GateTopology.Falsifier.CGB_SWEEP_RESTART_HOLD,
                        Pulse1GateTopology.Falsifier.CGB_POWER_ON_LENGTH_RESET,
                        Pulse1GateTopology.Falsifier.DMG_POWER_OFF_LENGTH_WRITE,
                        Pulse1GateTopology.Falsifier.DUTY_AND_FREQUENCY_TIMER_PHASE,
                        Pulse1GateTopology.Falsifier.ANALOG_DAC_TRANSIENT),
                Pulse1GateTopology.falsifiers());
    }

    private static void assertLengthDifferential(
            int value,
            boolean oldEnable,
            boolean newEnable,
            boolean trigger,
            boolean firstHalf,
            boolean frameClock) {
        FrameSequencer frameSequencer = new FrameSequencer();
        frameSequencer.reset();
        if (firstHalf) {
            frameSequencer.tick(0x1000, true, false);
            assertEquals(0, frameSequencer.tick(0x0000, true, false));
            assertTrue(frameSequencer.isFirstHalfOfLengthPeriod());
        } else {
            assertFalse(frameSequencer.isFirstHalfOfLengthPeriod());
        }

        LengthCounter production = new LengthCounter(64, frameSequencer);
        if (oldEnable) {
            production.setNr4(0x40);
        }
        if (value > 0) {
            production.setLength(value == 64 ? 0 : value);
        }
        int nr14 = (newEnable ? 0x40 : 0) | (trigger ? 0x80 : 0);
        boolean productionStop = production.setNr4(nr14);
        if (frameClock) {
            productionStop |= production.clockTick();
        }

        var topology = Pulse1GateTopology.resolveLength(
                new Pulse1GateTopology.LengthState(value, oldEnable),
                new Pulse1GateTopology.LengthSignals(
                        true, nr14, firstHalf, frameClock));
        String label = "length=" + value + ", oldE=" + oldEnable + ", newE=" + newEnable
                + ", trigger=" + trigger + ", firstHalf=" + firstHalf
                + ", frameClock=" + frameClock;
        assertEquals(label, production.getValue(), topology.next().value());
        assertEquals(label, production.isEnabled(), topology.next().enabled());
        assertEquals(label, productionStop, topology.lengthStopPulse());
    }

    private static String label(int nr12, int clock) {
        return "NR12=" + Integer.toHexString(nr12) + ", envelope clock=" + clock;
    }

    private static boolean[] booleans() {
        return new boolean[]{false, true};
    }

    private static final class SweepDifferential {

        private final FrequencySweep production = new FrequencySweep();

        private Pulse1GateTopology.SweepState topology =
                Pulse1GateTopology.SweepState.reset();

        private Pulse1GateTopology.SweepResolution lastResolution;

        private SweepDifferential() {
            production.start();
        }

        private void writeNr10(int value) {
            production.setNr10(value);
            apply(Pulse1GateTopology.SweepSignals.writeNr10(value));
            settle();
        }

        private void writeFrequencyAndTrigger(int frequency) {
            production.setNr13(frequency & 0xff);
            apply(Pulse1GateTopology.SweepSignals.writeNr13(frequency & 0xff));
            production.setNr14(0x80 | frequency >>> 8);
            production.trigger(false, false, false);
            apply(Pulse1GateTopology.SweepSignals.writeNr14(
                    0x80 | frequency >>> 8));
            settle();
        }

        private void sweepClock() {
            production.clockTick();
            apply(Pulse1GateTopology.SweepSignals.clock());
            settle();
        }

        private void writeNr10AndClock(int value) {
            production.setNr10(value);
            production.clockTick();
            apply(new Pulse1GateTopology.SweepSignals(
                    true, value, false, 0, false, 0, true));
            settle();
        }

        private void apply(Pulse1GateTopology.SweepSignals signals) {
            lastResolution = Pulse1GateTopology.resolveSweep(topology, signals);
            topology = lastResolution.next();
        }

        private void settle() {
            // Longest DMG serial-adder observation path in FrequencySweep is 40 T-cycles.
            for (int i = 0; i < 64; i++) {
                production.tick();
            }
        }

        private void assertEquivalent(String context) {
            int productionFrequency = production.getNr13()
                    | ((production.getNr14() & 0x07) << 8);
            assertEquals(context + ": frequency",
                    productionFrequency, topology.visibleFrequency());
            assertEquals(context + ": overflow",
                    !production.isEnabled(), topology.overflow());
        }
    }
}
