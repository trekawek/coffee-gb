package eu.rekawek.coffeegb.events;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class EventBus {

  private final List<Registration> registrations = new CopyOnWriteArrayList<>();
  private final EventBus parent;
  private final List<EventBus> children = new CopyOnWriteArrayList<>();
  private volatile boolean stopped;

  public EventBus() {
    this.parent = null;
  }

  public EventBus(EventBus parent) {
    this.parent = parent;
  }

  public <E extends Event> void register(Subscriber<E> subscriber, Class<E> eventType) {
    if (stopped) {
      throw new IllegalStateException("This EventBus is no longer active.");
    }
    registrations.add(new Registration(subscriber, eventType));
  }

  public <E extends Event> void post(E event) {
    // all ancestors
    EventBus ancestor = parent;
    while (ancestor != null) {
      ancestor.doPost(event);
      ancestor = ancestor.parent;
    }

    // this event bus
    doPost(event);

    // all children
    for (EventBus c : children) {
      c.doPost(event);
    }

    // not siblings, nieces, uncles, etc.
  }

  public <E extends Event> void doPost(E event) {
    if (stopped) {
      throw new IllegalStateException("This EventBus is no longer active.");
    }
    for (Registration r : registrations) {
      if (r.eventType.isInstance(event)) {
        //noinspection unchecked
        ((Subscriber<E>) r.subscriber).onEvent(event);
      }
    }
  }

  @NotNull
  public EventBus fork() {
    EventBus child = new EventBus(this);
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

  private record Registration(Subscriber<?> subscriber, Class<?> eventType) {}
}
