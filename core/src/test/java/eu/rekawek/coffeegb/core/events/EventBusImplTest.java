package eu.rekawek.coffeegb.core.events;

import org.junit.Test;

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

    private static class TestEvent implements Event {
    }
}
