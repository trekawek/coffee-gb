package eu.rekawek.coffeegb.joypad;

import eu.rekawek.coffeegb.events.Event;

public record ButtonPressEvent(Button button) implements Event {
}
