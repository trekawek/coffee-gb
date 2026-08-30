package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.sound.Sound;
import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class AndroidAudioTimingProbeTest {

    private static final ClockSpec THREE_TICKS_PER_SECOND = new ClockSpec(3L, 1L, 1L);
    private static final Sound.SoundSampleEvent ONE_TICK =
            new Sound.SoundSampleEvent(new int[2], THREE_TICKS_PER_SECOND);

    @Test
    public void attributesEveryTimingSegmentAndPublicationPhaseWithOnePooledFrame()
            throws Exception {
        FakeNanoClock clock = new FakeNanoClock();
        AndroidAudioTimingProbe probe = new AndroidAudioTimingProbe();
        long generation = probe.reset(1L, 0L);
        AtomicInteger phase = new AtomicInteger(AndroidAudioSink.WORKER_PHASE_POLL);
        BoundedPcmQueue queue = queue(clock, probe, phase);

        clock.set(2_000_000L, 0L);
        queue.offer(ONE_TICK, 100, false, 0L, 0L, 1_000_000L, generation);
        clock.set(25_000_000L, 2_000_000L);
        BoundedPcmQueue.Frame first = queue.poll(0L, TimeUnit.MILLISECONDS);
        probe.writeStarted(first, 30_000_000L);
        probe.writeCall(first, 30_000_000L, 55_000_000L);
        probe.writeComplete(first, 55_000_000L);
        queue.release(first);

        phase.set(AndroidAudioSink.WORKER_PHASE_WRITE);
        clock.set(60_000_000L, 0L);
        queue.offer(ONE_TICK, 100, false, 0L, 0L, 59_000_000L, generation);
        clock.set(64_000_000L, 1_000_000L);
        BoundedPcmQueue.Frame second = queue.poll(0L, TimeUnit.MILLISECONDS);
        queue.release(second);

        phase.set(AndroidAudioSink.WORKER_PHASE_CONTROL_OR_RECOVERY);
        clock.set(70_000_000L, 0L);
        queue.offer(ONE_TICK, 100, false, 0L, 0L, 69_000_000L, generation);
        clock.set(76_000_000L, 1_000_000L);
        BoundedPcmQueue.Frame third = queue.poll(0L, TimeUnit.MILLISECONDS);
        queue.release(third);

        AndroidAudioTimingProbe.Snapshot snapshot = probe.snapshot();
        assertEquals(1_000L, snapshot.eventToPublishMaxUs());
        assertEquals(23_000L, snapshot.publishToDequeuePollMaxUs());
        assertEquals(4_000L, snapshot.publishToDequeueWriteMaxUs());
        assertEquals(6_000L, snapshot.publishToDequeueOtherMaxUs());
        assertEquals(1L, snapshot.publishToDequeue20Millis());
        assertEquals(2_000L, snapshot.dequeueToReadyMaxUs());
        assertEquals(3_000L, snapshot.readyToFirstWriteMaxUs());
        assertEquals(25_000L, snapshot.writeCallMaxUs());
        assertEquals(1L, snapshot.writeCalls20Millis());
        assertEquals(53_000L, snapshot.publishToWriteCompleteMaxUs());
    }

    @Test
    public void exactRationalCadenceDoesNotDriftAndCatchUpRepaysDebt() {
        FakeNanoClock clock = new FakeNanoClock();
        AndroidAudioTimingProbe probe = new AndroidAudioTimingProbe();
        long generation = probe.reset(1L, 0L);
        AtomicInteger phase = new AtomicInteger();
        BoundedPcmQueue queue = queue(clock, probe, phase);

        for (int packet = 0; packet <= 3_002; packet++) {
            long eventNanos = packet * 1_000_000_000L / 3L;
            publishAndClear(queue, clock, generation, ONE_TICK, eventNanos);
        }
        AndroidAudioTimingProbe.Snapshot exact = probe.snapshot();
        assertEquals(3_002L, exact.producerIntervals());
        assertEquals(1L, exact.sourceCadenceDebtPeakUs());
        assertEquals(0L, probe.currentCadenceDebtUsForTesting());

        long anchor = 3_002L * 1_000_000_000L / 3L;
        publishAndClear(queue, clock, generation, ONE_TICK, anchor + 353_333_334L);
        assertEquals(20_001L, probe.currentCadenceDebtUsForTesting());
        publishAndClear(queue, clock, generation, ONE_TICK, anchor + 686_666_667L);
        assertEquals(20_001L, probe.currentCadenceDebtUsForTesting());
        publishAndClear(queue, clock, generation, ONE_TICK, anchor + 1_000_000_000L);
        assertEquals(0L, probe.currentCadenceDebtUsForTesting());
    }

    @Test
    public void firstPostResetAndProfilePacketsAnchorWhileOldGenerationIsIgnored()
            throws Exception {
        FakeNanoClock clock = new FakeNanoClock();
        AndroidAudioTimingProbe probe = new AndroidAudioTimingProbe();
        long oldGeneration = probe.reset(1L, 0L);
        AtomicInteger phase = new AtomicInteger();
        BoundedPcmQueue queue = queue(clock, probe, phase);

        clock.set(1_000L, 0L);
        queue.offer(ONE_TICK, 100, false, 0L, 0L, 0L, oldGeneration);
        long generation = probe.reset(1L, 0L);
        clock.set(2_000L, 1_000L);
        BoundedPcmQueue.Frame stale = queue.poll(0L, TimeUnit.MILLISECONDS);
        probe.writeStarted(stale, 4_000L);
        probe.writeCall(stale, 4_000L, 5_000L);
        probe.writeComplete(stale, 5_000L);
        queue.release(stale);
        assertEquals(0L, probe.snapshot().eventToPublishMaxUs());

        publishAndClear(queue, clock, generation, ONE_TICK, 10_000L);
        assertEquals(0L, probe.snapshot().producerIntervals());
        publishAndClear(queue, clock, generation, ONE_TICK, 333_343_334L);
        assertEquals(1L, probe.snapshot().producerIntervals());

        Sound.SoundSampleEvent profilePacket =
                new Sound.SoundSampleEvent(new int[2], new ClockSpec(4L, 1L, 1L));
        publishAndClear(queue, clock, generation, profilePacket, 600_000_000L);
        assertEquals(1L, probe.snapshot().producerIntervals());
        publishAndClear(queue, clock, generation, profilePacket, 850_000_000L);
        assertEquals(2L, probe.snapshot().producerIntervals());
    }

    @Test
    public void shortWritesAggregateOnceAndPoolReturnClearsTimingMetadata() throws Exception {
        FakeNanoClock clock = new FakeNanoClock();
        AndroidAudioTimingProbe probe = new AndroidAudioTimingProbe();
        long generation = probe.reset(1L, 0L);
        BoundedPcmQueue queue = queue(clock, probe, new AtomicInteger());

        BoundedPcmQueue.Frame first = publishAndPoll(
                queue, clock, generation, ONE_TICK, 0L, 1_000L, 2_000L);
        probe.writeStarted(first, 3_000L);
        probe.writeCall(first, 3_000L, 4_000L);
        probe.writeStarted(first, 5_000L);
        probe.writeCall(first, 5_000L, 8_000L);
        probe.writeComplete(first, 8_000L);
        probe.writeComplete(first, 9_000L);
        assertEquals(1L, probe.snapshot().readyToFirstWriteMaxUs());
        assertEquals(8L, probe.snapshot().publishToWriteCompleteMaxUs());
        queue.release(first);
        assertEquals(0L, first.timingGeneration());
        assertEquals(-1L, first.firstWriteNanos());

        BoundedPcmQueue.Frame second = publishAndPoll(
                queue, clock, generation, ONE_TICK, 10_000L, 11_000L, 12_000L);
        queue.release(second);
        BoundedPcmQueue.Frame reused = publishAndPoll(
                queue, clock, generation, ONE_TICK, 20_000L, 21_000L, 22_000L);
        assertSame(first, reused);
        assertEquals(generation, reused.timingGeneration());
        assertEquals(-1L, reused.firstWriteNanos());
        queue.reconcileAccountingBytes(reused);
        assertEquals(reused.length(), reused.accountingBytes());
        queue.release(reused);
    }

    @Test
    public void underrunEdgesConserveDeltaAcrossQueueSilenceWriteAndOutputChanges() {
        AndroidAudioTimingProbe probe = new AndroidAudioTimingProbe();
        probe.reset(11L, 0L);
        probe.outputUnderruns(11L, 7L, 1_000_000L, 3);
        AndroidAudioTimingProbe.Snapshot queued = probe.snapshot();
        assertEquals(7L, queued.outputUnderrunDelta());
        assertEquals(7L, queued.underrunQueueNonempty());
        assertEquals(3L, queued.underrunMaxQueuedFrames());

        // A replacement output and a regressing counter establish a new observational baseline.
        probe.outputUnderruns(12L, 9L, 2_000_000L, 0);
        probe.outputUnderruns(12L, 2L, 3_000_000L, 0);
        assertEquals(7L, probe.snapshot().outputUnderrunDelta());

        FakeNanoClock clock = new FakeNanoClock();
        AtomicInteger phase = new AtomicInteger();
        BoundedPcmQueue queue = queue(clock, probe, phase);
        long generation = probe.generation();
        publishAndClear(queue, clock, generation, ONE_TICK, 10_000_000L);
        probe.outputUnderruns(12L, 3L, 35_000_000L, 0);
        assertEquals(1L, probe.snapshot().underrunProducerSilence20Millis());

        BoundedPcmQueue.Frame frame;
        try {
            frame = publishAndPoll(queue, clock, generation, ONE_TICK,
                    50_000_000L, 51_000_000L, 52_000_000L);
        } catch (InterruptedException impossible) {
            throw new AssertionError(impossible);
        }
        probe.writeStarted(frame, 53_000_000L);
        probe.writeCall(frame, 53_000_000L, 74_000_000L);
        publishAndClear(queue, clock, generation, ONE_TICK, 70_000_000L);
        probe.outputUnderruns(12L, 4L, 75_000_000L, 0);
        assertEquals(1L, probe.snapshot().underrunAfterWrite20Millis());
        probe.writeCall(frame, 75_000_000L, 75_500_000L);
        probe.outputUnderruns(12L, 5L, 76_000_000L, 0);
        assertEquals(1L, probe.snapshot().underrunUnclassified());
        queue.release(frame);

        AndroidAudioTimingProbe.Snapshot finalSnapshot = probe.snapshot();
        assertEquals(finalSnapshot.outputUnderrunDelta(),
                finalSnapshot.underrunQueueNonempty()
                        + finalSnapshot.underrunProducerSilence20Millis()
                        + finalSnapshot.underrunAfterWrite20Millis()
                        + finalSnapshot.underrunUnclassified());
        assertEquals(25_000L, finalSnapshot.underrunMaxProducerSilenceUs());
    }

    private static BoundedPcmQueue queue(FakeNanoClock clock, AndroidAudioTimingProbe probe,
            AtomicInteger phase) {
        return new BoundedPcmQueue(3, 2, 64, THREE_TICKS_PER_SECOND, clock, probe, phase);
    }

    private static void publishAndClear(BoundedPcmQueue queue, FakeNanoClock clock,
            long generation, Sound.SoundSampleEvent event, long eventNanos) {
        clock.set(eventNanos, 0L);
        queue.offer(event, 100, false, 0L, 0L, eventNanos, generation);
        queue.clear();
    }

    private static BoundedPcmQueue.Frame publishAndPoll(BoundedPcmQueue queue,
            FakeNanoClock clock, long generation, Sound.SoundSampleEvent event,
            long eventNanos, long dequeueNanos, long readyNanos) throws InterruptedException {
        clock.set(eventNanos, 0L);
        queue.offer(event, 100, false, 0L, 0L, eventNanos, generation);
        clock.set(dequeueNanos, readyNanos - dequeueNanos);
        return queue.poll(0L, TimeUnit.MILLISECONDS);
    }

    private static final class FakeNanoClock implements NanoClock {
        private long now;
        private long step;

        void set(long now, long step) {
            this.now = now;
            this.step = step;
        }

        @Override
        public long nanoTime() {
            long value = now;
            now += step;
            return value;
        }
    }
}
