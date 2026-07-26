package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.controller.properties.EmulatorProperties;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.sound.Sound;
import org.junit.Test;

import java.lang.reflect.Field;
import java.math.BigInteger;
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

    @Test
    public void rationalSgbClockHasExactChunkIndependentSampleCountAndPhase() throws Exception {
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        AudioSystemSound output = new AudioSystemSound(
                new EmulatorProperties().getSound(), eventBus, null);
        BlockingQueue<byte[]> queue = queue(output);
        ClockSpec clock = ClockSpec.SGB;
        int chunks = 73;
        int ticksPerChunk = 997;
        long samples = 0;
        for (int i = 0; i < chunks; i++) {
            eventBus.post(new Sound.SoundSampleEvent(new int[ticksPerChunk * 2], clock));
            samples += queue.remove().length / 4L;
        }

        BigInteger numerator = BigInteger.valueOf((long) chunks * ticksPerChunk)
                .multiply(BigInteger.valueOf(44_100))
                .multiply(BigInteger.valueOf(clock.ticksPerSecondDenominator()));
        BigInteger[] expected = numerator.divideAndRemainder(
                BigInteger.valueOf(clock.ticksPerSecondNumerator()));
        assertEquals(expected[0].longValueExact(), samples);
        assertEquals(expected[1].longValueExact(), output.samplePhaseForTesting());
        eventBus.close();
    }

    @Test
    public void pauseAndSameProfileResumePreserveAudioFractionalPhase() throws Exception {
        EventBusImpl eventBus = new EventBusImpl(null, null, false);
        AudioSystemSound output = new AudioSystemSound(
                new EmulatorProperties().getSound(), eventBus, null);
        BlockingQueue<byte[]> queue = queue(output);
        ClockSpec clock = ClockSpec.SGB2;

        eventBus.post(new Sound.SoundSampleEvent(new int[86], clock));
        queue.remove();
        long phaseBeforePause = output.samplePhaseForTesting();

        // A portable restore/pause posts no host-audio clock transition. Supplying the same exact
        // registered clock afterwards must continue, rather than resetting, the consumer phase.
        eventBus.post(new Sound.SoundSampleEvent(new int[134], ClockSpec.SGB2));
        long samples = queue.remove().length / 4L;
        BigInteger numerator = BigInteger.valueOf(110L)
                .multiply(BigInteger.valueOf(44_100));
        BigInteger[] expected = numerator.divideAndRemainder(
                BigInteger.valueOf(clock.ticksPerSecondNumerator()));
        assertEquals(expected[0].longValueExact(), samples);
        assertEquals(expected[1].longValueExact(), output.samplePhaseForTesting());
        org.junit.Assert.assertNotEquals(0L, phaseBeforePause);
        eventBus.close();
    }

    @SuppressWarnings("unchecked")
    private static BlockingQueue<byte[]> queue(AudioSystemSound sound) throws Exception {
        Field field = AudioSystemSound.class.getDeclaredField("queue");
        field.setAccessible(true);
        return (BlockingQueue<byte[]>) field.get(sound);
    }
}
