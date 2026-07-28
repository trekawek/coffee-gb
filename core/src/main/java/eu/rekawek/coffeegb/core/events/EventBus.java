package eu.rekawek.coffeegb.core.events;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

public interface EventBus extends AutoCloseable {
    <E extends Event> void register(Subscriber<E> subscriber, Class<E> eventType, String callerFilter);

    <E extends Event> void register(Subscriber<E> subscriber, Class<E> eventType);

    <E extends Event> void post(E event);

    <E extends Event> void postAsync(E event);

    @NotNull EventBus fork(String callerId);

    default void close() {
        close(1, TimeUnit.SECONDS);
    }

    /**
     * Stops this bus and all descendants within one shared deadline.
     *
     * @throws EventBusTeardownTimeoutException if an asynchronous subscriber has not returned
     */
    void close(long timeout, TimeUnit unit);

    EventBus NULL_EVENT_BUS = new EventBus() {
        @Override
        public void close(long timeout, TimeUnit unit) {
        }

        @Override
        public <E extends Event> void register(Subscriber<E> subscriber, Class<E> eventType, String callerFilter) {
        }

        @Override
        public <E extends Event> void register(Subscriber<E> subscriber, Class<E> eventType) {
        }

        @Override
        public <E extends Event> void post(E event) {
        }

        @Override
        public <E extends Event> void postAsync(E event) {
        }

        @NotNull
        @Override
        public EventBus fork(String callerId) {
            return this;
        }
    };
}
