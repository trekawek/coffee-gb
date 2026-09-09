package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.controller.Controller;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.sound.Sound;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Focused proof that asynchronous evidence cannot hold the owner or PCM ledger. */
public class AndroidSoakDiagnosticsTest {

    @Test
    public void blockedAudioQueryDoesNotBlockOwnerSubmissionOrPcmProducer() throws Exception {
        Assume.assumeTrue(BuildConfig.DIAGNOSTICS_ENABLED);
        EventBusImpl events = new EventBusImpl(null, null, false);
        BlockingFactory factory = new BlockingFactory();
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        List<String> records = new CopyOnWriteArrayList<>();
        AndroidSoakDiagnostics diagnostics = diagnostics(records);
        sink.start();
        try {
            assertTrue(factory.opened.await(2, TimeUnit.SECONDS));
            awaitAudioReady(sink);
            factory.output.blockNextPlaybackQuery.set(true);
            diagnostics.sample(event(1), sink, true, false);
            assertTrue(factory.output.queryEntered.await(2, TimeUnit.SECONDS));

            long started = System.nanoTime();
            events.post(packet(ClockSpec.LEGACY, 120));
            events.post(packet(ClockSpec.LEGACY, 150));
            events.post(packet(ClockSpec.LEGACY, 180));
            events.post(packet(ClockSpec.LEGACY, 210));
            long elapsedNanos = System.nanoTime() - started;
            assertTrue("controller PCM offer waited on audio query",
                    elapsedNanos < TimeUnit.MILLISECONDS.toNanos(500));
            // The audio worker has already consumed producer accounting and written PCM while the
            // diagnostic worker is still blocked in the fake media query.
            waitForWrites(factory.output);
            factory.output.releaseQuery.countDown();
        } finally {
            factory.output.releaseQuery.countDown();
            diagnostics.close();
            sink.close();
        }
    }

    @Test
    public void positionTimestampIsBeforeAStoppedRemainderQuery() throws Exception {
        Assume.assumeTrue(BuildConfig.DIAGNOSTICS_ENABLED);
        EventBusImpl events = new EventBusImpl(null, null, false);
        BlockingFactory factory = new BlockingFactory();
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        sink.start();
        try {
            assertTrue(factory.opened.await(2, TimeUnit.SECONDS));
            awaitAudioReady(sink);
            factory.output.delayAfterPosition.set(true);
            AtomicReference<AndroidAudioSink.SoakDiagnosticSnapshot> result = new AtomicReference<>();
            Thread reader = new Thread(() -> result.set(sink.snapshotForSoak(System.nanoTime())),
                    "soak-snapshot-test");
            reader.start();
            assertTrue(factory.output.remainderEntered.await(2, TimeUnit.SECONDS));
            long releaseNanos = System.nanoTime();
            factory.output.releaseRemainder.countDown();
            reader.join(2_000L);
            assertFalse(reader.isAlive());
            AndroidAudioSink.SoakDiagnosticSnapshot snapshot = result.get();
            assertTrue(snapshot.capturedAtNanos() < releaseNanos);
            assertTrue(snapshot.completedAtNanos() >= releaseNanos);
        } finally {
            factory.output.releaseRemainder.countDown();
            sink.close();
        }
    }

    @Test
    public void boundaryChangesInvalidateABlockedSnapshot() throws Exception {
        Assume.assumeTrue(BuildConfig.DIAGNOSTICS_ENABLED);
        assertSnapshotIncoherent(AndroidAudioSink::requestRouteReopen);
        assertSnapshotIncoherent(AndroidAudioSink::pause);
        assertSnapshotIncoherent(sink -> sink.setMuted(true));
    }

    @Test
    public void pendingRouteReopenAtSnapshotEntryIsIncoherent() throws Exception {
        Assume.assumeTrue(BuildConfig.DIAGNOSTICS_ENABLED);
        EventBusImpl events = new EventBusImpl(null, null, false);
        BlockingFactory factory = new BlockingFactory();
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        sink.start();
        try {
            assertTrue(factory.opened.await(2, TimeUnit.SECONDS));
            awaitAudioReady(sink);
            factory.output.blockNextWrite.set(true);
            events.post(packet(ClockSpec.LEGACY, 120));
            events.post(packet(ClockSpec.LEGACY, 150));
            events.post(packet(ClockSpec.LEGACY, 180));
            events.post(packet(ClockSpec.LEGACY, 210));
            assertTrue(factory.output.writeEntered.await(2, TimeUnit.SECONDS));
            sink.requestRouteReopen();
            AndroidAudioSink.SoakDiagnosticSnapshot snapshot =
                    sink.snapshotForSoak(System.nanoTime());
            assertFalse("a queued route reopen must fail closed", snapshot.coherent());
        } finally {
            factory.output.releaseWrite.countDown();
            sink.close();
        }
    }

    @Test
    public void busyRequestsAreDroppedAndAcceptedRecordsStayOrdered() throws Exception {
        Assume.assumeTrue(BuildConfig.DIAGNOSTICS_ENABLED);
        EventBusImpl events = new EventBusImpl(null, null, false);
        BlockingFactory factory = new BlockingFactory();
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        List<String> records = new CopyOnWriteArrayList<>();
        AndroidSoakDiagnostics diagnostics = diagnostics(records);
        sink.start();
        try {
            assertTrue(factory.opened.await(2, TimeUnit.SECONDS));
            awaitAudioReady(sink);
            factory.output.blockNextPlaybackQuery.set(true);
            diagnostics.submitted();
            diagnostics.sample(event(1), sink, true, false);
            assertTrue(factory.output.queryEntered.await(2, TimeUnit.SECONDS));
            diagnostics.submitted();
            diagnostics.submissionFailed();
            diagnostics.sample(event(2), sink, true, false);
            factory.output.releaseQuery.countDown();
            waitForRecords(records, 1);
            assertTrue(diagnostics.awaitIdleForTesting(2, TimeUnit.SECONDS));

            diagnostics.submitted();
            diagnostics.sample(event(3), sink, false, true);
            waitForRecords(records, 2);
            JSONObject first = new JSONObject(records.get(0));
            JSONObject second = new JSONObject(records.get(1));
            assertEquals(1L, first.getLong("audioSnapshotSequence"));
            assertEquals(3L, second.getLong("audioSnapshotSequence"));
            assertEquals(1L, second.getLong("audioSnapshotDropped"));
            assertEquals(1L, first.getLong("masterTicks"));
            assertEquals(1L, first.getLong("submittedFrames"));
            assertEquals(0L, first.getLong("submissionFailures"));
            assertEquals(0L, first.getLong("controllerPriority"));
            assertTrue(first.getBoolean("visible"));
            assertFalse(first.getBoolean("hintsActive"));
            assertEquals(3L, second.getLong("masterTicks"));
            assertEquals(3L, second.getLong("submittedFrames"));
            assertEquals(1L, second.getLong("submissionFailures"));
            assertEquals(0L, second.getLong("controllerPriority"));
            assertFalse(second.getBoolean("visible"));
            assertTrue(second.getBoolean("hintsActive"));
        } finally {
            factory.output.releaseQuery.countDown();
            diagnostics.close();
            sink.close();
        }
    }

    @Test
    public void closeSuppressesACompletionFromTheClosedGeneration() throws Exception {
        Assume.assumeTrue(BuildConfig.DIAGNOSTICS_ENABLED);
        EventBusImpl events = new EventBusImpl(null, null, false);
        BlockingFactory factory = new BlockingFactory();
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        List<String> records = new CopyOnWriteArrayList<>();
        AndroidSoakDiagnostics diagnostics = diagnostics(records);
        sink.start();
        try {
            assertTrue(factory.opened.await(2, TimeUnit.SECONDS));
            awaitAudioReady(sink);
            factory.output.blockNextPlaybackQuery.set(true);
            diagnostics.sample(event(1), sink, true, false);
            assertTrue(factory.output.queryEntered.await(2, TimeUnit.SECONDS));
            long started = System.nanoTime();
            diagnostics.close();
            assertTrue("diagnostic close must stay bounded",
                    System.nanoTime() - started < TimeUnit.SECONDS.toNanos(1));
            factory.output.releaseQuery.countDown();
            assertTrue(factory.output.queryFinished.await(2, TimeUnit.SECONDS));
            assertTrue(diagnostics.awaitIdleForTesting(2, TimeUnit.SECONDS));
            assertTrue(records.isEmpty());
        } finally {
            factory.output.releaseQuery.countDown();
            diagnostics.close();
            sink.close();
        }
    }

    private static Controller.PerformanceSoakSampleEvent event(long ticks) {
        return new Controller.PerformanceSoakSampleEvent(
                1L, ticks * 1_000_000_000L, ticks, ticks, ticks, 0L,
                1_000_000L, 2_000_000L, false, 4_194_304L, 1L,
                "CGB", 1, "PERFORMANCE", false);
    }

    private static Sound.SoundSampleEvent packet(ClockSpec clock, int amplitude) {
        int[] samples = new int[clock.controllerTicksPerFrame() * 2];
        for (int index = 0; index < samples.length; index += 2) {
            samples[index] = amplitude;
            samples[index + 1] = -amplitude;
        }
        return new Sound.SoundSampleEvent(samples, clock);
    }

    private static void waitForWrites(BlockingOutput output) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (output.writeCalls.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertTrue(output.writeCalls.get() > 0);
    }

    private static void assertSnapshotIncoherent(Consumer<AndroidAudioSink> boundary)
            throws Exception {
        EventBusImpl events = new EventBusImpl(null, null, false);
        BlockingFactory factory = new BlockingFactory();
        AndroidAudioSink sink = new AndroidAudioSink(events, factory);
        sink.start();
        try {
            assertTrue(factory.opened.await(2, TimeUnit.SECONDS));
            awaitAudioReady(sink);
            factory.output.blockNextPlaybackQuery.set(true);
            AtomicReference<AndroidAudioSink.SoakDiagnosticSnapshot> result = new AtomicReference<>();
            Thread reader = new Thread(() -> result.set(sink.snapshotForSoak(System.nanoTime())),
                    "soak-snapshot-test");
            reader.start();
            assertTrue(factory.output.queryEntered.await(2, TimeUnit.SECONDS));
            boundary.accept(sink);
            factory.output.releaseQuery.countDown();
            reader.join(2_000L);
            assertFalse(reader.isAlive());
            assertTrue(result.get() != null);
            assertFalse("boundary crossed a supposedly coherent snapshot",
                    result.get().coherent());
        } finally {
            factory.output.releaseQuery.countDown();
            sink.close();
        }
    }

    private static void awaitAudioReady(AndroidAudioSink sink) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (sink.stats().sampleRate() != 44_100 && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertEquals(44_100, sink.stats().sampleRate());
    }

    private static void waitForRecords(List<String> records, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (records.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertTrue("diagnostic record count", records.size() >= count);
    }

    private static AndroidSoakDiagnostics diagnostics(List<String> records) {
        return new AndroidSoakDiagnostics(records::add, () -> 0);
    }

    private static final class BlockingFactory implements AndroidAudioSink.OutputFactory {
        private final CountDownLatch opened = new CountDownLatch(1);
        private final BlockingOutput output = new BlockingOutput();

        @Override
        public AndroidAudioSink.Output open() {
            opened.countDown();
            return output;
        }
    }

    private static final class BlockingOutput implements AndroidAudioSink.Output {
        private final CountDownLatch queryEntered = new CountDownLatch(1);
        private final CountDownLatch queryFinished = new CountDownLatch(1);
        private final CountDownLatch releaseQuery = new CountDownLatch(1);
        private final CountDownLatch remainderEntered = new CountDownLatch(1);
        private final CountDownLatch releaseRemainder = new CountDownLatch(1);
        private final CountDownLatch writeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseWrite = new CountDownLatch(1);
        private final AtomicBoolean blockNextPlaybackQuery = new AtomicBoolean();
        private final AtomicBoolean delayAfterPosition = new AtomicBoolean();
        private final AtomicBoolean blockNextWrite = new AtomicBoolean();
        private final AtomicInteger writeCalls = new AtomicInteger();

        @Override
        public int sampleRate() {
            return 44_100;
        }

        @Override
        public AndroidAudioSink.AudioStats audioStats() {
            return new AndroidAudioSink.AudioStats(44_100, 4096, 4096, 4096);
        }

        @Override
        public long playbackPositionFrames() {
            if ((Thread.currentThread().getName().contains("diagnostics")
                    || Thread.currentThread().getName().equals("soak-snapshot-test"))
                    && blockNextPlaybackQuery.compareAndSet(true, false)) {
                queryEntered.countDown();
                boolean released = false;
                while (!released) {
                    try {
                        released = releaseQuery.await(50, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                        // Model a media-service call that does not honor thread interruption.
                    }
                }
                queryFinished.countDown();
            }
            return 0L;
        }

        @Override
        public long outputUnderrunCount() {
            if (delayAfterPosition.get()
                    && Thread.currentThread().getName().equals("soak-snapshot-test")) {
                remainderEntered.countDown();
                boolean released = false;
                while (!released) {
                    try {
                        released = releaseRemainder.await(50, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                        // Keep the position timestamp test representative of a non-interruptible
                        // remainder query; close remains bounded because this is test-only.
                    }
                }
            }
            return 0L;
        }

        @Override
        public void play() {
        }

        @Override
        public void pause() {
        }

        @Override
        public void flush() {
        }

        @Override
        public int write(byte[] bytes, int offset, int length) {
            if (blockNextWrite.compareAndSet(true, false)) {
                writeEntered.countDown();
                boolean released = false;
                while (!released) {
                    try {
                        released = releaseWrite.await(50, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                        // Keep the consumer in the fake write until the test releases it.
                    }
                }
            }
            writeCalls.incrementAndGet();
            return length;
        }

        @Override
        public void release() {
        }
    }
}
