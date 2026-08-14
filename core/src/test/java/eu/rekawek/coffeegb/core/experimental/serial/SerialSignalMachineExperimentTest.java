package eu.rekawek.coffeegb.core.experimental.serial;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.debug.DebugHardwareInspection;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.core.serial.SerialPort;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.Serial;
import static eu.rekawek.coffeegb.core.experimental.serial.SerialSignalMachine.BusWrite.NONE;
import static eu.rekawek.coffeegb.core.experimental.serial.SerialSignalMachine.BusWrite.to;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Falsification tests for the signal-driven serial hypothesis. The legacy implementation is used
 * as an executable oracle at master-dot boundaries. Where its interrupt-acknowledge lookahead
 * deliberately moves a future serial edge earlier, comparison resumes at the physical edge after
 * both representations must have converged.
 */
public class SerialSignalMachineExperimentTest {

    private static final Config DMG = new Config("DMG", false, false, false, 0x81, 3);

    private static final Config CGB_FAST = new Config("CGB fast", true, false, false, 0x83, 8);

    private static final Config CGB_FAST_DOUBLE =
            new Config("CGB fast double", true, false, true, 0x83, 8);

    private static final Config CGB_NORMAL =
            new Config("CGB normal serial", true, false, false, 0x81, 8);

    private static final Config CGB_DMG_COMPAT =
            new Config("CGB DMG compatibility", true, true, false, 0x83, 8);

    private static final List<Config> ALL_CONFIGS =
            List.of(DMG, CGB_FAST, CGB_FAST_DOUBLE, CGB_NORMAL, CGB_DMG_COMPAT);

    @Test
    public void naturalTransfersAndThreeInterruptObservationPointsMatchLegacy() {
        for (Config config : ALL_CONFIGS) {
            Pair pair = new Pair(config);
            int maxMasterDots = 8 * 2 * pair.signal.internalClockHalfPeriod()
                    / pair.signal.cpuClocksPerMasterDot() + 8;
            for (int tick = 0; tick < maxMasterDots; tick++) {
                pair.tickWithoutAcknowledge();
                pair.assertEquivalent(config.name() + " tick " + tick);
            }

            assertEquals(config.name(), 8, pair.legacy.endpoint.sentBits);
            assertTrue(config.name(), pair.signal.observe().readableIf());
            assertTrue(config.name(), pair.signal.observe().runningCpuRequest());
            assertTrue(config.name(), pair.signal.observe().haltWakeRequest());
        }
    }

    @Test
    public void dividerResetTransitionMatchesLegacyAtEveryReachableClockPhase() {
        for (Config config : ALL_CONFIGS) {
            Pair pair = new Pair(config);
            pair.runUntilSentBits(7);
            PairSnapshot sevenBits = pair.snapshot();
            int period = 2 * pair.signal.internalClockHalfPeriod();
            int reachablePhases = period / pair.signal.cpuClocksPerMasterDot();

            for (int offset = 0; offset < reachablePhases; offset++) {
                pair.restore(sevenBits);
                for (int tick = 0; tick < offset; tick++) {
                    pair.tickWithoutAcknowledge();
                }
                assertEquals(config.name() + " pre-reset bit count", 7,
                        pair.legacy.endpoint.sentBits);

                pair.writeDiv();
                pair.assertEquivalent(config.name() + " reset offset " + offset);

                // A reset can produce the final falling edge immediately, after half a period,
                // or after a full period. Follow the longest case plus the wake synchronizer.
                int followTicks = period / pair.signal.cpuClocksPerMasterDot() + 6;
                for (int tick = 0; tick < followTicks; tick++) {
                    pair.tickWithoutAcknowledge();
                    pair.assertEquivalent(config.name() + " reset offset " + offset
                            + " follow " + tick);
                }
            }
        }
    }

    @Test
    public void localResetTapTransitionIsExactlyEquivalentToTheRemovedDeadlineFormula() {
        for (int halfPeriod : new int[]{8, 256}) {
            for (int dividerLow = 0; dividerLow <= 0xff; dividerLow++) {
                for (int oldOutput = 0; oldOutput <= 1; oldOutput++) {
                    boolean oldClock = oldOutput != 0;

                    int clocksToNextToggle = halfPeriod
                            - (dividerLow & (halfPeriod - 1));
                    int clocksToNextBit = clocksToNextToggle
                            + (oldClock ? 0 : halfPeriod);
                    int phaseAdjustment = Math.floorMod(-clocksToNextBit, halfPeriod);
                    int adjustedClocksToNextBit = clocksToNextBit + phaseAdjustment
                            - 2 * (phaseAdjustment & (halfPeriod >> 1));
                    boolean deadlineFormulaClock = adjustedClocksToNextBit == halfPeriod;
                    boolean deadlineFormulaShift = adjustedClocksToNextBit == 0;

                    boolean tapHigh = (dividerLow & (halfPeriod >> 1)) != 0;
                    boolean localClock = tapHigh ? !oldClock : oldClock;
                    boolean localShift = oldClock && !localClock;

                    String label = "halfPeriod=" + halfPeriod + " dividerLow=" + dividerLow
                            + " oldClock=" + oldClock;
                    assertEquals(label + " clock", deadlineFormulaClock, localClock);
                    assertEquals(label + " falling edge", deadlineFormulaShift, localShift);
                }
            }
        }
    }

    @Test
    public void physicalAcknowledgeStrobeReplacesCompletionLookaheadForEveryLead() {
        int deliberatelyEarlyLegacyCompletions = 0;
        int retiredClockLatchDivergences = 0;

        for (Config config : ALL_CONFIGS) {
            Pair pair = new Pair(config);
            pair.runUntilSentBits(7);
            PairSnapshot sevenBits = pair.snapshot();
            int period = 2 * pair.signal.internalClockHalfPeriod();
            int speed = pair.signal.cpuClocksPerMasterDot();
            int reachableOffsets = period / speed;

            for (int offset = 0; offset < reachableOffsets; offset++) {
                pair.restore(sevenBits);
                for (int tick = 0; tick < offset; tick++) {
                    pair.tickWithoutAcknowledge();
                }
                int clocksToPhysicalCompletion = period - offset * speed;
                assertEquals(config.name() + " pre-ack bit count", 7,
                        pair.legacy.endpoint.sentBits);

                // This is the early API call made by the legacy IRQ_PUSH_2 state. It asks
                // SerialPort to forecast whether completion lies inside a later hardware gate.
                pair.legacy.interrupts.clearInterrupt(Serial);

                // The signal machine receives only the actual acknowledge wire at the physical
                // end of that gate. It has no acknowledgement window and cannot inspect its own
                // future serial state.
                int cpuEdges = 0;
                int totalCpuEdges = Math.max(clocksToPhysicalCompletion,
                        config.legacyAcknowledgeLeadCpuClocks()) + 6;
                int totalMasterDots = (totalCpuEdges + speed - 1) / speed;
                for (int tick = 0; tick < totalMasterDots; tick++) {
                    pair.legacy.serial.tick();
                    for (int halfDot = 0; halfDot < 2; halfDot++) {
                        boolean cpuClock = config.doubleSpeed() || halfDot == 1;
                        if (cpuClock) {
                            cpuEdges++;
                        }
                        boolean acknowledge = cpuClock
                                && cpuEdges == config.legacyAcknowledgeLeadCpuClocks();
                        pair.signal.step(new SerialSignalMachine.Inputs(
                                cpuClock, NONE, acknowledge, 1));
                    }
                    if (tick == 0
                            && pair.legacy.endpoint.sentBits > pair.signal.observe().sentBits()) {
                        deliberatelyEarlyLegacyCompletions++;
                    }
                }

                String label = config.name() + " ack distance " + clocksToPhysicalCompletion;
                if (pair.legacy.serial.captureDebugSerialInspection().clockSignal()
                        != pair.signal.observe().serialClock()) {
                    retiredClockLatchDivergences++;
                }
                pair.assertEquivalentIgnoringRetiredClock(label);
                boolean requestSurvives = clocksToPhysicalCompletion
                        > config.legacyAcknowledgeLeadCpuClocks();
                assertEquals(label + " result",
                        requestSurvives,
                        pair.signal.observe().readableIf());

                // The legacy forecast can skip the physical falling clock transition because it
                // stops the transfer early. That hidden latch is reset by the next SC start, so
                // both models converge again at the next externally meaningful boundary.
                pair.restartTransfer();
                pair.assertEquivalent(label + " after restart");
            }
        }

        // Confirms that the comparison really exercised the representational difference rather
        // than accidentally reproducing the legacy lookahead inside the signal model.
        assertTrue(deliberatelyEarlyLegacyCompletions > 0);
        assertTrue(retiredClockLatchDivergences > 0);
    }

    @Test
    public void deletingForecastWithoutMovingTheCpuAcknowledgeEdgeIsFalsified() {
        Pair pair = new Pair(CGB_FAST);
        pair.runUntilSentBits(7);

        // At phase eight the eighth bit is eight CPU clocks away. The legacy CPU-facing API is
        // called here, so SerialPort forecasts and consumes that future completion.
        for (int tick = 0; tick < 8; tick++) {
            pair.tickWithoutAcknowledge();
        }
        pair.legacy.interrupts.clearInterrupt(Serial);

        // A signal latch given the acknowledge at that same (too-early) time has nothing to
        // clear. When the real falling edge arrives later, IF is asserted. Therefore the serial
        // forecast cannot merely be deleted: the CPU model must drive its acknowledge at the
        // physical gate represented by the previous exhaustive test.
        pair.signal.step(new SerialSignalMachine.Inputs(false, NONE, true, 1));
        for (int tick = 0; tick < 12; tick++) {
            pair.tickWithoutAcknowledge();
        }

        assertFalse(pair.legacy.interrupts.isInterruptFlagSet(Serial));
        assertTrue(pair.signal.observe().readableIf());
    }

    @Test
    public void simultaneousEighthBitAndAcknowledgeAreResolvedByOneLatchTruthTable() {
        SerialSignalMachine machine = new SerialSignalMachine(CGB_FAST.profile());
        write(machine, SerialSignalMachine.IF, 0);
        write(machine, SerialSignalMachine.IE, SerialSignalMachine.SERIAL_MASK);
        write(machine, SerialSignalMachine.SB, 0);
        write(machine, SerialSignalMachine.SC, 0x83);

        while (machine.observe().sentBits() < 7) {
            cpuClock(machine, false);
        }
        int period = 2 * machine.internalClockHalfPeriod();
        for (int clock = 1; clock < period; clock++) {
            cpuClock(machine, false);
        }

        SerialSignalMachine.Observation collision = cpuClock(machine, true);

        assertTrue(collision.serialRequestWire());
        assertTrue(collision.interruptAcknowledgeWire());
        assertFalse(collision.readableIf());
        assertFalse(collision.runningCpuRequest());
        assertFalse(collision.haltWakeRequest());
        assertEquals(8, collision.sentBits());
    }

    @Test
    public void requestIsReadableAndRunnableFourClocksBeforeHaltCanObserveIt() {
        SerialSignalMachine machine = new SerialSignalMachine(CGB_FAST.profile());
        write(machine, SerialSignalMachine.IF, 0);
        write(machine, SerialSignalMachine.IE, SerialSignalMachine.SERIAL_MASK);
        write(machine, SerialSignalMachine.SC, 0x83);

        SerialSignalMachine.Observation observation;
        do {
            observation = cpuClock(machine, false);
        } while (!observation.serialRequestWire());

        assertTrue(observation.readableIf());
        assertTrue(observation.runningCpuRequest());
        assertFalse(observation.haltWakeRequest());

        for (int clock = 1; clock < 4; clock++) {
            observation = cpuClock(machine, false);
            assertFalse("HALT woke after only " + clock + " clocks",
                    observation.haltWakeRequest());
        }
        observation = cpuClock(machine, false);
        assertTrue(observation.haltWakeRequest());
    }

    @Test
    public void rawDividerTapModelIsFalsifiedButOneLocalClockLatchExplainsBothResets() {
        Pair pair = new Pair(CGB_FAST);
        for (int tick = 0; tick < 11; tick++) {
            pair.tickWithoutAcknowledge();
        }
        assertTrue("a raw bit-3 clock would be high", (pair.signal.observe().dividerLow() & 8) != 0);
        pair.writeDiv();
        assertEquals("raw tap incorrectly predicts an immediate falling edge", 0,
                pair.signal.observe().sentBits());
        pair.assertEquivalent("fast reset at phase 11");

        pair = new Pair(CGB_FAST);
        for (int tick = 0; tick < 13; tick++) {
            pair.tickWithoutAcknowledge();
        }
        assertTrue("the same raw bit-3 clock is still high",
                (pair.signal.observe().dividerLow() & 8) != 0);
        pair.writeDiv();
        assertEquals("the preceding divider stage toggles the output latch", 1,
                pair.signal.observe().sentBits());
        pair.assertEquivalent("fast reset at phase 13");
    }

    private static SerialSignalMachine.Observation cpuClock(
            SerialSignalMachine machine, boolean acknowledge) {
        return machine.step(new SerialSignalMachine.Inputs(true, NONE, acknowledge, 1));
    }

    private static void write(SerialSignalMachine machine, int address, int value) {
        machine.step(new SerialSignalMachine.Inputs(false, to(address, value), false, 1));
    }

    private record Config(
            String name,
            boolean cgb,
            boolean dmgCompatibility,
            boolean doubleSpeed,
            int sc,
            int legacyAcknowledgeLeadCpuClocks) {

        SerialSignalMachine.Profile profile() {
            return new SerialSignalMachine.Profile(cgb, dmgCompatibility, doubleSpeed);
        }
    }

    private static final class Pair {

        private final Config config;

        private final LegacyFixture legacy;

        private final SerialSignalMachine signal;

        private Pair(Config config) {
            this.config = config;
            legacy = new LegacyFixture(config);
            signal = new SerialSignalMachine(config.profile());

            legacy.interrupts.setByte(SerialSignalMachine.IF, 0);
            write(signal, SerialSignalMachine.IF, 0);
            legacy.interrupts.setByte(SerialSignalMachine.IE, SerialSignalMachine.SERIAL_MASK);
            write(signal, SerialSignalMachine.IE, SerialSignalMachine.SERIAL_MASK);
            legacy.serial.setByte(SerialSignalMachine.SB, 0);
            write(signal, SerialSignalMachine.SB, 0);
            legacy.serial.setByte(SerialSignalMachine.SC, config.sc());
            write(signal, SerialSignalMachine.SC, config.sc());
            assertEquivalent(config.name() + " initialized");
        }

        private void tickWithoutAcknowledge() {
            legacy.serial.tick();
            for (int halfDot = 0; halfDot < 2; halfDot++) {
                boolean cpuClock = config.doubleSpeed() || halfDot == 1;
                signal.step(SerialSignalMachine.Inputs.idle(cpuClock));
            }
        }

        private void writeDiv() {
            legacy.serial.onDivReset();
            signal.step(new SerialSignalMachine.Inputs(
                    false, to(SerialSignalMachine.DIV, 0), false, 1));
        }

        private void restartTransfer() {
            legacy.serial.setByte(SerialSignalMachine.SC, config.sc());
            write(signal, SerialSignalMachine.SC, config.sc());
        }

        private void runUntilSentBits(int bits) {
            int remaining = 20_000;
            while (legacy.endpoint.sentBits < bits && remaining-- > 0) {
                tickWithoutAcknowledge();
                assertEquivalent(config.name() + " seek bit " + bits);
            }
            assertTrue(config.name() + " did not reach bit " + bits, remaining > 0);
            assertEquals(bits, signal.observe().sentBits());
        }

        private PairSnapshot snapshot() {
            return new PairSnapshot(legacy.snapshot(), signal.snapshot());
        }

        private void restore(PairSnapshot snapshot) {
            legacy.restore(snapshot.legacy());
            signal.restore(snapshot.signal());
        }

        private void assertEquivalent(String label) {
            assertEquivalent(label, true);
        }

        private void assertEquivalentIgnoringRetiredClock(String label) {
            assertEquivalent(label, false);
        }

        private void assertEquivalent(String label, boolean compareClock) {
            DebugHardwareInspection.Serial actual = legacy.serial.captureDebugSerialInspection();
            SerialSignalMachine.Observation candidate = signal.observe();
            assertEquals(label + " SB", actual.sb(), candidate.sb());
            assertEquals(label + " SC", legacy.serial.getByte(SerialSignalMachine.SC), candidate.sc());
            assertEquals(label + " received bits", actual.receivedBits(), candidate.receivedBits());
            assertEquals(label + " divider low", actual.clockPhase(), candidate.dividerLow());
            if (compareClock) {
                assertEquals(label + " serial clock", actual.clockSignal(), candidate.serialClock());
            }
            assertEquals(label + " sent bits", legacy.endpoint.sentBits, candidate.sentBits());
            assertEquals(label + " readable IF",
                    legacy.interrupts.isInterruptFlagSet(Serial), candidate.readableIf());
            assertEquals(label + " running request",
                    legacy.interrupts.isInterruptRequested(), candidate.runningCpuRequest());
            assertEquals(label + " HALT wake request",
                    legacy.interrupts.isInterruptRequestedForHalt(), candidate.haltWakeRequest());
        }
    }

    private record PairSnapshot(
            LegacyFixture.Snapshot legacy, SerialSignalMachine.Snapshot signal) {
    }

    private static final class LegacyFixture {

        private final InterruptManager interrupts;

        private final SerialPort serial;

        private final CountingEndpoint endpoint;

        private LegacyFixture(Config config) {
            SpeedMode speedMode = new SpeedMode(config.cgb());
            speedMode.setDmgCompat(config.dmgCompatibility());
            if (config.doubleSpeed()) {
                enableDoubleSpeed(speedMode);
            }
            interrupts = new InterruptManager(config.cgb());
            serial = new SerialPort(interrupts, config.cgb(), speedMode);
            endpoint = new CountingEndpoint();
            serial.init(endpoint);
        }

        private Snapshot snapshot() {
            return new Snapshot(serial.captureState(), interrupts.captureState(), endpoint.sentBits);
        }

        private void restore(Snapshot snapshot) {
            serial.restoreState(snapshot.serial());
            interrupts.restoreState(snapshot.interrupts());
            endpoint.sentBits = snapshot.sentBits();
        }

        private record Snapshot(
                ComponentState<SerialPort> serial,
                ComponentState<InterruptManager> interrupts,
                int sentBits) {
        }
    }

    private static final class CountingEndpoint implements SerialEndpoint {

        private int sentBits;

        @Override
        public void setSb(int sb) {
        }

        @Override
        public int recvBit() {
            return -1;
        }

        @Override
        public void startSending() {
        }

        @Override
        public int sendBit() {
            sentBits++;
            return 1;
        }

        @Override
        public ComponentState<SerialEndpoint> captureState() {
            return null;
        }

        @Override
        public void restoreState(ComponentState<SerialEndpoint> state) {
        }
    }

    private static void enableDoubleSpeed(SpeedMode speedMode) {
        speedMode.setByte(0xff4d, 1);
        try {
            Method onStop = SpeedMode.class.getDeclaredMethod("onStop");
            onStop.setAccessible(true);
            assertEquals(Boolean.TRUE, onStop.invoke(speedMode));
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError(e);
        } catch (InvocationTargetException e) {
            throw new AssertionError(e.getCause());
        }
    }
}
