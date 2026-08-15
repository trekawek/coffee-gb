package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;

import static org.junit.Assert.assertEquals;

public class SquareOscillatorPhasePackingTest {

    @Test
    public void constructorKeepsTheOldFalseFalsePhase() throws Exception {
        for (int channel = 1; channel <= 2; channel++) {
            AbstractSoundMode mode = newMode(channel);
            assertPhase(channel, 0, mode.captureState());

            mode.tick(false);

            assertPhase(channel, 3, mode.captureState());
        }
    }

    @Test
    public void allReleasedPhaseCombinationsRoundTrip() throws Exception {
        for (int channel = 1; channel <= 2; channel++) {
            for (int phase = 0; phase < 4; phase++) {
                AbstractSoundMode mode = newMode(channel);
                mode.restoreState(withPhase(mode.captureState(), phase));

                assertPhase(channel, phase, mode.captureState());
            }
        }
    }

    @Test
    public void packedPhaseHasTheExactOldTransitionForEveryState() throws Exception {
        for (int channel = 1; channel <= 2; channel++) {
            for (int phase = 0; phase < 4; phase++) {
                AbstractSoundMode mode = newMode(channel);
                mode.restoreState(withPhase(mode.captureState(), phase));

                mode.tick(false);

                assertPhase(channel, (phase - 1) & 3, mode.captureState());
            }
        }
    }

    @Test
    public void triggerReadsTheSameLowFrequencyPhaseForEveryState() throws Exception {
        for (int channel = 1; channel <= 2; channel++) {
            for (int phase = 0; phase < 4; phase++) {
                AbstractSoundMode mode = newMode(channel);
                mode.restoreState(withPhase(mode.captureState(), phase));

                mode.trigger();

                int expectedDivider = (2048 - 1) * 2 + 6 - ((phase & 2) != 0 ? 1 : 0);
                assertEquals(label(channel, phase), expectedDivider,
                        component(mode.captureState(), "freqDivider"));
            }
        }
    }

    private static AbstractSoundMode newMode(int channel) {
        FrameSequencer frameSequencer = new FrameSequencer();
        return channel == 1
                ? new SoundMode1(frameSequencer, false)
                : new SoundMode2(frameSequencer, false);
    }

    private static void assertPhase(int channel, int phase,
                                    ComponentState<AbstractSoundMode> state) throws Exception {
        assertEquals(label(channel, phase), (phase & 1) != 0,
                component(state, "clock2Mhz"));
        assertEquals(label(channel, phase), (phase & 2) != 0,
                component(state, "lowFrequencyPhase"));
    }

    private static String label(int channel, int phase) {
        return "channel=" + channel + ", phase=" + phase;
    }

    private static ComponentState<AbstractSoundMode> withPhase(
            ComponentState<AbstractSoundMode> state, int phase) throws Exception {
        Class<?> type = state.getClass();
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            parameterTypes[i] = component.getType();
            component.getAccessor().setAccessible(true);
            values[i] = component.getAccessor().invoke(state);
            if (component.getName().equals("clock2Mhz")) {
                values[i] = (phase & 1) != 0;
            } else if (component.getName().equals("lowFrequencyPhase")) {
                values[i] = (phase & 2) != 0;
            }
        }
        Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        @SuppressWarnings("unchecked")
        ComponentState<AbstractSoundMode> result =
                (ComponentState<AbstractSoundMode>) constructor.newInstance(values);
        return result;
    }

    private static Object component(ComponentState<AbstractSoundMode> state,
                                    String name) throws Exception {
        for (RecordComponent component : state.getClass().getRecordComponents()) {
            if (component.getName().equals(name)) {
                component.getAccessor().setAccessible(true);
                return component.getAccessor().invoke(state);
            }
        }
        throw new AssertionError("Missing state component: " + name);
    }
}
