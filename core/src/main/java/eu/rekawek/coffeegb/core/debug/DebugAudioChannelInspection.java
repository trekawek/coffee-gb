package eu.rekawek.coffeegb.core.debug;

/** Detached state for one APU channel and its five-address NRx0-NRx4 register window. */
public record DebugAudioChannelInspection(
        int channel,
        boolean enabled,
        boolean dacEnabled,
        int output,
        int lengthCounter,
        boolean lengthEnabled,
        int nr0,
        int nr1,
        int nr2,
        int nr3,
        int nr4) {

    public DebugAudioChannelInspection {
        DebugValueChecks.range("channel", channel, 1, 4);
        DebugValueChecks.range("output", output, 0, 15);
        DebugValueChecks.range(
                "lengthCounter", lengthCounter, 0, channel == 3 ? 256 : 64);
        DebugValueChecks.unsignedByte("nr0", nr0);
        DebugValueChecks.unsignedByte("nr1", nr1);
        DebugValueChecks.unsignedByte("nr2", nr2);
        DebugValueChecks.unsignedByte("nr3", nr3);
        DebugValueChecks.unsignedByte("nr4", nr4);
        if (enabled && !dacEnabled) {
            throw new IllegalArgumentException("Enabled APU channel must have an enabled DAC");
        }
        if ((channel == 2 || channel == 4) && nr0 != 0) {
            throw new IllegalArgumentException("APU channels 2 and 4 do not expose NRx0");
        }
    }
}
