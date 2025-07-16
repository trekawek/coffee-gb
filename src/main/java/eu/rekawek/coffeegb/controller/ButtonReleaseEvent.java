package eu.rekawek.coffeegb.controller;

import eu.rekawek.coffeegb.events.Event;

public record ButtonReleaseEvent(Button button) implements Event {
}
