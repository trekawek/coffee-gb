package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.events.Event;
import eu.rekawek.coffeegb.memory.Ram;

public record VBlankEvent(Ram videoRam) implements Event {
}
