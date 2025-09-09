package eu.rekawek.coffeegb.core.joypad;

import eu.rekawek.coffeegb.core.events.Event;

public record ButtonReleaseEvent(Button button) implements Event {
}
