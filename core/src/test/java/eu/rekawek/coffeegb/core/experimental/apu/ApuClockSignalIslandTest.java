package eu.rekawek.coffeegb.core.experimental.apu;

import eu.rekawek.coffeegb.core.sound.FrameSequencer;
import eu.rekawek.coffeegb.core.sound.LengthCounter;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.experimental.apu.ApuClockSignalIsland.ENVELOPE_PULSE;
import static eu.rekawek.coffeegb.core.experimental.apu.ApuClockSignalIsland.EXTERNAL_CGB_BOOT_DIV_OFFSET;
import static eu.rekawek.coffeegb.core.experimental.apu.ApuClockSignalIsland.EXTERNAL_CGB_DOUBLE_SPEED_TAP;
import static eu.rekawek.coffeegb.core.experimental.apu.ApuClockSignalIsland.EXTERNAL_CGB_SPEED_SWITCH_COMMIT_PHASE;
import static eu.rekawek.coffeegb.core.experimental.apu.ApuClockSignalIsland.EXTERNAL_NR52_LATE_WRITE_ADAPTER;
import static eu.rekawek.coffeegb.core.experimental.apu.ApuClockSignalIsland.EXTERNAL_POWER_ON_HIGH_TAP_SUPPRESSION;
import static eu.rekawek.coffeegb.core.experimental.apu.ApuClockSignalIsland.LENGTH_PULSE;
import static eu.rekawek.coffeegb.core.experimental.apu.ApuClockSignalIsland.Profile.CGB_DOUBLE;
import static eu.rekawek.coffeegb.core.experimental.apu.ApuClockSignalIsland.Profile.CGB_NORMAL;
import static eu.rekawek.coffeegb.core.experimental.apu.ApuClockSignalIsland.Profile.DMG;
import static eu.rekawek.coffeegb.core.experimental.apu.ApuClockSignalIsland.SWEEP_PULSE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ApuClockSignalIslandTest {

    @Test
    public void naturalRipplePulsesMatchProductionForAllClockProfiles() {
        for (ApuClockSignalIsland.Profile profile : ApuClockSignalIsland.Profile.values()) {
            DifferentialRig rig = new DifferentialRig(profile, 0);
            int fired = 0;
            for (int masterTick = 0; masterTick < 0x10000; masterTick++) {
                fired += rig.tick(false, "natural tick " + masterTick) ? 1 : 0;
            }
            assertEquals(profile.toString(), 8, fired);
            assertEquals(profile.toString(), 0, rig.topology.nextStep());
        }
    }

    @Test
    public void rippleLatchLevelsDecodeTheClassicEightStepsWithoutAStepTable() {
        DifferentialRig rig = new DifferentialRig(DMG, 0);
        boolean[] expectedBufy = {true, false, true, false, true, false, true, false};
        boolean[] expectedByfe = {false, true, true, false, false, true, true, false};
        int event = 0;
        while (event < 8) {
            if (rig.tick(false, "event search " + event)) {
                assertTrue("HORU must expose BARA's falling phase", rig.topology.horu512Hz());
                assertEquals(expectedBufy[event], rig.topology.bufy256Hz());
                assertEquals(expectedByfe[event], rig.topology.byfe128Hz());
                assertEquals(rig.topology.byfe128Hz(), rig.topology.cate128Hz());
                event++;
            }
        }
    }

    @Test
    public void legalPhaseDivResetsEmitTheSamePulsesAndLengthTimingAsProduction() {
        for (ApuClockSignalIsland.Profile profile : ApuClockSignalIsland.Profile.values()) {
            DifferentialRig rig = new DifferentialRig(profile, 0);
            LengthCounter productionLength = new LengthCounter(64, rig.production);
            productionLength.setLength(9);
            productionLength.setNr4(0x40);
            TopologyLengthCounter topologyLength = new TopologyLengthCounter(9, true);

            int resetCount = 0;
            int masterTicks = 0;
            while (resetCount < 8 || masterTicks < 0x9000) {
                // COKE rises on even-numbered T-cycles in this reset alignment. CPU writes occur
                // on a fixed M-cycle phase, so FF04 can drive zero into the same BARA sample.
                boolean reset = resetCount < 8
                        && (masterTicks & 1) == 1
                        && selectedTap(profile, rig.productionDiv);
                rig.productionDiv = reset
                        ? 0
                        : (rig.productionDiv + profile.dividerIncrement()) & 0xffff;
                int productionStep = rig.production.tick(
                        rig.productionDiv, true, profile.doubleSpeed());

                rig.topology.drive(reset);
                rig.topology.resolve();
                assertEquals(profile + " fired step at T=" + masterTicks,
                        productionStep, rig.topology.firedStep());
                assertEquals(profile + " pulse vector at T=" + masterTicks,
                        pulsesForStep(productionStep), rig.topology.pulses());

                boolean productionZero = false;
                if (productionStep >= 0 && (productionStep & 1) == 0) {
                    productionZero = productionLength.clockTick();
                }
                boolean topologyZero = topologyLength.clock(rig.topology.pulses());
                assertEquals(profile + " zero edge at T=" + masterTicks,
                        productionZero, topologyZero);
                assertEquals(profile + " length at T=" + masterTicks,
                        productionLength.getValue(), topologyLength.value);

                rig.topology.commit();
                assertEquals(profile + " DIV at T=" + masterTicks,
                        rig.productionDiv, rig.topology.div());
                if (reset) {
                    resetCount++;
                }
                masterTicks++;
            }
            assertEquals(profile.toString(), 8, resetCount);
        }
    }

    @Test
    public void poweringOffHoldsTheRippleResetWhileDividerKeepsRunning() {
        ApuClockSignalIsland topology = new ApuClockSignalIsland(DMG, 0, false);
        for (int tick = 0; tick < 0x2400; tick++) {
            topology.drive(false);
            topology.resolve();
            assertEquals(-1, topology.firedStep());
            assertEquals(0, topology.pulses());
            topology.commit();
        }
        assertEquals(0x2400, topology.div());
        assertEquals(0, topology.nextStep());
        assertTrue(topology.horu512Hz());
        assertFalse(topology.bufy256Hz());
        assertFalse(topology.byfe128Hz());
    }

    @Test
    public void cgbTapSelectionKeepsFramePulsesAt512HzInMasterTime() {
        DifferentialRig normal = new DifferentialRig(CGB_NORMAL, 0);
        DifferentialRig doubled = new DifferentialRig(CGB_DOUBLE, 0);
        int normalPulses = 0;
        int doubledPulses = 0;
        for (int masterTick = 0; masterTick < 0x10000; masterTick++) {
            normalPulses += normal.tick(false, "normal " + masterTick) ? 1 : 0;
            doubledPulses += doubled.tick(false, "double " + masterTick) ? 1 : 0;
        }
        assertEquals(8, normalPulses);
        assertEquals(normalPulses, doubledPulses);
    }

    @Test
    public void productionPowerOnAdaptersAreExplicitFalsifiersOfTheDmgGateCone() {
        for (ApuClockSignalIsland.Profile profile : ApuClockSignalIsland.Profile.values()) {
            int selectedBit = 1 << profile.tapBit();

            // BARA is reset low. Releasing reset while the source is high naturally primes CARU
            // and emits step 0 on that source's next fall. Production instead skips that fall.
            FirstEdge highTap = firstEdgeAfterPowerOn(profile, selectedBit + 0x64);
            assertEquals(profile.toString(), 0, highTap.topologyStep);
            assertEquals(profile.toString(), -1, highTap.productionStep);

            // Four clocks before the rising edge, the same gate reset still predicts step 0.
            // FrameSequencer.reset() injects step 1 as a calibrated NR52 write-phase adapter.
            FirstEdge lateWrite = firstEdgeAfterPowerOn(profile,
                    selectedBit - 2 * profile.dividerIncrement());
            assertEquals(profile.toString(), 0, lateWrite.topologyStep);
            assertEquals(profile.toString(), 1, lateWrite.productionStep);
            assertNotEquals(lateWrite.productionStep, lateWrite.topologyStep);
        }
    }

    @Test
    public void everyNonEmergentProfileRuleIsNamedInsteadOfHiddenInTheIsland() {
        assertEquals(
                EXTERNAL_NR52_LATE_WRITE_ADAPTER | EXTERNAL_POWER_ON_HIGH_TAP_SUPPRESSION,
                DMG.externalRules());
        assertEquals(
                EXTERNAL_CGB_BOOT_DIV_OFFSET
                        | EXTERNAL_CGB_SPEED_SWITCH_COMMIT_PHASE
                        | EXTERNAL_NR52_LATE_WRITE_ADAPTER
                        | EXTERNAL_POWER_ON_HIGH_TAP_SUPPRESSION,
                CGB_NORMAL.externalRules());
        assertEquals(
                CGB_NORMAL.externalRules() | EXTERNAL_CGB_DOUBLE_SPEED_TAP,
                CGB_DOUBLE.externalRules());
    }

    @Test
    public void phaseMethodsRejectSequentialMutationShortcuts() {
        ApuClockSignalIsland topology = new ApuClockSignalIsland(DMG, 0, true);
        assertThrows(IllegalStateException.class, topology::resolve);
        assertThrows(IllegalStateException.class, topology::commit);
        topology.drive(false);
        assertFalse(topology.divResetDrive());
        assertThrows(IllegalStateException.class, () -> topology.drive(false));
        topology.resolve();
        assertThrows(IllegalStateException.class, topology::resolve);
        topology.commit();
    }

    private static FirstEdge firstEdgeAfterPowerOn(
            ApuClockSignalIsland.Profile profile, int initialDiv) {
        FrameSequencer production = new FrameSequencer();
        production.reset(initialDiv, profile.doubleSpeed());
        ApuClockSignalIsland topology = new ApuClockSignalIsland(profile, initialDiv, false);
        topology.setPowered(true);
        int div = initialDiv;
        int productionStep;
        int topologyStep;
        do {
            div = (div + profile.dividerIncrement()) & 0xffff;
            productionStep = production.tick(div, true, profile.doubleSpeed());
            topology.drive(false);
            topology.resolve();
            topologyStep = topology.firedStep();
            topology.commit();
        } while (productionStep < 0 && topologyStep < 0);
        return new FirstEdge(productionStep, topologyStep);
    }

    private static boolean selectedTap(ApuClockSignalIsland.Profile profile, int div) {
        return (div & (1 << profile.tapBit())) != 0;
    }

    private static int pulsesForStep(int step) {
        if (step < 0) {
            return 0;
        }
        int pulses = 0;
        if ((step & 1) == 0) {
            pulses |= LENGTH_PULSE;
        }
        if (step == 2 || step == 6) {
            pulses |= SWEEP_PULSE;
        }
        if (step == 7) {
            pulses |= ENVELOPE_PULSE;
        }
        return pulses;
    }

    private static final class DifferentialRig {

        private final ApuClockSignalIsland.Profile profile;

        private final FrameSequencer production = new FrameSequencer();

        private final ApuClockSignalIsland topology;

        private int productionDiv;

        private DifferentialRig(ApuClockSignalIsland.Profile profile, int initialDiv) {
            this.profile = profile;
            production.reset();
            productionDiv = initialDiv & 0xffff;
            topology = new ApuClockSignalIsland(profile, initialDiv, true);
        }

        private boolean tick(boolean divReset, String context) {
            productionDiv = divReset
                    ? 0
                    : (productionDiv + profile.dividerIncrement()) & 0xffff;
            int productionStep = production.tick(productionDiv, true, profile.doubleSpeed());

            topology.drive(divReset);
            topology.resolve();
            assertEquals(context + " fired step", productionStep, topology.firedStep());
            assertEquals(context + " pulse vector",
                    pulsesForStep(productionStep), topology.pulses());
            boolean fired = topology.firedStep() >= 0;
            topology.commit();
            assertEquals(context + " divider", productionDiv, topology.div());
            return fired;
        }
    }

    private static final class TopologyLengthCounter {

        private int value;

        private final boolean enabled;

        private TopologyLengthCounter(int value, boolean enabled) {
            this.value = value;
            this.enabled = enabled;
        }

        private boolean clock(int pulses) {
            if ((pulses & LENGTH_PULSE) != 0 && enabled && value > 0) {
                value--;
                return value == 0;
            }
            return false;
        }
    }

    private record FirstEdge(int productionStep, int topologyStep) {
    }
}
