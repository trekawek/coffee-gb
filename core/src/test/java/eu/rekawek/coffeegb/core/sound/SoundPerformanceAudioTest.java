package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.ExecutionMode;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.timer.Timer;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;

/** Tests the deliberately compact, PERFORMANCE-only host-audio source stream. */
public class SoundPerformanceAudioTest {

    @Test
    public void performanceSourceIs55thRateAndSamplesEachWindow() {
        ClockSpec sourceClock = new ClockSpec(550, 1, 1);
        Sound sound = newSound(sourceClock);
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        List<Sound.SoundSampleEvent> events = new ArrayList<>();
        eventBus.register(events::add, Sound.SoundSampleEvent.class);
        sound.init(eventBus);

        // A compact source frame has ten samples, each representing 55 master ticks.
        for (int sample = 0; sample < 549; sample++) {
            int value = sample < 55 ? sample * 2 : 0;
            sound.play(value, -value);
        }

        assertEquals(0, events.size());
        sound.play(0, 0);

        assertEquals(1, events.size());
        Sound.SoundSampleEvent event = events.get(0);
        assertEquals(new ClockSpec(10, 1, 1), event.clockSpec());
        assertEquals(20, event.buffer().length);
        assertEquals(108, event.buffer()[0]);
        assertEquals(-108, event.buffer()[1]);
    }

    @Test
    public void legacyPerformanceSourceAdvertises76260HzAt55TickCadence() {
        Sound sound = newSound(ClockSpec.LEGACY);
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        List<Sound.SoundSampleEvent> events = new ArrayList<>();
        eventBus.register(events::add, Sound.SoundSampleEvent.class);
        sound.init(eventBus);

        // 1,271 compact samples exactly cover the historical 69,905-T controller chunk.
        int sourceTicks = 1_271 * 55;
        for (int i = 0; i < sourceTicks; i++) {
            sound.play(i, -i);
        }

        assertEquals(1, events.size());
        Sound.SoundSampleEvent event = events.get(0);
        assertEquals(4_194_304L, event.clockSpec().ticksPerSecondNumerator());
        assertEquals(55L, event.clockSpec().ticksPerSecondDenominator());
        assertEquals(1_271 * 2, event.buffer().length);
    }

    @Test
    public void canonicalSpanStopsBeforeSynchronousHostCallback() {
        ClockSpec sourceClock = new ClockSpec(550, 1, 1);
        Sound sound = newSound(sourceClock);
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        List<Sound.SoundSampleEvent> events = new ArrayList<>();
        eventBus.register(events::add, Sound.SoundSampleEvent.class);
        sound.init(eventBus);

        // Ten compact samples fill this host buffer. The first 549 ticks may be one exact span,
        // while the callback-producing 550th tick remains owned by the scalar scheduler.
        assertEquals(549, sound.performanceQuietSpanLimit(1_000));
        sound.tickPerformanceQuietSpan(549);
        assertEquals(0, events.size());
        assertEquals(0, sound.performanceQuietSpanLimit(1));
        sound.tick(false);
        assertEquals(1, events.size());
        assertEquals(20, events.get(0).buffer().length);
    }

    @Test
    public void sgbPerformanceSourcesEmit1254SamplesPerExactFrame() {
        for (ClockSpec sourceClock : new ClockSpec[]{ClockSpec.SGB, ClockSpec.SGB2}) {
            Sound sound = newSound(sourceClock);
            EventBusImpl eventBus = new EventBusImpl(null, null, false);
            List<Sound.SoundSampleEvent> events = new ArrayList<>();
            eventBus.register(events::add, Sound.SoundSampleEvent.class);
            sound.init(eventBus);

            for (int tick = 0; tick < 70_224; tick++) {
                sound.play(tick, -tick);
            }

            assertEquals(1, events.size());
            Sound.SoundSampleEvent event = events.get(0);
            assertEquals(1_254, event.clockSpec().controllerTicksPerFrame());
            assertEquals(new ClockSpec(
                            sourceClock.ticksPerSecondNumerator(),
                            sourceClock.ticksPerSecondDenominator() * 56L,
                            sourceClock.controllerFramesPerSecondNumerator(),
                            sourceClock.controllerFramesPerSecondDenominator()),
                    event.clockSpec());
            assertEquals(1_254 * 2, event.buffer().length);
            eventBus.close();
        }
    }

    @Test
    public void partialDecimationWindowSurvivesStateRestore() {
        ClockSpec sourceClock = new ClockSpec(550, 1, 1);
        Sound sound = newSound(sourceClock);
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        List<Sound.SoundSampleEvent> events = new ArrayList<>();
        eventBus.register(events::add, Sound.SoundSampleEvent.class);
        sound.init(eventBus);

        sound.play(11, 11);
        sound.play(22, 22);
        var state = sound.captureState();
        sound.play(100, 100);
        sound.play(100, 100);
        sound.restoreState(state);

        for (int sample = 2; sample < 550; sample++) {
            sound.play(0, 0);
        }

        assertEquals(1, events.size());
        int[] expected = new int[20];
        expected[0] = 0;
        expected[1] = 0;
        assertArrayEquals(expected, events.get(0).buffer());
    }

    @Test
    public void accuracyStateWithLargeAudioPrefixRestoresIntoPerformance() {
        ClockSpec sourceClock = new ClockSpec(550, 1, 1);
        Sound accuracy = newSound(sourceClock, ExecutionMode.ACCURACY);
        for (int sample = 0; sample < 80; sample++) {
            accuracy.play(sample, -sample);
        }
        var state = accuracy.captureState();

        Sound performance = newSound(sourceClock, ExecutionMode.PERFORMANCE);
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        List<Sound.SoundSampleEvent> events = new ArrayList<>();
        eventBus.register(events::add, Sound.SoundSampleEvent.class);
        performance.init(eventBus);
        performance.restoreState(state);
        assertEquals(0x80, performance.getByte(0xff26) & 0x80);
        for (int sample = 0; sample < 549; sample++) {
            performance.play(0, 0);
        }
        assertEquals(0, events.size());
        performance.play(0, 0);
        assertEquals(1, events.size());
    }

    @Test
    public void compactPerformanceStateRestoresIntoAccuracy() {
        ClockSpec sourceClock = new ClockSpec(550, 1, 1);
        Sound performance = newSound(sourceClock, ExecutionMode.PERFORMANCE);
        for (int sample = 0; sample < 7; sample++) {
            performance.play(sample, sample);
        }
        var state = performance.captureState();

        Sound accuracy = newSound(sourceClock, ExecutionMode.ACCURACY);
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        List<Sound.SoundSampleEvent> events = new ArrayList<>();
        eventBus.register(events::add, Sound.SoundSampleEvent.class);
        accuracy.init(eventBus);
        accuracy.restoreState(state);
        assertEquals(0x80, accuracy.getByte(0xff26) & 0x80);
        for (int sample = 0; sample < 549; sample++) {
            accuracy.play(0, 0);
        }
        assertEquals(0, events.size());
        accuracy.play(0, 0);
        assertEquals(1, events.size());
    }

    @Test
    public void legacySgbDecimationStateIsAcceptedButDropsItsHostAudioPrefix() throws Exception {
        Sound source = newSound(ClockSpec.SGB);
        for (int tick = 0; tick < 56; tick++) {
            source.play(tick, -tick);
        }
        var legacyState = withAudioDecimation(source.captureState(), 11);

        Sound restored = newSound(ClockSpec.SGB);
        restored.restoreState(legacyState);

        assertEquals(0, component(restored.captureState(), "i"));
        assertEquals(0, component(restored.captureState(), "performanceSamplePhase"));
        assertEquals(11, component(legacyState, "audioDecimation"));
        assertEquals(56, component(restored.captureState(), "audioDecimation"));
    }

    private static Sound newSound(ClockSpec sourceClock) {
        return newSound(sourceClock, ExecutionMode.PERFORMANCE);
    }

    private static Sound newSound(ClockSpec sourceClock, ExecutionMode executionMode) {
        SpeedMode speedMode = new SpeedMode(true);
        Timer timer = new Timer(new InterruptManager(true), speedMode);
        return new Sound(timer, speedMode, true, sourceClock, executionMode);
    }

    @SuppressWarnings("unchecked")
    private static <T> ComponentState<T> withAudioDecimation(
            ComponentState<T> state, int decimation) throws Exception {
        Class<?> type = state.getClass();
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            parameterTypes[i] = component.getType();
            component.getAccessor().setAccessible(true);
            values[i] = component.getAccessor().invoke(state);
            if (component.getName().equals("audioDecimation")) {
                values[i] = decimation;
            }
        }
        Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        return (ComponentState<T>) constructor.newInstance(values);
    }

    private static Object component(Object state, String name) throws Exception {
        for (RecordComponent component : state.getClass().getRecordComponents()) {
            if (component.getName().equals(name)) {
                component.getAccessor().setAccessible(true);
                return component.getAccessor().invoke(state);
            }
        }
        throw new AssertionError("Missing state component: " + name);
    }
}
