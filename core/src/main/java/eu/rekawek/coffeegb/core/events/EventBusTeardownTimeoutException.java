package eu.rekawek.coffeegb.core.events;

/**
 * Indicates that an event-bus worker did not finish within the bounded close deadline.
 *
 * <p>The bus remains in its stopping state. Callers may retry {@link EventBus#close()} after the
 * subscriber that delayed teardown has returned.
 */
public class EventBusTeardownTimeoutException extends IllegalStateException {

    public EventBusTeardownTimeoutException(String message) {
        super(message);
    }
}
