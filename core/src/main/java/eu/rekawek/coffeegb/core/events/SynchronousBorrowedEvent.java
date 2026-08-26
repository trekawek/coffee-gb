package eu.rekawek.coffeegb.core.events;

/**
 * Marks an event whose payload is borrowed from its producer for the duration of a synchronous
 * callback only.
 *
 * <p>Such an event must never be queued for asynchronous delivery or retained by a staged event
 * bus: the producer is allowed to reuse or mutate its payload as soon as the synchronous post
 * returns.</p>
 */
public interface SynchronousBorrowedEvent extends Event {
}
