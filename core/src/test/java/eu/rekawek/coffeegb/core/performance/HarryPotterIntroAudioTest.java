package eu.rekawek.coffeegb.core.performance;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.core.sound.Sound;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent;
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent;
import org.junit.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;

/**
 * Deterministic audio-content probe for the Harry Potter intro after its company logos.
 *
 * <p>The probe consumes the core's synchronous {@link Sound.SoundSampleEvent} stream instead
 * of opening a host audio device. This makes it suitable for comparing emulator commits and
 * detects changes in the generated audio independently of wall-clock FPS.</p>
 */
public class HarryPotterIntroAudioTest {

    private static final int WARMUP_FRAMES = 1_200;

    private static final int MEASUREMENT_FRAMES = 600;

    @Test
    public void measuresIntroAudio() throws Exception {
        File romFile = HarryPotterIntroHarness.requireRom();

        Gameboy.GameboyConfiguration configuration = new Gameboy.GameboyConfiguration(new Rom(romFile))
                .setBootstrapMode(Gameboy.BootstrapMode.NORMAL)
                .setBatteryData(HarryPotterIntroHarness.loadBatteryData())
                .setSupportBatterySave(false);

        int warmupFrames = Integer.getInteger("harryPotterAudioWarmupFrames", WARMUP_FRAMES);
        int measurementFrames = Integer.getInteger("harryPotterAudioMeasurementFrames", MEASUREMENT_FRAMES);

        try (EventBus eventBus = new EventBusImpl(); Gameboy gameboy = configuration.build()) {
            AudioCapture capture = new AudioCapture(warmupFrames, measurementFrames);
            eventBus.register(event -> capture.accept(event.buffer()), Sound.SoundSampleEvent.class);
            gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
            gameboy.requestFrameRenderSuppression(HarryPotterIntroHarness.forceFrameSkip());

            if (Boolean.getBoolean("harryPotterAudioPressStart")) {
                eventBus.post(new ButtonPressEvent(Button.START));
                for (int i = 0; i < Gameboy.TICKS_PER_FRAME * 10; i++) {
                    gameboy.tick();
                }
                eventBus.post(new ButtonReleaseEvent(Button.START));
            }

            while (!capture.isComplete()) {
                gameboy.tick();
            }
            capture.printReport();
            if (HarryPotterIntroHarness.forceFrameSkip()) {
                System.out.println("Forced frame suppression: enabled");
            }
        }
    }

    private static final class AudioCapture {
        private final int warmupFrames;
        private final int measurementFrames;
        private final MessageDigest digest;
        private final byte[] digestBytes = new byte[Gameboy.TICKS_PER_FRAME * 2 * Integer.BYTES];

        private int receivedFrames;
        private int capturedFrames;
        private long samplePairs;
        private long quietSamplePairs;
        private long zeroSamplePairs;
        private int quietRunFrames;
        private int maxQuietRunFrames;
        private int firstNonZeroFrame = -1;
        private double minRms = Double.POSITIVE_INFINITY;
        private double maxRms;

        private AudioCapture(int warmupFrames, int measurementFrames) throws Exception {
            this.warmupFrames = warmupFrames;
            this.measurementFrames = measurementFrames;
            this.digest = MessageDigest.getInstance("SHA-256");
        }

        private void accept(int[] source) {
            int frame = receivedFrames++;
            if (frame < warmupFrames || frame >= warmupFrames + measurementFrames) {
                if (firstNonZeroFrame < 0 && containsNonZero(source)) {
                    firstNonZeroFrame = frame;
                }
                return;
            }

            ByteBuffer.wrap(digestBytes)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .asIntBuffer()
                    .put(source);
            digest.update(digestBytes);

            long sumSquares = 0;
            int quietPairs = 0;
            int zeroPairs = 0;
            int pairs = source.length / 2;
            for (int i = 0; i < source.length; i += 2) {
                int left = source[i];
                int right = source[i + 1];
                sumSquares += (long) left * left + (long) right * right;
                if (Math.abs(left) <= 1 && Math.abs(right) <= 1) {
                    quietPairs++;
                }
                if (left == 0 && right == 0) {
                    zeroPairs++;
                }
            }
            if (firstNonZeroFrame < 0 && (zeroPairs != pairs)) {
                firstNonZeroFrame = frame;
            }

            double rms = Math.sqrt((double) sumSquares / source.length);
            minRms = Math.min(minRms, rms);
            maxRms = Math.max(maxRms, rms);
            samplePairs += pairs;
            quietSamplePairs += quietPairs;
            zeroSamplePairs += zeroPairs;
            capturedFrames++;

            if (quietPairs == pairs) {
                quietRunFrames++;
                maxQuietRunFrames = Math.max(maxQuietRunFrames, quietRunFrames);
            } else {
                quietRunFrames = 0;
            }
        }

        private boolean isComplete() {
            return capturedFrames >= measurementFrames;
        }

        private void printReport() {
            if (receivedFrames < warmupFrames + measurementFrames) {
                throw new IllegalStateException("Audio events missing: received " + receivedFrames
                        + ", expected at least " + (warmupFrames + measurementFrames));
            }
            if (capturedFrames != measurementFrames) {
                throw new IllegalStateException("Audio measurement frames: " + capturedFrames
                        + ", expected " + measurementFrames);
            }

            System.out.printf("Audio frames: %d%n", capturedFrames);
            System.out.printf("First non-zero audio frame: %d%n", firstNonZeroFrame);
            System.out.printf("Audio sample pairs: %d%n", samplePairs);
            System.out.printf("Audio PCM SHA-256: %s%n", toHex(digest.digest()));
            System.out.printf("Audio min/max RMS: %.3f / %.3f%n", minRms, maxRms);
            System.out.printf("Quiet sample pairs: %d (%.3f%%)%n", quietSamplePairs,
                    quietSamplePairs * 100.0 / samplePairs);
            System.out.printf("Zero sample pairs: %d (%.3f%%)%n", zeroSamplePairs,
                    zeroSamplePairs * 100.0 / samplePairs);
            System.out.printf("Max fully-quiet frame run: %d%n", maxQuietRunFrames);
        }

        private boolean containsNonZero(int[] source) {
            for (int sample : source) {
                if (sample != 0) {
                    return true;
                }
            }
            return false;
        }

        private static String toHex(byte[] bytes) {
            char[] digits = "0123456789abcdef".toCharArray();
            char[] encoded = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                int value = bytes[i] & 0xff;
                encoded[i * 2] = digits[value >>> 4];
                encoded[i * 2 + 1] = digits[value & 0x0f];
            }
            return new String(encoded);
        }
    }
}
