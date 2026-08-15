package eu.rekawek.coffeegb.core.experimental.apu;

import eu.rekawek.coffeegb.core.sound.Lfsr;
import eu.rekawek.coffeegb.core.sound.PolynomialCounter;
import org.junit.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static eu.rekawek.coffeegb.core.experimental.apu.DmgNoiseGateTopology.Falsifier.ANALOG_DAC_TRANSIENT;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgNoiseGateTopology.Falsifier.APU_TEST_MODE_BYPASS;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgNoiseGateTopology.Falsifier.APU_RESET_ASSERTION_AND_RELEASE;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgNoiseGateTopology.Falsifier.CGB_CLOCK_AND_RESTART_PROFILE;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgNoiseGateTopology.Falsifier.CPU_WRITE_TO_APU_PHI_PHASE;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgNoiseGateTopology.Falsifier.DAC_DISABLE_GARY_COLLISION;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgNoiseGateTopology.Falsifier.LIVE_SHIFT_MUX_WRITE_EDGE;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgNoiseGateTopology.Falsifier.SUB_T_LFSR_BUFFER_PROPAGATION;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgNoiseGateTopology.InputBoundary.CH4_AMP_EN_N_FROM_NR42;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgNoiseGateTopology.InputBoundary.CH4_START_AFTER_GYSU;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgNoiseGateTopology.InputBoundary.LATCHED_NR43_BITS;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgNoiseGateTopology.InputBoundary.LENGTH_STOP_EFOT;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgNoiseGateTopology.ProductionProjectionBoundary.COUNTDOWN_RELOADED_WRITE_WINDOW;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgNoiseGateTopology.ProductionProjectionBoundary.DAC_OFF_BACKGROUND_COUNTER;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgNoiseGateTopology.ProductionProjectionBoundary.INACTIVE_CHANNEL_LFSR_FREEZE;
import static eu.rekawek.coffeegb.core.experimental.apu.DmgNoiseGateTopology.ProductionProjectionBoundary.TRIGGER_COUNTDOWN_ALIGNMENT;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Silicon-algebra claims are tested independently from production differentials. In particular,
 * the exhaustive LFSR comparison observes complete selected-tap cycles, not JOTO's half-cycle
 * aperture, and the NR43 frequency comparison deliberately discards each trigger transient.
 */
public class DmgNoiseGateTopologyTest {

    private static final int[] FREQUENCY_INCREMENT_PERIOD = {4, 8, 16, 24, 32, 40, 48, 56};

    @Test
    public void zeroResetXnorBankIsTheExactComplementOfEveryProductionState() {
        for (int conventional = 0; conventional < 0x8000; conventional++) {
            for (boolean widthMode7 : booleans()) {
                Lfsr production = new Lfsr();
                production.restoreState(new Lfsr.LfsrState(conventional));
                int productionOutput = production.nextBit(widthMode7);

                int physical = DmgNoiseGateTopology.physicalFromConventional(conventional);
                var topology = DmgNoiseGateTopology.clockPhysicalLfsr(physical, widthMode7);
                String label = "state=" + conventional + ", width7=" + widthMode7;
                assertEquals(label, productionOutput, topology.output());
                assertEquals(label, production.captureState(), new Lfsr.LfsrState(
                        DmgNoiseGateTopology.conventionalFromPhysical(topology.physicalState())));
            }
        }
    }

    @Test
    public void complementLoadDerivesAllEightRatiosWithoutAZeroSpecialCase() {
        for (int ratio = 0; ratio < 8; ratio++) {
            assertEquals("parallel load " + ratio, 7 - ratio,
                    DmgNoiseGateTopology.ratioParallelLoad(ratio));
            DmgNoiseGateTopology topology = DmgNoiseGateTopology.steady(ratio);
            int previous = -1;
            int intervals = 0;
            for (int tick = 1; intervals < 4; tick++) {
                if (topology.tick().noiseCounterClockRising()) {
                    if (previous >= 0) {
                        assertEquals("ratio " + ratio, FREQUENCY_INCREMENT_PERIOD[ratio],
                                tick - previous);
                        intervals++;
                    }
                    previous = tick;
                }
            }
        }
    }

    @Test
    public void selectedRippleTapMatchesProductionForEveryClockedNr43Field() {
        for (int shift = 0; shift < 14; shift++) {
            for (int ratio = 0; ratio < 8; ratio++) {
                int nr43 = shift << 4 | ratio;
                int expectedPeriod = FREQUENCY_INCREMENT_PERIOD[ratio] << (shift + 1);
                int topologyPeriod = topologyLfsrPeriod(nr43);
                int productionPeriod = productionLfsrPeriod(nr43);
                String label = "shift=" + shift + ", ratio=" + ratio;
                assertEquals(label, expectedPeriod, topologyPeriod);
                assertEquals(label, expectedPeriod, productionPeriod);
            }
        }

        // ESEP is the fourteenth and final ripple stage. NR43 shifts 14 and 15 select no wire.
        for (int counter = 0; counter < 0x4000; counter++) {
            assertFalse(DmgNoiseGateTopology.selectedTap(counter, 14));
            assertFalse(DmgNoiseGateTopology.selectedTap(counter, 15));
        }
    }

    @Test
    public void liveRatioWritesOnlyReachTheTransparentPrescalerLoadAperture() {
        DmgNoiseGateTopology inside = DmgNoiseGateTopology.steady(1);
        while (!inside.tick().noiseCounterClockRising()) {
            // GARY remains high from the sampling edge through this gated CH4 pulse.
        }
        assertTrue(inside.ratioLoadHigh());
        inside.writeNr43(5);
        assertEquals(2, inside.ratioCounter());

        DmgNoiseGateTopology outside = DmgNoiseGateTopology.steady(1);
        while (outside.ratioLoadHigh() || outside.frequencyCounter() == 0) {
            outside.tick();
        }
        int retainedRun = outside.ratioCounter();
        outside.writeNr43(5);
        assertEquals("a closed TFFNL load ignores new parallel data", retainedRun,
                outside.ratioCounter());
        assertNotEquals(DmgNoiseGateTopology.ratioParallelLoad(5), outside.ratioCounter());
    }

    @Test
    public void restartIsAHamaSampledPulseAndOnlyTheLfsrSideIsReset() {
        for (int requestPhase = 0; requestPhase < 8; requestPhase++) {
            DmgNoiseGateTopology topology = DmgNoiseGateTopology.steady(3);
            while (topology.frequencyCounter() < 3) {
                topology.tick();
            }
            for (int i = 0; i < requestPhase; i++) {
                topology.tick();
            }
            topology.latchSynchronizedStart();

            int restartAt = -1;
            int restartEndedAt = -1;
            int delayedStartAt = -1;
            for (int tick = 1; delayedStartAt < 0; tick++) {
                int oldFrequencyCounter = topology.frequencyCounter();
                var observation = topology.tick();
                if (observation.restartRising()) {
                    assertTrue(observation.hamaRising());
                    assertEquals("restart must preserve CEXO..ESEP", oldFrequencyCounter,
                            topology.frequencyCounter());
                    assertEquals(0, topology.physicalLfsr());
                    assertEquals(DmgNoiseGateTopology.ratioParallelLoad(3),
                            topology.ratioCounter());
                    restartAt = tick;
                }
                if (restartAt >= 0 && restartEndedAt < 0 && !observation.restartHigh()) {
                    restartEndedAt = tick;
                }
                if (observation.delayedStartRising()) {
                    assertTrue(observation.hamaRising());
                    delayedStartAt = tick;
                }
            }
            assertEquals("GONE is high for one HAMA period", 8, restartEndedAt - restartAt);
            assertEquals("GATY follows two HAMA samples after GONE", 16,
                    delayedStartAt - restartAt);
            assertFalse(topology.frequencyDisabled());
        }
    }

    @Test
    public void triggerTransientCannotBeCollapsedToOnePhaseOffsetAcrossNr43() {
        // The production API and this cone start on opposite sides of GYSU, so first try every
        // possible CH4_1MHZ/HAMA seed rather than declaring a convenient offset. Each vector
        // contains the first two post-reset LFSR clocks for all 112 clocked NR43 fields.
        int[][][][] production = new int[2][4][14 * 8][2];
        int[][][][] topology = new int[2][8][14 * 8][2];
        for (int activeIndex = 0; activeIndex < 2; activeIndex++) {
            boolean activeRetrigger = activeIndex != 0;
            for (int alignment = 0; alignment < 4; alignment++) {
                for (int shift = 0; shift < 14; shift++) {
                    for (int ratio = 0; ratio < 8; ratio++) {
                        int field = shift * 8 + ratio;
                        production[activeIndex][alignment][field] = productionTriggerEvents(
                                shift << 4 | ratio, alignment, activeRetrigger);
                    }
                }
            }
            for (int clockPhase = 0; clockPhase < 8; clockPhase++) {
                for (int shift = 0; shift < 14; shift++) {
                    for (int ratio = 0; ratio < 8; ratio++) {
                        int field = shift * 8 + ratio;
                        topology[activeIndex][clockPhase][field] = topologyTriggerEvents(
                                shift << 4 | ratio, clockPhase, activeRetrigger);
                    }
                }
            }
        }

        for (int activeIndex = 0; activeIndex < 2; activeIndex++) {
            for (int alignment = 0; alignment < 4; alignment++) {
                Set<Integer> matchingClockPhases = new HashSet<>();
                for (int clockPhase = 0; clockPhase < 8; clockPhase++) {
                    if (hasOneOffset(production[activeIndex][alignment],
                            topology[activeIndex][clockPhase])) {
                        matchingClockPhases.add(clockPhase);
                    }
                }
                Set<Integer> expected = activeIndex == 1 && alignment == 2
                        ? Set.of(0, 1, 2, 3, 6, 7)
                        : Set.of();
                assertEquals("active=" + (activeIndex != 0) + ", alignment=" + alignment,
                        expected, matchingClockPhases);
            }
        }

        // Executable witness: with inactive alignment zero and candidate phase zero, ratio zero
        // would require offset -5 while ratio one would require -9. A scheduler offset cannot
        // reconcile both, even though each channel's second clock has the correct steady period.
        assertArrayEquals(new int[]{11, 19}, production[0][0][0]);
        assertArrayEquals(new int[]{16, 24}, topology[0][0][0]);
        assertArrayEquals(new int[]{19, 35}, production[0][0][1]);
        assertArrayEquals(new int[]{28, 44}, topology[0][0][1]);
    }

    @Test
    public void channelActivityMasksOutputButDoesNotEnterThePhysicalClockCone() {
        DmgNoiseGateTopology topology = DmgNoiseGateTopology.steady(0);
        topology.driveLengthStopPulse();
        int clocks = 0;
        int initial = topology.physicalLfsr();
        for (int tick = 0; tick < 128; tick++) {
            var observation = topology.tick();
            clocks += observation.lfsrClockRising() ? 1 : 0;
            assertEquals(0, observation.digitalOutput());
        }
        assertTrue(clocks > 0);
        assertNotEquals(initial, topology.physicalLfsr());
        assertFalse(topology.channelActive());
    }

    @Test
    public void phaseProtocolAndAllBoundariesAreExplicit() {
        DmgNoiseGateTopology topology = DmgNoiseGateTopology.steady(0);
        assertThrows(IllegalStateException.class, topology::resolve);
        assertThrows(IllegalStateException.class, topology::commit);
        topology.drive();
        assertThrows(IllegalStateException.class, topology::drive);
        assertThrows(IllegalStateException.class, topology::observation);
        topology.resolve();
        assertThrows(IllegalStateException.class, topology::resolve);
        topology.observation();
        topology.commit();

        assertEquals(EnumSet.of(
                        CGB_CLOCK_AND_RESTART_PROFILE,
                        CPU_WRITE_TO_APU_PHI_PHASE,
                        SUB_T_LFSR_BUFFER_PROPAGATION,
                        LIVE_SHIFT_MUX_WRITE_EDGE,
                        DAC_DISABLE_GARY_COLLISION,
                        APU_TEST_MODE_BYPASS,
                        ANALOG_DAC_TRANSIENT,
                        APU_RESET_ASSERTION_AND_RELEASE),
                DmgNoiseGateTopology.falsifiers());
        assertEquals(EnumSet.of(
                        LATCHED_NR43_BITS,
                        CH4_START_AFTER_GYSU,
                        CH4_AMP_EN_N_FROM_NR42,
                        LENGTH_STOP_EFOT),
                DmgNoiseGateTopology.inputBoundaries());
        assertEquals(EnumSet.of(
                        TRIGGER_COUNTDOWN_ALIGNMENT,
                        COUNTDOWN_RELOADED_WRITE_WINDOW,
                        INACTIVE_CHANNEL_LFSR_FREEZE,
                        DAC_OFF_BACKGROUND_COUNTER),
                DmgNoiseGateTopology.productionProjectionBoundaries());
    }

    private static int topologyLfsrPeriod(int nr43) {
        DmgNoiseGateTopology topology = DmgNoiseGateTopology.steady(nr43);
        int previous = -1;
        for (int tick = 1; ; tick++) {
            if (topology.tick().lfsrClockRising()) {
                if (previous >= 0) {
                    return tick - previous;
                }
                previous = tick;
            }
        }
    }

    private static int productionLfsrPeriod(int nr43) {
        PolynomialCounter production = new PolynomialCounter();
        production.start();
        production.setNr43(nr43);
        production.trigger();
        int previous = -1;
        for (int tick = 1; ; tick++) {
            if (production.tick()) {
                if (previous >= 0) {
                    return tick - previous;
                }
                previous = tick;
            }
        }
    }

    private static int[] productionTriggerEvents(
            int nr43, int alignment, boolean activeRetrigger) {
        PolynomialCounter production = new PolynomialCounter();
        production.start();
        production.setNr43(nr43);
        if (activeRetrigger) {
            production.trigger();
        }
        // Two master ticks leave clock2Mhz in the same phase and advance alignment once. The
        // longest prelude is six ticks, shorter than every initial counter reload.
        for (int tick = 0; tick < alignment * 2; tick++) {
            production.tick();
        }
        production.trigger();

        int[] events = new int[2];
        int count = 0;
        for (int tick = 1; count < events.length; tick++) {
            if (production.tick()) {
                events[count++] = tick;
            }
        }
        return events;
    }

    private static int[] topologyTriggerEvents(
            int nr43, int clockPhase, boolean activeRetrigger) {
        DmgNoiseGateTopology topology = DmgNoiseGateTopology.triggerDifferentialSeed(
                nr43, clockPhase, activeRetrigger);
        topology.latchSynchronizedStart();
        int[] events = new int[2];
        int count = 0;
        boolean restartSeen = false;
        for (int tick = 1; count < events.length; tick++) {
            var observation = topology.tick();
            restartSeen |= observation.restartRising();
            if (restartSeen && observation.lfsrClockRising()) {
                events[count++] = tick;
            }
        }
        return events;
    }

    private static boolean hasOneOffset(int[][] production, int[][] topology) {
        Integer offset = null;
        for (int field = 0; field < production.length; field++) {
            for (int event = 0; event < 2; event++) {
                int difference = production[field][event] - topology[field][event];
                if (offset == null) {
                    offset = difference;
                } else if (offset != difference) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean[] booleans() {
        return new boolean[]{false, true};
    }
}
