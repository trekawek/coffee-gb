package eu.rekawek.coffeegb.core.joypad;

/**
 * Platform-neutral source of physical input for the four logical SGB controller slots.
 *
 * <p>The emulator samples this service at a Joypad clock boundary. Implementations must return
 * detached immutable values and must never expose desktop/device objects to the core. Physical
 * input is deliberately not machine state: restoring or rewinding the machine keeps the input
 * that is physically held at the time of the restore.
 */
@FunctionalInterface
public interface PlayerInputSource {

    int PLAYER_COUNT = 4;

    PlayerInputSource RELEASED = () -> PlayerInputSnapshot.RELEASED;

    /** Returns one immutable, deeply owned sample for all slots P1 through P4. */
    PlayerInputSnapshot sample();
}
