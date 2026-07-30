package eu.rekawek.coffeegb.core.debug;

/** Detached global APU and channel status. */
public record DebugApuState(
        boolean enabled,
        int frameSequencerStep,
        boolean channel1Enabled,
        boolean channel2Enabled,
        boolean channel3Enabled,
        boolean channel4Enabled,
        int nr50,
        int nr51,
        int nr52) {

    public DebugApuState {
        DebugValueChecks.range("frameSequencerStep", frameSequencerStep, -1, 7);
        DebugValueChecks.unsignedByte("nr50", nr50);
        DebugValueChecks.unsignedByte("nr51", nr51);
        DebugValueChecks.unsignedByte("nr52", nr52);
        if (!enabled && (channel1Enabled || channel2Enabled || channel3Enabled
                || channel4Enabled)) {
            throw new IllegalArgumentException("Disabled APU cannot have an enabled channel");
        }
    }
}
