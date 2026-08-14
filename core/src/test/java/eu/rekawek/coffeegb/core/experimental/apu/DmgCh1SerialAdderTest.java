package eu.rekawek.coffeegb.core.experimental.apu;

import eu.rekawek.coffeegb.core.sound.FrequencySweep;
import org.junit.Test;

import java.util.EnumSet;

import static eu.rekawek.coffeegb.core.experimental.apu.DmgCh1SerialAdder.Falsifier.CGB_RESTART_PROFILE;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgCh1SerialAdder.Falsifier.FREQUENCY_WRITE_DURING_RESTART_PIPELINE;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgCh1SerialAdder.Falsifier.INTERMEDIATE_SUM_NODE_OBSERVATION;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgCh1SerialAdder.Falsifier.NR10_WRITE_DURING_RESTART_OR_CALCULATION;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgCh1SerialAdder.Falsifier.SUB_T_GATE_PROPAGATION;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgCh1SerialAdder.Falsifier.SWEEP_TERMINAL_DURING_RESTART_PIPELINE;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgCh1SerialAdder.Falsifier.UPSTREAM_CH1_START_APERTURE_MAPPING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Netlist traces and production differentials for the DMG CH1 serial-adder cone. */
public class DmgCh1SerialAdderTest {

    private static final int[] FREQUENCIES = {
            0x000, 0x001, 0x155, 0x3ff, 0x400, 0x555, 0x600, 0x7fe, 0x7ff
    };

    @Test
    public void restartAndShiftThreeMatchTheDmgWavedromAtEveryT() {
        DmgCh1SerialAdder.State state = DmgCh1SerialAdder.State.initial(
                0x13, 0x400, oneMhzAt(0), ajer2MhzAt(0));
        DmgCh1SerialAdder.Resolution resolution = DmgCh1SerialAdder.resolve(
                state, DmgCh1SerialAdder.Signals.clocks(oneMhzAt(0), ajer2MhzAt(0)).withStart());
        state = resolution.next();
        assertTriggerTrace(0, state, resolution);

        for (int tick = 1; tick <= 24; tick++) {
            resolution = DmgCh1SerialAdder.resolve(state,
                    DmgCh1SerialAdder.Signals.clocks(oneMhzAt(tick), ajer2MhzAt(tick)));
            state = resolution.next();
            assertTriggerTrace(tick, state, resolution);
        }

        assertEquals(0x480, state.sumResult());
        assertFalse(state.sumOverflow());
    }

    @Test
    public void bexaAndShiftThreeMatchTheDmgWavedromAtEveryT() {
        DmgCh1SerialAdder.State state = DmgCh1SerialAdder.State.initial(
                0x13, 0x200, oneMhzAt(0), ajer2MhzAt(0));
        for (int tick = 1; tick <= 20; tick++) {
            boolean bexa = tick == 4 || tick == 5;
            DmgCh1SerialAdder.Signals signals = DmgCh1SerialAdder.Signals.clocks(
                    oneMhzAt(tick), ajer2MhzAt(tick));
            if (bexa) {
                signals = signals.withBexa();
            }
            DmgCh1SerialAdder.Resolution resolution =
                    DmgCh1SerialAdder.resolve(state, signals);
            state = resolution.next();

            int expectedCounter;
            if (tick < 4) {
                expectedCounter = 7;
            } else if (tick < 7) {
                expectedCounter = 4;
            } else if (tick < 11) {
                expectedCounter = 5;
            } else if (tick < 15) {
                expectedCounter = 6;
            } else {
                expectedCounter = 7;
            }
            assertEquals("shift counter at T=" + tick, expectedCounter, state.shiftCounter());
            assertEquals("LD_SUM at T=" + tick, tick < 4 || tick >= 16, state.loadSum());
            assertEquals("calculation latch at T=" + tick,
                    tick >= 4 && tick < 16, state.calculationLatch());
            assertEquals("shift clock at T=" + tick,
                    tick == 7 || tick == 11 || tick == 15, resolution.shiftClockRise());
            assertEquals("sum load at T=" + tick, tick == 16, resolution.loadSumRise());
        }
    }

    @Test
    public void zeroShiftUsesTheTerminalDetectorButTriggerCheckGateStaysClosed() {
        Harness frame = new Harness(0x10, 0x600);
        DmgCh1SerialAdder.Resolution bexa = frame.bexaRise();
        assertFalse(bexa.frequencyUpdatePulse());
        DmgCh1SerialAdder.Resolution completed = frame.runUntilSumLoad();
        assertTrue(completed.overflowCheckPulse());
        assertTrue(frame.state.overflow());

        Harness trigger = new Harness(0x10, 0x600);
        trigger.startBeforeCurrentClockEdge();
        completed = trigger.runUntilSumLoad();
        assertFalse(completed.overflowCheckPulse());
        assertFalse(trigger.state.overflow());
    }

    @Test
    public void freeRunningRestartApertureSpansBothProductionDelayBuckets() {
        // Production labels these buckets with channel activity because it has no CH1_START
        // aperture input. The equality pins a possible replacement, not the unproven mapping
        // from channel status to which side of FEKU's edge the real write reaches.
        for (int shift = 1; shift <= 7; shift++) {
            int nr10 = 0x10 | shift;
            int productionMetEdge = productionTriggerOverflowTicks(nr10, true);
            int productionMissedEdge = productionTriggerOverflowTicks(nr10, false);

            assertEquals("met FEKU edge, shift=" + shift,
                    productionMetEdge, topologyTriggerOverflowTicks(nr10, true));
            assertEquals("missed FEKU edge, shift=" + shift,
                    productionMissedEdge, topologyTriggerOverflowTicks(nr10, false));
            assertEquals("one free-running 1 MHz period", 4,
                    productionMissedEdge - productionMetEdge);
        }
    }

    @Test
    public void triggerOverflowDifferentialMatchesEverySweepOperation() {
        for (boolean negate : booleans()) {
            for (int shift = 0; shift <= 7; shift++) {
                int nr10 = 0x10 | (negate ? 0x08 : 0) | shift;
                for (int frequency : FREQUENCIES) {
                    FrequencySweep production = configuredProduction(nr10, frequency);
                    production.trigger(true, false, false);
                    for (int tick = 0; tick < 64; tick++) {
                        production.tick();
                    }

                    Harness topology = new Harness(nr10, frequency);
                    topology.startBeforeCurrentClockEdge();
                    topology.runUntilSumLoad();
                    assertEquals(label(nr10, frequency, "trigger overflow"),
                            !production.isEnabled(), topology.state.overflow());
                    assertEquals(label(nr10, frequency, "trigger does not write frequency"),
                            frequency, topology.state.frequency());
                }
            }
        }
    }

    @Test
    public void bexaFeedbackMatchesWritebackAndSecondOverflowCheck() {
        for (boolean negate : booleans()) {
            for (int shift = 0; shift <= 7; shift++) {
                int nr10 = 0x10 | (negate ? 0x08 : 0) | shift;
                for (int frequency : FREQUENCIES) {
                    FrequencySweep production = configuredProduction(nr10, frequency);
                    production.trigger(true, false, false);
                    for (int tick = 0; tick < 64; tick++) {
                        production.tick();
                    }

                    Harness topology = new Harness(nr10, frequency);
                    topology.startBeforeCurrentClockEdge();
                    topology.runUntilSumLoad();

                    production.clockTick();
                    DmgCh1SerialAdder.Resolution bexa = topology.bexaRise();
                    assertEquals(label(nr10, frequency, "frequency update pulse"),
                            shift != 0 && production.isEnabled(), bexa.frequencyUpdatePulse());
                    assertEquals(label(nr10, frequency, "first writeback"),
                            productionFrequency(production), topology.state.frequency());

                    for (int tick = 0; tick < 64; tick++) {
                        production.tick();
                    }
                    topology.runUntilSumLoad();
                    assertEquals(label(nr10, frequency, "second overflow"),
                            !production.isEnabled(), topology.state.overflow());
                }
            }
        }
    }

    @Test
    public void unresolvedClockAndWriteCollisionsAreExecutableFalsifiers() {
        Harness harness = new Harness(0x13, 0x200);
        harness.startBeforeCurrentClockEdge();

        DmgCh1SerialAdder.Resolution frequencyWrite = harness.resolveCurrent(
                DmgCh1SerialAdder.Signals.clocks(harness.oneMhz(), harness.ajer2Mhz())
                        .withFrequency(0x300));
        assertTrue(frequencyWrite.falsifiers().contains(FREQUENCY_WRITE_DURING_RESTART_PIPELINE));

        DmgCh1SerialAdder.Resolution bexa = harness.resolveCurrent(
                DmgCh1SerialAdder.Signals.clocks(harness.oneMhz(), harness.ajer2Mhz()).withBexa());
        assertTrue(bexa.falsifiers().contains(SWEEP_TERMINAL_DURING_RESTART_PIPELINE));

        harness.advanceIdle();
        DmgCh1SerialAdder.Resolution nr10Write = harness.resolveCurrent(
                DmgCh1SerialAdder.Signals.clocks(harness.oneMhz(), harness.ajer2Mhz())
                        .withNr10(0x11));
        assertTrue(nr10Write.falsifiers().contains(NR10_WRITE_DURING_RESTART_OR_CALCULATION));
    }

    @Test
    public void cgbAndTransistorDelayRemainSeparateProfiles() {
        assertEquals(EnumSet.of(UPSTREAM_CH1_START_APERTURE_MAPPING,
                        INTERMEDIATE_SUM_NODE_OBSERVATION,
                        CGB_RESTART_PROFILE, SUB_T_GATE_PROPAGATION),
                DmgCh1SerialAdder.profileFalsifiers());
    }

    private static void assertTriggerTrace(
            int tick,
            DmgCh1SerialAdder.State state,
            DmgCh1SerialAdder.Resolution resolution) {
        assertEquals("RESTART at T=" + tick, tick >= 1 && tick < 5, state.restart());
        assertEquals("RESTART_DLY at T=" + tick,
                tick >= 9 && tick < 13, state.restartDelayed());

        int expectedCounter;
        if (tick < 1) {
            expectedCounter = 7;
        } else if (tick < 11) {
            expectedCounter = 4;
        } else if (tick < 15) {
            expectedCounter = 5;
        } else if (tick < 19) {
            expectedCounter = 6;
        } else {
            expectedCounter = 7;
        }
        assertEquals("SHIFTCOUNT at T=" + tick, expectedCounter, state.shiftCounter());
        assertEquals("LD_SUM at T=" + tick, tick < 1 || tick >= 20, state.loadSum());
        assertEquals("calculation latch at T=" + tick,
                tick >= 9 && tick < 20, state.calculationLatch());
        assertEquals("RESTART edge at T=" + tick, tick == 1, resolution.restartRise());
        assertEquals("RESTART_DLY edge at T=" + tick,
                tick == 9, resolution.restartDelayedRise());
        assertEquals("sum load edge at T=" + tick, tick == 20, resolution.loadSumRise());
    }

    private static int topologyTriggerOverflowTicks(int nr10, boolean requestMeetsEdge) {
        DmgCh1SerialAdder.State state = DmgCh1SerialAdder.State.initial(
                nr10, 0x7ff, oneMhzAt(0), ajer2MhzAt(0));
        int tick = 1;
        DmgCh1SerialAdder.Signals first = DmgCh1SerialAdder.Signals.clocks(
                oneMhzAt(tick), ajer2MhzAt(tick));
        if (requestMeetsEdge) {
            first = first.withStart();
        }
        state = DmgCh1SerialAdder.resolve(state, first).next();
        if (!requestMeetsEdge) {
            state = DmgCh1SerialAdder.resolve(state,
                    DmgCh1SerialAdder.Signals.clocks(oneMhzAt(tick), ajer2MhzAt(tick))
                            .withStart()).next();
        }
        while (!state.overflow() && tick < 100) {
            tick++;
            state = DmgCh1SerialAdder.resolve(state,
                    DmgCh1SerialAdder.Signals.clocks(oneMhzAt(tick), ajer2MhzAt(tick))).next();
        }
        assertTrue("topology did not overflow", state.overflow());
        return tick;
    }

    private static int productionTriggerOverflowTicks(int nr10, boolean wasActive) {
        FrequencySweep production = configuredProduction(nr10, 0x7ff);
        production.trigger(wasActive, false, false);
        int ticks = 0;
        while (production.isEnabled() && ticks < 100) {
            production.tick();
            ticks++;
        }
        assertFalse("production did not overflow", production.isEnabled());
        return ticks;
    }

    private static FrequencySweep configuredProduction(int nr10, int frequency) {
        FrequencySweep production = new FrequencySweep();
        production.start();
        production.setNr10(nr10);
        production.setNr13(frequency & 0xff);
        production.setNr14(frequency >>> 8);
        return production;
    }

    private static int productionFrequency(FrequencySweep production) {
        return production.getNr13() | ((production.getNr14() & 0x07) << 8);
    }

    private static String label(int nr10, int frequency, String observation) {
        return observation + ", NR10=" + Integer.toHexString(nr10)
                + ", frequency=" + Integer.toHexString(frequency);
    }

    private static boolean oneMhzAt(int tick) {
        int phase = tick & 3;
        return phase == 1 || phase == 2;
    }

    private static boolean ajer2MhzAt(int tick) {
        return (tick & 1) == 0;
    }

    private static boolean[] booleans() {
        return new boolean[]{false, true};
    }

    private static final class Harness {

        private DmgCh1SerialAdder.State state;

        private int tick;

        private Harness(int nr10, int frequency) {
            state = DmgCh1SerialAdder.State.initial(
                    nr10, frequency, oneMhzAt(0), ajer2MhzAt(0));
        }

        private void startBeforeCurrentClockEdge() {
            tick++;
            resolveCurrent(DmgCh1SerialAdder.Signals.clocks(oneMhz(), ajer2Mhz()).withStart());
        }

        private DmgCh1SerialAdder.Resolution bexaRise() {
            DmgCh1SerialAdder.Resolution result = resolveCurrent(
                    DmgCh1SerialAdder.Signals.clocks(oneMhz(), ajer2Mhz()).withBexa());
            advanceBexa();
            advanceIdle();
            return result;
        }

        private DmgCh1SerialAdder.Resolution runUntilSumLoad() {
            for (int i = 0; i < 100; i++) {
                DmgCh1SerialAdder.Resolution resolution = advanceIdle();
                if (resolution.loadSumRise()) {
                    return resolution;
                }
            }
            throw new AssertionError("sum load did not rise");
        }

        private DmgCh1SerialAdder.Resolution advanceBexa() {
            tick++;
            return resolveCurrent(
                    DmgCh1SerialAdder.Signals.clocks(oneMhz(), ajer2Mhz()).withBexa());
        }

        private DmgCh1SerialAdder.Resolution advanceIdle() {
            tick++;
            return resolveCurrent(DmgCh1SerialAdder.Signals.clocks(oneMhz(), ajer2Mhz()));
        }

        private DmgCh1SerialAdder.Resolution resolveCurrent(DmgCh1SerialAdder.Signals signals) {
            DmgCh1SerialAdder.Resolution resolution = DmgCh1SerialAdder.resolve(state, signals);
            state = resolution.next();
            return resolution;
        }

        private boolean oneMhz() {
            return oneMhzAt(tick);
        }

        private boolean ajer2Mhz() {
            return ajer2MhzAt(tick);
        }
    }
}
