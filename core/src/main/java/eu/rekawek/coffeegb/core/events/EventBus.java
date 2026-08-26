package eu.rekawek.coffeegb.core.events;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

public interface EventBus extends AutoCloseable {
    <E extends Event> void register(Subscriber<E> subscriber, Class<E> eventType, String callerFilter);

    <E extends Event> void register(Subscriber<E> subscriber, Class<E> eventType);

    <E extends Event> void post(E event);

    <E extends Event> void postAsync(E event);

    @NotNull EventBus fork(String callerId);

    @Override
    void close();

    /**
     * Stops this bus and all descendants within one shared deadline.
     *
     * @throws EventBusTeardownTimeoutException if an asynchronous or synchronous subscriber has
     *     not returned, or if close is invoked reentrantly from a subscriber
     */
    default void close(long timeout, TimeUnit unit) {
        close();
    }

    EventBus NULL_EVENT_BUS = new EventBus() {
        @Override
        public void close() {
        }

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
            if (event instanceof SynchronousBorrowedEvent) {
                throw new IllegalArgumentException(
                        "Borrowed events can only be delivered synchronously");
            }
        }

        @NotNull
        @Override
        public EventBus fork(String callerId) {
            return this;
        }
    };
}
