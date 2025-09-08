package eu.rekawek.coffeegb.joypad;

import eu.rekawek.coffeegb.events.Event;

public record ButtonReleaseEvent(Button button) implements Event {
}
