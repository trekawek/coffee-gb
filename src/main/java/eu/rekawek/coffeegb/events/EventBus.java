package eu.rekawek.coffeegb.events;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class EventBus {
    private final List<Registration> registrations = new CopyOnWriteArrayList<>();
    private final EventBus parent;
    private final List<EventBus> children = new CopyOnWriteArrayList<>();
    private final String callerId;

    private volatile boolean stopped;

    public EventBus() {
        this(null, null);
    }

    public EventBus(EventBus parent, String callerId) {
        this.parent = parent;
        this.callerId = callerId;
    }

    public <E extends Event> void register(
            Subscriber<E> subscriber, Class<E> eventType, String callerFilter) {
        if (stopped) {
            throw new IllegalStateException("This EventBus is no longer active.");
        }
        registrations.add(new Registration(subscriber, eventType, callerFilter));
    }

    public <E extends Event> void register(Subscriber<E> subscriber, Class<E> eventType) {
        register(subscriber, eventType, null);
    }

    public <E extends Event> void post(E event) {
        getRoot().postToDescendants(event, callerId);
    }

    private EventBus getRoot() {
        var current = this;
        while (current.parent != null) {
            current = current.parent;
        }
        return current;
    }

    private <E extends Event> void postToDescendants(E event, String callerId) {
        doPost(event, callerId);
        // all children
        for (EventBus c : children) {
            c.postToDescendants(event, callerId);
        }
    }

    private <E extends Event> void doPost(E event, String callerId) {
        if (stopped) {
            throw new IllegalStateException("This EventBus is no longer active.");
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

    @NotNull
    public EventBus fork(String callerId) {
        EventBus child = new EventBus(this, callerId);
        children.add(child);
        return child;
    }

    private void removeChild(EventBus child) {
        children.remove(child);
    }

    public void stop() {
        if (parent != null) {
            parent.removeChild(this);
        }
        stopped = true;
    }

    private record Registration(Subscriber<?> subscriber, Class<?> eventType, String callerFilter) {
    }
}
