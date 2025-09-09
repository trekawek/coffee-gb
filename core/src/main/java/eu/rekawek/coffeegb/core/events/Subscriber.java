package eu.rekawek.coffeegb.core.events;

public interface Subscriber<E extends Event> {
    void onEvent(E event);
}
