package eu.rekawek.coffeegb.swing.io;

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
}
