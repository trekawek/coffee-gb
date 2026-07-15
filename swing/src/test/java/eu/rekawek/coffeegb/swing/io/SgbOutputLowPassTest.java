package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.core.GameboyType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SgbOutputLowPassTest {

    private static final int SAMPLE_RATE = 44_100;

    @Test
    public void leavesHandheldOutputUnchanged() {
        SgbOutputLowPass filter = new SgbOutputLowPass(SAMPLE_RATE);
        double[] inputs = {-480.0, -1.25, 0.0, 42.5, 480.0};

        filter.filter(480.0, GameboyType.SGB);
        for (GameboyType type : new GameboyType[]{GameboyType.DMG, GameboyType.CGB}) {
            for (double input : inputs) {
                assertEquals(input, filter.filter(input, type), 0.0);
            }
        }
    }

    @Test
    public void sgbStepReachesOneBoardTimeConstant() {
        SgbOutputLowPass filter = new SgbOutputLowPass(SAMPLE_RATE);
        double timeConstant =
                SgbOutputLowPass.RESISTANCE_OHMS * SgbOutputLowPass.CAPACITANCE_FARADS;
        int timeConstantSamples = (int) Math.round(SAMPLE_RATE * timeConstant);

        double output = 0;
        for (int i = 0; i < timeConstantSamples; i++) {
            output = filter.filter(1.0, GameboyType.SGB);
        }

        assertEquals(1.0 - Math.exp(-1.0), output, 0.002);
    }

    @Test
    public void sgbIsThreeDbDownAtBoardCutoff() {
        SgbOutputLowPass filter = new SgbOutputLowPass(SAMPLE_RATE);
        double timeConstant =
                SgbOutputLowPass.RESISTANCE_OHMS * SgbOutputLowPass.CAPACITANCE_FARADS;
        double cutoff = 1.0 / (2.0 * Math.PI * timeConstant);
        int warmup = SAMPLE_RATE / 10;
        double inputEnergy = 0;
        double outputEnergy = 0;

        for (int i = 0; i < SAMPLE_RATE; i++) {
            double input = Math.sin(2.0 * Math.PI * cutoff * i / SAMPLE_RATE);
            double output = filter.filter(input, GameboyType.SGB);
            if (i >= warmup) {
                inputEnergy += input * input;
                outputEnergy += output * output;
            }
        }

        assertEquals(1.0 / Math.sqrt(2.0), Math.sqrt(outputEnergy / inputEnergy), 0.02);
    }

    @Test
    public void resetDischargesTheFilter() {
        SgbOutputLowPass filter = new SgbOutputLowPass(SAMPLE_RATE);
        for (int i = 0; i < 100; i++) {
            filter.filter(1.0, GameboyType.SGB);
        }

        filter.reset();

        double timeConstant =
                SgbOutputLowPass.RESISTANCE_OHMS * SgbOutputLowPass.CAPACITANCE_FARADS;
        double expectedFirstSample = 1.0 - Math.exp(-1.0 / (SAMPLE_RATE * timeConstant));
        assertEquals(expectedFirstSample, filter.filter(1.0, GameboyType.SGB), 1e-12);
    }
}
