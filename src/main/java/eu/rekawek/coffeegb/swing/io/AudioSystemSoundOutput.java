package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.Gameboy;
import eu.rekawek.coffeegb.sound.SoundOutput;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.Arrays;

public class AudioSystemSoundOutput implements SoundOutput, Runnable {

    private static final int SAMPLE_RATE = 44100;

    private static final int BUFFER_SIZE = 4096;

    private static final AudioFormat FORMAT = new AudioFormat(AudioFormat.Encoding.PCM_UNSIGNED, SAMPLE_RATE, 8, 2, 2, SAMPLE_RATE, false);

    private static final int DIVIDER = (int) (Gameboy.TICKS_PER_SEC / FORMAT.getSampleRate());

    private byte[] buffer = new byte[BUFFER_SIZE];

    private byte[] copiedBuffer = new byte[BUFFER_SIZE];

    private volatile boolean enabled = true;

    private volatile int pos;

    private int tick;

    private volatile boolean doStop;

    private volatile boolean isStopped;

    private volatile boolean isPlaying;

    public AudioSystemSoundOutput() {
    }

    @Override
    public void run() {
        pos = 0;
        tick = 0;
        doStop = false;
        isStopped = false;
        while (pos < BUFFER_SIZE && !doStop) {
        }
        SourceDataLine line;
        try {
            line = AudioSystem.getSourceDataLine(FORMAT);
            line.open(FORMAT, BUFFER_SIZE);
        } catch (LineUnavailableException e) {
            throw new RuntimeException(e);
        }
        line.start();
        while (!doStop) {
            if (pos < BUFFER_SIZE && isPlaying) {
                while (pos < BUFFER_SIZE && !doStop) {
                }
                if (doStop) {
                    break;
                }
            }
            byte[] tmp = copiedBuffer;
            copiedBuffer = buffer;
            buffer = tmp;
            pos = 0;
            if (!isPlaying || !enabled) {
                Arrays.fill(copiedBuffer, (byte) 0);
            }
            line.write(copiedBuffer, 0, BUFFER_SIZE);
        }
        line.drain();
        line.stop();
        isStopped = true;
    }

    public void stopThread() {
        doStop = true;
        while (!isStopped) {
        }
    }

    @Override
    public void start() {
        isPlaying = true;
    }

    @Override
    public void stop() {
        isPlaying = false;
    }

    @Override
    public void play(int left, int right) {
        if (tick++ != 0) {
            tick %= DIVIDER;
            return;
        }

        while (pos >= BUFFER_SIZE) {
        }

        buffer[pos] = (byte) (left);
        buffer[pos + 1] = (byte) (right);
        pos += 2;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
