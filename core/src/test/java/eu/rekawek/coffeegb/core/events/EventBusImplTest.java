package eu.rekawek.coffeegb.core.events;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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

    private static class TestEvent implements Event {
    }
}
