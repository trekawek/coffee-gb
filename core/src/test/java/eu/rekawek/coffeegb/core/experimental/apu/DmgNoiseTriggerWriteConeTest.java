package eu.rekawek.coffeegb.core.experimental.apu;

import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Executable boundary for the two nodelay gate traces. The event offsets below are external
 * fixtures; they were not fitted from {@code PolynomialCounter} or from a caller-selected
 * {@code DmgNoiseGateTopology.triggerDifferentialSeed} phase.
 */
public class DmgNoiseTriggerWriteConeTest {

    @Test
    public void resetHalfDerivesMinimalBootTraceWithoutATriggerPhaseInput() {
        DmgNoiseTriggerWriteCone cone = DmgNoiseTriggerWriteCone.resetSeed(0x09);
        cone.queueCpuTriggerWrite();
        TriggerTrace trace = traceTwoLfsrClocks(cone);

        var write = trace.atOffset().get(0);
        assertEquals(3, write.rawClockPhase());
        assertFalse(write.apuPhiHigh());
        assertFalse(write.hamaHigh());
        assertTrue(write.hogaHigh());
        assertFalse(write.ch4StartHigh());
        assertEquals(0, write.ratioCounter());

        var firstCh4Edge = trace.atOffset().get(1);
        assertTrue(firstCh4Edge.downstream().ch4Rising());
        assertTrue(firstCh4Edge.downstream().hamaRising());
        assertTrue(firstCh4Edge.hamaHigh());

        var gysuSample = trace.atOffset().get(2);
        assertEquals(1, gysuSample.rawClockPhase());
        assertTrue(gysuSample.apuPhiRising());
        assertTrue(gysuSample.ch4StartRising());
        assertTrue(gysuSample.ch4StartHigh());
        assertFalse("CH4_START clears HOGA through GUZY", gysuSample.hogaHigh());
        assertTrue(gysuSample.hamaHigh());

        assertTrue(trace.atOffset().get(9).downstream().restartRising());
        assertEquals(6, trace.atOffset().get(9).ratioCounter());
        assertTrue(trace.atOffset().get(25).downstream().delayedStartRising());
        assertFalse(trace.atOffset().get(25).frequencyDisabled());
        assertEquals(7, trace.atOffset().get(29).ratioCounter());

        // External nodelay trace: write 32020242000 ps, LFSR rises 32028294000/32032198000 ps;
        // one master T in that run is 244000 ps.
        assertArrayEquals(new int[]{33, 49}, trace.lfsrClockOffsets());
        assertEquals(2, trace.ch4StartRiseOffset());
        assertEquals(6, trace.ch4StartFallOffset());
    }

    @Test
    public void oneRawMachineIntervalDerivesSameSuiteTraceWithoutAnAlignmentSelector() {
        DmgNoiseTriggerWriteCone cone = DmgNoiseTriggerWriteCone.resetSeed(0x09);
        for (int tick = 0; tick < 4; tick++) {
            cone.tick();
        }
        cone.queueCpuTriggerWrite();
        TriggerTrace trace = traceTwoLfsrClocks(cone);

        var write = trace.atOffset().get(0);
        assertEquals(3, write.rawClockPhase());
        assertFalse(write.apuPhiHigh());
        assertTrue("the raw JESO half changed; no trigger phase was supplied", write.hamaHigh());

        var firstCh4Edge = trace.atOffset().get(1);
        assertTrue(firstCh4Edge.downstream().ch4Rising());
        assertTrue(firstCh4Edge.downstream().hamaFalling());
        assertFalse(firstCh4Edge.hamaHigh());

        var gysuSample = trace.atOffset().get(2);
        assertTrue(gysuSample.apuPhiRising());
        assertTrue(gysuSample.ch4StartRising());
        assertFalse(gysuSample.hamaHigh());
        assertTrue(trace.atOffset().get(5).downstream().restartRising());
        assertTrue(trace.atOffset().get(21).downstream().delayedStartRising());

        // External nodelay trace of SameSuite channel_4_frequency_alignment: HAMA was high at
        // HOGA assertion and the first two post-trigger LFSR clocks were +29 T and +45 T.
        assertArrayEquals(new int[]{29, 45}, trace.lfsrClockOffsets());
        assertEquals(2, trace.ch4StartRiseOffset());
    }

    @Test
    public void provenanceBoundariesAndFiniteFalsifiersAreExplicit() {
        assertEquals(new DmgNoiseTriggerWriteCone.Provenance(
                        "https://github.com/msinger/dmg-sim",
                        "ee559e1d963e1cc522df512e3bae1b4e5ff96fb5",
                        "dmg_cpu_b/dmg_cpu_b.sv",
                        "dmg_cpu_b_gameboy.sv",
                        "TIMING=nodelay",
                        "Icarus Verilog 14.0-devel (1d2aa1b)"),
                DmgNoiseTriggerWriteCone.provenance());
        assertEquals(EnumSet.of(
                        DmgNoiseTriggerWriteCone.Evidence.STATIC_NETLIST_CONNECTIVITY,
                        DmgNoiseTriggerWriteCone.Evidence.IVERILOG_NODELAY_MINIMAL_BOOT_TRACE,
                        DmgNoiseTriggerWriteCone.Evidence.IVERILOG_NODELAY_SAMESUITE_TRACE),
                DmgNoiseTriggerWriteCone.evidence());
        assertEquals(EnumSet.of(
                        DmgNoiseTriggerWriteCone.InputBoundary.RESET_RELEASED_DMG_DIVIDER_STATE,
                        DmgNoiseTriggerWriteCone.InputBoundary.FIXED_SPEED_MASTER_TICK,
                        DmgNoiseTriggerWriteCone.InputBoundary.LATCHED_NR43_BITS,
                        DmgNoiseTriggerWriteCone.InputBoundary.DAC_ALREADY_ENABLED,
                        DmgNoiseTriggerWriteCone.InputBoundary.QUEUED_CPU_NR44_D7_WRITE),
                DmgNoiseTriggerWriteCone.inputBoundaries());
        assertEquals(EnumSet.of(
                        DmgNoiseTriggerWriteCone.Falsifier.TIMING_ENABLED_PROPAGATION_REORDERS_T_EVENTS,
                        DmgNoiseTriggerWriteCone.Falsifier.RESET_RELEASE_OR_CLOCK_GATING_CHANGES_RAW_SEED,
                        DmgNoiseTriggerWriteCone.Falsifier.CPU_WRITE_USES_ANOTHER_RAW_APERTURE,
                        DmgNoiseTriggerWriteCone.Falsifier.STOP_OR_NON_CPU_WRITE_PATH,
                        DmgNoiseTriggerWriteCone.Falsifier.SIMULTANEOUS_APU_RESET_OR_DAC_DISABLE,
                        DmgNoiseTriggerWriteCone.Falsifier.LIVE_NR43_WRITE_COLLISION,
                        DmgNoiseTriggerWriteCone.Falsifier.APU_TEST_MODE_BYPASS,
                        DmgNoiseTriggerWriteCone.Falsifier.CGB_OR_DOUBLE_SPEED_CONTROL_PROFILE),
                DmgNoiseTriggerWriteCone.falsifiers());

        DmgNoiseTriggerWriteCone cone = DmgNoiseTriggerWriteCone.resetSeed(0);
        cone.queueCpuTriggerWrite();
        assertThrows(IllegalStateException.class, cone::queueCpuTriggerWrite);
        assertTrue(cone.writeQueued());
    }

    private static TriggerTrace traceTwoLfsrClocks(DmgNoiseTriggerWriteCone cone) {
        long writeTick = -1;
        int startRiseOffset = -1;
        int startFallOffset = -1;
        boolean previousStart = false;
        Map<Integer, DmgNoiseTriggerWriteCone.Observation> atOffset = new HashMap<>();
        List<Integer> lfsrOffsets = new ArrayList<>();

        for (int guard = 0; guard < 10_000 && lfsrOffsets.size() < 2; guard++) {
            var observation = cone.tick();
            if (observation.cpuWriteCommitted()) {
                if (writeTick >= 0) {
                    throw new AssertionError("unexpected second CPU write");
                }
                writeTick = observation.tick();
                previousStart = observation.ch4StartHigh();
            }
            if (writeTick < 0) {
                continue;
            }

            int offset = Math.toIntExact(observation.tick() - writeTick);
            atOffset.put(offset, observation);
            if (observation.ch4StartRising()) {
                startRiseOffset = offset;
            }
            if (previousStart && !observation.ch4StartHigh()) {
                startFallOffset = offset;
            }
            previousStart = observation.ch4StartHigh();
            if (observation.downstream().lfsrClockRising()) {
                lfsrOffsets.add(offset);
            }
        }

        if (writeTick < 0 || lfsrOffsets.size() != 2) {
            throw new AssertionError("bounded trigger trace did not settle");
        }
        return new TriggerTrace(atOffset, startRiseOffset, startFallOffset,
                lfsrOffsets.stream().mapToInt(Integer::intValue).toArray());
    }

    private record TriggerTrace(
            Map<Integer, DmgNoiseTriggerWriteCone.Observation> atOffset,
            int ch4StartRiseOffset,
            int ch4StartFallOffset,
            int[] lfsrClockOffsets) {
    }
}
