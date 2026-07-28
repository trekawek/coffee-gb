package eu.rekawek.coffeegb.swing.io;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validated host-audio settings applied without mutating emulation state.
 *
 * <p>The balanced preset deliberately retains Coffee GB's historical 8192-byte line and
 * three-frame producer queue. Every line buffer is stereo-frame aligned and every queue stays
 * bounded so a slow host cannot create ever-growing latency.
 */
public record AudioRuntimeConfiguration(
        String outputDeviceId,
        int masterVolume,
        boolean muted,
        LatencyPreset latencyPreset) {

    private static final Pattern EXPLICIT_DEVICE_ID =
            Pattern.compile("java-sound-[0-9a-f]{64}");

    public AudioRuntimeConfiguration {
        Objects.requireNonNull(outputDeviceId, "outputDeviceId");
        Objects.requireNonNull(latencyPreset, "latencyPreset");
        if (!AudioDeviceSnapshot.SYSTEM_DEFAULT_ID.equals(outputDeviceId)
                && !isExplicitDeviceId(outputDeviceId)) {
            throw new IllegalArgumentException(
                    "Audio output must be default or java-sound- followed by 64 lowercase hex digits");
        }
        if (masterVolume < 0 || masterVolume > 100) {
            throw new IllegalArgumentException("Master volume must be between 0 and 100");
        }
    }

    public static AudioRuntimeConfiguration defaults() {
        return defaults(true);
    }

    public static AudioRuntimeConfiguration defaults(boolean enabled) {
        return new AudioRuntimeConfiguration(
                AudioDeviceSnapshot.SYSTEM_DEFAULT_ID,
                100,
                !enabled,
                LatencyPreset.BALANCED);
    }

    public AudioRuntimeConfiguration withMuted(boolean nextMuted) {
        return new AudioRuntimeConfiguration(
                outputDeviceId, masterVolume, nextMuted, latencyPreset);
    }

    static boolean isExplicitDeviceId(String value) {
        return value != null && EXPLICIT_DEVICE_ID.matcher(value).matches();
    }

    public enum LatencyPreset {
        LOW(2048, 1),
        BALANCED(8192, 3),
        SAFE(16384, 6);

        private final int lineBufferBytes;
        private final int queuedFrames;

        LatencyPreset(int lineBufferBytes, int queuedFrames) {
            if (lineBufferBytes <= 0 || lineBufferBytes % 4 != 0) {
                throw new IllegalArgumentException(
                        "Audio line buffers must contain complete stereo frames");
            }
            if (queuedFrames <= 0) {
                throw new IllegalArgumentException("Audio queue capacity must be positive");
            }
            this.lineBufferBytes = lineBufferBytes;
            this.queuedFrames = queuedFrames;
        }

        public int lineBufferBytes() {
            return lineBufferBytes;
        }

        public int queuedFrames() {
            return queuedFrames;
        }
    }
}
