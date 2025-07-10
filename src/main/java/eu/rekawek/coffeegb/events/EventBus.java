package eu.rekawek.coffeegb.events;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;

public class EventBus {

  private final List<Registration> registrations = new CopyOnWriteArrayList<>();

  public <E extends Event> void register(Subscriber<E> subscriber, Class<E> eventType) {
    registrations.add(new Registration(subscriber, eventType));
  }

  public <E extends Event> void post(E event) {
    for (Registration r : registrations) {
      if (r.eventType.isInstance(event)) {
        //noinspection unchecked
        ((Subscriber<E>) r.subscriber).onEvent(event);
      }
    }
  }

  private record Registration(Subscriber<?> subscriber, Class<?> eventType) {}
}
