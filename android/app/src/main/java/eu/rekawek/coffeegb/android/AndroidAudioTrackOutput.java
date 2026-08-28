package eu.rekawek.coffeegb.android;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

/** Android framework bridge kept outside the portable DSP and fixed PCM queue. */
final class AndroidAudioTrackOutput implements AndroidAudioSink.Output {

    static final class Factory implements AndroidAudioSink.OutputFactory {

        /** Five maximum packets of physical capacity; the start threshold is configured below. */
        static final int BUFFER_PACKETS = AndroidAudioSink.PRIMER_PACKETS + 1;

        @Override
        public AndroidAudioSink.Output open() {
            int nativeRate = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
            for (int sampleRate : candidateSampleRates(nativeRate)) {
                AndroidAudioTrackOutput output = tryOpen(sampleRate);
                if (output != null) {
                    return output;
                }
            }
            throw new IllegalStateException("No supported Android stereo PCM output rate");
        }

        /**
         * Prefers the rates used by the portable mixer before trying an OEM-only rate.
         *
         * <p>The PCM converter runs synchronously on the emulation thread. Some devices report a
         * 96/192 kHz native music rate, which needlessly multiplies that work when a standard
         * 48/44.1 kHz {@link AudioTrack} is available. Keep the native rate as a final fallback so
         * devices that reject both standard rates remain supported, while dropping invalid and
         * duplicate candidates before any framework call.</p>
         */
        static int[] candidateSampleRates(int nativeRate) {
            if (nativeRate == 44_100) {
                return new int[]{44_100, 48_000};
            }
            if (nativeRate == 48_000) {
                return new int[]{48_000, 44_100};
            }
            boolean hasDistinctPositiveNativeRate = nativeRate > 0
                    && nativeRate != 48_000 && nativeRate != 44_100;
            int[] candidates = new int[hasDistinctPositiveNativeRate ? 3 : 2];
            candidates[0] = 48_000;
            candidates[1] = 44_100;
            if (hasDistinctPositiveNativeRate) {
                candidates[2] = nativeRate;
            }
            return candidates;
        }

        static int packetBufferBytes(int sampleRate) {
            return Math.multiplyExact(BUFFER_PACKETS,
                    BoundedPcmQueue.maximumFrameBytes(sampleRate));
        }

        static int configuredBufferBytes(int sampleRate, int minimumBufferBytes) {
            if (minimumBufferBytes <= 0) {
                throw new IllegalArgumentException("Minimum audio buffer must be positive");
            }
            return Math.max(minimumBufferBytes, packetBufferBytes(sampleRate));
        }

        static boolean hasPacketCapacity(int sampleRate, int configuredBytes, int actualBytes) {
            int required = packetBufferBytes(sampleRate);
            return configuredBytes >= required && actualBytes >= required;
        }

        static boolean hasEffectivePrimerWindow(int sampleRate, int effectiveFrames,
                int startThresholdFrames) {
            int minimumEffective = BoundedPcmQueue.maximumOutputFramesForPackets(sampleRate,
                    AndroidAudioSink.PRIMER_PACKETS);
            int maximumThreshold = BoundedPcmQueue.minimumOutputFramesForPackets(sampleRate,
                    BoundedPcmQueue.DEFAULT_CAPACITY);
            return effectiveFrames >= minimumEffective && startThresholdFrames > 0
                    && startThresholdFrames <= effectiveFrames
                    && startThresholdFrames <= maximumThreshold;
        }

        static int startThresholdFrames(int sdkInt, int reportedFrames, int effectiveFrames) {
            if (effectiveFrames <= 0) {
                throw new IllegalArgumentException("Effective audio buffer must be positive");
            }
            if (sdkInt < Build.VERSION_CODES.S) {
                // Pre-S does not expose the device threshold. The documented portable refill is
                // the entire effective application-write buffer.
                return effectiveFrames;
            }
            if (reportedFrames <= 0 || reportedFrames > effectiveFrames) {
                throw new IllegalArgumentException("Invalid AudioTrack start threshold");
            }
            return reportedFrames;
        }

        private static AndroidAudioTrackOutput tryOpen(int sampleRate) {
            if (sampleRate <= 0) {
                return null;
            }
            int minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) {
                return null;
            }
            AudioTrack track = null;
            try {
                int configured = configuredBufferBytes(sampleRate, minimum);
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_GAME)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .build())
                        .setBufferSizeInBytes(configured)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build();
                if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                    track.release();
                    return null;
                }
                int actualRate = track.getSampleRate();
                int selectedRate = actualRate > 0 ? actualRate : sampleRate;
                int capacityFrames = Math.max(0, track.getBufferCapacityInFrames());
                int capacityBytes = Math.multiplyExact(capacityFrames, 4);
                if (!hasPacketCapacity(selectedRate, configured, capacityBytes)) {
                    track.release();
                    return null;
                }
                int requestedEffectiveFrames = Math.max((minimum + 3) / 4,
                        packetBufferBytes(selectedRate) / 4);
                int effectiveFrames = track.setBufferSizeInFrames(requestedEffectiveFrames);
                int desiredThresholdFrames = BoundedPcmQueue.minimumOutputFramesForPackets(
                        selectedRate, AndroidAudioSink.PRIMER_PACKETS);
                int reportedThreshold = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? track.setStartThresholdInFrames(desiredThresholdFrames) : effectiveFrames;
                int selectedStartThresholdFrames = startThresholdFrames(Build.VERSION.SDK_INT,
                        reportedThreshold, effectiveFrames);
                if (!hasEffectivePrimerWindow(selectedRate, effectiveFrames,
                        selectedStartThresholdFrames)) {
                    track.release();
                    return null;
                }
                return new AndroidAudioTrackOutput(track,
                        selectedRate,
                        minimum, configured, capacityBytes, capacityFrames, effectiveFrames,
                        selectedStartThresholdFrames);
            } catch (RuntimeException unavailable) {
                if (track != null) {
                    track.release();
                }
                return null;
            }
        }
    }

    private final AudioTrack track;
    private final int sampleRate;
    private final int minimumBufferBytes;
    private final int configuredBufferBytes;
    private final int actualBufferBytes;
    private final int bufferCapacityFrames;
    private final int effectiveBufferFrames;
    private final int startThresholdFrames;

    private AndroidAudioTrackOutput(AudioTrack track, int sampleRate, int minimumBufferBytes,
            int configuredBufferBytes, int actualBufferBytes, int bufferCapacityFrames,
            int effectiveBufferFrames, int startThresholdFrames) {
        this.track = track;
        this.sampleRate = sampleRate;
        this.minimumBufferBytes = minimumBufferBytes;
        this.configuredBufferBytes = configuredBufferBytes;
        this.actualBufferBytes = actualBufferBytes;
        this.bufferCapacityFrames = bufferCapacityFrames;
        this.effectiveBufferFrames = effectiveBufferFrames;
        this.startThresholdFrames = startThresholdFrames;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public AndroidAudioSink.AudioStats audioStats() {
        return new AndroidAudioSink.AudioStats(sampleRate, minimumBufferBytes,
                configuredBufferBytes, actualBufferBytes);
    }

    @Override
    public int bufferCapacityFrames() {
        return bufferCapacityFrames;
    }

    @Override
    public int effectiveBufferFrames() {
        return effectiveBufferFrames;
    }

    @Override
    public int startThresholdFrames() {
        return startThresholdFrames;
    }

    @Override
    public long playbackPositionFrames() {
        return track.getPlaybackHeadPosition() & 0xffffffffL;
    }

    @Override
    public long outputUnderrunCount() {
        return track.getUnderrunCount();
    }

    @Override
    public boolean isPlaying() {
        return track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
    }

    @Override
    public void play() {
        track.play();
    }

    @Override
    public void pause() {
        track.pause();
    }

    @Override
    public void flush() {
        track.flush();
    }

    @Override
    public int write(byte[] bytes, int offset, int length) {
        return track.write(bytes, offset, length, AudioTrack.WRITE_BLOCKING);
    }

    @Override
    public void release() {
        track.release();
    }
}
