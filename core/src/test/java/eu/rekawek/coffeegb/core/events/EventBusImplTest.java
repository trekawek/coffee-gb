package eu.rekawek.coffeegb.core.events;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class EventBusImplTest {

    @Test
    public void closeTimesOutForAStalledSubscriberAndCanBeRetried() throws Exception {
        EventBusImpl bus = new EventBusImpl(null, "test", true, 50);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        AtomicInteger deliveries = new AtomicInteger();
        bus.register(
                event -> {
                    deliveries.incrementAndGet();
                    entered.countDown();
                    while (release.getCount() != 0) {
                        try {
                            release.await();
                        } catch (InterruptedException ignored) {
                            // Deliberately model a third-party subscriber that does not cooperate
                            // with interruption. The close deadline must still hold.
                        }
                    }
                    returned.countDown();
                },
                TestEvent.class);
        bus.postAsync(new TestEvent());
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        long started = System.nanoTime();
        assertThrows(EventBusTeardownTimeoutException.class, bus::close);
        long elapsedMillis =
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue("close took " + elapsedMillis + " ms", elapsedMillis < 1_000);
        assertFalse("close must not pretend a running subscriber stopped", returned.await(25, TimeUnit.MILLISECONDS));

        bus.post(new TestEvent());
        assertEquals("a stopping bus must reject new synchronous delivery", 1, deliveries.get());

        release.countDown();
        assertTrue(returned.await(2, TimeUnit.SECONDS));
        bus.close();
        assertThrows(
                IllegalStateException.class,
                () -> bus.register(event -> {}, TestEvent.class));
    }

    @Test
    public void synchronousBusClosesWithoutWaitingForAWorker() {
        EventBusImpl bus = new EventBusImpl(null, "test", false, 50);

        bus.close();

        assertThrows(
                IllegalStateException.class,
                () -> bus.register(event -> {}, TestEvent.class));
    }

    @Test
    public void closeTimesOutForAnInFlightSynchronousSubscriberAndCanBeRetried()
            throws Exception {
        EventBusImpl bus = new EventBusImpl(null, "sync", false, 50);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger deliveries = new AtomicInteger();
        bus.register(
                event -> {
                    deliveries.incrementAndGet();
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                TestEvent.class);
        Thread poster = new Thread(() -> bus.post(new TestEvent()));
        poster.setDaemon(true);
        poster.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        long started = System.nanoTime();
        assertThrows(EventBusTeardownTimeoutException.class, bus::close);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue("close took " + elapsedMillis + " ms", elapsedMillis < 1_000);
        bus.post(new TestEvent());
        assertEquals("the close gate must reject later synchronous posts", 1, deliveries.get());

        release.countDown();
        poster.join(2_000);
        assertFalse(poster.isAlive());
        bus.close();
    }

    @Test
    public void parentCloseWaitsForSynchronousDispatchInItsDescendant() throws Exception {
        EventBusImpl root = new EventBusImpl(null, "root", false, 1_000);
        EventBusImpl child = root.fork("child");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        child.register(
                event -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                TestEvent.class);
        Thread poster = new Thread(() -> root.post(new TestEvent()));
        Thread closer =
                new Thread(
                        () -> {
                            try {
                                root.close();
                            } catch (Throwable failure) {
                                closeFailure.set(failure);
                            } finally {
                                closeReturned.countDown();
                            }
                        });
        poster.setDaemon(true);
        closer.setDaemon(true);

        poster.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        closer.start();
        assertFalse(
                "closeBefore must retain the parent while a child subscriber is running",
                closeReturned.await(50, TimeUnit.MILLISECONDS));

        release.countDown();
        poster.join(2_000);
        closer.join(2_000);
        assertFalse(poster.isAlive());
        assertFalse(closer.isAlive());
        assertEquals(null, closeFailure.get());
    }

    @Test
    public void reentrantNestedCloseFailsFastAndCanBeRetriedAfterDispatch() {
        EventBusImpl bus = new EventBusImpl(null, "nested", false, 1_000);
        AtomicInteger primaryDeliveries = new AtomicInteger();
        AtomicInteger laterDeliveries = new AtomicInteger();
        bus.register(
                event -> {
                    primaryDeliveries.incrementAndGet();
                    if (event.depth == 0) {
                        bus.post(new NestedEvent(1));
                    } else {
                        assertThrows(EventBusTeardownTimeoutException.class, bus::close);
                    }
                },
                NestedEvent.class);
        bus.register(event -> laterDeliveries.incrementAndGet(), NestedEvent.class);

        bus.post(new NestedEvent(0));

        assertEquals(2, primaryDeliveries.get());
        assertEquals(
                "no later callback may start after the reentrant close gate",
                0,
                laterDeliveries.get());
        bus.close();
    }

    @Test
    public void stoppedChildCannotPostThroughItsRetainedParentIntoActiveSibling() {
        EventBusImpl root = new EventBusImpl(null, null, false);
        EventBusImpl stoppedChild = root.fork("stopped");
        EventBusImpl activeSibling = root.fork("active");
        AtomicInteger siblingDeliveries = new AtomicInteger();
        activeSibling.register(event -> siblingDeliveries.incrementAndGet(), TestEvent.class);

        stoppedChild.close();
        stoppedChild.post(new TestEvent());

        assertEquals(0, siblingDeliveries.get());
        root.post(new TestEvent());
        assertEquals(1, siblingDeliveries.get());
        root.close();
    }

    @Test
    public void concurrentForkIsOwnedByCloseBeforeItsWorkerCanEscape() throws Exception {
        CountDownLatch childConstructed = new CountDownLatch(1);
        CountDownLatch releaseFork = new CountDownLatch(1);
        EventBusImpl root =
                new EventBusImpl(null, null, true, 1_000) {
                    @Override
                    EventBusImpl createChild(String callerId) {
                        EventBusImpl child = super.createChild(callerId);
                        childConstructed.countDown();
                        try {
                            releaseFork.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return child;
                    }
                };
        AtomicReference<EventBusImpl> child = new AtomicReference<>();
        AtomicReference<Throwable> forkFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        CountDownLatch closeAttempted = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        Thread forker =
                new Thread(
                        () -> {
                            try {
                                child.set(root.fork("racing-child"));
                            } catch (Throwable failure) {
                                forkFailure.set(failure);
                            }
                        });
        Thread closer =
                new Thread(
                        () -> {
                            closeAttempted.countDown();
                            try {
                                root.close();
                            } catch (Throwable failure) {
                                closeFailure.set(failure);
                            } finally {
                                closeReturned.countDown();
                            }
                        });
        forker.setDaemon(true);
        closer.setDaemon(true);

        forker.start();
        assertTrue(childConstructed.await(2, TimeUnit.SECONDS));
        closer.start();
        assertTrue(closeAttempted.await(2, TimeUnit.SECONDS));
        assertFalse(
                "close must wait for the in-flight ownership transaction",
                closeReturned.await(50, TimeUnit.MILLISECONDS));

        releaseFork.countDown();
        forker.join(2_000);
        closer.join(2_000);

        assertFalse(forker.isAlive());
        assertFalse(closer.isAlive());
        assertEquals(null, forkFailure.get());
        assertEquals(null, closeFailure.get());
        EventBusImpl attached = child.get();
        assertThrows(
                IllegalStateException.class,
                () -> attached.register(event -> {}, TestEvent.class));
        assertThrows(
                IllegalStateException.class,
                () -> attached.postAsync(new TestEvent()));
    }

    @Test
    public void boundedCloseFallsBackToLegacyCloseImplementation() {
        AtomicInteger closeCalls = new AtomicInteger();
        EventBus legacy =
                new EventBus() {
                    @Override
                    public void close() {
                        closeCalls.incrementAndGet();
                    }

                    @Override
                    public <E extends Event> void register(
                            Subscriber<E> subscriber, Class<E> eventType, String callerFilter) {
                    }

                    @Override
                    public <E extends Event> void register(
                            Subscriber<E> subscriber, Class<E> eventType) {
                    }

                    @Override
                    public <E extends Event> void post(E event) {
                    }

                    @Override
                    public <E extends Event> void postAsync(E event) {
                    }

                    @Override
                    public EventBus fork(String callerId) {
                        return this;
                    }
                };

        legacy.close(5, TimeUnit.MILLISECONDS);

        assertEquals(1, closeCalls.get());
    }

    @Test
    public void asyncBurstIsDeliveredOnceAndInFifoOrder() {
        EventBusImpl bus = new EventBusImpl(null, "fifo", true, 1_000);
        List<Integer> deliveries = Collections.synchronizedList(new ArrayList<>());
        bus.register(event -> deliveries.add(event.sequence), SequencedEvent.class);

        for (int sequence = 0; sequence < 100; sequence++) {
            bus.postAsync(new SequencedEvent(sequence));
        }
        bus.drainAsyncEvents();

        assertEquals(100, deliveries.size());
        for (int sequence = 0; sequence < deliveries.size(); sequence++) {
            assertEquals(sequence, deliveries.get(sequence).intValue());
        }
        bus.close();
    }

    @Test
    public void drainWaitsWhileTheHeadAsyncSubscriberIsStillRunning() throws Exception {
        EventBusImpl bus = new EventBusImpl(null, "drain", true, 1_000);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch drained = new CountDownLatch(1);
        bus.register(
                event -> {
                    entered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                TestEvent.class);
        bus.postAsync(new TestEvent());
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        Thread drainer = new Thread(() -> {
            bus.drainAsyncEvents();
            drained.countDown();
        });
        drainer.setDaemon(true);
        drainer.start();
        assertFalse("the head must remain visible through subscriber dispatch",
                drained.await(50, TimeUnit.MILLISECONDS));

        release.countDown();
        assertTrue(drained.await(2, TimeUnit.SECONDS));
        drainer.join(2_000);
        assertFalse(drainer.isAlive());
        bus.close();
    }

    @Test
    public void idleAsyncWorkerBlocksUntilSignalledAndCloseWakesIt() throws Exception {
        EventBusImpl bus = new EventBusImpl(null, "idle", true, 1_000);
        await("the async worker to block on its event signal", bus::isAsyncWorkerBlockedForEvent);

        long started = System.nanoTime();
        bus.close();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue("close took " + elapsedMillis + " ms", elapsedMillis < 1_000);
        assertThrows(IllegalStateException.class, () -> bus.postAsync(new TestEvent()));
    }

    @Test
    public void borrowedEventsCannotBeQueuedAsynchronously() {
        EventBusImpl bus = new EventBusImpl(null, "borrowed", true, 1_000);
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> bus.postAsync(new BorrowedEvent()));
        } finally {
            bus.close();
        }
    }

    private static void await(String description, Condition condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.evaluate() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue("Timed out waiting for " + description, condition.evaluate());
    }

    @FunctionalInterface
    private interface Condition {
        boolean evaluate();
    }

    private static class TestEvent implements Event {
    }

    private static class BorrowedEvent implements SynchronousBorrowedEvent {
    }

    private static class NestedEvent implements Event {
        private final int depth;

        private NestedEvent(int depth) {
            this.depth = depth;
        }
    }

    private static class SequencedEvent implements Event {
        private final int sequence;

        private SequencedEvent(int sequence) {
            this.sequence = sequence;
        }
    }
}
