package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.sound.Sound;
import eu.rekawek.coffeegb.controller.properties.SoundProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Plays the emulator audio through javax.sound. The per-tick sample buffer posted by the
 * core once per frame is decimated to the output rate directly in the event handler (the
 * event bus dispatches synchronously on the emulator thread, so the shared buffer can be
 * read without copying it). A fractional resampling step keeps the produced rate exactly
 * at the line's sample rate - an integer divider would run 0.1% fast and slowly fill any
 * queue, ending in a rising latency followed by periodic overflow glitches.
 */
public class AudioSystemSound implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(AudioSystemSound.class);

    private static final int SAMPLE_RATE = 44100;

    private static final AudioFormat FORMAT =
            new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, SAMPLE_RATE, 16, 2, 4, SAMPLE_RATE, false);

    /**
     * The mixer outputs signed DAC-modelled samples of at most 4 channels * ±15 * volume 8
     * = ±480 per side; scale to leave a little 16-bit headroom.
     */
    private static final int VOLUME_SCALE = 62;

    /**
     * The console's output capacitor has a cutoff of about 28 Hz. It removes the
     * baseline steps caused by channel routing while preserving master-volume PCM.
     */
    private static final double HIGHPASS_CUTOFF = 28.0;

    private static final double TICKS_PER_SAMPLE = (double) Gameboy.TICKS_PER_SEC / SAMPLE_RATE;

    /**
     * Samples produced by one frame's worth of ticks, rounded up.
     */
    private static final int MAX_FRAME_SAMPLES = Gameboy.TICKS_PER_FRAME / 95 + 2;

    /**
     * Line buffer of about 45 ms; the frame queue adds at most three frames (50 ms).
     */
    private static final int LINE_BUFFER_BYTES = 8192;

    private volatile boolean doStop;
    private volatile boolean isStopped;
    private volatile boolean enabled;

    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(3);

    private double resamplePos;

    private long sumL, sumR, prevSumL, prevSumR;

    private int cnt, prevCnt;

    private final DcBlocker dcBlockerL = new DcBlocker(SAMPLE_RATE, HIGHPASS_CUTOFF);

    private final DcBlocker dcBlockerR = new DcBlocker(SAMPLE_RATE, HIGHPASS_CUTOFF);

    public AudioSystemSound(SoundProperties properties, EventBus eventBus, String callerId) {
        enabled = properties.getSoundEnabled();
        eventBus.register(this::play, Sound.SoundSampleEvent.class, callerId);
        eventBus.register(e -> this.enabled = e.enabled(), Sound.SoundEnabledEvent.class);
    }

    @Override
    public void run() {
        doStop = false;
        isStopped = false;

        SourceDataLine line;
        try {
            line = AudioSystem.getSourceDataLine(FORMAT);
            line.open(FORMAT, LINE_BUFFER_BYTES);
        } catch (LineUnavailableException e) {
            LOG.error("Cannot open the audio line", e);
            isStopped = true;
            return;
        }
        line.start();
        byte[] silence = new byte[MAX_FRAME_SAMPLES * 4];
        while (!doStop) {
            byte[] buffer;
            try {
                buffer = queue.poll(20, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (buffer != null) {
                line.write(buffer, 0, buffer.length);
            } else if (line.available() >= line.getBufferSize() - 4) {
                // nothing is playing and the line has drained: keep it warm with silence
                // instead of letting it underrun (a transiently late producer does not
                // cause a silence gap this way)
                line.write(silence, 0, silence.length);
            }
        }
        line.stop();
        line.flush();
        isStopped = true;
        synchronized (this) {
            notifyAll();
        }
    }

    public void stopThread() {
        doStop = true;
        synchronized (this) {
            while (!isStopped) {
                try {
                    wait(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private void play(Sound.SoundSampleEvent event) {
        int[] source = event.buffer();
        int ticks = source.length / 2;

        byte[] out = new byte[MAX_FRAME_SAMPLES * 4];
        int j = 0;
        // moving average over two output periods (a width-2 box filter): near-Nyquist
        // content is attenuated ~38 dB, so games parking a channel on an ultrasonic
        // frequency stay inaudible like on hardware instead of aliasing into a buzz
        // (Pit-Fighter, issue #59), at the cost of ~3 dB roll-off at 10 kHz
        for (int t = 0; t < ticks; t++) {
            sumL += source[t * 2];
            sumR += source[t * 2 + 1];
            cnt++;
            if (++resamplePos >= TICKS_PER_SAMPLE) {
                resamplePos -= TICKS_PER_SAMPLE;
                int total = prevCnt + cnt;
                double rawL = (double) (prevSumL + sumL) / total;
                double rawR = (double) (prevSumR + sumR) / total;
                double filteredL = dcBlockerL.filter(rawL);
                double filteredR = dcBlockerR.filter(rawR);
                int left = enabled ? (int) (filteredL * VOLUME_SCALE) : 0;
                int right = enabled ? (int) (filteredR * VOLUME_SCALE) : 0;
                out[j++] = (byte) left;
                out[j++] = (byte) (left >> 8);
                out[j++] = (byte) right;
                out[j++] = (byte) (right >> 8);
                prevSumL = sumL;
                prevSumR = sumR;
                prevCnt = cnt;
                sumL = 0;
                sumR = 0;
                cnt = 0;
            }
        }

        byte[] trimmed = new byte[j];
        System.arraycopy(out, 0, trimmed, 0, j);
        if (!queue.offer(trimmed)) {
            // the writer is behind; drop the oldest frame to keep the latency bounded
            queue.poll();
            queue.offer(trimmed);
        }
    }
}
