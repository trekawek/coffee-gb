package eu.rekawek.coffeegb.android;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/** Android framework bridge kept outside the portable DSP and fixed PCM queue. */
final class AndroidAudioTrackOutput implements AndroidAudioSink.Output {

    static final class Factory implements AndroidAudioSink.OutputFactory {
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
            boolean hasDistinctPositiveNativeRate = nativeRate > 0
                    && nativeRate != 48_000
                    && nativeRate != 44_100;
            int[] candidates = new int[hasDistinctPositiveNativeRate ? 3 : 2];
            candidates[0] = 48_000;
            candidates[1] = 44_100;
            if (hasDistinctPositiveNativeRate) {
                candidates[2] = nativeRate;
            }
            return candidates;
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
                        .setBufferSizeInBytes(Math.max(minimum, sampleRate * 4 / 100))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build();
                if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                    track.release();
                    return null;
                }
                int actualRate = track.getSampleRate();
                int actualBuffer = Math.max(0, track.getBufferSizeInFrames()) * 4;
                int configured = Math.max(minimum, sampleRate * 4 / 100);
                return new AndroidAudioTrackOutput(track,
                        actualRate > 0 ? actualRate : sampleRate,
                        minimum, configured, actualBuffer);
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

    private AndroidAudioTrackOutput(AudioTrack track, int sampleRate, int minimumBufferBytes,
            int configuredBufferBytes, int actualBufferBytes) {
        this.track = track;
        this.sampleRate = sampleRate;
        this.minimumBufferBytes = minimumBufferBytes;
        this.configuredBufferBytes = configuredBufferBytes;
        this.actualBufferBytes = actualBufferBytes;
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
