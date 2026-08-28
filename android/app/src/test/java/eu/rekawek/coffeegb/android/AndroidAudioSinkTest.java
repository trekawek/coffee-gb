package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.sound.Sound;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AndroidAudioSinkTest {

    private static final int SAMPLE_RATE = 44_100;

    @Test
    public void primesFourGenuineOrderedPacketsBeforePlayWithMoreThanFiftyMilliseconds()
            throws Exception {
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeFactory factory = new FakeFactory();
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        sink.start();
        try {
            await("audio output", () -> sink.stats().sampleRate() == SAMPLE_RATE);
            FakeOutput output = factory.current();
            Thread owner = sink.workerThreadForTesting();
            assertNotNull(owner);
            assertTrue(owner.getName().contains("android-audio"));

            List<Sound.SoundSampleEvent> packets = packets(ClockSpec.LEGACY, 4, 120);
            byte[] expected = render(packets);
            byte[] firstThree = render(packets.subList(0, 3));
            post(events, packets.subList(0, 3));
            await("three stopped writes", () -> output.acceptedLength() == firstThree.length);
            assertEquals(0, output.playCalls);

            events.post(packets.get(3));
            await("primed playback", () -> output.playCalls == 1);
            assertArrayEquals(expected, output.acceptedBytes());
            assertEquals(expected.length, output.bytesAtFirstPlay);
            assertTrue(output.writeThreads.stream().allMatch(owner.getName()::equals));

            double primerMillis = expected.length / 4.0 / SAMPLE_RATE * 1_000.0;
            assertTrue("primer must absorb more than 50 ms ticker debt: " + primerMillis,
                    primerMillis > 50.0);
            assertTrue("four frame packets should remain below 70 ms: " + primerMillis,
                    primerMillis < 70.0);
        } finally {
            sink.close();
        }
        assertTrue(factory.current().released);
        assertFalse(sink.stats().active());
        assertFalse(sink.workerThreadForTesting().isAlive());
    }

    @Test
    public void pauseResumeAndLegitimateReopenEachRequireANewPrimer() throws Exception {
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeFactory factory = new FakeFactory();
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        sink.start();
        try {
            await("audio output", () -> sink.stats().sampleRate() == SAMPLE_RATE);
            FakeOutput first = factory.current();
            List<Sound.SoundSampleEvent> initial = packets(ClockSpec.LEGACY, 4, 100);
            post(events, initial);
            await("initial play", () -> first.playCalls == 1);

            sink.pause();
            await("pause flush", () -> first.flushCalls > 0 && !first.playing);
            events.post(packet(ClockSpec.LEGACY, 900));
            Thread.sleep(40L);
            int playsBeforeResume = first.playCalls;

            sink.resume();
            List<Sound.SoundSampleEvent> resumed = packets(ClockSpec.LEGACY, 4, 200);
            byte[] resumedFirstThree = renderFollowing(initial, resumed.subList(0, 3));
            int resumeOffset = first.acceptedLength();
            post(events, resumed.subList(0, 3));
            await("three resume writes",
                    () -> first.acceptedLength() == resumeOffset + resumedFirstThree.length);
            assertEquals(playsBeforeResume, first.playCalls);
            events.post(resumed.get(3));
            await("resume play", () -> first.playCalls == playsBeforeResume + 1);

            sink.requestRouteReopen();
            await("route reopen", () -> factory.opens.get() == 2 && first.released);
            FakeOutput second = factory.current();
            List<Sound.SoundSampleEvent> reopened = packets(ClockSpec.LEGACY, 4, 500);
            List<Sound.SoundSampleEvent> beforeReopen = new ArrayList<>(initial);
            beforeReopen.addAll(resumed);
            byte[] reopenedFirstThree = renderFollowing(
                    beforeReopen, reopened.subList(0, 3));
            post(events, reopened.subList(0, 3));
            await("three reopen writes",
                    () -> second.acceptedLength() == reopenedFirstThree.length);
            assertEquals(0, second.playCalls);
            events.post(reopened.get(3));
            await("reopened play", () -> second.playCalls == 1);
            assertArrayEquals(renderFollowing(beforeReopen, reopened), second.acceptedBytes());
            assertEquals(1L, sink.stats().restarts());
        } finally {
            sink.close();
        }
    }

    @Test
    public void clockSpecChangeDoesNotReopenAndImmediateSgbPacketIsAccepted() throws Exception {
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeFactory factory = new FakeFactory();
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        sink.start();
        try {
            await("audio output", () -> sink.stats().sampleRate() == SAMPLE_RATE);
            FakeOutput output = factory.current();

            sink.setClockSpec(ClockSpec.SGB);
            Sound.SoundSampleEvent firstSgbPacket = packet(ClockSpec.SGB, 240);
            events.post(firstSgbPacket);
            int firstPacketBytes = render(List.of(firstSgbPacket)).length;
            await("first SGB PCM packet", () -> output.acceptedLength() == firstPacketBytes);

            assertEquals(1, factory.opens.get());
            assertEquals(0L, sink.stats().restarts());
            assertEquals(maximumSourceSamples(), sink.sourceSamplesForTesting());
            assertEquals(0, output.playCalls);
            post(events, packets(ClockSpec.SGB, 3, 300));
            await("SGB primer", () -> output.playCalls == 1);
        } finally {
            sink.close();
        }
    }

    @Test
    public void producerDuringRateChangingSwapOffersOnlyToReplacementQueue() throws Exception {
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeFactory factory = new FakeFactory();
        CountDownLatch swapEntered = new CountDownLatch(1);
        CountDownLatch allowSwap = new CountDownLatch(1);
        AndroidAudioSink sink = new AndroidAudioSink(events, factory, () -> {
            swapEntered.countDown();
            try {
                allowSwap.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        sink.start();
        try {
            await("initial audio output", () -> sink.stats().sampleRate() == SAMPLE_RATE);
            post(events, packets(ClockSpec.LEGACY, 4, 100));
            await("initial playback", () -> factory.current().playCalls == 1);

            int replacementRate = 48_000;
            factory.nextSampleRate = replacementRate;
            sink.requestRouteReopen();
            assertTrue(swapEntered.await(2, TimeUnit.SECONDS));

            List<Sound.SoundSampleEvent> replacement = packets(ClockSpec.SGB, 4, 500);
            Thread producer = new Thread(() -> post(events, replacement), "rate-swap-producer");
            producer.start();
            Thread.sleep(40L);
            assertTrue("producer must wait for the linearized queue handoff", producer.isAlive());

            allowSwap.countDown();
            producer.join(2_000L);
            assertFalse(producer.isAlive());
            await("replacement rate", () -> sink.stats().sampleRate() == replacementRate);
            FakeOutput second = factory.current();
            await("replacement primer", () -> second.playCalls == 1);
            assertEquals(replacementRate, second.sampleRate());
            assertArrayEquals(renderAtRate(replacementRate, List.of(), replacement, 100, false),
                    second.acceptedBytes());
        } finally {
            allowSwap.countDown();
            sink.close();
        }
    }

    @Test
    public void shortStoppedWritesRemainExactAndNeverExceedReportedCapacity() throws Exception {
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeFactory factory = new FakeFactory();
        factory.writeLimit = 36;
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        sink.start();
        try {
            await("audio output", () -> sink.stats().sampleRate() == SAMPLE_RATE);
            List<Sound.SoundSampleEvent> packets = packets(ClockSpec.SGB2, 4, 140);
            byte[] expected = render(packets);
            post(events, packets);

            FakeOutput output = factory.current();
            await("short-write primer", () -> output.playCalls == 1);
            assertArrayEquals(expected, output.acceptedBytes());
            assertTrue(output.writeCalls > AndroidAudioSink.PRIMER_PACKETS);
            assertTrue(output.writeSizes.stream().allMatch(size -> size % 4 == 0));
            assertEquals(expected.length, output.bytesAtFirstPlay);
            assertTrue(output.bytesAtFirstPlay <= output.audioStats().actualBufferBytes());
        } finally {
            sink.close();
        }
    }

    @Test
    public void prePrimeMuteAndVolumeChangesFlushStaleHeldPcmAndReprimeExactly()
            throws Exception {
        assertPrePrimePolicyReset(true);
        assertPrePrimePolicyReset(false);
    }

    @Test
    public void newPolicyProducerCannotOvertakeItsBoundaryClear() throws Exception {
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeFactory factory = new FakeFactory();
        CountDownLatch boundaryPublished = new CountDownLatch(1);
        CountDownLatch allowBoundaryClear = new CountDownLatch(1);
        AndroidAudioSink sink = new AndroidAudioSink(events, factory, null, () -> {
            boundaryPublished.countDown();
            try {
                allowBoundaryClear.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        sink.start();
        try {
            await("audio output", () -> sink.stats().sampleRate() == SAMPLE_RATE);
            List<Sound.SoundSampleEvent> initial = packets(ClockSpec.LEGACY, 4, 100);
            post(events, initial);
            FakeOutput output = factory.current();
            await("initial play", () -> output.playCalls == 1);
            int initialBytes = output.acceptedLength();

            Thread policy = new Thread(() -> sink.setVolume(50), "policy-boundary");
            policy.start();
            assertTrue(boundaryPublished.await(2, TimeUnit.SECONDS));

            List<Sound.SoundSampleEvent> fresh = packets(ClockSpec.LEGACY, 4, 700);
            Thread producer = new Thread(() -> post(events, fresh), "new-policy-producer");
            producer.start();
            Thread.sleep(40L);
            assertTrue("new-policy producer must wait until the old queue is cleared",
                    producer.isAlive());
            assertEquals(initialBytes, output.acceptedLength());

            allowBoundaryClear.countDown();
            policy.join(2_000L);
            producer.join(2_000L);
            assertFalse(policy.isAlive());
            assertFalse(producer.isAlive());
            await("new-policy primer", () -> output.playCalls == 2);
            byte[] expected = renderFollowing(initial, fresh, 50, false);
            assertArrayEquals(expected, Arrays.copyOfRange(output.acceptedBytes(),
                    output.lastFlushAcceptedOffset, output.acceptedLength()));
        } finally {
            allowBoundaryClear.countDown();
            sink.close();
        }
    }

    @Test
    public void partialWriteInterruptedByReopenReplaysFromZeroAfterOldTrackRelease()
            throws Exception {
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeFactory factory = new FakeFactory();
        factory.blockFirstWrite = true;
        factory.writeLimit = 36;
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        sink.start();
        try {
            await("audio output", () -> sink.stats().sampleRate() == SAMPLE_RATE);
            List<Sound.SoundSampleEvent> packets = packets(ClockSpec.LEGACY, 4, 180);
            byte[] expected = render(packets);
            post(events, packets);

            FakeOutput first = factory.current();
            assertTrue(first.firstWriteEntered.await(2, TimeUnit.SECONDS));
            sink.requestRouteReopen();
            first.continueFirstWrite.countDown();

            await("replacement output", () -> factory.opens.get() == 2 && first.released);
            FakeOutput second = factory.current();
            await("replayed primer", () -> second.playCalls == 1);
            assertEquals(36, first.acceptedLength());
            assertArrayEquals(Arrays.copyOf(expected, 36), first.acceptedBytes());
            assertTrue(first.flushCalls > 0);
            assertArrayEquals(expected, second.acceptedBytes());
            assertTrue(factory.eventIndex("release-1")
                    < factory.eventIndex("write-2"));
        } finally {
            factory.releaseBlockedWrites();
            sink.close();
        }
    }

    @Test
    public void rapidPauseResumeDuringPartialWriteStillFlushesAndReprimes() throws Exception {
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeFactory factory = new FakeFactory();
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        sink.start();
        try {
            await("audio output", () -> sink.stats().sampleRate() == SAMPLE_RATE);
            List<Sound.SoundSampleEvent> initial = packets(ClockSpec.LEGACY, 4, 100);
            post(events, initial);
            FakeOutput output = factory.current();
            await("initial play", () -> output.playCalls == 1);

            output.writeLimit = 36;
            output.armBlockedWrite();
            Sound.SoundSampleEvent interrupted = packet(ClockSpec.LEGACY, 400);
            events.post(interrupted);
            assertTrue(output.blockedWriteEntered.await(2, TimeUnit.SECONDS));

            int flushesBefore = output.flushCalls;
            sink.pause();
            sink.resume();
            output.continueBlockedWrite.countDown();
            await("sticky rapid-pause flush", () -> output.flushCalls > flushesBefore);
            assertEquals(1, output.playCalls);

            List<Sound.SoundSampleEvent> fresh = packets(ClockSpec.LEGACY, 4, 700);
            int flushOffset = output.lastFlushAcceptedOffset;
            post(events, fresh.subList(0, 3));
            int firstThreeBytes = renderFollowing(
                    concat(initial, List.of(interrupted)), fresh.subList(0, 3)).length;
            await("three post-pause packets", () -> output.acceptedLength()
                    == flushOffset + firstThreeBytes);
            assertEquals(1, output.playCalls);
            events.post(fresh.get(3));
            await("post-pause primer", () -> output.playCalls == 2);
            byte[] expectedFresh = renderFollowing(
                    concat(initial, List.of(interrupted)), fresh);
            assertArrayEquals(expectedFresh, Arrays.copyOfRange(output.acceptedBytes(),
                    flushOffset, output.acceptedLength()));
        } finally {
            factory.releaseBlockedWrites();
            sink.close();
        }
    }

    @Test
    public void closeLinearizesAfterAnInFlightPlayAndThenStopsTheWorker() throws Exception {
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeFactory factory = new FakeFactory();
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        sink.start();
        Thread closer = null;
        try {
            await("audio output", () -> sink.stats().sampleRate() == SAMPLE_RATE);
            FakeOutput output = factory.current();
            List<Sound.SoundSampleEvent> primer = packets(ClockSpec.LEGACY, 4, 200);
            post(events, primer.subList(0, 3));
            int firstThreeBytes = render(primer.subList(0, 3)).length;
            await("three stopped packets", () -> output.acceptedLength() == firstThreeBytes);
            output.blockNextPlay = true;
            events.post(primer.get(3));
            assertTrue(output.blockedPlayEntered.await(2, TimeUnit.SECONDS));

            closer = new Thread(sink::close, "audio-close");
            closer.start();
            Thread.sleep(40L);
            assertTrue("close must wait for the already-linearized play call", closer.isAlive());
            output.continueBlockedPlay.countDown();
            closer.join(2_000L);
            assertFalse(closer.isAlive());
            assertEquals(1, output.playCalls);
            assertTrue(output.released);
            assertFalse(sink.workerThreadForTesting().isAlive());
        } finally {
            factory.releaseBlockedWrites();
            if (closer != null && closer.isAlive()) {
                closer.join(2_000L);
            }
            sink.close();
        }
    }

    @Test
    public void reportedStartThresholdBeyondFourPacketsDelaysPlayAndPreservesPartialSuffix()
            throws Exception {
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeFactory factory = new FakeFactory();
        List<Sound.SoundSampleEvent> packets = packets(ClockSpec.LEGACY, 5, 100);
        int fourPacketBytes = render(packets.subList(0, 4)).length;
        byte[] expected = render(packets);
        int thresholdFrames = (fourPacketBytes + (expected.length - fourPacketBytes) / 2) / 4;
        factory.nextEffectiveBufferFrames = thresholdFrames;
        factory.nextStartThresholdFrames = thresholdFrames;
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        sink.start();
        try {
            await("audio output", () -> sink.stats().sampleRate() == SAMPLE_RATE);
            FakeOutput output = factory.current();
            post(events, packets.subList(0, 4));
            await("four complete stopped packets",
                    () -> output.acceptedLength() == fourPacketBytes);
            assertEquals("packet count alone must not bypass the device threshold",
                    0, output.playCalls);

            events.post(packets.get(4));
            await("reported threshold play", () -> output.playCalls == 1);
            assertEquals(thresholdFrames * 4, output.bytesAtFirstPlay);
            assertEquals("threshold-aware primer must not start in underrun", 0L,
                    output.outputUnderrunCount);
            await("ordered threshold packet suffix", () -> output.acceptedLength() == expected.length);
            assertArrayEquals(expected, output.acceptedBytes());
        } finally {
            sink.close();
        }
    }

    @Test
    public void advancingHeadCannotEndRecoveryBeforeLatestRefillThresholdIsRebuilt() {
        AndroidAudioSink.UnderrunRecovery recovery = new AndroidAudioSink.UnderrunRecovery();
        assertTrue(recovery.rebase(100L, 1_000L, 100L, 400));

        recovery.observeRefillProgress(1_299L, 399L, 400);
        assertFalse("head progress alone must not prove the latest refill",
                recovery.hasRestarted(110L));
        recovery.observeRefillProgress(1_300L, 399L, 400);
        assertFalse("accepted refill debt does not replace the buffered-runway proof",
                recovery.hasRestarted(110L));
        recovery.observeRefillProgress(1_301L, 400L, 400);
        assertTrue(recovery.hasRestarted(110L));

        assertTrue(recovery.rebase(110L, 1_300L, 200L, 400));
        recovery.observeRefillProgress(1_499L, 399L, 400);
        assertFalse("a newer underrun must revoke the preceding refill proof",
                recovery.hasRestarted(111L));
        recovery.observeRefillProgress(1_500L, 400L, 400);
        assertTrue(recovery.hasRestarted(111L));
    }

    @Test
    public void actualUnderrunRefillsThresholdInPlaceWithoutFlushDropOrDuplicate()
            throws Exception {
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeFactory factory = new FakeFactory();
        List<Sound.SoundSampleEvent> initial = packets(ClockSpec.LEGACY, 4, 100);
        Sound.SoundSampleEvent interrupted = packet(ClockSpec.LEGACY, 500);
        Sound.SoundSampleEvent refill = packet(ClockSpec.LEGACY, 700);
        Sound.SoundSampleEvent sentinel = packet(ClockSpec.LEGACY, 900);
        int initialBytes = render(initial).length;
        int interruptedBytes = renderFollowing(initial, List.of(interrupted)).length;
        int refillThresholdFrames = renderFollowing(initial,
                List.of(interrupted, refill)).length / 4;
        factory.nextEffectiveBufferFrames = AndroidAudioTrackOutput.Factory
                .packetBufferBytes(SAMPLE_RATE) / 4;
        factory.nextStartThresholdFrames = refillThresholdFrames;
        factory.nextInitialPlaybackHeadFrames = 0L;
        factory.nextUnavailablePlaybackHeadReads = 1;
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        sink.start();
        try {
            await("audio output", () -> sink.stats().sampleRate() == SAMPLE_RATE);
            post(events, initial);
            FakeOutput output = factory.current();
            await("initial play", () -> output.playCalls == 1);

            output.writeLimit = 36;
            output.armThresholdUnderrun(false, initialBytes / 4);
            events.post(interrupted);
            await("complete first refill packet", () -> output.acceptedLength()
                    == initialBytes + interruptedBytes);
            assertEquals("the post-detection head advance is the refill baseline",
                    1L, output.thresholdRestarted.getCount());
            assertEquals(0, output.flushCalls);

            // A second cumulative rise while refilling must coalesce into the same recovery.
            output.outputUnderrunCount++;
            events.post(refill);
            assertTrue(output.thresholdRestarted.await(2, TimeUnit.SECONDS));
            output.writeLimit = 0;
            events.post(sentinel);

            List<Sound.SoundSampleEvent> all = concat(initial,
                    List.of(interrupted, refill, sentinel));
            byte[] expected = render(all);
            await("post-restart sentinel", () -> output.acceptedLength() == expected.length);
            assertArrayEquals(expected, output.acceptedBytes());
            assertEquals("ordinary threshold refill does not call play again", 1, output.playCalls);
            assertEquals("ordinary threshold refill does not pause", 0, output.pauseCalls);
            assertEquals("ordinary threshold refill does not flush", 0, output.flushCalls);
            assertEquals("ordinary threshold refill keeps the same output", 1,
                    factory.opens.get());
        } finally {
            sink.close();
        }
    }

    @Test
    public void repeatedUnderrunDuringRetainedShortWriteRebasesAndFallsBackBoundedly()
            throws Exception {
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeFactory factory = new FakeFactory();
        List<Sound.SoundSampleEvent> initial = packets(ClockSpec.LEGACY, 4, 100);
        Sound.SoundSampleEvent interrupted = packet(ClockSpec.LEGACY, 500);
        int initialBytes = render(initial).length;
        int effectiveFrames = BoundedPcmQueue.maximumOutputFramesForPackets(
                SAMPLE_RATE, AndroidAudioSink.PRIMER_PACKETS + 1);
        factory.nextEffectiveBufferFrames = effectiveFrames;
        factory.nextStartThresholdFrames = BoundedPcmQueue.minimumOutputFramesForPackets(
                SAMPLE_RATE, AndroidAudioSink.PRIMER_PACKETS);
        factory.nextInitialPlaybackHeadFrames = 0L;
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        sink.start();
        try {
            await("audio output", () -> sink.stats().sampleRate() == SAMPLE_RATE);
            post(events, initial);
            FakeOutput first = factory.current();
            await("initial play", () -> first.playCalls == 1);

            first.writeLimit = 36;
            first.blockWriteAfterThresholdUnderrun = true;
            first.armThresholdUnderrun(true, initialBytes / 4);
            events.post(interrupted);
            assertTrue("the retained suffix write must block deterministically",
                    first.blockedWriteEntered.await(2, TimeUnit.SECONDS));
            assertEquals(1L, first.outputUnderrunCount);
            long firstUnderrunHead = first.recoveryHeadFrames;

            first.manualPlaybackHeadFrames = firstUnderrunHead + 1L;
            first.armThresholdUnderrun(true, 0);
            // One of the six fixed queue slots is the retained interrupted packet.
            List<Sound.SoundSampleEvent> stalled = packets(ClockSpec.LEGACY, 5, 700);
            post(events, stalled);
            first.continueBlockedWrite.countDown();
            await("second underrun during retained suffix",
                    () -> first.outputUnderrunCount == 2L);
            assertEquals(firstUnderrunHead + 1L, first.recoveryHeadFrames);

            Thread.sleep(30L);
            assertEquals("the latest underrun receives a fresh stall grace", 1,
                    factory.opens.get());
            await("bounded repeated-underrun reopen", () -> factory.opens.get() == 2);
            assertTrue(first.released);
            long unplayedFrames = first.acceptedLength() / 4L - first.recoveryHeadFrames;
            assertTrue("recovery writes must remain within effective capacity: "
                    + unplayedFrames, unplayedFrames <= effectiveFrames);

            List<Sound.SoundSampleEvent> ordered = concat(initial,
                    concat(List.of(interrupted), stalled));
            byte[] expected = render(ordered);
            byte[] accepted = first.acceptedBytes();
            assertArrayEquals("the replaced output must contain one exact ordered prefix",
                    Arrays.copyOf(expected, accepted.length), accepted);
        } finally {
            factory.releaseBlockedWrites();
            sink.close();
        }
    }

    @Test
    public void capacityFullUnderrunWithNoHeadProgressFallsBackToReopenAndPrimer()
            throws Exception {
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeFactory factory = new FakeFactory();
        List<Sound.SoundSampleEvent> initial = packets(ClockSpec.LEGACY, 4, 100);
        int initialBytes = render(initial).length;
        int effectiveFrames = BoundedPcmQueue.maximumOutputFramesForPackets(
                SAMPLE_RATE, AndroidAudioSink.PRIMER_PACKETS + 1);
        factory.nextEffectiveBufferFrames = effectiveFrames;
        factory.nextStartThresholdFrames = BoundedPcmQueue.minimumOutputFramesForPackets(
                SAMPLE_RATE, AndroidAudioSink.PRIMER_PACKETS);
        factory.nextInitialPlaybackHeadFrames = 0L;
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        sink.start();
        try {
            await("audio output", () -> sink.stats().sampleRate() == SAMPLE_RATE);
            post(events, initial);
            FakeOutput first = factory.current();
            await("initial play", () -> first.playCalls == 1);

            first.armThresholdUnderrun(true, initialBytes / 4);
            List<Sound.SoundSampleEvent> stalled = packets(ClockSpec.LEGACY, 6, 500);
            post(events, stalled);
            await("bounded stalled-output reopen", () -> factory.opens.get() == 2);
            assertTrue(first.released);
            long unplayedFrames = first.acceptedLength() / 4L - first.recoveryHeadFrames;
            assertTrue("writes must stop at effective capacity: " + unplayedFrames,
                    unplayedFrames <= effectiveFrames);

            FakeOutput replacement = factory.current();
            List<Sound.SoundSampleEvent> tail = packets(ClockSpec.LEGACY, 6, 900);
            post(events, tail);
            await("replacement genuine primer", () -> replacement.playCalls == 1);
            assertTrue(replacement.bytesAtFirstPlay / 4
                    >= replacement.startThresholdFrames());
            assertTrue(replacement.bytesAtFirstPlay > 0);
        } finally {
            sink.close();
        }
    }

    @Test
    public void playbackHeadExtensionHandlesUnsignedWrapAndRejectsRegression() {
        long beforeWrap = 0xfffffff0L;
        assertEquals(beforeWrap,
                AndroidAudioSink.OutputProgress.extendUnsignedPlaybackPosition(-1L, beforeWrap));
        assertEquals(0x1_00000010L,
                AndroidAudioSink.OutputProgress.extendUnsignedPlaybackPosition(beforeWrap, 0x10L));
        assertEquals(-1L,
                AndroidAudioSink.OutputProgress.extendUnsignedPlaybackPosition(100L, 99L));
    }

    @Test
    public void transientWriteAndPlayExceptionsReopenAndReplayExactPrimer() throws Exception {
        assertPrimerFailureRecovers(FailurePoint.WRITE);
        assertPrimerFailureRecovers(FailurePoint.PLAY);
    }

    @Test
    public void transientPauseAndFlushExceptionsReopenAndResumeThroughPrimer() throws Exception {
        assertPauseFailureRecovers(FailurePoint.PAUSE);
        assertPauseFailureRecovers(FailurePoint.FLUSH);
    }

    @Test
    public void emptyPlayBypassIsRejectedForOrdinaryPlayback() throws Exception {
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeFactory factory = new FakeFactory();
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        sink.start();
        try {
            await("audio output", () -> sink.stats().sampleRate() == SAMPLE_RATE);
            try {
                sink.resumeEmptyForBenchmarkPreArm();
                fail("ordinary playback must not authorize empty AudioTrack play");
            } catch (IllegalStateException expected) {
                // Expected production gate.
            }
            sink.resume();
            Thread.sleep(40L);
            assertEquals(0, factory.current().playCalls);
            assertEquals(0, factory.current().acceptedLength());
        } finally {
            sink.close();
        }
    }

    @Test
    public void benchmarkOnlyPreArmBypassCanPlayEmptyWhileGuestRemainsPaused() throws Exception {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeFactory factory = new FakeFactory();
        DiagnosticsOptions options = DiagnosticsOptions.parseValues(
                true, "dmg", true, "presentation", true, true, false);
        AndroidBenchmarkDiagnostics diagnostics = new AndroidBenchmarkDiagnostics(
                null, options, message -> { });
        AndroidAudioSink sink = new AndroidAudioSink(events, factory, diagnostics);
        sink.start();
        try {
            await("audio output", () -> sink.stats().sampleRate() == SAMPLE_RATE);
            sink.pause();
            await("benchmark stopped output", () -> factory.current().flushCalls > 0);
            sink.resumeEmptyForBenchmarkPreArm();
            FakeOutput output = factory.current();
            await("benchmark empty play", () -> output.playCalls == 1);
            assertEquals(0, output.acceptedLength());

            int flushesAfterEmptyPlay = output.flushCalls;
            output.outputUnderrunCount = 1L;
            Thread.sleep(60L);
            assertEquals("intentional empty-play underruns must not start recovery",
                    flushesAfterEmptyPlay, output.flushCalls);
            assertEquals(1, output.playCalls);

            List<Sound.SoundSampleEvent> packets = packets(ClockSpec.LEGACY, 4, 300);
            byte[] firstThree = render(packets.subList(0, 3));
            post(events, packets.subList(0, 3));
            await("empty-play exit flush and three genuine packets", () -> output.flushCalls
                    > flushesAfterEmptyPlay && output.acceptedLength() == firstThree.length);
            assertEquals("N-1 packets must not restart playback", 1, output.playCalls);
            events.post(packets.get(3));
            await("production primer after benchmark bypass", () -> output.playCalls == 2);
            assertArrayEquals(render(packets), output.acceptedBytes());
        } finally {
            sink.close();
        }
    }

    @Test
    public void benchmarkBaselineAndConcurrentSnapshotsRemainCoherentAcrossShortWrites()
            throws Exception {
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            return;
        }
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeFactory factory = new FakeFactory();
        factory.writeLimit = 36;
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        sink.start();
        try {
            await("audio output", () -> sink.stats().sampleRate() == SAMPLE_RATE);
            List<Sound.SoundSampleEvent> initial = packets(ClockSpec.LEGACY, 4, 100);
            post(events, initial);
            await("primed output", () -> factory.current().playCalls == 1);
            AndroidAudioSink.AudioBaseline baseline = sink.benchmarkBaseline();
            assertTrue(baseline.outputOpen());
            assertTrue(baseline.outputPlaying());
            assertTrue(baseline.outputIdentity() > 0L);
            assertTrue(baseline.queueIdentity() > 0L);
            int flushesBefore = factory.current().flushCalls;

            AtomicInteger snapshots = new AtomicInteger();
            Thread sampler = new Thread(() -> {
                while (snapshots.get() < 100) {
                    AndroidAudioSink.Stats stats = sink.stats();
                    assertTrue(stats.pcmPendingBytes() >= 0L);
                    snapshots.incrementAndGet();
                }
            });
            sampler.start();
            events.post(packet(ClockSpec.LEGACY, 700));
            await("short PCM writes", () -> sink.stats().pcmWrittenBytes() > baseline.writtenBytes());
            sampler.join(2_000L);
            assertEquals(100, snapshots.get());
            AndroidAudioSink.Stats stats = sink.stats();
            assertEquals(0L, stats.writeFailures());
            assertEquals(stats.pcmEnqueuedBytes(), stats.pcmWrittenBytes()
                    + stats.pcmPendingBytes() + stats.pcmDiscardedBytes());
            assertEquals(flushesBefore, factory.current().flushCalls);
        } finally {
            sink.close();
        }
    }

    private static void assertPrimerFailureRecovers(FailurePoint failure) throws Exception {
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeFactory factory = new FakeFactory();
        factory.firstOutputFailure = failure;
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        sink.start();
        try {
            await("audio output", () -> sink.stats().sampleRate() == SAMPLE_RATE);
            List<Sound.SoundSampleEvent> packets = packets(ClockSpec.LEGACY, 4, 260);
            byte[] expected = render(packets);
            post(events, packets);

            await(failure + " recovery", () -> factory.opens.get() == 2
                    && factory.current().playCalls == 1);
            assertTrue(factory.outputs.get(0).released);
            assertArrayEquals(expected, factory.current().acceptedBytes());
            assertTrue(sink.workerThreadForTesting().isAlive());
        } finally {
            sink.close();
        }
    }

    private static void assertPrePrimePolicyReset(boolean muteChange) throws Exception {
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeFactory factory = new FakeFactory();
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        sink.start();
        try {
            await("audio output", () -> sink.stats().sampleRate() == SAMPLE_RATE);
            FakeOutput output = factory.current();
            List<Sound.SoundSampleEvent> stale = packets(ClockSpec.LEGACY, 2, 120);
            post(events, stale);
            int staleBytes = render(stale).length;
            await("held stale primer", () -> output.acceptedLength() == staleBytes);
            assertEquals(0, output.playCalls);

            int volume = muteChange ? 100 : 37;
            boolean muted = muteChange;
            if (muteChange) {
                sink.setMuted(true);
            } else {
                sink.setVolume(volume);
            }
            List<Sound.SoundSampleEvent> fresh = packets(ClockSpec.LEGACY, 4, 500);
            byte[] expected = renderFollowing(stale, fresh, volume, muted);
            // Post immediately: the first new-policy packet may wake a poll that began before the
            // generation changed. It must be preserved across the worker's reset, not dropped.
            post(events, fresh);
            await("policy flush", () -> output.flushCalls > 0
                    && output.lastFlushAcceptedOffset == staleBytes);
            await("policy re-prime", () -> output.playCalls == 1);
            byte[] accepted = output.acceptedBytes();
            assertArrayEquals(expected,
                    Arrays.copyOfRange(accepted, staleBytes, accepted.length));
            assertEquals(expected.length, output.bytesSinceLastFlushAtFirstPlay);
        } finally {
            sink.close();
        }
    }

    private static void assertPauseFailureRecovers(FailurePoint failure) throws Exception {
        EventBusImpl events = new EventBusImpl(null, null, false);
        FakeFactory factory = new FakeFactory();
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        sink.start();
        try {
            await("audio output", () -> sink.stats().sampleRate() == SAMPLE_RATE);
            List<Sound.SoundSampleEvent> initial = packets(ClockSpec.LEGACY, 4, 100);
            post(events, initial);
            FakeOutput first = factory.current();
            await("initial play", () -> first.playCalls == 1);
            first.armFailure(failure);

            sink.pause();
            await(failure + " reopen", () -> factory.opens.get() == 2 && first.released);
            sink.resume();
            List<Sound.SoundSampleEvent> resumed = packets(ClockSpec.LEGACY, 4, 600);
            post(events, resumed);
            await(failure + " resumed primer", () -> factory.current().playCalls == 1);
            assertArrayEquals(renderFollowing(initial, resumed),
                    factory.current().acceptedBytes());
            assertTrue(sink.workerThreadForTesting().isAlive());
        } finally {
            sink.close();
        }
    }

    private static int maximumSourceSamples() {
        return Math.max(ClockSpec.LEGACY.controllerTicksPerFrame(),
                Math.max(ClockSpec.SGB.controllerTicksPerFrame(),
                        ClockSpec.SGB2.controllerTicksPerFrame())) * 2;
    }

    private static List<Sound.SoundSampleEvent> packets(
            ClockSpec clock, int count, int firstAmplitude) {
        List<Sound.SoundSampleEvent> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            result.add(packet(clock, firstAmplitude + index * 37));
        }
        return result;
    }

    private static Sound.SoundSampleEvent packet(ClockSpec clock, int amplitude) {
        int ticks = clock.controllerTicksPerFrame();
        int[] samples = new int[ticks * 2];
        for (int tick = 0; tick < ticks; tick++) {
            samples[tick * 2] = amplitude;
            samples[tick * 2 + 1] = -amplitude;
        }
        return new Sound.SoundSampleEvent(samples, clock);
    }

    private static byte[] render(List<Sound.SoundSampleEvent> packets) throws Exception {
        return renderFollowing(List.of(), packets);
    }

    private static byte[] renderFollowing(List<Sound.SoundSampleEvent> preceding,
            List<Sound.SoundSampleEvent> packets) throws Exception {
        return renderFollowing(preceding, packets, 100, false);
    }

    private static byte[] renderFollowing(List<Sound.SoundSampleEvent> preceding,
            List<Sound.SoundSampleEvent> packets, int volume, boolean muted) throws Exception {
        return renderAtRate(SAMPLE_RATE, preceding, packets, volume, muted);
    }

    private static byte[] renderAtRate(int sampleRate,
            List<Sound.SoundSampleEvent> preceding, List<Sound.SoundSampleEvent> packets,
            int volume, boolean muted) throws Exception {
        BoundedPcmQueue queue = new BoundedPcmQueue(sampleRate);
        for (Sound.SoundSampleEvent packet : preceding) {
            queue.offer(packet, 100, false);
            BoundedPcmQueue.Frame frame = queue.poll(1, TimeUnit.SECONDS);
            assertNotNull(frame);
            queue.release(frame);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (Sound.SoundSampleEvent packet : packets) {
            queue.offer(packet, volume, muted);
            BoundedPcmQueue.Frame frame = queue.poll(1, TimeUnit.SECONDS);
            assertNotNull(frame);
            bytes.write(frame.bytes(), 0, frame.length());
            queue.release(frame);
        }
        return bytes.toByteArray();
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        List<T> combined = new ArrayList<>(first.size() + second.size());
        combined.addAll(first);
        combined.addAll(second);
        return combined;
    }

    private static void post(EventBusImpl events, List<Sound.SoundSampleEvent> packets) {
        for (Sound.SoundSampleEvent packet : packets) {
            events.post(packet);
        }
    }

    private static void await(String description, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertTrue("Timed out waiting for " + description, condition.getAsBoolean());
    }

    private enum FailurePoint {
        NONE,
        WRITE,
        PLAY,
        PAUSE,
        FLUSH
    }

    private static final class FakeFactory implements AndroidAudioSink.OutputFactory {
        private final AtomicInteger opens = new AtomicInteger();
        private final List<FakeOutput> outputs = new CopyOnWriteArrayList<>();
        private final List<String> lifecycleEvents = new CopyOnWriteArrayList<>();
        private volatile int writeLimit;
        private volatile boolean blockFirstWrite;
        private volatile int nextSampleRate = SAMPLE_RATE;
        private volatile int nextEffectiveBufferFrames;
        private volatile int nextStartThresholdFrames;
        private volatile long nextInitialPlaybackHeadFrames = -1L;
        private volatile int nextUnavailablePlaybackHeadReads;
        private volatile FailurePoint firstOutputFailure = FailurePoint.NONE;

        @Override
        public AndroidAudioSink.Output open() {
            int identity = opens.incrementAndGet();
            FakeOutput output = new FakeOutput(this, identity, nextSampleRate,
                    nextEffectiveBufferFrames, nextStartThresholdFrames,
                    nextInitialPlaybackHeadFrames, nextUnavailablePlaybackHeadReads);
            output.writeLimit = writeLimit;
            output.blockFirstWrite = blockFirstWrite && identity == 1;
            if (identity == 1) {
                output.armFailure(firstOutputFailure);
            }
            outputs.add(output);
            lifecycleEvents.add("open-" + identity);
            return output;
        }

        private FakeOutput current() {
            return outputs.get(outputs.size() - 1);
        }

        private int eventIndex(String event) {
            return lifecycleEvents.indexOf(event);
        }

        private void releaseBlockedWrites() {
            for (FakeOutput output : outputs) {
                output.continueFirstWrite.countDown();
                output.continueBlockedWrite.countDown();
                output.continueBlockedPlay.countDown();
            }
        }
    }

    private static final class FakeOutput implements AndroidAudioSink.Output {
        private final FakeFactory owner;
        private final int identity;
        private final int sampleRate;
        private final ByteArrayOutputStream accepted = new ByteArrayOutputStream();
        private final List<String> writeThreads = new CopyOnWriteArrayList<>();
        private final List<Integer> writeSizes = new CopyOnWriteArrayList<>();
        private final CountDownLatch firstWriteEntered = new CountDownLatch(1);
        private final CountDownLatch continueFirstWrite = new CountDownLatch(1);
        private final CountDownLatch blockedWriteEntered = new CountDownLatch(1);
        private final CountDownLatch continueBlockedWrite = new CountDownLatch(1);
        private final CountDownLatch blockedPlayEntered = new CountDownLatch(1);
        private final CountDownLatch continueBlockedPlay = new CountDownLatch(1);
        private final CountDownLatch thresholdRestarted = new CountDownLatch(1);
        private volatile boolean released;
        private volatile boolean playing;
        private volatile boolean blockFirstWrite;
        private volatile int writeLimit;
        private volatile int writeCalls;
        private volatile int playCalls;
        private volatile int pauseCalls;
        private volatile int flushCalls;
        private volatile int bytesAtFirstPlay = -1;
        private volatile int lastFlushAcceptedOffset;
        private volatile int bytesSinceLastFlushAtFirstPlay = -1;
        private volatile int blockedWriteCall = -1;
        private volatile boolean blockNextPlay;
        private volatile long outputUnderrunCount;
        private volatile long manualPlaybackHeadFrames = -1L;
        private final int bufferCapacityFrames;
        private final int effectiveBufferFrames;
        private final int startThresholdFrames;
        private volatile boolean armThresholdUnderrunOnNextWrite;
        private volatile boolean blockWriteAfterThresholdUnderrun;
        private volatile boolean thresholdRecoveryArmed;
        private volatile boolean thresholdRecoveryStalled;
        private volatile int advanceHeadBeforeUnderrunFrames;
        private volatile long recoveryAcceptedBaseFrames;
        private volatile long recoveryHeadFrames;
        private int unavailablePlaybackHeadReads;
        private volatile FailurePoint armedFailure = FailurePoint.NONE;

        private FakeOutput(FakeFactory owner, int identity, int sampleRate,
                int configuredEffectiveFrames, int configuredStartThresholdFrames,
                long initialPlaybackHeadFrames, int unavailablePlaybackHeadReads) {
            this.owner = owner;
            this.identity = identity;
            this.sampleRate = sampleRate;
            bufferCapacityFrames = AndroidAudioTrackOutput.Factory.packetBufferBytes(sampleRate)
                    / 4;
            effectiveBufferFrames = configuredEffectiveFrames > 0
                    ? configuredEffectiveFrames
                    : BoundedPcmQueue.maximumOutputFramesForPackets(sampleRate,
                            AndroidAudioSink.PRIMER_PACKETS);
            startThresholdFrames = configuredStartThresholdFrames > 0
                    ? configuredStartThresholdFrames
                    : BoundedPcmQueue.minimumOutputFramesForPackets(sampleRate,
                            AndroidAudioSink.PRIMER_PACKETS);
            if (startThresholdFrames > effectiveBufferFrames
                    || effectiveBufferFrames > bufferCapacityFrames) {
                throw new IllegalArgumentException("Invalid fake output buffer limits");
            }
            manualPlaybackHeadFrames = initialPlaybackHeadFrames;
            this.unavailablePlaybackHeadReads = unavailablePlaybackHeadReads;
        }

        private void armBlockedWrite() {
            blockedWriteCall = writeCalls + 1;
        }

        private void armFailure(FailurePoint failure) {
            armedFailure = failure;
        }

        private void armThresholdUnderrun(boolean stalled, int headAdvanceFrames) {
            thresholdRecoveryStalled = stalled;
            advanceHeadBeforeUnderrunFrames = headAdvanceFrames;
            armThresholdUnderrunOnNextWrite = true;
        }

        private boolean takeFailure(FailurePoint point) {
            if (armedFailure != point) {
                return false;
            }
            armedFailure = FailurePoint.NONE;
            return true;
        }

        @Override
        public int sampleRate() {
            return sampleRate;
        }

        @Override
        public AndroidAudioSink.AudioStats audioStats() {
            int capacityBytes = bufferCapacityFrames * 4;
            return new AndroidAudioSink.AudioStats(sampleRate, 1_024,
                    capacityBytes, capacityBytes);
        }

        @Override
        public int bufferCapacityFrames() {
            return bufferCapacityFrames;
        }

        @Override
        public int effectiveBufferFrames() {
            return effectiveBufferFrames;
        }

        @Override
        public int startThresholdFrames() {
            return startThresholdFrames;
        }

        @Override
        public synchronized long playbackPositionFrames() {
            if (unavailablePlaybackHeadReads > 0) {
                unavailablePlaybackHeadReads--;
                return -1L;
            }
            if (thresholdRecoveryArmed && !thresholdRecoveryStalled
                    && accepted.size() / 4L - recoveryAcceptedBaseFrames
                    >= startThresholdFrames) {
                manualPlaybackHeadFrames = recoveryHeadFrames + 1L;
                thresholdRecoveryArmed = false;
                thresholdRestarted.countDown();
            }
            long manual = manualPlaybackHeadFrames;
            return manual >= 0L ? manual : acceptedLength() / 4L;
        }

        @Override
        public long outputUnderrunCount() {
            return outputUnderrunCount;
        }

        @Override
        public boolean isPlaying() {
            return playing;
        }

        @Override
        public synchronized void play() {
            if (takeFailure(FailurePoint.PLAY)) {
                throw new IllegalStateException("transient play failure");
            }
            if (blockNextPlay) {
                blockNextPlay = false;
                blockedPlayEntered.countDown();
                try {
                    continueBlockedPlay.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            playCalls++;
            playing = true;
            if ((accepted.size() - lastFlushAcceptedOffset) / 4 < startThresholdFrames) {
                outputUnderrunCount++;
            }
            if (bytesAtFirstPlay < 0) {
                bytesAtFirstPlay = accepted.size();
                bytesSinceLastFlushAtFirstPlay = accepted.size() - lastFlushAcceptedOffset;
            }
        }

        @Override
        public synchronized void pause() {
            pauseCalls++;
            if (takeFailure(FailurePoint.PAUSE)) {
                throw new IllegalStateException("transient pause failure");
            }
            playing = false;
        }

        @Override
        public synchronized void flush() {
            if (takeFailure(FailurePoint.FLUSH)) {
                throw new IllegalStateException("transient flush failure");
            }
            flushCalls++;
            lastFlushAcceptedOffset = accepted.size();
            long head = manualPlaybackHeadFrames;
            if (head >= 0L) {
                manualPlaybackHeadFrames = 0L;
            }
        }

        @Override
        public int write(byte[] bytes, int offset, int length) {
            int call = ++writeCalls;
            if (takeFailure(FailurePoint.WRITE)) {
                throw new IllegalStateException("transient write failure");
            }
            int actual = writeLimit > 0 ? Math.min(writeLimit, length) : length;
            long acceptedBeforeFrames;
            synchronized (this) {
                acceptedBeforeFrames = accepted.size() / 4L;
                accepted.write(bytes, offset, actual);
            }
            if (armThresholdUnderrunOnNextWrite) {
                armThresholdUnderrunOnNextWrite = false;
                long currentHead = manualPlaybackHeadFrames >= 0L
                        ? manualPlaybackHeadFrames : acceptedBeforeFrames;
                recoveryHeadFrames = currentHead + advanceHeadBeforeUnderrunFrames;
                manualPlaybackHeadFrames = recoveryHeadFrames;
                recoveryAcceptedBaseFrames = acceptedBeforeFrames;
                thresholdRecoveryArmed = true;
                outputUnderrunCount++;
                if (blockWriteAfterThresholdUnderrun) {
                    blockWriteAfterThresholdUnderrun = false;
                    blockedWriteCall = call + 1;
                }
            }
            writeSizes.add(actual);
            writeThreads.add(Thread.currentThread().getName());
            owner.lifecycleEvents.add("write-" + identity);
            if (blockFirstWrite && call == 1) {
                firstWriteEntered.countDown();
                try {
                    continueFirstWrite.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            if (call == blockedWriteCall) {
                blockedWriteEntered.countDown();
                try {
                    continueBlockedWrite.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return actual;
        }

        @Override
        public synchronized void release() {
            playing = false;
            released = true;
            owner.lifecycleEvents.add("release-" + identity);
            continueFirstWrite.countDown();
            continueBlockedWrite.countDown();
            continueBlockedPlay.countDown();
        }

        private synchronized int acceptedLength() {
            return accepted.size();
        }

        private synchronized byte[] acceptedBytes() {
            return accepted.toByteArray();
        }
    }
}
