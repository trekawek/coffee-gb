package eu.rekawek.coffeegb.controller;

import eu.rekawek.coffeegb.events.Event;

public record ButtonPressEvent(Button button) implements Event {

}
