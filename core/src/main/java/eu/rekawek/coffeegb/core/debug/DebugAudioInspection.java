package eu.rekawek.coffeegb.core.debug;

import java.util.List;
import java.util.Objects;

/** Detached APU sequencer, mixer, channel, and wave-RAM state. */
public final class DebugAudioInspection {

    public static final int WAVE_RAM_LENGTH = 0x10;

    private final boolean enabled;

    private final int frameSequencerStep;

    private final int nr50;

    private final int nr51;

    private final int nr52;

    private final List<DebugAudioChannelInspection> channels;

    private final DebugByteData waveRam;

    public DebugAudioInspection(
            boolean enabled,
            int frameSequencerStep,
            int nr50,
            int nr51,
            int nr52,
            List<DebugAudioChannelInspection> channels,
            DebugByteData waveRam) {
        this.enabled = enabled;
        DebugValueChecks.range("frameSequencerStep", frameSequencerStep, -1, 7);
        this.frameSequencerStep = frameSequencerStep;
        DebugValueChecks.unsignedByte("nr50", nr50);
        DebugValueChecks.unsignedByte("nr51", nr51);
        DebugValueChecks.unsignedByte("nr52", nr52);
        this.nr50 = nr50;
        this.nr51 = nr51;
        this.nr52 = nr52;
        Objects.requireNonNull(channels, "channels");
        this.channels = List.copyOf(channels);
        if (this.channels.size() != 4) {
            throw new IllegalArgumentException("Audio inspection must contain four channels");
        }
        for (int i = 0; i < this.channels.size(); i++) {
            DebugAudioChannelInspection channel = Objects.requireNonNull(
                    this.channels.get(i), "channels contains null");
            if (channel.channel() != i + 1) {
                throw new IllegalArgumentException("Audio channels must be ordered 1 through 4");
            }
            if (!enabled && channel.enabled()) {
                throw new IllegalArgumentException("Disabled APU cannot have an enabled channel");
            }
        }
        Objects.requireNonNull(waveRam, "waveRam");
        if (waveRam.length() != WAVE_RAM_LENGTH) {
            throw new IllegalArgumentException(
                    "waveRam must contain exactly " + WAVE_RAM_LENGTH + " bytes");
        }
        this.waveRam = waveRam;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Next frame-sequencer step, or -1 when the implementation cannot expose it. */
    public int frameSequencerStep() {
        return frameSequencerStep;
    }

    public int nr50() {
        return nr50;
    }

    public int nr51() {
        return nr51;
    }

    public int nr52() {
        return nr52;
    }

    public List<DebugAudioChannelInspection> channels() {
        return channels;
    }

    public DebugByteData waveRam() {
        return waveRam;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof DebugAudioInspection that)) return false;
        return enabled == that.enabled
                && frameSequencerStep == that.frameSequencerStep
                && nr50 == that.nr50
                && nr51 == that.nr51
                && nr52 == that.nr52
                && channels.equals(that.channels)
                && waveRam.equals(that.waveRam);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, frameSequencerStep, nr50, nr51, nr52, channels, waveRam);
    }

    @Override
    public String toString() {
        return "DebugAudioInspection[enabled=" + enabled
                + ", frameSequencerStep=" + frameSequencerStep + "]";
    }
}
