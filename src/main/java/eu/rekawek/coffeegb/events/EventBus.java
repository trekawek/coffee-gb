package eu.rekawek.coffeegb.events;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class EventBus {

  private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

  public void register(Subscriber subscriber) {
    subscribers.add(subscriber);
  }

  public void post(Event event) {
    for (Subscriber s : subscribers) {
      s.onEvent(event);
    }
  }
}
