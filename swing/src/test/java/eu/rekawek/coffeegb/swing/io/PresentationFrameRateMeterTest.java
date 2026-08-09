package eu.rekawek.coffeegb.swing.io;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PresentationFrameRateMeterTest {

    @Test
    public void reportsOnlyAfterTheLowFrequencySampleWindow() {
        AtomicLong now = new AtomicLong();
        PresentationFrameRateMeter meter = new PresentationFrameRateMeter(now::get);

        for (int frame = 0; frame < 59; frame++) {
            now.addAndGet(16_666_667L);
            assertTrue(Double.isNaN(meter.framePublished()));
        }

        now.set(PresentationFrameRateMeter.SAMPLE_WINDOW_NANOS);
        assertEquals(60.0, meter.framePublished(), 0.001);
    }

    @Test
    public void resetDropsStaleFramesAcrossPauseOrSessionReplacement() {
        AtomicLong now = new AtomicLong();
        PresentationFrameRateMeter meter = new PresentationFrameRateMeter(now::get);
        for (int frame = 0; frame < 30; frame++) {
            meter.framePublished();
        }

        now.set(2_000_000_000L);
        meter.reset();
        now.addAndGet(PresentationFrameRateMeter.SAMPLE_WINDOW_NANOS);
        assertEquals(1.0, meter.framePublished(), 0.001);
    }
}
