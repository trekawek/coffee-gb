package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.sound.Sound;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class BoundedPcmQueueTest {

    @Test
    public void fullQueueDropsOldestFramesInsteadOfGrowingOrBlockingProducer() throws Exception {
        BoundedPcmQueue queue = new BoundedPcmQueue(44_100, 2, 4_096);
        Sound.SoundSampleEvent event = new Sound.SoundSampleEvent(samples(2_000), ClockSpec.LEGACY);

        queue.offer(event, 100, false);
        queue.offer(event, 100, false);
        queue.offer(event, 100, false);

        assertEquals(2, queue.queuedFrames());
        assertEquals(1, queue.overruns());
        BoundedPcmQueue.Frame first = queue.poll(1, TimeUnit.SECONDS);
        BoundedPcmQueue.Frame second = queue.poll(1, TimeUnit.SECONDS);
        assertNotNull(first);
        assertNotNull(second);
        assertTrue(first.length() > 0);
        assertTrue(second.length() > 0);
        queue.release(first);
        queue.release(second);
    }

    @Test
    public void clearingQueuedHostFramesKeepsFractionalResamplerContinuity() {
        BoundedPcmQueue queue = new BoundedPcmQueue(44_100, 3, 4_096);
        queue.offer(new Sound.SoundSampleEvent(samples(43), ClockSpec.SGB2), 100, false);
        long phase = queue.samplePhase();

        queue.clear();
        queue.offer(new Sound.SoundSampleEvent(samples(67), ClockSpec.SGB2), 100, false);

        assertTrue(phase != 0);
        assertEquals(1, queue.queuedFrames());
        assertTrue(queue.samplePhase() != 0);
    }

    @Test
    public void releasedFrameAndPcmStorageAreReusedForTheNextEvent() throws Exception {
        BoundedPcmQueue queue = new BoundedPcmQueue(44_100, 2, 4_096);
        Sound.SoundSampleEvent event = new Sound.SoundSampleEvent(samples(2_000), ClockSpec.LEGACY);

        queue.offer(event, 100, false);
        queue.offer(event, 100, false);
        BoundedPcmQueue.Frame first = queue.poll(1, TimeUnit.SECONDS);
        BoundedPcmQueue.Frame second = queue.poll(1, TimeUnit.SECONDS);
        assertNotNull(first);
        assertNotNull(second);
        byte[] firstBytes = first.bytes();
        byte[] secondBytes = second.bytes();
        queue.release(first);
        queue.release(second);

        queue.offer(event, 100, false);
        queue.offer(event, 100, false);
        BoundedPcmQueue.Frame reusedFirst = queue.poll(1, TimeUnit.SECONDS);
        BoundedPcmQueue.Frame reusedSecond = queue.poll(1, TimeUnit.SECONDS);
        assertNotNull(reusedFirst);
        assertNotNull(reusedSecond);
        assertSame(first, reusedFirst);
        assertSame(second, reusedSecond);
        assertSame(firstBytes, reusedFirst.bytes());
        assertSame(secondBytes, reusedSecond.bytes());
        queue.release(reusedFirst);
        queue.release(reusedSecond);
    }

    private static int[] samples(int ticks) {
        int[] result = new int[ticks * 2];
        for (int tick = 0; tick < ticks; tick++) {
            result[tick * 2] = 480;
            result[tick * 2 + 1] = -480;
        }
        return result;
    }
}
