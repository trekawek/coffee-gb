package eu.rekawek.coffeegb.core.genie;

import eu.rekawek.coffeegb.core.events.Event;

import java.util.List;

public record AddPatches(List<Patch> patches) implements Event {
}
