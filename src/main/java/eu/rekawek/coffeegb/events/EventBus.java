package eu.rekawek.coffeegb.events;

import org.jetbrains.annotations.NotNull;

public interface EventBus extends AutoCloseable {
    <E extends Event> void register(Subscriber<E> subscriber, Class<E> eventType, String callerFilter);

    <E extends Event> void register(Subscriber<E> subscriber, Class<E> eventType);

    <E extends Event> void post(E event);

    <E extends Event> void postAsync(E event);

    @NotNull EventBus fork(String callerId);

    void close();

    EventBus NULL_EVENT_BUS = new EventBus() {
        @Override
        public void close() {
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
