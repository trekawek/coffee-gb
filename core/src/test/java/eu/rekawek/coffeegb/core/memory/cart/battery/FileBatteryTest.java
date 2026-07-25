package eu.rekawek.coffeegb.core.memory.cart.battery;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.persistence.AtomicFileWriter;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class FileBatteryTest {

    @Test
    public void failedRamAndRtcFlushRetainsExactPendingBytesAndRetryClearsDirty()
            throws Exception {
        withDirectory(directory -> {
            Path target = directory.resolve("clock.sav");
            byte[] old = "old-complete-battery".getBytes();
            Files.write(target, old);
            FailOnceWriter persistence = new FailOnceWriter();
            FileBattery battery = new FileBattery(target.toFile(), 8, persistence);
            EventBusImpl eventBus = new EventBusImpl();
            LinkedBlockingQueue<BatteryPersistenceFailedEvent> failures =
                    new LinkedBlockingQueue<>();
            eventBus.register(failures::add, BatteryPersistenceFailedEvent.class);
            battery.init(eventBus);
            int[] ram = {1, 2, 3, 4, 250, 251, 252, 253};
            long[] clock = new long[11];
            for (int i = 0; i < clock.length; i++) {
                clock[i] = 0x10203040L + i;
            }
            byte[] expected = expectedBytes(ram, clock);

            battery.saveRamWithClock(ram, clock);
            Arrays.fill(ram, 0);
            Arrays.fill(clock, 0);
            battery.flush();

            assertArrayEquals(old, Files.readAllBytes(target));
            assertTrue(battery.isDirtyForTesting());
            assertTrue(battery.isClockPresentForTesting());
            BatteryPersistenceFailedEvent event = failures.poll(2, TimeUnit.SECONDS);
            assertNotNull(event);
            assertEquals(BatteryPersistenceFailedEvent.Operation.SAVE, event.operation());
            assertTrue(event.message().contains("Changes remain pending"));

            battery.flush();
            assertArrayEquals(expected, Files.readAllBytes(target));
            assertFalse(battery.isDirtyForTesting());
            assertFalse(battery.isClockPresentForTesting());
            eventBus.close();
        });
    }

    @Test
    public void reportedPostRenameFailureKeepsRetryStateEvenWhenNewFileIsVisible()
            throws Exception {
        withDirectory(directory -> {
            Path target = directory.resolve("post-rename.sav");
            Files.write(target, new byte[] {9, 9, 9, 9});
            CommitThenFailOnceWriter persistence = new CommitThenFailOnceWriter();
            FileBattery battery = new FileBattery(target.toFile(), 4, persistence);
            int[] ram = {5, 6, 7, 8};

            battery.saveRam(ram);
            battery.flush();

            assertArrayEquals(new byte[] {5, 6, 7, 8}, Files.readAllBytes(target));
            assertTrue(battery.isDirtyForTesting());

            battery.flush();
            assertArrayEquals(new byte[] {5, 6, 7, 8}, Files.readAllBytes(target));
            assertFalse(battery.isDirtyForTesting());
        });
    }

    @Test
    public void newerGenerationWaitingForOlderFlushIsNeverClearedByThatFlush()
            throws Exception {
        withDirectory(directory -> {
            Path target = directory.resolve("generation.sav");
            BlockingWriter persistence = new BlockingWriter();
            FileBattery battery = new FileBattery(target.toFile(), 4, persistence);
            int[] older = {1, 1, 1, 1};
            int[] newer = {2, 2, 2, 2};
            AtomicReference<Throwable> failure = new AtomicReference<>();

            battery.saveRam(older);
            Thread flushing = new Thread(() -> run(battery::flush, failure));
            flushing.start();
            assertTrue(persistence.entered.await(5, TimeUnit.SECONDS));

            Thread updating = new Thread(() -> run(() -> battery.saveRam(newer), failure));
            updating.start();
            Thread.sleep(50);
            persistence.release.countDown();
            flushing.join(5_000);
            updating.join(5_000);
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }

            assertArrayEquals(new byte[] {1, 1, 1, 1}, Files.readAllBytes(target));
            assertTrue("the newer generation must remain dirty", battery.isDirtyForTesting());

            battery.flush();
            assertArrayEquals(new byte[] {2, 2, 2, 2}, Files.readAllBytes(target));
            assertFalse(battery.isDirtyForTesting());
        });
    }

    @Test
    public void loadUsesRecoveryRoutePreservesSmallTruncatedSemanticsAndReportsIoFailure()
            throws Exception {
        withDirectory(directory -> {
            Path target = directory.resolve("small.sav");
            Files.write(target, new byte[] {0x11, 0x22, (byte) 0xfe});
            TrackingWriter persistence = new TrackingWriter();
            FileBattery battery = new FileBattery(target.toFile(), 8, persistence);
            int[] ram = new int[8];

            battery.loadRam(ram);

            assertEquals(1, persistence.reads);
            assertArrayEquals(new int[] {0x11, 0x22, 0xfe, 0, 0, 0, 0, 0}, ram);

            EventBusImpl eventBus = new EventBusImpl();
            LinkedBlockingQueue<BatteryPersistenceFailedEvent> failures =
                    new LinkedBlockingQueue<>();
            eventBus.register(failures::add, BatteryPersistenceFailedEvent.class);
            eventBus.register(
                    ignored -> {
                        throw new IllegalStateException("broken UI subscriber");
                    },
                    BatteryPersistenceFailedEvent.class);
            FileBattery failing =
                    new FileBattery(
                            directory.resolve("unreadable.sav").toFile(),
                            4,
                            new ReadFailingWriter());
            int[] unchanged = {7, 7, 7, 7};
            // Construction-time mapper loads happen before Gameboy.init; the signal is retained
            // and delivered when the session event route becomes available.
            failing.loadRam(unchanged);
            failing.init(eventBus);

            assertArrayEquals(new int[] {7, 7, 7, 7}, unchanged);
            BatteryPersistenceFailedEvent event = failures.poll(2, TimeUnit.SECONDS);
            assertNotNull(event);
            assertEquals(BatteryPersistenceFailedEvent.Operation.LOAD, event.operation());
            assertEquals("unreadable.sav", event.fileName());
            assertTrue(event.message().contains("Unable to load battery save"));
            assertEquals("execution continues after a broken subscriber", 4, unchanged.length);
            eventBus.close();
        });
    }

    private static byte[] expectedBytes(int[] ram, long[] clock) {
        ByteBuffer bytes =
                ByteBuffer.allocate(ram.length + clock.length * Integer.BYTES)
                        .order(ByteOrder.LITTLE_ENDIAN);
        for (int value : ram) {
            bytes.put((byte) value);
        }
        for (long value : clock) {
            bytes.putInt((int) value);
        }
        return bytes.array();
    }

    private static void run(Runnable action, AtomicReference<Throwable> failure) {
        try {
            action.run();
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

    private static void withDirectory(DirectoryTest test) throws Exception {
        Path directory = Files.createTempDirectory("coffee-gb-file-battery");
        try {
            test.run(directory);
        } finally {
            try (var files = Files.list(directory)) {
                files.forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            Files.deleteIfExists(directory);
        }
    }

    @FunctionalInterface
    private interface DirectoryTest {
        void run(Path directory) throws Exception;
    }

    private static class FailOnceWriter extends AtomicFileWriter {
        private boolean fail = true;

        @Override
        public void write(Path target, byte[] intendedBytes) throws IOException {
            if (fail) {
                fail = false;
                throw new IOException("injected before write");
            }
            AtomicFileWriter.system().write(target, intendedBytes);
        }
    }

    private static class CommitThenFailOnceWriter extends AtomicFileWriter {
        private boolean fail = true;

        @Override
        public void write(Path target, byte[] intendedBytes) throws IOException {
            AtomicFileWriter.system().write(target, intendedBytes);
            if (fail) {
                fail = false;
                throw new IOException("injected after commit");
            }
        }
    }

    private static class BlockingWriter extends AtomicFileWriter {
        private final CountDownLatch entered = new CountDownLatch(1);

        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void write(Path target, byte[] intendedBytes) throws IOException {
            if (entered.getCount() > 0) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("test timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("test interrupted", e);
                }
            }
            AtomicFileWriter.system().write(target, intendedBytes);
        }
    }

    private static class TrackingWriter extends AtomicFileWriter {
        private int reads;

        @Override
        public <T> T read(Path target, PathReader<T> reader) throws IOException {
            reads++;
            return AtomicFileWriter.system().read(target, reader);
        }
    }

    private static class ReadFailingWriter extends AtomicFileWriter {
        @Override
        public <T> T read(Path target, PathReader<T> reader) throws IOException {
            throw new IOException("injected recovery failure");
        }
    }
}
