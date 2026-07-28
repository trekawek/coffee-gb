package eu.rekawek.coffeegb.swing.io;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Real Java Sound adapter. Mixer discovery and line ownership stay outside Swing components. */
final class JavaSoundAudioBackend implements AudioBackend {

    @Override
    public List<AudioDeviceSnapshot> devices() {
        DataLine.Info lineInfo =
                new DataLine.Info(SourceDataLine.class, AudioSystemSound.outputFormat());
        Map<String, AudioDeviceSnapshot> devices = new LinkedHashMap<>();
        devices.put(
                AudioDeviceSnapshot.SYSTEM_DEFAULT_ID,
                AudioDeviceSnapshot.systemDefaultDevice());
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            Mixer mixer;
            try {
                mixer = AudioSystem.getMixer(info);
            } catch (IllegalArgumentException | SecurityException unavailable) {
                continue;
            }
            if (!mixer.isLineSupported(lineInfo)) {
                continue;
            }
            String stableId = stableId(
                    info.getName(),
                    info.getVendor(),
                    info.getDescription(),
                    info.getVersion());
            devices.putIfAbsent(
                    stableId,
                    new AudioDeviceSnapshot(stableId, displayName(info), false));
        }
        return List.copyOf(devices.values());
    }

    @Override
    public AudioLine open(String stableId, AudioFormat format, int bufferBytes)
            throws LineUnavailableException {
        SourceDataLine line;
        if (AudioDeviceSnapshot.SYSTEM_DEFAULT_ID.equals(stableId)) {
            line = AudioSystem.getSourceDataLine(format);
        } else {
            Mixer.Info selected = findMixer(stableId, format);
            if (selected == null) {
                throw unavailable("Configured audio output is not available: " + stableId, null);
            }
            Mixer mixer;
            try {
                mixer = AudioSystem.getMixer(selected);
            } catch (IllegalArgumentException | SecurityException failure) {
                throw unavailable("Configured audio output cannot be accessed: " + stableId, failure);
            }
            Line candidate;
            try {
                candidate = mixer.getLine(new DataLine.Info(SourceDataLine.class, format));
            } catch (IllegalArgumentException failure) {
                throw unavailable("Configured audio output does not support Coffee GB PCM", failure);
            }
            if (!(candidate instanceof SourceDataLine)) {
                candidate.close();
                throw unavailable("Configured audio output did not provide a source data line", null);
            }
            line = (SourceDataLine) candidate;
        }

        try {
            line.open(format, bufferBytes);
            return new JavaSoundLine(line);
        } catch (LineUnavailableException | RuntimeException failure) {
            line.close();
            if (failure instanceof LineUnavailableException) {
                throw (LineUnavailableException) failure;
            }
            throw unavailable("Audio output could not be opened", failure);
        }
    }

    private static Mixer.Info findMixer(String stableId, AudioFormat format) {
        DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, format);
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (!stableId.equals(stableId(
                    info.getName(),
                    info.getVendor(),
                    info.getDescription(),
                    info.getVersion()))) {
                continue;
            }
            try {
                if (AudioSystem.getMixer(info).isLineSupported(lineInfo)) {
                    return info;
                }
            } catch (IllegalArgumentException | SecurityException ignored) {
                // Device disappeared between enumeration and opening.
            }
        }
        return null;
    }

    static String stableId(String name, String vendor, String description, String version) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> fields = new ArrayList<>();
            fields.add(nullToEmpty(name));
            fields.add(nullToEmpty(vendor));
            fields.add(nullToEmpty(description));
            fields.add(nullToEmpty(version));
            byte[] bytes = String.join("\0", fields).getBytes(StandardCharsets.UTF_8);
            return "java-sound-" + lowercaseHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String displayName(Mixer.Info info) {
        String name = nullToEmpty(info.getName()).trim();
        String vendor = nullToEmpty(info.getVendor()).trim();
        if (name.isEmpty()) {
            name = "Audio Output";
        }
        return vendor.isEmpty() || vendor.equalsIgnoreCase(name)
                ? name
                : name + " — " + vendor;
    }

    private static String lowercaseHex(byte[] bytes) {
        char[] encoded = new char[Math.multiplyExact(bytes.length, 2)];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            encoded[i * 2] = Character.forDigit(value >>> 4, 16);
            encoded[i * 2 + 1] = Character.forDigit(value & 0x0f, 16);
        }
        return new String(encoded);
    }

    private static LineUnavailableException unavailable(String message, Throwable cause) {
        LineUnavailableException failure = new LineUnavailableException(message);
        if (cause != null) {
            failure.initCause(cause);
        }
        return failure;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record JavaSoundLine(SourceDataLine line) implements AudioLine {
        @Override
        public void start() {
            line.start();
        }

        @Override
        public int write(byte[] bytes, int offset, int length) {
            return line.write(bytes, offset, length);
        }

        @Override
        public int available() {
            return line.available();
        }

        @Override
        public int bufferSize() {
            return line.getBufferSize();
        }

        @Override
        public boolean isOpen() {
            return line.isOpen();
        }

        @Override
        public void flush() {
            line.flush();
        }

        @Override
        public void stop() {
            line.stop();
        }

        @Override
        public void close() {
            line.close();
        }
    }
}
