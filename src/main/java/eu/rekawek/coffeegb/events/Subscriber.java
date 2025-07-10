package eu.rekawek.coffeegb.events;

public interface Subscriber<E extends Event> {
  void onEvent(E event);
}
