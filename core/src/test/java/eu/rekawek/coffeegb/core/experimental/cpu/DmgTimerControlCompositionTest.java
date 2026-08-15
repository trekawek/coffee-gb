package eu.rekawek.coffeegb.core.experimental.cpu;

import eu.rekawek.coffeegb.core.signal.HalfDotClockRouter;
import org.junit.Test;

import java.util.Arrays;

import static eu.rekawek.coffeegb.core.experimental.cpu.DmgControlSignalFabric.TIMER_MASK;
import static eu.rekawek.coffeegb.core.signal.HalfDotClockRouter.Phase.BETWEEN_FIXED_DOMAIN_EDGES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Natural timer request integrated through the shared half-dot/IF control boundary. */
public class DmgTimerControlCompositionTest {

    @Test
    public void naturalOverflowDrivesIfWithoutADeadlineOrRawSourceScript() {
        Composition composition = new Composition(
                overflowingTimer(),
                fabric(0, CpuBusCycleMachine.Cycle.internal()));

        CombinedObservation request = stepUntilRequest(composition);

        assertEquals(7, request.fabric().halfDot());
        assertTrue(request.timer().requestPulse());
        assertEquals(TIMER_MASK, request.fabric().wires().rawRequests());
        assertEquals(TIMER_MASK, request.fabric().control().readableIf() & TIMER_MASK);
        assertEquals(0x42, request.timer().state().tima());
        assertTrue(request.timer().state().reloadLevel());
    }

    @Test
    public void sameEdgeNaturalRequestAndCpuAcknowledgeMeetAtOneClearDominantIfLatch() {
        Composition composition = new Composition(
                overflowingTimer(),
                fabric(TIMER_MASK,
                        CpuBusCycleMachine.Cycle.interruptAcknowledgeWrite(0xcffe, 0x34)));

        CombinedObservation collision = stepUntilRequest(composition);

        assertEquals(TIMER_MASK, collision.fabric().wires().oneHotAcknowledge());
        assertEquals(TIMER_MASK, collision.fabric().wires().rawRequests());
        assertEquals("the central latch owns set/ack dominance", 0,
                collision.fabric().control().readableIf() & TIMER_MASK);
        assertTrue("acknowledge does not suppress the timer's reload topology",
                collision.timer().state().reloadLevel());
    }

    @Test
    public void requestIsAnEdgePulseWhileReloadOwnershipLastsOneBogaPeriod() {
        Composition composition = new Composition(
                overflowingTimer(),
                fabric(0, CpuBusCycleMachine.Cycle.internal(),
                        CpuBusCycleMachine.Cycle.internal(),
                        CpuBusCycleMachine.Cycle.internal(),
                        CpuBusCycleMachine.Cycle.internal()));
        CombinedObservation request = stepUntilRequest(composition);
        assertTrue(request.timer().requestPulse());

        for (int halfDot = 0; halfDot < 7; halfDot++) {
            CombinedObservation after = composition.step();
            assertFalse(after.timer().requestPulse());
            assertTrue(after.timer().state().reloadLevel());
        }
        CombinedObservation release = composition.step();
        assertFalse(release.timer().requestPulse());
        assertFalse(release.timer().state().reloadLevel());
    }

    @Test
    public void timerAndControlStateRestoreTogetherAtEveryHalfDotPhase() {
        for (int snapshotAt = 0; snapshotAt < 16; snapshotAt++) {
            Composition reference = new Composition(
                    overflowingTimer(), fabric(0, internalCycles(8)));
            for (int halfDot = 0; halfDot < snapshotAt; halfDot++) {
                reference.step();
            }
            Composition.State snapshot = reference.capture();

            Composition replay = new Composition(
                    DmgTimerRequestIsland.State.stable(0, 0, 0, 0, 0),
                    fabric(0, internalCycles(8)));
            replay.restore(snapshot);
            for (int halfDot = 0; halfDot < 16; halfDot++) {
                assertEquals("snapshot " + snapshotAt + ", half-dot " + halfDot,
                        reference.step(), replay.step());
            }
        }
    }

    private static CombinedObservation stepUntilRequest(Composition composition) {
        for (int halfDot = 0; halfDot < 32; halfDot++) {
            CombinedObservation observation = composition.step();
            if (observation.timer().requestPulse()) {
                return observation;
            }
        }
        throw new AssertionError("natural timer request did not arrive");
    }

    private static DmgTimerRequestIsland.State overflowingTimer() {
        return DmgTimerRequestIsland.State.stable(0x000f, 0x05, 0xff, 0x42, 0);
    }

    private static DmgControlSignalFabric fabric(
            int interruptFlags, CpuBusCycleMachine.Cycle... cycles) {
        return new DmgControlSignalFabric(interruptFlags, TIMER_MASK, false, cycles);
    }

    private static CpuBusCycleMachine.Cycle[] internalCycles(int count) {
        CpuBusCycleMachine.Cycle[] cycles = new CpuBusCycleMachine.Cycle[count];
        Arrays.fill(cycles, CpuBusCycleMachine.Cycle.internal());
        return cycles;
    }

    private record CombinedObservation(
            DmgTimerRequestIsland.Observation timer,
            DmgControlSignalFabric.Observation fabric) {
    }

    private static final class Composition {

        private record State(
                HalfDotClockRouter.Phase phase,
                DmgTimerRequestIsland.State timer,
                DmgControlSignalFabric.Snapshot fabric) {
        }

        private final HalfDotClockRouter clock = new HalfDotClockRouter(
                BETWEEN_FIXED_DOMAIN_EDGES);

        private final DmgTimerRequestIsland timer;

        private final DmgControlSignalFabric fabric;

        private Composition(
                DmgTimerRequestIsland.State timerState,
                DmgControlSignalFabric fabric) {
            timer = new DmgTimerRequestIsland(timerState);
            this.fabric = fabric;
        }

        private CombinedObservation step() {
            clock.resolve(false);
            timer.resolve(clock.cpuDomainClockEnable());
            DmgTimerRequestIsland.Observation timerWires = timer.capturedObservation();

            DmgControlSignalFabric.Observation fabricWires = fabric.stepHalfDot(
                    new DmgControlSignalFabric.SourcePins(timerWires.requestPulse(), false));
            assertEquals("the timer and control islands must share a CPU edge",
                    clock.cpuDomainClockEnable(), fabricWires.cpuClockEnable());

            // Both commits publish from the same old-state boundary. Their order is unobservable.
            timer.commit();
            clock.commit();
            return new CombinedObservation(timerWires, fabricWires);
        }

        private State capture() {
            return new State(clock.phase(), timer.capture(), fabric.snapshot());
        }

        private void restore(State state) {
            clock.restore(state.phase());
            timer.restore(state.timer());
            fabric.restore(state.fabric());
        }
    }
}
