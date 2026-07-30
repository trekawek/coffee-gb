package eu.rekawek.coffeegb.core.joypad;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.debug.DebugButton;
import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.DebugInterruptType;
import eu.rekawek.coffeegb.core.debug.trace.InputTrace;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class JoypadDebugHooksTest {

    @Test
    public void reportsEffectiveP1UnionWithStableDebugButtonBitsAndNoDuplicates() {
        MutableInput input = new MutableInput(Set.of(Button.A));
        Joypad joypad = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false, input);
        joypad.tick();
        joypad.setPressedButtons(Set.of(Button.A));
        RecordingHooks hooks = new RecordingHooks();
        joypad.setDebugHooks(hooks);

        // Removing or adding a second owner of the same physical button leaves the union intact.
        joypad.setPressedButtons(Set.of());
        joypad.setPressedButtons(Set.of(Button.A));
        input.buttons = Set.of(Button.A, Button.B);
        joypad.tick();
        joypad.setPressedButtons(Set.of());
        input.buttons = Set.of(Button.START);
        joypad.tick();
        joypad.setPressedButtons(Set.of(Button.START));
        input.buttons = Set.of();
        joypad.tick();
        joypad.setPressedButtons(Set.of());

        int a = 1 << DebugButton.A.ordinal();
        int b = 1 << DebugButton.B.ordinal();
        int start = 1 << DebugButton.START.ordinal();
        assertEquals(List.of(
                new InputEvent(InputTrace.Kind.PRESSED, a | b, b),
                new InputEvent(InputTrace.Kind.STATE_CHANGED, start, a | b | start),
                new InputEvent(InputTrace.Kind.RELEASED, 0, start)), hooks.events);
    }

    @Test
    public void attachmentAndRestoreOnlyAlignTheObservedUnion() {
        Joypad joypad = new Joypad(
                new InterruptManager(false), EventBus.NULL_EVENT_BUS, false);
        joypad.setPressedButtons(Set.of(Button.LEFT));
        ComponentState<Joypad> state = joypad.captureState();
        RecordingHooks hooks = new RecordingHooks();

        joypad.setDebugHooks(hooks);
        joypad.restoreState(state);

        assertEquals(List.of(), hooks.events);
    }

    private record InputEvent(InputTrace.Kind kind, int buttonMask, int changedMask) {
    }

    private static final class RecordingHooks implements DebugHooks {

        private final List<InputEvent> events = new ArrayList<>();

        @Override
        public void onInputEvent(InputTrace.Kind kind, int buttonMask, int changedMask) {
            events.add(new InputEvent(kind, buttonMask, changedMask));
        }

        @Override
        public void onInstructionFetch(int programCounter) {
        }

        @Override
        public void onOpcodeFetched(int programCounter, boolean cbPrefixed, int opcode) {
        }

        @Override
        public void onInstructionRetired(
                boolean instructionKnown, int programCounter, int opcode, int prefixedOpcode) {
        }

        @Override
        public void onInterruptRequested(DebugInterruptType interrupt) {
        }

        @Override
        public void onInterruptAccepted(DebugInterruptType interrupt) {
        }
    }

    private static final class MutableInput implements PlayerInputSource {

        private Set<Button> buttons;

        private MutableInput(Set<Button> buttons) {
            this.buttons = buttons;
        }

        @Override
        public PlayerInputSnapshot sample() {
            return PlayerInputSnapshot.of(List.of(buttons, Set.of(), Set.of(), Set.of()));
        }
    }
}
