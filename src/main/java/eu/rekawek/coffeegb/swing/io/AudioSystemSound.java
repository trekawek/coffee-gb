package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.Gameboy;
import eu.rekawek.coffeegb.events.EventBus;
import eu.rekawek.coffeegb.sound.Sound;
import eu.rekawek.coffeegb.swing.gui.properties.SoundProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.Arrays;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

public class AudioSystemSound implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(AudioSystemSound.class);
    private static final int SAMPLE_RATE = 44100;
    private static final int BUFFER_SIZE = 4096;
    private static final AudioFormat FORMAT = new AudioFormat(AudioFormat.Encoding.PCM_UNSIGNED, SAMPLE_RATE, 8, 2, 2, SAMPLE_RATE, false);

    private static final int DIVIDER = (int) (Gameboy.TICKS_PER_SEC / FORMAT.getSampleRate());
    private volatile boolean doStop;
    private volatile boolean isStopped;
    private volatile boolean enabled;

    private BlockingDeque<int[]> bufferDequeue = new LinkedBlockingDeque<>(10);

    public AudioSystemSound(SoundProperties properties, EventBus eventBus, String callerId) {
        enabled = properties.getSoundEnabled();
        eventBus.register(this::play, Sound.SoundSampleEvent.class, callerId);
        eventBus.register(e -> {
            this.enabled = e.enabled();
        }, Sound.SoundEnabledEvent.class);
    }

    @Override
    public void run() {
        while (bufferDequeue.isEmpty()) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
            }
        }

        byte[] finalBuffer = new byte[Gameboy.TICKS_PER_FRAME * 2 / DIVIDER + 1];

        doStop = false;
        isStopped = false;
        SourceDataLine line;
        try {
            line = AudioSystem.getSourceDataLine(FORMAT);
            line.open(FORMAT, BUFFER_SIZE / 2);
        } catch (LineUnavailableException e) {
            throw new RuntimeException(e);
        }
        line.start();
        while (!doStop) {
            int[] buffer;
            try {
                buffer = bufferDequeue.pollFirst(1, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (buffer != null && enabled) {
                for (int i = 0, j = 0; i < buffer.length; i++) {
                    if (i % DIVIDER == 0) {
                        finalBuffer[j++] = (byte) buffer[i];
                    }
                }
            } else {
                Arrays.fill(finalBuffer, (byte) 0);
            }
            line.write(finalBuffer, 0, finalBuffer.length);
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

    private void play(Sound.SoundSampleEvent event) {
        if (!bufferDequeue.offerLast(event.buffer().clone())) {
            LOG.atInfo().log("Buffer overflow");
        }
    }
}
