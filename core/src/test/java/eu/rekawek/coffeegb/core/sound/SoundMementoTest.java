package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.timer.Timer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class SoundMementoTest {

    @Test
    public void frameBoundaryStateDoesNotRetainTheEmittedFrameBuffer() {
        Sound sound = newSound();
        tick(sound, Gameboy.TICKS_PER_FRAME);

        long retainedBytes = MachineStateCapture.withVerifiedView(
                sound::declareMachineStatePayloads,
                sound::captureState,
                (state, capture) -> capture.getVerifiedPayloadBytes());

        // Sound owns three integer register values plus the behavior-relevant prefix of its large
        // output buffer. At a frame boundary that prefix is empty, so the 546 KiB frame buffer
        // contributes zero bytes to the explicit state view.
        assertEquals(3L * Integer.BYTES, retainedBytes);
    }

    @Test
    public void partialFramePrefixSurvivesMementoRestore() {
        Sound sound = newSound();
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        List<int[]> frames = new ArrayList<>();
        eventBus.register(event -> frames.add(event.buffer().clone()), Sound.SoundSampleEvent.class);
        sound.init(eventBus);

        // An enabled but inactive DAC contributes a constant analog level. That makes the
        // saved prefix observably different from the zeroes used to overwrite it below.
        sound.setByte(0xff12, 0xf0);
        sound.setByte(0xff25, 0x11);
        int prefixTicks = 127;
        tick(sound, prefixTicks);
        var memento = sound.captureState();

        tick(sound, Gameboy.TICKS_PER_FRAME - prefixTicks);
        int[] expected = frames.remove(0);

        sound.setByte(0xff12, 0);
        tick(sound, prefixTicks);
        sound.restoreState(memento);
        tick(sound, Gameboy.TICKS_PER_FRAME - prefixTicks);

        assertArrayEquals(expected, frames.remove(0));
    }

    @Test
    public void sampleChunkUsesTheOwningCustomClockInsteadOfLegacyGlobals() {
        ClockSpec clock = new ClockSpec(96_000, 60, 1);
        SpeedMode speedMode = new SpeedMode(true);
        Sound sound = new Sound(new Timer(new InterruptManager(true), speedMode), speedMode, true, clock);
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        List<Sound.SoundSampleEvent> frames = new ArrayList<>();
        eventBus.register(frames::add, Sound.SoundSampleEvent.class);
        sound.init(eventBus);

        tick(sound, clock.controllerTicksPerFrame() - 1);
        assertEquals(0, frames.size());
        tick(sound, 1);

        assertEquals(1, frames.size());
        assertEquals(clock, frames.get(0).clockSpec());
        assertEquals(clock.controllerTicksPerFrame() * 2, frames.get(0).buffer().length);
    }

    private static Sound newSound() {
        SpeedMode speedMode = new SpeedMode(true);
        Timer timer = new Timer(new InterruptManager(true), speedMode);
        return new Sound(timer, speedMode, true);
    }

    private static void tick(Sound sound, int ticks) {
        for (int i = 0; i < ticks; i++) {
            sound.tick();
        }
    }
}
