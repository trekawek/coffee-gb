package eu.rekawek.coffeegb.core.debug;

/** Detached divider and programmable-timer state. */
public record DebugTimerState(
        int dividerCounter,
        int tima,
        int tma,
        int tac,
        boolean overflowPending,
        int overflowDelayTicks) {

    public DebugTimerState {
        DebugValueChecks.unsignedWord("dividerCounter", dividerCounter);
        DebugValueChecks.unsignedByte("tima", tima);
        DebugValueChecks.unsignedByte("tma", tma);
        DebugValueChecks.unsignedByte("tac", tac);
        DebugValueChecks.nonNegative("overflowDelayTicks", overflowDelayTicks);
        if (!overflowPending && overflowDelayTicks != 0) {
            throw new IllegalArgumentException(
                    "A timer without a pending overflow cannot have an overflow delay");
        }
    }
}
