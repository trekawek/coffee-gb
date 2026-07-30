package eu.rekawek.coffeegb.core.joypad;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static eu.rekawek.coffeegb.core.joypad.InputTimelineObserver.Phase.LEGACY_P1_BEFORE_TICK;
import static eu.rekawek.coffeegb.core.joypad.InputTimelineObserver.Phase.PHYSICAL_JOYPAD_SAMPLE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JoypadInputTimelineObserverTest {

    @Test
    public void attachmentIsExclusiveAndDetachRequiresTheOwningObserver() {
        MutableInput input = new MutableInput(PlayerInputSnapshot.of(List.of(
                Set.of(Button.A), Set.of(), Set.of(Button.LEFT), Set.of())));
        Joypad joypad = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false, input);
        joypad.setPressedButtons(Set.of(Button.B));
        joypad.tick();
        RecordingObserver first = new RecordingObserver();
        RecordingObserver second = new RecordingObserver();

        assertTrue(joypad.attachInputTimelineObserver(first));
        assertFalse(joypad.attachInputTimelineObserver(second));
        assertFalse(joypad.detachInputTimelineObserver(second));
        joypad.restoreState(joypad.captureState());
        assertTrue(joypad.detachInputTimelineObserver(first));
        assertTrue(joypad.attachInputTimelineObserver(second));

        assertEquals(List.of(), first.events);
        assertEquals(List.of(), second.events);
    }

    @Test
    public void legacyEventsKeepSourceLocalOrderWithinOneEmulatorTick() {
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        Joypad joypad = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false);
        joypad.init(eventBus);
        RecordingObserver observer = new RecordingObserver();
        assertTrue(joypad.attachInputTimelineObserver(observer));

        eventBus.post(new ButtonPressEvent(Button.A));
        eventBus.post(new ButtonPressEvent(Button.B));
        eventBus.post(new ButtonReleaseEvent(Button.A));
        eventBus.post(new ButtonPressEvent(Button.B));

        assertEquals(List.of(
                new InputEvent(LEGACY_P1_BEFORE_TICK, 0,
                        JoypadButtonMask.A, JoypadButtonMask.A),
                new InputEvent(LEGACY_P1_BEFORE_TICK, 0,
                        JoypadButtonMask.A | JoypadButtonMask.B, JoypadButtonMask.B),
                new InputEvent(LEGACY_P1_BEFORE_TICK, 0,
                        JoypadButtonMask.B, JoypadButtonMask.A)), observer.events);
    }

    @Test
    public void bulkLegacyReplacementReportsOneAbsoluteSourceLocalTransition() {
        MutableInput input = new MutableInput(PlayerInputSnapshot.of(List.of(
                Set.of(Button.A), Set.of(), Set.of(), Set.of())));
        Joypad joypad = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false, input);
        joypad.tick();
        joypad.setPressedButtons(Set.of(Button.A));
        RecordingObserver observer = new RecordingObserver();
        assertTrue(joypad.attachInputTimelineObserver(observer));

        // Physical A remains pressed, but the legacy source changes independently from A to B.
        joypad.setPressedButtons(Set.of(Button.B));

        assertEquals(List.of(new InputEvent(LEGACY_P1_BEFORE_TICK, 0,
                JoypadButtonMask.B, JoypadButtonMask.A | JoypadButtonMask.B)), observer.events);
    }

    @Test
    public void physicalSamplesAreCoalescedAndReportedOncePerChangedPlayer() {
        MutableInput input = new MutableInput(PlayerInputSnapshot.released());
        Joypad joypad = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, true, input);
        RecordingObserver observer = new RecordingObserver();
        assertTrue(joypad.attachInputTimelineObserver(observer));

        input.snapshot = PlayerInputSnapshot.of(List.of(
                Set.of(Button.A),
                Set.of(),
                Set.of(Button.LEFT, Button.START),
                Set.of()));
        joypad.tick();
        input.snapshot = PlayerInputSnapshot.of(List.of(
                Set.of(Button.A, Button.B),
                Set.of(Button.UP),
                Set.of(Button.LEFT),
                Set.of()));
        joypad.tick();

        assertEquals(List.of(
                new InputEvent(PHYSICAL_JOYPAD_SAMPLE, 0,
                        JoypadButtonMask.A, JoypadButtonMask.A),
                new InputEvent(PHYSICAL_JOYPAD_SAMPLE, 2,
                        JoypadButtonMask.LEFT | JoypadButtonMask.START,
                        JoypadButtonMask.LEFT | JoypadButtonMask.START),
                new InputEvent(PHYSICAL_JOYPAD_SAMPLE, 0,
                        JoypadButtonMask.A | JoypadButtonMask.B, JoypadButtonMask.B),
                new InputEvent(PHYSICAL_JOYPAD_SAMPLE, 1,
                        JoypadButtonMask.UP, JoypadButtonMask.UP),
                new InputEvent(PHYSICAL_JOYPAD_SAMPLE, 2,
                        JoypadButtonMask.LEFT, JoypadButtonMask.START)), observer.events);
    }

    private record InputEvent(InputTimelineObserver.Phase phase, int player,
                              int buttonMask, int changedMask) {
    }

    private static final class RecordingObserver implements InputTimelineObserver {

        private final List<InputEvent> events = new ArrayList<>();

        @Override
        public void onInputChanged(Phase phase, int player, int buttonMask, int changedMask) {
            events.add(new InputEvent(phase, player, buttonMask, changedMask));
        }
    }

    private static final class MutableInput implements PlayerInputSource {

        private PlayerInputSnapshot snapshot;

        private MutableInput(PlayerInputSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public PlayerInputSnapshot sample() {
            return snapshot;
        }
    }
}
