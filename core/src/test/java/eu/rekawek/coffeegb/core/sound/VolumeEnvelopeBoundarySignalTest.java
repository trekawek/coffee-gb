package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.lang.reflect.Constructor;

import static org.junit.Assert.assertEquals;

/** Exhaustive differential for the four-bit envelope counter's carry/borrow stop signal. */
public class VolumeEnvelopeBoundarySignalTest {

    private static final Constructor<?> STATE_CONSTRUCTOR = stateConstructor();

    @Test
    public void clockInputsMatchTheLegacyBoundaryDecoderForEveryReleasedStateTuple() throws Exception {
        for (int initialVolume = 0; initialVolume < 16; initialVolume++) {
            for (int direction = -1; direction <= 1; direction++) {
                for (int sweep = 0; sweep < 8; sweep++) {
                    for (int volume = 0; volume < 16; volume++) {
                        for (int timer = 0; timer <= 8; timer++) {
                            for (boolean finished : booleans()) {
                                for (boolean pending : booleans()) {
                                    EnvelopeState state = new EnvelopeState(initialVolume, direction, sweep,
                                            volume, timer, finished, pending);
                                    assertTransition(state, LegacyClock.CLOCK_TICK, 0);
                                    assertTransition(state, LegacyClock.APU_CLOCK_TICK, 0);
                                    assertTransition(state, LegacyClock.APU_CLOCK_TICK, 1);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void publicRegisterProjectionRetainsOutOfRangeLegacyBehavior() throws Exception {
        VolumeEnvelope historicalWitness = triggered(0x109);
        historicalWitness.clockTick();
        assertEquals(17, historicalWitness.getVolume());

        for (int register = -0x10000; register < 0x10000; register++) {
            assertRegisterProjection(register);
        }
        for (int register : new int[]{
                Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -0x10001,
                0x10000, Integer.MAX_VALUE - 1, Integer.MAX_VALUE}) {
            assertRegisterProjection(register);
        }
    }

    @Test
    public void stopLatchIsIndependentOfTheVisibleBoundary() {
        VolumeEnvelope unlocked = triggered(0x00);
        VolumeEnvelope locked = triggered(0x00);
        locked.clockTick();

        unlocked.setNr2(0x01, true);
        locked.setNr2(0x01, true);

        assertEquals(15, unlocked.getVolume());
        assertEquals(0, locked.getVolume());
    }

    @Test
    public void pendingClockIsIndependentOfThePeriodTimer() {
        VolumeEnvelope pending = triggered(0x81);
        pending.setNr2(0x80, true);
        pending.setNr2(0x71, true);
        VolumeEnvelope idle = triggered(0x71);

        assertEquals(7, pending.getVolume());
        assertEquals(7, idle.getVolume());
        pending.apuClockTick(1);
        idle.apuClockTick(1);

        assertEquals(6, pending.getVolume());
        assertEquals(7, idle.getVolume());
    }

    @Test
    public void periodTimerRetainsIndependentCountdownPhase() {
        VolumeEnvelope timerTwo = triggered(0x83);
        timerTwo.clockTick();
        VolumeEnvelope timerOne = triggered(0x83);
        timerOne.clockTick();
        timerOne.clockTick();

        assertEquals(8, timerTwo.getVolume());
        assertEquals(8, timerOne.getVolume());
        timerTwo.clockTick();
        timerOne.clockTick();

        assertEquals(8, timerTwo.getVolume());
        assertEquals(7, timerOne.getVolume());
    }

    private static void assertTransition(EnvelopeState before, LegacyClock clock, int step) throws Exception {
        VolumeEnvelope production = new VolumeEnvelope();
        production.restoreState(componentState(before));

        EnvelopeState expected;
        if (clock == LegacyClock.CLOCK_TICK) {
            production.clockTick();
            expected = legacyClockTick(before);
        } else {
            production.apuClockTick(step);
            expected = legacyApuClockTick(before, step);
        }

        ComponentState<VolumeEnvelope> actual = production.captureState();
        ComponentState<VolumeEnvelope> legacy = componentState(expected);
        if (!legacy.equals(actual)) {
            throw new AssertionError(clock + " step=" + step + " before=" + before
                    + " expected=" + legacy + " actual=" + actual);
        }
    }

    private static void assertRegisterProjection(int register) throws Exception {
        VolumeEnvelope production = triggered(register);
        int direction = (register & 0b1000) == 0 ? -1 : 1;
        int sweep = register & 0b111;
        EnvelopeState legacy = new EnvelopeState(register >> 4, direction, sweep,
                register >> 4, sweep == 0 ? 8 : sweep, false, false);
        assertState(legacy, production);
        for (int clock = 1; clock <= 9; clock++) {
            production.clockTick();
            legacy = legacyClockTick(legacy);
            assertState(legacy, production);
        }
    }

    private static void assertState(EnvelopeState expected, VolumeEnvelope production) throws Exception {
        ComponentState<VolumeEnvelope> actual = production.captureState();
        ComponentState<VolumeEnvelope> legacy = componentState(expected);
        if (!legacy.equals(actual)) {
            throw new AssertionError("expected=" + legacy + " actual=" + actual);
        }
    }

    private static EnvelopeState legacyClockTick(EnvelopeState old) {
        if (old.finished) {
            return old;
        }
        if (legacyBoundary(old)) {
            return old.withFinished(true);
        }
        if (old.sweep == 0) {
            return old;
        }
        int timer = old.timer - 1;
        if (timer <= 0) {
            return old.withTimerAndVolume(old.sweep, old.volume + old.direction);
        }
        return old.withTimer(timer);
    }

    private static EnvelopeState legacyApuClockTick(EnvelopeState old, int step) {
        if (!old.pending || (step & 1) == 0) {
            return old;
        }
        EnvelopeState next = old.withPendingAndTimer(false, old.sweep);
        return legacyBoundary(next)
                ? next.withFinished(true)
                : next.withVolume(next.volume + next.direction);
    }

    private static boolean legacyBoundary(EnvelopeState state) {
        return state.volume == 0 && state.direction == -1
                || state.volume == 15 && state.direction == 1;
    }

    @SuppressWarnings("unchecked")
    private static ComponentState<VolumeEnvelope> componentState(EnvelopeState state) throws Exception {
        return (ComponentState<VolumeEnvelope>) STATE_CONSTRUCTOR.newInstance(
                state.initialVolume, state.direction, state.sweep, state.volume, state.timer,
                state.finished, state.pending);
    }

    private static Constructor<?> stateConstructor() {
        ComponentState<VolumeEnvelope> state = new VolumeEnvelope().captureState();
        try {
            Constructor<?> constructor = state.getClass().getDeclaredConstructor(
                    int.class, int.class, int.class, int.class, int.class,
                    boolean.class, boolean.class);
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static VolumeEnvelope triggered(int nr2) {
        VolumeEnvelope envelope = new VolumeEnvelope();
        envelope.start();
        envelope.setNr2(nr2);
        envelope.trigger();
        return envelope;
    }

    private static boolean[] booleans() {
        return new boolean[]{false, true};
    }

    private enum LegacyClock {
        CLOCK_TICK,
        APU_CLOCK_TICK
    }

    private record EnvelopeState(int initialVolume, int direction, int sweep, int volume, int timer,
                                 boolean finished, boolean pending) {

        private EnvelopeState withFinished(boolean value) {
            return new EnvelopeState(initialVolume, direction, sweep, volume, timer, value, pending);
        }

        private EnvelopeState withPendingAndTimer(boolean pendingValue, int timerValue) {
            return new EnvelopeState(initialVolume, direction, sweep, volume, timerValue, finished, pendingValue);
        }

        private EnvelopeState withTimer(int value) {
            return new EnvelopeState(initialVolume, direction, sweep, volume, value, finished, pending);
        }

        private EnvelopeState withTimerAndVolume(int timerValue, int volumeValue) {
            return new EnvelopeState(initialVolume, direction, sweep, volumeValue, timerValue, finished, pending);
        }

        private EnvelopeState withVolume(int value) {
            return new EnvelopeState(initialVolume, direction, sweep, value, timer, finished, pending);
        }
    }
}
