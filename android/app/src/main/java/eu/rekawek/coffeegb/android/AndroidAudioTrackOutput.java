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
            for (int sampleRate : new int[]{nativeRate, 48_000, 44_100}) {
                AndroidAudioTrackOutput output = tryOpen(sampleRate);
                if (output != null) {
                    return output;
                }
            }
            throw new IllegalStateException("No supported Android stereo PCM output rate");
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
                return new AndroidAudioTrackOutput(track,
                        actualRate > 0 ? actualRate : sampleRate);
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

    private AndroidAudioTrackOutput(AudioTrack track, int sampleRate) {
        this.track = track;
        this.sampleRate = sampleRate;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
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
