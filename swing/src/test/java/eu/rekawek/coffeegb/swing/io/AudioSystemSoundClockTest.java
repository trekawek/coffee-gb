package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.controller.properties.EmulatorProperties;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.sound.Sound;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.BlockingQueue;

import static org.junit.Assert.assertEquals;

public class AudioSystemSoundClockTest {

    @Test
    public void exactSessionClockAccumulatorHasNoChunkingDrift() throws Exception {
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        AudioSystemSound output = new AudioSystemSound(
                new EmulatorProperties().getSound(), eventBus, null);
        eventBus.post(new Sound.SoundEnabledEvent(true));
        ClockSpec custom = new ClockSpec(100_000, 10, 1);
        BlockingQueue<byte[]> queue = queue(output);

        long samples = 0;
        int chunks = 1_000;
        int ticksPerChunk = 101;
        for (int i = 0; i < chunks; i++) {
            eventBus.post(new Sound.SoundSampleEvent(new int[ticksPerChunk * 2], custom));
            samples += queue.remove().length / 4L;
        }

        assertEquals((long) chunks * ticksPerChunk * 44_100 / custom.ticksPerSecond(), samples);
    }

    @SuppressWarnings("unchecked")
    private static BlockingQueue<byte[]> queue(AudioSystemSound sound) throws Exception {
        Field field = AudioSystemSound.class.getDeclaredField("queue");
        field.setAccessible(true);
        return (BlockingQueue<byte[]>) field.get(sound);
    }
}
