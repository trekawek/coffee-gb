package eu.rekawek.coffeegb.core.sound;

/**
 * First-order high-pass filter modelling the Game Boy output capacitor. Kept separate so desktop
 * and Android conversion share both its stateful behavior and direct regression coverage.
 */
final class DcBlocker {

    private final double pole;
    private double previousInput;
    private double previousOutput;

    DcBlocker(double sampleRate, double cutoff) {
        pole = Math.exp(-2.0 * Math.PI * cutoff / sampleRate);
    }

    double filter(double input) {
        double output = input - previousInput + pole * previousOutput;
        previousInput = input;
        previousOutput = output;
        return output;
    }

    void reset() {
        previousInput = 0;
        previousOutput = 0;
    }
}
