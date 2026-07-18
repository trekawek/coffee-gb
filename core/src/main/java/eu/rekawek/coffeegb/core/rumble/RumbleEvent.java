package eu.rekawek.coffeegb.core.rumble;

import eu.rekawek.coffeegb.core.events.Event;

/** A cartridge or pass-through accessory turning its vibration motor on or off. */
public record RumbleEvent(boolean on) implements Event {
}
