package eu.rekawek.coffeegb.core.joypad;

import eu.rekawek.coffeegb.core.events.Event;

import java.util.Objects;

/** Aggregate physical-input transition for one local SGB controller slot. */
public record LogicalPlayerButtonReleaseEvent(int player, Button button) implements Event {
    public LogicalPlayerButtonReleaseEvent {
        PlayerInputSnapshot.checkPlayer(player);
        Objects.requireNonNull(button, "button");
    }
}
