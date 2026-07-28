package eu.rekawek.coffeegb.swing.io;

import java.util.Objects;

/** Immutable host-audio state suitable for logging or delivery to the Swing EDT. */
public record AudioOutputStatus(
        State state,
        String requestedDeviceId,
        String activeDeviceId,
        String detail) {

    public AudioOutputStatus {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(requestedDeviceId, "requestedDeviceId");
        Objects.requireNonNull(detail, "detail");
    }

    public enum State {
        STARTING,
        ACTIVE,
        FALLBACK,
        UNAVAILABLE,
        STOPPED
    }
}
