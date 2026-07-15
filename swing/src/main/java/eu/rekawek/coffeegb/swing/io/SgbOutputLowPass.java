package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.core.GameboyType;

/**
 * Models the RC low-pass on each audio output of the SGB-R-10 board. The SGB CPU's L/R outputs
 * pass through a 910 ohm resistor and have a 0.1 uF capacitor to ground before reaching the
 * SNES cartridge contacts. Handheld models do not use this output stage and pass through
 * unchanged.
 *
 * <p>Schematic: https://gbdev.gg8.se/wiki/images/4/41/Super-gameboy.gif
 */
final class SgbOutputLowPass {

    static final double RESISTANCE_OHMS = 910.0;

    static final double CAPACITANCE_FARADS = 0.1e-6;

    private final double pole;

    private double previousOutput;

    SgbOutputLowPass(double sampleRate) {
        double timeConstant = RESISTANCE_OHMS * CAPACITANCE_FARADS;
        pole = Math.exp(-1.0 / (sampleRate * timeConstant));
    }

    double filter(double input, GameboyType gameboyType) {
        if (gameboyType != GameboyType.SGB) {
            return input;
        }
        previousOutput = input + pole * (previousOutput - input);
        return previousOutput;
    }

    void reset() {
        previousOutput = 0;
    }
}
