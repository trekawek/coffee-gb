package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GambatteHwTestRunnerTest {

    @Test
    public void intentionalPreResultSelfLoopIsNotTerminal() {
        Ram videoRam = new Ram(0x8000, 0x2000);
        for (int i = 0; i < 4; i++) {
            // A zero-filled result map can look like a valid hexadecimal result, but
            // the generated renderer has not copied its digit tiles yet.
            videoRam.setByte(0x9800 + i, 0x00);
        }

        assertFalse(GambatteHwTestRunner.isTerminalState(true, videoRam, 4));
    }

    @Test
    public void renderedHexOutputAndStableCpuStateAreTerminal() {
        Ram videoRam = new Ram(0x8000, 0x2000);
        videoRam.setByte(0x8002, 0x7f);
        videoRam.setByte(0x8003, 0x7f);
        videoRam.setByte(0x8004, 0x41);
        videoRam.setByte(0x8005, 0x41);
        videoRam.setByte(0x800e, 0x7f);
        videoRam.setByte(0x800f, 0x7f);
        videoRam.setByte(0x9800, 0x01);
        videoRam.setByte(0x9801, 0x0a);
        videoRam.setByte(0x9802, 0x0f);
        videoRam.setByte(0x9803, 0x00);

        assertFalse(GambatteHwTestRunner.isTerminalState(false, videoRam, 4));
        assertTrue(GambatteHwTestRunner.isTerminalState(true, videoRam, 4));
    }
}
