package eu.rekawek.coffeegb.core.memory.cart.battery;

import eu.rekawek.coffeegb.core.events.Event;

/**
 * User-facing persistence failure signal. It contains only the save filename and sanitized
 * diagnostics, never ROM, RAM, RTC, or save-file contents.
 */
public record BatteryPersistenceFailedEvent(
        Operation operation,
        String fileName,
        String message) implements Event {

    public enum Operation {
        LOAD,
        SAVE
    }
}
