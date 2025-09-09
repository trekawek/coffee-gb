package eu.rekawek.coffeegb.core.events;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

public class EventBusImpl implements EventBus {
    private static final Logger LOG = LoggerFactory.getLogger(EventBusImpl.class);

    private final List<Registration> registrations = new CopyOnWriteArrayList<>();
    private final EventBusImpl parent;
    private final List<EventBusImpl> children = new CopyOnWriteArrayList<>();
    private final String callerId;
    private final boolean asyncEventsEnabled;

    private volatile boolean doStop;
    private volatile boolean stopped;

    private final BlockingDeque<Event> asyncEvents = new LinkedBlockingDeque<>();

    public EventBusImpl() {
        this(null, null, true);
    }

    public EventBusImpl(EventBusImpl parent, String callerId, boolean asyncEventsEnabled) {
        this.parent = parent;
        this.callerId = callerId;
        this.asyncEventsEnabled = asyncEventsEnabled;
        if (asyncEventsEnabled) {
            new Thread(new AsyncRunnable()).start();
        }
    }

    @Override
    public <E extends Event> void register(
            Subscriber<E> subscriber, Class<E> eventType, String callerFilter) {
        if (stopped) {
            throw new IllegalStateException("This EventBus is no longer active.");
        }
        registrations.add(new Registration(subscriber, eventType, callerFilter));
    }

    @Override
    public <E extends Event> void register(Subscriber<E> subscriber, Class<E> eventType) {
        register(subscriber, eventType, null);
    }

    @Override
    public <E extends Event> void post(E event) {
        getRoot().postToDescendants(event, callerId);
    }

    @Override
    public <E extends Event> void postAsync(E event) {
        if (!asyncEventsEnabled) {
            throw new IllegalStateException("Async events are disabled");
        }
        asyncEvents.addLast(event);
    }

    private EventBusImpl getRoot() {
        var current = this;
        while (current.parent != null) {
            current = current.parent;
        }
        return current;
    }

    private <E extends Event> void postToDescendants(E event, String callerId) {
        doPost(event, callerId);
        // all children
        for (EventBusImpl c : children) {
            c.postToDescendants(event, callerId);
        }
    }

    private <E extends Event> void doPost(E event, String callerId) {
        if (stopped) {
            LOG.atInfo().log("This EventBus is no longer active.");
            return;
        }
        for (Registration r : registrations) {
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

    @Override
    @NotNull
    public EventBusImpl fork(String callerId) {
        EventBusImpl child = new EventBusImpl(this, callerId, asyncEventsEnabled);
        children.add(child);
        return child;
    }

    private void removeChild(EventBusImpl child) {
        children.remove(child);
    }

    @Override
    public void close() {
        for (EventBus c : children) {
            c.close();
        }
        doStop = true;
        if (asyncEventsEnabled) {
            while (!stopped) {
            }
        }
        if (parent != null) {
            parent.removeChild(this);
        }
    }

    private record Registration(Subscriber<?> subscriber, Class<?> eventType, String callerFilter) {
    }

    private class AsyncRunnable implements Runnable {
        @Override
        public void run() {
            while (!doStop) {
                try {
                    Event event = asyncEvents.poll(10, TimeUnit.MILLISECONDS);
                    if (event != null) {
                        post(event);
                    }
                } catch (Exception e) {
                    LOG.atError().setCause(e).log("Error processing event");
                }
            }
            stopped = true;
        }
    }
}
