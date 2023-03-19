package eu.rekawek.coffeegb.gui;

import com.google.common.base.Preconditions;
import eu.rekawek.coffeegb.Gameboy;
import eu.rekawek.coffeegb.sound.SoundOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

public class AudioSystemSoundOutput implements SoundOutput, Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(AudioSystemSoundOutput.class);

    private static final int SAMPLE_RATE = 22050;

    private static final int BUFFER_SIZE = 4096;

    private static final AudioFormat FORMAT = new AudioFormat(AudioFormat.Encoding.PCM_UNSIGNED, SAMPLE_RATE, 8, 2, 2, SAMPLE_RATE, false);

    private byte[] buffer;

    private byte[] copiedBuffer;

    private int pos;

    private int tick;

    private int divider;

    private volatile boolean doStop;

    private volatile boolean isPlaying;

    @Override
    public void run() {
        SourceDataLine line;
        try {
            line = AudioSystem.getSourceDataLine(FORMAT);
            line.open(FORMAT, BUFFER_SIZE);
        } catch (LineUnavailableException e) {
            throw new RuntimeException(e);
        }
        line.start();
        buffer = new byte[line.getBufferSize()];
        copiedBuffer = new byte[buffer.length];
        divider = (int) (Gameboy.TICKS_PER_SEC / FORMAT.getSampleRate());

        while (!doStop) {
            boolean isPlayingLocal = isPlaying;
            synchronized (this) {
                if (pos < copiedBuffer.length) {
                    LOG.warn("Sound buffer under-run: {}", pos);
                }
                for (int i = 0; i < buffer.length; i++) {
                    copiedBuffer[i] = isPlayingLocal ? buffer[i] : 0;
                }
                this.pos = 0;
                this.notify();
            }
            line.write(copiedBuffer, 0, copiedBuffer.length);
        }

        line.drain();
        line.stop();
    }

    public void stopThread() {
        doStop = true;
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
            tick %= divider;
            return;
        }

        Preconditions.checkArgument(left >= 0);
        Preconditions.checkArgument(left < 256);
        Preconditions.checkArgument(right >= 0);
        Preconditions.checkArgument(right < 256);

        synchronized (this) {
            while (buffer == null || pos >= buffer.length) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            buffer[pos] = (byte) (left);
            buffer[pos + 1] = (byte) (right);
            pos += 2;
        }
    }

}
