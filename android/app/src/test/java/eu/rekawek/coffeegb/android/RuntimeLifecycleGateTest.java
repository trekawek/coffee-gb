package eu.rekawek.coffeegb.android;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RuntimeLifecycleGateTest {

    @Test
    public void rapidBackgroundSignalsCoalesceUntilTheFakeSessionFlushCompletes() {
        RuntimeLifecycleGate gate = new RuntimeLifecycleGate();
        AtomicInteger pauses = new AtomicInteger();
        AtomicInteger flushes = new AtomicInteger();
        RuntimeLifecycleGate.SessionCommands session = new RuntimeLifecycleGate.SessionCommands() {
            @Override
            public void pause() {
                pauses.incrementAndGet();
            }

            @Override
            public void resumeOutputs() {
            }

            @Override
            public void requestBatteryFlush() {
                flushes.incrementAndGet();
            }
        };

        gate.activated(session);
        for (int index = 0; index < 10; index++) {
            gate.background(session);
        }

        assertTrue(gate.active());
        assertEquals(1, pauses.get());
        assertEquals(1, flushes.get());

        gate.flushCompleted();
        gate.resumedByUser();
        gate.background(session);

        assertEquals(2, pauses.get());
        assertEquals(2, flushes.get());
    }

    @Test
    public void releasedRuntimeNeverCallsTheFakeSessionAgain() {
        RuntimeLifecycleGate gate = new RuntimeLifecycleGate();
        AtomicInteger calls = new AtomicInteger();
        RuntimeLifecycleGate.SessionCommands session = new RuntimeLifecycleGate.SessionCommands() {
            @Override
            public void pause() {
                calls.incrementAndGet();
            }

            @Override
            public void resumeOutputs() {
            }

            @Override
            public void requestBatteryFlush() {
                calls.incrementAndGet();
            }
        };

        gate.activated(session);
        gate.released();
        gate.background(session);

        assertFalse(gate.active());
        assertEquals(0, calls.get());
    }

    @Test
    public void tenRapidFakeSessionLifecycleCyclesLeaveNoCommandsAfterEachRelease() {
        RuntimeLifecycleGate gate = new RuntimeLifecycleGate();
        AtomicInteger pauses = new AtomicInteger();
        AtomicInteger flushes = new AtomicInteger();
        RuntimeLifecycleGate.SessionCommands session = new RuntimeLifecycleGate.SessionCommands() {
            @Override
            public void pause() {
                pauses.incrementAndGet();
            }

            @Override
            public void resumeOutputs() {
            }

            @Override
            public void requestBatteryFlush() {
                flushes.incrementAndGet();
            }
        };

        for (int cycle = 0; cycle < 10; cycle++) {
            gate.activated(session);
            gate.background(session);
            gate.background(session);
            gate.flushCompleted();
            gate.released();
            gate.background(session);
        }

        assertFalse(gate.active());
        assertEquals(10, pauses.get());
        assertEquals(10, flushes.get());
    }

    @Test
    public void foregroundActivationResumesOutputsAfterAFirstRomPickerRoundTrip() {
        RuntimeLifecycleGate gate = new RuntimeLifecycleGate();
        AtomicInteger pauses = new AtomicInteger();
        AtomicInteger resumes = new AtomicInteger();
        AtomicInteger flushes = new AtomicInteger();
        RuntimeLifecycleGate.SessionCommands session = new RuntimeLifecycleGate.SessionCommands() {
            @Override
            public void pause() {
                pauses.incrementAndGet();
            }

            @Override
            public void resumeOutputs() {
                resumes.incrementAndGet();
            }

            @Override
            public void requestBatteryFlush() {
                flushes.incrementAndGet();
            }
        };

        gate.background(session);
        gate.foregrounded();
        gate.activated(session);

        assertEquals(0, pauses.get());
        assertEquals(1, resumes.get());
        assertEquals(0, flushes.get());
    }

    @Test
    public void ordinaryForegroundActivationLeavesAlreadyRunningOutputsAlone() {
        RuntimeLifecycleGate gate = new RuntimeLifecycleGate();
        AtomicInteger calls = new AtomicInteger();
        RuntimeLifecycleGate.SessionCommands session = new RuntimeLifecycleGate.SessionCommands() {
            @Override
            public void pause() {
                calls.incrementAndGet();
            }

            @Override
            public void resumeOutputs() {
                calls.incrementAndGet();
            }

            @Override
            public void requestBatteryFlush() {
                calls.incrementAndGet();
            }
        };

        gate.activated(session);

        assertEquals(0, calls.get());
    }

    @Test
    public void backgroundActivationKeepsNewSessionOutputsPaused() {
        RuntimeLifecycleGate gate = new RuntimeLifecycleGate();
        AtomicInteger pauses = new AtomicInteger();
        AtomicInteger resumes = new AtomicInteger();
        AtomicInteger flushes = new AtomicInteger();
        RuntimeLifecycleGate.SessionCommands session = new RuntimeLifecycleGate.SessionCommands() {
            @Override
            public void pause() {
                pauses.incrementAndGet();
            }

            @Override
            public void resumeOutputs() {
                resumes.incrementAndGet();
            }

            @Override
            public void requestBatteryFlush() {
                flushes.incrementAndGet();
            }
        };

        gate.background(session);
        gate.activated(session);

        assertEquals(1, pauses.get());
        assertEquals(0, resumes.get());
        assertEquals(1, flushes.get());
    }
}
