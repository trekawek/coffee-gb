package eu.rekawek.coffeegb.swing.io;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DcBlockerTest {

    private static final int SAMPLE_RATE = 44_100;

    private static final double CUTOFF = 28.0;

    @Test
    public void dcStepDecaysByOneTimeConstant() {
        DcBlocker filter = new DcBlocker(SAMPLE_RATE, CUTOFF);
        int timeConstantSamples = (int) Math.round(SAMPLE_RATE / (2.0 * Math.PI * CUTOFF));

        double output = 0;
        for (int i = 0; i < timeConstantSamples; i++) {
            output = filter.filter(1.0);
        }

        assertEquals(Math.exp(-1), output, 0.002);
    }

    @Test
    public void preservesMasterVolumePcmFrequencies() {
        DcBlocker filter = new DcBlocker(SAMPLE_RATE, CUTOFF);
        double inputEnergy = 0;
        double outputEnergy = 0;
        int warmup = SAMPLE_RATE / 10;

        for (int i = 0; i < SAMPLE_RATE; i++) {
            double input = Math.sin(2.0 * Math.PI * 200.0 * i / SAMPLE_RATE);
            double output = filter.filter(input);
            if (i >= warmup) {
                inputEnergy += input * input;
                outputEnergy += output * output;
            }
        }

        assertEquals(1.0, Math.sqrt(outputEnergy / inputEnergy), 0.02);
    }
}
