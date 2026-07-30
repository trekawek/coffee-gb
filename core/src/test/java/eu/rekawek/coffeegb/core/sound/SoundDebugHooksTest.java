package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.DebugInterruptType;
import eu.rekawek.coffeegb.core.debug.trace.ApuTrace;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.timer.Timer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class SoundDebugHooksTest {

    @Test
    public void acceptedWritesPrecedeTriggersAndRealDisableTransitions() {
        Sound sound = newSound(true);
        RecordingHooks hooks = new RecordingHooks();
        sound.setDebugHooks(hooks);

        sound.setByte(0xff12, 0xf0);
        hooks.events.clear();
        sound.setByte(0xff14, 0x80);
        assertEquals(List.of(
                event(ApuTrace.Kind.REGISTER_WRITTEN, 1, 0xff14, 0x80),
                event(ApuTrace.Kind.CHANNEL_TRIGGERED, 1, 0xff14, 0x80)), hooks.events);

        hooks.events.clear();
        sound.setByte(0xff12, 0x00);
        assertEquals(List.of(
                event(ApuTrace.Kind.REGISTER_WRITTEN, 1, 0xff12, 0x00),
                event(ApuTrace.Kind.CHANNEL_DISABLED, 1, -1, -1)), hooks.events);

        hooks.events.clear();
        sound.setByte(0xff12, 0x00);
        assertEquals(List.of(
                event(ApuTrace.Kind.REGISTER_WRITTEN, 1, 0xff12, 0x00)), hooks.events);

        hooks.events.clear();
        sound.setByte(0xff14, 0x80);
        assertEquals(List.of(
                event(ApuTrace.Kind.REGISTER_WRITTEN, 1, 0xff14, 0x80),
                event(ApuTrace.Kind.CHANNEL_TRIGGERED, 1, 0xff14, 0x80)), hooks.events);
    }

    @Test
    public void powerOffAndWaveAccessOnlyReportWritesAcceptedByTheApu() {
        Sound cgb = newSound(true);
        RecordingHooks cgbHooks = new RecordingHooks();
        cgb.setDebugHooks(cgbHooks);
        cgb.setByte(0xff26, 0x00);
        cgbHooks.events.clear();

        cgb.setByte(0xff12, 0xf0);
        cgb.setByte(0xff26, 0x00);

        assertEquals(List.of(
                event(ApuTrace.Kind.REGISTER_WRITTEN, -1, 0xff26, 0x00)), cgbHooks.events);

        Sound dmg = newSound(false);
        RecordingHooks dmgHooks = new RecordingHooks();
        dmg.setDebugHooks(dmgHooks);
        dmg.setByte(0xff1a, 0x80);
        dmg.setByte(0xff1e, 0x80);
        dmgHooks.events.clear();

        dmg.setByte(0xff30, 0x55);
        assertEquals(List.of(), dmgHooks.events);
        dmg.setByte(0xff26, 0x00);
        dmgHooks.events.clear();
        dmg.setByte(0xff11, 0x3f);

        assertEquals(List.of(
                event(ApuTrace.Kind.REGISTER_WRITTEN, 1, 0xff11, 0x3f)), dmgHooks.events);
    }

    @Test
    public void frameStepPrecedesTheChannelDisableItCauses() {
        SpeedMode speedMode = new SpeedMode(false);
        Timer timer = new Timer(new InterruptManager(false), speedMode);
        Sound sound = new Sound(timer, speedMode, false);
        RecordingHooks hooks = new RecordingHooks();
        sound.setDebugHooks(hooks);
        sound.setByte(0xff11, 0x3f); // length = 1
        sound.setByte(0xff12, 0xf0); // DAC on
        sound.setByte(0xff14, 0xc0); // trigger and enable length
        hooks.events.clear();

        timer.presetDiv(0x1000);
        sound.tickFrameSequencer();
        timer.presetDiv(0x0000);
        sound.tickFrameSequencer();
        sound.commitFrameSequencerClock();

        assertEquals(List.of(
                event(ApuTrace.Kind.FRAME_SEQUENCER_STEP, -1, -1, 0),
                event(ApuTrace.Kind.CHANNEL_DISABLED, 1, -1, -1)), hooks.events);
    }

    @Test
    public void powerOffWritePrecedesDisablingEnabledChannels() {
        Sound sound = newSound(true);
        RecordingHooks hooks = new RecordingHooks();
        sound.setDebugHooks(hooks);
        sound.setByte(0xff12, 0xf0);
        sound.setByte(0xff14, 0x80);
        hooks.events.clear();

        sound.setByte(0xff26, 0x00);

        assertEquals(List.of(
                event(ApuTrace.Kind.REGISTER_WRITTEN, -1, 0xff26, 0x00),
                event(ApuTrace.Kind.CHANNEL_DISABLED, 1, -1, -1)), hooks.events);
    }

    @Test
    public void attachmentAndRestoreDoNotEmitApuEvents() {
        Sound sound = newSound(false);
        ComponentState<Sound> state = sound.captureState();
        RecordingHooks hooks = new RecordingHooks();

        sound.setDebugHooks(hooks);
        sound.restoreState(state);

        assertEquals(List.of(), hooks.events);
    }

    private static Sound newSound(boolean gbc) {
        SpeedMode speedMode = new SpeedMode(gbc);
        Timer timer = new Timer(new InterruptManager(gbc), speedMode);
        return new Sound(timer, speedMode, gbc);
    }

    private static ApuEvent event(
            ApuTrace.Kind kind, int channel, int register, int value) {
        return new ApuEvent(kind, channel, register, value);
    }

    private record ApuEvent(ApuTrace.Kind kind, int channel, int register, int value) {
    }

    private static final class RecordingHooks implements DebugHooks {

        private final List<ApuEvent> events = new ArrayList<>();

        @Override
        public void onApuEvent(ApuTrace.Kind kind, int channel, int register, int value) {
            events.add(new ApuEvent(kind, channel, register, value));
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
}
