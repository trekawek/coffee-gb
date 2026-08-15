package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.lang.reflect.Constructor;

/** Exhaustive differential for the sweep adder's shared add/subtract suffix. */
public class FrequencySweepAdderAlgebraTest {

    private static final Constructor<?> STATE_CONSTRUCTOR = stateConstructor();

    @Test
    public void delayedCalculationMatchesTheLegacyBranchForEveryAcceptedAdderState() throws Exception {
        for (int shift = 0; shift <= 7; shift++) {
            for (int shadow = 0; shadow <= 0x7ff; shadow++) {
                for (boolean negate : booleans()) {
                    for (boolean overflow : booleans()) {
                        for (boolean negging : booleans()) {
                            for (boolean unshifted : booleans()) {
                                SweepState before = new SweepState(7, negate, shift, 8, shadow,
                                        Integer.MIN_VALUE, Integer.MAX_VALUE, overflow, true, negging,
                                        1, unshifted, 0, true);
                                FrequencySweep production = new FrequencySweep();
                                production.restoreState(componentState(before));
                                production.tick();

                                SweepState expected = legacyTick(before);
                                ComponentState<FrequencySweep> actual = production.captureState();
                                ComponentState<FrequencySweep> legacy = componentState(expected);
                                if (!legacy.equals(actual)) {
                                    throw new AssertionError(label(before) + " expected=" + legacy
                                            + " actual=" + actual);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void widePublicIntInputsRetainLegacyModuloArithmetic() {
        for (int nr13 = -0x10000; nr13 <= 0x10000; nr13++) {
            assertPublicInput(nr13, ~nr13);
        }
        for (int value : new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE + 1,
                Integer.MAX_VALUE - 1, Integer.MAX_VALUE}) {
            assertPublicInput(value, Integer.rotateLeft(value, 11));
        }
    }

    private static void assertPublicInput(int nr13, int nr14) {
        for (int shift = 1; shift <= 7; shift++) {
            for (boolean negate : booleans()) {
                FrequencySweep production = new FrequencySweep();
                production.start();
                production.setNr10((negate ? 0x08 : 0) | shift);
                production.setNr13(nr13);
                production.setNr14(nr14);
                production.trigger(false, false, false);
                for (int tick = 0; tick < 40; tick++) {
                    production.tick();
                }

                int shadow = nr13 | ((nr14 & 7) << 8);
                int delta = shadow >> shift;
                int legacy = negate ? shadow - delta : shadow + delta;
                boolean actual = production.isEnabled();
                if ((legacy <= 2047) != actual) {
                    throw new AssertionError("nr13=" + nr13 + " nr14=" + nr14 + " shift="
                            + shift + " negate=" + negate + " legacy=" + legacy
                            + " enabled=" + actual);
                }
            }
        }
    }

    private static SweepState legacyTick(SweepState old) {
        if (old.calculationDelay == 0 || old.shift == 0 && !old.unshiftedCalculation) {
            return old;
        }
        int delay = old.calculationDelay - 1;
        if (delay != 0) {
            return old.withCalculation(delay, old.unshiftedCalculation);
        }

        int delta = old.shadowFreq >> old.shift;
        int frequency = old.negate ? old.shadowFreq - delta : old.shadowFreq + delta;
        return old.withCalculationResult(
                old.overflow || frequency > 2047,
                old.negging || old.negate);
    }

    @SuppressWarnings("unchecked")
    private static ComponentState<FrequencySweep> componentState(SweepState state) throws Exception {
        return (ComponentState<FrequencySweep>) STATE_CONSTRUCTOR.newInstance(
                state.period, state.negate, state.shift, state.timer, state.shadowFreq,
                state.nr13, state.nr14, state.overflow, state.counterEnabled, state.negging,
                state.calculationDelay, state.unshiftedCalculation, state.restartHold,
                state.frequencyUpdatePending);
    }

    private static Constructor<?> stateConstructor() {
        ComponentState<FrequencySweep> state = new FrequencySweep().captureState();
        try {
            Constructor<?> constructor = state.getClass().getDeclaredConstructor(
                    int.class, boolean.class, int.class, int.class, int.class, int.class, int.class,
                    boolean.class, boolean.class, boolean.class, int.class, boolean.class,
                    int.class, boolean.class);
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String label(SweepState state) {
        return "shift=" + state.shift + " shadow=" + state.shadowFreq + " negate=" + state.negate
                + " overflow=" + state.overflow + " negging=" + state.negging
                + " unshifted=" + state.unshiftedCalculation;
    }

    private static boolean[] booleans() {
        return new boolean[]{false, true};
    }

    private record SweepState(int period, boolean negate, int shift, int timer, int shadowFreq,
                              int nr13, int nr14, boolean overflow, boolean counterEnabled,
                              boolean negging, int calculationDelay, boolean unshiftedCalculation,
                              int restartHold, boolean frequencyUpdatePending) {

        private SweepState withCalculation(int delay, boolean unshifted) {
            return new SweepState(period, negate, shift, timer, shadowFreq, nr13, nr14, overflow,
                    counterEnabled, negging, delay, unshifted, restartHold, frequencyUpdatePending);
        }

        private SweepState withCalculationResult(boolean overflowValue, boolean neggingValue) {
            return new SweepState(period, negate, shift, timer, shadowFreq, nr13, nr14, overflowValue,
                    counterEnabled, neggingValue, 0, false, restartHold, frequencyUpdatePending);
        }
    }
}
