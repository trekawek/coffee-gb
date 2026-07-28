package eu.rekawek.coffeegb.swing.io;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.LineUnavailableException;
import java.util.List;

/** Java-Sound-free seam for deterministic device, fallback, and lifecycle tests. */
interface AudioBackend {

    List<AudioDeviceSnapshot> devices();

    AudioLine open(String stableId, AudioFormat format, int bufferBytes)
            throws LineUnavailableException;

    interface AudioLine extends AutoCloseable {
        void start();

        int write(byte[] bytes, int offset, int length);

        int available();

        int bufferSize();

        boolean isOpen();

        void flush();

        void stop();

        @Override
        void close();
    }
}
