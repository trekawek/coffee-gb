package eu.rekawek.coffeegb.core.sound;

import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import org.junit.Test;

import java.math.BigInteger;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StereoPcmConverterTest {

    private static final int SAMPLE_RATE = 44_100;

    @Test
    public void legacyDesktopPcmParityKeepsSampleOrderAndEndian() {
        StereoPcmConverter converter = new StereoPcmConverter(SAMPLE_RATE);
        int[] source = constantStereo(ClockSpec.LEGACY.controllerTicksPerFrame(), 480, -480);
        byte[] output = new byte[converter.maximumPcmBytes(source.length / 2, ClockSpec.LEGACY)];

        int written = converter.render(source, ClockSpec.LEGACY, 100, false, output);

        assertEquals(4 * 734, written);
        assertEquals(0x40, output[0] & 0xff);
        assertEquals(0x74, output[1] & 0xff);
        assertEquals(0xc0, output[2] & 0xff);
        assertEquals(0x8b, output[3] & 0xff);
    }

    @Test
    public void muteClippingAndDcBlockingAreStable() {
        StereoPcmConverter converter = new StereoPcmConverter(SAMPLE_RATE);
        int[] loud = constantStereo(ClockSpec.LEGACY.controllerTicksPerFrame(), 1_000_000, -1_000_000);
        byte[] pcm = new byte[converter.maximumPcmBytes(loud.length / 2, ClockSpec.LEGACY)];

        int written = converter.render(loud, ClockSpec.LEGACY, 100, false, pcm);
        assertEquals(Short.MAX_VALUE, littleEndianShort(pcm, 0));
        assertEquals(Short.MIN_VALUE, littleEndianShort(pcm, 2));

        Arrays.fill(pcm, (byte) 0x7f);
        converter.render(loud, ClockSpec.LEGACY, 0, true, pcm);
        for (int offset = 0; offset < written; offset++) {
            assertEquals(0, pcm[offset]);
        }

        StereoPcmConverter dc = new StereoPcmConverter(SAMPLE_RATE);
        int[] constant = constantStereo(ClockSpec.LEGACY.controllerTicksPerFrame(), 480, 480);
        byte[] settled = new byte[dc.maximumPcmBytes(constant.length / 2, ClockSpec.LEGACY)];
        for (int i = 0; i < 8; i++) {
            dc.render(constant, ClockSpec.LEGACY, 100, false, settled);
        }
        assertTrue(Math.abs(littleEndianShort(settled, settled.length - 4)) < 1_500);
    }

    @Test
    public void fractionalResamplerPhaseContinuesAcrossEventBuffers() {
        StereoPcmConverter converter = new StereoPcmConverter(SAMPLE_RATE);
        ClockSpec clock = ClockSpec.SGB2;
        int[] first = constantStereo(43, 480, 480);
        byte[] firstPcm = new byte[converter.maximumPcmBytes(43, clock)];
        converter.render(first, clock, 100, false, firstPcm);
        long phaseBefore = converter.samplePhase();

        int[] second = constantStereo(67, 480, 480);
        byte[] secondPcm = new byte[converter.maximumPcmBytes(67, clock)];
        int written = converter.render(second, clock, 100, false, secondPcm);

        BigInteger numerator = BigInteger.valueOf(110L).multiply(BigInteger.valueOf(SAMPLE_RATE));
        BigInteger[] expected = numerator.divideAndRemainder(
                BigInteger.valueOf(clock.ticksPerSecondNumerator()));
        assertTrue(phaseBefore != 0);
        assertEquals(expected[0].longValueExact() * 4, written);
        assertEquals(expected[1].longValueExact(), converter.samplePhase());
    }

    private static int[] constantStereo(int ticks, int left, int right) {
        int[] samples = new int[ticks * 2];
        for (int tick = 0; tick < ticks; tick++) {
            samples[tick * 2] = left;
            samples[tick * 2 + 1] = right;
        }
        return samples;
    }

    private static short littleEndianShort(byte[] bytes, int offset) {
        return (short) ((bytes[offset] & 0xff) | (bytes[offset + 1] << 8));
    }
}
