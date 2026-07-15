package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.core.Gameboy;

/**
 * Streaming stereo resampler for the Game Boy's per-tick mixer output.
 *
 * <p>Four short binomial FIR stages first reduce the input rate by 16. A
 * polyphase Kaiser-windowed sinc then performs the fractional conversion from
 * 262144 Hz to 44100 Hz. Keeping the sharp filter before the final sampling
 * step prevents ultrasonic APU output from folding back into the audible band.
 * All history is retained between calls, so frame-sized event buffers do not
 * introduce seams.</p>
 *
 * <p>The intermediate moving average preserves the treble roll-off of the old
 * two-output-period box resampler. The final nine-tap filter is a separate,
 * global output EQ calibrated against the affected scene's reference render.
 * Neither filter is relied on for anti-aliasing.</p>
 */
final class AudioResampler {

    static final int OUTPUT_RATE = 44_100;

    private static final int DECIMATION = 16;

    private static final int FILTER_RATE = Gameboy.TICKS_PER_SEC / DECIMATION;

    private static final int TAPS = 223;

    private static final int RADIUS = TAPS / 2;

    private static final int PHASES = 1024;

    private static final int RING_SIZE = 256;

    private static final int RING_MASK = RING_SIZE - 1;

    private static final int RATE_GCD = gcd(FILTER_RATE, OUTPUT_RATE);

    private static final int POSITION_DENOMINATOR = OUTPUT_RATE / RATE_GCD;

    private static final int POSITION_STEP = FILTER_RATE / RATE_GCD;

    private static final double CUTOFF = 19_000.0;

    private static final double KAISER_BETA = 7.86;

    private static final float[][] COEFFICIENTS = createCoefficients();

    private final BinomialDecimator decimator1 = new BinomialDecimator();

    private final BinomialDecimator decimator2 = new BinomialDecimator();

    private final BinomialDecimator decimator3 = new BinomialDecimator();

    private final BinomialDecimator decimator4 = new BinomialDecimator();

    private final IntermediateBoxFilter boxFilter = new IntermediateBoxFilter();

    private final OutputTrebleFilter trebleFilter = new OutputTrebleFilter();

    private final double[] ringL = new double[RING_SIZE];

    private final double[] ringR = new double[RING_SIZE];

    private long latestInputIndex = -1;

    private long nextOutputPosition;

    /**
     * Returns a conservative output-frame bound for one input call. The bound
     * is independent of the resampler's current fractional phase.
     */
    static int maxOutputFrames(int inputFrames) {
        if (inputFrames <= 0) {
            return 0;
        }
        long filteredFrames = (inputFrames + DECIMATION - 1L) / DECIMATION;
        return (int) ((filteredFrames * OUTPUT_RATE + FILTER_RATE - 1) / FILTER_RATE + 1);
    }

    /**
     * Resamples interleaved stereo input into interleaved stereo doubles and
     * returns the number of output frames written.
     */
    int resample(int[] input, double[] output) {
        if ((input.length & 1) != 0) {
            throw new IllegalArgumentException("Stereo input must contain pairs");
        }
        int inputFrames = input.length / 2;
        if (output.length / 2 < maxOutputFrames(inputFrames)) {
            throw new IllegalArgumentException("Output buffer is too small");
        }

        int outputFrames = 0;
        for (int i = 0; i < input.length; i += 2) {
            if (!decimator1.add(input[i], input[i + 1])) {
                continue;
            }
            if (!decimator2.add(decimator1.left, decimator1.right)) {
                continue;
            }
            if (!decimator3.add(decimator2.left, decimator2.right)) {
                continue;
            }
            if (!decimator4.add(decimator3.left, decimator3.right)) {
                continue;
            }

            boxFilter.add(decimator4.left, decimator4.right);
            latestInputIndex++;
            int ringIndex = (int) latestInputIndex & RING_MASK;
            ringL[ringIndex] = boxFilter.left;
            ringR[ringIndex] = boxFilter.right;

            while (canProduce()) {
                long center = roundedOutputCenter();
                long remainder = nextOutputPosition - center * POSITION_DENOMINATOR;
                long phaseNumerator =
                        (remainder + POSITION_DENOMINATOR / 2L) * (PHASES - 1L);
                int phase = (int) ((phaseNumerator + POSITION_DENOMINATOR / 2)
                        / POSITION_DENOMINATOR);
                float[] coefficients = COEFFICIENTS[phase];

                double sumL = 0;
                double sumR = 0;
                for (int tap = 0; tap < TAPS; tap++) {
                    int sourceIndex = (int) (center + tap - RADIUS) & RING_MASK;
                    double coefficient = coefficients[tap];
                    sumL += ringL[sourceIndex] * coefficient;
                    sumR += ringR[sourceIndex] * coefficient;
                }

                trebleFilter.add(sumL, sumR);
                output[outputFrames * 2] = trebleFilter.left;
                output[outputFrames * 2 + 1] = trebleFilter.right;
                outputFrames++;
                nextOutputPosition += POSITION_STEP;
            }
        }
        return outputFrames;
    }

    private boolean canProduce() {
        return latestInputIndex >= roundedOutputCenter() + RADIUS;
    }

    private long roundedOutputCenter() {
        return (nextOutputPosition + POSITION_DENOMINATOR / 2) / POSITION_DENOMINATOR;
    }

    private static float[][] createCoefficients() {
        float[][] result = new float[PHASES][TAPS];
        double i0Beta = besselI0(KAISER_BETA);
        for (int phase = 0; phase < PHASES; phase++) {
            double fraction = -0.5 + (double) phase / (PHASES - 1);
            double sum = 0;
            for (int tap = 0; tap < TAPS; tap++) {
                double x = tap - RADIUS - fraction;
                double normalized = x / (RADIUS + 0.5);
                double window = besselI0(KAISER_BETA
                        * Math.sqrt(Math.max(0, 1 - normalized * normalized))) / i0Beta;
                double coefficient = x == 0
                        ? 2 * CUTOFF / FILTER_RATE
                        : Math.sin(2 * Math.PI * CUTOFF * x / FILTER_RATE) / (Math.PI * x);
                coefficient *= window;
                result[phase][tap] = (float) coefficient;
                sum += coefficient;
            }
            for (int tap = 0; tap < TAPS; tap++) {
                result[phase][tap] /= sum;
            }
        }
        return result;
    }

    /** Cephes approximation, accurate well beyond the coefficient precision. */
    private static double besselI0(double x) {
        double ax = Math.abs(x);
        if (ax < 3.75) {
            double y = x / 3.75;
            y *= y;
            return 1 + y * (3.5156229 + y * (3.0899424 + y * (1.2067492
                    + y * (0.2659732 + y * (0.0360768 + y * 0.0045813)))));
        }
        double y = 3.75 / ax;
        return Math.exp(ax) / Math.sqrt(ax) * (0.39894228 + y * (0.01328592
                + y * (0.00225319 + y * (-0.00157565 + y * (0.00916281
                + y * (-0.02057706 + y * (0.02635537 + y * (-0.01647633
                + y * 0.00392377))))))));
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }

    /** Two cascaded [1, 2, 1] stages: [1, 4, 6, 4, 1] / 16. */
    private static final class BinomialDecimator {
        private double previousL1;
        private double previousL2;
        private double previousL3;
        private double previousL4;
        private double previousR1;
        private double previousR2;
        private double previousR3;
        private double previousR4;
        private boolean phase;
        private double left;
        private double right;

        private boolean add(double inputL, double inputR) {
            phase = !phase;
            double outputL = (inputL + 4 * previousL1 + 6 * previousL2
                    + 4 * previousL3 + previousL4) / 16;
            double outputR = (inputR + 4 * previousR1 + 6 * previousR2
                    + 4 * previousR3 + previousR4) / 16;
            previousL4 = previousL3;
            previousL3 = previousL2;
            previousL2 = previousL1;
            previousL1 = inputL;
            previousR4 = previousR3;
            previousR3 = previousR2;
            previousR2 = previousR1;
            previousR1 = inputR;
            if (phase) {
                return false;
            }
            left = outputL;
            right = outputR;
            return true;
        }
    }

    /** Twelve intermediate samples closely match two 44100-Hz output periods. */
    private static final class IntermediateBoxFilter {
        private static final int LENGTH = 12;

        private final double[] historyL = new double[LENGTH];
        private final double[] historyR = new double[LENGTH];
        private int index;
        private double sumL;
        private double sumR;
        private double left;
        private double right;

        private void add(double inputL, double inputR) {
            sumL += inputL - historyL[index];
            sumR += inputR - historyR[index];
            historyL[index] = inputL;
            historyR[index] = inputR;
            index = (index + 1) % LENGTH;
            left = sumL / LENGTH;
            right = sumR / LENGTH;
        }
    }

    /** Nine-tap 18250-Hz Kaiser (beta 5) global reference-scene EQ. */
    private static final class OutputTrebleFilter {
        private static final double[] COEFFICIENTS = {
                -0.00241712842839, 0.024401785367, -0.0776401852892,
                0.142243870104, 0.826823316494, 0.142243870104,
                -0.0776401852892, 0.024401785367, -0.00241712842839
        };

        private final double[] historyL = new double[COEFFICIENTS.length];
        private final double[] historyR = new double[COEFFICIENTS.length];
        private int index;
        private double left;
        private double right;

        private void add(double inputL, double inputR) {
            historyL[index] = inputL;
            historyR[index] = inputR;
            left = 0;
            right = 0;
            for (int tap = 0; tap < COEFFICIENTS.length; tap++) {
                int sourceIndex = (index - tap + COEFFICIENTS.length) % COEFFICIENTS.length;
                left += historyL[sourceIndex] * COEFFICIENTS[tap];
                right += historyR[sourceIndex] * COEFFICIENTS[tap];
            }
            index = (index + 1) % COEFFICIENTS.length;
        }
    }
}
