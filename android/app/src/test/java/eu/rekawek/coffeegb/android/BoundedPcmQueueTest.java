package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.sound.Sound;
import org.junit.Test;

import java.util.Arrays;
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
    public void clearingQueuedHostFramesKeepsFractionalResamplerContinuity() throws Exception {
        BoundedPcmQueue queue = new BoundedPcmQueue(44_100, 3, 4_096);
        queue.offer(new Sound.SoundSampleEvent(samples(43), ClockSpec.SGB2), 100, false);
        assertEquals(0L, queue.samplePhase());
        BoundedPcmQueue.Frame first = queue.poll(1, TimeUnit.SECONDS);
        assertNotNull(first);
        long phase = queue.samplePhase();
        queue.release(first);

        queue.clear();
        queue.offer(new Sound.SoundSampleEvent(samples(67), ClockSpec.SGB2), 100, false);

        assertTrue(phase != 0);
        assertEquals(1, queue.queuedFrames());
        BoundedPcmQueue.Frame second = queue.poll(1, TimeUnit.SECONDS);
        assertNotNull(second);
        assertTrue(queue.samplePhase() != 0);
        queue.release(second);
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

    @Test
    public void producerCopyIsStableAndConversionWaitsForConsumer() throws Exception {
        int[] source = samples(2_000);
        BoundedPcmQueue expectedQueue = new BoundedPcmQueue(44_100, 2, 4_096);
        BoundedPcmQueue copiedQueue = new BoundedPcmQueue(44_100, 2, 4_096);
        expectedQueue.offer(new Sound.SoundSampleEvent(source.clone(), ClockSpec.LEGACY), 100, false);
        copiedQueue.offer(new Sound.SoundSampleEvent(source, ClockSpec.LEGACY), 100, false);

        assertEquals(0L, copiedQueue.samplePhase());
        Arrays.fill(source, 0);

        BoundedPcmQueue.Frame expected = expectedQueue.poll(1, TimeUnit.SECONDS);
        BoundedPcmQueue.Frame copied = copiedQueue.poll(1, TimeUnit.SECONDS);
        assertNotNull(expected);
        assertNotNull(copied);
        assertEquals(expected.length(), copied.length());
        assertTrue(Arrays.equals(expected.bytes(), copied.bytes()));
        assertTrue(copiedQueue.samplePhase() != 0L);
        expectedQueue.release(expected);
        copiedQueue.release(copied);
    }

    @Test
    public void sourceStorageTracksHardwareProfileFrameBudget() {
        BoundedPcmQueue legacy = new BoundedPcmQueue(44_100, ClockSpec.LEGACY);
        BoundedPcmQueue sgb2 = new BoundedPcmQueue(44_100, ClockSpec.SGB2);

        assertEquals(ClockSpec.LEGACY.controllerTicksPerFrame() * 2,
                legacy.maximumSourceSamples());
        assertEquals(ClockSpec.SGB2.controllerTicksPerFrame() * 2,
                sgb2.maximumSourceSamples());
        assertTrue(sgb2.maximumSourceSamples() > legacy.maximumSourceSamples());
    }

    @Test
    public void benchmarkQueuedRawFramesExposeHostBytesBeforeConsumerConversion() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        BoundedPcmQueue queue = new BoundedPcmQueue(44_100, 2, 4_096);
        Sound.SoundSampleEvent event = new Sound.SoundSampleEvent(samples(2_000), ClockSpec.LEGACY);

        int estimatedBytes = queue.offer(event, 100, false);

        assertTrue(estimatedBytes > 0);
        assertEquals(estimatedBytes, queue.queuedBytes());
        assertEquals(0L, queue.drainDiscardedBytes());
    }

    @Test
    public void benchmarkDiscardLedgerCountsRawReplacementAndClearExactlyOnce() {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        BoundedPcmQueue queue = new BoundedPcmQueue(44_100, 2, 4_096);
        Sound.SoundSampleEvent event = new Sound.SoundSampleEvent(samples(2_000), ClockSpec.LEGACY);

        int frameBytes = queue.offer(event, 100, false);
        queue.offer(event, 100, false);
        queue.offer(event, 100, false);
        assertEquals(frameBytes, queue.drainDiscardedBytes());
        assertEquals(0L, queue.drainDiscardedBytes());

        queue.clear();
        long clearedBytes = queue.drainDiscardedBytes();
        assertEquals(frameBytes * 2L, clearedBytes);
        queue.clear();
        assertEquals(0L, queue.drainDiscardedBytes());
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
