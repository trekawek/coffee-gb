package eu.rekawek.coffeegb.gui;

import eu.rekawek.coffeegb.Gameboy;
import eu.rekawek.coffeegb.sound.SoundOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import static com.google.common.base.Preconditions.checkArgument;

public class JavaSoundOutput implements SoundOutput {

    private static final Logger LOG = LoggerFactory.getLogger(JavaSoundOutput.class);

    private static final int SAMPLE_RATE = 44010;

    private static final AudioFormat FORMAT = new AudioFormat(AudioFormat.Encoding.PCM_UNSIGNED, SAMPLE_RATE, 8, 2, 2, SAMPLE_RATE, false);

    private SourceDataLine line;

    private byte[] buffer;

    private int i;

    private int tick;

    private int divider;

    @Override
    public void start() {
        if (line != null) {
            LOG.info("Sound already started");
            return;
        }
        LOG.info("Start sound");
        try {
            line = AudioSystem.getSourceDataLine(FORMAT);
            line.open(FORMAT, 4096);
        } catch (LineUnavailableException e) {
            throw new RuntimeException(e);
        }
        line.start();
        buffer = new byte[line.getBufferSize()];
        divider = (int) (Gameboy.TICKS_PER_SEC / FORMAT.getSampleRate());
    }

    @Override
    public void stop() {
        if (line == null) {
            LOG.info("Can't stop - sound wasn't started");
        }
        LOG.info("Stop sound");
        line.drain();
        line.stop();
        line = null;
    }

    @Override
    public void play(int left, int right) {
        if (tick++ != 0) {
            tick %= divider;
            return;
        }

        checkArgument(left >= 0 && left < 256);
        checkArgument(right >= 0 && right < 256);

        buffer[i++] = (byte) (left);
        buffer[i++] = (byte) (right);
        if (i > 2048) {
            line.write(buffer, 0, i);
            i = 0;
        }
    }
}
