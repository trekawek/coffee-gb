package eu.rekawek.coffeegb.core.events;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

public class EventBusImpl implements EventBus {
    private static final Logger LOG = LoggerFactory.getLogger(EventBusImpl.class);
    private static final long DEFAULT_CLOSE_TIMEOUT_MILLIS = 1_000;

    private final List<Registration> registrations = new CopyOnWriteArrayList<>();
    private final EventBusImpl parent;
    private final List<EventBusImpl> children = new CopyOnWriteArrayList<>();
    private final Object lifecycleLock = new Object();
    private final String callerId;
    private final boolean asyncEventsEnabled;
    private final long closeTimeoutMillis;
    private final Thread asyncThread;
    private final CountDownLatch stoppedSignal = new CountDownLatch(1);
    private final ThreadLocal<Integer> synchronousDispatchDepth =
            ThreadLocal.withInitial(() -> 0);

    private volatile boolean doStop;
    private volatile boolean stopped;
    private int activeSynchronousDispatches;

    private final BlockingDeque<Event> asyncEvents = new LinkedBlockingDeque<>();

    public EventBusImpl() {
        this(null, null, true);
    }

    public EventBusImpl(EventBusImpl parent, String callerId, boolean asyncEventsEnabled) {
        this(parent, callerId, asyncEventsEnabled, DEFAULT_CLOSE_TIMEOUT_MILLIS);
    }

    EventBusImpl(
            EventBusImpl parent,
            String callerId,
            boolean asyncEventsEnabled,
            long closeTimeoutMillis) {
        if (closeTimeoutMillis <= 0) {
            throw new IllegalArgumentException("Close timeout must be positive");
        }
        this.parent = parent;
        this.callerId = callerId;
        this.asyncEventsEnabled = asyncEventsEnabled;
        this.closeTimeoutMillis = closeTimeoutMillis;
        if (asyncEventsEnabled) {
            asyncThread = new Thread(new AsyncRunnable(), eventThreadName(callerId));
            // A subscriber must not make the process immortal. close() still waits for the
            // bounded deadline and reports a typed failure so application teardown can retry.
            asyncThread.setDaemon(true);
            asyncThread.start();
        } else {
            asyncThread = null;
        }
    }

    @Override
    public <E extends Event> void register(
            Subscriber<E> subscriber, Class<E> eventType, String callerFilter) {
        synchronized (lifecycleLock) {
            if (doStop || stopped) {
                throw new IllegalStateException("This EventBus is no longer active.");
            }
            registrations.add(new Registration(subscriber, eventType, callerFilter));
        }
    }

    @Override
    public <E extends Event> void register(Subscriber<E> subscriber, Class<E> eventType) {
        register(subscriber, eventType, null);
    }

    @Override
    public <E extends Event> void post(E event) {
        // A child retains its parent reference after removal so a bounded close can be retried.
        // Never let that retained route turn a post-close cleanup signal into a sibling event.
        if (!enterSynchronousDispatch()) {
            return;
        }
        try {
            getRoot().postToDescendants(event, callerId);
        } finally {
            leaveSynchronousDispatch();
        }
    }

    @Override
    public <E extends Event> void postAsync(E event) {
        if (!asyncEventsEnabled) {
            throw new IllegalStateException("Async events are disabled");
        }
        synchronized (lifecycleLock) {
            if (doStop || stopped) {
                throw new IllegalStateException("This EventBus is no longer active.");
            }
            asyncEvents.addLast(event);
        }
    }

    private EventBusImpl getRoot() {
        var current = this;
        while (current.parent != null) {
            current = current.parent;
        }
        return current;
    }

    private <E extends Event> void postToDescendants(E event, String callerId) {
        if (!enterSynchronousDispatch()) {
            return;
        }
        try {
            doPost(event, callerId);
            // Keep this bus accounted for through the complete owned subtree. A close that gates
            // the parent therefore cannot return while an already-entered child subscriber is
            // still running.
            for (EventBusImpl c : children) {
                if (doStop || stopped) {
                    return;
                }
                c.postToDescendants(event, callerId);
            }
        } finally {
            leaveSynchronousDispatch();
        }
    }

    private <E extends Event> void doPost(E event, String callerId) {
        for (Registration r : registrations) {
            // A close requested by an earlier (possibly nested) subscriber owns the boundary.
            // Let that subscriber return, but do not begin another callback after the gate.
            if (doStop || stopped) {
                return;
            }
            if (!r.eventType.isInstance(event)) {
                continue;
            }
            if (r.callerFilter != null && !r.callerFilter.equals(callerId)) {
                continue;
            }
            //noinspection unchecked
            ((Subscriber<E>) r.subscriber).onEvent(event);
        }
    }

    private boolean enterSynchronousDispatch() {
        synchronized (lifecycleLock) {
            if (doStop || stopped) {
                return false;
            }
            activeSynchronousDispatches++;
            synchronousDispatchDepth.set(synchronousDispatchDepth.get() + 1);
            return true;
        }
    }

    private void leaveSynchronousDispatch() {
        synchronized (lifecycleLock) {
            int depth = synchronousDispatchDepth.get();
            if (depth <= 0 || activeSynchronousDispatches <= 0) {
                throw new IllegalStateException("Unbalanced event-bus dispatch accounting");
            }
            if (depth == 1) {
                synchronousDispatchDepth.remove();
            } else {
                synchronousDispatchDepth.set(depth - 1);
            }
            activeSynchronousDispatches--;
            lifecycleLock.notifyAll();
        }
    }

    @Override
    @NotNull
    public EventBusImpl fork(String callerId) {
        synchronized (lifecycleLock) {
            if (doStop || stopped) {
                throw new IllegalStateException("This EventBus is no longer active.");
            }
            // Creation and attachment are one ownership transaction with closeBefore(). The child
            // constructor may start an async worker, so it must be visible to the parent's close
            // traversal before that traversal is allowed to begin.
            EventBusImpl child = createChild(callerId);
            children.add(child);
            return child;
        }
    }

    EventBusImpl createChild(String callerId) {
        return new EventBusImpl(this, callerId, asyncEventsEnabled);
    }

    private void removeChild(EventBusImpl child) {
        synchronized (lifecycleLock) {
            children.remove(child);
        }
    }

    @Override
    public void close() {
        close(closeTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close(long timeout, TimeUnit unit) {
        if (timeout <= 0) {
            throw new IllegalArgumentException("Close timeout must be positive");
        }
        long timeoutNanos = unit.toNanos(timeout);
        if (timeoutNanos <= 0) {
            timeoutNanos = 1;
        }
        closeBefore(System.nanoTime() + timeoutNanos);
    }

    private void closeBefore(long deadlineNanos) {
        List<EventBusImpl> ownedChildren;
        synchronized (lifecycleLock) {
            // Prevent a new child worker from being attached after this traversal's snapshot.
            doStop = true;
            ownedChildren = List.copyOf(children);
        }
        for (EventBusImpl child : ownedChildren) {
            child.closeBefore(deadlineNanos);
        }

        if (asyncThread == null) {
            awaitSynchronousDispatches(deadlineNanos);
            stopped = true;
            stoppedSignal.countDown();
        } else if (!stopped) {
            asyncThread.interrupt();
            awaitWorker(deadlineNanos);
        }
        if (asyncThread != null) {
            awaitSynchronousDispatches(deadlineNanos);
        }

        if (parent != null) {
            parent.removeChild(this);
        }
    }

    private void awaitWorker(long deadlineNanos) {
        if (Thread.currentThread() == asyncThread) {
            throw new EventBusTeardownTimeoutException(
                    "An event bus cannot synchronously close from its own event dispatch; "
                            + "close can be retried after the subscriber returns");
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw closeTimeout("asynchronous worker");
        }
        try {
            if (!stoppedSignal.await(remainingNanos, TimeUnit.NANOSECONDS)) {
                throw closeTimeout("asynchronous worker");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventBusTeardownTimeoutException(
                    "Interrupted while waiting for the event-bus worker to stop");
        }
    }

    private void awaitSynchronousDispatches(long deadlineNanos) {
        synchronized (lifecycleLock) {
            if (synchronousDispatchDepth.get() > 0) {
                throw new EventBusTeardownTimeoutException(
                        "An event bus cannot synchronously close from its own event dispatch; "
                                + "close can be retried after the subscriber returns");
            }
            while (activeSynchronousDispatches != 0) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw closeTimeout("synchronous subscribers");
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(lifecycleLock, remainingNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new EventBusTeardownTimeoutException(
                            "Interrupted while waiting for synchronous event subscribers to stop");
                }
            }
        }
    }

    private EventBusTeardownTimeoutException closeTimeout(String activity) {
        String identity = callerId == null ? "root" : callerId;
        return new EventBusTeardownTimeoutException(
                "Timed out while stopping " + identity + " event-bus " + activity);
    }

    private static String eventThreadName(String callerId) {
        return callerId == null
                ? "coffee-gb-events"
                : "coffee-gb-events-" + callerId;
    }

    private record Registration(Subscriber<?> subscriber, Class<?> eventType, String callerFilter) {
    }

    private class AsyncRunnable implements Runnable {
        @Override
        public void run() {
            try {
                while (!doStop) {
                    // peek first and remove only after dispatching, so the queue reads
                    // as non-empty for the whole duration of the dispatch (drainAsyncEvents
                    // relies on it)
                    Event event = asyncEvents.peekFirst();
                    if (event == null) {
                        try {
                            Thread.sleep(1);
                        } catch (InterruptedException e) {
                            if (!doStop) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        continue;
                    }
                    try {
                        post(event);
                        asyncEvents.removeFirst();
                    } catch (Exception e) {
                        LOG.atError().setCause(e).log("Error processing event");
                        asyncEvents.pollFirst();
                    }
                }
            } finally {
                stopped = true;
                stoppedSignal.countDown();
            }
        }
    }

    /**
     * Blocks until all asynchronous events queued on this bus and its descendants have
     * been dispatched. Useful in tests that need a deterministic point after which every
     * in-flight event has been observed.
     */
    public void drainAsyncEvents() {
        while (hasPendingAsyncEvents()) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean hasPendingAsyncEvents() {
        if (!asyncEvents.isEmpty()) {
            return true;
        }
        for (EventBusImpl c : children) {
            if (c.hasPendingAsyncEvents()) {
                return true;
            }
        }
        return false;
    }
}
