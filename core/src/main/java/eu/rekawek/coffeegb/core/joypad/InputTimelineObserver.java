package eu.rekawek.coffeegb.core.joypad;

/**
 * Observes source-local joypad transitions at their emulation-visible boundary.
 *
 * <p>The observer is an owner-thread service and is deliberately absent from portable machine
 * state. Masks use the stable layout defined by {@link JoypadButtonMask}; they never depend on
 * {@link Button#ordinal()}.</p>
 */
@FunctionalInterface
public interface InputTimelineObserver {

    /** The boundary at which a source transition becomes part of the input timeline. */
    enum Phase {
        /** Historical P1 events, visible to the CPU before the next emulator tick. */
        LEGACY_P1_BEFORE_TICK,

        /** Physical P1-P4 input latched while the joypad peripheral is ticked. */
        PHYSICAL_JOYPAD_SAMPLE
    }

    /**
     * Reports one source-local transition after it has been applied.
     *
     * @param phase emulation boundary of the transition
     * @param player zero-based logical player; legacy events always use player zero
     * @param buttonMask absolute eight-bit source-local state after the transition
     * @param changedMask eight-bit XOR of the previous and new source-local states
     */
    void onInputChanged(Phase phase, int player, int buttonMask, int changedMask);
}
