package eu.rekawek.coffeegb.swing.io;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validated host-audio settings applied without mutating emulation state.
 *
 * <p>The balanced preset deliberately retains Coffee GB's historical 8192-byte line and
 * three-frame startup watermark. Runtime producer capacity is separately bounded so it can be
 * tuned without changing startup priming or line-buffer latency.
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
        /** Latency-first: one startup frame plus three controller frames of catch-up headroom. */
        LOW(2048, 1, 4),
        /** Historical default startup latency with nine transient scheduling-headroom frames. */
        BALANCED(8192, 3, 12),
        /** Conservative startup latency with the same nine-frame transient scheduling headroom. */
        SAFE(16384, 6, 15);

        private final int lineBufferBytes;
        private final int queuedFrames;
        private final int runtimeQueueCapacity;

        LatencyPreset(int lineBufferBytes, int queuedFrames, int runtimeQueueCapacity) {
            if (lineBufferBytes <= 0 || lineBufferBytes % 4 != 0) {
                throw new IllegalArgumentException(
                        "Audio line buffers must contain complete stereo frames");
            }
            if (queuedFrames <= 0) {
                throw new IllegalArgumentException("Audio startup watermark must be positive");
            }
            if (runtimeQueueCapacity < queuedFrames) {
                throw new IllegalArgumentException(
                        "Audio runtime queue capacity cannot be below its startup watermark");
            }
            this.lineBufferBytes = lineBufferBytes;
            this.queuedFrames = queuedFrames;
            this.runtimeQueueCapacity = runtimeQueueCapacity;
        }

        public int lineBufferBytes() {
            return lineBufferBytes;
        }

        public int queuedFrames() {
            return queuedFrames;
        }

        /**
         * Maximum real PCM frames retained while playback is running.
         *
         * <p>This does not change startup priming or line-buffer latency. BALANCED and SAFE retain
         * nine controller frames (about 150 ms) beyond their startup watermarks to absorb short
         * Windows Java Sound/JVM scheduling stalls; LOW intentionally retains only three.</p>
         */
        public int runtimeQueueCapacity() {
            return runtimeQueueCapacity;
        }
    }
}
