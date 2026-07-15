package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.core.Gameboy;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class AudioResamplerTest {

    private static final int TRANSFER_AMPLITUDE = 1_000_000;

    @Test
    public void hasExpectedCombinedPassbandAndRejectsNoiseClocks() {
        assertEquals(-0.03, measureGainDb(1_000), 0.08);
        assertEquals(-3.36, measureGainDb(10_000), 0.15);
        assertEquals(-10.44, measureGainDb(15_000), 0.20);
        assertEquals(-21.09, measureGainDb(18_000), 0.25);

        assertTrue(measureGainDb(26_214.4) < -80);
        assertTrue(measureGainDb(37_449) < -80);
        assertTrue(measureGainDb(65_536) < -80);
    }

    @Test
    public void producesExactRateAfterFixedStartupLatency() {
        AudioResampler resampler = new AudioResampler();

        long firstSecond = processSilence(resampler, Gameboy.TICKS_PER_SEC);
        long secondSecond = processSilence(resampler, Gameboy.TICKS_PER_SEC);

        assertEquals(44_082, firstSecond);
        assertEquals(AudioResampler.OUTPUT_RATE, secondSecond);
    }

    @Test
    public void frameBoundariesDoNotChangeOutput() {
        int frames = Gameboy.TICKS_PER_FRAME * 3 + 37;
        int[] input = new int[frames * 2];
        for (int i = 0; i < frames; i++) {
            input[i * 2] = i * 31 % 961 - 480;
            input[i * 2 + 1] = i * 47 % 961 - 480;
        }

        AudioResampler contiguousResampler = new AudioResampler();
        double[] contiguous = new double[AudioResampler.maxOutputFrames(frames) * 2];
        int contiguousFrames = contiguousResampler.resample(input, contiguous);

        AudioResampler chunkedResampler = new AudioResampler();
        double[] chunked = new double[contiguous.length];
        int[] chunkSizes = {1, 7, 16, 31, 503, Gameboy.TICKS_PER_FRAME};
        int inputPosition = 0;
        int outputPosition = 0;
        int chunkIndex = 0;
        while (inputPosition < frames) {
            int chunkFrames = Math.min(chunkSizes[chunkIndex++ % chunkSizes.length],
                    frames - inputPosition);
            int[] chunk = Arrays.copyOfRange(input, inputPosition * 2,
                    (inputPosition + chunkFrames) * 2);
            double[] chunkOutput =
                    new double[AudioResampler.maxOutputFrames(chunkFrames) * 2];
            int outputFrames = chunkedResampler.resample(chunk, chunkOutput);
            System.arraycopy(chunkOutput, 0, chunked, outputPosition * 2,
                    outputFrames * 2);
            inputPosition += chunkFrames;
            outputPosition += outputFrames;
        }

        assertEquals(contiguousFrames, outputPosition);
        assertArrayEquals(Arrays.copyOf(contiguous, contiguousFrames * 2),
                Arrays.copyOf(chunked, outputPosition * 2), 0.0);
    }

    @Test
    public void keepsStereoChannelsIndependent() {
        int frames = Gameboy.TICKS_PER_FRAME;
        int[] leftInput = new int[frames * 2];
        int[] rightInput = new int[frames * 2];
        for (int i = 0; i < frames; i++) {
            int value = i * 37 % 961 - 480;
            leftInput[i * 2] = value;
            rightInput[i * 2 + 1] = value;
        }

        int bound = AudioResampler.maxOutputFrames(frames);
        double[] leftOutput = new double[bound * 2];
        double[] rightOutput = new double[bound * 2];
        int leftFrames = new AudioResampler().resample(leftInput, leftOutput);
        int rightFrames = new AudioResampler().resample(rightInput, rightOutput);

        assertEquals(leftFrames, rightFrames);
        double energy = 0;
        for (int i = 0; i < leftFrames; i++) {
            assertEquals(leftOutput[i * 2], rightOutput[i * 2 + 1], 0.0);
            assertEquals(0.0, leftOutput[i * 2 + 1], 0.0);
            assertEquals(0.0, rightOutput[i * 2], 0.0);
            energy += leftOutput[i * 2] * leftOutput[i * 2];
        }
        assertTrue(energy > 1);
    }

    @Test
    public void checksOutputCapacityBeforeConsumingInput() {
        int frames = Gameboy.TICKS_PER_FRAME;
        int bound = AudioResampler.maxOutputFrames(frames);
        int[] input = new int[frames * 2];
        for (int i = 0; i < input.length; i++) {
            input[i] = i % 127 - 63;
        }

        AudioResampler checked = new AudioResampler();
        assertThrows(IllegalArgumentException.class,
                () -> checked.resample(input, new double[(bound - 1) * 2]));
        double[] checkedOutput = new double[bound * 2];
        int checkedFrames = checked.resample(input, checkedOutput);

        double[] freshOutput = new double[bound * 2];
        int freshFrames = new AudioResampler().resample(input, freshOutput);

        assertTrue(checkedFrames <= bound);
        assertEquals(freshFrames, checkedFrames);
        assertArrayEquals(freshOutput, checkedOutput, 0.0);
    }

    @Test
    public void clampsPcmInsteadOfWrappingOnFilterOvershoot() {
        assertEquals(Short.MAX_VALUE, AudioSystemSound.clampToPcm16(40_000));
        assertEquals(Short.MIN_VALUE, AudioSystemSound.clampToPcm16(-40_000));
        assertEquals(12_345, AudioSystemSound.clampToPcm16(12_345.9));
    }

    private static long processSilence(AudioResampler resampler, int inputFrames) {
        long outputFrames = 0;
        int remaining = inputFrames;
        while (remaining > 0) {
            int frames = Math.min(Gameboy.TICKS_PER_FRAME, remaining);
            int[] input = new int[frames * 2];
            double[] output = new double[AudioResampler.maxOutputFrames(frames) * 2];
            outputFrames += resampler.resample(input, output);
            remaining -= frames;
        }
        return outputFrames;
    }

    private static double measureGainDb(double frequency) {
        AudioResampler resampler = new AudioResampler();
        int totalTicks = Gameboy.TICKS_PER_SEC / 4;
        int processedTicks = 0;
        long outputIndex = 0;
        double outputEnergy = 0;
        long measuredSamples = 0;
        int warmupSamples = AudioResampler.OUTPUT_RATE / 50;

        while (processedTicks < totalTicks) {
            int frames = Math.min(Gameboy.TICKS_PER_FRAME, totalTicks - processedTicks);
            int[] input = new int[frames * 2];
            for (int i = 0; i < frames; i++) {
                long tick = (long) processedTicks + i;
                int value = (int) Math.round(TRANSFER_AMPLITUDE * Math.sin(
                        2 * Math.PI * frequency * tick / Gameboy.TICKS_PER_SEC));
                input[i * 2] = value;
                input[i * 2 + 1] = value;
            }
            double[] output = new double[AudioResampler.maxOutputFrames(frames) * 2];
            int outputFrames = resampler.resample(input, output);
            for (int i = 0; i < outputFrames; i++, outputIndex++) {
                if (outputIndex >= warmupSamples) {
                    outputEnergy += output[i * 2] * output[i * 2];
                    measuredSamples++;
                }
            }
            processedTicks += frames;
        }

        double outputRms = Math.sqrt(outputEnergy / measuredSamples);
        double inputRms = TRANSFER_AMPLITUDE / Math.sqrt(2);
        return 20 * Math.log10(outputRms / inputRms);
    }
}
