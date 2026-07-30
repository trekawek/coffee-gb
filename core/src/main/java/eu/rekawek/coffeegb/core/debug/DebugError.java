package eu.rekawek.coffeegb.core.debug;

import java.util.Objects;

/** Immutable failure description suitable for UI, console, and headless clients. */
public record DebugError(DebugErrorCode code, String message) {

    public DebugError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("Debug error message must not be blank");
        }
    }
}
