package eu.rekawek.coffeegb.core.timer;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.DebugInterruptType;
import eu.rekawek.coffeegb.core.debug.trace.TimerTrace;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class TimerDebugHooksTest {

    @Test
    public void reportsOverflowAndReloadBeforeTheInterruptRequest() {
        InterruptManager interrupts = new InterruptManager(true);
        Timer timer = new Timer(interrupts, new SpeedMode(true));
        timer.presetDiv(0x000f);
        timer.setByte(0xff05, 0xff);
        timer.setByte(0xff06, 0xa5);
        timer.setByte(0xff07, 0x05);
        RecordingHooks hooks = new RecordingHooks();
        timer.setDebugHooks(hooks);
        interrupts.setDebugHooks(hooks);

        timer.tick();
        for (int i = 0; i < 8 && hooks.events.size() < 3; i++) {
            timer.tick();
        }

        assertEquals(List.of(
                "COUNTER_OVERFLOWED:0010:00:A5:5",
                "COUNTER_RELOADED:0013:A5:A5:5",
                "IRQ:TIMER"), hooks.events);
    }

    @Test
    public void edgeEffectsPrecedeDividerAndControlTransitions() {
        Timer timer = new Timer(new InterruptManager(true), new SpeedMode(true));
        timer.presetDiv(0x0008);
        timer.setByte(0xff05, 0x20);
        timer.setByte(0xff07, 0x05);
        RecordingHooks hooks = new RecordingHooks();
        timer.setDebugHooks(hooks);

        timer.setByte(0xff04, 0);

        assertEquals(List.of(
                "COUNTER_INCREMENTED:0000:21:00:5",
                "DIVIDER_RESET:0000:21:00:5"), hooks.events);

        hooks.events.clear();
        timer.presetDiv(0x0008);
        timer.setByte(0xff07, 0x04);

        assertEquals(List.of(
                "COUNTER_INCREMENTED:0008:22:00:4",
                "CONTROL_CHANGED:0008:22:00:4"), hooks.events);

        hooks.events.clear();
        timer.setByte(0xff07, 0xfc);
        assertEquals(List.of(), hooks.events);
    }

    @Test
    public void attachmentAndRestoreDoNotEmitTransitions() {
        Timer timer = new Timer(new InterruptManager(false), new SpeedMode(false));
        timer.setByte(0xff04, 0);
        Timer.TimerState state = (Timer.TimerState) timer.captureState();
        RecordingHooks hooks = new RecordingHooks();

        timer.setDebugHooks(hooks);
        timer.restoreState(state);

        assertEquals(List.of(), hooks.events);
    }

    private static final class RecordingHooks implements DebugHooks {

        private final List<String> events = new ArrayList<>();

        @Override
        public void onTimerEvent(
                TimerTrace.Kind kind, int divider, int counter, int modulo, int control) {
            events.add(String.format(
                    "%s:%04X:%02X:%02X:%X",
                    kind, divider, counter, modulo, control));
        }

        @Override
        public void onInterruptRequested(DebugInterruptType interrupt) {
            events.add("IRQ:" + interrupt);
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
        public void onInterruptAccepted(DebugInterruptType interrupt) {
        }
    }
}
