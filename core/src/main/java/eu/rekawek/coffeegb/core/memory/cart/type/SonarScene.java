package eu.rekawek.coffeegb.core.memory.cart.type;

import java.util.Arrays;

/** Immutable, repeating sonar echo field. Rows represent successive samples of a pulse. */
public final class SonarScene {
    public static final int WIDTH = 160;
    public static final int HEIGHT = 192;
    private final byte[] samples;

    public SonarScene(byte[] samples) {
        if (samples == null || samples.length != WIDTH * HEIGHT) {
            throw new IllegalArgumentException("Sonar scenes must contain 160 x 192 samples");
        }
        this.samples = samples.clone();
        for (byte sample : this.samples) {
            if (sample < 0 || sample > 7) {
                throw new IllegalArgumentException("Sonar samples must be between 0 and 7");
            }
        }
    }

    public byte[] copySamples() { return samples.clone(); }

    public static SonarScene openWater() {
        byte[] samples = new byte[WIDTH * HEIGHT];
        Arrays.fill(samples, (byte) 7);
        return new SonarScene(samples);
    }

    public static SonarScene demo() {
        byte[] samples = openWater().copySamples();
        for (int x = 0; x < WIDTH; x++) {
            int floor = 64 + Math.abs(x - 80) / 8;
            samples[floor * WIDTH + x] = 0;
            samples[(floor + 1) * WIDTH + x] = 0;
            if (x % 40 < 8) samples[(28 + (x / 40) * 6) * WIDTH + x] = 1;
        }
        return new SonarScene(samples);
    }
}
