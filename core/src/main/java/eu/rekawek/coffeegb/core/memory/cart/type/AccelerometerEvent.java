package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.events.Event;

public record AccelerometerEvent(double x, double y) implements Event {
}
