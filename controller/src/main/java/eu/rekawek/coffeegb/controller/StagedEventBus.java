package eu.rekawek.coffeegb.controller;

import eu.rekawek.coffeegb.controller.events.SuppressibleEventFunnel;
import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.Subscriber;
import eu.rekawek.coffeegb.core.events.SynchronousBorrowedEvent;
import kotlin.jvm.functions.Function0;
import kotlin.reflect.KClass;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Keeps a newly initialized session isolated from the shared event tree until ownership commits.
 *
 * <p>The delegate child is attached immediately, but every registration is guarded while staged,
 * so lifecycle and input events for the old session cannot mutate the candidate. Candidate-origin
 * events are retained in a small bounded queue and published only after activation.
 */
final class StagedEventBus implements SuppressibleEventFunnel {

    private static final Logger LOG = LoggerFactory.getLogger(StagedEventBus.class);
    private static final int MAX_STAGED_POSTS = 256;

    private final EventBus delegate;
    private final Object lock = new Object();
    private final ArrayDeque<StagedPost> stagedPosts = new ArrayDeque<>();

    private volatile State state = State.STAGED;

    StagedEventBus(EventBus delegate) {
        this.delegate = delegate;
    }

    void activate() {
        List<StagedPost> posts;
        synchronized (lock) {
            if (state == State.ACTIVE) {
                return;
            }
            if (state == State.CLOSED) {
                throw new IllegalStateException("A discarded staged event bus cannot be activated");
            }
            state = State.ACTIVE;
            posts = new ArrayList<>(stagedPosts);
            stagedPosts.clear();
        }
        for (StagedPost post : posts) {
            try {
                if (post.async) {
                    delegate.postAsync(post.event);
                } else {
                    delegate.post(post.event);
                }
            } catch (RuntimeException e) {
                // Candidate initialization has already committed. A presentation subscriber must
                // not roll ownership back to a session whose event bus is closing or closed.
                LOG.warn("A staged session event subscriber failed during activation", e);
            }
        }
    }

    @Override
    public <E extends Event> void register(
            Subscriber<E> subscriber, Class<E> eventType, String callerFilter) {
        synchronized (lock) {
            ensureOpen();
            delegate.register(
                    event -> {
                        if (state == State.ACTIVE) {
                            subscriber.onEvent(event);
                        }
                    },
                    eventType,
                    callerFilter);
        }
    }

    @Override
    public <E extends Event> void register(Subscriber<E> subscriber, Class<E> eventType) {
        register(subscriber, eventType, null);
    }

    @Override
    public <E extends Event> void post(E event) {
        if (stage(event, false)) {
            return;
        }
        delegate.post(event);
    }

    @Override
    public <E extends Event> void postAsync(E event) {
        if (stage(event, true)) {
            return;
        }
        delegate.postAsync(event);
    }

    private boolean stage(Event event, boolean async) {
        synchronized (lock) {
            if (state == State.CLOSED) {
                if (async) {
                    ensureOpen();
                }
                // Core resource cleanup may synchronously silence an output after the owning
                // session bus has quiesced. Treat that signal as local cleanup, not an error.
                return true;
            }
            if (event instanceof SynchronousBorrowedEvent && (async || state == State.STAGED)) {
                throw new IllegalArgumentException(
                        "Borrowed events cannot be posted through a staged event bus");
            }
            if (state == State.ACTIVE) {
                return false;
            }
            if (stagedPosts.size() >= MAX_STAGED_POSTS) {
                throw new IllegalStateException(
                        "A staged session emitted too many events before activation");
            }
            stagedPosts.addLast(new StagedPost(event, async));
            return true;
        }
    }

    @Override
    @NotNull
    public EventBus fork(String callerId) {
        synchronized (lock) {
            ensureOpen();
            if (state != State.ACTIVE) {
                throw new IllegalStateException(
                        "A staged session event bus cannot be forked before activation");
            }
            return delegate.fork(callerId);
        }
    }

    @Override
    public <T> T suppressForwarding(
            Set<? extends KClass<? extends Event>> eventTypes,
            Function0<? extends T> action) {
        synchronized (lock) {
            ensureOpen();
            if (state != State.ACTIVE) {
                throw new IllegalStateException(
                        "A staged session cannot suppress forwarding before activation");
            }
        }
        if (delegate instanceof SuppressibleEventFunnel suppressible) {
            return suppressible.suppressForwarding(eventTypes, action);
        }
        return action.invoke();
    }

    @Override
    public void close() {
        synchronized (lock) {
            state = State.CLOSED;
            stagedPosts.clear();
        }
        // Always retry the delegate close. A previous bounded attempt may have timed out while
        // its worker was still returning.
        delegate.close();
    }

    @Override
    public void close(long timeout, TimeUnit unit) {
        synchronized (lock) {
            state = State.CLOSED;
            stagedPosts.clear();
        }
        // Always retry the delegate close. A previous bounded attempt may have timed out while
        // its worker was still returning.
        delegate.close(timeout, unit);
    }

    private void ensureOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("This EventBus is no longer active.");
        }
    }

    private enum State {
        STAGED,
        ACTIVE,
        CLOSED
    }

    private record StagedPost(Event event, boolean async) {
    }
}
